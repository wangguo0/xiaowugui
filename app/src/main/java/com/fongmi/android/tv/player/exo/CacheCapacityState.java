package com.fongmi.android.tv.player.exo;

final class CacheCapacityState {

    private long actualCapacityBytes;
    private long pendingCapacityBytes;

    synchronized void recordCreated(long capacityBytes) {
        actualCapacityBytes = Math.max(0, capacityBytes);
        pendingCapacityBytes = 0;
    }

    synchronized long report(long desiredCapacityBytes) {
        long desired = Math.max(0, desiredCapacityBytes);
        if (actualCapacityBytes <= 0) return desired;
        pendingCapacityBytes = desired == actualCapacityBytes ? 0 : desired;
        return actualCapacityBytes;
    }

    synchronized boolean hasPending() {
        return actualCapacityBytes > 0 && pendingCapacityBytes > 0 && pendingCapacityBytes != actualCapacityBytes;
    }

    synchronized long pendingCapacityBytes() {
        return pendingCapacityBytes;
    }

    synchronized void recordReleased() {
        actualCapacityBytes = 0;
    }
}
