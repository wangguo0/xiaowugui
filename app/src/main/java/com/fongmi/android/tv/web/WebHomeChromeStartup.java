package com.fongmi.android.tv.web;

import android.text.TextUtils;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.bean.Config;
import com.fongmi.android.tv.bean.Site;
import com.github.catvod.utils.Json;
import com.github.catvod.utils.Prefers;
import com.google.gson.JsonObject;

public final class WebHomeChromeStartup {

    private static final String KEY_CONFIG_URL = "web_home_startup_config_url";
    private static final String KEY_HOME_KEY = "web_home_startup_home_key";
    private static final String KEY_CHROME = "web_home_startup_chrome";

    private WebHomeChromeStartup() {
    }

    public static JsonObject restore(Config config) {
        if (!matches(config)) return null;
        JsonObject object = parse(Prefers.getString(KEY_CHROME));
        String mode = WebHomeChrome.normalize(Json.safeString(object, "mode"), WebHomeChrome.EDGE);
        return WebHomeChrome.hidesNativeChrome(mode) ? object : null;
    }

    public static void remember(Config config, Site site) {
        if (config == null || TextUtils.isEmpty(config.getUrl()) || site == null || !site.hasHomePage()) {
            clear();
            return;
        }
        JsonObject object = site.getWebHomeChrome();
        String mode = WebHomeChrome.normalize(site.getChromeMode(), WebHomeChrome.EDGE);
        if (!object.has("mode")) object.addProperty("mode", mode);
        Prefers.put(KEY_CONFIG_URL, config.getUrl());
        Prefers.put(KEY_HOME_KEY, site.getKey());
        Prefers.put(KEY_CHROME, object.toString());
    }

    private static boolean matches(Config config) {
        if (config == null || TextUtils.isEmpty(config.getUrl())) return false;
        if (!TextUtils.equals(Prefers.getString(KEY_CONFIG_URL), config.getUrl())) return false;
        return TextUtils.equals(Prefers.getString(KEY_HOME_KEY), config.getHome());
    }

    private static JsonObject parse(String value) {
        try {
            JsonObject object = App.gson().fromJson(value, JsonObject.class);
            return object == null ? new JsonObject() : object;
        } catch (Throwable e) {
            return new JsonObject();
        }
    }

    private static void clear() {
        Prefers.remove(KEY_CONFIG_URL);
        Prefers.remove(KEY_HOME_KEY);
        Prefers.remove(KEY_CHROME);
    }
}
