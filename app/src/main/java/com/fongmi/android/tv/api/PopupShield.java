package com.fongmi.android.tv.api;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Choreographer;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.api.loader.JarLoader;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.utils.DiagLog;
import com.fongmi.android.tv.utils.Notify;
import com.github.catvod.utils.Util;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.WeakHashMap;

/**
 * 第三方订阅源「弹窗广告 / 免责声明」拦截。
 * <p>
 * 这类弹窗不是本软件产生的：spider.jar 拿到 App 交给它的 Context 后，自行
 * new AlertDialog(activity).show()（独立 Window），或 addContentView(...)
 * 往页面里塞遮罩。
 * <p>
 * 处置刻意保守，全程不抛异常、不改任何站点状态：
 * <ul>
 *   <li>对话框等独立窗口：命中即「隐身」——根 View 置 GONE、窗口改为不可触摸不可聚焦并取消遮罩。
 *       绝不调用 removeView，因为窗口一旦脱离，jar 后续 dismiss() 会抛
 *       "View not attached to window manager"，该异常堆栈含 spider 帧，会被
 *       {@link JarCrashShield} 判定为 jar 崩溃，进而永久屏蔽整个站源。</li>
 *   <li>页面内遮罩：只检查 content 新增的直接子 View，且仅当其中出现 jar 自有类
 *       （com.github.catvod.spider.*）时才摘除，避免误删播放器字幕层等同级 View。</li>
 *   <li>源内 Toast：spider.jar 会用 App 交给它的 Context 直接 Toast.makeText 弹提示，
 *       完全绕过 {@link com.fongmi.android.tv.utils.Notify}。因此额外按窗口类型
 *       TYPE_TOAST 拦截：文本命中 {@code Notify.isRecent}（本应用自身刚弹过的）
 *       则放行，否则 WMS 层透明化后整窗移除（仅置 GONE 留不住画面，见 killToast）。扫描为逐帧驱动，
 *       toast 最多闪现一帧。受「屏蔽接口提示」开关控制，与弹窗拦截开关相互独立。</li>
 * </ul>
 * 独立窗口依赖 {@code WindowManagerGlobal} 反射枚举；若被系统限制，会记录一次降级日志，
 * 页面内遮罩的拦截仍然有效。
 */
public final class PopupShield implements Application.ActivityLifecycleCallbacks {

    public static final List<String> DEFAULT_KEYWORDS = Collections.unmodifiableList(Arrays.asList(
            "未搜索到影视信息", "我已经知悉并了解", "请仔细阅读", "仅用于个人学习", "24小时之内", "我们不承担任何责任"));

    private static final String TAG = "popup-shield";
    private static final String JAR_PREFIX = "com.github.catvod.spider.";

    private static volatile PopupShield instance;

    // 已判定过且确认无害的窗口，避免重复扫描
    private final WeakHashMap<View, Boolean> checked = new WeakHashMap<>();
    // 已隐身的窗口，jar 重新 show 时需要再次压下去
    private final List<View> blocked = new ArrayList<>();
    // 各 Activity content 已知的子 View 数量
    private final WeakHashMap<View, Integer> contentCount = new WeakHashMap<>();
    // 本应用全部 Activity 的 DecorView，用于区分「独立窗口」与「页面自身」
    private final List<View> decors = new ArrayList<>();

    private volatile boolean running;
    private volatile boolean capabilityLogged;
    private volatile List<String> keywords;
    private volatile String keywordsRaw;

    private final Choreographer.FrameCallback frameCallback = new Choreographer.FrameCallback() {
        @Override
        public void doFrame(long frameTimeNanos) {
            try {
                // 逐帧驱动（约 16ms）：toast 窗口出现后下一帧即被处理，最多闪现一帧，肉眼不可见。
                // scan 内部按「弹窗拦截」「屏蔽接口提示」两个开关各自分流。
                if (running) scan();
            } catch (Throwable ignored) {
            }
            if (running) Choreographer.getInstance().postFrameCallback(frameCallback);
        }
    };

    private PopupShield() {
    }

    public static void install(Application app) {
        if (App.isProbeProcess()) return;
        if (instance == null) instance = new PopupShield();
        app.registerActivityLifecycleCallbacks(instance);
        instance.apply(active());
        DiagLog.log(TAG, "初始化 模式=逐帧+透明化+移除窗口");
    }

    public static void setEnabled(boolean enable) {
        reload();
    }

    // 弹窗拦截或接口提示拦截任一开启，扫描脉冲即需运行
    private static boolean active() {
        return Setting.isPopupShield() || Setting.isBlockNotice();
    }

    // 开关变化后重算脉冲是否运行（扫描内部按各自开关分流）
    public static void reload() {
        if (instance != null) instance.apply(active());
    }

    private synchronized void apply(boolean enable) {
        if (enable == running) return;
        running = enable;
        if (enable) {
            Choreographer.getInstance().postFrameCallback(frameCallback);
        } else {
            Choreographer.getInstance().removeFrameCallback(frameCallback);
            checked.clear();
            blocked.clear();
            contentCount.clear();
        }
    }

    // ==================== 生命周期：登记 DecorView ====================

    @Override
    public void onActivityCreated(@NonNull Activity activity, @Nullable Bundle savedInstanceState) {
        track(activity);
    }

    @Override
    public void onActivityResumed(@NonNull Activity activity) {
        track(activity);
    }

    private void track(Activity activity) {
        try {
            View decor = activity.getWindow().getDecorView();
            if (!decors.contains(decor)) decors.add(decor);
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onActivityDestroyed(@NonNull Activity activity) {
        try {
            View decor = activity.getWindow().getDecorView();
            decors.remove(decor);
            checked.remove(decor);
            contentCount.remove(decor.findViewById(android.R.id.content));
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onActivityStarted(@NonNull Activity activity) {
    }

    @Override
    public void onActivityPaused(@NonNull Activity activity) {
    }

    @Override
    public void onActivityStopped(@NonNull Activity activity) {
    }

    @Override
    public void onActivitySaveInstanceState(@NonNull Activity activity, @NonNull Bundle outState) {
    }

    // ==================== 扫描 ====================

    private void scan() {
        reassert();
        List<String> words = keywords();
        boolean popup = Setting.isPopupShield();
        boolean toastBlock = Setting.isBlockNotice();
        if (popup) for (View decor : new ArrayList<>(decors)) checkOverlay(decor);
        List<View> roots = WindowRoots.get();
        if (roots.isEmpty()) {
            logCapability();
            return;
        }
        capabilityLogged = false;
        for (View root : roots) {
            if (root == null || root.getWindowToken() == null) continue;
            if (isDecor(root)) continue;
            // Toast 窗口一律按 toast 规则处理，不走弹窗关键字判定，避免误杀本应用自身提示
            if (checkToast(root, toastBlock)) continue;
            if (popup) checkWindow(root, words);
        }
    }

    /**
     * TYPE_TOAST 窗口拦截：文本属于本应用最近弹出的（Notify.isRecent）则放行，
     * 否则视为源内（jar 等）弹出的提示并隐藏。
     * 每个新出现的 toast 窗口都会记录一次观测日志（拦截/放行），便于按日志定位漏网环节。
     *
     * @return true 表示该窗口为 toast 类型（无论是否隐藏），调用方无需再按弹窗逻辑判定
     */
    private boolean checkToast(View root, boolean block) {
        if (!(root.getLayoutParams() instanceof WindowManager.LayoutParams lp)) return false;
        if (lp.type != WindowManager.LayoutParams.TYPE_TOAST) return false;
        if (!checked.containsKey(root)) {
            checked.put(root, Boolean.TRUE);
            String text = toastText(root);
            // 防魔改 Toast 信号：jar 拒供数据时触发首页站点自动切换（不受 toast 拦截开关影响）
            if (!Notify.isRecent(text)) HomeProbe.onAntiTamperToast(text, currentSite());
            if (block && !Notify.isRecent(text)) {
                String method = killToast(root);
                DiagLog.log(TAG, "拦截Toast 文本=%s 站点=%s 方式=%s", text, currentSite(), method);
            } else if (block) {
                DiagLog.log(TAG, "放行自家Toast 文本=%s", text);
            } else {
                DiagLog.log(TAG, "观测Toast(拦截关闭) 文本=%s", text);
            }
        }
        return true;
    }

    private String toastText(View view) {
        StringBuilder builder = new StringBuilder();
        collectText(view, builder);
        return builder.toString().trim();
    }

    /**
     * 杀 toast，三层保险，返回实际生效方式（写入日志，便于定位漏网环节）：
     * <ol>
     * <li>WMS 层窗口透明化：lp.alpha=0 + FLAG_NOT_TOUCHABLE + updateViewLayout。
     *     alpha 由 SurfaceFlinger 直接作用于 surface，不依赖 view 重绘——「GONE 后
     *     surface 保留最后一帧导致画面残留」的失效模式在此被彻底堵死；</li>
     * <li>removeView 整窗销毁：物理移除窗口。TN.handleHide 对已移除视图（parent==null）
     *     会跳过系统侧 removeView，jar 再 cancel()/show() 无副作用，不会引发崩溃；</li>
     * <li>兜底：GONE + 强制重绘（仅当前两层都抛异常时才会走到）。</li>
     * </ol>
     */
    private String killToast(View root) {
        root.setVisibility(View.GONE);
        StringBuilder failed = new StringBuilder();
        Object service = root.getContext().getSystemService(Context.WINDOW_SERVICE);
        if (service instanceof WindowManager wm) {
            try {
                if (root.getLayoutParams() instanceof WindowManager.LayoutParams lp) {
                    lp.alpha = 0f;
                    lp.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
                    wm.updateViewLayout(root, lp);
                }
            } catch (Throwable e) {
                failed.append("透明化失败:").append(e.getClass().getSimpleName()).append('/');
            }
            try {
                wm.removeView(root);
                return failed.length() == 0 ? "透明化+移除" : "移除(" + failed.substring(0, failed.length() - 1) + ")";
            } catch (Throwable e) {
                failed.append("移除失败:").append(e.getClass().getSimpleName()).append('/');
            }
        } else {
            failed.append("WM获取失败/");
        }
        root.invalidate();
        root.requestLayout();
        return "GONE兜底(" + failed.substring(0, failed.length() - 1) + ")";
    }

    private void collectText(View view, StringBuilder builder) {
        if (view instanceof TextView text) {
            String value = text.getText() == null ? "" : text.getText().toString();
            if (!value.isEmpty()) {
                if (builder.length() > 0) builder.append("\n");
                builder.append(value);
            }
        }
        if (view instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) collectText(group.getChildAt(i), builder);
        }
    }

    /**
     * 页面内遮罩：只处理 content 新增的直接子 View，且仅当其中出现 jar 自有类才摘除，
     * 避免误删播放器字幕层、弹幕层等同级 View。
     */
    private void checkOverlay(View decor) {
        try {
            if (!(decor.findViewById(android.R.id.content) instanceof ViewGroup content)) return;
            int count = content.getChildCount();
            Integer last = contentCount.get(content);
            if (last == null) {
                contentCount.put(content, count);
                return;
            }
            if (count < last) {
                contentCount.put(content, count);
                return;
            }
            if (count <= last) return;
            for (int i = count - 1; i >= last; i--) {
                String jar = jarView(content.getChildAt(i));
                if (jar == null) continue;
                content.removeViewAt(i);
                DiagLog.log(TAG, "拦截遮罩 命中=%s 站点=%s", jar, currentSite());
            }
            contentCount.put(content, content.getChildCount());
        } catch (Throwable ignored) {
        }
    }

    private void logCapability() {
        if (capabilityLogged) return;
        capabilityLogged = true;
        DiagLog.log(TAG, "无法枚举窗口（系统限制），仅页面内遮罩拦截生效");
    }

    private boolean isDecor(View root) {
        return decors.contains(root);
    }

    private void checkWindow(View root, List<String> words) {
        if (checked.containsKey(root)) return;
        checked.put(root, Boolean.TRUE);
        String offense = offense(root, words);
        if (offense == null) return;
        hide(root);
        DiagLog.log(TAG, "拦截弹窗 命中=%s 站点=%s", offense, currentSite());
    }

    private void reassert() {
        if (blocked.isEmpty()) return;
        for (View root : new ArrayList<>(blocked)) {
            if (root.getWindowToken() == null) {
                blocked.remove(root);
            } else if (root.getVisibility() != View.GONE) {
                hide(root);
            }
        }
    }

    private void hide(View root) {
        if (!blocked.contains(root)) blocked.add(root);
        root.setVisibility(View.GONE);
        WindowManager.LayoutParams lp = root.getLayoutParams() instanceof WindowManager.LayoutParams p ? p : null;
        if (lp == null) return;
        lp.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        lp.flags &= ~WindowManager.LayoutParams.FLAG_DIM_BEHIND;
        try {
            Object service = root.getContext().getSystemService(Context.WINDOW_SERVICE);
            if (service instanceof WindowManager) ((WindowManager) service).updateViewLayout(root, lp);
        } catch (Throwable ignored) {
        }
    }

    // ==================== 判定 ====================

    private String offense(View view, List<String> words) {
        if (view instanceof TextView text) {
            String value = text.getText() == null ? "" : text.getText().toString();
            for (String word : words) {
                if (!word.isEmpty() && value.contains(word)) return word;
            }
        }
        if (view.getClass().getName().startsWith(JAR_PREFIX)) return "jar:" + view.getClass().getName();
        if (view instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) {
                String offense = offense(group.getChildAt(i), words);
                if (offense != null) return offense;
            }
        }
        return null;
    }

    private String jarView(View view) {
        if (view.getClass().getName().startsWith(JAR_PREFIX)) return view.getClass().getName();
        if (view instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) {
                String jar = jarView(group.getChildAt(i));
                if (jar != null) return jar;
            }
        }
        return null;
    }

    private String currentSite() {
        try {
            JarLoader loader = JarLoader.get();
            String recent = loader == null ? null : loader.getRecent();
            if (TextUtils.isEmpty(recent)) return "未知";
            for (Site site : VodConfig.get().getSites()) {
                String jar = site.getJar();
                if (!TextUtils.isEmpty(jar) && recent.equals(Util.md5(jar))) return site.getName();
            }
        } catch (Throwable ignored) {
        }
        return "未知";
    }

    // ==================== 关键字 ====================

    private List<String> keywords() {
        String raw = Setting.getPopupKeywords();
        if (raw == null) return DEFAULT_KEYWORDS;
        if (raw.equals(keywordsRaw)) return keywords;
        keywordsRaw = raw;
        keywords = split(raw);
        return keywords;
    }

    public static List<String> getKeywords() {
        String raw = Setting.getPopupKeywords();
        return raw == null ? new ArrayList<>(DEFAULT_KEYWORDS) : split(raw);
    }

    public static void saveKeywords(List<String> items) {
        Setting.putPopupKeywords(TextUtils.join("\n", items));
        refresh();
    }

    public static void resetKeywords() {
        Setting.putPopupKeywords(TextUtils.join("\n", DEFAULT_KEYWORDS));
        refresh();
    }

    // 词表变化后需重新判定此前已放行的窗口
    private static void refresh() {
        if (instance == null) return;
        instance.keywordsRaw = null;
        instance.checked.clear();
        instance.blocked.clear();
    }

    private static List<String> split(String raw) {
        List<String> result = new ArrayList<>();
        for (String item : raw.split("\n")) {
            if (!TextUtils.isEmpty(item.trim())) result.add(item.trim());
        }
        return result;
    }

    /**
     * 枚举本进程全部窗口根 View，复用 DanmakuSearchListFocusFixer 的成熟反射方式。
     */
    static final class WindowRoots {

        private WindowRoots() {
        }

        static List<View> get() {
            List<View> roots = byMethod();
            return roots.isEmpty() ? byField() : roots;
        }

        private static Object global() {
            try {
                Class<?> clazz = Class.forName("android.view.WindowManagerGlobal");
                return clazz.getMethod("getInstance").invoke(null);
            } catch (Throwable ignored) {
                return null;
            }
        }

        private static List<View> byMethod() {
            List<View> roots = new ArrayList<>();
            try {
                Object global = global();
                if (global == null) return roots;
                Method names = global.getClass().getMethod("getViewRootNames");
                Method root = global.getClass().getMethod("getRootView", String.class);
                if (!(names.invoke(global) instanceof String[] values)) return roots;
                for (String name : values) {
                    Object view = root.invoke(global, name);
                    if (view instanceof View view1) roots.add(view1);
                }
            } catch (Throwable ignored) {
            }
            return roots;
        }

        private static List<View> byField() {
            try {
                Object global = global();
                if (global == null) return Collections.emptyList();
                Field views = global.getClass().getDeclaredField("mViews");
                views.setAccessible(true);
                if (!(views.get(global) instanceof List<?> list)) return Collections.emptyList();
                List<View> roots = new ArrayList<>();
                for (Object item : list) if (item instanceof View view) roots.add(view);
                return roots;
            } catch (Throwable ignored) {
                return Collections.emptyList();
            }
        }
    }
}
