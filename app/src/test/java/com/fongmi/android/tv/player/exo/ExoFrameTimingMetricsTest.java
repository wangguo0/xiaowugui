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
}
