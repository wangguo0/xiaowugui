package com.fongmi.android.tv.player.exo;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class CacheCapacityStateTest {

    @Test
    public void currentSessionReportsActualEvictorCapacity() {
        CacheCapacityState state = new CacheCapacityState();
        state.recordCreated(mib(128));

        assertEquals(mib(128), state.report(mib(512)));
        assertEquals(mib(512), state.pendingCapacityBytes());
        assertTrue(state.hasPending());
    }

    @Test
    public void safeReleaseAllowsNextSessionCapacity() {
        CacheCapacityState state = new CacheCapacityState();
        state.recordCreated(mib(128));
        state.report(mib(512));
        state.recordReleased();

        assertFalse(state.hasPending());
        assertEquals(mib(512), state.report(mib(512)));
        state.recordCreated(mib(512));
        assertEquals(mib(512), state.report(mib(512)));
        assertFalse(state.hasPending());
    }

    private static long mib(long value) {
        return value * 1024L * 1024L;
    }
}
