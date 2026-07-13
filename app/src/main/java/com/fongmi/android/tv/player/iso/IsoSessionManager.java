package com.fongmi.android.tv.player.iso;

import com.github.catvod.crawler.SpiderDebug;

import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public final class IsoSessionManager {

    private static final AtomicLong NEXT_ID = new AtomicLong(1000);
    private static final Map<Long, IsoPlaybackSession> SESSIONS = new ConcurrentHashMap<>();

    private IsoSessionManager() {
    }

    public static String create(String url, Map<String, String> headers) throws Exception {
        long id = NEXT_ID.incrementAndGet();
        IsoPlaybackSession session = new IsoPlaybackSession(id, url, headers);
        SESSIONS.put(id, session);
        SpiderDebug.log("iso-native", "session create id=%d", id);
        return "webhtv-dvdiso://" + id + "/longest";
    }

    public static void close(long id) {
        IsoPlaybackSession session = SESSIONS.remove(id);
        if (session != null) {
            session.close();
            SpiderDebug.log("iso-native", "session close id=%d", id);
        }
    }

    public static void closeUri(String uri) {
        close(parseId(uri));
    }

    public static long length(long id) {
        IsoPlaybackSession session = SESSIONS.get(id);
        if (session == null) return -1;
        try {
            return session.length();
        } catch (Throwable e) {
            SpiderDebug.log("iso-source", "length failed id=%d error=%s", id, e.getMessage());
            return -1;
        }
    }

    public static int readAt(long id, long offset, ByteBuffer target, int length) {
        IsoPlaybackSession session = SESSIONS.get(id);
        if (session == null || target == null) return -1;
        try {
            return session.readAt(offset, target, length);
        } catch (Throwable e) {
            SpiderDebug.log("iso-source", "read failed id=%d offset=%d length=%d error=%s", id, offset, length, e.getMessage());
            return -1;
        }
    }

    public static long parseId(String uri) {
        if (uri == null) return -1;
        int scheme = uri.indexOf("://");
        int start = scheme < 0 ? 0 : scheme + 3;
        int end = uri.indexOf('/', start);
        if (end < 0) end = uri.length();
        try {
            return Long.parseLong(uri.substring(start, end));
        } catch (Throwable ignored) {
            return -1;
        }
    }
}
