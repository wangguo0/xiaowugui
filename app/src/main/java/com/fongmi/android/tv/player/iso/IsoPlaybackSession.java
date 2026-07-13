package com.fongmi.android.tv.player.iso;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

final class IsoPlaybackSession {

    private final AtomicBoolean closed = new AtomicBoolean();
    private final IsoPageCache source;
    private final long id;

    IsoPlaybackSession(long id, String url, Map<String, String> headers) {
        this.id = id;
        this.source = new IsoPageCache(new HttpRangeIsoSource(url, headers));
    }

    long id() {
        return id;
    }

    long length() throws IOException {
        ensureOpen();
        return source.length();
    }

    int readAt(long offset, ByteBuffer target, int length) throws IOException {
        ensureOpen();
        int wanted = Math.min(length, target.remaining());
        byte[] data = new byte[wanted];
        int read = source.readAt(offset, data, 0, wanted);
        if (read > 0) target.put(data, 0, read);
        return read;
    }

    void close() {
        if (closed.compareAndSet(false, true)) source.close();
    }

    private void ensureOpen() throws IsoSourceException {
        if (closed.get()) throw new IsoSourceException(IsoSourceException.Reason.CLOSED, "ISO session closed");
    }
}
