package com.fongmi.android.tv.utils;

import java.util.Locale;

public class Github {

    private static final String GITHUB = "https://github.com/fish2018/webhtv/releases/latest/download";
    private static final String GITHUB_API = "https://api.github.com/repos/fish2018/webhtv/releases/tags";
    private static final String CNB = "https://cnb.cool/fish2018/webhtv/-/git/raw/main";

    private static String getUrl(String name) {
        String base = isZhLocale() ? CNB : GITHUB;
        return base + (base.contains("/releases/") ? "/" : "/apk/") + name;
    }

    private static String getBetaUrl(String name) {
        return CNB + "/apk/" + name;
    }

    private static boolean isZhLocale() {
        Locale locale = Locale.getDefault();
        return "zh".equalsIgnoreCase(locale.getLanguage()) || "CN".equalsIgnoreCase(locale.getCountry());
    }

    public static String getJson(String name) {
        return getUrl(name + ".json");
    }

    public static String getJson(String name, String channel) {
        if ("beta".equals(channel)) return getBetaUrl(name + "-beta.json");
        return getJson(name);
    }

    public static String getApk(String name) {
        return getUrl(name + ".apk");
    }

    public static String getApk(String name, String channel) {
        if ("beta".equals(channel)) return getBetaUrl(name + "-beta.apk");
        return getApk(name);
    }

    public static String getAsset(String name, String channel) {
        if ("beta".equals(channel)) return getBetaUrl(name);
        return getUrl(name);
    }

    public static String getReleaseApi(String tag) {
        return GITHUB_API + "/" + tag;
    }
}
