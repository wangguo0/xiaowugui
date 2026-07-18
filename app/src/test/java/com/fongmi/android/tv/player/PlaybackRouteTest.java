package com.fongmi.android.tv.player;

import com.github.catvod.Proxy;

import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class PlaybackRouteTest {

    @Before
    public void setUp() {
        Proxy.set(9988);
    }

    @Test
    public void classifiesAppLocalServiceByOwnedPort() {
        assertEquals(PlaybackRoute.APP_LOCAL_SERVICE, PlaybackRoute.classify("http://127.0.0.1:9988/media"));
        assertEquals(PlaybackRoute.APP_LOCAL_SERVICE, PlaybackRoute.classify("http://localhost:9988/proxy"));
    }

    @Test
    public void classifiesOtherLoopbackPortAsExternalProxy() {
        assertEquals(PlaybackRoute.EXTERNAL_LOOPBACK_PROXY, PlaybackRoute.classify("http://127.0.0.1:7777/video"));
        assertEquals(PlaybackRoute.EXTERNAL_LOOPBACK_PROXY, PlaybackRoute.classify("http://localhost:8080/video"));
    }

    @Test
    public void classifiesRemoteHttpSeparately() {
        assertEquals(PlaybackRoute.DIRECT_REMOTE_HTTP, PlaybackRoute.classify("https://cdn.example.com/movie.mkv"));
        assertEquals(PlaybackRoute.OTHER, PlaybackRoute.classify("file:///storage/movie.mkv"));
    }

    @Test
    public void externalProxyAlwaysUsesOnePreloadThreadWhileAppRouteKeepsCustomValue() {
        assertEquals(1, PlaybackRoute.EXTERNAL_LOOPBACK_PROXY.effectivePreloadThreads(4));
        assertEquals(4, PlaybackRoute.APP_LOCAL_SERVICE.effectivePreloadThreads(10));
    }

    @Test
    public void directRemoteKeepsConfiguredFiniteConcurrency() {
        assertEquals(1, PlaybackRoute.DIRECT_REMOTE_HTTP.effectivePreloadThreads(1));
        assertEquals(2, PlaybackRoute.DIRECT_REMOTE_HTTP.effectivePreloadThreads(2));
        assertEquals(3, PlaybackRoute.DIRECT_REMOTE_HTTP.effectivePreloadThreads(3));
        assertEquals(4, PlaybackRoute.DIRECT_REMOTE_HTTP.effectivePreloadThreads(4));
        assertEquals(4, PlaybackRoute.DIRECT_REMOTE_HTTP.effectivePreloadThreads(10));
    }

    @Test
    public void appOwnedAndDirectRoutesDefaultToOneThread() {
        assertEquals(1, PlaybackRoute.APP_LOCAL_SERVICE.effectivePreloadThreads(1));
        assertEquals(1, PlaybackRoute.DIRECT_REMOTE_HTTP.effectivePreloadThreads(1));
    }

    @Test
    public void unknownRouteUsesConservativeSingleThread() {
        assertEquals(1, PlaybackRoute.OTHER.effectivePreloadThreads(4));
    }
}
