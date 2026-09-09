package com.fongmi.android.tv.setting;

import android.text.TextUtils;

import com.fongmi.android.tv.App;
import com.github.catvod.utils.Prefers;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 「导致崩溃的播放路线（spider jar 地址）」永久黑名单：
 * 第三方 jar 的 Init 子线程崩溃被免疫捕获后，其 jar 地址写入本名单——
 * 之后任何订阅源引用同一 jar 均不再加载，且无解除入口。
 */
public class JarBlockSetting {

    public static final String KEY = "jar_block_urls";
    private static final Type TYPE = new TypeToken<List<String>>() {}.getType();

    public static boolean isBlocked(String jar) {
        return !TextUtils.isEmpty(jar) && urls().contains(jar);
    }

    /**
     * 按基础地址（不含 ;md5; 后缀）匹配黑名单：黑名单存的是配置原始 jar 值，
     * 添加前置扫描收集到的是去后缀基础地址，需逐条比对前缀。
     */
    public static boolean blocksBase(String base) {
        if (TextUtils.isEmpty(base)) return false;
        for (String url : urls()) {
            if (url.equals(base) || url.startsWith(base + ";md5;")) return true;
        }
        return false;
    }

    public static void add(String jar) {
        if (TextUtils.isEmpty(jar)) return;
        Set<String> urls = urls();
        if (urls.add(jar)) save(urls);
    }

    /**
     * 进程死亡瞬间（shutdown hook）使用的同步登记：apply() 异步落盘可能来不及写
     * 进程就没了，这里强制 commit + 同步写黑名单文件，保证「临死拉黑」必然持久。
     */
    public static void addSync(String jar) {
        if (TextUtils.isEmpty(jar)) return;
        Set<String> urls = urls();
        urls.add(jar);
        try {
            Prefers.getPrefers().edit().putString(KEY, App.gson().toJson(new ArrayList<>(urls))).commit();
        } catch (Throwable ignored) {
        }
    }

    public static List<String> get() {
        return new ArrayList<>(urls());
    }

    private static Set<String> urls() {
        try {
            List<String> items = App.gson().fromJson(Prefers.getString(KEY, "[]"), TYPE);
            return new LinkedHashSet<>(items == null ? new ArrayList<>() : items);
        } catch (Exception e) {
            return new LinkedHashSet<>();
        }
    }

    private static void save(Set<String> urls) {
        Prefers.put(KEY, App.gson().toJson(new ArrayList<>(urls)));
    }
}
