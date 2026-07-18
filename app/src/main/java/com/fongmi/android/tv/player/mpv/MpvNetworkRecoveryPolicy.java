package com.fongmi.android.tv.player.mpv;

import com.fongmi.android.tv.player.PlaybackRoute;

public final class MpvNetworkRecoveryPolicy {

    private MpvNetworkRecoveryPolicy() {
    }

    public static Decision resolve(String playableUri) {
        PlaybackRoute.Resolution resolution = PlaybackRoute.resolve(playableUri);
        PlaybackRoute route = resolution.route();
        return switch (route) {
            case DIRECT_REMOTE_HTTP -> decision(resolution, "mpv-native-curl", true, false, false);
            case APP_LOCAL_SERVICE -> decision(resolution, "app-local-service", false, true, false);
            case EXTERNAL_LOOPBACK_PROXY -> decision(resolution, "external-or-unknown-loopback", false, true, false);
            default -> decision(resolution, "not-applicable", false, false, false);
        };
    }

    private static Decision decision(PlaybackRoute.Resolution resolution, String recoveryOwner, boolean nativeRemoteRecovery, boolean proxyOwnsUpstreamRecovery, boolean appReconnectOverlay) {
        return new Decision(resolution.route(), resolution.owner().label(), resolution.evidence().label(), resolution.confidence().label(), recoveryOwner, nativeRemoteRecovery, proxyOwnsUpstreamRecovery, appReconnectOverlay);
    }

    public record Decision(PlaybackRoute route, String routeOwner, String routeEvidence, String routeConfidence, String recoveryOwner, boolean nativeRemoteRecovery, boolean proxyOwnsUpstreamRecovery, boolean appReconnectOverlay) {
    }
}
