package androidx.media3.mpvplayer;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MpvCacheObserverStateTest {

    @Test
    public void firstObserverValueDisablesFallbackForThatMetric() {
        MpvCacheObserverState state = new MpvCacheObserverState();

        assertTrue(state.needsFallback(MpvCacheObserverState.Metric.DURATION));
        assertTrue(state.record("demuxer-cache-state/cache-duration", 4.5));
        assertFalse(state.needsFallback(MpvCacheObserverState.Metric.DURATION));
        assertEquals(1, state.observedCount());
    }

    @Test
    public void aliasesShareOneLogicalMetric() {
        MpvCacheObserverState state = new MpvCacheObserverState();

        assertTrue(state.record("cache-speed", 1024L));
        assertFalse(state.record("demuxer-cache-state/raw-input-rate", 2048L));
        assertFalse(state.needsFallback(MpvCacheObserverState.Metric.SPEED));
        assertEquals(1, state.observedCount());
    }

    @Test
    public void unavailableAndUnrelatedPropertiesKeepFallbackEnabled() {
        MpvCacheObserverState state = new MpvCacheObserverState();

        assertFalse(state.record("demuxer-cache-state/fw-bytes", null));
        assertFalse(state.record("time-pos", 1.0));
        assertTrue(state.needsFallback(MpvCacheObserverState.Metric.FORWARD_BYTES));
        assertEquals(0, state.observedCount());
    }

    @Test
    public void resetRequiresFreshObserverValuesForNewMedia() {
        MpvCacheObserverState state = new MpvCacheObserverState();
        state.record("demuxer-cache-state/idle", false);
        state.record("demuxer-cache-state/eof-cached", true);

        assertEquals(2, state.observedCount());
        state.reset();

        assertEquals(0, state.observedCount());
        assertTrue(state.needsFallback(MpvCacheObserverState.Metric.IDLE));
        assertTrue(state.needsFallback(MpvCacheObserverState.Metric.EOF));
    }
}
