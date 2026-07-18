package com.fongmi.android.tv.player.exo;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PreCachePolicyTest {

    @Test
    public void initialAndRecoveryUseIndependentWatermarks() {
        assertEquals(5_000, target(false, -1, 0, 128));
        assertEquals(8_000, target(true, -1, 0, 128));
    }

    @Test
    public void highBitrateTargetFitsInsideEffectiveCapacity() {
        assertEquals(3_435, target(false, -1, 100, 51.2));
        assertEquals(3_435, target(true, -1, 100, 51.2));
    }

    @Test
    public void targetNeverExtendsPastMediaEnd() {
        assertEquals(1_500, target(false, 1_500, 0, 128));
        assertEquals(2_500, target(true, 2_500, 0, 128));
    }

    @Test
    public void loadingRequiresFullWatermark() {
        assertFalse(PreCachePolicy.hasSafeBuffer(4_999, true, 5_000, false));
        assertTrue(PreCachePolicy.hasSafeBuffer(5_000, true, 5_000, false));
        assertFalse(PreCachePolicy.hasSafeBuffer(7_999, true, 8_000, true));
    }

    @Test
    public void loadControlIdleFallbackStillKeepsAReserve() {
        assertFalse(PreCachePolicy.hasSafeBuffer(1_999, false, 5_000, false));
        assertTrue(PreCachePolicy.hasSafeBuffer(2_000, false, 5_000, false));
        assertFalse(PreCachePolicy.hasSafeBuffer(2_999, false, 8_000, true));
        assertTrue(PreCachePolicy.hasSafeBuffer(3_000, false, 8_000, true));
    }

    private static long target(boolean recovery, long remainingMs, double bitrateMbps, double capacityMib) {
        long bitrate = Math.round(bitrateMbps * 1_000_000);
        int capacity = (int) Math.round(capacityMib * 1024 * 1024);
        return PreCachePolicy.safeBufferTargetMs(recovery, remainingMs, bitrate, capacity);
    }
}
