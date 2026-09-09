package com.fongmi.android.tv.utils;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;

import com.github.catvod.utils.Prefers;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

// 诊断日志：默认开启，跨进程重启留存，用于定位「播放中无缘无故重启/返回上一页」等问题。
// 落盘与采集（应用私有 files 目录，无需权限，清缓存不丢）：
//   1) 事件日志：Activity 生命周期等（log(tag,msg) 主动打点）
//   2) Java 崩溃：挂钩 default UncaughtExceptionHandler，写完整堆栈（含线程名、主线程堆栈、内存快照）后转交链路
//   3) 主线程卡死：独立 watchdog 采样 Looper 心跳，超阈值立即 dump [main] 完整堆栈（ANR 不再“无记录”）
//   4) 进程异常退出：启动时读 ApplicationExitInfo，打印原始 reason/timestamp/importance（区分 ANR、低内存、崩溃）
//   5) 环境快照：每次启动、崩溃、卡死记录 PID、内存水位、当前 Activity 类名
public final class DiagLog {

    private static final String TAG = "DiagLog";
    private static final String FILE_NAME = "diag-log.txt";
    private static final String PREF_ENABLED = "diag_log_enabled";
    // 增大环形缓冲：多线程采集 + 多次堆栈 dump 之后日志量更大，1024 行可覆盖更多历史
    private static final int MAX_LINES = 1200;
    // 主线程卡死判定阈值（毫秒）：主线程 Looper 空闲到下一帧间隔超过该值即视为卡死
    private static final long WATCHDOG_TIMEOUT_MS = 5000L;
    // 心跳/扫描间隔（毫秒）
    private static final long WATCHDOG_INTERVAL_MS = 2000L;
    // 进程 PID：跨日志对齐用
    private static final int PID = android.os.Process.myPid();
    private static final Object LOCK = new Object();
    private static final Deque<String> LINES = new ArrayDeque<>();
    private static final ThreadLocal<SimpleDateFormat> FORMAT = ThreadLocal.withInitial(() -> new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US));

    private static volatile boolean enabled = true;
    private static File file;
    private static Context context;
    // 主线程 Looper 心跳戳：每一条消息处理开始时刷新；watchdog 据此判断主线程是否卡死
    private static volatile long mainHeartbeat = SystemClock.uptimeMillis();
    // watchdog 上次 dump 的幂等：避免卡死期间疯狂刷屏
    private static volatile long lastStuckDump = 0L;
    // 连续命中计数：需连续 2 次超阈值且堆栈非空闲特征才判卡死，滤掉时钟/调度毛刺
    private static volatile int stuckStrikes = 0;
    private static volatile boolean watchdogStarted;
    private static volatile String currentActivity = "";

    private DiagLog() {
    }

    public static void init(Context context) {
        DiagLog.context = context.getApplicationContext();
        file = new File(DiagLog.context.getFilesDir(), FILE_NAME);
        enabled = Prefers.getBoolean(PREF_ENABLED, true);
        synchronized (LOCK) {
            loadLocked();
        }
        recordExitReason(context);
        installCrashHandler();
        startWatchdogIfNeeded();
        log("app", "===== process start pid=%s =====", PID);
        log("meta", "sdk=%s arch=%s maxMem=%sMB usedMem=%sMB", android.os.Build.VERSION.SDK_INT,
                arch(), Runtime.getRuntime().maxMemory() / 1048576, (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1048576);
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static void setEnabled(boolean value) {
        enabled = value;
        Prefers.put(PREF_ENABLED, value);
        if (value) log("diag", "诊断日志已开启");
    }

    public static void log(String tag, String msg) {
        if (!enabled || TextUtils.isEmpty(msg)) return;
        add(tag, msg, false);
    }

    public static void log(String tag, String format, Object... args) {
        if (!enabled) return;
        add(tag, String.format(format, args), false);
    }

    public static String text() {
        synchronized (LOCK) {
            StringBuilder builder = new StringBuilder();
            for (String line : LINES) builder.append(line).append('\n');
            return builder.toString();
        }
    }

    public static int size() {
        synchronized (LOCK) {
            return LINES.size();
        }
    }

    public static void clear() {
        synchronized (LOCK) {
            LINES.clear();
            delete();
        }
    }

    // 导出当前日志到 cache 下临时文件，供 FileProvider 分享
    public static File export() {
        try {
            if (context == null) return null;
            File out = new File(context.getCacheDir(), "diag-export.txt");
            try (FileOutputStream stream = new FileOutputStream(out)) {
                stream.write(text().getBytes(StandardCharsets.UTF_8));
            }
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    private static void add(String tag, String msg, boolean force) {
        if ("activity".equals(tag)) currentActivity = msg;
        String line = FORMAT.get().format(new Date()) + " [" + Thread.currentThread().getName() + "] " + PID + ": " + safe(tag) + ": " + msg;
        synchronized (LOCK) {
            LINES.addLast(line);
            while (LINES.size() > MAX_LINES) LINES.removeFirst();
            if (force || enabled) writeLocked(line);
        }
    }

    private static void installCrashHandler() {
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            try {
                recordCrash(thread, error);
            } catch (Throwable ignored) {
            }
            if (prev != null) prev.uncaughtException(thread, error);
        });
    }

    private static void recordCrash(Thread thread, Throwable error) {
        StringWriter writer = new StringWriter();
        error.printStackTrace(new PrintWriter(writer));
        StringBuilder sb = new StringBuilder();
        sb.append("uncaught in ").append(thread.getName());
        sb.append("\ncrash-thread-stack-------------------------------\n").append(writer);
        if (!thread.equals(Looper.getMainLooper().getThread())) {
            sb.append("\nmain-thread-stack-------------------------------\n").append(stackOf(Looper.getMainLooper().getThread()));
        }
        sb.append("\nenvironment-------------------------------------\n").append(envSummary());
        add("crash", sb.toString(), true);
    }

    /**
     * JarCrashShield 在把「非 spider 的后续异常」转交 CustomActivityOnCrash 弹崩溃窗前，
     * 调用此方法做纯日志取证。仅记录，不改变任何崩溃处理逻辑。
     */
    public static void recordForward(Thread thread, Throwable error) {
        try {
            StringWriter writer = new StringWriter();
            error.printStackTrace(new PrintWriter(writer));
            StringBuilder sb = new StringBuilder();
            sb.append("uncaught-forward in ").append(thread.getName());
            sb.append("\nforward-thread-stack-------------------------------\n").append(writer);
            if (!thread.equals(Looper.getMainLooper().getThread())) {
                sb.append("\nmain-thread-stack-------------------------------\n").append(stackOf(Looper.getMainLooper().getThread()));
            }
            sb.append("\nenvironment-------------------------------------\n").append(envSummary());
            add("crash-forward", sb.toString(), true);
        } catch (Throwable ignored) {
        }
    }

    /**
     * 常驻「自杀哨兵」（备用取证版）：System.exit / Runtime.exit 不是异常，崩溃处理器抓不到；
     * 而 Android 15（SDK 36）禁止应用安装 SecurityManager（setSecurityManager 直接抛
     * SecurityException），故改用 JVM Shutdown Hook：
     * 任何走 System.exit / Runtime.exit 的「干净退出」都会先执行钩子，此时调用方线程仍
     * 停在 Runtime.exit 帧内，遍历线程即可抓到「是谁调用的 exit」完整调用栈。
     * 仅记录、不阻止退出。注意：Process.killProcess 类 native 杀进程不会触发本钩子，
     * 「钩子未触发但进程消失」本身就是有价值的判据。
     */
    public static void installExitSentinel() {
        try {
            Runtime.getRuntime().addShutdownHook(new Thread(DiagLog::recordExitHook, "diag-exit-hook"));
        } catch (Throwable e) {
            add("exit-sentinel", "unavailable: " + e, true);
        }
    }

    private static void recordExitHook() {
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("JVM shutdown hook fired（存在代码调用 System.exit/Runtime.exit）\n");
            Thread me = Thread.currentThread();
            String callerStack = "";
            for (Thread t : Thread.getAllStackTraces().keySet()) {
                if (t == null || t == me) continue;
                StackTraceElement[] stack = t.getStackTrace();
                if (stack == null || stack.length == 0) continue;
                boolean exiting = false;
                for (StackTraceElement f : stack) {
                    String cn = f.getClassName();
                    String mn = f.getMethodName();
                    if (("exit".equals(mn) || "halt".equals(mn))
                            && (cn.startsWith("java.lang.Runtime") || cn.startsWith("java.lang.System") || cn.startsWith("java.lang.Terminator"))) {
                        exiting = true;
                        break;
                    }
                }
                if (!exiting) continue;
                StringBuilder cs = new StringBuilder();
                cs.append(">>> exit caller [").append(t.getName()).append("]\n");
                for (StackTraceElement f : stack) cs.append("    at ").append(f).append('\n');
                callerStack = cs.toString();
                sb.append(callerStack);
            }
            sb.append("\nenvironment-------------------------------------\n").append(envSummary());
            add("exit-point", sb.toString(), true);
            // 兜底1 死亡归因：exit 调用栈含 spider 帧 → jar 主动杀宿主，临死同步拉黑 + 站源记事故
            com.fongmi.android.tv.api.JarGuard.attributeDeath(callerStack);
        } catch (Throwable ignored) {
        }
    }

    // 主线程是否只是「空闲等待消息」：堆栈里没有任何应用/播放/JNI 工作帧，
    // 仅系统框架的 poll/monitor 代码 → 不是卡死，避免 main-stuck 假阳性。
    private static boolean isMainThreadIdle() {
        try {
            StackTraceElement[] stack = Looper.getMainLooper().getThread().getStackTrace();
            if (stack == null || stack.length == 0) return true;
            for (StackTraceElement e : stack) {
                String cn = e.getClassName();
                if (cn.startsWith("android.os.MessageQueue") || cn.startsWith("android.os.Looper")
                        || cn.startsWith("android.os.LooperExtImpl") || cn.startsWith("android.os.LooperMessageSuperviser")
                        || cn.startsWith("android.app.ActivityThread") || cn.startsWith("com.android.internal.os.")
                        || cn.startsWith("java.lang.reflect.") || cn.startsWith("dalvik.system.")
                        || cn.startsWith("android.os.Handler") || cn.startsWith("android.view.Choreographer")) {
                    continue;
                }
                // 出现任何非框架空闲帧（应用代码、播放器、JNI、网络等）即视为可能真卡死
                return false;
            }
            return true;
        } catch (Throwable t) {
            return true;
        }
    }

    private static String stackOf(Thread t) {
        try {
            StackTraceElement[] stack = t.getStackTrace();
            if (stack == null) return "(empty)";
            StringBuilder sb = new StringBuilder();
            for (StackTraceElement e : stack) sb.append("    ").append(e).append('\n');
            return sb.toString();
        } catch (Throwable e) {
            return "(stack unavailable: " + e + ")";
        }
    }

    private static String envSummary() {
        Runtime rt = Runtime.getRuntime();
        long used = rt.totalMemory() - rt.freeMemory();
        return "pid=" + PID
            + " activity=" + safe(currentActivity)
            + " now=" + new Date()
            + " maxHeap=" + (rt.maxMemory() / 1048576) + "MB"
            + " usedHeap=" + (used / 1048576) + "MB"
            + " freeHeap=" + (rt.freeMemory() / 1048576) + "MB";
    }

    // ==================== 主线程卡死 watchdog ====================
    // 原理：watchdog 线程每 2s 用主线程 post 一个轻量 BEAT 刷新 mainHeartbeat；
    // 主线程一旦被阻塞（Looper 无法处理消息），BEAT 不会执行，mainHeartbeat 停留 → idle 增长
    // 超过超时阈值即可判定主线程卡死，立刻 dump [main] 完整堆栈，ANR 从此不再“无记录”。
    private static Handler mainHandler;
    private static Handler watchdogHandler;

    private static void startWatchdogIfNeeded() {
        if (watchdogStarted) return;
        watchdogStarted = true;
        mainHandler = new Handler(Looper.getMainLooper());
        HandlerThread thread = new HandlerThread("diag-watchdog");
        thread.start();
        watchdogHandler = new Handler(thread.getLooper());
        watchdogHandler.post(watchdogLoop);
    }

    // 主线程每收到一次即刷新心跳
    private static final Runnable BEAT = () -> mainHeartbeat = SystemClock.uptimeMillis();

    private static final Runnable watchdogLoop = new Runnable() {
        @Override
        public void run() {
            Handler main = mainHandler;
            Handler wh = watchdogHandler;
            if (main == null || wh == null) return;
            try {
                long now = SystemClock.uptimeMillis();
                long idle = now - mainHeartbeat;
                if (idle >= WATCHDOG_TIMEOUT_MS) {
                    // 假阳性过滤：主线程堆栈若只是系统框架的空闲/监控代码
                    // （nativePollOnce、LooperMessageSuperviser 等），说明主线程其实在等消息，
                    // 并非卡死，只是 BEAT 被调度延迟，直接重置心跳不报警。
                    if (isMainThreadIdle()) {
                        mainHeartbeat = now;
                        stuckStrikes = 0;
                    } else if (++stuckStrikes >= 2) {
                        // 连续 2 次（约 4s 间隔）非空闲超阈值才坐实卡死；节流：卡死期间最多 30s 打一次
                        stuckStrikes = 0;
                        if (now - lastStuckDump >= 30000L) {
                            lastStuckDump = now;
                            StringBuilder sb = new StringBuilder();
                            sb.append("main-thread idle=").append(idle).append("ms >= ").append(WATCHDOG_TIMEOUT_MS).append("ms，疑似卡死");
                            sb.append("\nmain-thread-stack:\n").append(stackOf(Looper.getMainLooper().getThread()));
                            sb.append("\nenvironment:\n").append(envSummary());
                            add("main-stuck", sb.toString(), true);
                        }
                    }
                } else {
                    stuckStrikes = 0;
                    main.removeCallbacks(BEAT);
                    main.post(BEAT);
                }
            } catch (Throwable ignored) {
            } finally {
                try {
                    wh.postDelayed(watchdogLoop, WATCHDOG_INTERVAL_MS);
                } catch (Throwable ignored) {
                }
            }
        }
    };

    private static String arch() {
        try {
            String[] abis = Build.SUPPORTED_ABIS;
            return abis != null && abis.length > 0 ? abis[0] : "unknown";
        } catch (Throwable e) {
            return "unknown";
        }
    }

    private static void recordExitReason(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return;
        try {
            ActivityManager manager = context.getSystemService(ActivityManager.class);
            if (manager == null) return;
            List<ApplicationExitInfo> exits = manager.getHistoricalProcessExitReasons(context.getPackageName(), 0, 5);
            for (ApplicationExitInfo exit : exits) {
                String detail = "prev reason=" + reasonName(exit.getReason()) + "(" + exit.getReason() + ")"
                        + " status=" + exit.getStatus()
                        + " importance=" + exit.getImportance()
                        + " timestamp=" + ts(exit.getTimestamp())
                        + " pid=" + exit.getPid();
                String desc = exit.getDescription();
                if (!TextUtils.isEmpty(desc)) detail += " desc=" + safe(desc);
                add("exit", detail, true);
                dumpTraceIfAny(exit);
            }
            // 兜底2 验尸归因：上次「无解释的主进程自杀/异常停止」→ 用站源心跳补刀拉黑肇事 jar
            // （用户划后台、系统回收等外因退出已被过滤，绝不误伤）
            com.fongmi.android.tv.api.JarGuard.autopsy(exits, context.getPackageName());
            // 被吞 jar 崩溃的升级判定：崩溃后主进程确实「无外因异常退出」过 → 用户感知到闪退，弹窗告知
            android.app.ApplicationExitInfo unexplained = com.fongmi.android.tv.api.JarGuard.findUnexplainedMainExit(exits, context.getPackageName());
            if (unexplained != null) {
                com.fongmi.android.tv.api.JarCrashShield.promoteIfNeeded(unexplained.getReason(), unexplained.getTimestamp());
            }
        } catch (Throwable ignored) {
        }
    }

    private static String ts(long t) {
        try {
            return t <= 0 ? String.valueOf(t) : new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(new Date(t));
        } catch (Throwable e) {
            return String.valueOf(t);
        }
    }

    // Android 10+：ANR / 原生崩溃 / 被杀时系统留存进程 trace，导出以定位卡死点
    private static void dumpTraceIfAny(ApplicationExitInfo exit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return;
        try (java.io.InputStream in = exit.getTraceInputStream()) {
            if (in == null) return;
            StringBuilder sb = new StringBuilder();
            java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            int n = 0;
            while ((line = reader.readLine()) != null && n < 600) {
                sb.append(line).append('\n');
                n++;
            }
            if (n > 0) add("exit-trace", "reason=" + exit.getReason() + " pid=" + exit.getPid() + "\n" + sb, true);
        } catch (Throwable ignored) {
        }
    }

    private static String reasonName(int reason) {
        switch (reason) {
            case ApplicationExitInfo.REASON_EXIT_SELF: return "EXIT_SELF";
            case ApplicationExitInfo.REASON_SIGNALED: return "SIGNALED";
            case ApplicationExitInfo.REASON_LOW_MEMORY: return "LOW_MEMORY(系统查杀)";
            case ApplicationExitInfo.REASON_CRASH: return "CRASH(Java崩溃)";
            case ApplicationExitInfo.REASON_CRASH_NATIVE: return "CRASH_NATIVE(原生崩溃)";
            case ApplicationExitInfo.REASON_ANR: return "ANR";
            case ApplicationExitInfo.REASON_INITIALIZATION_FAILURE: return "INIT_FAILURE";
            case ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE: return "EXCESSIVE_RESOURCE";
            case ApplicationExitInfo.REASON_USER_REQUESTED: return "USER_REQUESTED";
            case ApplicationExitInfo.REASON_USER_STOPPED: return "USER_STOPPED";
            case ApplicationExitInfo.REASON_DEPENDENCY_DIED: return "DEPENDENCY_DIED";
            case ApplicationExitInfo.REASON_OTHER: return "OTHER";
            default: return "UNKNOWN(" + reason + ")";
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value.replace('\n', ' ').replace('\r', ' ');
    }

    private static File file() {
        return file;
    }

    private static void writeLocked(String line) {
        try {
            File f = file();
            if (f == null) return;
            try (FileOutputStream stream = new FileOutputStream(f, true)) {
                stream.write((line + "\n").getBytes(StandardCharsets.UTF_8));
            }
        } catch (Throwable ignored) {
        }
    }

    private static void loadLocked() {
        try {
            File f = file();
            if (f == null || !f.exists()) return;
            List<String> all = new ArrayList<>();
            for (String line : readAll(f).split("\\r?\\n")) if (!TextUtils.isEmpty(line)) all.add(line);
            int from = Math.max(0, all.size() - MAX_LINES);
            for (int i = from; i < all.size(); i++) LINES.addLast(all.get(i));
            // 重写文件，截断到环形缓冲上限，避免无限增长
            rewriteLocked();
        } catch (Throwable ignored) {
        }
    }

    private static void rewriteLocked() {
        try {
            File f = file();
            if (f == null) return;
            try (FileOutputStream stream = new FileOutputStream(f, false)) {
                for (String line : LINES) stream.write((line + "\n").getBytes(StandardCharsets.UTF_8));
            }
        } catch (Throwable ignored) {
        }
    }

    private static String readAll(File f) throws Exception {
        try (FileInputStream input = new FileInputStream(f); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }

    private static void delete() {
        try {
            File f = file();
            if (f != null && f.exists()) f.delete();
        } catch (Throwable ignored) {
        }
    }
}
