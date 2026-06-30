package com.fongmi.android.tv.ui.custom;

import android.net.Uri;
import android.net.TrafficStats;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.View;
import android.widget.TextView;

import androidx.media3.common.C;
import androidx.media3.common.Format;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.player.PlayerManager;
import com.fongmi.android.tv.player.exo.PlaybackAnalyticsListener;
import com.fongmi.android.tv.setting.PlayerSetting;
import com.fongmi.android.tv.utils.Util;

import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class PlayerOsdController {

    public interface Source {
        PlayerManager getPlayer();

        String getTitle();
    }

    private static final DecimalFormat SPEED_FORMAT = new DecimalFormat("#.0");
    private static final int UID = App.get().getApplicationInfo().uid;

    private final SimpleDateFormat timeFormat;
    private final TextView topLeft;
    private final TextView topRight;
    private final TextView bottomLeft;
    private final TextView bottomRight;
    private final TextView diagnostics;
    private final MiniProgressView miniProgress;
    private final Runnable update;
    private final Source source;
    private final View root;
    private final float miniSp;

    private final DecimalFormat frameFormat;
    private final DecimalFormat refreshFormat;
    private final DecimalFormat bitrateFormat;
    private long lastTotalRxBytes;
    private long lastTimeStamp;
    private long lastSpeedKBps;
    private String lastSpeedText;
    private boolean controlsVisible;
    private boolean diagnosticsVisible;
    private boolean started;

    public PlayerOsdController(View root, TextView topLeft, TextView topRight, TextView bottomLeft, TextView bottomRight, TextView diagnostics, MiniProgressView miniProgress, Source source, float miniSp) {
        this.timeFormat = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
        this.bitrateFormat = new DecimalFormat("#.0");
        this.refreshFormat = new DecimalFormat("#.##");
        this.frameFormat = new DecimalFormat("#.###");
        this.miniProgress = miniProgress;
        this.bottomRight = bottomRight;
        this.bottomLeft = bottomLeft;
        this.diagnostics = diagnostics;
        this.topRight = topRight;
        this.topLeft = topLeft;
        this.miniSp = miniSp;
        this.source = source;
        this.root = root;
        this.update = this::update;
    }

    public void start() {
        started = true;
        if (!PlayerSetting.isOsdEnabled()) {
            root.setVisibility(View.GONE);
            return;
        }
        resetSpeed();
        App.post(update, 0);
    }

    public void stop() {
        started = false;
        App.removeCallbacks(update);
    }

    public void release() {
        stop();
    }

    public void setControlsVisible(boolean controlsVisible) {
        if (this.controlsVisible == controlsVisible) return;
        this.controlsVisible = controlsVisible;
        if (started) render();
    }

    public boolean isDiagnosticsVisible() {
        return diagnosticsVisible;
    }

    public void setDiagnosticsVisible(boolean visible) {
        boolean next = visible && PlayerSetting.isOsdDiagnostics();
        if (diagnosticsVisible == next) return;
        diagnosticsVisible = next;
        if (started) render();
    }

    public void toggleDiagnostics() {
        if (!PlayerSetting.isOsdDiagnostics()) return;
        diagnosticsVisible = !diagnosticsVisible;
        if (started) render();
    }

    private void update() {
        if (render()) App.post(update, 1000);
    }

    private boolean render() {
        boolean enabled = PlayerSetting.isOsdEnabled();
        if (!enabled) {
            root.setVisibility(View.GONE);
            return false;
        }
        root.setVisibility(controlsVisible ? View.GONE : View.VISIBLE);
        if (controlsVisible) return true;
        setTextSize(miniSp);
        PlayerManager player = source.getPlayer();
        updateSpeed();
        setTopLeft(player);
        setTopRight();
        setBottomLeft(player);
        setBottomRight();
        setDiagnosticsPanel(player);
        setMiniProgress(player);
        return true;
    }

    private void setTopLeft(PlayerManager player) {
        if ((!PlayerSetting.isOsdTitle() && !PlayerSetting.isOsdResolution()) || diagnosticsVisible) {
            topLeft.setVisibility(View.GONE);
            return;
        }
        String title = PlayerSetting.isOsdTitle() ? source.getTitle() : "";
        String size = PlayerSetting.isOsdResolution() && player != null ? player.getSizeText() : "";
        topLeft.setText(join("\n", title, size));
        topLeft.setVisibility(TextUtils.isEmpty(topLeft.getText()) ? View.GONE : View.VISIBLE);
    }

    private void setTopRight() {
        topRight.setVisibility(PlayerSetting.isOsdTime() ? View.VISIBLE : View.GONE);
        if (PlayerSetting.isOsdTime()) topRight.setText(timeFormat.format(new Date()));
    }

    private void setBottomLeft(PlayerManager player) {
        if (controlsVisible || !PlayerSetting.isOsdProgress() || player == null || player.isLive()) {
            bottomLeft.setVisibility(View.GONE);
            return;
        }
        long position = Math.max(0, player.getPosition());
        long duration = Math.max(0, player.getDuration());
        if (duration <= 0) {
            bottomLeft.setVisibility(View.GONE);
            return;
        }
        bottomLeft.setText(Util.timeMs(position) + " / " + Util.timeMs(duration));
        bottomLeft.setVisibility(View.VISIBLE);
    }

    private void setBottomRight() {
        bottomRight.setVisibility(PlayerSetting.isOsdTraffic() ? View.VISIBLE : View.GONE);
        if (!PlayerSetting.isOsdTraffic()) return;
        bottomRight.setText(lastSpeedText);
        bottomRight.setVisibility(TextUtils.isEmpty(lastSpeedText) ? View.GONE : View.VISIBLE);
    }

    private void setDiagnosticsPanel(PlayerManager player) {
        if (controlsVisible || !PlayerSetting.isOsdDiagnostics() || !diagnosticsVisible || player == null) {
            diagnostics.setVisibility(View.GONE);
            return;
        }
        String text = getDiagnostics(player);
        diagnostics.setText(text);
        diagnostics.setVisibility(TextUtils.isEmpty(text) ? View.GONE : View.VISIBLE);
    }

    private void setMiniProgress(PlayerManager player) {
        if (controlsVisible || !PlayerSetting.isOsdMini() || player == null || player.isLive()) {
            miniProgress.setVisibility(View.GONE);
            return;
        }
        long duration = Math.max(0, player.getDuration());
        if (duration <= 0) {
            miniProgress.setVisibility(View.GONE);
            return;
        }
        miniProgress.setProgress(player.getPosition(), duration);
        miniProgress.setVisibility(View.VISIBLE);
    }

    private void setTextSize(float sp) {
        topLeft.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        topRight.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        bottomLeft.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        bottomRight.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        diagnostics.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
    }

    private void updateSpeed() {
        long total = TrafficStats.getUidRxBytes(UID);
        if (total == TrafficStats.UNSUPPORTED) {
            lastSpeedKBps = 0;
            lastSpeedText = "";
            return;
        }
        long now = System.currentTimeMillis();
        long rxKb = total / 1024;
        long speed = (rxKb - lastTotalRxBytes) * 1000 / Math.max(now - lastTimeStamp, 1);
        lastTimeStamp = now;
        lastTotalRxBytes = rxKb;
        lastSpeedKBps = Math.max(0, speed);
        lastSpeedText = formatSpeed(lastSpeedKBps);
    }

    private String getDiagnostics(PlayerManager player) {
        PlaybackAnalyticsListener.Snapshot snapshot = player.isIjk() ? PlaybackAnalyticsListener.Snapshot.empty() : PlaybackAnalyticsListener.getSnapshot();
        Format video = snapshot.videoFormat() != null ? snapshot.videoFormat() : player.getVideoFormat();
        Format audio = snapshot.audioFormat();
        String state = stateName(player.getPlaybackState()) + (player.isLoading() ? " loading" : "");
        String buffer = join(" / ", formatDuration(player.getBufferedDuration()), player.getBufferedPercentage() > 0 ? player.getBufferedPercentage() + "%" : "");
        String rebuffer = snapshot.rebufferCount() <= 0 ? "0" : snapshot.rebufferCount() + "x / " + formatDuration(snapshot.rebufferTotalMs());
        String network = join(" / ", "app " + emptyDash(lastSpeedText), "est " + emptyDash(formatBitrate(snapshot.bandwidthEstimate())), snapshot.lastLoadBytes() > 0 ? formatBytes(snapshot.lastLoadBytes()) + " in " + snapshot.lastLoadTimeMs() + " ms" : "");
        String videoText = summarizeVideo(video, player, snapshot.videoDecoderName());
        String audioText = summarizeAudio(audio, player, snapshot.audioDecoderName());
        String render = PlayerSetting.getRender() == PlayerSetting.RENDER_SURFACE ? "Surface" : "Texture";
        String tunnel = PlayerSetting.isTunnelingEnabled() ? "on" : "off";
        String enhance = PlayerSetting.isExoEnhanced() ? "on" : "off";
        String playerText = join(" / ", player.getPlayerText(), player.getDecodeText(), render, "Tunnel " + tunnel, "EXO+ " + enhance, player.isIjk() ? "" : "FFmpeg audio on");
        String error = join(" ", snapshot.errorCode(), shortText(snapshot.errorMessage(), 72));
        String display = getDisplayRefreshText();
        return join("\n",
                row("Judge", getDiagnosis(player, snapshot, video)),
                row("Network", network),
                row("Buffer", join(" / ", state, buffer, "rebuffer " + rebuffer)),
                row("Video", videoText),
                row("Audio", audioText),
                row("Drops", String.valueOf(snapshot.droppedFrames())),
                row("Player", playerText),
                row("Source", summarizeSource(player.getUrl())),
                row("Display", TextUtils.isEmpty(display) ? "-" : display),
                TextUtils.isEmpty(error) ? "" : row("Error", error));
    }

    private String getDiagnosis(PlayerManager player, PlaybackAnalyticsListener.Snapshot snapshot, Format video) {
        if (!TextUtils.isEmpty(snapshot.errorCode())) return "player error";
        if (player.haveTrack(C.TRACK_TYPE_AUDIO) && TextUtils.isEmpty(snapshot.audioDecoderName()) && player.getPlaybackState() == androidx.media3.common.Player.STATE_READY) return "audio decoder not ready";
        long mediaBitrate = getMediaBitrate(video, snapshot.audioFormat());
        long availableBitrate = snapshot.bandwidthEstimate() > 0 ? snapshot.bandwidthEstimate() : lastSpeedKBps * 1024 * 8;
        if (availableBitrate > 0 && mediaBitrate > 0 && availableBitrate < mediaBitrate * 13 / 10) return "network may be below media bitrate";
        if (player.isLoading() && player.getBufferedDuration() < 3000) return "buffer is low";
        if (snapshot.droppedFrames() >= 60) return "decoder/render dropped frames";
        if (video != null && video.bitrate >= 30_000_000) return "high bitrate source";
        if (player.haveTrack(C.TRACK_TYPE_AUDIO) && snapshot.audioFormat() == null) return "waiting audio track info";
        return "normal";
    }

    private String summarizeVideo(Format format, PlayerManager player, String decoder) {
        String size = getSize(format, player);
        String fps = getFrameRate(format);
        String bitrate = getBitrate(format);
        return join(" ", getMime(format), size, TextUtils.isEmpty(fps) ? "" : "@" + fps, bitrate, TextUtils.isEmpty(decoder) ? "" : "dec " + decoder);
    }

    private String summarizeAudio(Format format, PlayerManager player, String decoder) {
        if (format == null) return player.haveTrack(C.TRACK_TYPE_AUDIO) ? "track detected / waiting decoder" : "no audio track";
        String channels = format.channelCount <= 0 ? "" : format.channelCount + "ch";
        String sampleRate = format.sampleRate <= 0 ? "" : format.sampleRate % 1000 == 0 ? (format.sampleRate / 1000) + "kHz" : bitrateFormat.format(format.sampleRate / 1000f) + "kHz";
        return join(" ", getMime(format), channels, sampleRate, getBitrate(format), TextUtils.isEmpty(format.language) ? "" : format.language, TextUtils.isEmpty(decoder) ? "" : "dec " + decoder);
    }

    private String getSize(Format format, PlayerManager player) {
        int width = format == null || format.width <= 0 ? player.getVideoWidth() : format.width;
        int height = format == null || format.height <= 0 ? player.getVideoHeight() : format.height;
        return width <= 0 || height <= 0 ? "" : width + "x" + height;
    }

    private String getFrameRate(Format format) {
        if (format == null || format.frameRate <= 0) return "";
        return frameFormat.format(format.frameRate) + "fps";
    }

    private String getBitrate(Format format) {
        return format == null ? "" : formatBitrate(format.bitrate);
    }

    private long getMediaBitrate(Format video, Format audio) {
        long bitrate = 0;
        if (video != null && video.bitrate > 0) bitrate += video.bitrate;
        if (audio != null && audio.bitrate > 0) bitrate += audio.bitrate;
        return bitrate;
    }

    private String getMime(Format format) {
        if (format == null) return "";
        if (!TextUtils.isEmpty(format.sampleMimeType)) {
            int index = format.sampleMimeType.indexOf('/');
            return index >= 0 && index + 1 < format.sampleMimeType.length() ? format.sampleMimeType.substring(index + 1) : format.sampleMimeType;
        }
        return TextUtils.isEmpty(format.codecs) ? "" : format.codecs;
    }

    private String formatBitrate(long bitrate) {
        if (bitrate <= 0) return "";
        float mbps = bitrate / 1_000_000f;
        if (mbps < 1) return Math.round(bitrate / 1000f) + "Kbps";
        return bitrateFormat.format(mbps) + "Mbps";
    }

    private String formatBytes(long bytes) {
        if (bytes <= 0) return "";
        float kb = bytes / 1024f;
        if (kb < 1024) return Math.round(kb) + "KB";
        return bitrateFormat.format(kb / 1024f) + "MB";
    }

    private String formatSpeed(long kbps) {
        return kbps < 1000 ? kbps + " KB/s" : SPEED_FORMAT.format(kbps / 1024f) + " MB/s";
    }

    private String formatDuration(long ms) {
        if (ms <= 0) return "";
        if (ms >= 60_000) return Util.timeMs(ms);
        return bitrateFormat.format(ms / 1000f) + " s";
    }

    private String getDisplayRefreshText() {
        if (root.getDisplay() == null || root.getDisplay().getRefreshRate() <= 0) return "";
        return refreshFormat.format(root.getDisplay().getRefreshRate()) + " Hz";
    }

    private String summarizeSource(String url) {
        if (TextUtils.isEmpty(url)) return "";
        try {
            Uri uri = Uri.parse(url);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            String path = uri.getPath();
            String type = sourceType(scheme, host, path, url);
            String ext = extension(path);
            return join(" ", type, TextUtils.isEmpty(host) ? emptyDash(scheme) : scheme + "://" + host, ext);
        } catch (Throwable ignored) {
            return shortText(url, 80);
        }
    }

    private String sourceType(String scheme, String host, String path, String url) {
        String lower = url.toLowerCase(Locale.US);
        if ("file".equals(scheme) || "content".equals(scheme)) return "local";
        if ("127.0.0.1".equals(host) || "localhost".equals(host)) return "local-proxy";
        if (lower.contains(".m3u8")) return "hls";
        if (lower.contains(".mpd")) return "dash";
        if (lower.startsWith("rtsp")) return "rtsp";
        if (lower.startsWith("rtp")) return "rtp";
        if (path != null && path.contains(".")) return "file";
        return TextUtils.isEmpty(scheme) ? "unknown" : scheme;
    }

    private String extension(String path) {
        if (TextUtils.isEmpty(path)) return "";
        int slash = path.lastIndexOf('/');
        int dot = path.lastIndexOf('.');
        if (dot <= slash || dot + 1 >= path.length()) return "";
        String ext = path.substring(dot + 1);
        return ext.length() > 8 ? "" : ext;
    }

    private String stateName(int state) {
        return switch (state) {
            case androidx.media3.common.Player.STATE_IDLE -> "IDLE";
            case androidx.media3.common.Player.STATE_BUFFERING -> "BUFFERING";
            case androidx.media3.common.Player.STATE_READY -> "READY";
            case androidx.media3.common.Player.STATE_ENDED -> "ENDED";
            default -> String.valueOf(state);
        };
    }

    private String join(String separator, String... values) {
        StringBuilder builder = new StringBuilder();
        for (String value : values) {
            if (TextUtils.isEmpty(value)) continue;
            if (builder.length() > 0) builder.append(separator);
            builder.append(value);
        }
        return builder.toString();
    }

    private String row(String label, String value) {
        return String.format(Locale.US, "%-8s %s", label, TextUtils.isEmpty(value) ? "-" : value);
    }

    private String emptyDash(String value) {
        return TextUtils.isEmpty(value) ? "-" : value;
    }

    private String shortText(String value, int max) {
        if (TextUtils.isEmpty(value) || value.length() <= max) return value;
        return value.substring(0, Math.max(0, max - 1)) + "...";
    }

    private void resetSpeed() {
        long total = TrafficStats.getUidRxBytes(UID);
        lastTotalRxBytes = total == TrafficStats.UNSUPPORTED ? 0 : total / 1024;
        lastTimeStamp = System.currentTimeMillis();
        lastSpeedKBps = 0;
        lastSpeedText = "";
    }
}
