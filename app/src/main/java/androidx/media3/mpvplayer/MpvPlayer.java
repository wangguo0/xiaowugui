package androidx.media3.mpvplayer;

import android.content.Context;
import android.content.res.AssetManager;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.text.TextUtils;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.TextureView;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Player;
import androidx.media3.common.SimpleBasePlayer;
import androidx.media3.common.TrackGroup;
import androidx.media3.common.Tracks;
import androidx.media3.common.VideoSize;
import androidx.media3.common.util.UnstableApi;

import com.github.catvod.crawler.SpiderDebug;
import com.google.common.collect.ImmutableList;
import com.google.common.net.HttpHeaders;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import is.xyz.mpv.MPVLib;

@UnstableApi
public final class MpvPlayer extends SimpleBasePlayer implements MPVLib.EventObserver, MPVLib.LogObserver {

    private static final String TAG = "TV-mpv";
    private static final long STATE_REFRESH_INTERVAL_MS = 1000;
    private static final long END_FILE_VALIDATION_DELAY_MS = 800;
    private static final long LOAD_START_RETRY_DELAY_MS = 1000;
    private static final int MAX_LOAD_START_RETRIES = 2;
    private static final double SECONDS_TO_MS = 1000.0;
    private static final String HLS_LOAD_OPTIONS = "demuxer=lavf,demuxer-lavf-format=hls,demuxer-lavf-probesize=10485760,demuxer-lavf-analyzeduration=5";
    private static final String HLS_PLAYBACK_FAILED_MESSAGE = "MPV_HLS_PLAYBACK_FAILED";
    private static final int RECENT_LOG_LIMIT = 32;
    private static final String HEADER_ACCEPT = "Accept";
    private static final String HEADER_ORIGIN = "Origin";

    private static final Commands COMMANDS = new Commands.Builder()
            .add(COMMAND_PLAY_PAUSE)
            .add(COMMAND_PREPARE)
            .add(COMMAND_STOP)
            .add(COMMAND_RELEASE)
            .add(COMMAND_SET_REPEAT_MODE)
            .add(COMMAND_GET_CURRENT_MEDIA_ITEM)
            .add(COMMAND_GET_TIMELINE)
            .add(COMMAND_GET_METADATA)
            .add(COMMAND_SET_MEDIA_ITEM)
            .add(COMMAND_CHANGE_MEDIA_ITEMS)
            .add(COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
            .add(COMMAND_SEEK_TO_DEFAULT_POSITION)
            .add(COMMAND_GET_VOLUME)
            .add(COMMAND_SET_VOLUME)
            .add(COMMAND_SET_SPEED_AND_PITCH)
            .add(COMMAND_SET_VIDEO_SURFACE)
            .add(COMMAND_GET_TRACKS)
            .build();

    private final Context context;
    private final MpvPlayerConfig config;
    private final Handler mainHandler;
    private final Runnable stateRefreshRunnable;
    private final Runnable endFileValidationRunnable;
    private final Runnable loadStartRetryRunnable;
    private final MpvHlsProxy hlsProxy;
    private final List<String> recentLogs;
    private final List<ParcelFileDescriptor> contentFds;
    private MediaItem mediaItem;
    private SurfaceHolder surfaceHolder;
    private Surface surface;
    private Object videoOutput;
    private String currentPlayableUri;
    private PlaybackParameters playbackParameters;
    private PlaybackException playerError;
    private Tracks currentTracks;
    private VideoSize videoSize;
    private int playbackState;
    private long pendingSeekPositionMs;
    private long cachedPositionMs;
    private long cachedDurationMs;
    private long cachedCacheDurationMs;
    private boolean playWhenReady;
    private boolean loading;
    private boolean repeatOne;
    private boolean ownsSurface;
    private boolean initialized;
    private boolean released;
    private boolean surfaceAttached;
    private boolean fileLoaded;
    private boolean loadStarted;
    private boolean playbackRestarted;
    private boolean stopping;
    private boolean eofReached;
    private boolean idleActive;
    private boolean currentLikelyHls;
    private boolean sawNoAvData;
    private boolean sawInvalidData;
    private boolean sawPngVideo;
    private int loadStartRetryCount;
    private String lastFailureLog;
    private float volume;

    public MpvPlayer(Context context, MpvPlayerConfig config) {
        super(Looper.getMainLooper());
        this.context = context.getApplicationContext();
        this.config = config;
        mainHandler = new Handler(Looper.getMainLooper());
        stateRefreshRunnable = this::refreshPlaybackState;
        endFileValidationRunnable = this::validateEarlyEndFile;
        loadStartRetryRunnable = this::retryLoadIfNotStarted;
        hlsProxy = new MpvHlsProxy();
        recentLogs = new ArrayList<>();
        contentFds = new ArrayList<>();
        playbackParameters = PlaybackParameters.DEFAULT;
        currentTracks = Tracks.EMPTY;
        videoSize = VideoSize.UNKNOWN;
        playbackState = Player.STATE_IDLE;
        pendingSeekPositionMs = C.TIME_UNSET;
        cachedDurationMs = C.TIME_UNSET;
        playWhenReady = true;
        volume = 1f;
    }

    @Override
    protected State getState() {
        int state = playbackState;
        State.Builder builder = new State.Builder()
                .setAvailableCommands(COMMANDS)
                .setPlayWhenReady(playWhenReady, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
                .setPlaybackState(state)
                .setIsLoading(loading && state != Player.STATE_IDLE && state != Player.STATE_ENDED)
                .setPlayerError(playerError)
                .setRepeatMode(repeatOne ? Player.REPEAT_MODE_ONE : Player.REPEAT_MODE_OFF)
                .setPlaybackParameters(playbackParameters)
                .setVideoSize(videoSize)
                .setVolume(volume)
                .setPlaylist(mediaItem == null ? ImmutableList.of() : ImmutableList.of(mediaItemData()))
                .setCurrentMediaItemIndex(mediaItem == null ? C.INDEX_UNSET : 0);
        if (mediaItem != null) {
            long duration = durationMs();
            long position = positionMs();
            PositionSupplier positionSupplier = isPlayingInternal()
                    ? PositionSupplier.getExtrapolating(position, playbackParameters.speed)
                    : PositionSupplier.getConstant(position);
            builder.setContentPositionMs(positionSupplier);
            builder.setContentBufferedPositionMs(PositionSupplier.getConstant(bufferedPositionMs(position, duration)));
            builder.setTotalBufferedDurationMs(PositionSupplier.getConstant(Math.max(0, bufferedPositionMs(position, duration) - position)));
        }
        return builder.build();
    }

    private MediaItemData mediaItemData() {
        long duration = durationMs();
        return new MediaItemData.Builder(mediaItem.mediaId)
                .setMediaItem(mediaItem)
                .setMediaMetadata(mediaItem.mediaMetadata)
                .setDurationUs(duration == C.TIME_UNSET ? C.TIME_UNSET : duration * 1000)
                .setIsSeekable(duration > 0)
                .setIsDynamic(duration == C.TIME_UNSET)
                .setTracks(currentTracks)
                .build();
    }

    @Override
    protected ListenableFuture<?> handleSetMediaItems(List<MediaItem> mediaItems, int startIndex, long startPositionMs) {
        mediaItem = mediaItems.isEmpty() ? null : mediaItems.get(0);
        pendingSeekPositionMs = mediaItem != null && startPositionMs > 0 ? startPositionMs : C.TIME_UNSET;
        cachedPositionMs = Math.max(0, startPositionMs == C.TIME_UNSET ? 0 : startPositionMs);
        cachedDurationMs = C.TIME_UNSET;
        cachedCacheDurationMs = 0;
        currentTracks = Tracks.EMPTY;
        playbackState = mediaItem == null ? Player.STATE_IDLE : Player.STATE_IDLE;
        loading = false;
        fileLoaded = false;
        playbackRestarted = false;
        loadStarted = false;
        loadStartRetryCount = 0;
        eofReached = false;
        idleActive = false;
        currentPlayableUri = null;
        currentLikelyHls = false;
        resetFailureSignals();
        recentLogs.clear();
        playerError = null;
        resetMpvContextForNewMedia();
        mainHandler.removeCallbacks(endFileValidationRunnable);
        mainHandler.removeCallbacks(loadStartRetryRunnable);
        closeContentFds();
        return Futures.immediateVoidFuture();
    }

    @Override
    protected ListenableFuture<?> handleAddMediaItems(int index, List<MediaItem> mediaItems) {
        if (mediaItem == null && !mediaItems.isEmpty()) mediaItem = mediaItems.get(0);
        return Futures.immediateVoidFuture();
    }

    @Override
    protected ListenableFuture<?> handleReplaceMediaItems(int fromIndex, int toIndex, List<MediaItem> mediaItems) {
        mediaItem = mediaItems.isEmpty() ? null : mediaItems.get(0);
        return Futures.immediateVoidFuture();
    }

    @Override
    protected ListenableFuture<?> handleRemoveMediaItems(int fromIndex, int toIndex) {
        mediaItem = null;
        stopInternal(true);
        return Futures.immediateVoidFuture();
    }

    @Override
    protected ListenableFuture<?> handlePrepare() {
        openCurrent();
        return Futures.immediateVoidFuture();
    }

    @Override
    protected ListenableFuture<?> handleSetPlayWhenReady(boolean playWhenReady) {
        this.playWhenReady = playWhenReady;
        if (initialized && playbackState != Player.STATE_IDLE && playbackState != Player.STATE_ENDED) {
            safeSetPropertyBoolean("pause", !playWhenReady);
        }
        invalidateState();
        return Futures.immediateVoidFuture();
    }

    @Override
    protected ListenableFuture<?> handleStop() {
        stopInternal(true);
        return Futures.immediateVoidFuture();
    }

    @Override
    protected ListenableFuture<?> handleRelease() {
        released = true;
        stopInternal(false);
        hlsProxy.release();
        clearVideoOutput();
        mainHandler.removeCallbacks(stateRefreshRunnable);
        mainHandler.removeCallbacks(endFileValidationRunnable);
        if (initialized) {
            try {
                MPVLib.removeObserver(this);
                MPVLib.removeLogObserver(this);
                MPVLib.destroy();
            } catch (Throwable ignored) {
            }
            initialized = false;
        }
        return Futures.immediateVoidFuture();
    }

    @Override
    protected ListenableFuture<?> handleSetRepeatMode(int repeatMode) {
        repeatOne = repeatMode == Player.REPEAT_MODE_ONE;
        if (initialized) safeSetPropertyString("loop-file", repeatOne ? "inf" : "no");
        invalidateState();
        return Futures.immediateVoidFuture();
    }

    @Override
    protected ListenableFuture<?> handleSeek(int mediaItemIndex, long positionMs, int seekCommand) {
        if (positionMs == C.TIME_UNSET) positionMs = 0;
        cachedPositionMs = Math.max(0, positionMs);
        pendingSeekPositionMs = cachedPositionMs;
        if (initialized && playbackState != Player.STATE_IDLE) {
            seekMpv(cachedPositionMs);
            if (playbackState == Player.STATE_ENDED) playbackState = Player.STATE_BUFFERING;
        }
        invalidateState();
        return Futures.immediateVoidFuture();
    }

    @Override
    protected ListenableFuture<?> handleSetPlaybackParameters(PlaybackParameters playbackParameters) {
        this.playbackParameters = playbackParameters;
        if (initialized) safeSetPropertyDouble("speed", playbackParameters.speed);
        invalidateState();
        return Futures.immediateVoidFuture();
    }

    @Override
    protected ListenableFuture<?> handleSetVolume(float volume, int volumeOperationType) {
        this.volume = Math.max(0f, Math.min(1f, volume));
        if (initialized) safeSetPropertyDouble("volume", this.volume * 100.0);
        invalidateState();
        return Futures.immediateVoidFuture();
    }

    @Override
    protected ListenableFuture<?> handleSetVideoOutput(Object videoOutput) {
        this.videoOutput = videoOutput;
        setVideoOutput(videoOutput);
        return Futures.immediateVoidFuture();
    }

    @Override
    protected ListenableFuture<?> handleClearVideoOutput(@Nullable Object videoOutput) {
        if (videoOutput == null || videoOutput == this.videoOutput) {
            this.videoOutput = null;
            clearVideoOutput();
        }
        return Futures.immediateVoidFuture();
    }

    @Override
    public void eventProperty(String property) {
        postToMain(() -> handleProperty(property, null));
    }

    @Override
    public void eventProperty(String property, long value) {
        postToMain(() -> handleProperty(property, value));
    }

    @Override
    public void eventProperty(String property, boolean value) {
        postToMain(() -> handleProperty(property, value));
    }

    @Override
    public void eventProperty(String property, String value) {
        postToMain(() -> handleProperty(property, value));
    }

    @Override
    public void eventProperty(String property, double value) {
        postToMain(() -> handleProperty(property, value));
    }

    @Override
    public void event(int eventId) {
        postToMain(() -> handleEvent(eventId));
    }

    @Override
    public void logMessage(String prefix, int level, String text) {
        postToMain(() -> {
            if (released) return;
            String line = prefix + ": " + text;
            rememberLog(line);
            markFailureSignal(line);
            if (shouldDebugLogMpvLine(line)) SpiderDebug.log("mpv", "%s", line);
        });
    }

    private void openCurrent() {
        if (mediaItem == null || mediaItem.localConfiguration == null) return;
        try {
            ensureInitialized();
            playbackState = Player.STATE_BUFFERING;
            loading = true;
            playerError = null;
            fileLoaded = false;
            loadStarted = false;
            playbackRestarted = false;
            loadStartRetryCount = 0;
            eofReached = false;
            idleActive = false;
            cachedDurationMs = C.TIME_UNSET;
            cachedCacheDurationMs = 0;
            resetFailureSignals();
            recentLogs.clear();
            mainHandler.removeCallbacks(endFileValidationRunnable);
            closeContentFds();
            Map<String, String> headers = applyMediaOptions(mediaItem);
            bindVideoOutput();
            safeSetPropertyBoolean("pause", !playWhenReady);
            safeSetPropertyString("loop-file", repeatOne ? "inf" : "no");
            safeSetPropertyDouble("speed", playbackParameters.speed);
            safeSetPropertyDouble("volume", volume * 100.0);
            currentPlayableUri = playableUri(mediaItem.localConfiguration.uri);
            currentLikelyHls = isLikelyHls(mediaItem, currentPlayableUri);
            if (shouldProxyHls(currentPlayableUri, currentLikelyHls)) {
                String originalUri = currentPlayableUri;
                currentPlayableUri = hlsProxy.proxy(originalUri, headers);
                SpiderDebug.log("mpv", "hls proxy enabled original=%s proxy=%s", originalUri, currentPlayableUri);
            } else {
                hlsProxy.clear();
            }
            Log.d(TAG, "load uri=" + currentPlayableUri + " hls=" + currentLikelyHls);
            SpiderDebug.log("mpv", "load uri=%s hls=%s surface=%s attached=%s hwdec=%s", currentPlayableUri, currentLikelyHls, surface != null && surface.isValid(), surfaceAttached, config.hwdec());
            loadCurrentUri();
            scheduleLoadStartRetry();
            invalidateState();
            startStateRefresh();
        } catch (Throwable e) {
            fail(e, PlaybackException.ERROR_CODE_IO_UNSPECIFIED);
        }
    }

    private void ensureInitialized() throws IOException {
        if (initialized) return;
        if (!MPVLib.ensureLoaded(context)) {
            Throwable e = MPVLib.getLoadError();
            if (e instanceof IOException io) throw io;
            if (e instanceof RuntimeException runtime) throw runtime;
            throw new IOException(e == null ? "MPV native libraries are unavailable" : e.getMessage(), e);
        }
        copySupportAssets();
        MPVLib.create(context);
        applyPreInitOptions();
        MPVLib.init();
        initialized = true;
        MPVLib.addObserver(this);
        MPVLib.addLogObserver(this);
        applyPostInitOptions();
        observeProperties();
    }

    private void applyPreInitOptions() {
        setOption("config", "yes");
        setOption("config-dir", config.configDir().getAbsolutePath());
        setOption("gpu-shader-cache-dir", config.cacheDir().getAbsolutePath());
        setOption("icc-cache-dir", config.cacheDir().getAbsolutePath());
        setOption("profile", "fast");
        setOption("vo", config.vo());
        setOption("gpu-context", "android");
        setOption("opengl-es", "yes");
        setOption("hwdec", config.hwdec());
        setOption("hwdec-codecs", "h264,hevc,mpeg4,mpeg2video,vp8,vp9,av1");
        setOption("ao", config.ao());
        setOption("audio-set-media-role", "yes");
        setOption("tls-verify", config.tlsVerify() ? "yes" : "no");
        if (config.caFile().isFile()) setOption("tls-ca-file", config.caFile().getAbsolutePath());
        setOption("input-default-bindings", "yes");
        setOption("cache", "yes");
        setOption("http-allow-redirect", "yes");
        setOption("hls-bitrate", "max");
        setOption("demuxer-max-bytes", String.valueOf(config.demuxerMaxBytes()));
        setOption("demuxer-max-back-bytes", String.valueOf(config.demuxerMaxBackBytes()));
        setOption("demuxer-readahead-secs", "20");
        setOption("volume-max", "100");
        setOption("msg-level", config.logLevel());
        for (Map.Entry<String, String> entry : config.extraOptions().entrySet()) setOption(entry.getKey(), entry.getValue());
    }

    private void applyPostInitOptions() {
        setRuntimeString("save-position-on-quit", "no");
        setRuntimeString("force-window", "no");
        setRuntimeString("idle", "once");
    }

    private void observeProperties() {
        observe("time-pos", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE);
        observe("time-pos/full", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE);
        observe("duration", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE);
        observe("duration/full", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE);
        observe("demuxer-cache-duration", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE);
        observe("pause", MPVLib.MpvFormat.MPV_FORMAT_FLAG);
        observe("paused-for-cache", MPVLib.MpvFormat.MPV_FORMAT_FLAG);
        observe("eof-reached", MPVLib.MpvFormat.MPV_FORMAT_FLAG);
        observe("idle-active", MPVLib.MpvFormat.MPV_FORMAT_FLAG);
        observe("width", MPVLib.MpvFormat.MPV_FORMAT_INT64);
        observe("height", MPVLib.MpvFormat.MPV_FORMAT_INT64);
        observe("track-list/count", MPVLib.MpvFormat.MPV_FORMAT_INT64);
        observe("vid", MPVLib.MpvFormat.MPV_FORMAT_STRING);
        observe("aid", MPVLib.MpvFormat.MPV_FORMAT_STRING);
        observe("sid", MPVLib.MpvFormat.MPV_FORMAT_STRING);
        observe("current-tracks/video/id", MPVLib.MpvFormat.MPV_FORMAT_STRING);
        observe("current-tracks/audio/id", MPVLib.MpvFormat.MPV_FORMAT_STRING);
        observe("current-tracks/sub/id", MPVLib.MpvFormat.MPV_FORMAT_STRING);
    }

    private void handleProperty(String property, @Nullable Object value) {
        if (released) return;
        switch (property) {
            case "time-pos", "time-pos/full" -> cachedPositionMs = doubleSecondsToMs(value, cachedPositionMs);
            case "duration", "duration/full" -> cachedDurationMs = doubleSecondsToMs(value, cachedDurationMs);
            case "demuxer-cache-duration" -> cachedCacheDurationMs = Math.max(0, doubleSecondsToMs(value, cachedCacheDurationMs));
            case "pause" -> {
                if (value instanceof Boolean paused) playWhenReady = !paused;
            }
            case "paused-for-cache" -> {
                loading = Boolean.TRUE.equals(value);
                if (loading) playbackState = Player.STATE_BUFFERING;
                else if (playbackState == Player.STATE_BUFFERING && fileLoaded && playbackRestarted) playbackState = Player.STATE_READY;
            }
            case "eof-reached" -> {
                eofReached = Boolean.TRUE.equals(value);
                if (eofReached) {
                    playbackState = Player.STATE_ENDED;
                    loading = false;
                    stopStateRefresh();
                }
            }
            case "idle-active" -> idleActive = Boolean.TRUE.equals(value);
            case "width", "height" -> updateVideoSize();
            case "track-list/count" -> refreshTracks();
            case "vid", "aid", "sid", "current-tracks/video/id", "current-tracks/audio/id", "current-tracks/sub/id" -> refreshTracks();
            default -> {
            }
        }
        invalidateState();
    }

    public Tracks getCurrentTracksSnapshot() {
        return currentTracks;
    }

    public void resetTrackSelection() {
        setMpvTrack(C.TRACK_TYPE_VIDEO, "auto");
        setMpvTrack(C.TRACK_TYPE_AUDIO, "auto");
        setMpvTrack(C.TRACK_TYPE_TEXT, "auto");
        refreshTracks();
        invalidateState();
    }

    public void setTrackSelection(int type, String mpvId) {
        if (TextUtils.isEmpty(mpvId)) return;
        setMpvTrack(type, mpvId);
        refreshTracks();
        invalidateState();
    }

    private void setMpvTrack(int type, String mpvId) {
        if (!initialized) return;
        String property = mpvTrackProperty(type);
        if (property == null) return;
        safeSetPropertyString(property, mpvId);
        SpiderDebug.log("mpv", "select track type=%d property=%s id=%s", type, property, mpvId);
    }

    @Nullable
    private String mpvTrackProperty(int type) {
        return switch (type) {
            case C.TRACK_TYPE_VIDEO -> "vid";
            case C.TRACK_TYPE_AUDIO -> "aid";
            case C.TRACK_TYPE_TEXT -> "sid";
            default -> null;
        };
    }

    private void handleEvent(int eventId) {
        if (released) return;
        switch (eventId) {
            case MPVLib.MpvEvent.MPV_EVENT_START_FILE -> {
                loadStarted = true;
                playbackState = Player.STATE_BUFFERING;
                loading = true;
                fileLoaded = false;
                playbackRestarted = false;
                stopping = false;
                eofReached = false;
                idleActive = false;
                resetFailureSignals();
                SpiderDebug.log("mpv", "event=start-file uri=%s", currentPlayableUri);
                mainHandler.removeCallbacks(endFileValidationRunnable);
                mainHandler.removeCallbacks(loadStartRetryRunnable);
                startStateRefresh();
            }
            case MPVLib.MpvEvent.MPV_EVENT_FILE_LOADED -> {
                if (loadedUnexpectedImage()) {
                    fail(new IOException("MPV loaded image entry instead of video: " + stringProperty("path", "") + recentLogSuffix()), PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED);
                    return;
                }
                fileLoaded = true;
                mainHandler.removeCallbacks(endFileValidationRunnable);
                playbackState = Player.STATE_BUFFERING;
                loading = true;
                cachedDurationMs = durationMs();
                updateVideoSize();
                refreshTracks();
                SpiderDebug.log("mpv", "event=file-loaded duration=%d size=%dx%d path=%s", cachedDurationMs, videoSize.width, videoSize.height, stringProperty("path", ""));
                addSubtitleConfigurations();
                if (pendingSeekPositionMs != C.TIME_UNSET) {
                    seekMpv(pendingSeekPositionMs);
                    pendingSeekPositionMs = C.TIME_UNSET;
                }
                safeSetPropertyBoolean("pause", !playWhenReady);
                startStateRefresh();
            }
            case MPVLib.MpvEvent.MPV_EVENT_PLAYBACK_RESTART -> {
                playbackRestarted = true;
                updateVideoSize();
                refreshTracks();
                SpiderDebug.log("mpv", "event=playback-restart position=%d duration=%d size=%dx%d", positionMs(), durationMs(), videoSize.width, videoSize.height);
                if (playbackState != Player.STATE_ENDED) {
                    playbackState = Player.STATE_READY;
                    loading = false;
                    startStateRefresh();
                }
            }
            case MPVLib.MpvEvent.MPV_EVENT_VIDEO_RECONFIG -> updateVideoSize();
            case MPVLib.MpvEvent.MPV_EVENT_END_FILE -> {
                stopStateRefresh();
                loading = false;
                if (stopping) {
                    stopping = false;
                } else if (isFailedLoadedMedia()) {
                    fail(new IOException(failedLoadedMediaMessage()), PlaybackException.ERROR_CODE_DECODING_FAILED);
                    return;
                } else if (fileLoaded || eofReached) {
                    playbackState = Player.STATE_ENDED;
                } else {
                    loading = true;
                    playbackState = Player.STATE_BUFFERING;
                    mainHandler.removeCallbacks(endFileValidationRunnable);
                    mainHandler.postDelayed(endFileValidationRunnable, END_FILE_VALIDATION_DELAY_MS);
                    startStateRefresh();
                }
            }
            case MPVLib.MpvEvent.MPV_EVENT_IDLE -> {
                if (loading && !fileLoaded && !stopping) {
                    playbackState = Player.STATE_BUFFERING;
                    mainHandler.removeCallbacks(endFileValidationRunnable);
                    mainHandler.postDelayed(endFileValidationRunnable, END_FILE_VALIDATION_DELAY_MS);
                    startStateRefresh();
                }
            }
            case MPVLib.MpvEvent.MPV_EVENT_SHUTDOWN -> {
                playbackState = Player.STATE_IDLE;
                loading = false;
                stopStateRefresh();
            }
            default -> {
            }
        }
        invalidateState();
    }

    private boolean isLikelyHls(MediaItem item, String uri) {
        if (item.localConfiguration != null) {
            String mimeType = item.localConfiguration.mimeType;
            if (MimeTypes.APPLICATION_M3U8.equals(mimeType)
                    || "application/vnd.apple.mpegurl".equalsIgnoreCase(mimeType)
                    || "application/x-mpegurl".equalsIgnoreCase(mimeType)
                    || "hls".equalsIgnoreCase(mimeType)) {
                return true;
            }
        }
        String lower = uri == null ? "" : uri.toLowerCase(Locale.US);
        return lower.contains("m3u8");
    }

    private boolean loadedUnexpectedImage() {
        String path = stringProperty("path", "");
        if (!isImageUri(path)) return false;
        if (!TextUtils.isEmpty(currentPlayableUri) && sameUri(path, currentPlayableUri)) return false;
        Log.w(TAG, "unexpected image path=" + path + " requested=" + currentPlayableUri);
        return true;
    }

    private boolean isImageUri(String uri) {
        if (TextUtils.isEmpty(uri)) return false;
        String lower = uri.toLowerCase(Locale.US);
        int end = lower.length();
        int query = lower.indexOf('?');
        int fragment = lower.indexOf('#');
        if (query >= 0) end = Math.min(end, query);
        if (fragment >= 0) end = Math.min(end, fragment);
        lower = lower.substring(0, end);
        return lower.endsWith(".png")
                || lower.endsWith(".jpg")
                || lower.endsWith(".jpeg")
                || lower.endsWith(".webp")
                || lower.endsWith(".gif")
                || lower.endsWith(".bmp")
                || lower.endsWith(".avif");
    }

    private boolean sameUri(String a, String b) {
        return a != null && b != null && a.equalsIgnoreCase(b);
    }

    private boolean shouldProxyHls(String uri, boolean likelyHls) {
        if (!likelyHls || TextUtils.isEmpty(uri)) return false;
        Uri parsed = Uri.parse(uri);
        String scheme = parsed.getScheme();
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) return false;
        return !"/mpv/index.m3u8".equals(parsed.getPath()) && !"/mpv/item".equals(parsed.getPath());
    }

    private Map<String, String> applyMediaOptions(MediaItem item) {
        Map<String, String> headers = new LinkedHashMap<>(extractHeaders(item));
        String userAgent = findHeader(headers, HttpHeaders.USER_AGENT);
        String referer = findHeader(headers, HttpHeaders.REFERER);
        if (TextUtils.isEmpty(userAgent)) userAgent = config.userAgent();
        if (TextUtils.isEmpty(referer)) referer = config.referer();
        if (TextUtils.isEmpty(referer) && item.localConfiguration != null) referer = originOf(item.localConfiguration.uri);
        String origin = findHeader(headers, HEADER_ORIGIN);
        if (!TextUtils.isEmpty(userAgent)) putHeader(headers, HttpHeaders.USER_AGENT, userAgent);
        if (!TextUtils.isEmpty(referer)) putHeader(headers, HttpHeaders.REFERER, referer);
        if (TextUtils.isEmpty(origin)) origin = originOf(referer);
        if (!TextUtils.isEmpty(origin)) putHeader(headers, HEADER_ORIGIN, origin);
        if (TextUtils.isEmpty(findHeader(headers, HEADER_ACCEPT))) putHeader(headers, HEADER_ACCEPT, "*/*");
        String headerFields = buildHeaderFields(headers);
        setRuntimeString("user-agent", userAgent == null ? "" : userAgent);
        setRuntimeString("referrer", referer == null ? "" : referer);
        setRuntimeString("http-header-fields", headerFields);
        if (item.mediaMetadata.title != null) setRuntimeString("force-media-title", item.mediaMetadata.title.toString());
        SpiderDebug.log("mpv", "media options uaEmpty=%s refererEmpty=%s originEmpty=%s headerNames=%s headerFields=%s",
                TextUtils.isEmpty(userAgent), TextUtils.isEmpty(referer), TextUtils.isEmpty(origin), headerNames(headers), !TextUtils.isEmpty(headerFields));
        return headers;
    }

    private Map<String, String> extractHeaders(MediaItem item) {
        if (item.requestMetadata.extras == null) return Map.of();
        android.os.Bundle extras = item.requestMetadata.extras;
        java.util.LinkedHashMap<String, String> headers = new java.util.LinkedHashMap<>();
        for (String key : extras.keySet()) {
            String value = extras.getString(key);
            if (value != null) headers.put(key, value);
        }
        return headers;
    }

    private String buildHeaderFields(Map<String, String> headers) {
        if (headers.isEmpty()) return "";
        List<String> fields = new ArrayList<>();
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            String key = entry.getKey();
            if (equalsHeader(key, HttpHeaders.USER_AGENT) || equalsHeader(key, HttpHeaders.REFERER) || equalsHeader(key, HttpHeaders.RANGE)) continue;
            fields.add(key + ": " + escapeListValue(entry.getValue()));
        }
        return String.join(",", fields);
    }

    private void putHeader(Map<String, String> headers, String name, String value) {
        if (TextUtils.isEmpty(value)) return;
        String existing = null;
        for (String key : headers.keySet()) {
            if (equalsHeader(key, name)) {
                existing = key;
                break;
            }
        }
        headers.put(existing == null ? name : existing, value.trim());
    }

    private List<String> headerNames(Map<String, String> headers) {
        if (headers.isEmpty()) return List.of();
        List<String> names = new ArrayList<>();
        for (String key : headers.keySet()) names.add(key);
        return names;
    }

    private String escapeListValue(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace(",", "\\,");
    }

    @Nullable
    private String findHeader(Map<String, String> headers, String name) {
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (equalsHeader(entry.getKey(), name)) return entry.getValue();
        }
        return null;
    }

    private boolean equalsHeader(String a, String b) {
        return a != null && a.equalsIgnoreCase(b);
    }

    @Nullable
    private String originOf(String uri) {
        if (TextUtils.isEmpty(uri)) return null;
        try {
            return originOf(Uri.parse(uri));
        } catch (Throwable ignored) {
            return null;
        }
    }

    @Nullable
    private String originOf(Uri uri) {
        if (uri == null || TextUtils.isEmpty(uri.getScheme()) || TextUtils.isEmpty(uri.getHost())) return null;
        String scheme = uri.getScheme();
        int port = uri.getPort();
        if (port > 0 && port != 80 && port != 443) return scheme + "://" + uri.getHost() + ":" + port;
        return scheme + "://" + uri.getHost();
    }

    private String playableUri(Uri uri) throws IOException {
        if ("content".equalsIgnoreCase(uri.getScheme())) {
            ParcelFileDescriptor fd = context.getContentResolver().openFileDescriptor(uri, "r");
            if (fd == null) throw new IOException("Unable to open content uri: " + uri);
            contentFds.add(fd);
            return "fd://" + fd.getFd();
        }
        return uri.toString();
    }

    private void addSubtitleConfigurations() {
        if (mediaItem == null || mediaItem.localConfiguration == null || mediaItem.localConfiguration.subtitleConfigurations.isEmpty()) return;
        for (MediaItem.SubtitleConfiguration sub : mediaItem.localConfiguration.subtitleConfigurations) {
            Uri uri = sub.uri;
            try {
                MPVLib.command(new String[]{"sub-add", playableUri(uri), "auto"});
            } catch (Throwable ignored) {
            }
        }
    }

    private void setVideoOutput(Object output) {
        detachSurfaceHolder();
        if (output instanceof SurfaceView view) {
            setSurfaceHolder(view.getHolder());
        } else if (output instanceof TextureView view && view.getSurfaceTexture() != null) {
            releaseOwnedSurface();
            surface = new Surface(view.getSurfaceTexture());
            ownsSurface = true;
        } else if (output instanceof SurfaceHolder holder) {
            setSurfaceHolder(holder);
        } else if (output instanceof Surface s) {
            releaseOwnedSurface();
            surface = s;
            ownsSurface = false;
        }
        bindVideoOutput();
    }

    private void setSurfaceHolder(SurfaceHolder holder) {
        surfaceHolder = holder;
        surfaceHolder.addCallback(surfaceCallback);
        surface = surfaceHolder.getSurface();
        ownsSurface = false;
    }

    private void bindVideoOutput() {
        if (!initialized || surface == null || !surface.isValid()) return;
        try {
            if (surfaceAttached) detachMpvSurface();
            MPVLib.attachSurface(surface);
            surfaceAttached = true;
            setRuntimeString("force-window", "yes");
            safeSetPropertyString("android-surface-size", "0x0");
            safeSetPropertyString("vo", config.vo());
            SpiderDebug.log("mpv", "surface attached surface=%s vo=%s", surface, config.vo());
        } catch (Throwable e) {
            fail(e, PlaybackException.ERROR_CODE_VIDEO_FRAME_PROCESSING_FAILED);
        }
    }

    private void clearVideoOutput() {
        detachSurfaceHolder();
        detachMpvSurface();
        releaseOwnedSurface();
        surface = null;
    }

    private void detachMpvSurface() {
        if (!initialized || !surfaceAttached) return;
        try {
            safeSetPropertyString("vo", "null");
            setRuntimeString("force-window", "no");
            MPVLib.detachSurface();
        } catch (Throwable ignored) {
        }
        surfaceAttached = false;
    }

    private void detachSurfaceHolder() {
        if (surfaceHolder == null) return;
        try {
            surfaceHolder.removeCallback(surfaceCallback);
        } catch (Throwable ignored) {
        }
        surfaceHolder = null;
    }

    private void releaseOwnedSurface() {
        if (ownsSurface && surface != null) surface.release();
        ownsSurface = false;
    }

    private final SurfaceHolder.Callback surfaceCallback = new SurfaceHolder.Callback() {

        @Override
        public void surfaceCreated(SurfaceHolder holder) {
            surface = holder.getSurface();
            bindVideoOutput();
        }

        @Override
        public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            surface = holder.getSurface();
            if (initialized) safeSetPropertyString("android-surface-size", width + "x" + height);
            bindVideoOutput();
        }

        @Override
        public void surfaceDestroyed(SurfaceHolder holder) {
            surface = null;
            detachMpvSurface();
        }
    };

    private void stopInternal(boolean resetState) {
        stopMpv(true);
        closeContentFds();
        loading = false;
        fileLoaded = false;
        loadStarted = false;
        playbackRestarted = false;
        loadStartRetryCount = 0;
        eofReached = false;
        cachedPositionMs = 0;
        cachedCacheDurationMs = 0;
        cachedDurationMs = C.TIME_UNSET;
        currentTracks = Tracks.EMPTY;
        videoSize = VideoSize.UNKNOWN;
        playerError = null;
        pendingSeekPositionMs = C.TIME_UNSET;
        idleActive = false;
        currentPlayableUri = null;
        currentLikelyHls = false;
        resetFailureSignals();
        hlsProxy.clear();
        mainHandler.removeCallbacks(endFileValidationRunnable);
        mainHandler.removeCallbacks(loadStartRetryRunnable);
        if (resetState) playbackState = Player.STATE_IDLE;
        stopStateRefresh();
        invalidateState();
    }

    private void stopMpv(boolean markStopping) {
        if (!initialized) return;
        boolean previousStopping = stopping;
        if (markStopping) stopping = true;
        try {
            MPVLib.command(new String[]{"stop"});
        } catch (Throwable ignored) {
            stopping = previousStopping;
        }
    }

    private void resetMpvContextForNewMedia() {
        if (!initialized) return;
        mainHandler.removeCallbacks(stateRefreshRunnable);
        mainHandler.removeCallbacks(endFileValidationRunnable);
        mainHandler.removeCallbacks(loadStartRetryRunnable);
        try {
            if (surfaceAttached) MPVLib.detachSurface();
        } catch (Throwable ignored) {
        }
        try {
            MPVLib.removeObserver(this);
            MPVLib.removeLogObserver(this);
            MPVLib.destroy();
        } catch (Throwable ignored) {
        }
        initialized = false;
        surfaceAttached = false;
        stopping = false;
        loadStarted = false;
        loadStartRetryCount = 0;
        SpiderDebug.log("mpv", "context reset for new media");
    }

    private void seekMpv(long positionMs) {
        try {
            MPVLib.command(new String[]{"seek", String.format(Locale.US, "%.3f", positionMs / SECONDS_TO_MS), "absolute+exact"});
        } catch (Throwable e) {
            fail(e, PlaybackException.ERROR_CODE_UNSPECIFIED);
        }
    }

    private void loadCurrentUri() {
        if (currentLikelyHls) {
            MPVLib.command(new String[]{"loadfile", currentPlayableUri, "replace", "-1", HLS_LOAD_OPTIONS});
        } else {
            MPVLib.command(new String[]{"loadfile", currentPlayableUri, "replace"});
        }
    }

    private void scheduleLoadStartRetry() {
        mainHandler.removeCallbacks(loadStartRetryRunnable);
        mainHandler.postDelayed(loadStartRetryRunnable, LOAD_START_RETRY_DELAY_MS);
    }

    private void retryLoadIfNotStarted() {
        if (released || loadStarted || fileLoaded || playerError != null) return;
        if (playbackState != Player.STATE_BUFFERING || TextUtils.isEmpty(currentPlayableUri)) return;
        if (loadStartRetryCount >= MAX_LOAD_START_RETRIES) return;
        loadStartRetryCount++;
        SpiderDebug.log("mpv", "load retry attempt=%d uri=%s idle=%s", loadStartRetryCount, currentPlayableUri, booleanProperty("idle-active", idleActive));
        try {
            loadCurrentUri();
            scheduleLoadStartRetry();
        } catch (Throwable e) {
            fail(e, PlaybackException.ERROR_CODE_IO_UNSPECIFIED);
        }
    }

    private void updateVideoSize() {
        int width = intProperty("width", 0);
        int height = intProperty("height", 0);
        if (width > 0 && height > 0) videoSize = new VideoSize(width, height);
    }

    private void startStateRefresh() {
        mainHandler.removeCallbacks(stateRefreshRunnable);
        mainHandler.postDelayed(stateRefreshRunnable, STATE_REFRESH_INTERVAL_MS);
    }

    private void stopStateRefresh() {
        mainHandler.removeCallbacks(stateRefreshRunnable);
    }

    private void refreshPlaybackState() {
        if (released || mediaItem == null || playbackState == Player.STATE_IDLE || playbackState == Player.STATE_ENDED || playerError != null) return;
        cachedPositionMs = positionMs();
        cachedDurationMs = durationMs();
        cachedCacheDurationMs = Math.max(0, doublePropertyMs("demuxer-cache-duration", cachedCacheDurationMs));
        invalidateState();
        startStateRefresh();
    }

    private void validateEarlyEndFile() {
        if (released || stopping || fileLoaded || eofReached || playerError != null || playbackState != Player.STATE_BUFFERING) return;
        if (booleanProperty("idle-active", idleActive)) {
            fail(new IOException("MPV failed to load media" + recentLogSuffix()), PlaybackException.ERROR_CODE_IO_UNSPECIFIED);
        } else {
            startStateRefresh();
        }
    }

    private boolean isFailedLoadedMedia() {
        if (!fileLoaded) return false;
        if (sawNoAvData || sawInvalidData || sawPngVideo) return true;
        if (recentLogsContain("no audio or video data played", "invalid data found when processing input", "video: png")) return true;
        return playbackRestarted && videoSize.width <= 0 && videoSize.height <= 0 && positionMs() <= 0 && durationMs() == C.TIME_UNSET;
    }

    private String failedLoadedMediaMessage() {
        if (currentLikelyHls && (sawNoAvData
                || sawInvalidData
                || sawPngVideo
                || recentLogsContain("no audio or video data played", "invalid data found when processing input", "video: png")
                || playbackRestarted && videoSize.width <= 0 && videoSize.height <= 0 && positionMs() <= 0)) {
            return HLS_PLAYBACK_FAILED_MESSAGE + recentLogSuffix();
        }
        return "MPV failed to play media" + recentLogSuffix();
    }

    private void rememberLog(String line) {
        if (recentLogs.size() >= RECENT_LOG_LIMIT) recentLogs.remove(0);
        recentLogs.add(line);
    }

    private void markFailureSignal(String line) {
        String lower = line == null ? "" : line.toLowerCase(Locale.US);
        if (lower.contains("no audio or video data played")) sawNoAvData = true;
        if (lower.contains("invalid data found when processing input")) sawInvalidData = true;
        if (lower.contains("video: png")) sawPngVideo = true;
        if (sawNoAvData || sawInvalidData || sawPngVideo || lower.contains("failed") || lower.contains("error")) lastFailureLog = line;
    }

    private boolean shouldDebugLogMpvLine(String line) {
        String lower = line == null ? "" : line.toLowerCase(Locale.US);
        return lower.contains("error")
                || lower.contains("failed")
                || lower.contains("invalid")
                || lower.contains("no audio")
                || lower.contains("video:")
                || lower.contains("audio:")
                || lower.contains("found 'hls'")
                || lower.contains("opening")
                || lower.contains("lavf")
                || lower.contains("demux")
                || lower.contains("codec")
                || lower.contains("track");
    }

    private void resetFailureSignals() {
        sawNoAvData = false;
        sawInvalidData = false;
        sawPngVideo = false;
        lastFailureLog = null;
    }

    private boolean recentLogsContain(String... needles) {
        for (String log : recentLogs) {
            String lower = log == null ? "" : log.toLowerCase(Locale.US);
            for (String needle : needles) {
                if (lower.contains(needle)) return true;
            }
        }
        return false;
    }

    private long positionMs() {
        if (initialized) cachedPositionMs = Math.max(0, doublePropertyMs("time-pos/full", doublePropertyMs("time-pos", cachedPositionMs)));
        return cachedPositionMs;
    }

    private long durationMs() {
        if (initialized) {
            long duration = doublePropertyMs("duration/full", doublePropertyMs("duration", cachedDurationMs));
            cachedDurationMs = duration > 0 ? duration : C.TIME_UNSET;
        }
        return cachedDurationMs > 0 ? cachedDurationMs : C.TIME_UNSET;
    }

    private long bufferedPositionMs(long position, long duration) {
        if (duration == C.TIME_UNSET || duration <= 0) return position;
        if (cachedCacheDurationMs > 0) return Math.min(duration, position + cachedCacheDurationMs);
        return playbackState == Player.STATE_READY || playbackState == Player.STATE_ENDED ? duration : position;
    }

    private boolean isPlayingInternal() {
        return playbackState == Player.STATE_READY && playWhenReady && !loading;
    }

    private void refreshTracks() {
        if (!initialized) {
            currentTracks = Tracks.EMPTY;
            return;
        }
        int count = Math.max(0, intProperty("track-list/count", 0));
        if (count <= 0) {
            currentTracks = Tracks.EMPTY;
            return;
        }
        List<TrackInfo> infos = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            TrackInfo info = readTrackInfo(i);
            if (info == null) continue;
            infos.add(info);
        }
        if (infos.isEmpty()) {
            currentTracks = Tracks.EMPTY;
            return;
        }
        String selectedVideo = selectedTrackId(C.TRACK_TYPE_VIDEO);
        String selectedAudio = selectedTrackId(C.TRACK_TYPE_AUDIO);
        String selectedText = selectedTrackId(C.TRACK_TYPE_TEXT);
        boolean hasSelectedVideo = hasSelectedTrack(infos, C.TRACK_TYPE_VIDEO, selectedVideo);
        boolean hasSelectedAudio = hasSelectedTrack(infos, C.TRACK_TYPE_AUDIO, selectedAudio);
        boolean hasSelectedText = hasSelectedTrack(infos, C.TRACK_TYPE_TEXT, selectedText);
        boolean autoVideoFallbackUsed = false;
        boolean autoAudioFallbackUsed = false;
        boolean autoTextFallbackUsed = false;
        List<Tracks.Group> groups = new ArrayList<>();
        for (TrackInfo info : infos) {
            boolean selected = isTrackSelected(info, trackIdForType(info.type, selectedVideo, selectedAudio, selectedText));
            if (!selected && info.type == C.TRACK_TYPE_VIDEO && !hasSelectedVideo && isAutoTrackChoice(selectedVideo) && !autoVideoFallbackUsed) {
                selected = true;
                autoVideoFallbackUsed = true;
            } else if (!selected && info.type == C.TRACK_TYPE_AUDIO && !hasSelectedAudio && isAutoTrackChoice(selectedAudio) && !autoAudioFallbackUsed) {
                selected = true;
                autoAudioFallbackUsed = true;
            } else if (!selected && info.type == C.TRACK_TYPE_TEXT && !hasSelectedText && isAutoTrackChoice(selectedText) && !autoTextFallbackUsed) {
                selected = true;
                autoTextFallbackUsed = true;
            }
            Format format = info.toFormat();
            TrackGroup mediaGroup = new TrackGroup("mpv:" + info.type + ":" + info.id, format);
            groups.add(new Tracks.Group(mediaGroup, false, new int[]{C.FORMAT_HANDLED}, new boolean[]{selected}));
        }
        currentTracks = groups.isEmpty() ? Tracks.EMPTY : new Tracks(groups);
        if (SpiderDebug.isEnabled()) SpiderDebug.log("mpv", "tracks refreshed count=%d groups=%d", count, groups.size());
    }

    private boolean hasSelectedTrack(List<TrackInfo> infos, int type, String selectedId) {
        for (TrackInfo info : infos) {
            if (info.type == type && isTrackSelected(info, selectedId)) return true;
        }
        return false;
    }

    private boolean isTrackSelected(TrackInfo info, String selectedId) {
        if (info.selected) return true;
        if (TextUtils.isEmpty(selectedId) || isAutoTrackChoice(selectedId) || isDisabledTrackChoice(selectedId)) return false;
        return selectedId.equals(info.id);
    }

    private String selectedTrackId(int type) {
        String currentTrackId = currentTrackId(type);
        if (!TextUtils.isEmpty(currentTrackId)) return currentTrackId;
        String property = mpvTrackProperty(type);
        return property == null ? "" : propertyStringOrInt(property);
    }

    private String currentTrackId(int type) {
        String property = switch (type) {
            case C.TRACK_TYPE_VIDEO -> "current-tracks/video/id";
            case C.TRACK_TYPE_AUDIO -> "current-tracks/audio/id";
            case C.TRACK_TYPE_TEXT -> "current-tracks/sub/id";
            default -> null;
        };
        return property == null ? "" : propertyStringOrInt(property);
    }

    private String propertyStringOrInt(String property) {
        String value = stringProperty(property, "");
        if (!TextUtils.isEmpty(value)) return value;
        int intValue = intProperty(property, Integer.MIN_VALUE);
        return intValue == Integer.MIN_VALUE ? "" : String.valueOf(intValue);
    }

    private String trackIdForType(int type, String selectedVideo, String selectedAudio, String selectedText) {
        return switch (type) {
            case C.TRACK_TYPE_VIDEO -> selectedVideo;
            case C.TRACK_TYPE_AUDIO -> selectedAudio;
            case C.TRACK_TYPE_TEXT -> selectedText;
            default -> "";
        };
    }

    private boolean isAutoTrackChoice(String value) {
        return "auto".equalsIgnoreCase(value);
    }

    private boolean isDisabledTrackChoice(String value) {
        return "no".equalsIgnoreCase(value);
    }

    @Nullable
    private TrackInfo readTrackInfo(int index) {
        String prefix = "track-list/" + index + "/";
        String mpvType = stringProperty(prefix + "type", "");
        int type = mediaTrackType(mpvType);
        if (type == C.TRACK_TYPE_UNKNOWN) return null;
        if (type == C.TRACK_TYPE_VIDEO && booleanProperty(prefix + "albumart", false)) return null;
        String id = stringProperty(prefix + "id", "");
        if (TextUtils.isEmpty(id)) id = String.valueOf(intProperty(prefix + "id", index + 1));
        String title = stringProperty(prefix + "title", "");
        String lang = stringProperty(prefix + "lang", "");
        String codec = stringProperty(prefix + "codec", "");
        boolean selected = booleanProperty(prefix + "selected", false);
        int width = intProperty(prefix + "demux-w", C.LENGTH_UNSET);
        int height = intProperty(prefix + "demux-h", C.LENGTH_UNSET);
        int sampleRate = intProperty(prefix + "demux-samplerate", C.RATE_UNSET_INT);
        int channels = intProperty(prefix + "demux-channel-count", C.LENGTH_UNSET);
        int bitrate = intProperty(prefix + "demux-bitrate", C.LENGTH_UNSET);
        return new TrackInfo(type, id, title, lang, codec, selected, width, height, sampleRate, channels, bitrate);
    }

    private int mediaTrackType(String mpvType) {
        if ("video".equals(mpvType)) return C.TRACK_TYPE_VIDEO;
        if ("audio".equals(mpvType)) return C.TRACK_TYPE_AUDIO;
        if ("sub".equals(mpvType)) return C.TRACK_TYPE_TEXT;
        return C.TRACK_TYPE_UNKNOWN;
    }

    private String sampleMimeType(TrackInfo info) {
        String codec = info.codec == null ? "" : info.codec.toLowerCase(Locale.US);
        if (info.type == C.TRACK_TYPE_TEXT) {
            if (codec.contains("ass") || codec.contains("ssa")) return MimeTypes.TEXT_SSA;
            if (codec.contains("webvtt") || codec.contains("vtt")) return MimeTypes.TEXT_VTT;
            if (codec.contains("ttml")) return MimeTypes.APPLICATION_TTML;
            return MimeTypes.APPLICATION_SUBRIP;
        }
        if (info.type == C.TRACK_TYPE_AUDIO) {
            if (codec.contains("aac")) return MimeTypes.AUDIO_AAC;
            if (codec.contains("ac3")) return MimeTypes.AUDIO_AC3;
            if (codec.contains("eac3") || codec.contains("e-ac-3")) return MimeTypes.AUDIO_E_AC3;
            if (codec.contains("opus")) return MimeTypes.AUDIO_OPUS;
            if (codec.contains("vorbis")) return MimeTypes.AUDIO_VORBIS;
            if (codec.contains("flac")) return MimeTypes.AUDIO_FLAC;
            if (codec.contains("mp3")) return MimeTypes.AUDIO_MPEG;
            return MimeTypes.BASE_TYPE_AUDIO + "/" + (TextUtils.isEmpty(codec) ? "unknown" : codec);
        }
        if (codec.contains("hevc") || codec.contains("h265")) return MimeTypes.VIDEO_H265;
        if (codec.contains("h264") || codec.contains("avc")) return MimeTypes.VIDEO_H264;
        if (codec.contains("av1")) return MimeTypes.VIDEO_AV1;
        if (codec.contains("vp9")) return MimeTypes.VIDEO_VP9;
        if (codec.contains("vp8")) return MimeTypes.VIDEO_VP8;
        if (codec.contains("mpeg2")) return MimeTypes.VIDEO_MPEG2;
        return MimeTypes.BASE_TYPE_VIDEO + "/" + (TextUtils.isEmpty(codec) ? "unknown" : codec);
    }

    private long doublePropertyMs(String property, long fallback) {
        try {
            Double value = MPVLib.getPropertyDouble(property);
            if (value == null || value.isNaN() || value.isInfinite()) return fallback;
            return Math.max(0, Math.round(value * SECONDS_TO_MS));
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private long doubleSecondsToMs(@Nullable Object value, long fallback) {
        if (value instanceof Number number) return Math.max(0, Math.round(number.doubleValue() * SECONDS_TO_MS));
        return fallback;
    }

    private int intProperty(String property, int fallback) {
        try {
            Integer value = MPVLib.getPropertyInt(property);
            return value == null ? fallback : value;
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private boolean booleanProperty(String property, boolean fallback) {
        try {
            Boolean value = MPVLib.getPropertyBoolean(property);
            return value == null ? fallback : value;
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private String stringProperty(String property, String fallback) {
        try {
            String value = MPVLib.getPropertyString(property);
            return value == null ? fallback : value;
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private void fail(Throwable e, int errorCode) {
        playerError = new PlaybackException(e.getMessage(), e, errorCode);
        playbackState = Player.STATE_IDLE;
        loading = false;
        fileLoaded = false;
        closeContentFds();
        mainHandler.removeCallbacks(endFileValidationRunnable);
        stopStateRefresh();
        invalidateState();
    }

    private String recentLogSuffix() {
        if (!TextUtils.isEmpty(lastFailureLog)) return ": " + lastFailureLog;
        if (recentLogs.isEmpty()) return "";
        return ": " + recentLogs.get(recentLogs.size() - 1);
    }

    private void postToMain(Runnable runnable) {
        if (Looper.myLooper() == Looper.getMainLooper()) runnable.run();
        else mainHandler.post(runnable);
    }

    private void setOption(String name, String value) {
        if (value == null) value = "";
        try {
            MPVLib.setOptionString(name, value);
        } catch (Throwable ignored) {
        }
    }

    private void setRuntimeString(String name, String value) {
        if (value == null) value = "";
        if (initialized) {
            try {
                MPVLib.setPropertyString(name, value);
                return;
            } catch (Throwable ignored) {
            }
        }
        setOption(name, value);
    }

    private void observe(String property, int format) {
        try {
            MPVLib.observeProperty(property, format);
        } catch (Throwable ignored) {
        }
    }

    private void safeSetPropertyBoolean(String property, boolean value) {
        try {
            MPVLib.setPropertyBoolean(property, value);
        } catch (Throwable ignored) {
        }
    }

    private void safeSetPropertyDouble(String property, double value) {
        try {
            MPVLib.setPropertyDouble(property, value);
        } catch (Throwable ignored) {
        }
    }

    private void safeSetPropertyString(String property, String value) {
        try {
            MPVLib.setPropertyString(property, value);
        } catch (Throwable ignored) {
        }
    }

    private void closeContentFds() {
        if (contentFds.isEmpty()) return;
        for (ParcelFileDescriptor fd : contentFds) {
            try {
                fd.close();
            } catch (IOException ignored) {
            }
        }
        contentFds.clear();
    }

    private void copySupportAssets() throws IOException {
        copyAsset("cacert.pem", config.caFile());
        writeFontsConf(new File(config.configDir(), "fonts.conf"));
    }

    private void copyAsset(String name, File outFile) throws IOException {
        AssetManager assets = context.getAssets();
        try (InputStream in = assets.open(name, AssetManager.ACCESS_STREAMING)) {
            long size = in.available();
            if (outFile.length() == size && size > 0) return;
            File parent = outFile.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) throw new IOException("Unable to create " + parent);
            try (OutputStream out = new FileOutputStream(outFile)) {
                byte[] buffer = new byte[16 * 1024];
                int read;
                while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
            }
        }
    }

    private void writeFontsConf(File file) {
        String text = "<fontconfig>\n"
                + "<dir>/system/fonts/</dir>\n"
                + "<dir>/product/fonts/</dir>\n"
                + "<cachedir>" + config.cacheDir().getAbsolutePath() + "</cachedir>\n"
                + "<alias><family>serif</family><prefer><family>Noto Serif</family></prefer></alias>\n"
                + "<alias><family>sans-serif</family><prefer><family>Roboto</family><family>Noto Sans</family></prefer></alias>\n"
                + "<alias><family>monospace</family><prefer><family>Droid Sans Mono</family></prefer></alias>\n"
                + "</fontconfig>\n";
        try (OutputStream out = new FileOutputStream(file)) {
            out.write(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (IOException ignored) {
        }
    }

    private final class TrackInfo {
        final int type;
        final String id;
        final String title;
        final String lang;
        final String codec;
        final boolean selected;
        final int width;
        final int height;
        final int sampleRate;
        final int channels;
        final int bitrate;

        TrackInfo(int type, String id, String title, String lang, String codec, boolean selected, int width, int height, int sampleRate, int channels, int bitrate) {
            this.type = type;
            this.id = id;
            this.title = title;
            this.lang = lang;
            this.codec = codec;
            this.selected = selected;
            this.width = width;
            this.height = height;
            this.sampleRate = sampleRate;
            this.channels = channels;
            this.bitrate = bitrate;
        }

        Format toFormat() {
            String label = TextUtils.isEmpty(title) ? trackLabel() : title;
            Format.Builder builder = new Format.Builder()
                    .setId(type + ":" + id)
                    .setLabel(label)
                    .setCodecs(TextUtils.isEmpty(codec) ? null : codec)
                    .setLanguage(TextUtils.isEmpty(lang) ? null : lang)
                    .setSampleMimeType(sampleMimeType(this));
            if (width > 0) builder.setWidth(width);
            if (height > 0) builder.setHeight(height);
            if (sampleRate > 0) builder.setSampleRate(sampleRate);
            if (channels > 0) builder.setChannelCount(channels);
            if (bitrate > 0) builder.setAverageBitrate(bitrate);
            return builder.build();
        }

        private String trackLabel() {
            String prefix = switch (type) {
                case C.TRACK_TYPE_VIDEO -> "Video";
                case C.TRACK_TYPE_AUDIO -> "Audio";
                case C.TRACK_TYPE_TEXT -> "Subtitle";
                default -> "Track";
            };
            return prefix + " " + id;
        }
    }
}
