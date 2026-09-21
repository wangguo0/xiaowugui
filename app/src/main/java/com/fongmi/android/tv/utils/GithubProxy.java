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
    private static final String DIRECT = "直连";
    // 诊断日志标签：update 为更新链路专用（探测/下载），github 为共享的镜像列表拉取
    private static final String LOG_UPDATE = "update";
    private static final String LOG_GITHUB = "github";
    private static final int PROBE_TIMEOUT = 4000;
    private static final int FETCH_BUDGET = 5000;
    // 每线两段 Range（头 2KB + 尾 64KB）验证，预算相应放宽
    private static final int PROBE_BUDGET = 12000;
    private static final int LIST_BUDGET = 6000;
    private static final int HEAD_BYTES = 2048;
    private static final long TAIL_BYTES = 64 * 1024;

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
        else DiagLog.log(LOG_GITHUB, "镜像列表拉取失败：%s 个源全部不可用，本次仅 GitHub 直连兜底", LIST_SOURCES.length);
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

    // 下载线路：prefix 为空串表示 GitHub 官方直连
    public static final class Line {
        public final String prefix;
        public final long cost;

        Line(String prefix, long cost) {
            this.prefix = prefix;
            this.cost = cost;
        }
    }

    private static final OkHttpClient RANGE = new OkHttpClient.Builder()
            .connectTimeout(PROBE_TIMEOUT, TimeUnit.MILLISECONDS)
            .readTimeout(PROBE_TIMEOUT, TimeUnit.MILLISECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build();
    // 裸下载客户端：不经过 App 爬虫网络栈的全局缓存/代理/拦截器，杜绝重定向命中旧缓存
    private static final OkHttpClient DOWNLOAD = new OkHttpClient.Builder()
            .connectTimeout(PROBE_TIMEOUT, TimeUnit.MILLISECONDS)
            .readTimeout(30000, TimeUnit.MILLISECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build();

    public static okhttp3.OkHttpClient downloadClient() {
        return DOWNLOAD;
    }

    // 拼下载 URL：镜像线路带 ?t 防缓存；直连保持原样（GitHub 永不缓存）
    public static String build(Line line, String url) {
        return line.prefix.isEmpty() ? url : line.prefix + bust(url);
    }

    // 线路名（展示用）：镜像取主机名，直连返回空串由调用方本地化
    public static String name(Line line) {
        return line.prefix.isEmpty() ? "" : host(line.prefix);
    }

    // 从镜像前缀提取主机名（展示与诊断日志共用）
    private static String host(String prefix) {
        if (prefix.isEmpty()) return DIRECT;
        String value = prefix;
        int scheme = value.indexOf("://");
        if (scheme >= 0) value = value.substring(scheme + 3);
        int slash = value.indexOf('/');
        if (slash >= 0) value = value.substring(0, slash);
        return value;
    }

    // 带内容验证的并发测速：对每条线路 + 直连用 Range 拉取 APK 头 2KB，核对
    // ①总大小与清单一致（Content-Range/Content-Length）②文件头为合法 zip（PK）。
    // 只有验证通过的线路才返回（按耗时升序），从源头杜绝"下载到陈旧/残缺内容"。
    public static List<Line> probeVerified(String url, long size) {
        List<String> lines = prefixes();
        List<Line> result = Collections.synchronizedList(new ArrayList<>());
        // 诊断日志用：逐线结果与淘汰原因（每线两段 Range 往返，出问题时要能看出是谁被淘汰、为什么）
        List<String> passed = Collections.synchronizedList(new ArrayList<>());
        List<String> rejected = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch latch = new CountDownLatch(lines.size() + 1);
        for (String prefix : lines) {
            Task.largeExecutor().execute(() -> {
                try {
                    probe(prefix + bust(url), size, prefix, result, passed, rejected);
                } finally {
                    latch.countDown();
                }
            });
        }
        Task.largeExecutor().execute(() -> {
            try {
                probe(url, size, "", result, passed, rejected);
            } finally {
                latch.countDown();
            }
        });
        await(latch, PROBE_BUDGET);
        cancelRange();
        result.sort((a, b) -> Long.compare(a.cost, b.cost));
        DiagLog.log(LOG_UPDATE, "[探测] 清单size=%s 候选=%s 通过=%s 线路=%s 淘汰=%s",
                size, lines.size() + 1, passed.size(), passed, rejected);
        return result;
    }

    private static void probe(String fullUrl, long size, String prefix, List<Line> result, List<String> passed, List<String> rejected) {
        Verified verified = new Verified();
        long start = System.currentTimeMillis();
        try {
            if (!verify(fullUrl, size, verified)) {
                rejected.add(host(prefix) + ":" + verified.reason);
                return;
            }
            long cost = System.currentTimeMillis() - start;
            result.add(new Line(prefix, cost));
            passed.add(host(prefix) + "(" + cost + "ms)");
        } catch (Exception e) {
            rejected.add(host(prefix) + ":" + e.getClass().getSimpleName());
        }
    }

    // 单线验证结论：诊断日志用（淘汰原因，含观测到的 HTTP 码/总大小）
    private static final class Verified {
        String reason = "未知";
    }

    private static boolean verify(String fullUrl, long size, Verified out) {
        long total = verifyHead(fullUrl, size, out);
        if (total <= 0) return false;
        return verifyTail(fullUrl, total, out);
    }

    // 第一段：头 2KB。必须核出文件总大小且与清单 size 严格一致（拿不到总大小 = 无法证明内容正确，直接淘汰），
    // 同时核对文件头为合法 zip（PK）
    private static long verifyHead(String fullUrl, long size, Verified out) {
        Request request = new Request.Builder().url(fullUrl).addHeader("User-Agent", UA).addHeader("Range", "bytes=0-" + (HEAD_BYTES - 1)).build();
        try (Response res = RANGE.newCall(request).execute()) {
            if (!res.isSuccessful() && res.code() != 206) {
                out.reason = "HTTP" + res.code();
                return -1;
            }
            long total = contentRangeTotal(res);
            if (total <= 0) total = res.body() != null && res.code() == 200 ? getLength(res) : -1;
            if (total <= 0) {
                out.reason = "无总大小";
                return -1;
            }
            if (size > 0 && total != size) {
                out.reason = "大小不符(" + total + "≠" + size + ")";
                return -1;
            }
            byte[] head = new byte[2];
            int read = res.body() == null ? -1 : readFully(res.body(), head, 2);
            if (read != 2 || head[0] != 'P' || head[1] != 'K') {
                out.reason = "头非zip";
                return -1;
            }
            return total;
        } catch (Exception e) {
            out.reason = "头段" + e.getClass().getSimpleName();
            return -1;
        }
    }

    // 第二段：尾 64KB。尾部必须存在 zip 中央目录结束记录，证明整包结构完整、不是被截断或替换的内容
    private static boolean verifyTail(String fullUrl, long total, Verified out) {
        long start = Math.max(0, total - TAIL_BYTES);
        Request request = new Request.Builder().url(fullUrl).addHeader("User-Agent", UA).addHeader("Range", "bytes=" + start + "-" + (total - 1)).build();
        try (Response res = RANGE.newCall(request).execute()) {
            if (!res.isSuccessful() && res.code() != 206) {
                out.reason = "尾段HTTP" + res.code();
                return false;
            }
            if (!hasCentralDirectoryEnd(res.body() == null ? null : res.body().bytes())) {
                out.reason = "尾段无EOCD";
                return false;
            }
            return true;
        } catch (Exception e) {
            out.reason = "尾段" + e.getClass().getSimpleName();
            return false;
        }
    }

    // zip 中央目录结束记录（EOCD）签名 PK\x05\x06
    private static boolean hasCentralDirectoryEnd(byte[] tail) {
        if (tail == null || tail.length < 4) return false;
        for (int i = tail.length - 4; i >= 0; i--) {
            if (tail[i] == 0x50 && tail[i + 1] == 0x4B && tail[i + 2] == 0x05 && tail[i + 3] == 0x06) return true;
        }
        return false;
    }

    private static long getLength(Response res) {
        try {
            String header = res.header("Content-Length");
            return header != null ? Long.parseLong(header) : -1;
        } catch (Exception e) {
            return -1;
        }
    }

    private static long contentRangeTotal(Response res) {
        // Content-Range: bytes 0-2047/71509357
        String header = res.header("Content-Range");
        if (header == null) return -1;
        int slash = header.lastIndexOf('/');
        if (slash < 0 || slash == header.length() - 1) return -1;
        try {
            String total = header.substring(slash + 1).trim();
            return "*".equals(total) ? -1 : Long.parseLong(total);
        } catch (Exception e) {
            return -1;
        }
    }

    private static int readFully(okhttp3.ResponseBody body, byte[] buffer, int length) throws java.io.IOException {
        int offset = 0;
        while (offset < length) {
            int read = body.byteStream().read(buffer, offset, length - offset);
            if (read < 0) break;
            offset += read;
        }
        return offset;
    }

    private static void cancelRange() {
        for (Call call : RANGE.dispatcher().queuedCalls()) call.cancel();
        for (Call call : RANGE.dispatcher().runningCalls()) call.cancel();
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
        if (lines == null) return Collections.emptyList();
        DiagLog.log(LOG_GITHUB, "镜像列表拉取成功：%s 条（上限 %s）", lines.size(), MAX_LINES);
        return lines;
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
