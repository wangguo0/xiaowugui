package com.fongmi.android.tv.player.engine;

import androidx.media3.common.MimeTypes;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.Tracks;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.mpvplayer.MpvPlayer;
import androidx.media3.mpvplayer.MpvPlayerConfig;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.Track;
import com.fongmi.android.tv.player.exo.ExoUtil;
import com.fongmi.android.tv.player.exo.TrackUtil;
import com.fongmi.android.tv.player.PlayerHelper;
import com.fongmi.android.tv.utils.ResUtil;
import com.github.catvod.crawler.SpiderDebug;

import java.util.List;
import java.util.concurrent.TimeUnit;

@UnstableApi
public class MpvPlayerEngine implements PlayerEngine {

    private MpvPlayer player;
    private PlaySpec spec;
    private boolean playWhenReady;
    private boolean retriedFormat;
    private int decode;

    public MpvPlayerEngine(int decode, Player.Listener listener) {
        this.decode = decode;
        this.player = buildPlayer(listener);
    }

    @Override
    public Player getPlayer() {
        return player;
    }

    @Override
    public void release() {
        player.release();
    }

    @Override
    public Player rebuild(Player.Listener listener) {
        player.release();
        SpiderDebug.log("player-engine", "rebuild mpv decode=%d", decode);
        return player = buildPlayer(listener);
    }

    @Override
    public int getDecode() {
        return decode;
    }

    @Override
    public void setDecode(int decode) {
        this.decode = decode;
    }

    @Override
    public boolean isHard() {
        return decode == HARD;
    }

    @Override
    public String getDecodeText() {
        return ResUtil.getStringArray(R.array.select_decode)[decode];
    }

    @Override
    public void start(PlaySpec spec) {
        start(spec, true);
    }

    @Override
    public void start(PlaySpec spec, boolean playWhenReady) {
        start(spec, 0, playWhenReady);
    }

    @Override
    public void start(PlaySpec spec, long position, boolean playWhenReady) {
        this.spec = spec;
        this.playWhenReady = playWhenReady;
        this.retriedFormat = false;
        SpiderDebug.log("player-engine", "start mpv decode=%d position=%d play=%s url=%s headers=%s", decode, position, playWhenReady, spec.getUrl(), spec.getHeaders());
        MediaItem item = ExoUtil.getMediaItem(spec, decode);
        if (position > 0) player.setMediaItem(item, position);
        else player.setMediaItem(item);
        player.prepare();
        if (playWhenReady) player.play();
        else player.pause();
    }

    @Override
    public void restart(PlaySpec spec, long position, boolean playWhenReady) {
        player.stop();
        start(spec, position, playWhenReady);
    }

    @Override
    public void stop() {
        player.stop();
    }

    @Override
    public void setMetadata(MediaMetadata data) {
        MediaItem current = player.getCurrentMediaItem();
        if (current != null) player.replaceMediaItem(player.getCurrentMediaItemIndex(), current.buildUpon().setMediaMetadata(data).build());
    }

    @Override
    public boolean isLive() {
        return player.getDuration() < TimeUnit.MINUTES.toMillis(1) || player.isCurrentMediaItemLive();
    }

    @Override
    public boolean isVod() {
        return player.getDuration() > TimeUnit.MINUTES.toMillis(1) && !player.isCurrentMediaItemLive();
    }

    @Override
    public void setTrack(List<Track> tracks) {
        for (Track track : tracks) {
            if (track.isDisabled() && track.getType() == C.TRACK_TYPE_TEXT) {
                player.setTrackSelection(C.TRACK_TYPE_TEXT, "no");
                continue;
            }
            String id = findMpvTrackId(track);
            if (id != null) player.setTrackSelection(track.getType(), id);
        }
    }

    @Override
    public void resetTrack() {
        player.resetTrackSelection();
    }

    @Override
    public boolean haveTrack(int type) {
        return TrackUtil.count(getCurrentTracks(), type) > 0;
    }

    @Override
    public Tracks getCurrentTracks() {
        return player.getCurrentTracksSnapshot();
    }

    private String findMpvTrackId(Track track) {
        if (track == null || track.getFormat() == null) return null;
        for (Tracks.Group group : getCurrentTracks().getGroups()) {
            if (group.getType() != track.getType()) continue;
            for (int i = 0; i < group.length; i++) {
                if (!group.isTrackSupported(i)) continue;
                Format format = group.getTrackFormat(i);
                if (!track.getFormat().equals(PlayerHelper.describeFormat(format))) continue;
                return parseMpvTrackId(format.id);
            }
        }
        return null;
    }

    private String parseMpvTrackId(String id) {
        if (id == null) return null;
        int index = id.indexOf(':');
        return index >= 0 && index + 1 < id.length() ? id.substring(index + 1) : id;
    }

    @Override
    public String getErrorMessage(PlaybackException e) {
        String message = e.getMessage();
        if (message != null && message.startsWith("MPV_HLS_PLAYBACK_FAILED")) {
            return ResUtil.getString(R.string.error_play_mpv_hls_unsupported);
        }
        return e.getMessage();
    }

    @Override
    public ErrorAction handleError(PlaybackException e) {
        SpiderDebug.log("player-engine", "handleError mpv code=%d message=%s format=%s retried=%s url=%s", e.errorCode, e.getMessage(), spec == null ? null : spec.getFormat(), retriedFormat, spec == null ? null : spec.getUrl());
        if (shouldRetryFormat(e)) return retryFormat();
        return ErrorAction.FATAL;
    }

    private boolean shouldRetryFormat(PlaybackException e) {
        if (retriedFormat || spec == null || spec.getFormat() != null) return false;
        String message = e.getMessage();
        if (message != null && message.startsWith("MPV failed to play media")) return false;
        return e.errorCode == PlaybackException.ERROR_CODE_IO_UNSPECIFIED
                || e.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED
                || e.errorCode == PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED
                || e.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED
                || e.errorCode == PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED;
    }

    private ErrorAction retryFormat() {
        retriedFormat = true;
        spec.setFormat(MimeTypes.APPLICATION_M3U8);
        long position = Math.max(0, player.getCurrentPosition());
        SpiderDebug.log("player-engine", "retryFormat mpv newFormat=%s position=%d", spec.getFormat(), position);
        player.stop();
        MediaItem item = ExoUtil.getMediaItem(spec, decode);
        if (position > 0) player.setMediaItem(item, position);
        else player.setMediaItem(item);
        player.prepare();
        if (playWhenReady) player.play();
        else player.pause();
        return ErrorAction.RECOVERED;
    }

    private MpvPlayer buildPlayer(Player.Listener listener) {
        MpvPlayer player = new MpvPlayer(App.get(), buildConfig());
        player.addListener(listener);
        return player;
    }

    private MpvPlayerConfig buildConfig() {
        return MpvPlayerConfig.builder(App.get())
                .hwdec(decode == HARD ? "mediacodec,mediacodec-copy" : "no")
                .build();
    }
}
