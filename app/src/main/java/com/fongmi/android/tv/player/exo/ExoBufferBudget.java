package com.fongmi.android.tv.player.exo;

import android.app.ActivityManager;
import android.content.Context;

final class ExoBufferBudget {

    static final int MIN_TARGET_BYTES = 16 * 1024 * 1024;
    static final int MAX_TARGET_BYTES = 256 * 1024 * 1024;
    private static final int LOW_RAM_PERCENT = 20;
    private static final int NORMAL_RAM_PERCENT = 30;
    private static final int LOW_RAM_RESERVED_BYTES = 48 * 1024 * 1024;
    private static final int NORMAL_RESERVED_BYTES = 64 * 1024 * 1024;

    private ExoBufferBudget() {
    }

    static Budget resolve(Context context, int requestedTargetBytes) {
        ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        boolean lowRamDevice = manager != null && manager.isLowRamDevice();
        return calculate(requestedTargetBytes, Runtime.getRuntime().maxMemory(), lowRamDevice);
    }

    static int getEffectiveTargetBytes(Context context, int requestedTargetBytes) {
        return resolve(context, requestedTargetBytes).effectiveTargetBytes();
    }

    static int calculateEffectiveTargetBytes(int requestedTargetBytes, long heapLimitBytes, boolean lowRamDevice) {
        return calculate(requestedTargetBytes, heapLimitBytes, lowRamDevice).effectiveTargetBytes();
    }

    static Budget calculate(int requestedTargetBytes, long heapLimitBytes, boolean lowRamDevice) {
        long heapLimit = Math.max(0, heapLimitBytes);
        int percent = lowRamDevice ? LOW_RAM_PERCENT : NORMAL_RAM_PERCENT;
        int reservedHeadroom = lowRamDevice ? LOW_RAM_RESERVED_BYTES : NORMAL_RESERVED_BYTES;
        long proportionalBudget = heapLimit * percent / 100;
        long minimumBudget = Math.min(MIN_TARGET_BYTES, heapLimit);
        long percentageBudget = Math.max(minimumBudget, proportionalBudget);
        long availableAfterReserve = Math.max(0, heapLimit - reservedHeadroom);
        long headroomBudget = Math.max(minimumBudget, availableAfterReserve);
        long heapBudget = Math.min(heapLimit, Math.min(MAX_TARGET_BYTES, Math.min(percentageBudget, headroomBudget)));
        int requested = requestedTargetBytes > 0 ? requestedTargetBytes : MAX_TARGET_BYTES;
        int effective = (int) Math.min(requested, heapBudget);
        return new Budget(requested, effective, (int) heapBudget, heapLimit, reservedHeadroom, (int) Math.min(Integer.MAX_VALUE, availableAfterReserve), lowRamDevice);
    }

    record Budget(int requestedTargetBytes, int effectiveTargetBytes, int heapBudgetBytes, long heapLimitBytes, int reservedHeadroomBytes, int availableAfterReserveBytes, boolean lowRamDevice) {
    }
}
