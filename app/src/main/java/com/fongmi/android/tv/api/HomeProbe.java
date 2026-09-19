package com.fongmi.android.tv.api;

import android.text.TextUtils;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.utils.DiagLog;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.utils.Task;
import com.github.catvod.utils.Prefers;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 首页站点自动切换兜底：点播配置加载完成后，后台先拉当前默认站首页；
 * 有数据即结束（零额外开销），空/异常（或防魔改 Toast 信号确认拒供）才从第 4 个站点起探测（最多 4 个候选站，不限站点类型），
 * 单站 Future 硬超时 5s、总预算 20s，任一命中立即切换 home 并弹一次性通知（约3秒自动消失）。
 * 4 个候选全空则永久标记该订阅「探测失败」，后续不再探测、无冷却；仅重新添加该订阅时重置。
 */
public class HomeProbe {

    private static final long SITE_TIMEOUT_MS = 5000L;
    private static final long TOTAL_BUDGET_MS = 20000L;
    private static final int MAX_CANDIDATES = 4;
    private static final int START_INDEX = 3;

    // 防魔改 Toast 关键词：jar 检测到篡改/改版软件后拒供数据时的典型提示
    private static final String[] ANTI_TAMPER_KEYWORDS = {"开源空壳", "魔改", "内置版本", "自动退出"};

    // 同一进程内防并发重复探测
    private static final AtomicInteger running = new AtomicInteger(0);

    // 防魔改标志：命中且归属当前首页站时，跳过默认站探测直接进候选
    private static final AtomicInteger antiTamper = new AtomicInteger(0);

    private static String failKey(String url) {
        // v2：旧 key 曾被 type==0 过滤误标为永久失败，换 key 让此类订阅重新获得探测机会
        return "home_probe_fail_v2:" + url;
    }

    // 重新添加订阅时重置「探测失败」标记
    public static void reset(String url) {
        if (TextUtils.isEmpty(url)) return;
        Prefers.remove(failKey(url));
    }

    // PopupShield 拦截到 jar 的防魔改 Toast 时回调：命中关键词即触发探测；归属当前首页站时置标志跳过默认站探测
    public static void onAntiTamperToast(String text, String siteName) {
        if (!Setting.isHomeAutoSwitch()) return;
        if (TextUtils.isEmpty(text)) return;
        boolean hit = false;
        for (String keyword : ANTI_TAMPER_KEYWORDS) {
            if (text.contains(keyword)) {
                hit = true;
                break;
            }
        }
        if (!hit) return;
        Site home = VodConfig.get().getHome();
        boolean skipDefault = !TextUtils.isEmpty(siteName) && siteName.equals(home.getName());
        if (skipDefault) antiTamper.set(1);
        DiagLog.log("home-probe", "anti-tamper toast site=%s skipDefault=%b", siteName, skipDefault);
        trigger();
    }

    public static void trigger() {
        if (!Setting.isHomeAutoSwitch()) return;
        if (running.getAndSet(1) == 1) return;
        Task.submit(() -> {
            try {
                probe();
            } catch (Throwable e) {
                DiagLog.log("home-probe", "probe error: %s", e);
            } finally {
                running.set(0);
            }
        });
    }

    private static void probe() {
        String url = VodConfig.getUrl();
        if (TextUtils.isEmpty(url)) return;
        boolean skipDefault = antiTamper.getAndSet(0) == 1;
        if (Prefers.getBoolean(failKey(url))) {
            DiagLog.log("home-probe", "skip: marked failed url=%s", url);
            return; // 此前探测全空，永久跳过
        }
        List<Site> sites = VodConfig.get().getSites();
        if (sites.size() <= START_INDEX) return; // 站点总数不足 4 个，直接空态不探测
        Site home = VodConfig.get().getHome();
        if (home.isEmpty()) return;
        long deadline = System.currentTimeMillis() + TOTAL_BUDGET_MS;
        if (!skipDefault && hasContent(home, deadline)) {
            DiagLog.log("home-probe", "default ok: %s", home.getKey());
            return; // 默认站有数据，零开销结束
        }
        DiagLog.log("home-probe", "start: default=%s empty or skipped, probing candidates", home.getKey());
        List<Site> candidates = new ArrayList<>();
        for (int i = START_INDEX; i < sites.size() && candidates.size() < MAX_CANDIDATES; i++) {
            Site item = sites.get(i);
            if (!item.getKey().equals(home.getKey())) candidates.add(item);
        }
        if (candidates.isEmpty()) {
            Prefers.put(failKey(url), true);
            return;
        }
        boolean exhausted = true;
        for (Site item : candidates) {
            if (System.currentTimeMillis() >= deadline) {
                exhausted = false; // 总预算耗尽，本轮未探完，不标记永久失败
                break;
            }
            boolean hit = hasContent(item, deadline);
            DiagLog.log("home-probe", "probe %s -> %s", item.getKey(), hit ? "data" : "empty");
            if (hit) {
                VodConfig.get().setHome(item); // 持久化 + RefreshEvent.home() 自动刷新首页
                DiagLog.log("home-probe", "switched: %s -> %s", home.getKey(), item.getKey());
                String tip = ResUtil.getString(R.string.home_switch_banner, item.getName());
                App.post(() -> Notify.show(tip)); // 一次性通知，约3秒自动消失
                return;
            }
        }
        if (exhausted) {
            Prefers.put(failKey(url), true); // 候选全空，后续不再探测
            DiagLog.log("home-probe", "all empty, marked failed url=%s", url);
        }
    }

    // 单站首页探测：Future 硬超时 min(5s, 剩余预算)，超时/异常均视为无数据
    private static boolean hasContent(Site site, long deadline) {
        long remain = deadline - System.currentTimeMillis();
        if (remain <= 0) return false;
        Future<Boolean> future = Task.largeExecutor().submit(() -> {
            try {
                Result result = SiteApi.homeContent(site);
                return !result.getTypes().isEmpty() || !result.getList().isEmpty();
            } catch (Throwable e) {
                return false;
            }
        });
        try {
            return Boolean.TRUE.equals(future.get(Math.min(SITE_TIMEOUT_MS, remain), TimeUnit.MILLISECONDS));
        } catch (Throwable e) {
            future.cancel(true);
            return false;
        }
    }
}
