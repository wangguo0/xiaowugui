package com.fongmi.android.tv.player.exo;

import androidx.media3.common.Format;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ObservedMediaBitrateEstimatorTest {

    @Test
    public void formatBitrateHasHighestPriority() {
        ObservedMediaBitrateEstimator estimator = new ObservedMediaBitrateEstimator();
        estimator.updateFormats(new Format.Builder().setAverageBitrate(80_000_000).build(), new Format.Builder().setAverageBitrate(1_500_000).build());
        estimator.updateContent(mib(10_000), 2_000_000);

        ObservedMediaBitrateEstimator.Estimate estimate = estimator.estimate();
        assertEquals(81_500_000, estimate.bitrateBitsPerSecond());
        assertEquals(ObservedMediaBitrateEstimator.Source.FORMAT, estimate.source());
        assertEquals(ObservedMediaBitrateEstimator.Confidence.HIGH, estimate.confidence());
    }

    @Test
    public void contentLengthAndDurationProvideHighConfidenceAverage() {
        ObservedMediaBitrateEstimator estimator = new ObservedMediaBitrateEstimator();
        estimator.updateContent(60_000_000_000L, 6_000_000);

        ObservedMediaBitrateEstimator.Estimate estimate = estimator.estimate();
        assertEquals(80_000_000, estimate.bitrateBitsPerSecond());
        assertEquals(ObservedMediaBitrateEstimator.Source.CONTENT_LENGTH, estimate.source());
        assertEquals(ObservedMediaBitrateEstimator.Confidence.HIGH, estimate.confidence());
    }

    @Test
    public void smallSegmentLengthIsNotTreatedAsWholeMovie() {
        ObservedMediaBitrateEstimator estimator = new ObservedMediaBitrateEstimator();
        estimator.updateContent(8_000_000, 6_000_000);

        assertEquals(ObservedMediaBitrateEstimator.Source.UNKNOWN, estimator.estimate().source());
    }

    @Test
    public void byteSlopeUsesConservativePercentile() {
        ObservedMediaBitrateEstimator estimator = new ObservedMediaBitrateEstimator();
        long sequence = 7;
        estimator.observeBytePosition(0, 0, snapshot(sequence, 0), true);
        estimator.observeBytePosition(10_000, 10_000, snapshot(sequence, 100_000_000), true);
        estimator.observeBytePosition(20_000, 20_000, snapshot(sequence, 225_000_000), true);
        estimator.observeBytePosition(30_000, 30_000, snapshot(sequence, 375_000_000), true);

        ObservedMediaBitrateEstimator.Estimate estimate = estimator.estimate();
        assertEquals(ObservedMediaBitrateEstimator.Source.BYTE_SLOPE, estimate.source());
        assertTrue(estimate.bitrateBitsPerSecond() >= 100_000_000);
        assertTrue(estimate.p90BitsPerSecond() >= estimate.p50BitsPerSecond());
        assertTrue(estimate.windowCount() >= 3);
    }

    @Test
    public void seekOrSequenceChangeInvalidatesSlopeWindow() {
        ObservedMediaBitrateEstimator estimator = new ObservedMediaBitrateEstimator();
        estimator.observeBytePosition(0, 0, snapshot(1, 0), true);
        estimator.observeBytePosition(10_000, 10_000, snapshot(1, 100_000_000), true);
        estimator.observeBytePosition(11_000, 2_000, snapshot(2, 10_000_000), true);

        assertEquals(0, estimator.estimate().windowDurationMs());
    }

    @Test
    public void unstableOrZeroDeltaSamplesDoNotCreateEstimate() {
        ObservedMediaBitrateEstimator estimator = new ObservedMediaBitrateEstimator();
        estimator.observeBytePosition(0, 0, snapshot(1, 0), true);
        estimator.observeBytePosition(20_000, 20_000, snapshot(1, 0), true);
        estimator.observeBytePosition(30_000, 30_000, snapshot(1, 100_000_000), false);

        assertEquals(ObservedMediaBitrateEstimator.Source.UNKNOWN, estimator.estimate().source());
    }

    private static PlaybackBytePositionDataSource.Snapshot snapshot(long sequence, long position) {
        return new PlaybackBytePositionDataSource.Snapshot(sequence, position, 0);
    }

    private static long mib(long value) {
        return value * 1024L * 1024L;
    }
}
