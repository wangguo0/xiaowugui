package com.fongmi.android.tv.utils;

import com.fongmi.android.tv.BuildConfig;

public final class AppVersion {

    private AppVersion() {
    }

    public static String fullName() {
        String tag = BuildConfig.BUILD_TAG == null ? "" : BuildConfig.BUILD_TAG.trim();
        if (tag.isEmpty()) tag = BuildConfig.VERSION_NAME + "-" + BuildConfig.BUILD_TIME;
        return stripPrefix(tag);
    }

    public static boolean isCurrent(String name) {
        return stripPrefix(name).equals(stripPrefix(fullName()));
    }

    // 发布产物名与界面显示统一用拼音机型名，与 CI 上传的 Release 附件名保持一致（mobile→shouji、leanback→dianshi）
    public static String modeName() {
        String mode = BuildConfig.FLAVOR_mode;
        return "mobile".equals(mode) ? "shouji" : "leanback".equals(mode) ? "dianshi" : mode;
    }

    // 机型串：shouji-arm64_v8a / dianshi-armeabi_v7a
    public static String deviceName() {
        return modeName() + "-" + BuildConfig.FLAVOR_abi;
    }

    public static String stripPrefix(String value) {
        if (value == null) return "";
        value = value.trim();
        return value.startsWith("v") && value.length() > 1 && Character.isDigit(value.charAt(1)) ? value.substring(1) : value;
    }
}
