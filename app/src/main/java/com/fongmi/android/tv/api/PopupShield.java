package com.fongmi.android.tv.api;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.os.Bundle;
import android.text.TextUtils;
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
 * </ul>
 * 独立窗口依赖 {@code WindowManagerGlobal} 反射枚举；若被系统限制，会记录一次降级日志，
 * 页面内遮罩的拦截仍然有效。
 */
public final class PopupShield implements Application.ActivityLifecycleCallbacks {

    public static final List<String> DEFAULT_KEYWORDS = Collections.unmodifiableList(Arrays.asList(
            "未搜索到影视信息", "我已经知悉并了解", "请仔细阅读", "仅用于个人学习", "24小时之内", "我们不承担任何责任"));

    private static final String TAG = "popup-shield";
    private static final String JAR_PREFIX = "com.github.catvod.spider.";
    private static final long INTERVAL_MS = 250;

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

    private final Runnable pulse = new Runnable() {
        @Override
        public void run() {
            try {
                if (running && Setting.isPopupShield()) scan();
            } catch (Throwable ignored) {
            }
            if (running) App.post(pulse, INTERVAL_MS);
        }
    };

    private PopupShield() {
    }

    public static void install(Application app) {
        if (App.isProbeProcess()) return;
        if (instance == null) instance = new PopupShield();
        app.registerActivityLifecycleCallbacks(instance);
        instance.apply(Setting.isPopupShield());
    }

    public static void setEnabled(boolean enable) {
        if (instance != null) instance.apply(enable);
    }

    private synchronized void apply(boolean enable) {
        if (enable == running) return;
        running = enable;
        if (enable) {
            App.post(pulse, INTERVAL_MS);
        } else {
            App.removeCallbacks(pulse);
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
        for (View decor : new ArrayList<>(decors)) checkOverlay(decor);
        List<View> roots = WindowRoots.get();
        if (roots.isEmpty()) {
            logCapability();
            return;
        }
        capabilityLogged = false;
        for (View root : roots) {
            if (root == null || root.getWindowToken() == null) continue;
            if (isDecor(root)) continue;
            checkWindow(root, words);
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
