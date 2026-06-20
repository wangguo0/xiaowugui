package com.fongmi.android.tv.utils;

import java.util.Locale;

public class Github {

    private static final String GITHUB = "https://github.com/fish2018/webhtv/releases/latest/download";
    private static final String CNB = "https://cnb.cool/fish2018/webhtv/-/git/raw/main";

    private static String getUrl(String name) {
        String base = isZhLocale() ? CNB : GITHUB;
        return base + (base.contains("/releases/") ? "/" : "/apk/") + name;
    }

    private static boolean isZhLocale() {
        Locale locale = Locale.getDefault();
        return "zh".equalsIgnoreCase(locale.getLanguage()) || "CN".equalsIgnoreCase(locale.getCountry());
    }

    public static String getJson(String name) {
        return getUrl(name + ".json");
    }

    public static String getApk(String name) {
        return getUrl(name + ".apk");
    }
}
