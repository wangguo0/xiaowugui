package com.fongmi.android.tv.setting;

import android.net.Uri;
import android.text.TextUtils;

import com.github.catvod.bean.Proxy;
import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Json;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.List;

public class ProxySetting {

    private static final String NAME = "app";

    public static void apply() {
        OkHttp.selector().remove(NAME);
        OkHttp.closeIdleConnections();
        if (!Setting.isShellProxy()) {
            SpiderDebug.log("proxy", "app proxy disabled");
            return;
        }
        List<Proxy> rules = getRules();
        if (rules.isEmpty()) {
            SpiderDebug.log("proxy", "app proxy enabled but no valid rules defaultUrl=%s rulesLength=%s", safeUrl(Setting.getShellProxyUrl()), Setting.getShellProxyRules().length());
            return;
        }
        OkHttp.selector().addAll(rules);
        SpiderDebug.log("proxy", "app proxy enabled rules=%s defaultUrl=%s", rules.size(), safeUrl(Setting.getShellProxyUrl()));
    }

    public static List<Proxy> getRules() {
        String rules = Setting.getShellProxyRules().trim();
        if (!TextUtils.isEmpty(rules)) return parse(rules, cleanUrl(Setting.getShellProxyUrl()));
        return legacy();
    }

    private static List<Proxy> legacy() {
        String url = cleanUrl(Setting.getShellProxyUrl());
        if (TextUtils.isEmpty(url) || !isValid(url)) return List.of();
        return Proxy.arrayFrom(legacy(url));
    }

    private static List<Proxy> parse(String rules, String defaultUrl) {
        try {
            if (Json.isArray(rules)) return Proxy.arrayFrom(normalize(Json.parse(rules).getAsJsonArray(), defaultUrl));
            if (Json.isObj(rules)) return parseObject(Json.parse(rules).getAsJsonObject(), defaultUrl);
            return Proxy.arrayFrom(parseLines(rules, defaultUrl));
        } catch (Exception e) {
            SpiderDebug.log("proxy", "parse failed rulesLength=%s error=%s", rules.length(), e.getMessage());
            return List.of();
        }
    }

    private static List<Proxy> parseObject(JsonObject object, String defaultUrl) {
        if (object.has("proxy")) return Proxy.arrayFrom(normalize(object.getAsJsonArray("proxy"), defaultUrl));
        JsonArray array = new JsonArray();
        array.add(object);
        return Proxy.arrayFrom(normalize(array, defaultUrl));
    }

    private static JsonArray normalize(JsonArray input, String defaultUrl) {
        JsonArray output = new JsonArray();
        for (int i = 0; i < input.size(); i++) {
            if (!input.get(i).isJsonObject()) continue;
            JsonObject object = input.get(i).getAsJsonObject().deepCopy();
            object.addProperty("name", NAME);
            fillDefaultUrl(object, defaultUrl);
            output.add(object);
        }
        return output;
    }

    private static void fillDefaultUrl(JsonObject object, String defaultUrl) {
        if (object.has("urls") && object.get("urls").isJsonArray() && !object.getAsJsonArray("urls").isEmpty()) return;
        if (TextUtils.isEmpty(defaultUrl)) return;
        object.add("urls", urls(defaultUrl));
    }

    private static JsonArray parseLines(String rules, String defaultUrl) {
        JsonArray array = new JsonArray();
        int index = 0;
        for (String line : rules.split("\\r?\\n")) {
            JsonObject object = parseLine(line.trim(), ++index, defaultUrl);
            if (object != null) array.add(object);
        }
        return array;
    }

    private static JsonObject parseLine(String line, int index, String defaultUrl) {
        if (TextUtils.isEmpty(line) || line.startsWith("#")) return null;
        String[] parts = line.split("\\s+", 2);
        String hosts = parts.length > 1 ? parts[0].trim() : line.trim();
        String urls = parts.length > 1 ? parts[1].trim() : defaultUrl;
        if (parts.length == 1 && looksLikeProxyUrl(hosts)) {
            urls = hosts;
            hosts = "*";
        }
        if (TextUtils.isEmpty(hosts) || TextUtils.isEmpty(urls)) return null;
        JsonObject object = new JsonObject();
        object.addProperty("name", NAME);
        object.add("hosts", array(hosts));
        object.add("urls", array(urls));
        return object;
    }

    private static JsonArray legacy(String url) {
        JsonObject object = new JsonObject();
        object.addProperty("name", NAME);
        object.add("hosts", hosts());
        object.add("urls", urls(url));
        JsonArray array = new JsonArray();
        array.add(object);
        return array;
    }

    private static JsonArray hosts() {
        return array(Setting.getShellProxyHosts());
    }

    private static JsonArray urls(String url) {
        return array(url);
    }

    private static JsonArray array(String text) {
        JsonArray array = new JsonArray();
        for (String item : text.split(",")) {
            String value = item.trim();
            if (!TextUtils.isEmpty(value)) array.add(value);
        }
        if (array.isEmpty()) array.add("*");
        return array;
    }

    public static String cleanUrl(String url) {
        String value = url == null ? "" : url.trim();
        return "socks5://".equalsIgnoreCase(value) ? "" : value;
    }

    private static String safeUrl(String url) {
        String value = cleanUrl(url);
        if (TextUtils.isEmpty(value)) return "";
        Uri uri = Uri.parse(value);
        return uri.getScheme() + "://" + uri.getHost() + ":" + uri.getPort();
    }

    public static boolean isValid(String url) {
        url = cleanUrl(url);
        if (TextUtils.isEmpty(url)) return false;
        Uri uri = Uri.parse(url);
        String scheme = uri.getScheme();
        return scheme != null && (scheme.startsWith("http") || scheme.startsWith("socks")) && uri.getHost() != null && uri.getPort() > 0;
    }

    private static boolean looksLikeProxyUrl(String text) {
        Uri uri = Uri.parse(text);
        String scheme = uri.getScheme();
        return scheme != null && (scheme.startsWith("http") || scheme.startsWith("socks"));
    }

    public static boolean isValidRules(String rules, String defaultUrl) {
        String text = rules == null ? "" : rules.trim();
        String url = cleanUrl(defaultUrl);
        if (TextUtils.isEmpty(text)) return TextUtils.isEmpty(url) || isValid(url);
        if (!TextUtils.isEmpty(url) && !isValid(url)) return false;
        List<Proxy> items = parse(text, url);
        if (items.isEmpty()) return false;
        for (Proxy proxy : items) {
            proxy.init();
            if (proxy.getHosts().isEmpty() || proxy.getProxies().isEmpty()) return false;
        }
        return true;
    }

    public static boolean isValidRules(String rules) {
        return isValidRules(rules, Setting.getShellProxyUrl());
    }

    public static int count() {
        return getRules().size();
    }

}
