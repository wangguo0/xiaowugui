package com.fongmi.android.tv.player;

import com.fongmi.android.tv.setting.PreloadSetting;
import com.github.catvod.Proxy;

import java.net.URI;

public enum PlaybackRoute {

    DIRECT_REMOTE_HTTP,
    APP_LOCAL_SERVICE,
    EXTERNAL_LOOPBACK_PROXY,
    OTHER;

    public static PlaybackRoute classify(String url) {
        if (url == null || url.isBlank()) return OTHER;
        try {
            URI uri = URI.create(url);
            String scheme = uri.getScheme();
            if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) return OTHER;
            if (!isLoopback(uri.getHost())) return DIRECT_REMOTE_HTTP;
            int appPort = Proxy.getPort();
            return appPort > 0 && uri.getPort() == appPort ? APP_LOCAL_SERVICE : EXTERNAL_LOOPBACK_PROXY;
        } catch (IllegalArgumentException e) {
            return OTHER;
        }
    }

    public int effectivePreloadThreads(int requestedThreads) {
        int requested = Math.min(Math.max(requestedThreads, PreloadSetting.MIN_THREADS), PreloadSetting.MAX_THREADS);
        return this == EXTERNAL_LOOPBACK_PROXY ? PreloadSetting.MIN_THREADS : requested;
    }

    private static boolean isLoopback(String host) {
        if (host == null) return false;
        return "localhost".equalsIgnoreCase(host)
                || "127.0.0.1".equals(host)
                || "::1".equals(host)
                || "[::1]".equals(host)
                || "0:0:0:0:0:0:0:1".equals(host);
    }
}
