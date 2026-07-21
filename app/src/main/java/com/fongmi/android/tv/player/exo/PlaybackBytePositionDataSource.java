package com.fongmi.android.tv.player.exo;

import android.net.Uri;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.TransferListener;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class PlaybackBytePositionDataSource implements DataSource {

    private static final Pattern CONTENT_RANGE = Pattern.compile("bytes\\s+\\d+-\\d+/(\\d+|\\*)", Pattern.CASE_INSENSITIVE);
    private static final Object LOCK = new Object();
    private static long sequence;
    private static long positionBytes = C.POSITION_UNSET;
    private static long contentLengthBytes;

    private final DataSource upstream;
    private long nextPositionBytes;

    PlaybackBytePositionDataSource(DataSource upstream) {
        this.upstream = upstream;
    }

    static void resetSession() {
        synchronized (LOCK) {
            sequence++;
            positionBytes = C.POSITION_UNSET;
            contentLengthBytes = 0;
        }
    }

    static Snapshot snapshot() {
        synchronized (LOCK) {
            return new Snapshot(sequence, positionBytes, contentLengthBytes);
        }
    }

    @Override
    public void addTransferListener(TransferListener transferListener) {
        upstream.addTransferListener(transferListener);
    }

    @Override
    public long open(DataSpec dataSpec) throws IOException {
        long length = upstream.open(dataSpec);
        nextPositionBytes = Math.max(0, dataSpec.position);
        long totalLength = resolveTotalLength(dataSpec, length, upstream.getResponseHeaders());
        synchronized (LOCK) {
            sequence++;
            positionBytes = nextPositionBytes;
            if (totalLength > 0) contentLengthBytes = totalLength;
        }
        return length;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        int read = upstream.read(buffer, offset, length);
        if (read > 0) {
            nextPositionBytes += read;
            synchronized (LOCK) {
                positionBytes = nextPositionBytes;
            }
        }
        return read;
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
        upstream.close();
    }

    static long parseContentRangeTotal(Map<String, List<String>> headers) {
        if (headers == null || headers.isEmpty()) return 0;
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            if (entry.getKey() == null || !"Content-Range".equalsIgnoreCase(entry.getKey())) continue;
            for (String value : entry.getValue()) {
                if (value == null) continue;
                Matcher matcher = CONTENT_RANGE.matcher(value.trim());
                if (!matcher.matches() || "*".equals(matcher.group(1))) continue;
                try {
                    return Long.parseLong(matcher.group(1));
                } catch (NumberFormatException ignored) {
                    return 0;
                }
            }
        }
        return 0;
    }

    private static long resolveTotalLength(DataSpec dataSpec, long openedLength, Map<String, List<String>> headers) {
        long contentRangeTotal = parseContentRangeTotal(headers);
        if (contentRangeTotal > 0) return contentRangeTotal;
        if (dataSpec.length != C.LENGTH_UNSET) return dataSpec.position == 0 ? dataSpec.length : 0;
        if (openedLength == C.LENGTH_UNSET || openedLength <= 0 || dataSpec.position > Long.MAX_VALUE - openedLength) return 0;
        return dataSpec.position + openedLength;
    }

    record Snapshot(long sequence, long positionBytes, long contentLengthBytes) {
    }

    static final class Factory implements DataSource.Factory {

        private final DataSource.Factory upstreamFactory;

        Factory(DataSource.Factory upstreamFactory) {
            this.upstreamFactory = upstreamFactory;
        }

        @Override
        public DataSource createDataSource() {
            return new PlaybackBytePositionDataSource(upstreamFactory.createDataSource());
        }
    }
}
