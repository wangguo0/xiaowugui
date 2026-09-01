package com.fongmi.android.tv.bean;

import android.text.TextUtils;

import com.fongmi.android.tv.App;
import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.utils.Prefers;
import com.google.gson.annotations.SerializedName;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.LinkedHashMap;
import java.util.Map;

// 追番卡片手动绑定：卡片名 -> 播放页影片信息，存于 bangumi_bind 映射，
// 换绑即覆盖旧条目，实现用户可控的绑定/解除
public class BangumiBind {

    private static final String TAG = "TV-Bangumi";
    private static final String KEY_BIND = "bangumi_bind";

    @SerializedName("siteKey")
    private String siteKey;
    @SerializedName("vodId")
    private String vodId;
    @SerializedName("vodName")
    private String vodName;
    @SerializedName("vodPic")
    private String vodPic;
    @SerializedName("wallPic")
    private String wallPic;

    public static BangumiBind find(String name) {
        if (TextUtils.isEmpty(name)) return null;
        BangumiBind bind = map().get(name);
        return bind == null || TextUtils.isEmpty(bind.getSiteKey()) || TextUtils.isEmpty(bind.getVodId()) ? null : bind;
    }

    public static void put(String name, String siteKey, String vodId, String vodName, String vodPic, String wallPic) {
        if (TextUtils.isEmpty(name)) return;
        try {
            Map<String, BangumiBind> map = map();
            BangumiBind bind = new BangumiBind();
            bind.siteKey = siteKey;
            bind.vodId = vodId;
            bind.vodName = vodName;
            bind.vodPic = vodPic;
            bind.wallPic = wallPic;
            map.put(name, bind);
            Prefers.put(KEY_BIND, App.gson().toJson(map));
        } catch (Exception e) {
            SpiderDebug.log(TAG, "bind put error=%s", e.getMessage());
        }
    }

    public static void remove(String name) {
        if (TextUtils.isEmpty(name)) return;
        try {
            Map<String, BangumiBind> map = map();
            map.remove(name);
            Prefers.put(KEY_BIND, App.gson().toJson(map));
        } catch (Exception e) {
            SpiderDebug.log(TAG, "bind remove error=%s", e.getMessage());
        }
    }

    private static Map<String, BangumiBind> map() {
        try {
            String json = Prefers.getString(KEY_BIND);
            if (TextUtils.isEmpty(json)) return new LinkedHashMap<>();
            Type type = new TypeToken<Map<String, BangumiBind>>() {}.getType();
            Map<String, BangumiBind> map = App.gson().fromJson(json, type);
            return map == null ? new LinkedHashMap<>() : new LinkedHashMap<>(map);
        } catch (Exception e) {
            SpiderDebug.log(TAG, "bind map error=%s", e.getMessage());
            return new LinkedHashMap<>();
        }
    }

    public String getSiteKey() {
        return safe(siteKey);
    }

    public String getVodId() {
        return safe(vodId);
    }

    public String getVodName() {
        return safe(vodName);
    }

    public String getVodPic() {
        return safe(vodPic);
    }

    public String getWallPic() {
        return safe(wallPic);
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}
