package com.fongmi.android.tv.utils;

import android.text.TextUtils;

import com.fongmi.android.tv.api.Decoder;
import com.fongmi.android.tv.api.parser.LiveParser;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.bean.Channel;
import com.fongmi.android.tv.bean.Depot;
import com.fongmi.android.tv.bean.Group;
import com.fongmi.android.tv.bean.Live;
import com.fongmi.android.tv.bean.Site;
import com.github.catvod.utils.Json;
import com.github.catvod.utils.Path;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 将多个 TVBox 单仓/多仓源合并为一个单仓配置。
 * 点播：合并 sites，按 key 去重；多仓(depot)递归展开子仓。
 * 直播：合并 groups/channels，组内同名频道合并线路；多仓递归展开。
 * 产物始终是单仓 JSON，写入本地文件后以 file:// 注册为一条普通配置。
 */
public class ConfigMerger {

    private static final String TAG = ConfigMerger.class.getSimpleName();

    public interface ProgressCallback {

        void onProgress(int index, int total);

        // 源内细粒度进度：每解析出一个站点/频道回调一次，供上层平滑推进进度条
        default void onSite(int sourceIndex, int siteCount, String siteName) {
        }
    }

    private static final ProgressCallback NO_OP = (index, total) -> {
    };

    public static final class Result {

        public final JsonObject json;
        public final int count;
        public final int failed;
        public final List<String> failedUrls;

        private Result(JsonObject json, int count, int failed, List<String> failedUrls) {
            this.json = json;
            this.count = count;
            this.failed = failed;
            this.failedUrls = failedUrls;
        }
    }

    public static Result mergeVod(List<String> urls, boolean keepAll) {
        return mergeVod(urls, keepAll, NO_OP);
    }

    public static Result mergeVod(List<String> urls, boolean keepAll, ProgressCallback callback) {
        List<String> failed = new ArrayList<>();
        JsonArray sites = new JsonArray();
        Set<String> keys = new LinkedHashSet<>();
        Set<String> spiders = new LinkedHashSet<>();
        int total = urls.size();
        for (int i = 0; i < urls.size(); i++) {
            final int index = i;
            collectVod(urls.get(i), sites, keys, spiders, keepAll, failed, count -> callback.onSite(index + 1, count, ""));
            callback.onProgress(i + 1, total);
        }
        JsonObject result = new JsonObject();
        if (spiders.size() == 1) result.addProperty("spider", spiders.iterator().next());
        result.add("sites", sites);
        return new Result(result, sites.size(), failed.size(), failed);
    }

    public static Result mergeLive(String name, List<String> urls, boolean keepAll) {
        return mergeLive(name, urls, keepAll, NO_OP);
    }

    public static Result mergeLive(String name, List<String> urls, boolean keepAll, ProgressCallback callback) {
        List<String> failed = new ArrayList<>();
        Map<String, Group> groups = new LinkedHashMap<>();
        int total = urls.size();
        for (int i = 0; i < urls.size(); i++) {
            final int index = i;
            collectLive(urls.get(i), groups, keepAll, failed, count -> callback.onSite(index + 1, count, ""));
            callback.onProgress(i + 1, total);
        }
        JsonObject result = new JsonObject();
        JsonArray lives = new JsonArray();
        JsonObject entry = new JsonObject();
        entry.addProperty("name", name);
        JsonArray groupArr = new JsonArray();
        int count = 0;
        for (Group group : groups.values()) {
            JsonObject go = new JsonObject();
            go.addProperty("name", group.getName());
            if (!group.getPass().isEmpty()) go.addProperty("pass", group.getPass());
            JsonArray chs = new JsonArray();
            for (Channel channel : group.getChannel()) {
                chs.add(channelJson(channel));
                count++;
            }
            go.add("channel", chs);
            groupArr.add(go);
        }
        entry.add("groups", groupArr);
        lives.add(entry);
        result.add("lives", lives);
        return new Result(result, count, failed.size(), failed);
    }

    public static String save(JsonObject json, String name) {
        String file = sanitize(name);
        // 合并标记置于产物头部：添加/再合并时仅需读文件头即可识别为本软件合并源并免检
        JsonObject marked = new JsonObject();
        marked.addProperty("xwgui_merged", 1);
        for (Map.Entry<String, JsonElement> entry : json.entrySet()) marked.add(entry.getKey(), entry.getValue());
        Path.write(Path.root("TV", file + ".json"), marked.toString().getBytes(StandardCharsets.UTF_8));
        return "file://TV/" + file + ".json";
    }

    private static JsonObject channelJson(Channel channel) {
        JsonObject co = new JsonObject();
        co.addProperty("name", channel.getName());
        JsonArray urls = new JsonArray();
        for (String url : channel.getUrls()) urls.add(url);
        co.add("urls", urls);
        if (!channel.getNumber().isEmpty()) co.addProperty("number", channel.getNumber());
        if (!channel.getLogo().isEmpty()) co.addProperty("logo", channel.getLogo());
        if (!channel.getEpg().isEmpty()) co.addProperty("epg", channel.getEpg());
        if (!channel.getUa().isEmpty()) co.addProperty("ua", channel.getUa());
        return co;
    }

    private static void collectVod(String url, JsonArray sites, Set<String> keys, Set<String> spiders, boolean keepAll, List<String> failed, SiteListener listener) {
        try {
            JsonObject object = Json.parse(Decoder.getJson(UrlUtil.convert(url), TAG)).getAsJsonObject();
            if (object.has("msg")) throw new Exception(object.get("msg").getAsString());
            if (object.has("urls")) {
                for (Depot depot : Depot.arrayFrom(object.getAsJsonArray("urls").toString())) {
                    if (depot == null || TextUtils.isEmpty(depot.getUrl())) continue;
                    collectVod(depot.getUrl(), sites, keys, spiders, keepAll, failed, listener);
                }
            } else {
                String spider = Json.safeString(object, "spider");
                // 合并期安全闸口：全局 spider 引用宿主自杀 API（System.exit/killProcess）→ 整个订阅源剔除不合并
                // 受「合并源安全检测」开关控制：关闭时不执行静态扫描；「杀进程中和」开启时放行（加载期 NOP 中和）
                if (Setting.isProbeMerge() && !Setting.isNeutralizeKill() && !spider.isEmpty() && com.fongmi.android.tv.api.SourceScanner.hasExitRef(spider)) {
                    failed.add(url);
                    return;
                }
                if (!spider.isEmpty()) spiders.add(spider);
                for (JsonElement element : Json.safeListElement(object, "sites")) {
                    Site site = Site.objectFrom(element, spider);
                    if (site == null || site.getKey().isEmpty()) continue;
                    if (!keepAll && !keys.add(site.getKey())) continue;
                    JsonObject item = element.getAsJsonObject().deepCopy();
                    // 站点自带 jar 引用自杀 API → 仅剔除该站点，保留源内其它站点
                    String siteJar = Json.safeString(item, "jar");
                    if (Setting.isProbeMerge() && !Setting.isNeutralizeKill() && !siteJar.isEmpty() && com.fongmi.android.tv.api.SourceScanner.hasExitRef(siteJar)) continue;
                    // Site.objectFrom 会把空 jar 填成 spider，故须检查原始 JSON 是否自带 jar
                    if (siteJar.isEmpty() && !spider.isEmpty()) item.addProperty("jar", spider);
                    sites.add(item);
                    if (listener != null) listener.onSite(sites.size());
                }
            }
        } catch (Exception e) {
            failed.add(url);
        }
    }

    private static void collectLive(String url, Map<String, Group> groups, boolean keepAll, List<String> failed, SiteListener listener) {
        try {
            String text = Decoder.getJson(UrlUtil.convert(url), TAG);
            if (Json.isObj(text)) {
                JsonObject object = Json.parse(text).getAsJsonObject();
                if (object.has("msg")) throw new Exception(object.get("msg").getAsString());
                if (object.has("urls")) {
                    for (Depot depot : Depot.arrayFrom(object.getAsJsonArray("urls").toString())) {
                        if (depot == null || TextUtils.isEmpty(depot.getUrl())) continue;
                        collectLive(depot.getUrl(), groups, keepAll, failed, listener);
                    }
                } else if (object.has("lives")) {
                    String spider = Json.safeString(object, "spider");
                    // 合并期安全闸口：全局 spider 引用宿主自杀 API → 整个订阅源剔除不合并
                    // 受「合并源安全检测」开关控制：关闭时不执行静态扫描；「杀进程中和」开启时放行（加载期 NOP 中和）
                    if (Setting.isProbeMerge() && !Setting.isNeutralizeKill() && !spider.isEmpty() && com.fongmi.android.tv.api.SourceScanner.hasExitRef(spider)) {
                        failed.add(url);
                        return;
                    }
                    for (JsonElement element : Json.safeListElement(object, "lives")) {
                        // lives 分组自带 jar 引用自杀 API → 仅剔除该分组
                        String groupJar = element.isJsonObject() ? Json.safeString(element.getAsJsonObject(), "jar") : "";
                        if (Setting.isProbeMerge() && !Setting.isNeutralizeKill() && !groupJar.isEmpty() && com.fongmi.android.tv.api.SourceScanner.hasExitRef(groupJar)) continue;
                        Live live = Live.objectFrom(element, spider);
                        collectLiveGroups(live, groups, keepAll);
                        if (listener != null) listener.onSite(countChannels(groups));
                    }
                } else if (!Json.safeString(object, "url").isEmpty()) {
                    Live live = new Live(Json.safeString(object, "name"), Json.safeString(object, "url"));
                    collectLiveGroups(live, groups, keepAll);
                    if (listener != null) listener.onSite(countChannels(groups));
                }
            } else if (!text.isEmpty()) {
                Live live = new Live(UrlUtil.getName(url), url);
                LiveParser.text(live, text);
                mergeGroups(groups, live.getGroups(), keepAll);
                if (listener != null) listener.onSite(countChannels(groups));
            }
        } catch (Exception e) {
            failed.add(url);
        }
    }

    private static int countChannels(Map<String, Group> groups) {
        int count = 0;
        for (Group group : groups.values()) count += group.getChannel().size();
        return count;
    }

    private interface SiteListener {
        void onSite(int count);
    }

    private static void collectLiveGroups(Live live, Map<String, Group> groups, boolean keepAll) {
        try {
            LiveParser.start(live);
        } catch (Exception ignored) {
        }
        mergeGroups(groups, live.getGroups(), keepAll);
    }

    private static void mergeGroups(Map<String, Group> groups, List<Group> incoming, boolean keepAll) {
        for (Group group : incoming) {
            if (group == null || group.getName().isEmpty()) continue;
            Group target = groups.get(group.getName());
            if (target == null) {
                target = new Group(group.getName(), false);
                if (!group.getPass().isEmpty()) target.setPass(group.getPass());
                groups.put(group.getName(), target);
            }
            for (Channel channel : group.getChannel()) {
                if (channel == null || channel.getName().isEmpty()) continue;
                if (keepAll) {
                    target.getChannel().add(Channel.create(channel));
                } else {
                    Channel exist = target.find(Channel.create(channel.getName()));
                    for (String url : channel.getUrls()) if (!exist.getUrls().contains(url)) exist.getUrls().add(url);
                    if (exist.getLogo().isEmpty() && !channel.getLogo().isEmpty()) exist.setLogo(channel.getLogo());
                    if (exist.getNumber().isEmpty() && !channel.getNumber().isEmpty()) exist.setNumber(channel.getNumber());
                    if (exist.getEpg().isEmpty() && !channel.getEpg().isEmpty()) exist.setEpg(channel.getEpg());
                    if (exist.getUa().isEmpty() && !channel.getUa().isEmpty()) exist.setUa(channel.getUa());
                }
            }
        }
    }

    private static String sanitize(String name) {
        String file = name.trim().replaceAll("[\\\\/:*?\"<>|\\s]+", "_");
        return TextUtils.isEmpty(file) ? "merged" : file;
    }
}
