package com.fongmi.android.tv.api.bangumi;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.bean.Bangumi;
import com.fongmi.android.tv.utils.Task;
import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.utils.Prefers;

import java.util.ArrayList;
import java.util.List;

// 追番数据仓库：getCard 接口实时抓取为主源，上次成功缓存兜底；
// 成功后随机 2~3 天内直接复用缓存不发起请求，避免高频访问被限流
public class BangumiRepository {

    public static final int SOURCE_LIVE = 0;
    public static final int SOURCE_CACHE = 1;
    public static final int SOURCE_STALE = 2;

    private static final String TAG = "TV-Bangumi";
    private static final String KEY_JSON = "bangumi_cache_json";
    private static final String KEY_TIME = "bangumi_cache_time";
    private static final String KEY_NEXT = "bangumi_next_fetch";
    private static final long BASE_INTERVAL = 48 * 60 * 60 * 1000L;
    private static final long RANDOM_RANGE = 24 * 60 * 60 * 1000L;

    private final List<Callback> callbacks = new ArrayList<>();
    private final Runnable timer;
    private List<Bangumi> result;
    private int source;
    private boolean loading;

    public interface Callback {
        void onResult(List<Bangumi> items, int source, long time);
    }

    private static class Holder {
        private static final BangumiRepository INSTANCE = new BangumiRepository();
    }

    private BangumiRepository() {
        timer = this::finish;
    }

    public static BangumiRepository get() {
        return Holder.INSTANCE;
    }

    public synchronized void load(Callback callback) {
        if (callback != null) callbacks.add(callback);
        if (loading) return;
        loading = true;
        result = null;
        source = SOURCE_LIVE;
        App.post(timer, 60000);
        Task.execute(() -> {
            List<Bangumi> items;
            try {
                items = BangumiApi.fetch();
            } catch (Exception e) {
                SpiderDebug.log(TAG, "load error=%s", e.getMessage());
                items = new ArrayList<>();
            }
            List<Bangumi> finalItems = items;
            App.post(() -> onApi(finalItems));
        });
    }

    public synchronized void refresh() {
        clear();
        load(null);
    }

    private void clear() {
        Prefers.getPrefers().edit().remove(KEY_JSON).remove(KEY_TIME).remove(KEY_NEXT).apply();
    }

    // 缓存有效（未超出随机拉取窗口）则直接回调，返回 true 表示已处理
    public synchronized boolean loadCache(Callback callback) {
        List<Bangumi> items = readCache();
        if (items.isEmpty() || System.currentTimeMillis() >= Prefers.getLong(KEY_NEXT)) {
            if (callback != null) callback.onResult(new ArrayList<>(), SOURCE_LIVE, 0);
            return false;
        }
        long time = Prefers.getLong(KEY_TIME);
        if (callback != null) callback.onResult(items, SOURCE_CACHE, time);
        return true;
    }

    private synchronized void onApi(List<Bangumi> items) {
        if (!loading) return;
        if (items == null || items.isEmpty()) {
            result = readCache();
            source = SOURCE_STALE;
        } else {
            result = items;
            source = SOURCE_LIVE;
            save(items);
        }
        finish();
    }

    private List<Bangumi> readCache() {
        try {
            return Bangumi.from(Prefers.getString(KEY_JSON));
        } catch (Exception e) {
            SpiderDebug.log(TAG, "readCache error=%s", e.getMessage());
            return new ArrayList<>();
        }
    }

    private void save(List<Bangumi> items) {
        try {
            long next = System.currentTimeMillis() + BASE_INTERVAL + (long) (Math.random() * RANDOM_RANGE);
            Prefers.getPrefers().edit()
                    .putString(KEY_JSON, Bangumi.toJson(items))
                    .putLong(KEY_TIME, System.currentTimeMillis())
                    .putLong(KEY_NEXT, next)
                    .apply();
        } catch (Exception e) {
            SpiderDebug.log(TAG, "save error=%s", e.getMessage());
        }
    }

    public long updateTime() {
        return Prefers.getLong(KEY_TIME);
    }

    private synchronized void finish() {
        if (!loading) return;
        loading = false;
        App.removeCallbacks(timer);
        // 超时未返回时退回本地缓存，保证页面不空白
        if (result == null) {
            result = readCache();
            source = SOURCE_STALE;
        }
        List<Bangumi> items = result;
        List<Callback> targets = new ArrayList<>(callbacks);
        callbacks.clear();
        long time = Prefers.getLong(KEY_TIME);
        for (Callback callback : targets) callback.onResult(items, source, time);
        result = null;
    }
}
