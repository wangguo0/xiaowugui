package com.fongmi.android.tv.utils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

// GitHub 访问加速：对 api.github.com 的 JSON 请求与 releases 文件下载尝试
// gh-proxy 系镜像前缀，并发试错、首个合法结果胜出；全部失败返回 null 由调用方回退直连。
// 使用独立裸 OkHttpClient，不经过 App 爬虫网络栈的代理/DNS/拦截器（ShareAppDialog 教训）。
public class GithubProxy {

    // 已验证可用的 gh-proxy 系镜像前缀（与 ShareAppDialog 线路同源）
    public static final String[] PREFIXES = {
            "https://gh-proxy.com/",
            "https://ghfast.top/",
            "https://ghproxy.net/",
            "https://gh.llkk.cc/",
            "https://mirror.ghproxy.com/",
    };

    private static final String UA = "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36";
    private static final String TAG = "GithubProxy";
    private static final int PROBE_TIMEOUT = 4000;
    private static final int FETCH_BUDGET = 5000;
    private static final int PROBE_BUDGET = 8000;

    private static final OkHttpClient PROBE = new OkHttpClient.Builder()
            .connectTimeout(PROBE_TIMEOUT, TimeUnit.MILLISECONDS)
            .readTimeout(PROBE_TIMEOUT, TimeUnit.MILLISECONDS)
            .writeTimeout(PROBE_TIMEOUT, TimeUnit.MILLISECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build();
    private static final OkHttpClient FETCH = new OkHttpClient.Builder()
            .connectTimeout(PROBE_TIMEOUT, TimeUnit.MILLISECONDS)
            .readTimeout(PROBE_TIMEOUT, TimeUnit.MILLISECONDS)
            .writeTimeout(PROBE_TIMEOUT, TimeUnit.MILLISECONDS)
            .build();

    // 会话级缓存：JSON 与 APK 线路可能不同（部分镜像不代理 api），分开缓存
    private static final AtomicReference<String> JSON_PREFIX = new AtomicReference<>();
    private static final AtomicReference<String> FILE_PREFIX = new AtomicReference<>();

    private GithubProxy() {
    }

    // 拉取 JSON（api.github.com）：镜像命中返回响应体，全部失败返回 null（调用方回退直连）
    public static String fetchJson(String url) {
        String cached = JSON_PREFIX.get();
        if (cached != null) {
            String body = get(cached + url);
            if (isJson(body)) return body;
            JSON_PREFIX.compareAndSet(cached, null);
        }
        AtomicReference<String> holder = new AtomicReference<>();
        AtomicReference<String> winner = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(PREFIXES.length);
        for (String prefix : PREFIXES) {
            Task.largeExecutor().execute(() -> {
                try {
                    if (holder.get() != null) return;
                    String body = get(prefix + url);
                    if (isJson(body) && holder.compareAndSet(null, body)) winner.set(prefix);
                } catch (Exception ignored) {
                } finally {
                    latch.countDown();
                }
            });
        }
        await(latch, FETCH_BUDGET);
        cancel();
        if (holder.get() == null) return null;
        JSON_PREFIX.set(winner.get());
        return holder.get();
    }

    // 清除 JSON 线路缓存（缓存线路返回异常时由调用方触发重探）
    public static void clearJsonCache() {
        JSON_PREFIX.set(null);
    }

    // 加速下载直链（APK）：并发 GET 探测「前缀+直链」，200/3xx 即视为可用，取最快线路；
    // 无可用线路时返回原地址，探测失败永不阻塞
    public static String accelerate(String url) {
        String cached = FILE_PREFIX.get();
        if (cached != null) {
            if (test(cached + url)) return cached + url;
            FILE_PREFIX.compareAndSet(cached, null);
        }
        Map<String, Long> result = new ConcurrentHashMap<>();
        CountDownLatch latch = new CountDownLatch(PREFIXES.length);
        for (String prefix : PREFIXES) {
            Task.largeExecutor().execute(() -> {
                try {
                    long start = System.currentTimeMillis();
                    if (test(prefix + url)) result.put(prefix, System.currentTimeMillis() - start);
                } finally {
                    latch.countDown();
                }
            });
        }
        await(latch, PROBE_BUDGET);
        cancel();
        String best = result.entrySet().stream().min(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
        if (best == null) return url;
        FILE_PREFIX.set(best);
        return best + url;
    }

    private static String get(String url) {
        try (Response res = FETCH.newCall(new Request.Builder().url(url).addHeader("User-Agent", UA).build()).execute()) {
            return res.body() == null ? null : res.body().string();
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean test(String url) {
        Call call = PROBE.newCall(new Request.Builder().url(url).addHeader("User-Agent", UA).tag(TAG).build());
        try (Response res = call.execute()) {
            int code = res.code();
            if (code == 200) return true;
            return code >= 300 && code < 400 && !res.header("Location", "").isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean isJson(String body) {
        if (body == null) return false;
        String text = body.trim();
        try {
            if (text.startsWith("[")) new JSONArray(text);
            else if (text.startsWith("{")) new JSONObject(text);
            else return false;
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static void await(CountDownLatch latch, long budget) {
        try {
            latch.await(budget, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void cancel() {
        for (Call call : PROBE.dispatcher().queuedCalls()) call.cancel();
        for (Call call : PROBE.dispatcher().runningCalls()) call.cancel();
    }
}
