package com.fongmi.android.tv.player.engine;

import com.fongmi.android.tv.player.PlaybackTrace;

import org.junit.Test;

import java.util.Map;

import static org.junit.Assert.assertEquals;

public class PlaySpecPlaybackTraceTest {

    @Test
    public void formatRetryCopyKeepsTheSamePlaybackTrace() {
        PlaySpec original = PlaySpec.from("key", "https://example.com/video", Map.of(), null);
        original.setPlaybackTraceId("p-abc-1");

        PlaySpec retry = original.copyWithFormat("application/x-mpegURL");

        assertEquals("p-abc-1", retry.getPlaybackTraceId());
    }

    @Test
    public void invalidTraceCannotEnterPlaySpec() {
        PlaySpec spec = PlaySpec.from("key", "https://example.com/video", Map.of(), null);

        spec.setPlaybackTraceId("https://example.com/?token=secret");

        assertEquals(PlaybackTrace.NONE, spec.getPlaybackTraceId());
    }
}
