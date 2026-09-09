package com.fongmi.android.tv.api;

import android.app.ApplicationExitInfo;
import android.text.TextUtils;

import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.api.loader.JarLoader;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.setting.JarBlockSetting;
import com.fongmi.android.tv.utils.DiagLog;
import com.github.catvod.utils.Prefers;
import com.github.catvod.utils.Util;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * 第三方 spider.jar 子线程崩溃免疫：
 * jar 的 com.github.catvod.spider.Init 自起线程存在「先读后写」缺陷（如读取
 * files/pvideo-arm64-v8a 缺失即抛 FileNotFoundException），且线程内无 try-catch，
 * 会把整个 App 拖进崩溃页。本 Shield 作为最外层 UncaughtExceptionHandler：
 * 仅当「非主线程 + 堆栈顶层类为 spider.Init」时吞掉异常并记 DiagLog，
 * 同时把肇事 jar 登记进 JarBlockSetting（仅拦截该 jar，不整站锁死），
 * 并留存弹窗提示，由首页消费；其余异常原样转交 CustomActivityOnCrash。
 */
public final class JarCrashShield {

    private static final String SPIDER_PREFIX = "com.github.catvod.spider.";
    private static final String INIT_CLASS = "com.github.catvod.spider.Init";
    public static final String NOTICE = "jar_block_notice";
    // 暂存「被吞但可能引发重启」的崩溃（ts\njar\nreason）：崩溃当下无法预知用户是否感知，
    // 下次启动由 promoteIfNeeded 结合 ApplicationExitInfo 判定——确实发生自杀/异常重启才升级为弹窗
    private static final String PENDING = "jar_block_pending";
    private static final long PROMOTE_WINDOW = 60_000L;
    private static final String SEP = "\n";

    private static Thread.UncaughtExceptionHandler origin;
    private static volatile boolean handled;

    private JarCrashShield() {
    }

    // 需在 Startup（CustomActivityOnCrash 安装点）之后调用，成为最外层 handler
    public static void install() {
        origin = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            if (isJarCrash(t, e)) {
                handle(e, isSwallowable(t, e));
                // 仅「非主线程 + spider.Init 内部」子线程崩溃在此吞掉（避免弹崩溃页）；
                // 主线程 jar 崩溃隐患已在上方 handle 里完成隔离落盘，仍转交崩溃页兜底。
                if (isSwallowable(t, e)) return;
                if (origin != null) origin.uncaughtException(t, e);
            } else if (origin != null) {
                // 取证：记录「即将转交 CustomActivityOnCrash 弹崩溃窗」的异常（非 spider 类）。
                // 仅打日志，不改任何处理逻辑，用于定位 jar 崩被吞后播放/搜索回调里抛的二次异常。
                logForward(t, e);
                origin.uncaughtException(t, e);
            }
        });
    }

    // 判定：堆栈中存在第三方 jar 的 spider 类帧（覆盖 spider.Init 及 spider.merge.X 等混淆类）
    private static boolean isJarCrash(Thread t, Throwable e) {
        if (e == null) return false;
        for (StackTraceElement frame : e.getStackTrace()) {
            if (frame.getClassName().startsWith(SPIDER_PREFIX)) return true;
        }
        return false;
    }

    // 仅「非主线程 + spider.Init 内部」的崩溃才安全吞掉；其余 jar 崩溃（尤其主线程）保持交给崩溃页
    private static boolean isSwallowable(Thread t, Throwable e) {
        if (t == null || t == android.os.Looper.getMainLooper().getThread()) return false;
        for (StackTraceElement frame : e.getStackTrace()) {
            if (frame.getClassName().startsWith(INIT_CLASS)) return true;
        }
        return false;
    }

    // 取证：记录即将转交给 CustomActivityOnCrash 弹崩溃窗的「非 spider」后续异常
    private static void logForward(Thread t, Throwable e) {
        try {
            DiagLog.recordForward(t, e);
        } catch (Throwable ignored) {
        }
    }

    // silent=true：崩溃被免疫吞掉、播放照常 → 用户无感知，静默处理；
    // silent=false：崩溃将导致进程终止/重启 → 用户有感知，落盘重启后弹窗告知原因与处理方案。
    private static void handle(Throwable e, boolean silent) {
        String reason = e.getClass().getSimpleName() + ": " + e.getMessage();
        StringWriter sw = new StringWriter();
        e.printStackTrace(new PrintWriter(sw));
        DiagLog.log("jar-shield", "jar crash swallowed/isolated: %s\n%s", reason, sw);
        String jar = locateJar();
        // 屏蔽与黑名单必须对每一个崩溃的 jar 都生效（不被全局 handled 短路）
        if (!TextUtils.isEmpty(jar)) {
            // 方案 A1：仅登记肇事 jar。JarLoader.parseJar 在加载前会按 JarBlockSetting 拦截，
            // SiteBlockSetting 也会在站源列表过滤掉使用该 jar 的站点，因此隔离效果不打折；
            // 但不再 lock 整站，避免「一个后台文件缺失就把整站永久禁用、无法解除」的副作用。
            JarBlockSetting.add(jar);
            // 方案 B（安静跳过）：jar 的 Init 崩溃被吞后，播放器可能因该源失败而走
            // 「换核心/退出重启」路径（System.exit 换核心）。这里提前给该次退出打上
            // 「软件预期内重启」时间戳，让下次启动把它判为正常重启而非崩溃，从而
            // 静默回落列表/首页，而不是弹崩溃告警、也不会被当作异常崩溃处理。
            // 注意：崩溃将导致重启（非静默）时，「软件消失又重启」本身即用户可感知，
            // 弹窗照常落盘；expectSelfExit 仅用于防验尸归因把该次重启重复计账。
            TrialRun.expectSelfExit();
        }
        // 崩溃被吞（进程存活）：此刻无法预知用户是否感知——若该源随后导致播放失败、
        // 软件自杀重启，「软件消失又重启」即有感知。暂存 pending，下次启动由
        // promoteIfNeeded 依据 ApplicationExitInfo 判定：确实发生异常/自杀退出才升级为弹窗。
        if (silent) {
            Prefers.put(PENDING, System.currentTimeMillis() + SEP + (jar == null ? "" : jar) + SEP + reason);
            return;
        }
        if (handled) return; // 弹窗去重一次，避免刷屏
        handled = true;
        if (!TextUtils.isEmpty(jar)) {
            Prefers.put(NOTICE, jar + SEP + reason);
        } else {
            Prefers.put(NOTICE, SEP + reason);
        }
        try {
            Prefers.getPrefers().edit().putLong("jar_shield_flush", System.currentTimeMillis()).commit();
        } catch (Throwable ignored) {
        }
    }

    /**
     * 下次启动升级判定（由 DiagLog 读取 ApplicationExitInfo 后调用）：
     * 暂存的被吞崩溃之后，进程确实以「自杀 / 异常停止 / 崩溃」方式退出过
     * → 说明用户感知到了闪退重启，升级为弹窗提示；否则（正常退到后台等）丢弃。
     */
    public static void promoteIfNeeded(long lastExitReason, long lastExitTimestamp) {
        try {
            String raw = Prefers.getString(PENDING, "");
            Prefers.put(PENDING, "");
            if (raw.isEmpty()) return;
            int i = raw.indexOf(SEP);
            int j = raw.indexOf(SEP, i + 1);
            if (i < 0 || j < 0) return;
            long crashAt = Long.parseLong(raw.substring(0, i));
            String jar = raw.substring(i + 1, j);
            String reason = raw.substring(j + 1);
            boolean abnormal = lastExitReason == ApplicationExitInfo.REASON_EXIT_SELF
                    || lastExitReason == ApplicationExitInfo.REASON_OTHER
                    || lastExitReason == ApplicationExitInfo.REASON_CRASH
                    || lastExitReason == ApplicationExitInfo.REASON_CRASH_NATIVE
                    || lastExitReason == ApplicationExitInfo.REASON_SIGNALED;
            boolean causedByCrash = lastExitTimestamp >= crashAt - 2000 && lastExitTimestamp <= crashAt + PROMOTE_WINDOW;
            if (!abnormal || !causedByCrash) return; // 未导致重启/异常退出 → 用户无感知，静默
            if (TextUtils.isEmpty(jar)) return; // 无法归因到具体 jar → 不弹窗打扰
            Prefers.put(NOTICE, jar + SEP + reason);
            DiagLog.log("jar-shield", "pending promoted: crash=%s exit=%s", crashAt, lastExitTimestamp);
        } catch (Throwable ignored) {
        }
    }

    // 崩溃瞬间定位「肇事 jar」：优先按最近换源/播放的 recent(md5) 反查站点 jar，
    // 其次回退最近一次 init 的 jar
    private static String locateJar() {
        JarLoader jl = JarLoader.get();
        if (jl == null) return "";
        String recent = jl.getRecent();
        if (!TextUtils.isEmpty(recent)) {
            for (Site site : VodConfig.get().getSites()) {
                String jar = site.getJar();
                if (TextUtils.isEmpty(jar)) continue;
                try {
                    if (Util.md5(jar).equals(recent)) return jar;
                } catch (Throwable ignored) {
                }
            }
        }
        String last = jl.getLastInitJar();
        return last == null ? "" : last;
    }

    // 崩溃瞬间正在用的 jar 已通过 JarBlockSetting 登记并拦截（见 handle），不再整站锁死。
    public static String[] takeNotice() {
        String raw = Prefers.getString(NOTICE, "");
        if (raw.isEmpty()) return null;
        Prefers.put(NOTICE, "");
        int i = raw.indexOf(SEP); // reason 自身可能含换行，仅按首个分隔符切分
        return i < 0 ? null : new String[]{raw.substring(0, i), raw.substring(i + 1)};
    }
}
