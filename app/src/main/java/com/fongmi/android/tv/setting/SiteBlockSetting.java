package com.fongmi.android.tv.setting;

import android.text.TextUtils;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.bean.History;
import com.fongmi.android.tv.bean.Site;
import com.github.catvod.utils.Prefers;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class SiteBlockSetting {

    public static final String KEY = "site_block_keys";
    // 系统强制屏蔽名单（因导致崩溃的 jar 被屏蔽的站点）：不可解除，恢复初始设置亦保留
    public static final String LOCK_KEY = "site_block_locked";
    private static final Type TYPE = new TypeToken<List<String>>() {}.getType();

    public static boolean isBlocked(Site site) {
        return site != null && (isBlocked(site.getKey()) || JarBlockSetting.isBlocked(site.getJar()));
    }

    public static boolean isBlocked(String key) {
        return !TextUtils.isEmpty(key) && keys().contains(key);
    }

    public static boolean isLocked(Site site) {
        return site != null && locked().contains(site.getKey());
    }

    public static boolean isLocked(String key) {
        return !TextUtils.isEmpty(key) && locked().contains(key);
    }

    /**
     * 永久屏蔽站源且不可解禁：加入屏蔽名单 + 强制锁名单。
     * 用于「同一站源累计 2 次事故」升级处置（SiteIncidentSetting 触发）。
     */
    public static void blockForever(String key) {
        if (TextUtils.isEmpty(key)) return;
        Set<String> keys = keys();
        if (keys.add(key)) save(keys);
        lock(key);
        // 永久屏蔽后同步移除该站点源的历史观看记录，避免历史页仍残留可点但已失效的条目
        History.deleteBySite(key);
    }

    public static void lock(String key) {
        if (TextUtils.isEmpty(key)) return;
        Set<String> keys = locked();
        if (keys.add(key)) Prefers.put(LOCK_KEY, App.gson().toJson(new ArrayList<>(keys)));
    }

    public static boolean toggle(Site site) {
        boolean blocked = !isBlocked(site);
        setBlocked(site, blocked);
        return blocked;
    }

    public static void setBlocked(Site site, boolean blocked) {
        if (site == null || TextUtils.isEmpty(site.getKey())) return;
        if (!blocked && locked().contains(site.getKey())) return; // 强制屏蔽不可解除
        Set<String> keys = keys();
        if (blocked) keys.add(site.getKey());
        else keys.remove(site.getKey());
        save(keys);
    }

    /**
     * 将一组站点同时设为「参与换源」：可换源（changeable=true）且解除屏蔽。
     * 用于新订阅添加成功后的自动批量设置。
     */
    public static void enableChange(List<Site> sites) {
        if (sites == null) return;
        for (Site site : sites) {
            if (site == null || site.isEmpty()) continue;
            try {
                site.setChangeable(true).save();
            } catch (Throwable ignored) {
            }
            setBlocked(site, false);
        }
    }

    public static List<Site> filter(List<Site> sites, boolean includeBlocked) {
        List<Site> items = new ArrayList<>();
        if (sites == null) return items;
        Set<String> blockedKeys = includeBlocked ? new LinkedHashSet<>() : keys();
        for (Site site : sites) {
            if (site == null || site.isHide()) continue;
            if (!includeBlocked && blockedKeys.contains(site.getKey())) continue;
            if (!includeBlocked && JarBlockSetting.isBlocked(site.getJar())) continue;
            items.add(site);
        }
        return items;
    }

    public static void clear() {
        save(locked()); // 恢复初始设置保留系统强制屏蔽项
    }

    private static Set<String> locked() {
        try {
            List<String> items = App.gson().fromJson(Prefers.getString(LOCK_KEY, "[]"), TYPE);
            return new LinkedHashSet<>(items == null ? new ArrayList<>() : items);
        } catch (Exception e) {
            return new LinkedHashSet<>();
        }
    }

    private static Set<String> keys() {
        try {
            List<String> items = App.gson().fromJson(Prefers.getString(KEY, "[]"), TYPE);
            return new LinkedHashSet<>(items == null ? new ArrayList<>() : items);
        } catch (Exception e) {
            return new LinkedHashSet<>();
        }
    }

    private static void save(Set<String> keys) {
        Prefers.put(KEY, App.gson().toJson(new ArrayList<>(keys)));
    }
}
