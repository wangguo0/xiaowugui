package com.fongmi.android.tv.service;

import android.app.Service;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.Process;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fongmi.android.tv.api.Decoder;
import com.fongmi.android.tv.api.loader.BaseLoader;
import com.fongmi.android.tv.bean.Depot;
import com.fongmi.android.tv.utils.UrlUtil;
import com.github.catvod.crawler.Spider;
import com.github.catvod.utils.Json;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 源安全探测服务，运行在独立的 {@code :probe} 子进程。
 * <p>
 * 用真实包名/软件名完整复现「添加源后」的执行链：下载 jar → DexClassLoader 加载 →
 * spider.init(ext) → homeContent()/liveContent()。若源含包名/环境检测并触发崩溃、
 * System.exit 或 native 异常，死的是本子进程，主进程通过 Binder 死亡通知立即判定「危险」，
 * 从而在添加前拦截。Java 层可捕获异常（网络抖动等）不视为崩溃，不阻止添加。
 * <p>
 * 探测结束或超时后立即自杀回收，绝不写入任何用户数据。
 */
public class ProbeService extends Service {

    public static final String EXTRA_URL = "url";
    public static final String EXTRA_TYPE = "type";
    public static final String EXTRA_KILL = "killJars"; // 静态扫描命中「自杀」特征的 jar 地址，优先探测引用它们的站点

    // 主进程 -> 子进程
    public static final int WHAT_START = 1;
    // 子进程 -> 主进程
    public static final int WHAT_PROGRESS = 2;
    public static final int WHAT_RESULT = 3;

    // 探测结论
    public static final int V_PASS = 0;        // 全部站点正常，无崩溃
    public static final int V_DANGEROUS = 1;   // 子进程被源代码杀死（由主进程 Binder 死亡通知判定）
    public static final int V_INVALID = 2;     // 配置无法拉取或解析
    public static final int V_TIMEOUT = 3;     // 复核超时：不确定，主进程按放行处理（fail-open）

    private static final String TAG = "Probe";
    private static final long TOTAL_MS = 240_000L;   // 单源总超时（大源站点多、JS 引擎慢，给足余量）
    private static final long SITE_MS = 25_000L;     // 单站点执行上限，超时跳过继续下一站点
    private static final long EXIT_DELAY_MS = 5_000L; // 终局 reply 后延迟自杀，确保消息先于死亡通知送达主进程
    private static final int MAX_LEAF = 20;          // 多仓展开叶子源上限
    private static final int MAX_DEPOT = 3;          // 多仓递归深度上限

    private final Handler handler = new Handler(Looper.getMainLooper()) {
        @Override
        public void handleMessage(@NonNull Message msg) {
            if (msg.what == WHAT_START) startProbe(msg.getData(), msg.replyTo);
        }
    };
    private final Messenger messenger = new Messenger(handler);
    private volatile boolean running;
    private volatile Messenger timeoutTo;

    // 超时：先回报 TIMEOUT（主进程据此放行），再延迟自杀回收。
    // 必须留足投递间隔，否则 Binder 死亡通知可能抢在消息入队前被主进程处理，误判为「源代码杀进程」。
    private final Runnable killer = () -> {
        if (!running) return;
        running = false;
        reply(timeoutTo, V_TIMEOUT, "sandbox timeout");
        handler.postDelayed(this::exit, EXIT_DELAY_MS);
    };

    @Nullable
    @Override
    public IBinder onBind(@Nullable Intent intent) {
        return messenger.getBinder();
    }

    private void startProbe(Bundle data, Messenger replyTo) {
        if (data == null || running) return;
        running = true;
        timeoutTo = replyTo;
        handler.postDelayed(killer, TOTAL_MS);
        String url = data.getString(EXTRA_URL, "");
        int type = data.getInt(EXTRA_TYPE, 0);
        List<String> kill = data.getStringArrayList(EXTRA_KILL);
        new Thread(() -> run(url, type, kill, replyTo), "source-probe").start();
    }

    private void run(String url, int type, List<String> kill, Messenger replyTo) {
        List<Probe> list = new ArrayList<>();
        try {
            if (type == 0) {
                collectVod(url, 0, list);
            } else if (!collectLive(url, 0, list)) {
                // 纯 m3u/txt 播放列表：不执行任何远程代码，无崩溃风险
                reply(replyTo, V_PASS, "");
                finish();
                return;
            }
        } catch (Throwable e) {
            reply(replyTo, V_INVALID, brief(e));
            finish();
            return;
        }
        prioritize(list, kill);
        int total = list.size();
        for (int i = 0; i < total; i++) {
            Probe p = list.get(i);
            progress(replyTo, i + 1, total, p.name);
            probe(p);
        }
        reply(replyTo, V_PASS, "");
        finish();
    }

    // 命中「自杀」特征的 jar 所引用的站点排到最前：确保恶意站点必然在超时前被执行，
    // 消除「恶意站点排在列表靠后 → 总超时先到 → 误放行」的竞速波动
    private void prioritize(List<Probe> list, List<String> kill) {
        if (kill == null || kill.isEmpty()) return;
        list.sort((a, b) -> Integer.valueOf(hits(a, kill)).compareTo(hits(b, kill)));
    }

    private int hits(Probe p, List<String> kill) {
        if (TextUtils.isEmpty(p.jar)) return 1;
        String base = p.jar.split(";md5;")[0].trim();
        for (String k : kill) {
            if (k.equals(base) || base.startsWith(k) || k.startsWith(base)) return 0;
        }
        return 1;
    }

    // 真实执行远程代码：getSpider 内部完成 jar 下载 + DexClassLoader 加载 + Init.init + spider.init。
    // 若 jar 检测包名并崩溃/杀进程，进程死亡即被主进程感知；可捕获的 Java 异常不阻止添加。
    // 单站点限时 SITE_MS，超时仅跳过该站点（沙箱进程最终会自杀回收，泄漏线程无副作用）。
    private void probe(Probe p) {
        Thread thread = new Thread(() -> {
            try {
                Spider spider = BaseLoader.get().getSpider(p.key, p.api, p.ext, p.jar);
                if (p.type == 0) spider.homeContent(false);
                else spider.liveContent(p.url);
            } catch (Throwable ignored) {
            }
        }, "source-probe-site");
        thread.start();
        try {
            thread.join(SITE_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // 子源地址转站点 key（进出同一字节串映射，仅用于沙箱归因定位）
    private String jarKey(String url) {
        return "jar#" + url;
    }

    private void collectVod(String url, int depth, List<Probe> out) throws Exception {
        if (depth > MAX_DEPOT || out.size() >= MAX_LEAF) return;
        JsonObject object = fetchOrNull(url);
        if (object == null) {
            // 子源非合法 JSON：疑似加密/自解压 jar（如南风 XC.json）。
            // 不解析、不丢弃，直接当成叶子 jar 沙箱探测——若其 Init/加载过程杀宿主，
            // 子进程死亡即判 DANGEROUS，从而在添加阶段拦下这类「静态检测不到」的源。
            out.add(new Probe(0, jarKey(url), url, "", "", url, ""));
            return;
        }
        if (object.has("urls")) {
            for (Depot depot : Depot.arrayFrom(object.getAsJsonArray("urls").toString())) {
                if (depot == null || TextUtils.isEmpty(depot.getUrl())) continue;
                collectVod(depot.getUrl(), depth + 1, out);
            }
            return;
        }
        String spider = Json.safeString(object, "spider");
        for (JsonElement element : Json.safeListElement(object, "sites")) {
            if (!element.isJsonObject()) continue;
            JsonObject site = element.getAsJsonObject();
            String api = str(site, "api");
            if (api.isEmpty()) continue;
            String jar = str(site, "jar");
            String u = str(site, "url");
            if (jar.isEmpty()) jar = spider;
            out.add(new Probe(0, str(site, "key"), str(site, "name"), UrlUtil.convert(api), UrlUtil.convert(str(site, "ext")), jar, u));
        }
    }

    // 返回 false 表示该直播源为纯播放列表文本（无 spider），无需探测
    private boolean collectLive(String url, int depth, List<Probe> out) throws Exception {
        if (depth > MAX_DEPOT || out.size() >= MAX_LEAF) return true;
        String text = Decoder.getJson(UrlUtil.convert(url), TAG);
        if (!Json.isObj(text)) return out.isEmpty() ? false : true;
        JsonObject object = Json.parse(text).getAsJsonObject();
        if (object.has("urls")) {
            for (Depot depot : Depot.arrayFrom(object.getAsJsonArray("urls").toString())) {
                if (depot == null || TextUtils.isEmpty(depot.getUrl())) continue;
                collectLive(depot.getUrl(), depth + 1, out);
            }
            return true;
        }
        if (object.has("lives")) {
            String spider = Json.safeString(object, "spider");
            for (JsonElement element : Json.safeListElement(object, "lives")) {
                if (!element.isJsonObject()) continue;
                JsonObject live = element.getAsJsonObject();
                String api = str(live, "api");
                if (api.isEmpty()) continue;
                String jar = str(live, "jar");
                if (jar.isEmpty()) jar = spider;
                out.add(new Probe(1, str(live, "name"), str(live, "name"), UrlUtil.convert(api), UrlUtil.convert(str(live, "ext")), jar, str(live, "url")));
            }
        }
        return true;
    }

    private JsonObject fetch(String url) throws Exception {
        String text = Decoder.getJson(UrlUtil.convert(url), TAG);
        JsonObject object = Json.parse(text).getAsJsonObject();
        if (object.has("msg")) throw new Exception(Json.safeString(object, "msg"));
        return object;
    }

    // 同 fetch，但内容非合法 JSON 对象（含加密/自解压载荷）时不抛异常，返回 null，
    // 由调用方把该子源当作叶子 jar 沙箱探测。
    private JsonObject fetchOrNull(String url) {
        try {
            return fetch(url);
        } catch (Throwable e) {
            return null;
        }
    }

    private String str(JsonObject object, String key) {
        JsonElement element = object.get(key);
        if (element == null || element.isJsonNull()) return "";
        return element.isJsonPrimitive() ? element.getAsString() : element.toString();
    }

    private void progress(Messenger replyTo, int index, int total, String name) {
        if (replyTo == null) return;
        try {
            Message msg = Message.obtain(null, WHAT_PROGRESS, index, total);
            msg.obj = name;
            replyTo.send(msg);
        } catch (Throwable ignored) {
        }
    }

    private void reply(Messenger replyTo, int verdict, String reason) {
        if (replyTo == null) return;
        try {
            Message msg = Message.obtain(null, WHAT_RESULT, verdict, 0);
            msg.obj = reason;
            replyTo.send(msg);
        } catch (Throwable ignored) {
        }
    }

    private void finish() {
        running = false;
        handler.removeCallbacks(killer);
        // 让结果消息先投递，再自杀回收子进程
        handler.postDelayed(this::exit, EXIT_DELAY_MS);
    }

    private void exit() {
        Process.killProcess(Process.myPid());
    }

    private String brief(Throwable e) {
        Throwable cause = e.getCause() == null ? e : e.getCause();
        return cause.getClass().getSimpleName() + ":" + cause.getMessage();
    }

    private static class Probe {
        final int type;
        final String key;
        final String name;
        final String api;
        final String ext;
        final String jar;
        final String url;

        Probe(int type, String key, String name, String api, String ext, String jar, String url) {
            this.type = type;
            this.key = key;
            this.name = name;
            this.api = api;
            this.ext = ext;
            this.jar = jar;
            this.url = url;
        }
    }
}
