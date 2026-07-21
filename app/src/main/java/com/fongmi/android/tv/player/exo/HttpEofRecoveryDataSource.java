package com.fongmi.android.tv.player.exo;

import android.net.Uri;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.TransferListener;

import com.github.catvod.crawler.SpiderDebug;

import java.io.IOException;
import java.net.ProtocolException;
import java.util.List;
import java.util.Map;

/** Reopens a truncated fixed-length HTTP response at the exact unread byte offset. */
final class HttpEofRecoveryDataSource implements DataSource {

    private static final int MAX_RECONNECTS_PER_OPEN = 2;

    private final DataSource upstream;
    private DataSpec dataSpec;
    private long bytesRead;
    private int reconnectCount;

    HttpEofRecoveryDataSource(DataSource upstream) {
        this.upstream = upstream;
    }

    @Override
    public void addTransferListener(TransferListener transferListener) {
        upstream.addTransferListener(transferListener);
    }

    @Override
    public long open(DataSpec dataSpec) throws IOException {
        this.dataSpec = dataSpec;
        this.bytesRead = 0;
        this.reconnectCount = 0;
        return upstream.open(dataSpec);
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        while (true) {
            try {
                int read = upstream.read(buffer, offset, length);
                if (read > 0) bytesRead += read;
                return read;
            } catch (IOException error) {
                if (!isRecoverableEof(error) || reconnectCount >= MAX_RECONNECTS_PER_OPEN || dataSpec == null) throw error;
                reconnectCount++;
                reconnectAtCurrentPosition();
            }
        }
    }

    @Nullable
    @Override
    public Uri getUri() {
        return upstream.getUri();
    }

    @Override
    public Map<String, List<String>> getResponseHeaders() {
        return upstream.getResponseHeaders();
    }

    @Override
    public void close() throws IOException {
        dataSpec = null;
        bytesRead = 0;
        reconnectCount = 0;
        upstream.close();
    }

    private void reconnectAtCurrentPosition() throws IOException {
        try {
            upstream.close();
        } catch (IOException ignored) {
        }
        long remaining = remainingLength();
        DataSpec retrySpec = remaining == C.LENGTH_UNSET ? dataSpec.subrange(bytesRead) : dataSpec.subrange(bytesRead, remaining);
        upstream.open(retrySpec);
        if (SpiderDebug.isEnabled()) SpiderDebug.log("exo-network", "unexpected EOF recovered offset=%d remaining=%d attempt=%d", retrySpec.position, remaining, reconnectCount);
    }

    private long remainingLength() {
        if (dataSpec == null || dataSpec.length == C.LENGTH_UNSET) return C.LENGTH_UNSET;
        return remainingLength(dataSpec.length, bytesRead);
    }

    static long remainingLength(long requestedLength, long bytesRead) {
        if (requestedLength == C.LENGTH_UNSET) return C.LENGTH_UNSET;
        return Math.max(0, requestedLength - Math.max(0, bytesRead));
    }

    static boolean isRecoverableEof(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof ProtocolException && messageContainsUnexpectedEnd(current.getMessage())) return true;
            current = current.getCause();
        }
        return false;
    }

    private static boolean messageContainsUnexpectedEnd(String message) {
        return message != null && message.toLowerCase(java.util.Locale.ROOT).contains("unexpected end of stream");
    }

    static final class Factory implements DataSource.Factory {
        private final DataSource.Factory upstreamFactory;

        Factory(DataSource.Factory upstreamFactory) {
            this.upstreamFactory = upstreamFactory;
        }

        @Override
        public DataSource createDataSource() {
            return new HttpEofRecoveryDataSource(upstreamFactory.createDataSource());
        }
    }
}
