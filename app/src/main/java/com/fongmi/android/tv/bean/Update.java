package com.fongmi.android.tv.bean;

import android.text.TextUtils;

import com.fongmi.android.tv.BuildConfig;

public class Update {

    public static final String CHANNEL_STABLE = "stable";
    public static final String CHANNEL_BETA = "beta";

    public String channel;
    public String name;
    public String desc;
    public String notes;
    public String apk;
    public String apkUrl;
    public String error;
    public String sha256;
    public String forceMsg;
    public int code;
    public long size;
    public boolean force;

    public static Update empty(String channel) {
        Update update = new Update();
        update.channel = channel;
        return update;
    }

    public boolean isBeta() {
        return CHANNEL_BETA.equals(channel);
    }

    // 该版本被开发者标记为强制更新，且当前确实落后需要升级
    public boolean isForceUpdate() {
        return force && hasUpdate();
    }

    public boolean hasManifest() {
        return !TextUtils.isEmpty(name) && !TextUtils.isEmpty(apkUrl);
    }

    // 只有清单版本码严格大于本机才视为可更新：相等（已装上）与更旧（镜像陈旧缓存）一律不算，
    // 从源头杜绝把旧包递给安装器后被系统按降级拒装
    public boolean hasUpdate() {
        if (!hasManifest()) return false;
        return code > BuildConfig.VERSION_CODE;
    }

    public String getText() {
        if (!TextUtils.isEmpty(notes)) return notes;
        if (!TextUtils.isEmpty(desc)) return desc;
        return "";
    }
}
