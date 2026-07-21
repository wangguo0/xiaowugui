package com.fongmi.android.tv.player.exo;

/** Aggregates Media3 frame processing offsets without logging every rendered frame. */
public final class ExoFrameTimingMetrics {

    private long totalProcessingOffsetUs;
    private long frameCount;
    private int lateBatchCount;
    private int codecErrorCount;
    private String lastCodecError = "";

    synchronized void reset() {
        totalProcessingOffsetUs = 0;
        frameCount = 0;
        lateBatchCount = 0;
        codecErrorCount = 0;
        lastCodecError = "";
    }

    synchronized void observeProcessingOffset(long batchOffsetUs, int batchFrameCount) {
        if (batchFrameCount <= 0) return;
        totalProcessingOffsetUs = saturatedAdd(totalProcessingOffsetUs, batchOffsetUs);
        frameCount = saturatedAdd(frameCount, batchFrameCount);
        if (batchOffsetUs < 0) lateBatchCount++;
    }

    synchronized void observeCodecError(Exception error) {
        codecErrorCount++;
        lastCodecError = error == null ? "unknown" : error.getClass().getSimpleName() + (error.getMessage() == null ? "" : ": " + error.getMessage());
    }

    synchronized Snapshot snapshot() {
        long averageOffsetUs = frameCount <= 0 ? 0 : totalProcessingOffsetUs / frameCount;
        return new Snapshot(averageOffsetUs, frameCount, lateBatchCount, codecErrorCount, lastCodecError);
    }

    private static long saturatedAdd(long first, long second) {
        if (second > 0 && first > Long.MAX_VALUE - second) return Long.MAX_VALUE;
        if (second < 0 && first < Long.MIN_VALUE - second) return Long.MIN_VALUE;
        return first + second;
    }

    public record Snapshot(long averageOffsetUs, long frameCount, int lateBatchCount, int codecErrorCount, String lastCodecError) {
    }
}
