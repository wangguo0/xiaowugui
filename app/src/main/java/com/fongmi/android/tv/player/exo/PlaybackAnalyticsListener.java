package com.fongmi.android.tv.player.exo;

import androidx.annotation.Nullable;
import androidx.media3.common.Format;
import androidx.media3.common.Player;
import androidx.media3.common.VideoSize;
import androidx.media3.exoplayer.DecoderReuseEvaluation;
import androidx.media3.exoplayer.analytics.AnalyticsListener;
import androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime;

import com.github.catvod.crawler.SpiderDebug;

public class PlaybackAnalyticsListener implements AnalyticsListener {

    private long totalDroppedFrames;

    @Override
    public void onPlaybackStateChanged(EventTime eventTime, @Player.State int state) {
        if (!SpiderDebug.isEnabled()) return;
        SpiderDebug.log("playback-metrics", "state=%s position=%d buffered=%d", stateName(state), eventTime.currentPlaybackPositionMs, eventTime.totalBufferedDurationMs);
    }

    @Override
    public void onVideoDecoderInitialized(EventTime eventTime, String decoderName, long initializedTimestampMs, long initializationDurationMs) {
        if (!SpiderDebug.isEnabled()) return;
        SpiderDebug.log("playback-metrics", "video decoder=%s init=%dms", decoderName, initializationDurationMs);
    }

    @Override
    public void onVideoInputFormatChanged(EventTime eventTime, Format format, @Nullable DecoderReuseEvaluation decoderReuseEvaluation) {
        if (!SpiderDebug.isEnabled()) return;
        SpiderDebug.log("playback-metrics", "video format mime=%s codecs=%s size=%dx%d fps=%.3f bitrate=%d color=%s", format.sampleMimeType, format.codecs, format.width, format.height, format.frameRate, format.bitrate, format.colorInfo);
    }

    @Override
    public void onVideoSizeChanged(EventTime eventTime, VideoSize videoSize) {
        if (!SpiderDebug.isEnabled()) return;
        SpiderDebug.log("playback-metrics", "video size=%dx%d unappliedRotation=%d ratio=%.3f", videoSize.width, videoSize.height, videoSize.unappliedRotationDegrees, videoSize.pixelWidthHeightRatio);
    }

    @Override
    public void onDroppedVideoFrames(EventTime eventTime, int droppedFrames, long elapsedMs) {
        if (!SpiderDebug.isEnabled()) return;
        totalDroppedFrames += droppedFrames;
        SpiderDebug.log("playback-metrics", "droppedFrames=%d total=%d elapsed=%dms position=%d", droppedFrames, totalDroppedFrames, elapsedMs, eventTime.currentPlaybackPositionMs);
    }

    private static String stateName(int state) {
        return switch (state) {
            case Player.STATE_IDLE -> "IDLE";
            case Player.STATE_BUFFERING -> "BUFFERING";
            case Player.STATE_READY -> "READY";
            case Player.STATE_ENDED -> "ENDED";
            default -> String.valueOf(state);
        };
    }
}
