package com.fongmi.android.tv.utils;

import android.text.TextUtils;

import com.fongmi.android.tv.BuildConfig;
import com.github.catvod.net.OkHttp;

import org.json.JSONObject;

import java.util.concurrent.TimeUnit;

// 强制更新「最低可用版本」策略：读取仓库固定文件 update/force.json。
// 开发者可随时在 GitHub 网页改这一个文件开启/关闭强制、调整最低可用版本，无需重新构建发版。
// 拉取失败/超时/解析异常一律返回「无强制」，绝不阻断启动。
public class ForcePolicy {

    private static final long TIMEOUT_MS = TimeUnit.SECONDS.toMillis(6);

    public final boolean force;
    public final String message;

    private ForcePolicy(boolean force, String message) {
        this.force = force;
        this.message = message;
    }

    public static ForcePolicy none() {
        return new ForcePolicy(false, "");
    }

    // 拉取远端策略并判定当前版本是否低于最低可用版本
    public static ForcePolicy fetch() {
        try {
            String url = Github.getForcePolicyRaw();
            String text = GithubProxy.fetchJson(url);
            if (TextUtils.isEmpty(text)) text = OkHttp.string(url, TIMEOUT_MS);
            if (TextUtils.isEmpty(text)) return none();
            JSONObject object = new JSONObject(text);
            int minCode = object.optInt("minVersionCode", 0);
            String minName = object.optString("minVersionName", "");
            String msg = object.optString("forceMsg", "");
            boolean below = isBelow(minCode, minName);
            return new ForcePolicy(below, msg);
        } catch (Exception e) {
            return none();
        }
    }

    // 当前版本低于最低可用版本：versionCode 优先，缺失时回退 versionName 逐段比较
    private static boolean isBelow(int minCode, String minName) {
        if (minCode > 0) return BuildConfig.VERSION_CODE < minCode;
        if (!TextUtils.isEmpty(minName)) return compareVersion(BuildConfig.VERSION_NAME, minName) < 0;
        return false;
    }

    // 语义化版本逐段比较：a<b 返回负，a>b 返回正，相等返回 0
    private static int compareVersion(String a, String b) {
        String[] left = a.split("[.\\-+]");
        String[] right = b.split("[.\\-+]");
        int length = Math.max(left.length, right.length);
        for (int i = 0; i < length; i++) {
            int l = i < left.length ? parseInt(left[i]) : 0;
            int r = i < right.length ? parseInt(right[i]) : 0;
            if (l != r) return l - r;
        }
        return 0;
    }

    private static int parseInt(String value) {
        try {
            return Integer.parseInt(value);
        } catch (Exception e) {
            return 0;
        }
    }
}
