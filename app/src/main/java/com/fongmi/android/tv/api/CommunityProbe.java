package com.fongmi.android.tv.api;

import android.text.TextUtils;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.bean.Config;
import com.fongmi.android.tv.event.ConfigEvent;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.utils.DiagLog;
import com.fongmi.android.tv.utils.Task;
import com.github.catvod.utils.Prefers;
import com.github.catvod.utils.Util;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * 订阅源后台静默安全检测（社区添加 + 手动/扫码添加共用），结论供卡片右上角绿/红角标读取。
 * <p>
 * 结论来源（{@link #result}）按优先级：
 * ① 运行缓存：手动/扫码添加时前台已跑「8 秒试运行」（{@link TrialRun}），其终审结论
 * 经 {@link SourceProbe#markRuntime} 永久缓存，社区角标直接复用，与手动添加完全一致；
 * ② 静态扫描：社区后台源未在前台激活，无法安全做真机试运行（{@code VodConfig}/{@code LiveConfig}
 * 为进程级单例，后台加载会清空覆盖用户正在使用的配置），故以 {@link SourceProbe#scanDangerous}
 * 静态扫描给出结论；用户日后在订阅管理中首次启用（激活）该源时，前台会补跑 8 秒试运行并更新缓存。
 * <p>
 * 已标记但尚无结论（检测排队中）的源显示黄色「未检测」角标。
 * <p>
 * 全程无弹窗、无打断。受「添加源安全检测」开关（{@link Setting#isProbeAdd()}）节制：关闭则跳过、不产生角标。
 */
public final class CommunityProbe {

    // 检测标记：存在即代表该源参与角标检测（社区添加或手动/扫码添加成功时打上）
    private static final String ADDED_PREFIX = "community_added_";
    // 检测结论：pass / fail（静态扫描结论；运行缓存结论直接由 SourceProbe 提供，不在此重复存）
    private static final String RESULT_PREFIX = "community_check_";

    private static final Deque<Task2> QUEUE = new ArrayDeque<>();
    private static boolean running;

    private record Task2(String url, int type) {
    }

    private CommunityProbe() {
    }

    private static String key(String url) {
        return Util.md5(SourceProbe.cleanUrl(url));
    }

    /**
     * 纳入角标检测（新增成功后调用）：仅 http(s) 源参与，标记 + 入队一步完成。
     */
    public static void track(String url, int type) {
        if (TextUtils.isEmpty(url)) return;
        if (!(url.startsWith("http://") || url.startsWith("https://"))) return;
        markAdded(url);
        enqueue(url, type);
    }

    /**
     * 标记某订阅源参与角标检测（社区或手动/扫码添加成功后调用）。
     */
    public static void markAdded(String url) {
        try {
            Prefers.put(ADDED_PREFIX + key(url), "1");
        } catch (Throwable ignored) {
        }
    }

    /**
     * 该源是否参与角标检测（决定卡片是否可能显示角标）。
     */
    public static boolean isCommunityAdded(String url) {
        try {
            return "1".equals(Prefers.getString(ADDED_PREFIX + key(url), ""));
        } catch (Throwable e) {
            return false;
        }
    }

    /**
     * 检测结论：null=尚无结果（检测中或未检测）；true=通过；false=未通过。
     * 运行缓存（前台试运行终审）优先于静态扫描结论。
     */
    public static Boolean result(String url) {
        try {
            if (!Setting.isProbeAdd()) return null;
            if (SourceProbe.isRuntimePassed(url)) return true;
            if (SourceProbe.isRuntimeDangerous(url)) return false;
            String value = Prefers.getString(RESULT_PREFIX + key(url), "");
            if (TextUtils.isEmpty(value)) return null;
            return "pass".equals(value);
        } catch (Throwable e) {
            return null;
        }
    }

    /**
     * 是否显示黄色「未检测」角标：检测开关开启、参与角标检测、但尚无结论（排队检测中）。
     */
    public static boolean pending(String url) {
        return Setting.isProbeAdd() && isCommunityAdded(url) && result(url) == null;
    }

    /**
     * 入队一批待检测的订阅源，后台空闲时串行静默检测。
     * 「添加源安全检测」开关关闭时直接返回（不检测、不产生角标）。
     */
    public static void enqueue(List<Config> configs) {
        List<Config> copy = new ArrayList<>(configs);
        if (!Setting.isProbeAdd()) return;
        boolean changed = false;
        synchronized (QUEUE) {
            for (Config config : copy) {
                String url = config.getUrl();
                if (TextUtils.isEmpty(url)) continue;
                if (!(url.startsWith("http://") || url.startsWith("https://"))) continue;
                QUEUE.addLast(new Task2(url, config.getType()));
                changed = true;
            }
        }
        if (changed) pump();
    }

    // 便捷入队：单条（不经过 Config，避免误插入数据库）
    public static void enqueue(String url, int type) {
        if (!Setting.isProbeAdd()) return;
        if (TextUtils.isEmpty(url)) return;
        if (!(url.startsWith("http://") || url.startsWith("https://"))) return;
        synchronized (QUEUE) {
            QUEUE.addLast(new Task2(url, type));
        }
        pump();
    }

    // 串行泵：同一时刻仅一个检测在跑，避免抢占网络/CPU
    private static void pump() {
        synchronized (QUEUE) {
            if (running || QUEUE.isEmpty()) return;
            running = true;
        }
        Task.execute(() -> {
            while (true) {
                Task2 job;
                synchronized (QUEUE) {
                    job = QUEUE.pollFirst();
                    if (job == null) {
                        running = false;
                        break;
                    }
                }
                checkOne(job);
            }
        });
    }

    private static void checkOne(Task2 job) {
        try {
            // 空源判定：确认拉到内容但无任何站点/频道 → 静默删除该源（不打扰用户）；
            // 正在激活使用的源不删（避免打断当前配置），留给人工处理
            if (isEmptyUnused(job)) {
                Config.delete(job.url(), job.type());
                clear(job.url());
                DiagLog.log("community-probe", "empty source removed url=%s", job.url());
                App.post(ConfigEvent::common);
                return;
            }
            // 运行缓存已有前台终审结论 → 无需静态扫描（手动添加源命中此分支）
            if (SourceProbe.isRuntimePassed(job.url()) || SourceProbe.isRuntimeDangerous(job.url())) {
                App.post(ConfigEvent::common);
                return;
            }
            // 静态扫描：命中恶意特征判 fail，干净判 pass
            boolean dangerous = SourceProbe.scanDangerous(job.url(), job.type());
            Prefers.put(RESULT_PREFIX + key(job.url()), dangerous ? "fail" : "pass");
            DiagLog.log("community-probe", "url=%s verdict=%s", job.url(), dangerous ? "fail" : "pass");
        } catch (Throwable e) {
            // 检测异常保守标记为 fail（未通过），提示用户留意更安全
            try {
                Prefers.put(RESULT_PREFIX + key(job.url()), "fail");
            } catch (Throwable ignored) {
            }
        }
        App.post(ConfigEvent::common);
    }

    // 确认空源且非当前激活地址（判定与删除共用一次拉取结果前先判激活，激活中直接跳过判定）
    private static boolean isEmptyUnused(Task2 job) {
        String active = Prefers.getString("config_" + job.type(), "");
        if (cleanEquals(active, job.url())) return false;
        return SourceProbe.isEmptySource(job.url(), job.type());
    }

    private static boolean cleanEquals(String a, String b) {
        return SourceProbe.cleanUrl(a).equals(SourceProbe.cleanUrl(b));
    }

    /**
     * 清除某地址的检测标记与静态扫描结论（源被删除时调用，避免残留）。
     */
    public static void clear(String url) {
        try {
            Prefers.put(ADDED_PREFIX + key(url), "");
            Prefers.put(RESULT_PREFIX + key(url), "");
        } catch (Throwable ignored) {
        }
    }
}
