package com.fongmi.android.tv.player.exo;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;

final class ObservedMediaBitrateEstimator {

    private static final long MIN_SAMPLE_SPAN_MS = 10_000;
    private static final long MIN_LOAD_SAMPLE_SPAN_MS = 1_000;
    private static final long HIGH_CONFIDENCE_SPAN_MS = 30_000;
    private static final long MAX_SAMPLE_SPAN_MS = 60_000;
    private static final long MIN_VALID_BITRATE = 64_000;
    private static final long MAX_VALID_BITRATE = 1_000_000_000L;
    private static final long MIN_WHOLE_FILE_LENGTH_BYTES = 64L * 1024L * 1024L;
    private static final int MAX_RATE_SAMPLES = 24;

    private final Deque<PositionSample> positions = new ArrayDeque<>();
    private final List<Long> rateSamples = new ArrayList<>();
    private long formatBitrate;
    private boolean videoFormatPresent;
    private long contentLengthBytes;
    private long durationMs = C.TIME_UNSET;
    private long lastRateMediaPositionMs = C.TIME_UNSET;
    private long sequence = Long.MIN_VALUE;

    synchronized void reset() {
        formatBitrate = 0;
        videoFormatPresent = false;
        contentLengthBytes = 0;
        durationMs = C.TIME_UNSET;
        invalidateObserved(Long.MIN_VALUE);
    }

    synchronized void updateFormats(@Nullable Format video, @Nullable Format audio) {
        videoFormatPresent = video != null;
        long videoBitrate = ExoPlaybackDiagnostics.formatBitrate(video);
        formatBitrate = videoFormatPresent && videoBitrate <= 0 ? 0 : ExoPlaybackDiagnostics.combinedBitrate(video, audio);
    }

    synchronized void updateContent(long contentLengthBytes, long durationMs) {
        if (contentLengthBytes > this.contentLengthBytes) this.contentLengthBytes = contentLengthBytes;
        if (durationMs > 0 && durationMs != C.TIME_UNSET) this.durationMs = durationMs;
    }

    synchronized void observeLoad(long bytesLoaded, long mediaStartTimeMs, long mediaEndTimeMs) {
        if (bytesLoaded <= 0 || mediaStartTimeMs == C.TIME_UNSET || mediaEndTimeMs == C.TIME_UNSET) return;
        long spanMs = mediaEndTimeMs - mediaStartTimeMs;
        if (spanMs < MIN_LOAD_SAMPLE_SPAN_MS || spanMs > MAX_SAMPLE_SPAN_MS) return;
        addRate(rateFor(bytesLoaded, spanMs));
    }

    synchronized void observeBytePosition(long nowMs, long mediaPositionMs, PlaybackBytePositionDataSource.Snapshot bytePosition, boolean stable) {
        if (!stable || bytePosition == null || bytePosition.positionBytes() < 0 || mediaPositionMs < 0 || mediaPositionMs == C.TIME_UNSET) {
            invalidateObserved(bytePosition == null ? Long.MIN_VALUE : bytePosition.sequence());
            return;
        }
        updateContent(bytePosition.contentLengthBytes(), durationMs);
        if (sequence != bytePosition.sequence()) invalidateObserved(bytePosition.sequence());
        PositionSample latest = positions.peekLast();
        if (latest != null && (mediaPositionMs < latest.mediaPositionMs() || bytePosition.positionBytes() < latest.bytePositionBytes())) {
            invalidateObserved(bytePosition.sequence());
            latest = null;
        }
        if (latest != null && nowMs - latest.nowMs() < 1_000 && mediaPositionMs - latest.mediaPositionMs() < 1_000) return;
        positions.addLast(new PositionSample(nowMs, mediaPositionMs, bytePosition.positionBytes()));
        trimPositions(nowMs, mediaPositionMs);
        recordWindowRate(mediaPositionMs);
    }

    synchronized void disrupt() {
        invalidateObserved(sequence);
    }

    synchronized Estimate estimate() {
        return estimate(true);
    }

    synchronized Estimate estimateWithoutFormat() {
        return estimate(false);
    }

    private Estimate estimate(boolean includeFormat) {
        long p50 = percentile(rateSamples, 50);
        long p90 = percentile(rateSamples, 90);
        long observed = conservativeObserved(p50, p90);
        long content = contentBitrate();
        long windowMs = positionWindowMs();
        if (includeFormat && formatBitrate > 0) return new Estimate(formatBitrate, Source.FORMAT, Confidence.HIGH, p50, p90, rateSamples.size(), windowMs, contentLengthBytes, durationMs);
        if (content > 0 && observed > 0) {
            long bitrate = Math.max(content, observed);
            Confidence confidence = windowMs >= HIGH_CONFIDENCE_SPAN_MS && rateSamples.size() >= 3 ? Confidence.HIGH : Confidence.MEDIUM;
            return new Estimate(bitrate, Source.HYBRID, confidence, p50, p90, rateSamples.size(), windowMs, contentLengthBytes, durationMs);
        }
        if (content > 0) return new Estimate(content, Source.CONTENT_LENGTH, Confidence.HIGH, p50, p90, rateSamples.size(), windowMs, contentLengthBytes, durationMs);
        if (observed > 0) {
            Confidence confidence = windowMs >= HIGH_CONFIDENCE_SPAN_MS && rateSamples.size() >= 3 ? Confidence.HIGH : rateSamples.size() >= 2 ? Confidence.MEDIUM : Confidence.LOW;
            return new Estimate(observed, Source.BYTE_SLOPE, confidence, p50, p90, rateSamples.size(), windowMs, contentLengthBytes, durationMs);
        }
        return Estimate.unknown();
    }

    private void recordWindowRate(long mediaPositionMs) {
        if (lastRateMediaPositionMs != C.TIME_UNSET && mediaPositionMs - lastRateMediaPositionMs < 5_000) return;
        PositionSample newest = positions.peekLast();
        if (newest == null) return;
        PositionSample oldest = null;
        for (PositionSample sample : positions) {
            long spanMs = newest.mediaPositionMs() - sample.mediaPositionMs();
            if (spanMs >= MIN_SAMPLE_SPAN_MS && spanMs <= MAX_SAMPLE_SPAN_MS) {
                oldest = sample;
                break;
            }
        }
        if (oldest == null) return;
        long byteDelta = newest.bytePositionBytes() - oldest.bytePositionBytes();
        long mediaDelta = newest.mediaPositionMs() - oldest.mediaPositionMs();
        addRate(rateFor(byteDelta, mediaDelta));
        lastRateMediaPositionMs = mediaPositionMs;
    }

    private void addRate(long rate) {
        if (rate < MIN_VALID_BITRATE || rate > MAX_VALID_BITRATE) return;
        rateSamples.add(rate);
        if (rateSamples.size() > MAX_RATE_SAMPLES) rateSamples.remove(0);
    }

    private void trimPositions(long nowMs, long mediaPositionMs) {
        while (positions.size() > 2) {
            PositionSample first = positions.peekFirst();
            if (first == null) return;
            if (nowMs - first.nowMs() <= MAX_SAMPLE_SPAN_MS && mediaPositionMs - first.mediaPositionMs() <= MAX_SAMPLE_SPAN_MS) return;
            positions.removeFirst();
        }
    }

    private void resetPositionWindow(long sequence) {
        positions.clear();
        lastRateMediaPositionMs = C.TIME_UNSET;
        this.sequence = sequence;
    }

    private void invalidateObserved(long sequence) {
        resetPositionWindow(sequence);
        rateSamples.clear();
    }

    private long contentBitrate() {
        if (contentLengthBytes < MIN_WHOLE_FILE_LENGTH_BYTES || durationMs <= 0 || durationMs == C.TIME_UNSET) return 0;
        return rateFor(contentLengthBytes, durationMs);
    }

    private long positionWindowMs() {
        PositionSample first = positions.peekFirst();
        PositionSample last = positions.peekLast();
        return first == null || last == null ? 0 : Math.max(0, last.mediaPositionMs() - first.mediaPositionMs());
    }

    private static long conservativeObserved(long p50, long p90) {
        long medianHeadroom = p50 <= 0 ? 0 : p50 > Long.MAX_VALUE / 5 ? Long.MAX_VALUE : p50 * 5 / 4;
        return Math.max(p90, medianHeadroom);
    }

    private static long percentile(List<Long> values, int percentile) {
        if (values.isEmpty()) return 0;
        List<Long> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int index = (int) Math.ceil(percentile / 100.0 * sorted.size()) - 1;
        return sorted.get(Math.clamp(index, 0, sorted.size() - 1));
    }

    private static long rateFor(long bytes, long durationMs) {
        if (bytes <= 0 || durationMs <= 0) return 0;
        if (bytes > Long.MAX_VALUE / 8_000L) return Long.MAX_VALUE;
        return bytes * 8_000L / durationMs;
    }

    enum Source {
        FORMAT("format"),
        CONTENT_LENGTH("content-length"),
        HYBRID("hybrid"),
        BYTE_SLOPE("byte-slope"),
        UNKNOWN("unknown");

        private final String label;

        Source(String label) {
            this.label = label;
        }

        String label() {
            return label;
        }
    }

    enum Confidence {
        HIGH("high"),
        MEDIUM("medium"),
        LOW("low"),
        UNKNOWN("unknown");

        private final String label;

        Confidence(String label) {
            this.label = label;
        }

        String label() {
            return label;
        }
    }

    record Estimate(long bitrateBitsPerSecond, Source source, Confidence confidence, long p50BitsPerSecond, long p90BitsPerSecond, int windowCount, long windowDurationMs, long contentLengthBytes, long durationMs) {

        static Estimate unknown() {
            return new Estimate(0, Source.UNKNOWN, Confidence.UNKNOWN, 0, 0, 0, 0, 0, C.TIME_UNSET);
        }

        boolean reliable() {
            return bitrateBitsPerSecond > 0 && confidence != Confidence.LOW && confidence != Confidence.UNKNOWN;
        }
    }

    private record PositionSample(long nowMs, long mediaPositionMs, long bytePositionBytes) {
    }
}
