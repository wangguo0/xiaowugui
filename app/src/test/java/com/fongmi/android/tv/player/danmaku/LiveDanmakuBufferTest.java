package com.fongmi.android.tv.player.danmaku;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class LiveDanmakuBufferTest {

    @Test
    public void rejectsMessagesFromOldGeneration() {
        LiveDanmakuBuffer buffer = new LiveDanmakuBuffer(2, 1);
        buffer.reset(2L);

        assertEquals(LiveDanmakuBuffer.OfferResult.STALE, buffer.offer(message(1L, "old", LiveDanmakuMessage.Type.NORMAL)));
        assertEquals(0, buffer.size());
    }

    @Test
    public void dropsOldestNormalMessageWhenCapacityIsReached() {
        LiveDanmakuBuffer buffer = new LiveDanmakuBuffer(2, 1);
        buffer.reset(1L);
        buffer.offer(message(1L, "one", LiveDanmakuMessage.Type.NORMAL));
        buffer.offer(message(1L, "two", LiveDanmakuMessage.Type.NORMAL));

        assertEquals(LiveDanmakuBuffer.OfferResult.DROPPED_OLDEST, buffer.offer(message(1L, "three", LiveDanmakuMessage.Type.NORMAL)));
        assertEquals(List.of("two", "three"), buffer.drain(10).stream().map(LiveDanmakuMessage::text).toList());
    }

    @Test
    public void drainsPriorityBeforeNormalWithoutMixingCapacities() {
        LiveDanmakuBuffer buffer = new LiveDanmakuBuffer(2, 1);
        buffer.reset(1L);
        buffer.offer(message(1L, "normal", LiveDanmakuMessage.Type.NORMAL));
        buffer.offer(message(1L, "priority", LiveDanmakuMessage.Type.SUPER_CHAT));

        assertEquals(List.of("priority", "normal"), buffer.drain(2).stream().map(LiveDanmakuMessage::text).toList());
        assertEquals(0, buffer.size());
    }

    @Test
    public void tracksOnlineSeparatelyAndClearsOnGenerationReset() {
        LiveDanmakuBuffer buffer = new LiveDanmakuBuffer(2, 1);
        buffer.reset(4L);

        assertTrue(buffer.updateOnline(4L, 888L));
        assertFalse(buffer.updateOnline(3L, 999L));
        assertEquals(888L, buffer.latestOnline());
        buffer.reset(5L);
        assertEquals(-1L, buffer.latestOnline());
    }

    private static LiveDanmakuMessage message(long generation, String text, LiveDanmakuMessage.Type type) {
        return new LiveDanmakuMessage(type, text, 0xFFFFFFFF, 1L, generation);
    }
}
