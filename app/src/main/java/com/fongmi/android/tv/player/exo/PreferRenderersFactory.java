package com.fongmi.android.tv.player.exo;

import android.content.Context;
import android.os.Handler;

import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.Renderer;
import androidx.media3.exoplayer.audio.AudioRendererEventListener;
import androidx.media3.exoplayer.audio.AudioSink;
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector;
import androidx.media3.exoplayer.video.VideoRendererEventListener;

import java.util.ArrayList;

import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.NextRenderersFactory;

@UnstableApi
public class PreferRenderersFactory extends NextRenderersFactory {

    private final boolean audioPrefer;
    private final boolean videoPrefer;

    public PreferRenderersFactory(Context context, boolean audioPrefer, boolean videoPrefer) {
        super(context);
        this.audioPrefer = audioPrefer;
        this.videoPrefer = videoPrefer;
    }

    @Override
    public void buildAudioRenderers(Context context, int extensionRendererMode, MediaCodecSelector mediaCodecSelector, boolean enableDecoderFallback, AudioSink audioSink, Handler eventHandler, AudioRendererEventListener eventListener, ArrayList<Renderer> out) {
        super.buildAudioRenderers(context, prefer(extensionRendererMode, audioPrefer), mediaCodecSelector, enableDecoderFallback, audioSink, eventHandler, eventListener, out);
    }

    @Override
    public void buildVideoRenderers(Context context, int extensionRendererMode, MediaCodecSelector mediaCodecSelector, boolean enableDecoderFallback, Handler eventHandler, VideoRendererEventListener eventListener, long allowedVideoJoiningTimeMs, ArrayList<Renderer> out) {
        super.buildVideoRenderers(context, prefer(extensionRendererMode, videoPrefer), mediaCodecSelector, enableDecoderFallback, eventHandler, eventListener, allowedVideoJoiningTimeMs, out);
    }

    private int prefer(int extensionRendererMode, boolean prefer) {
        if (!prefer) return extensionRendererMode;
        if (extensionRendererMode == EXTENSION_RENDERER_MODE_OFF) return extensionRendererMode;
        return EXTENSION_RENDERER_MODE_PREFER;
    }
}
