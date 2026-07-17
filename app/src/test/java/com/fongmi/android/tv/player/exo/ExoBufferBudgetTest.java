package com.fongmi.android.tv.player.exo;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class ExoBufferBudgetTest {

    @Test
    public void lowRamDeviceUsesTwentyPercentHeapBudget() {
        assertEquals(percentOfMib(128, 20), effective(256, 128, true));
        assertEquals(percentOfMib(256, 20), effective(256, 256, true));
    }

    @Test
    public void normalDeviceUsesThirtyPercentHeapBudget() {
        assertEquals(percentOfMib(256, 30), effective(256, 256, false));
        assertEquals(percentOfMib(512, 30), effective(256, 512, false));
        assertEquals(percentOfMib(768, 30), effective(256, 768, false));
    }

    @Test
    public void heapBudgetNeverExceedsMaximum() {
        assertEquals(mib(256), effective(256, 1024, false));
        assertEquals(mib(256), effective(256, 2048, false));
    }

    @Test
    public void userTargetRemainsUpperBound() {
        assertEquals(mib(64), effective(64, 512, false));
        assertEquals(mib(128), effective(128, 1024, false));
    }

    @Test
    public void smallHeapDoesNotAllocateBeyondHeapLimit() {
        assertEquals(mib(16), effective(256, 16, true));
    }

    private static int effective(int requestedMib, int heapMib, boolean lowRamDevice) {
        return ExoBufferBudget.calculateEffectiveTargetBytes(mib(requestedMib), mibLong(heapMib), lowRamDevice);
    }

    private static int percentOfMib(int heapMib, int percent) {
        return (int) (mibLong(heapMib) * percent / 100);
    }

    private static int mib(int value) {
        return value * 1024 * 1024;
    }

    private static long mibLong(int value) {
        return value * 1024L * 1024L;
    }
}
