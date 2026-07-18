package com.fongmi.android.tv.player.exo;

final class PreCachePolicy {

    static final long INITIAL_SAFE_BUFFER_MS = 5_000;
    static final long RECOVERY_SAFE_BUFFER_MS = 8_000;
    private static final long INITIAL_IDLE_FLOOR_MS = 2_000;
    private static final long RECOVERY_IDLE_FLOOR_MS = 3_000;
    private static final int CAPACITY_HEADROOM_PERCENT = 80;
    private static final long MIN_CAPACITY_TARGET_MS = 1_000;

    private PreCachePolicy() {
    }

    static long safeBufferTargetMs(boolean recovery, long remainingMs, long bitrateBitsPerSecond, int capacityBytes) {
        long targetMs = recovery ? RECOVERY_SAFE_BUFFER_MS : INITIAL_SAFE_BUFFER_MS;
        if (remainingMs >= 0) targetMs = Math.min(targetMs, remainingMs);
        long capacityMs = capacityDurationMs(bitrateBitsPerSecond, capacityBytes);
        if (capacityMs > 0) {
            long usableCapacityMs = Math.max(MIN_CAPACITY_TARGET_MS, capacityMs * CAPACITY_HEADROOM_PERCENT / 100);
            targetMs = Math.min(targetMs, usableCapacityMs);
        }
        return Math.max(0, targetMs);
    }

    static boolean hasSafeBuffer(long bufferedDurationMs, boolean loading, long targetMs, boolean recovery) {
        if (bufferedDurationMs >= targetMs) return true;
        long idleFloorMs = recovery ? RECOVERY_IDLE_FLOOR_MS : INITIAL_IDLE_FLOOR_MS;
        return !loading && bufferedDurationMs >= Math.min(targetMs, idleFloorMs);
    }

    private static long capacityDurationMs(long bitrateBitsPerSecond, int capacityBytes) {
        if (bitrateBitsPerSecond <= 0 || capacityBytes <= 0) return 0;
        long capacityBits = capacityBytes * 8L;
        if (capacityBits > Long.MAX_VALUE / 1_000L) return Long.MAX_VALUE;
        return capacityBits * 1_000L / bitrateBitsPerSecond;
    }
}
