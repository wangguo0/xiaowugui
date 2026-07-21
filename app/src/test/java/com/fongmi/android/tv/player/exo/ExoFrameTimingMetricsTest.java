package com.fongmi.android.tv.player.exo;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ExoFrameTimingMetricsTest {

    @Test
    public void aggregatesEarlyAndLateFrameBatches() {
        ExoFrameTimingMetrics metrics = new ExoFrameTimingMetrics();
        metrics.observeProcessingOffset(40_000, 4);
        metrics.observeProcessingOffset(-10_000, 1);

        ExoFrameTimingMetrics.Snapshot snapshot = metrics.snapshot();
        assertEquals(6_000, snapshot.averageOffsetUs());
        assertEquals(5, snapshot.frameCount());
        assertEquals(1, snapshot.lateBatchCount());
    }

    @Test
    public void recordsRecoverableCodecErrorsAndReset() {
        ExoFrameTimingMetrics metrics = new ExoFrameTimingMetrics();
        metrics.observeCodecError(new IllegalStateException("codec stalled"));
        assertEquals(1, metrics.snapshot().codecErrorCount());
        assertTrue(metrics.snapshot().lastCodecError().contains("codec stalled"));

        metrics.reset();
        assertEquals(0, metrics.snapshot().codecErrorCount());
        assertEquals(0, metrics.snapshot().frameCount());
    }

    @Test
    public void aggregatesScheduledReleaseLatenessJitterAndCallbackGap() {
        ExoFrameTimingMetrics metrics = new ExoFrameTimingMetrics();
        metrics.observeFrameRelease(0, 1_000_000_000L, 999_000_000L);
        metrics.observeFrameRelease(40_000, 1_040_000_000L, 1_041_000_000L);
        metrics.observeFrameRelease(80_000, 1_082_000_000L, 1_084_000_000L);

        ExoFrameTimingMetrics.Snapshot snapshot = metrics.snapshot();
        assertEquals(3, snapshot.releaseFrameCount());
        assertEquals(-666, snapshot.averageReleaseLeadUs());
        assertEquals(2, snapshot.lateReleaseFrameCount());
        assertEquals(2_000, snapshot.maxLateReleaseUs());
        assertEquals(1_000, snapshot.averageReleaseJitterUs());
        assertEquals(42_500, snapshot.averageCallbackGapUs());
        assertEquals(43_000, snapshot.maxCallbackGapUs());
    }
}
