package com.fongmi.android.tv.api;

import android.app.ApplicationExitInfo;
import android.text.TextUtils;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.api.loader.JarLoader;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.setting.JarBlockSetting;
import com.fongmi.android.tv.setting.SiteIncidentSetting;
import com.fongmi.android.tv.utils.DiagLog;
import com.github.catvod.utils.Prefers;

import java.util.List;

/**
 * 运行时「肇事 jar」归因兜底（静态闸口/试用检测的漏网补刀）：
 * <p>
 * 兜底1 死亡归因：jar 用反射拼字符串调 System.exit 时，shutdown hook 仍会执行——
 * 在死亡瞬间检查 exit 调用线程栈是否含 spider 帧，是则当场同步拉黑该 jar 并给站源记事故。
 * <p>
 * 兜底2 验尸归因：jar 用 native killProcess 硬杀（hook 不响）时，下次启动检查：
 * 上次退出为自杀/异常停止、无自家已知退出标记（TVBus/TrialRun/崩溃页各有标识）、
 * hook 未抓到归因 → 读心跳文件，时间落在死亡窗口内则补拉黑 + 记事故。
 * <p>
 * 两条都只针对「jar 主动杀宿主」，处置 = 拉黑 jar + 站源记事故（满 2 次永久锁站）。
 * 软件被杀闪退属「用户有感知」事故：归因后落盘摘要，下次启动首页弹窗告知原因与处理方案。
 */
public final class JarGuard {

    // 自家主动退出标识时间戳（Prefers）：TVBus 换核心 / TrialRun 受控退出 / 崩溃页重启
    private static final String SELF_EXIT = "probe_self_exit";
    private static final String CRASH_EXIT = "crash_page_exit";
    // 重启后弹窗提示（type|事故次数|是否永久屏蔽）：jar 杀宿主属「用户有感知」事故，需告知
    public static final String NOTICE = "jar_guard_notice";
    private static final String SEP = "|";
    // 死亡归因已执行标记（同一进程只归因一次）
    private static volatile boolean deathAttributed;
    // 验尸窗口：心跳时间距死亡时间不超过该值才认定「死于该 jar 在场时」
    private static final long WINDOW_MS = 10 * 60 * 1000L;

    private JarGuard() {
    }

    // ==================== 兜底1：死亡瞬间归因（shutdown hook 调用） ====================

    /**
     * exit 调用线程栈中含 spider 帧 → 判定 jar 主动杀宿主。
     * 同步落盘：拉黑当前 jar + 站源记事故 + 打标记（防验尸重复计账）。
     */
    public static void attributeDeath(String exitStack) {
        try {
            if (deathAttributed) return;
            deathAttributed = true;
            if (exitStack == null || !exitStack.contains("com.github.catvod")) return;
            // 首选「栈 → ClassLoader → jar」精准归因：多源并发（换源/快搜）时只锁定真正
            // 执行 System.exit 的那个 jar，绝不把正在播放但无罪的站源误拉黑。
            String jar = blameFromStack(exitStack);
            String key = "";
            if (TextUtils.isEmpty(jar)) {
                // 反查不到（如纯反射/系统栈）才回退心跳 / init
                String[] hb = JarHeartbeat.read();
                jar = hb != null ? hb[1] : "";
                key = hb != null ? hb[0] : "";
                if (TextUtils.isEmpty(jar)) jar = currentInitJar();
            }
            if (TextUtils.isEmpty(jar)) {
                DiagLog.log("jar-guard", "death attributed but jar unknown");
                return;
            }
            JarBlockSetting.addSync(jar);
            if (TextUtils.isEmpty(key)) key = findSiteKeyByJar(jar);
            int count = SiteIncidentSetting.add(key, jar);
            Prefers.put("jar_guard_death", String.valueOf(System.currentTimeMillis()));
            // 用户有感知（软件被 jar 强制退出）→ 临死同步落盘摘要，下次启动展示
            putNoticeSync(1, count, count >= SiteIncidentSetting.limit());
            DiagLog.log("jar-guard", "death attributed jar=%s key=%s", jar, key);
            // 拦不住时不做“闪退→自动重启”：让软件干净关闭（与用户主动关闭无异），下次由用户手动打开再返回视图弹窗
            closeQuietly();
        } catch (Throwable ignored) {
        }
    }

    // 栈 → ClassLoader → jar 精准归类（方案④核心）：仅当栈深类确实属于某已加载 jar 才定罪
    private static String blameFromStack(String exitStack) {
        try {
            JarLoader jl = JarLoader.get();
            if (jl != null) return jl.blameJarFromStack(exitStack);
        } catch (Throwable ignored) {
        }
        return "";
    }

    // 死亡瞬间善后关闭：jar 杀宿主已触发 System.exit，进程注定要退。目标是让本次退出
    // 等价于「用户主动关闭」——主动停掉 PlaybackService（MediaLibraryService，进程死后
    // 会被系统按 MediaSession 复活并恢复任务栈，表现为「闪退后自动重启回播放页」）并
    // 移除任务栈，随后真正退出，且下次由用户手动重新打开。
    // 关键：必须在主线程完成 binder 调用（stopService/finishAndRemoveTask）后才真正退出，
    // 否则 shutdown hook 里直接同步调用往往来不及、导致服务没停、任务栈没清。
    private static void closeQuietly() {
        App.post(() -> {
            try {
                App.get().stopService(new android.content.Intent(App.get(), com.fongmi.android.tv.service.PlaybackService.class));
            } catch (Throwable ignored) {
            }
            try {
                android.app.Activity top = App.activity();
                if (top != null) top.finishAndRemoveTask();
            } catch (Throwable ignored) {
            }
        }, 600L);
        App.post(() -> System.exit(0), 1000L);
    }

    // ==================== 兜底2：下次启动验尸归因（DiagLog.recordExitReason 调用） ====================

    /**
     * 上次退出「无解释的自杀/异常停止」→ 用心跳文件补刀归因。
     * exits 为本次启动读到的 ApplicationExitInfo 列表（含上次死亡记录）。
     */
    public static void autopsy(List<ApplicationExitInfo> exits, String pkg) {
        try {
            ApplicationExitInfo last = findUnexplainedMainExit(exits, pkg);
            if (last == null) return;
            long died = last.getTimestamp();
            // 自家已知退出：TVBus 换核心 / TrialRun 受控退出 / 崩溃页重启 / 死亡归因已处理 → 不验尸
            if (recentMark(SELF_EXIT, died) || recentMark(CRASH_EXIT, died) || recentMark("jar_guard_death", died)) return;
            // Java/native 崩溃有 crash: 日志与崩溃页标识，这里只处理「无声消失」
            String[] hb = JarHeartbeat.read();
            if (hb == null) return;
            long beat = Long.parseLong(hb[2].trim());
            if (beat > died || died - beat > WINDOW_MS) return;
            String jar = hb[1];
            if (JarBlockSetting.isBlocked(jar) || jar.isEmpty()) return;
            JarBlockSetting.add(jar);
            int count = SiteIncidentSetting.add(hb[0], jar);
            // 用户有感知（软件无声消失）→ 弹窗告知原因与处理方案
            putNoticeSync(2, count, count >= SiteIncidentSetting.limit());
            DiagLog.log("jar-guard", "autopsy attributed jar=%s key=%s died=%s beat=%s", jar, hb[0], died, beat);
        } catch (Throwable ignored) {
        }
    }

    /**
     * 找出「主进程被 jar 无外因杀死」的退出记录（验尸归因 / 升级判定共用）。
     * 三重过滤防误杀（用户从最近任务划掉 App、系统回收等绝不算 jar 所为）：
     * ① 仅主进程——隔离/子进程退出与宿主被杀无关；
     * ② 退出瞬间进程必须处于前台/可感知（importance ≤ 250）——jar 杀宿主发生在用户正在使用时，
     *    后台/缓存进程死亡（400/1000）必为用户划后台或系统清理；
     * ③ 描述含外因关键字（o-stop / force-stop / isolated not needed / user / swipe / lmkd…）排除；
     *    原因仅认 EXIT_SELF、SIGNALED，或 OTHER 且描述完全为空（killProcess 硬杀无声）。
     */
    public static ApplicationExitInfo findUnexplainedMainExit(List<ApplicationExitInfo> exits, String pkg) {
        if (exits == null) return null;
        for (ApplicationExitInfo exit : exits) {
            String process = exit.getProcessName();
            if (process != null && !process.equalsIgnoreCase(pkg)) continue;
            if (exit.getImportance() > 250) continue;
            String desc = exit.getDescription() == null ? "" : exit.getDescription().toLowerCase(java.util.Locale.ROOT);
            if (hasExternalCause(desc)) continue;
            int r = exit.getReason();
            if (r == ApplicationExitInfo.REASON_EXIT_SELF || r == ApplicationExitInfo.REASON_SIGNALED) return exit;
            if (r == ApplicationExitInfo.REASON_OTHER && desc.trim().isEmpty()) return exit;
        }
        return null;
    }

    private static boolean hasExternalCause(String desc) {
        if (desc.isEmpty()) return false;
        String[] marks = {"o-stop", "force-stop", "stop ", "due to", "isolated", "user", "swipe", "remove task",
                "lmkd", "lowmem", "low memory", "excessive", "oversize", "shutdown", "clear data", "anr",
                "crash", "freezer", "permission", "updated", "dependency", "backup", "restore"};
        for (String mark : marks) if (desc.contains(mark)) return true;
        return false;
    }

    // 标记时间戳是否落在「死亡时刻」附近（±2 分钟容差：标记在死前写、退出时间由系统记）
    private static boolean recentMark(String key, long died) {
        long mark = parseLong(Prefers.getString(key, ""), 0);
        return mark > 0 && Math.abs(died - mark) < 2 * 60 * 1000L;
    }

    private static long parseLong(String value, long def) {
        try {
            return Long.parseLong(value.trim());
        } catch (Throwable e) {
            return def;
        }
    }

    // ==================== 归因辅助 ====================

    // 归因处置摘要落盘（type：1=死亡归因实锤，2=验尸归因；count=站源事故累计；forever=是否永久屏蔽）。
    // 死亡归因发生在进程临死瞬间，apply() 可能来不及落盘，强制 commit。
    private static void putNoticeSync(int type, int count, boolean forever) {
        try {
            Prefers.getPrefers().edit().putString(NOTICE, type + SEP + count + SEP + (forever ? 1 : 0)).commit();
        } catch (Throwable ignored) {
        }
    }

    /**
     * 首页消费重启提示：返回 [type, count, forever]，null 表示无提示。
     */
    public static String[] takeNotice() {
        String raw = Prefers.getString(NOTICE, "");
        if (raw.isEmpty()) return null;
        Prefers.put(NOTICE, "");
        String[] p = raw.split("\\" + SEP, -1);
        return p.length < 3 ? null : p;
    }

    private static String currentInitJar() {
        try {
            JarLoader jl = JarLoader.get();
            return jl == null ? "" : jl.getLastInitJar();
        } catch (Throwable e) {
            return "";
        }
    }

    // 按 jar 反查站源 key（心跳缺失时兜底归因用）
    private static String findSiteKeyByJar(String jar) {
        try {
            for (Site site : VodConfig.get().getSites()) {
                if (jar.equals(site.getJar())) return site.getKey();
            }
        } catch (Throwable ignored) {
        }
        return "";
    }
}
