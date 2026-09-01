package com.fongmi.android.tv.api.bangumi;

import android.text.TextUtils;

import com.fongmi.android.tv.bean.Bangumi;
import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Prefers;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

// 直连腾讯视频"每日更新"真实接口 getCard：以今天为起点逐天请求，
// 星期归属由请求日期直接决定，与官方页面完全同源，保证准确完整
public class BangumiApi {

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private static final String TAG = "TV-Bangumi";
    private static final String API = "https://pbaccess.video.qq.com/trpc.vector_layout.page_view.PageService/getCard?video_appid=3000010&vversion_platform=2";
    private static final String KEY_MOD = "bangumi_mod_id";
    private static final String DEFAULT_MOD = "d4afd_17f79";
    // 模块 ID 失效时记录失效日期，当天不再重复触发自愈
    private static final String KEY_MOD_FAIL = "bangumi_mod_fail_day";

    private BangumiApi() {
    }

    public static List<Bangumi> fetch() {
        List<Bangumi> items = fetch(modId());
        if (!items.isEmpty()) return items;
        return recover();
    }

    // 主链路：7 个日期各请求一次，单天失败不影响其他天
    private static List<Bangumi> fetch(String mod) {
        Map<String, Bangumi> map = new LinkedHashMap<>();
        Calendar c = Calendar.getInstance();
        for (int i = 0; i < 7; i++) {
            String week = new SimpleDateFormat("yyyyMMdd", Locale.CHINA).format(c.getTime());
            int day = weekDay(c);
            List<Bangumi> daily = request(mod, week, day);
            for (Bangumi item : daily) {
                Bangumi exist = map.get(item.getName());
                if (exist == null) map.put(item.getName(), item);
                else exist.addDay(day);
            }
            c.add(Calendar.DAY_OF_MONTH, 1);
        }
        List<Bangumi> result = new ArrayList<>(map.values());
        SpiderDebug.log(TAG, "api fetch mod=%s items=%s", mod, result.size());
        return result;
    }

    // 自愈：模块 ID 失效时，从页面 DOM 提取新 ID 并重试一次
    private static List<Bangumi> recover() {
        String today = new SimpleDateFormat("yyyyMMdd", Locale.CHINA).format(new Date());
        if (today.equals(Prefers.getString(KEY_MOD_FAIL))) return new ArrayList<>();
        Prefers.getPrefers().edit().putString(KEY_MOD_FAIL, today).apply();
        String mod = BangumiModule.fetch();
        if (TextUtils.isEmpty(mod) || mod.equals(Prefers.getString(KEY_MOD))) return new ArrayList<>();
        SpiderDebug.log(TAG, "api recover mod=%s", mod);
        return fetch(mod);
    }

    private static String modId() {
        String mod = Prefers.getString(KEY_MOD);
        return TextUtils.isEmpty(mod) ? DEFAULT_MOD : mod;
    }

    private static List<Bangumi> request(String mod, String week, int day) {
        try {
            String text = post(body(mod, week));
            List<Bangumi> items = parse(text, day);
            SpiderDebug.log(TAG, "api week=%s items=%s", week, items.size());
            return items;
        } catch (Exception e) {
            SpiderDebug.log(TAG, "api request error week=%s msg=%s", week, e.getMessage());
            return new ArrayList<>();
        }
    }

    private static String body(String mod, String week) {
        return "{\"page_params\":{\"page_id\":\"100119\",\"page_type\":\"channel\",\"new_mark_label_enabled\":\"1\",\"un_mod_id\":\"" + mod + "\",\"un_module_key\":\"\",\"week\":\"" + week + "\"},\"page_context\":{\"_ctrl_page_index\":\"1\",\"_ctrl_showed_module_num\":\"6\",\"page_index\":\"1\",\"video_un_page_index\":\"1\"},\"flip_info\":{\"page_strategy_id\":\"\",\"page_module_id\":\"" + mod + "\",\"sub_module_id\":\"\",\"flip_params\":{\"is_mvl\":\"1\",\"page_num\":\"0\"}}}";
    }

    private static String post(String body) throws Exception {
        Request request = new Request.Builder()
                .url(API)
                .header("Content-Type", "application/json")
                .header("Referer", "https://v.qq.com/channel/cartoon")
                .header("User-Agent", BangumiModule.UA)
                .post(RequestBody.create(body, JSON))
                .build();
        try (Response response = OkHttp.client(15000).newCall(request).execute()) {
            return response.body() != null ? response.body().string() : "";
        }
    }

    // 卡片路径：data.card.children_list.list.cards[]，type=poster 为动漫卡片
    private static List<Bangumi> parse(String text, int day) {
        List<Bangumi> items = new ArrayList<>();
        if (TextUtils.isEmpty(text)) return items;
        try {
            JsonElement root = JsonParser.parseString(text);
            JsonArray cards = root.getAsJsonObject()
                    .getAsJsonObject("data")
                    .getAsJsonObject("card")
                    .getAsJsonObject("children_list")
                    .getAsJsonObject("list")
                    .getAsJsonArray("cards");
            for (JsonElement element : cards) {
                Bangumi item = toBangumi(element.getAsJsonObject(), day);
                if (item != null) items.add(item);
            }
        } catch (Exception e) {
            SpiderDebug.log(TAG, "api parse error=%s", e.getMessage());
        }
        return items;
    }

    private static Bangumi toBangumi(JsonObject card, int day) {
        if (!"poster".equals(str(card, "type"))) return null;
        JsonElement params = card.get("params");
        if (params == null || !params.isJsonObject()) return null;
        JsonObject p = params.getAsJsonObject();
        String name = str(p, "title");
        if (TextUtils.isEmpty(name) || name.length() > 40) return null;
        Bangumi item = new Bangumi();
        item.setName(name.trim());
        item.setPic(str(p, "image_url"));
        item.setDesc(str(p, "sub_title"));
        item.setEpisode(str(p, "episode_updated"));
        item.setBadge(badge(str(p, "uni_imgtag")));
        item.setCid(str(p, "cid"));
        item.setSource("api");
        item.addDay(day);
        return item;
    }

    // 角标取 uni_imgtag.tag_2.text（如"独播""上新"）
    private static String badge(String json) {
        if (TextUtils.isEmpty(json)) return "";
        try {
            JsonObject tag = JsonParser.parseString(json).getAsJsonObject().getAsJsonObject("tag_2");
            String text = str(tag, "text");
            return "0".equals(str(tag, "type")) ? "" : text;
        } catch (Exception e) {
            return "";
        }
    }

    private static int weekDay(Calendar c) {
        int day = c.get(Calendar.DAY_OF_WEEK);
        return day == Calendar.SUNDAY ? Bangumi.SUNDAY : day - 1;
    }

    private static String str(JsonObject obj, String key) {
        JsonElement e = obj.get(key);
        return e == null || e.isJsonNull() || !e.isJsonPrimitive() ? "" : e.getAsString();
    }
}
