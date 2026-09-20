package com.fongmi.android.tv.utils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

// GitHub 访问加速：镜像线路从 mirror-cn 仓库动态拉取（与分享软件同源，上限 24 条、会话级缓存），
// 拉取失败时线路列表为空，唯一兜底 = GitHub 直连。
// 所有经镜像的 URL 统一追加 ?t=毫秒时间戳：镜像按完整 URL（含 query）做缓存键，时间戳强制回源，杜绝陈旧缓存命中。
// 提供两种取数模式：fetchJson 首个合法结果即返（tag 钉死/内容不可变场景）；fetchJsonAll 多源收集全部结果（由调用方取最新）。
// 使用独立裸 OkHttpClient，不经过 App 爬虫网络栈的代理/DNS/拦截器（ShareAppDialog 教训）。
public class GithubProxy {

    // 动态镜像列表源（与分享软件同源）：mirror-cn 仓库的线路清单
    private static final String RAW_LIST = "https://raw.githubusercontent.com/AClon314/mirror-cn/refs/heads/main/src/mirror_cn/mirror_cn.py";
    private static final String[] LIST_SOURCES = {
            "https://gh-proxy.com/" + RAW_LIST,
            "https://ghfast.top/" + RAW_LIST,
            "https://ghproxy.net/" + RAW_LIST,
            "https://gh.llkk.cc/" + RAW_LIST,
            "https://mirror.ghproxy.com/" + RAW_LIST,
            "https://github.akams.cn/" + RAW_LIST,
            "https://ghproxy.1888866.xyz/" + RAW_LIST,
            RAW_LIST,
            "https://cdn.jsdelivr.net/gh/AClon314/mirror-cn@main/src/mirror_cn/mirror_cn.py"
    };
    private static final Pattern PATTERN = Pattern.compile("\"(https?://[^\"]+?)/https://github\\.com\"");
    private static final int MAX_LINES = 24;

    private static final String UA = "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36";
    private static final String TAG = "GithubProxy";
    private static final int PROBE_TIMEOUT = 4000;
    private static final int FETCH_BUDGET = 5000;
    private static final int PROBE_BUDGET = 8000;
    private static final int LIST_BUDGET = 6000;

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

    // 会话级缓存：动态镜像列表（null=尚未拉取）、JSON 与 APK 线路（部分镜像不代理 api），分开缓存
    private static final AtomicReference<List<String>> PREFIXES = new AtomicReference<>();
    private static final AtomicReference<String> JSON_PREFIX = new AtomicReference<>();
    private static final AtomicReference<String> FILE_PREFIX = new AtomicReference<>();

    private GithubProxy() {
    }

    // 多源收集的单条结果：响应体 + 是否 GitHub 直连份（平局时直连份优先采纳）
    public static final class Source {
        public final String body;
        public final boolean direct;

        Source(String body, boolean direct) {
            this.body = body;
            this.direct = direct;
        }
    }

    // 当前镜像线路列表（首次调用阻塞拉取；拉取失败返回空列表 = 仅直连兜底，下次调用会重试）
    public static List<String> prefixes() {
        List<String> cached = PREFIXES.get();
        if (cached != null) return cached;
        List<String> lines = fetchList();
        if (!lines.isEmpty()) PREFIXES.compareAndSet(null, lines);
        return lines;
    }

    // 拉取 JSON（镜像优先、首个合法结果即返）：适用于 tag 钉死等内容不可变地址；全部失败返回 null（调用方回退直连）
    public static String fetchJson(String url) {
        List<String> lines = prefixes();
        String cached = JSON_PREFIX.get();
        if (cached != null) {
            String body = get(cached + bust(url));
            if (isJson(body)) return body;
            JSON_PREFIX.compareAndSet(cached, null);
        }
        if (lines.isEmpty()) return null;
        AtomicReference<String> holder = new AtomicReference<>();
        AtomicReference<String> winner = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(lines.size());
        for (String prefix : lines) {
            Task.largeExecutor().execute(() -> {
                try {
                    if (holder.get() != null) return;
                    String body = get(prefix + bust(url));
                    if (isJson(body) && holder.compareAndSet(null, body)) winner.set(prefix);
                } catch (Exception ignored) {
                } finally {
                    latch.countDown();
                }
            });
        }
        await(latch, FETCH_BUDGET);
        if (holder.get() == null) return null;
        JSON_PREFIX.set(winner.get());
        return holder.get();
    }

    // 多源收集：镜像 + 直连并发拉同一 URL，预算内收集全部合法 JSON（含直连份标记），由调用方比较取最新。
    // 「最快线路优先」小优化：首个有效应答的镜像线路立即写入 JSON 线路缓存，供后续清单下载复用。
    public static List<Source> fetchJsonAll(String url, long budgetMs) {
        List<String> lines = prefixes();
        List<Source> result = Collections.synchronizedList(new ArrayList<>());
        AtomicReference<String> fastest = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(lines.size() + 1);
        for (String prefix : lines) {
            Task.largeExecutor().execute(() -> {
                try {
                    String body = get(prefix + bust(url));
                    if (!isJson(body)) return;
                    result.add(new Source(body, false));
                    if (fastest.compareAndSet(null, prefix)) JSON_PREFIX.set(prefix);
                } catch (Exception ignored) {
                } finally {
                    latch.countDown();
                }
            });
        }
        Task.largeExecutor().execute(() -> {
            try {
                String body = get(url);
                if (isJson(body)) result.add(new Source(body, true));
            } catch (Exception ignored) {
            } finally {
                latch.countDown();
            }
        });
        await(latch, budgetMs);
        return result;
    }

    // 清除 JSON 线路缓存（缓存线路返回异常时由调用方触发重探）
    public static void clearJsonCache() {
        JSON_PREFIX.set(null);
    }

    // 加速下载直链（APK）：并发 GET 探测「前缀+直链」，200/3xx 即视为可用，取最快线路；
    // 无可用线路时返回原地址，探测失败永不阻塞
    public static String accelerate(String url) {
        List<String> lines = prefixes();
        String target = bust(url);
        String cached = FILE_PREFIX.get();
        if (cached != null) {
            if (test(cached + target)) return cached + target;
            FILE_PREFIX.compareAndSet(cached, null);
        }
        if (lines.isEmpty()) return url;
        Map<String, Long> result = new ConcurrentHashMap<>();
        CountDownLatch latch = new CountDownLatch(lines.size());
        for (String prefix : lines) {
            Task.largeExecutor().execute(() -> {
                try {
                    long start = System.currentTimeMillis();
                    if (test(prefix + target)) result.put(prefix, System.currentTimeMillis() - start);
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
        return best + target;
    }

    // 预热文件线路：探测一次最快镜像并缓存，返回已验证前缀（无可用镜像返回 null）。
    // 调用方后续直接拼 前缀+直链，避免每个文件重复探测（省一半往返）。
    public static String warmFile(String url) {
        accelerate(url);
        return FILE_PREFIX.get();
    }

    // 镜像 URL 追加防缓存时间戳
    private static String bust(String url) {
        return url + (url.contains("?") ? "&" : "?") + "t=" + System.currentTimeMillis();
    }

    // 动态拉取镜像列表：9 个源并发，首个解析成功者胜出，去重截断 24 条
    private static List<String> fetchList() {
        AtomicReference<List<String>> holder = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(LIST_SOURCES.length);
        for (String source : LIST_SOURCES) {
            Task.largeExecutor().execute(() -> {
                try {
                    if (holder.get() != null) return;
                    List<String> lines = parseLines(get(source));
                    if (!lines.isEmpty()) holder.compareAndSet(null, lines);
                } catch (Exception ignored) {
                } finally {
                    latch.countDown();
                }
            });
        }
        await(latch, LIST_BUDGET);
        List<String> lines = holder.get();
        return lines == null ? Collections.emptyList() : lines;
    }

    private static List<String> parseLines(String content) {
        List<String> lines = new ArrayList<>();
        if (content == null || content.isEmpty()) return lines;
        Matcher matcher = PATTERN.matcher(content);
        while (matcher.find() && lines.size() < MAX_LINES) {
            String line = matcher.group(1);
            if (!line.endsWith("/")) line = line + "/";
            if (!line.contains("github.com") && !lines.contains(line)) lines.add(line);
        }
        return lines;
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
