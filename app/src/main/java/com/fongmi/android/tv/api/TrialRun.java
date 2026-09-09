package com.fongmi.android.tv.api;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.Context;
import android.os.Build;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Config;
import com.fongmi.android.tv.utils.DiagLog;
import com.fongmi.android.tv.utils.Notify;
import com.github.catvod.utils.Prefers;

/**
 * 「15 秒运行试用」终审机制（单独添加源的唯一安全闸门，无前置检测）：
 * 源添加即激活并真机运行 15 秒——存活即通过（永久信任，缓存 PASS，
 * 后续添加/合并免检测）；期间表现出「杀/退软件」行为，无论是否被
 * 防护罩拦截成功，一律删除该源、恢复原配置并永久拉黑（缓存 DANGEROUS）。
 * <p>
 * 试运行防护罩（仅窗口期安装）：
 * ① SecurityManager.checkExit —— 拦截 System.exit / Runtime.exit，置企图标志并抛异常阻止退出；
 * ② 默认未捕获异常处理器 —— 吞掉窗口内 Java 崩溃，置企图标志。
 * 两者触发即「处决」：立即回滚 + 永久拉黑 + 受控干净退出（不弹崩溃页、
 * 不自动重启、移除任务栈），不等窗口结束。
 * killProcess 等 native 杀进程 Java 层无法拦截 → 走真实闪退路径，
 * 下次启动由 {@link #checkOnStartup(Context)} 依据 ApplicationExitInfo 回滚 + 永久拉黑。
 * <p>
 * 标记仅存 Prefers，崩溃后仍在；启动时（任何 Activity 加载配置之前）消费，
 * 避免恶意源反复导致启动即崩。
 */
public final class TrialRun {

    private static final String MARKER = "probe_trial";
    private static final String NOTICE = "probe_trial_notice";
    private static final String SELF_EXIT = "probe_self_exit";
    private static final long WINDOW = 15_000L;   // 存活判定窗口
    private static final long STALE = 10 * 60_000L; // 标记超时（App 被正常退出又久未启动）视为失效

    private static final String SEP = "\n";

    // 回滚提示类型（随 NOTICE 一并存储，供首页弹窗区分文案）
    public static final int NOTICE_CRASH = 0;     // 试运行期间真实闪退
    public static final int NOTICE_EXIT = 1;      // 试运行期间尝试强制退出（已拦截）

    private static Runnable watchdog;
    private static SecurityManager originalSm;
    private static Thread.UncaughtExceptionHandler originalUce;
    private static volatile boolean attempt;

    private TrialRun() {
    }

    /**
     * 单个源试运行：必须在 config 激活（update()）之前调用，以便记录原激活地址用于回滚。
     */
    public static void begin(String url, int type) {
        String prev = Prefers.getString("config_" + type, "");
        Prefers.put(MARKER, url + SEP + type + SEP + prev + SEP + System.currentTimeMillis());
        cancelWatchdog();
        watchdog = TrialRun::confirm;
        App.post(watchdog, WINDOW);
        installShield();
        DiagLog.log("trial", "begin url=%s type=%d", url, type);
    }

    // 存活满窗口：解除退出阻断、清除标记并永久 PASS，后续添加/合并免检测。
    private static void confirm() {
        String[] p = parse();
        if (p == null) return;
        uninstallShield();                     // 解除退出阻断，窗口期满后软件可正常关闭/重启
        clear();
        if (attempt) return;                    // 企图处决已接管回滚，勿再放行
        // 需求4：点播订阅存活 → 自动将全部站点设为「参与换源」
        if ((int) parseLong(p[1], 0) == 0) VodConfig.onTrialPassed(p[0]);
        SourceProbe.markRuntime(p[0], SourceProbe.PASS);
        Notify.show(R.string.source_probe_trial_passed);
        DiagLog.log("trial", "passed url=%s", p[0]);
    }

    /**
     * 主进程启动早期调用（任何配置加载之前）：消费上次未完成试用。
     * 依据 ApplicationExitInfo 区分「崩溃退出」与「用户正常退出」：
     * 崩溃 → 回滚（删除试用源、恢复原激活配置、永久拉黑、留提示弹窗）；
     * 正常退出/标记过期 → 按存活放行（永久 PASS）。
     */
    public static void checkOnStartup(Context context) {
        String[] p = parse();
        if (p == null) return;
        clear();
        long start = parseLong(p[3], 0);
        if (System.currentTimeMillis() - start > STALE) {
            SourceProbe.markRuntime(p[0], SourceProbe.PASS);
            return;
        }
        if (!isCrash(context, start)) {
            // 用户主动退出等非崩溃场景：无法证伪，按放行处理
            SourceProbe.markRuntime(p[0], SourceProbe.PASS);
            return;
        }
        String url = p[0];
        int type = (int) parseLong(p[1], 0);
        SourceProbe.markRuntime(url, SourceProbe.DANGEROUS);
        try {
            Config.delete(url, type);
        } catch (Throwable ignored) {
        }
        Prefers.put("config_" + type, p[2]);
        Prefers.put(NOTICE, url + SEP + NOTICE_CRASH);
        DiagLog.log("trial", "rollback url=%s type=%d prev=%s", url, type, p[2]);
    }

    /**
     * 首页就绪后取出并消费回滚提示：返回 [url, 提示类型]，null 表示无提示。
     */
    public static String[] takeNotice() {
        String raw = Prefers.getString(NOTICE, "");
        if (raw.isEmpty()) return null;
        Prefers.put(NOTICE, "");
        String[] p = raw.split(SEP, -1);
        return p.length < 2 ? null : p;
    }

    // ===== 试运行防护罩 =====

    private static void installShield() {
        attempt = false;
        try {
            originalSm = System.getSecurityManager();
            System.setSecurityManager(new SecurityManager() {
                @Override
                public void checkExit(int status) {
                    // 注意：不可调 super.checkExit——基类实现恒抛 SecurityException
                    if (isExpectedSelfExit()) return; // 软件自身主动重启（换核心等），放行
                    onAttempt("System.exit(" + status + ") blocked");
                    throw new SecurityException("trial: process exit blocked");
                }
            });
        } catch (Throwable e) {
            originalSm = null;
        }
        try {
            originalUce = Thread.getDefaultUncaughtExceptionHandler();
            Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
                onAttempt("uncaught exception swallowed: " + e);
            });
        } catch (Throwable ignored) {
            originalUce = null;
        }
    }

    private static void uninstallShield() {
        try {
            System.setSecurityManager(originalSm);
        } catch (Throwable ignored) {
        }
        originalSm = null;
        try {
            if (originalUce != null) Thread.setDefaultUncaughtExceptionHandler(originalUce);
        } catch (Throwable ignored) {
        }
        originalUce = null;
    }

    // 检测到「杀/退软件」企图：立即处决（回滚 + 永久拉黑），不等窗口结束
    private static void onAttempt(String detail) {
        if (attempt) return;
        attempt = true;
        DiagLog.log("trial", "attempt: %s", detail);
        App.post(TrialRun::execute);
    }

    // 处决：数据回滚（同步）后受控「干净退出」——不弹崩溃页、不自动重启、
    // 移除整个任务栈。无论用户停留在哪个页面，恶意源行为统一表现为软件直接关闭。
    private static void execute() {
        String[] p = parse();
        clear();
        uninstallShield();
        if (p == null) return;
        String url = p[0];
        int type = (int) parseLong(p[1], 0);
        SourceProbe.markRuntime(url, SourceProbe.DANGEROUS);
        try {
            Config.delete(url, type);
        } catch (Throwable ignored) {
        }
        Prefers.put("config_" + type, p[2]);
        Prefers.put(NOTICE, url + SEP + NOTICE_EXIT);
        // apply() 为异步落盘，System.exit 前强制同步刷写，防止回滚数据丢失
        try {
            Prefers.getPrefers().edit().putLong("probe_trial_flush", System.currentTimeMillis()).commit();
        } catch (Throwable ignored) {
        }
        DiagLog.log("trial", "execute rollback url=%s type=%d prev=%s", url, type, p[2]);
        exitApp();
    }

    // 受控退出：先留自我退出豁免标记，再移除任务栈 + System.exit。
    // 延时确保 Prefers 落盘与 Room 删除完成；下次启动 checkOnStartup 消费 NOTICE 弹提醒。
    private static void exitApp() {
        DiagLog.log("exit-point", "TrialRun.exitApp: 试运行回滚，受控退出");
        expectSelfExit();
        App.post(() -> {
            try {
                Activity top = App.activity();
                if (top != null) top.finishAndRemoveTask();
                else App.get().stopService(new android.content.Intent(App.get(), com.fongmi.android.tv.service.PlaybackService.class));
            } catch (Throwable ignored) {
            }
        }, 600L);
        App.post(() -> System.exit(0), 1000L);
    }

    // 软件自身主动退出（TVBus 换核心等）的时间戳吻合校验
    private static boolean isExpectedSelfExit() {
        long mark = parseLong(Prefers.getString(SELF_EXIT, ""), 0);
        return mark > 0 && System.currentTimeMillis() - mark < 60_000L;
    }

    private static String[] parse() {
        String raw = Prefers.getString(MARKER, "");
        if (raw.isEmpty()) return null;
        String[] p = raw.split(SEP, -1);
        return p.length < 4 ? null : p;
    }

    private static void clear() {
        cancelWatchdog();
        Prefers.put(MARKER, "");
    }

    private static void cancelWatchdog() {
        if (watchdog != null) App.removeCallbacks(watchdog);
    }

    // 主进程上次退出是否为崩溃类（API30 以下无从判断，保守按崩溃处理）
    private static boolean isCrash(Context context, long since) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return true;
        try {
            ActivityManager am = context.getSystemService(ActivityManager.class);
            if (am == null) return true;
            String pkg = context.getPackageName();
            for (ApplicationExitInfo exit : am.getHistoricalProcessExitReasons(pkg, 0, 8)) {
                if (exit == null || !pkg.equals(exit.getProcessName())) continue;
                if (exit.getTimestamp() < since - 2000) break;
                int reason = exit.getReason();
                // EXIT_SELF 且与「主动重启」时间戳吻合（1 分钟内）：软件自身的
                // System.exit(0)（如 TVBus 换核心），不算崩溃；无标记的 EXIT_SELF
                // 视为源代码调用 System.exit，按崩溃处理。
                if (reason == ApplicationExitInfo.REASON_EXIT_SELF && isExpectedSelfExitAt(exit.getTimestamp())) {
                    return false;
                }
                boolean crash = reason == ApplicationExitInfo.REASON_CRASH
                        || reason == ApplicationExitInfo.REASON_CRASH_NATIVE
                        || reason == ApplicationExitInfo.REASON_ANR
                        || reason == ApplicationExitInfo.REASON_SIGNALED
                        || reason == ApplicationExitInfo.REASON_EXIT_SELF;
                DiagLog.log("trial", "exit reason=%d crash=%b", reason, crash);
                return crash;
            }
        } catch (Throwable ignored) {
        }
        return true;
    }

    private static boolean isExpectedSelfExitAt(long timestamp) {
        long mark = parseLong(Prefers.getString(SELF_EXIT, ""), 0);
        return mark > 0 && Math.abs(timestamp - mark) < 60_000L;
    }

    /**
     * 软件自身主动退出（如 TVBus 换核心 System.exit(0)）前调用：
     * 留下时间戳，防护罩与崩溃判定据此把该次退出视为正常重启而非恶意行为。
     */
    public static void expectSelfExit() {
        try {
            Prefers.put(SELF_EXIT, String.valueOf(System.currentTimeMillis()));
        } catch (Throwable ignored) {
        }
    }

    private static long parseLong(String s, long def) {
        try {
            return Long.parseLong(s);
        } catch (Throwable e) {
            return def;
        }
    }
}
