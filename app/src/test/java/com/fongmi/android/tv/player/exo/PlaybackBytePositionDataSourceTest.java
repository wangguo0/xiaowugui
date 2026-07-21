package com.fongmi.android.tv.player.exo;

import org.junit.Test;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;

public class PlaybackBytePositionDataSourceTest {

    @Test
    public void contentRangeExposesWholeFileLength() {
        Map<String, List<String>> headers = Map.of("content-range", List.of("bytes 1048576-2097151/60000000000"));

        assertEquals(60_000_000_000L, PlaybackBytePositionDataSource.parseContentRangeTotal(headers));
    }

    @Test
    public void invalidOrUnknownContentRangeIsIgnored() {
        assertEquals(0, PlaybackBytePositionDataSource.parseContentRangeTotal(Map.of("Content-Range", List.of("bytes */*"))));
        assertEquals(0, PlaybackBytePositionDataSource.parseContentRangeTotal(Map.of("Content-Length", List.of("123"))));
    }
}
