package com.fongmi.android.tv.server.process;

import com.fongmi.android.tv.api.loader.BaseLoader;
import com.fongmi.android.tv.server.Nano;
import com.fongmi.android.tv.server.impl.Process;
import com.github.catvod.crawler.SpiderDebug;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import fi.iki.elonen.NanoHTTPD;
import fi.iki.elonen.NanoHTTPD.IHTTPSession;
import fi.iki.elonen.NanoHTTPD.Response;
import fi.iki.elonen.NanoHTTPD.Response.Status;

public class Proxy implements Process {

    private static final AtomicLong STREAM_ID = new AtomicLong();
    private static final long PROGRESS_INTERVAL_NS = TimeUnit.SECONDS.toNanos(5);

    @Override
    public boolean isRequest(IHTTPSession session, String url) {
        return url.startsWith("/proxy");
    }

    @Override
    public Response doResponse(IHTTPSession session, String url, Map<String, String> files) {
        try {
            Map<String, String> params = session.getParms();
            params.putAll(session.getHeaders());
            params.putAll(files);
            SpiderDebug.log("proxy", "request uri=%s method=%s do=%s params=%s", url, session.getMethod(), params.get("do"), params);
            Object[] rs = BaseLoader.get().proxy(params);
            if (rs == null) {
                SpiderDebug.log("proxy", "response null do=%s uri=%s", params.get("do"), url);
                return Nano.error("Proxy response is null");
            }
            if (rs[0] instanceof Response) {
                SpiderDebug.log("proxy", "response object do=%s type=%s", params.get("do"), rs[0].getClass().getName());
                return (Response) rs[0];
            }
            SpiderDebug.log("proxy", "response do=%s status=%s mime=%s body=%s headers=%s", params.get("do"), rs.length > 0 ? rs[0] : null, rs.length > 1 ? rs[1] : null, rs.length > 2 && rs[2] != null ? rs[2].getClass().getName() : null, rs.length > 3 ? rs[3] : null);
            Map<String, String> headers = headers(rs);
            InputStream stream = wrapStream(params, headers, (InputStream) rs[2]);
            Response response = NanoHTTPD.newChunkedResponse(Status.lookup((Integer) rs[0]), (String) rs[1], stream);
            if (headers != null) for (Map.Entry<String, String> entry : headers.entrySet()) response.addHeader(entry.getKey(), entry.getValue());
            return response;
        } catch (Throwable e) {
            e.printStackTrace();
            SpiderDebug.log("proxy", e);
            return Nano.error(e.getMessage());
        }
    }

    private InputStream wrapStream(Map<String, String> params, Map<String, String> headers, InputStream stream) {
        if (stream == null || !SpiderDebug.isEnabled()) return stream;
        String id = String.valueOf(STREAM_ID.incrementAndGet());
        String range = first(params.get("range"), params.get("Range"));
        String contentLength = header(headers, "Content-Length");
        String contentRange = header(headers, "Content-Range");
        String label = label(params);
        SpiderDebug.log("proxy-stream", "open id=%s do=%s site=%s range=%s length=%s contentRange=%s", id, params.get("do"), params.get("siteKey"), empty(range), empty(contentLength), empty(contentRange));
        return new DebugInputStream(stream, id, label);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> headers(Object[] rs) {
        return rs.length > 3 && rs[3] instanceof Map ? (Map<String, String>) rs[3] : null;
    }

    private static String label(Map<String, String> params) {
        return shorten(first(params.get("url"), params.get("playUrl"), params.get("thread"), params.get("siteKey")), 120);
    }

    private static String header(Map<String, String> headers, String name) {
        if (headers == null || name == null) return null;
        for (Map.Entry<String, String> entry : headers.entrySet()) if (name.equalsIgnoreCase(entry.getKey())) return entry.getValue();
        return null;
    }

    private static String first(String... values) {
        if (values == null) return null;
        for (String value : values) if (value != null && !value.isEmpty()) return value;
        return null;
    }

    private static String empty(String value) {
        return value == null || value.isEmpty() ? "-" : value;
    }

    private static String shorten(String value, int max) {
        if (value == null) return "-";
        return value.length() <= max ? value : value.substring(0, max) + "...";
    }

    private static class DebugInputStream extends FilterInputStream {

        private final String id;
        private final String label;
        private final long startNs;
        private long lastLogNs;
        private long bytes;
        private boolean firstByte;
        private boolean closed;

        private DebugInputStream(InputStream in, String id, String label) {
            super(in);
            this.id = id;
            this.label = label;
            this.startNs = System.nanoTime();
            this.lastLogNs = startNs;
        }

        @Override
        public int read() throws IOException {
            try {
                int read = super.read();
                if (read != -1) onRead(1);
                return read;
            } catch (IOException e) {
                onError(e);
                throw e;
            }
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            try {
                int read = super.read(b, off, len);
                if (read > 0) onRead(read);
                return read;
            } catch (IOException e) {
                onError(e);
                throw e;
            }
        }

        @Override
        public void close() throws IOException {
            if (!closed) {
                closed = true;
                log("close");
            }
            super.close();
        }

        private void onRead(int count) {
            bytes += count;
            long now = System.nanoTime();
            if (!firstByte) {
                firstByte = true;
                SpiderDebug.log("proxy-stream", "firstByte id=%s latency=%dms label=%s", id, elapsedMs(now), label);
            }
            if (now - lastLogNs >= PROGRESS_INTERVAL_NS) {
                lastLogNs = now;
                log("progress");
            }
        }

        private void onError(IOException e) {
            SpiderDebug.log("proxy-stream", "error id=%s bytes=%d elapsed=%dms speed=%.2fMiB/s error=%s", id, bytes, elapsedMs(System.nanoTime()), speedMiB(System.nanoTime()), e.getMessage());
        }

        private void log(String event) {
            long now = System.nanoTime();
            SpiderDebug.log("proxy-stream", "%s id=%s bytes=%d elapsed=%dms speed=%.2fMiB/s label=%s", event, id, bytes, elapsedMs(now), speedMiB(now), label);
        }

        private long elapsedMs(long now) {
            return TimeUnit.NANOSECONDS.toMillis(now - startNs);
        }

        private double speedMiB(long now) {
            long elapsedNs = Math.max(1, now - startNs);
            return bytes / 1024.0 / 1024.0 / (elapsedNs / 1_000_000_000.0);
        }
    }
}
