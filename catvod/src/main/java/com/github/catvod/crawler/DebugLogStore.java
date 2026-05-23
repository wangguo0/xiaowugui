package com.github.catvod.crawler;

import android.text.TextUtils;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class DebugLogStore {

    private static final Object LOCK = new Object();
    private static final ArrayDeque<String> LINES = new ArrayDeque<>();
    private static final ThreadLocal<SimpleDateFormat> FORMAT = ThreadLocal.withInitial(() -> new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US));
    private static volatile boolean enabled;

    public static boolean isEnabled() {
        return enabled;
    }

    public static void setEnabled(boolean enabled) {
        DebugLogStore.enabled = enabled;
        if (enabled) add("debug", "调试日志已开启");
        else clear();
    }

    public static void add(String tag, String msg) {
        if (!isEnabled()) return;
        if (TextUtils.isEmpty(msg)) return;
        String line = FORMAT.get().format(new Date()) + " [" + Thread.currentThread().getName() + "] " + safe(tag) + ": " + msg;
        synchronized (LOCK) {
            LINES.addLast(line);
        }
    }

    public static String text() {
        if (!isEnabled()) return "调试日志未开启";
        List<String> copy = snapshot();
        if (copy.isEmpty()) return "暂无调试日志";
        StringBuilder builder = new StringBuilder();
        for (String line : copy) builder.append(line).append('\n');
        return builder.toString();
    }

    public static List<String> snapshot() {
        synchronized (LOCK) {
            return new ArrayList<>(LINES);
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
        }
    }

    private static String safe(String tag) {
        return TextUtils.isEmpty(tag) ? "Debug" : tag;
    }
}
