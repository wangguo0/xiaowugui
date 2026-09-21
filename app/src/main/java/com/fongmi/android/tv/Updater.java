package com.fongmi.android.tv;

import android.os.SystemClock;
import android.text.TextUtils;
import android.view.View;

import androidx.fragment.app.FragmentActivity;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleEventObserver;

import com.fongmi.android.tv.bean.Update;
import com.fongmi.android.tv.impl.UpdateListener;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.ui.dialog.DownloadLineDialog;
import com.fongmi.android.tv.ui.dialog.UpdateDialog;
import com.fongmi.android.tv.utils.AppVersion;
import com.fongmi.android.tv.utils.DiagLog;
import com.fongmi.android.tv.utils.Download;
import com.fongmi.android.tv.utils.FileUtil;
import com.fongmi.android.tv.utils.ForcePolicy;
import com.fongmi.android.tv.utils.Github;
import com.fongmi.android.tv.utils.GithubProxy;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.utils.Task;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Path;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.lang.ref.WeakReference;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Updater implements Download.Callback, UpdateListener {

    private static final String DEFAULT_RELEASE_NOTES = "手动触发 GitHub Actions 构建发布。";
    private static final String SOURCE_GITHUB = "github";
    // 诊断日志标签：更新全链路（检查/闸门/探测/选线/下载/校验）统一用该标签，便于在日志页串成一条时间线
    private static final String LOG = "update";
    private static final long UPDATE_CHECK_TIMEOUT_MS = TimeUnit.SECONDS.toMillis(10);
    private static final long GITHUB_REQUEST_TIMEOUT_MS = TimeUnit.SECONDS.toMillis(4);
    // 直连 API 失败后的降级预算：动态镜像（最多 24 条）+ 直连共 25 源并发拉清单取最新
    private static final long FALLBACK_COLLECT_MS = TimeUnit.SECONDS.toMillis(6);
    // tag 形如「小乌龟1.2-202609201200」：版本号 + 可选构建时间戳
    private static final Pattern TAG_VERSION = Pattern.compile("(\\d+(?:\\.\\d+)+)");
    private static final Pattern TAG_TIME = Pattern.compile("(\\d{12})");
    private static final Map<String, String> GITHUB_API_HEADERS = Map.of("Accept", "application/vnd.github+json", "X-GitHub-Api-Version", "2022-11-28");
    private static final Map<String, String> GITHUB_ASSET_HEADERS = Map.of("Accept", "application/octet-stream", "X-GitHub-Api-Version", "2022-11-28");
    private static final Updater INSTANCE = new Updater();

    private final LifecycleEventObserver lifecycleObserver = (source, event) -> {
        if (!(source instanceof FragmentActivity)) return;
        FragmentActivity activity = (FragmentActivity) source;
        if (event == Lifecycle.Event.ON_DESTROY) unbind(activity);
    };

    private WeakReference<FragmentActivity> activityRef;
    private UpdateDialog dialog;
    private Download download;
    private Update stable;
    private Update beta;
    private Update selected;
    private boolean force;
    private boolean downloading;
    private boolean canceled;
    private volatile boolean launchChecked;
    private volatile boolean launchChecking;
    private volatile long lastLaunchCheckAt;
    private boolean forceMode;
    private String forceMsg = "";
    private GithubProxy.Line currentLine;
    private boolean retriedDirect;
    private boolean retriedLatest;
    private Snapshot snapshot;
    private int lastProgress = -1;
    private long lastBytes;
    private long lastTotal;
    private long lastSpeed;
    private long lastElapsed;

    private Updater() {
    }

    // 下载期冻结的清单快照：检查结果即使被后续静默刷新覆盖，本次下载与核对对象也不会脱节
    private static final class Snapshot {

        private final String asset;
        private final String url;
        private final String sha256;
        private final long size;
        private final long code;

        Snapshot(Update update, String asset) {
            this.asset = asset;
            this.url = update.apkUrl;
            this.sha256 = update.sha256;
            this.size = update.size;
            this.code = update.code;
        }
    }

    public static Updater create() {
        return INSTANCE;
    }

    private File getFile() {
        return Path.cache("update.apk");
    }

    // 发布产物文件名模式段用拼音（mobile→shouji、leanback→dianshi），与 CI 上传的 Release 附件名保持一致
    private String getName() {
        return AppVersion.deviceName();
    }

    public Updater force() {
        force = true;
        Notify.show(R.string.update_check);
        Setting.putUpdate(true);
        return this;
    }

    public void start(FragmentActivity activity) {
        bind(activity);
        boolean forceCheck = force;
        force = false;
        if (downloading) {
            restoreDialog(activity);
            return;
        }
        if (!Setting.getUpdate()) {
            DiagLog.log(LOG, "[检查] 跳过：检查更新开关已关闭");
            return;
        }
        Task.execute(() -> doInBackground(activity, forceCheck, false));
    }

    // 冷启动静默检查：仅命中强制更新（发布清单 force 或远端最低版本策略）才弹窗，否则完全不打扰
    // 检查失败（镜像+直连全挂/超时）不锁定状态，回前台经 resume() 自动重试（节流 30 秒）
    public void checkOnLaunch(FragmentActivity activity) {
        if (launchChecked || launchChecking) return;
        if (downloading || dialog != null) return;
        long now = SystemClock.elapsedRealtime();
        if (lastLaunchCheckAt > 0 && now - lastLaunchCheckAt < TimeUnit.SECONDS.toMillis(30)) return;
        lastLaunchCheckAt = now;
        launchChecking = true;
        Task.execute(() -> {
            try {
                doInBackground(activity, false, true);
            } finally {
                launchChecking = false;
            }
        });
    }

    public void resume(FragmentActivity activity) {
        bind(activity);
        if (downloading) {
            restoreDialog(activity);
            return;
        }
        if (forceMode && selected != null && selected.hasUpdate()) {
            if (dialog == null || !dialog.isAdded()) show(activity);
            return;
        }
        // 启动检查曾失败：回前台自动重试（内部含状态判断与 30 秒节流）
        if (!launchChecked) checkOnLaunch(activity);
    }

    private void doInBackground(FragmentActivity activity, boolean forceCheck, boolean launch) {
        // 静默检查不得覆盖正在展示的检查结果，避免用户已看到的"最新版本"与随后下载对象脱节
        if (launch && dialog != null && dialog.isAdded()) {
            DiagLog.log(LOG, "[检查] 触发=静默 跳过：正在展示检查结果");
            return;
        }
        DiagLog.log(LOG, "[检查] 触发=%s 本机=%s(code=%s) 机型=%s", triggerName(forceCheck, launch), AppVersion.fullName(), BuildConfig.VERSION_CODE, getName());
        long deadline = SystemClock.elapsedRealtime() + UPDATE_CHECK_TIMEOUT_MS;
        Future<Update> stableFuture = Task.executor().submit(() -> getUpdate(Update.CHANNEL_STABLE));
        Future<Update> betaFuture = Task.executor().submit(() -> getUpdate(Update.CHANNEL_BETA));
        Future<ForcePolicy> policyFuture = Task.executor().submit(ForcePolicy::fetch);
        stable = awaitUpdate(stableFuture, Update.CHANNEL_STABLE, deadline);
        beta = awaitUpdate(betaFuture, Update.CHANNEL_BETA, deadline);
        applyForce(awaitPolicy(policyFuture, deadline));
        DiagLog.log(LOG, "[检查] 清单结果 stable=%s beta=%s 强制=%s", describe(stable), describe(beta), forceMode);
        if (launch) {
            // 拿到任一渠道清单即视为检查成功并锁定；全失败则留待回前台重试
            if (stable.hasManifest() || beta.hasManifest()) launchChecked = true;
            if (!forceMode || selected == null || !selected.hasUpdate()) {
                DiagLog.log(LOG, "[检查] 静默检查结束：无需打扰");
                return;
            }
            DiagLog.log(LOG, "[检查] 静默检查命中强制更新 → 弹窗 目标=%s", describe(selected));
            // 弹窗被权限框/页面保存状态挡住时，回前台由 resume() 的强制态补弹
            App.post(() -> show(activity));
            return;
        }
        if (!stable.hasUpdate() && !beta.hasUpdate()) {
            if (forceCheck && (stable.hasManifest() || beta.hasManifest())) {
                selected = stable;
                DiagLog.log(LOG, "[结果] 手动检查：清单版本不高于本机 → 仅展示当前版本信息");
                App.post(() -> show(activity));
                return;
            }
            if (forceCheck) {
                DiagLog.log(LOG, "[结果] 手动检查：已是最新（检查失败=%s）", hasErrorOnly());
                App.post(() -> Notify.show(hasErrorOnly() ? R.string.update_failed : R.string.update_latest));
            }
            return;
        }
        if (!forceMode) selected = stable;
        DiagLog.log(LOG, "[结果] 发现可更新版本 → 弹窗 目标=%s", describe(selected));
        App.post(() -> show(activity));
    }

    // 触发方式（诊断日志用）：静默 = 冷启动/回前台自动检查；手动 = 用户点检查更新
    private String triggerName(boolean forceCheck, boolean launch) {
        return launch ? "静默" : forceCheck ? "手动" : "自动";
    }

    // 清单摘要（诊断日志用）：无清单时带上失败原因，一眼看出是"没有新版本"还是"根本没查到"
    private String describe(Update update) {
        if (update == null) return "null";
        if (!update.hasManifest()) return "无清单(" + (TextUtils.isEmpty(update.error) ? "未知原因" : update.error) + ")";
        return update.name + "(code=" + update.code + ",可更新=" + update.hasUpdate() + ")";
    }

    // SHA 前缀（诊断日志用）：只留前 12 位便于人工比对，避免整串刷屏
    private String shaPrefix(String sha) {
        return TextUtils.isEmpty(sha) ? "" : sha.substring(0, Math.min(12, sha.length()));
    }

    // 线路展示名：直连返回「直连」，镜像返回主机名
    private String lineName(GithubProxy.Line line) {
        if (line == null) return "直连";
        String name = GithubProxy.name(line);
        return TextUtils.isEmpty(name) ? "直连" : name;
    }

    // 合并 A（发布清单 force 字段）+ B（远端 force.json 最低版本策略）判定强制更新态
    private void applyForce(ForcePolicy policy) {
        forceMode = false;
        forceMsg = "";
        Update target = null;
        if (stable.isForceUpdate()) target = stable;
        else if (beta.isForceUpdate()) target = beta;
        if (target != null) {
            forceMode = true;
            forceMsg = TextUtils.isEmpty(target.forceMsg) ? policy.message : target.forceMsg;
            selected = target;
            return;
        }
        if (!policy.force) return;
        target = stable.hasUpdate() ? stable : (beta.hasUpdate() ? beta : null);
        // 策略要求强制但无可安装目标（清单缺失/网络异常）→ 无法执行，不弹窗卡死用户
        if (target == null) return;
        forceMode = true;
        forceMsg = policy.message;
        selected = target;
    }

    private ForcePolicy awaitPolicy(Future<ForcePolicy> future, long deadline) {
        try {
            long remaining = deadline - SystemClock.elapsedRealtime();
            if (remaining <= 0) return ForcePolicy.none();
            return future.get(remaining, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            future.cancel(true);
            return ForcePolicy.none();
        }
    }

    private Update awaitUpdate(Future<Update> future, String channel, long deadline) {
        try {
            long remaining = deadline - SystemClock.elapsedRealtime();
            if (remaining <= 0) throw new TimeoutException("Update check timed out");
            return future.get(remaining, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            future.cancel(true);
            e.printStackTrace();
            DiagLog.log(LOG, "[检查] 渠道=%s 清单任务失败：%s %s", channel, e.getClass().getSimpleName(), e.getMessage());
            Update update = Update.empty(channel);
            update.error = e.getMessage();
            return update;
        }
    }

    private Update getUpdate(String channel) {
        return Update.CHANNEL_BETA.equals(channel) ? getGithubBetaUpdate(channel) : getGithubStableUpdate(channel);
    }

    private Update getGithubStableUpdate(String channel) {
        try {
            JSONObject release = new JSONObject(fetchGithub(Github.getLatestReleaseApi(), Update.CHANNEL_STABLE));
            return readGithubReleaseUpdate(channel, release);
        } catch (Exception e) {
            e.printStackTrace();
            return Update.empty(channel);
        }
    }

    private Update getGithubBetaUpdate(String channel) {
        String manifestName = getManifestName(channel);
        try {
            JSONArray releases = new JSONArray(fetchGithub(Github.getReleasesApi(), channel));
            for (int i = 0; i < releases.length(); i++) {
                JSONObject release = releases.optJSONObject(i);
                if (release == null || !isBetaRelease(release)) continue;
                if (findAsset(release.optJSONArray("assets"), manifestName) == null) continue;
                return readGithubReleaseUpdate(channel, release);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return Update.empty(channel);
    }

    private boolean isBetaRelease(JSONObject release) {
        String tag = release.optString("tag_name");
        return release.optBoolean("prerelease") || tag.contains("-beta-");
    }

    private JSONObject findAsset(JSONArray assets, String name) {
        if (assets == null) return null;
        for (int i = 0; i < assets.length(); i++) {
            JSONObject asset = assets.optJSONObject(i);
            if (asset == null || !name.equals(asset.optString("name"))) continue;
            return asset;
        }
        return null;
    }

    private Update readGithubReleaseUpdate(String channel, JSONObject release) {
        JSONObject asset = findAsset(release.optJSONArray("assets"), getManifestName(channel));
        if (asset == null) {
            DiagLog.log(LOG, "[检查] 渠道=%s tag=%s 中未找到清单资源 %s", channel, release.optString("tag_name"), getManifestName(channel));
            return Update.empty(channel);
        }
        // 优先用资源直链（github.com 文件下载，镜像可代理），缺失时回退 API 地址
        String url = asset.optString("browser_download_url");
        boolean api = TextUtils.isEmpty(url);
        if (api) url = Github.getReleaseAssetApi(asset.optLong("id"));
        return readUpdate(channel, url, SOURCE_GITHUB, api ? GITHUB_ASSET_HEADERS : null, release.optString("body"));
    }

    // 版本信息 API（latest/releases 移动指针）：直连优先（GitHub API 永不缓存，响应仅 2KB），
    // 直连失败才降级为多源并发拉清单取最新——陈旧镜像缓存不可能在比较中胜出
    private String fetchGithub(String url, String channel) throws Exception {
        long start = System.currentTimeMillis();
        try {
            String body = OkHttp.string(url, GITHUB_API_HEADERS, GITHUB_REQUEST_TIMEOUT_MS);
            if (isReleaseJson(body)) {
                DiagLog.log(LOG, "[检查] 主路径直连API成功 渠道=%s 耗时=%sms 字节=%s", channel, System.currentTimeMillis() - start, body.length());
                return body;
            }
            DiagLog.log(LOG, "[检查] 主路径直连API返回非发布JSON 渠道=%s 字节=%s → 转降级", channel, body == null ? 0 : body.length());
        } catch (Exception e) {
            DiagLog.log(LOG, "[检查] 主路径直连API失败 渠道=%s 耗时=%sms %s: %s → 转降级",
                    channel, System.currentTimeMillis() - start, e.getClass().getSimpleName(), e.getMessage());
        }
        GithubProxy.clearJsonCache();
        List<GithubProxy.Source> sources = GithubProxy.fetchJsonAll(url, FALLBACK_COLLECT_MS);
        String best = pickLatest(sources, channel);
        if (best == null) {
            DiagLog.log(LOG, "[检查] 降级路径失败 渠道=%s 收集=%s 条均无有效发布JSON url=%s", channel, sources.size(), url);
            throw new IllegalStateException("Update check failed: " + url);
        }
        return best;
    }

    // tag 钉死的 API 地址（内容不可变，镜像缓存无害）：镜像优先加速，失败回退直连
    private String fetchGithubPinned(String url) throws Exception {
        String body = GithubProxy.fetchJson(url);
        if (isReleaseJson(body)) return body;
        GithubProxy.clearJsonCache();
        return OkHttp.string(url, GITHUB_API_HEADERS, GITHUB_REQUEST_TIMEOUT_MS);
    }

    private boolean isReleaseJson(String body) {
        return body != null && body.contains("\"assets\"");
    }

    // 多源取最新：比较 tag 版本号 → 平局优先直连份 → 再比 tag 内构建时间戳；返回胜出源的完整响应体
    private String pickLatest(List<GithubProxy.Source> sources, String channel) {
        String bestBody = null;
        String bestTag = null;
        boolean bestDirect = false;
        // 诊断日志用：把每个源返回的 tag 列出来，"为什么选中了旧版本"一看即知
        List<String> detail = new ArrayList<>();
        for (GithubProxy.Source source : sources) {
            if (!isReleaseJson(source.body)) continue;
            String tag = candidateTag(source.body, channel);
            if (TextUtils.isEmpty(tag)) continue;
            detail.add((source.direct ? "直连" : "镜像") + ":" + tag);
            if (bestTag == null || compareTag(tag, bestTag, source.direct, bestDirect) > 0) {
                bestBody = source.body;
                bestTag = tag;
                bestDirect = source.direct;
            }
        }
        DiagLog.log(LOG, "[检查] 降级候选 渠道=%s 收集=%s 有效=%s 明细=%s 胜出=%s",
                channel, sources.size(), detail.size(), detail, bestTag == null ? "无" : bestTag + "(" + (bestDirect ? "直连" : "镜像") + ")");
        return bestBody;
    }

    // 从 API 响应提取候选发布 tag：stable 为单对象；beta 从数组取首个带清单的预发布
    private String candidateTag(String body, String channel) {
        try {
            String text = body.trim();
            if (text.startsWith("[")) {
                JSONArray releases = new JSONArray(text);
                String manifestName = getManifestName(channel);
                for (int i = 0; i < releases.length(); i++) {
                    JSONObject release = releases.optJSONObject(i);
                    if (release == null || !isBetaRelease(release)) continue;
                    if (findAsset(release.optJSONArray("assets"), manifestName) == null) continue;
                    return release.optString("tag_name");
                }
                return null;
            }
            return new JSONObject(text).optString("tag_name");
        } catch (Exception e) {
            return null;
        }
    }

    private int compareTag(String a, String b, boolean aDirect, boolean bDirect) {
        int c = compareVersion(tagVersion(a), tagVersion(b));
        if (c != 0) return c;
        if (aDirect != bDirect) return aDirect ? 1 : -1;
        return tagTime(a).compareTo(tagTime(b));
    }

    private String tagVersion(String tag) {
        Matcher matcher = TAG_VERSION.matcher(tag);
        return matcher.find() ? matcher.group(1) : "";
    }

    private String tagTime(String tag) {
        Matcher matcher = TAG_TIME.matcher(tag);
        return matcher.find() ? matcher.group(1) : "";
    }

    private int compareVersion(String a, String b) {
        if (a.isEmpty() || b.isEmpty()) return 0;
        String[] pa = a.split("\\.");
        String[] pb = b.split("\\.");
        for (int i = 0; i < Math.max(pa.length, pb.length); i++) {
            long x = i < pa.length ? parseSegment(pa[i]) : 0;
            long y = i < pb.length ? parseSegment(pb[i]) : 0;
            if (x != y) return x > y ? 1 : -1;
        }
        return 0;
    }

    private long parseSegment(String value) {
        try {
            return Long.parseLong(value);
        } catch (Exception e) {
            return 0;
        }
    }

    private Update readUpdate(String channel, String manifestUrl, String source, Map<String, String> headers, String fallbackNotes) {
        Update update = Update.empty(channel);
        try {
            String text;
            if (headers == null) {
                // 文件直链清单：先试镜像加速，失败回退直连
                text = GithubProxy.fetchJson(manifestUrl);
                if (TextUtils.isEmpty(text)) text = OkHttp.string(manifestUrl, GITHUB_REQUEST_TIMEOUT_MS);
            } else {
                text = OkHttp.string(manifestUrl, headers, GITHUB_REQUEST_TIMEOUT_MS);
            }
            if (TextUtils.isEmpty(text)) throw new IllegalStateException("Empty update manifest: " + manifestUrl);
            JSONObject object = new JSONObject(text);
            update.name = object.optString("name");
            update.desc = normalizeText(object.optString("desc"));
            update.notes = normalizeText(object.optString("notes"));
            update.channel = object.optString("channel", channel);
            update.code = object.optInt("code");
            update.apk = object.optString("apk");
            update.size = object.optLong("size");
            update.sha256 = object.optString("sha256");
            update.force = object.optBoolean("force");
            update.forceMsg = normalizeText(object.optString("forceMsg"));
            update.apkUrl = getApkUrl(update, source);
            // 清单严格校验：apkUrl/size/sha256/code 缺任一即视为无效数据整体废弃，
            // 绝不允许带空字段进入下载流程（否则下载后的大小/SHA 校验会被空值整体跳过，旧包可蒙混过关）
            if (TextUtils.isEmpty(update.apkUrl) || update.size <= 0 || update.sha256.length() != 64 || update.code <= 0) {
                DiagLog.log(LOG, "[检查] 清单无效 渠道=%s url=%s name=%s code=%s size=%s sha长度=%s apkUrl=%s",
                        channel, manifestUrl, update.name, update.code, update.size, update.sha256.length(), update.apkUrl);
                throw new IllegalStateException("Invalid update manifest: " + manifestUrl);
            }
            DiagLog.log(LOG, "[检查] 清单解析成功 渠道=%s name=%s code=%s size=%s sha=%s apkUrl=%s",
                    channel, update.name, update.code, update.size, shaPrefix(update.sha256), update.apkUrl);
            if (isDefaultReleaseNotes(update.notes)) update.notes = "";
            if (TextUtils.isEmpty(update.notes) && TextUtils.isEmpty(update.desc)) {
                String notes = TextUtils.isEmpty(fallbackNotes) ? getReleaseNotes(update.name) : fallbackNotes;
                if (!TextUtils.isEmpty(notes)) update.notes = normalizeText(notes);
            }
        } catch (Exception e) {
            e.printStackTrace();
            DiagLog.log(LOG, "[检查] 清单获取/解析失败 渠道=%s url=%s %s: %s", channel, manifestUrl, e.getClass().getSimpleName(), e.getMessage());
            Update failed = Update.empty(channel);
            failed.error = e.getMessage();
            return failed;
        }
        return update;
    }

    private String normalizeText(String text) {
        if (TextUtils.isEmpty(text)) return "";
        return text
                .replace("\\r\\n", "\n")
                .replace("\\n", "\n")
                .replace("\\r", "\n")
                .replace("\\t", "\t")
                .replace("\\\"", "\"")
                .replace("\\'", "'");
    }

    private String getManifestName(String channel) {
        return getAssetName(channel, "json");
    }

    private String getDefaultApkName(String channel) {
        return getAssetName(channel, "apk");
    }

    private String getAssetName(String channel, String ext) {
        String suffix = Update.CHANNEL_BETA.equals(channel) ? "-beta" : "";
        return getName() + suffix + "." + ext;
    }

    private String getApkUrl(Update update, String source) {
        String apk = TextUtils.isEmpty(update.apk) ? getDefaultApkName(update.channel) : update.apk;
        if (apk.startsWith("http://") || apk.startsWith("https://")) return apk;
        if (SOURCE_GITHUB.equals(source) && !TextUtils.isEmpty(update.name)) return Github.getGithubReleaseAsset(update.name, getFileName(apk, update.channel));
        return Github.getGithubLatestAsset(getFileName(apk, update.channel));
    }

    private String getFileName(String value, String channel) {
        int query = value.indexOf('?');
        if (query >= 0) value = value.substring(0, query);
        int slash = value.lastIndexOf('/');
        String name = slash >= 0 ? value.substring(slash + 1) : value;
        return TextUtils.isEmpty(name) ? getDefaultApkName(channel) : name;
    }

    private boolean isDefaultReleaseNotes(String notes) {
        return !TextUtils.isEmpty(notes) && DEFAULT_RELEASE_NOTES.equals(notes.trim());
    }

    private String getReleaseNotes(String tag) {
        if (TextUtils.isEmpty(tag)) return "";
        String notes = readReleaseNotes(tag);
        if (!TextUtils.isEmpty(notes) || tag.startsWith("v")) return notes;
        return readReleaseNotes("v" + tag);
    }

    private String readReleaseNotes(String tag) {
        try {
            // release notes 按 tag 钉死取，内容不可变：优先镜像加速线路，全部失败才回退直连
            return new JSONObject(fetchGithubPinned(Github.getReleaseApi(tag))).optString("body");
        } catch (Exception ignored) {
            return "";
        }
    }

    private boolean hasErrorOnly() {
        return !stable.hasManifest() && !beta.hasManifest() && (!TextUtils.isEmpty(stable.error) || !TextUtils.isEmpty(beta.error));
    }

    private boolean show(FragmentActivity activity) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return false;
        if (activity.getSupportFragmentManager().isStateSaved()) return false;
        bind(activity);
        dismiss();
        // 旋转后 FragmentManager 会自动恢复一个无状态的旧实例，统一移除后以完整状态重建
        for (androidx.fragment.app.Fragment fragment : activity.getSupportFragmentManager().getFragments()) {
            if (fragment instanceof UpdateDialog) ((UpdateDialog) fragment).dismissAllowingStateLoss();
        }
        Notify.dismissToast();
        String channel = selected == null ? Update.CHANNEL_STABLE : selected.channel;
        dialog = UpdateDialog.create().stable(stable).beta(beta).selected(channel).force(forceMode, forceMsg).listener(this).show(activity);
        return true;
    }

    @Override
    public void onConfirm(View view) {
        // 闸门①：清单版本码必须严格大于本机已安装版本码，否则绝不进入下载（更旧的清单在此被拦下）
        if (selected == null || !selected.hasUpdate()) {
            DiagLog.log(LOG, "[闸门] 拒绝下载：清单 code=%s 未严格大于本机 code=%s name=%s",
                    selected == null ? "null" : String.valueOf(selected.code), BuildConfig.VERSION_CODE, selected == null ? "" : selected.name);
            Notify.show(R.string.update_latest);
            return;
        }
        view.setEnabled(false);
        downloading = true;
        canceled = false;
        retriedDirect = false;
        retriedLatest = false;
        resetProgress();
        Path.clear(getFile());
        // 闸门②：冻结本次下载对象（直链/大小/SHA/版本码），后续任何检查刷新都不得改变它
        snapshot = new Snapshot(selected, getFileName(selected.apkUrl, selected.channel));
        DiagLog.log(LOG, "[闸门] 冻结清单快照 asset=%s size=%s sha=%s code=%s url=%s",
                snapshot.asset, snapshot.size, shaPrefix(snapshot.sha256), snapshot.code, snapshot.url);
        setDialogProgress(0, 0, snapshot.size, 0, 0);
        String url = snapshot.url;
        long size = snapshot.size;
        FragmentActivity act = activityRef == null ? null : activityRef.get();
        // 下载前核对：24 镜像+直连并发 Range 探测（每线仅 2KB），总大小与 PK 文件头都对上的线路才有资格进列表；
        // 用户在弹窗手点线路或选「自动选择」（最快），从源头杜绝下载到陈旧/残缺内容
        Task.execute(() -> {
            if (canceled || !downloading) return;
            List<GithubProxy.Line> lines = GithubProxy.probeVerified(url, size);
            if (canceled || !downloading) return;
            if (lines.isEmpty()) {
                DiagLog.log(LOG, "[探测] 无任何线路通过验证 → 仍用原始直链尝试一次");
                App.post(() -> {
                    Notify.show(R.string.update_line_empty);
                    startDownload(url, null);
                });
                return;
            }
            App.post(() -> {
                if (act == null || act.isFinishing() || act.isDestroyed()) {
                    DiagLog.log(LOG, "[选线] 无可用界面 → 自动选择最快线路=%s", lineName(lines.get(0)));
                    startDownload(GithubProxy.build(lines.get(0), url), lines.get(0));
                    return;
                }
                DownloadLineDialog.show(act, lines, line -> {
                    DiagLog.log(LOG, "[选线] 选定线路=%s 是否最快=%s 候选=%s", lineName(line), line == lines.get(0), lines.size());
                    startDownload(GithubProxy.build(line, url), line);
                }, () -> {
                    // 用户取消选线路：中止本次更新下载
                    DiagLog.log(LOG, "[选线] 用户取消选线路 → 中止本次更新");
                    canceled = true;
                    downloading = false;
                    resetProgress();
                    Path.clear(getFile());
                    dismiss();
                });
            });
        });
    }

    private void startDownload(String url, GithubProxy.Line line) {
        currentLine = line;
        // 裸 OkHttpClient 下载：绕开爬虫网络栈的全局缓存/代理，杜绝镜像 302 重定向命中旧缓存
        long limit = snapshot == null || retriedLatest ? 0 : snapshot.size;
        DiagLog.log(LOG, "[下载] 开始 线路=%s 限长=%s url=%s", lineName(line), limit, url);
        download = Download.create(url, getFile()).tag(url).limit(limit).client(GithubProxy.downloadClient());
        download.start(this);
    }

    @Override
    public void onCancel(View view) {
        // 强制更新态：拒绝取消（下载中不可中断、弹窗不可关闭）
        if (forceMode) return;
        if (downloading) {
            DiagLog.log(LOG, "[结果] 用户取消下载 → 中止");
            canceled = true;
            downloading = false;
            if (download != null) download.cancel();
            download = null;
            resetProgress();
            Notify.show(R.string.update_canceled);
            dismiss();
            return;
        }
        Setting.putUpdate(false);
        DiagLog.log(LOG, "[结果] 用户关闭更新弹窗并停用检查更新");
        if (download != null) download.cancel();
        dismiss();
    }

    @Override
    public void onClose() {
        dialog = null;
    }

    @Override
    public void onChannel(String channel) {
        Setting.putUpdateChannel(channel);
        selected = Update.CHANNEL_BETA.equals(channel) ? beta : stable;
        DiagLog.log(LOG, "[检查] 用户切换渠道=%s 目标=%s", channel, describe(selected));
    }

    private void dismiss() {
        try {
            if (dialog != null) dialog.dismissAllowingStateLoss();
        } catch (Exception ignored) {
        } finally {
            dialog = null;
        }
    }

    @Override
    public void progress(int progress) {
        setDialogProgress(progress, 0, 0, 0, 0);
    }

    @Override
    public void progress(int progress, long bytes, long total, long speed, long elapsed) {
        setDialogProgress(progress, bytes, total, speed, elapsed);
    }

    private void setDialogProgress(int progress, long bytes, long total, long speed, long elapsed) {
        if (canceled || !downloading) return;
        long manifestSize = snapshot == null ? 0 : snapshot.size;
        if (total <= 0 && manifestSize > 0) total = manifestSize;
        if (progress < 0 && total > 0 && bytes > 0) progress = (int) (bytes * 100.0 / total);
        lastProgress = progress;
        lastBytes = bytes;
        lastTotal = total;
        lastSpeed = speed;
        lastElapsed = elapsed;
        if (dialog == null) return;
        if (!dialog.setProgress(progress, bytes, total, speed, elapsed)) dialog = null;
    }

    @Override
    public void error(String msg) {
        DiagLog.log(LOG, "[下载] 失败 线路=%s msg=%s", lineName(currentLine), msg);
        if (canceled) return;
        download = null;
        downloading = false;
        resetProgress();
        Notify.show(msg);
        // 强制更新态下载失败：保留弹窗供重试，不关闭
        if (forceMode && dialog != null && dialog.reset()) return;
        dismiss();
    }

    @Override
    public void success(File file) {
        if (canceled) return;
        download = null;
        Snapshot target = snapshot;
        DiagLog.log(LOG, "[下载] 完成 线路=%s 字节=%s", lineName(currentLine), file == null ? 0 : file.length());
        Task.execute(() -> {
            String error = validate(file, target);
            App.post(() -> {
                if (canceled) return;
                if (!TextUtils.isEmpty(error)) {
                    Path.clear(file);
                    // 校验不过且当前走的是镜像线路：自动改用官方直连重试一次（罕见兜底，防传输中途坏数据）
                    if (!retriedDirect && currentLine != null && target != null && !TextUtils.isEmpty(target.url)) {
                        retriedDirect = true;
                        DiagLog.log(LOG, "[结果] 校验不过（%s）→ 改用官方直连重试一次", error);
                        Notify.show(R.string.update_retry_direct);
                        startDownload(target.url, null);
                        return;
                    }
                    // 官方直连仍不过：最后改用与分享软件同源的 latest 直链（latest 永远指向最新 Release）
                    if (!retriedLatest && target != null) {
                        retriedLatest = true;
                        DiagLog.log(LOG, "[结果] 校验不过（%s）→ 改用 latest 直链兜底 asset=%s", error, target.asset);
                        Notify.show(R.string.update_retry_latest);
                        startDownload(Github.getGithubLatestAsset(target.asset), null);
                        return;
                    }
                    DiagLog.log(LOG, "[结果] 校验不过且重试已耗尽 → 放弃本次更新 原因=%s", error);
                    downloading = false;
                    resetProgress();
                    Notify.show(error);
                    dismiss();
                    return;
                }
                DiagLog.log(LOG, "[结果] 校验通过 → 送装 file=%s", file == null ? "" : file.getName());
                downloading = false;
                resetProgress();
                FileUtil.openFile(file);
                dismiss();
            });
        });
    }

    private void restoreDialog(FragmentActivity activity) {
        if (!downloading || selected == null) return;
        show(activity);
        setDialogProgress(lastProgress, lastBytes, lastTotal, lastSpeed, lastElapsed);
    }

    private String validate(File file, Snapshot update) {
        if (file == null || !file.exists() || file.length() <= 0) {
            DiagLog.log(LOG, "[校验] 拒绝：文件不存在或为空 file=%s", file == null ? "null" : file.getAbsolutePath());
            return ResUtil.getString(R.string.update_download_invalid);
        }
        android.content.pm.PackageInfo info = App.get().getPackageManager().getPackageArchiveInfo(file.getAbsolutePath(), 0);
        if (info == null) {
            DiagLog.log(LOG, "[校验] 拒绝：无法读取APK包信息 file=%s", file.getAbsolutePath());
            return ResUtil.getString(R.string.update_download_invalid);
        }
        // 包名核对：杜绝任何环节把别的应用递给安装器
        if (!BuildConfig.APPLICATION_ID.equals(info.packageName)) {
            DiagLog.log(LOG, "[校验] 拒绝：包名不符 实际=%s 期望=%s", info.packageName, BuildConfig.APPLICATION_ID);
            return ResUtil.getString(R.string.update_download_package);
        }
        long real = androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(info);
        // 闸门③（装前铁律）：APK 内嵌版本码必须严格大于本机已安装版本码，否则一律拒绝送装
        if (real <= BuildConfig.VERSION_CODE) {
            DiagLog.log(LOG, "[闸门] 送装前拒绝：APK内嵌 code=%s 不高于本机 code=%s", real, BuildConfig.VERSION_CODE);
            return ResUtil.getString(R.string.update_download_older);
        }
        if (update == null) return "";
        // latest 直链兜底：该地址可能已指向比清单更新的一版，只要求不旧于清单所描述的一版
        if (retriedLatest) {
            boolean older = real < update.code;
            DiagLog.log(LOG, "[校验] latest兜底核对 apkCode=%s 清单code=%s 拒绝=%s", real, update.code, older);
            return older ? ResUtil.getString(R.string.update_download_older) : "";
        }
        if (update.size > 0 && file.length() != update.size) {
            DiagLog.log(LOG, "[校验] 拒绝：长度不符 实际=%s 清单=%s", file.length(), update.size);
            return ResUtil.getString(R.string.update_download_incomplete);
        }
        String actual = TextUtils.isEmpty(update.sha256) ? "" : sha256(file);
        if (!TextUtils.isEmpty(update.sha256) && !update.sha256.equalsIgnoreCase(actual)) {
            DiagLog.log(LOG, "[校验] 拒绝：SHA不符 实际=%s 清单=%s", shaPrefix(actual), shaPrefix(update.sha256));
            return ResUtil.getString(R.string.update_download_checksum);
        }
        // 零流量核对（只读本地文件头）：APK 实际 versionCode 必须与冻结清单一致
        if (update.code > 0 && real != update.code) {
            DiagLog.log(LOG, "[校验] 拒绝：版本码不符 apkCode=%s 清单code=%s", real, update.code);
            return ResUtil.getString(R.string.update_download_version);
        }
        DiagLog.log(LOG, "[校验] 通过 长度=%s apkCode=%s 本机code=%s sha=%s", file.length(), real, BuildConfig.VERSION_CODE, shaPrefix(actual));
        return "";
    }

    private String sha256(File file) {
        try (FileInputStream input = new FileInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[16384];
            int read;
            while ((read = input.read(buffer)) != -1) digest.update(buffer, 0, read);
            StringBuilder builder = new StringBuilder();
            for (byte value : digest.digest()) builder.append(String.format(Locale.ROOT, "%02x", value));
            return builder.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private void bind(FragmentActivity activity) {
        if (activity == null) return;
        FragmentActivity old = activityRef == null ? null : activityRef.get();
        if (old == activity) return;
        if (old != null) old.getLifecycle().removeObserver(lifecycleObserver);
        activityRef = new WeakReference<>(activity);
        activity.getLifecycle().addObserver(lifecycleObserver);
    }

    private void unbind(FragmentActivity activity) {
        FragmentActivity current = activityRef == null ? null : activityRef.get();
        if (current != activity) return;
        activity.getLifecycle().removeObserver(lifecycleObserver);
        activityRef = null;
        if (!downloading) dialog = null;
    }

    private void resetProgress() {
        lastProgress = -1;
        lastBytes = 0;
        lastTotal = 0;
        lastSpeed = 0;
        lastElapsed = 0;
    }
}
