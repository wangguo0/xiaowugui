package com.fongmi.android.tv.player.danmaku;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

public final class LiveDanmakuBuffer {

    public static final int DEFAULT_NORMAL_CAPACITY = 128;
    public static final int DEFAULT_PRIORITY_CAPACITY = 16;

    private final ArrayDeque<LiveDanmakuMessage> normal;
    private final ArrayDeque<LiveDanmakuMessage> priority;
    private final int normalCapacity;
    private final int priorityCapacity;
    private long generation = -1L;
    private long latestOnline = -1L;

    public LiveDanmakuBuffer() {
        this(DEFAULT_NORMAL_CAPACITY, DEFAULT_PRIORITY_CAPACITY);
    }

    LiveDanmakuBuffer(int normalCapacity, int priorityCapacity) {
        this.normalCapacity = Math.max(1, normalCapacity);
        this.priorityCapacity = Math.max(1, priorityCapacity);
        this.normal = new ArrayDeque<>(this.normalCapacity);
        this.priority = new ArrayDeque<>(this.priorityCapacity);
    }

    public synchronized void reset(long generation) {
        this.generation = generation;
        latestOnline = -1L;
        normal.clear();
        priority.clear();
    }

    public synchronized void clear() {
        generation = -1L;
        latestOnline = -1L;
        normal.clear();
        priority.clear();
    }

    public synchronized OfferResult offer(LiveDanmakuMessage message) {
        if (message == null || message.generation() != generation) return OfferResult.STALE;
        boolean highPriority = message.type() == LiveDanmakuMessage.Type.SUPER_CHAT;
        ArrayDeque<LiveDanmakuMessage> queue = highPriority ? priority : normal;
        int capacity = highPriority ? priorityCapacity : normalCapacity;
        boolean dropped = queue.size() >= capacity;
        if (dropped) queue.pollFirst();
        queue.offerLast(message);
        return dropped ? OfferResult.DROPPED_OLDEST : OfferResult.QUEUED;
    }

    public synchronized boolean updateOnline(long generation, long online) {
        if (generation != this.generation || online < 0) return false;
        latestOnline = online;
        return true;
    }

    public synchronized List<LiveDanmakuMessage> drain(int maxItems) {
        int limit = Math.max(0, maxItems);
        if (limit == 0 || (priority.isEmpty() && normal.isEmpty())) return List.of();
        List<LiveDanmakuMessage> result = new ArrayList<>(Math.min(limit, priority.size() + normal.size()));
        while (result.size() < limit && !priority.isEmpty()) result.add(priority.removeFirst());
        while (result.size() < limit && !normal.isEmpty()) result.add(normal.removeFirst());
        return result;
    }

    public synchronized int size() {
        return normal.size() + priority.size();
    }

    public synchronized int normalSize() {
        return normal.size();
    }

    public synchronized int prioritySize() {
        return priority.size();
    }

    public synchronized long latestOnline() {
        return latestOnline;
    }

    public enum OfferResult {
        QUEUED,
        DROPPED_OLDEST,
        STALE
    }
}
