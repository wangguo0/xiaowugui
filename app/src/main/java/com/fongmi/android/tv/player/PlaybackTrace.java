package com.fongmi.android.tv.player;

import android.os.SystemClock;

import com.github.catvod.crawler.SpiderDebug;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

public final class PlaybackTrace {

    public static final String NONE = "none";

    private static final AtomicLong SEQUENCE = new AtomicLong();
    private String traceId = "";

    public String begin() {
        return begin(SystemClock.elapsedRealtime());
    }

    String begin(long elapsedRealtimeMs) {
        traceId = createId(elapsedRealtimeMs, SEQUENCE.incrementAndGet());
        return traceId;
    }

    public String ensure() {
        return traceId.isEmpty() ? begin() : traceId;
    }

    String ensure(long elapsedRealtimeMs) {
        return traceId.isEmpty() ? begin(elapsedRealtimeMs) : traceId;
    }

    public String current() {
        return normalize(traceId);
    }

    public void clear() {
        traceId = "";
    }

    public static String normalize(String traceId) {
        return isValid(traceId) ? traceId : NONE;
    }

    public static void log(String tag, String traceId, String format, Object... args) {
        if (!SpiderDebug.isEnabled()) return;
        Object[] values = new Object[(args == null ? 0 : args.length) + 1];
        values[0] = normalize(traceId);
        if (args != null && args.length > 0) System.arraycopy(args, 0, values, 1, args.length);
        SpiderDebug.log(tag, "trace=%s " + format, values);
    }

    static String createId(long elapsedRealtimeMs, long sequence) {
        long time = Math.max(0, elapsedRealtimeMs);
        long count = Math.max(1, sequence);
        return "p-" + Long.toString(time, 36).toLowerCase(Locale.US) + "-" + Long.toString(count, 36).toLowerCase(Locale.US);
    }

    private static boolean isValid(String value) {
        if (value == null || !value.startsWith("p-") || value.length() < 5) return false;
        int separator = value.indexOf('-', 2);
        if (separator <= 2 || separator >= value.length() - 1) return false;
        return isBase36(value, 2, separator) && isBase36(value, separator + 1, value.length());
    }

    private static boolean isBase36(String value, int start, int end) {
        for (int i = start; i < end; i++) {
            char c = value.charAt(i);
            if (c >= '0' && c <= '9') continue;
            if (c >= 'a' && c <= 'z') continue;
            return false;
        }
        return true;
    }
}
