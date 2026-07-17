package com.fongmi.android.tv.player.exo;

import android.app.ActivityManager;
import android.content.Context;

final class ExoBufferBudget {

    static final int MIN_TARGET_BYTES = 24 * 1024 * 1024;
    static final int MAX_TARGET_BYTES = 256 * 1024 * 1024;
    private static final int LOW_RAM_PERCENT = 20;
    private static final int NORMAL_RAM_PERCENT = 30;

    private ExoBufferBudget() {
    }

    static int getEffectiveTargetBytes(Context context, int requestedTargetBytes) {
        ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        boolean lowRamDevice = manager != null && manager.isLowRamDevice();
        return calculateEffectiveTargetBytes(requestedTargetBytes, Runtime.getRuntime().maxMemory(), lowRamDevice);
    }

    static int calculateEffectiveTargetBytes(int requestedTargetBytes, long heapLimitBytes, boolean lowRamDevice) {
        long heapLimit = Math.max(0, heapLimitBytes);
        int percent = lowRamDevice ? LOW_RAM_PERCENT : NORMAL_RAM_PERCENT;
        long proportionalBudget = heapLimit * percent / 100;
        long minimumBudget = Math.min(MIN_TARGET_BYTES, heapLimit);
        long heapBudget = Math.min(MAX_TARGET_BYTES, Math.max(minimumBudget, proportionalBudget));
        long requested = requestedTargetBytes > 0 ? requestedTargetBytes : MAX_TARGET_BYTES;
        return (int) Math.min(requested, heapBudget);
    }
}
