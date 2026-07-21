package com.fongmi.android.tv.player.exo;

import java.util.ArrayDeque;
import java.util.Deque;

final class ForwardBufferTrend {

    private static final long MIN_SAMPLE_INTERVAL_MS = 1_000;
    private static final long MIN_WINDOW_MS = 5_000;
    private static final long MEDIUM_WINDOW_MS = 15_000;
    private static final long HIGH_WINDOW_MS = 30_000;
    private static final long MAX_WINDOW_MS = 30_000;

    private final Deque<Sample> samples = new ArrayDeque<>();

    synchronized void reset() {
        samples.clear();
    }

    synchronized void observe(long nowMs, long bufferedMs, boolean stablePlayback) {
        if (!stablePlayback || bufferedMs < 0) {
            reset();
            return;
        }
        Sample last = samples.peekLast();
        if (last != null && nowMs - last.nowMs() < MIN_SAMPLE_INTERVAL_MS) return;
        samples.addLast(new Sample(nowMs, bufferedMs));
        while (samples.size() > 2 && nowMs - samples.peekFirst().nowMs() > MAX_WINDOW_MS) samples.removeFirst();
    }

    synchronized Snapshot snapshot() {
        Sample first = samples.peekFirst();
        Sample last = samples.peekLast();
        if (first == null || last == null) return Snapshot.unknown();
        long windowMs = last.nowMs() - first.nowMs();
        if (windowMs < MIN_WINDOW_MS) return new Snapshot(0, windowMs, samples.size(), Confidence.UNKNOWN);
        long deltaMs = last.bufferedMs() - first.bufferedMs();
        long slope = deltaMs * 1_000L / windowMs;
        Confidence confidence = windowMs >= HIGH_WINDOW_MS ? Confidence.HIGH : windowMs >= MEDIUM_WINDOW_MS ? Confidence.MEDIUM : Confidence.LOW;
        return new Snapshot(slope, windowMs, samples.size(), confidence);
    }

    enum Confidence {
        HIGH("high"),
        MEDIUM("medium"),
        LOW("low"),
        UNKNOWN("unknown");

        private final String label;

        Confidence(String label) {
            this.label = label;
        }

        String label() {
            return label;
        }
    }

    record Snapshot(long slopeMsPerSecond, long windowMs, int sampleCount, Confidence confidence) {

        static Snapshot unknown() {
            return new Snapshot(0, 0, 0, Confidence.UNKNOWN);
        }

        boolean known() {
            return confidence != Confidence.UNKNOWN;
        }
    }

    private record Sample(long nowMs, long bufferedMs) {
    }
}
