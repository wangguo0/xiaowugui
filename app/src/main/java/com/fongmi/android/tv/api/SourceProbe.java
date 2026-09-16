package com.fongmi.android.tv.api;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.text.TextUtils;

import androidx.annotation.NonNull;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.service.ProbeService;
import com.fongmi.android.tv.utils.DiagLog;
import com.fongmi.android.tv.utils.Task;
import com.fongmi.android.tv.utils.UrlUtil;
import com.github.catvod.utils.Path;
import com.github.catvod.utils.Prefers;
import com.github.catvod.utils.Util;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 源安全检测主进程客户端（方案B：静态扫描优先 + 可疑源沙箱复核，fail-open）。
 * <p>
 * 流程：后台拉取配置 → 查缓存 → {@link SourceScanner} 静态扫描 jar 危险特征 →
 * 未命中特征直接放行（正常源零执行、秒级完成）；命中特征才绑定 {@code :probe}
 * 子进程做行为复核。复核判定从严宽出：仅子进程被真实杀死（Binder 死亡通知，
 * 即源代码调用 killProcess/System.exit 的实锤）判定危险；超时、进程异常、
 * 网络失败一律放行，避免误杀正常源。
 */
public final class SourceProbe {

    public static final int PASS = ProbeService.V_PASS;
    public static final int DANGEROUS = ProbeService.V_DANGEROUS;
    public static final int INVALID = ProbeService.V_INVALID;
    // 预警级：沙箱中被杀但从未在真实运行中崩溃。沙箱环境（进程名/端口/Activity 等）
    // 与主进程存在天然差异，其"杀进程实锤"在主进程未必成立，因此不直接拦截，
    // 交由「25 秒运行试用」（{@link TrialRun}）终审。
    public static final int SUSPECT = 4;

    // 检测阶段（供 UI 进度条分区）
    public static final int STAGE_FETCH = 0;    // 拉取配置
    public static final int STAGE_SCAN = 1;     // 静态扫描 jar
    public static final int STAGE_SANDBOX = 2;  // 沙箱行为复核

    public interface Listener {
        // 阶段切换
        default void onStage(int stage) {
        }

        // 静态扫描子进度（jar index/total）
        default void onScan(int index, int total, String name) {
        }

        void onProgress(int index, int total, String name);

        void onResult(int verdict, String reason);
    }

    private static final String PREF_PREFIX = "source_probe_v3_";
    private static final String RUNTIME_PREFIX = "source_probe_rt_";
    private static final long CACHE_TTL = 7 * 24 * 3600_000L;
    private static final long SANDBOX_TIMEOUT = 270_000L; // 复核兜底超时（>沙箱总超时240s+退出间隔），超时按放行处理
    private static final long DEATH_GRACE_MS = 500L;      // 死亡通知宽限期：等终局 reply 先送达，防超时自杀被误判为恶意

    private static class Job {
        final String url;
        final int type;
        final Listener listener;
        final AtomicBoolean done = new AtomicBoolean(false);
        volatile String key;
        volatile ServiceConnection connection;
        volatile IBinder binder;
        volatile Runnable timeout;
        // 是否收到过子进程「终局 reply」（PASS/INVALID/TIMEOUT）。
        // 恶意源在发出若干进度后杀进程，进度不算终局；超时场景子进程先发 TIMEOUT
        // 再延迟自杀，死亡通知到达时该标记必为 true，据此与真实恶意区分。
        volatile boolean finalReply;
        // 静态扫描是否完整（全部 jar 均成功下载并扫描）。不完整的 PASS 不缓存，
        // 避免「某次 jar 下载失败导致偶然放行」被锁死 7 天，下次仍会重新检测。
        volatile boolean scanComplete;
        volatile java.util.List<String> killJars;

        Job(String url, int type, Listener listener) {
            this.url = url;
            this.type = type;
            this.listener = listener;
        }
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Deque<Job> QUEUE = new ArrayDeque<>();
    private static Job current;
    private static final Messenger REPLY = new Messenger(new Handler(Looper.getMainLooper()) {
        @Override
        public void handleMessage(@NonNull Message msg) {
            Job job = current;
            if (job == null) return;
            if (msg.what == ProbeService.WHAT_PROGRESS) {
                job.listener.onProgress(msg.arg1, msg.arg2, (String) msg.obj);
            } else if (msg.what == ProbeService.WHAT_RESULT) {
                job.finalReply = true;
                if (msg.arg1 == ProbeService.V_TIMEOUT) finish(job, PASS, "sandbox timeout, fail-open");
                else finish(job, msg.arg1, (String) msg.obj);
            }
        }
    });

    private SourceProbe() {
    }

    /**
     * 异步检测，回调均在主线程执行。
     */
    public static void check(String url, int type, Listener listener) {
        Job job = new Job(url, type, listener);
        MAIN.post(() -> {
            QUEUE.addLast(job);
            pump();
        });
    }

    /**
     * 同步检测，供源合并等后台流程调用（必须在非主线程使用）。
     *
     * @return PASS / DANGEROUS / INVALID
     */
    public static int checkSync(String url, int type) {
        return checkSync(url, type, null);
    }

    public static int checkSync(String url, int type, Listener progress) {
        Object lock = new Object();
        int[] result = {-1};
        check(url, type, new Listener() {
            @Override
            public void onScan(int index, int total, String name) {
                if (progress != null) progress.onScan(index, total, name);
            }

            @Override
            public void onProgress(int index, int total, String name) {
                if (progress != null) progress.onProgress(index, total, name);
            }

            @Override
            public void onResult(int verdict, String reason) {
                synchronized (lock) {
                    result[0] = verdict;
                    lock.notifyAll();
                }
            }
        });
        synchronized (lock) {
            while (result[0] < 0) {
                try {
                    lock.wait(SANDBOX_TIMEOUT + 30_000L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return PASS;
                }
                if (result[0] < 0) return PASS; // 兜底超时：放行
            }
        }
        return result[0];
    }

    private static void pump() {
        if (current != null || QUEUE.isEmpty()) return;
        Job job = QUEUE.pollFirst();
        current = job;
        prepare(job);
    }

    // 拉配置 → 缓存 → 静态扫描；仅可疑源进入沙箱复核
    private static void prepare(Job job) {
        MAIN.post(() -> {
            if (!job.done.get()) job.listener.onStage(STAGE_FETCH);
        });
        Task.submitLarge(() -> {
            // 运行验证实锤优先（永久有效）：同一地址曾真机崩溃 / 尝试杀进程 → 直接拦截
            int runtime = readRuntime(job.url);
            if (runtime == DANGEROUS) {
                MAIN.post(() -> finish(job, DANGEROUS, "runtime confirmed dangerous"));
                return;
            }
            if (runtime == PASS) {
                // 试运行通过（永久信任）：直接放行，免重复检测与试用
                job.scanComplete = true;
                MAIN.post(() -> finish(job, PASS, "runtime verified"));
                return;
            }
            String content = fetch(job.url);
            if (content == null) {
                MAIN.post(() -> finish(job, PASS, "config fetch failed, fail-open"));
                return;
            }
            job.key = cacheKey(job.url, content);
            int cached = readCache(job.key);
            if (cached >= 0) {
                job.scanComplete = true;
                MAIN.post(() -> finish(job, cached, "cached"));
                return;
            }
            MAIN.post(() -> {
                if (!job.done.get()) job.listener.onStage(STAGE_SCAN);
            });
            SourceScanner.ScanResult scan = SourceScanner.scan(job.url, job.type, content, (i, n, name) -> MAIN.post(() -> {
                if (!job.done.get()) job.listener.onScan(i, n, name);
            }));
            job.scanComplete = scan.complete;
            job.killJars = new ArrayList<>(scan.killJars);
            if (!scan.suspicious) {
                MAIN.post(() -> finish(job, PASS, "static scan clean"));
                return;
            }
            MAIN.post(() -> {
                if (!job.done.get()) job.listener.onStage(STAGE_SANDBOX);
                bind(job);
            });
        });
    }

    private static String fetch(String url) {
        try {
            return Decoder.getJson(UrlUtil.convert(url), "Probe");
        } catch (Throwable e) {
            return null;
        }
    }

    // 可疑源沙箱复核：进程死亡 = 危险实锤；其余不确定情况全部放行
    private static void bind(Job job) {
        Context context = App.get();
        Intent intent = new Intent(context, ProbeService.class);
        ServiceConnection connection = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                if (current != job || job.done.get()) return;
                job.binder = service;
                try {
                    service.linkToDeath(() -> MAIN.postDelayed(() -> {
                        // 死亡定案：已收到终局 reply（如超时自杀）则忽略；
                        // 从未收到终局 reply 的进程死亡 = 源代码在沙箱环境杀进程。
                        // 注意沙箱与主进程环境存在天然差异，此处仅判「预警」，
                        // 是否真危险交由「25 秒运行试用」终审。
                        if (current != job || job.done.get()) return;
                        if (job.finalReply) finish(job, PASS, "process died after final reply, fail-open");
                        else finish(job, SUSPECT, "probe process killed by source");
                    }, DEATH_GRACE_MS), 0);
                } catch (RemoteException ignored) {
                }
                job.timeout = () -> finish(job, PASS, "sandbox watchdog, fail-open");
                MAIN.postDelayed(job.timeout, SANDBOX_TIMEOUT);
                try {
                    Message msg = Message.obtain(null, ProbeService.WHAT_START);
                    msg.replyTo = REPLY;
                    Bundle data = new Bundle();
                    data.putString(ProbeService.EXTRA_URL, job.url);
                    data.putInt(ProbeService.EXTRA_TYPE, job.type);
                    msg.setData(data);
                    new Messenger(service).send(msg);
                } catch (Throwable e) {
                    finish(job, PASS, "sandbox start failed, fail-open");
                }
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                // 与 linkToDeath 同一定案逻辑：等宽限期让终局 reply 先送达
                MAIN.postDelayed(() -> {
                    if (current != job || job.done.get()) return;
                    if (job.finalReply) finish(job, PASS, "process died after final reply, fail-open");
                    else finish(job, SUSPECT, "probe process died");
                }, DEATH_GRACE_MS);
            }
        };
        job.connection = connection;
        try {
            if (!context.bindService(intent, connection, Context.BIND_AUTO_CREATE)) {
                finish(job, PASS, "bind rejected, fail-open");
            }
        } catch (Throwable e) {
            finish(job, PASS, "bind error, fail-open");
        }
    }

    private static void finish(Job job, int verdict, String reason) {
        if (job == null || !job.done.compareAndSet(false, true)) return;
        if (job.timeout != null) MAIN.removeCallbacks(job.timeout);
        if (job.connection != null) {
            try {
                App.get().unbindService(job.connection);
            } catch (Throwable ignored) {
            }
        }
        // DANGEROUS（运行验证实锤）与 SUSPECT（沙箱预警）一律按内容缓存；
        // PASS 仅在扫描完整（全部 jar 成功定性）或缓存命中时写入，
        // 偶然放行（jar 下载失败/扫描截断）不锁死 7 天，下次添加会重新检测
        if (verdict == DANGEROUS || verdict == SUSPECT || (verdict == PASS && job.scanComplete)) writeCache(job, verdict);
        DiagLog.log("probe", "url=%s verdict=%s reason=%s", job.url, verdictName(verdict), reason);
        Listener listener = job.listener;
        if (current == job) current = null;
        listener.onResult(verdict, reason);
        MAIN.post(SourceProbe::pump);
    }

    private static String verdictName(int verdict) {
        return switch (verdict) {
            case PASS -> "pass";
            case DANGEROUS -> "dangerous";
            case SUSPECT -> "suspect";
            default -> "invalid";
        };
    }

    // ===== 运行验证缓存（按地址，与内容缓存独立）：真机试运行终审结论，永久有效 =====

    /**
     * 记录「25 秒运行试用」终审结论（由 TrialRun 调用）：
     * PASS = 试运行通过（永久信任，免重复检测/试用）；
     * DANGEROUS = 真机崩溃或尝试杀进程实锤（永久拉黑）。
     */
    public static void markRuntime(String url, int verdict) {
        try {
            Prefers.put(RUNTIME_PREFIX + Util.md5(url), verdict + "|" + System.currentTimeMillis());
        } catch (Throwable ignored) {
        }
    }

    /**
     * 该地址是否曾通过 8 秒试运行（合并源流程据此免检测）。
     */
    public static boolean isRuntimePassed(String url) {
        return readRuntime(url) == PASS;
    }

    /**
     * 该地址是否被试运行实锤为危险（永久黑名单）。
     */
    public static boolean isRuntimeDangerous(String url) {
        return readRuntime(url) == DANGEROUS;
    }

    /**
     * 添加前置静态扫描进度回调（工作线程触发，UI 侧自行切主线程）。
     * phase：1=拉取订阅配置，2=解析站点与线路，3=逐个扫描 jar（total 为 jar 总数）。
     */
    public interface ScanProgress {
        void onPhase(int phase, int total);

        void onJar(int index, int total, String jar);
    }

    /**
     * 添加前置静态扫描（http 与本地文件源通用，不执行任何远程代码）：
     * 拉取配置 → 收集全部 jar → 逐个 DEX 解析，命中「自杀退出」实锤特征即判危险；
     * jar 曾被崩溃实锤拉黑（JarBlockSetting）同样判危险——添加后站点必被过滤为空，
     * 与其静默添加一个不可用源，不如阻止添加并告知用户。
     * 必须在非主线程调用。读取 / 扫描异常一律放行（fail-open，绝不误杀正常源）。
     */
    public static boolean scanDangerous(String url, int type) {
        return scanDangerous(url, type, null);
    }

    /**
     * 地址清洗：去除首尾全部空白（半角空格、全角空格 U+3000、不间断空格 U+00A0、
     * 制表符、换行）。添加/检测/试运行/存储各环节统一使用清洗后的地址，避免
     * 「尾部空格」这类格式问题污染缓存 key 与运行时标记（同一地址出现两套记录），
     * 也避免带空格请求触发服务器错误页被可疑载荷启发式误判为恶意代码。
     */
    public static String cleanUrl(String url) {
        return url == null ? "" : url.replaceAll("^[\\s\\u00A0\\u3000]+|[\\s\\u00A0\\u3000]+$", "");
    }

    public static boolean scanDangerous(String url, int type, ScanProgress progress) {
        try {
            url = cleanUrl(url);
            if (progress != null) progress.onPhase(1, 0);
            String content = Decoder.getJson(UrlUtil.convert(url), "PreScan");
            if (progress != null) progress.onPhase(2, 0);
            java.util.Set<String> jars = SourceScanner.collectJars(url, type, content);
            if (progress != null) progress.onPhase(3, jars.size());
            if (SourceScanner.structureSuspect(url, type, content)) return true;
            int index = 0;
            for (String jar : jars) {
                if (progress != null) progress.onJar(++index, jars.size(), jar);
                if (com.fongmi.android.tv.setting.JarBlockSetting.blocksBase(jar)) return true;
                // 「杀进程中和」开启时，kill 特征不再阻止添加：加载期由 DexPatch 原地 NOP 中和；
                // 黑名单与结构异常（疑似加密载荷）仍照常阻止
                if (!com.fongmi.android.tv.setting.Setting.isNeutralizeKill() && SourceScanner.hasExitRef(jar)) return true;
            }
            return false;
        } catch (Throwable e) {
            return false;
        }
    }

    // 本软件「合并源」产物头部标记（ConfigMerger.save 写入）
    private static final String MERGE_MARK = "xwgui_merged";

    /**
     * 是否为本软件「合并源」功能生成的本地文件（产物 JSON 头部含 {@code xwgui_merged} 标记）。
     * 合并产物在合并时已对各输入源逐一检测，因此再次添加 / 参与合并时免检测。
     * 仅读文件头 64 字节即可判定（Gson 输出为紧凑 JSON，标记位于头部）。
     */
    public static boolean isSelfMerged(String url) {
        if (url == null || !url.startsWith("file:")) return false;
        try {
            java.io.File file = Path.local(url);
            if (!file.isFile()) return false;
            try (java.io.FileInputStream is = new java.io.FileInputStream(file)) {
                byte[] buf = new byte[64];
                int n = is.read(buf);
                return n > 0 && new String(buf, 0, n, java.nio.charset.StandardCharsets.UTF_8).contains(MERGE_MARK);
            }
        } catch (Throwable e) {
            return false;
        }
    }

    // -1 表示从未做过运行验证（永久有效，不设过期）
    private static int readRuntime(String url) {
        try {
            String value = Prefers.getString(RUNTIME_PREFIX + Util.md5(url), "");
            if (TextUtils.isEmpty(value)) return -1;
            String[] parts = value.split("\\|");
            if (parts.length < 2) return -1;
            return Integer.parseInt(parts[0]);
        } catch (Throwable e) {
            return -1;
        }
    }

    private static String cacheKey(String url, String content) {
        return PREF_PREFIX + Util.md5(url + "|" + Util.md5(content));
    }

    private static int readCache(String key) {
        try {
            String value = Prefers.getString(key, "");
            if (TextUtils.isEmpty(value)) return -1;
            String[] parts = value.split("\\|");
            if (parts.length < 2 || System.currentTimeMillis() - Long.parseLong(parts[1]) > CACHE_TTL) return -1;
            return Integer.parseInt(parts[0]);
        } catch (Throwable e) {
            return -1;
        }
    }

    private static void writeCache(Job job, int verdict) {
        try {
            if (job.key == null) return;
            Prefers.put(job.key, verdict + "|" + System.currentTimeMillis());
        } catch (Throwable ignored) {
        }
    }
}
