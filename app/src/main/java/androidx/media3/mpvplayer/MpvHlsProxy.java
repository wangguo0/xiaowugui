package androidx.media3.mpvplayer;

import android.text.TextUtils;

import androidx.annotation.Nullable;

import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.net.OkHttp;

import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import fi.iki.elonen.NanoHTTPD;
import fi.iki.elonen.NanoHTTPD.Response.Status;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.ResponseBody;

public final class MpvHlsProxy extends NanoHTTPD {

    private static final String TAG = "mpv-proxy";
    private static final String MIME_M3U8 = "application/vnd.apple.mpegurl; charset=utf-8";
    private static final String MIME_TS = "video/MP2T";
    private static final String MIME_BINARY = "application/octet-stream";
    private static final String PLAYLIST_RANGE = "bytes=0-";
    private static final int PREFIX_SCAN_LIMIT = 64 * 1024;
    private static final long SESSION_TTL_MS = TimeUnit.MINUTES.toMillis(3);
    private static final byte[] PNG_SIGNATURE = new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
    private static final byte[] PNG_IEND = new byte[]{0x49, 0x45, 0x4E, 0x44, (byte) 0xAE, 0x42, 0x60, (byte) 0x82};
    private static final Pattern URI_ATTR = Pattern.compile("URI=\"([^\"]+)\"");

    private final OkHttpClient client;
    private final Map<Integer, Session> sessions;
    private final Map<String, Target> targets;
    private final AtomicLong nextId;
    private volatile int sessionId;
    private volatile boolean started;

    public MpvHlsProxy() {
        super("127.0.0.1", 0);
        client = OkHttp.player().newBuilder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();
        sessions = new ConcurrentHashMap<>();
        targets = new ConcurrentHashMap<>();
        nextId = new AtomicLong();
    }

    public synchronized String proxy(String url, Map<String, String> headers) throws IOException {
        ensureStarted();
        int id = ++this.sessionId;
        Session session = new Session(url, sanitize(headers), System.currentTimeMillis());
        sessions.put(id, session);
        pruneExpiredSessions(session.createdAtMs);
        String proxyUrl = baseUrl() + "/mpv/index.m3u8?s=" + sessionId;
        SpiderDebug.log(TAG, "enabled session=%d url=%s headers=%s proxy=%s", sessionId, shortUrl(url), session.headers.keySet(), proxyUrl);
        return proxyUrl;
    }

    public synchronized void clear() {
        sessions.clear();
        targets.clear();
    }

    public synchronized void release() {
        clear();
        if (started) stop();
        started = false;
    }

    @Override
    public Response serve(IHTTPSession session) {
        try {
            String path = session.getUri();
            if (path == null) return error(Status.NOT_FOUND, "missing path");
            if (path.startsWith("/mpv/index.m3u8")) return servePlaylist(session);
            if (path.startsWith("/mpv/item")) return serveItem(session);
            return error(Status.NOT_FOUND, "not found");
        } catch (Throwable e) {
            SpiderDebug.log(TAG, e);
            return error(Status.INTERNAL_ERROR, e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }

    private void ensureStarted() throws IOException {
        if (started) return;
        start(NanoHTTPD.SOCKET_READ_TIMEOUT, true);
        started = true;
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + getListeningPort();
    }

    private Response servePlaylist(IHTTPSession httpSession) throws IOException {
        int id = parseSessionId(httpSession);
        Session session = sessions.get(id);
        if (session == null || TextUtils.isEmpty(session.url)) return error(Status.NOT_FOUND, "expired playlist");
        try (okhttp3.Response response = fetch(session, session.url, PLAYLIST_RANGE)) {
            ResponseBody body = response.body();
            if (body == null) return error(Status.INTERNAL_ERROR, "empty playlist body");
            String text = body.string();
            if (!looksLikePlaylist(text)) {
                SpiderDebug.log(TAG, "invalid playlist session=%d code=%d bytes=%d url=%s", id, response.code(), text.length(), shortUrl(session.url));
                return error(Status.BAD_REQUEST, "invalid playlist");
            }
            String rewritten = rewritePlaylist(response.request().url().toString(), text, id);
            byte[] data = rewritten.getBytes(StandardCharsets.UTF_8);
            SpiderDebug.log(TAG, "playlist session=%d code=%d bytes=%d rewritten=%d url=%s", id, response.code(), text.length(), data.length, shortUrl(session.url));
            return noCache(newFixedLengthResponse(Status.OK, MIME_M3U8, new ByteArrayInputStream(data), data.length));
        }
    }

    private Response serveItem(IHTTPSession session) throws IOException {
        String id = session.getParms().get("id");
        Target target = id == null ? null : targets.get(id);
        Session owner = target == null ? null : sessions.get(target.sessionId);
        if (target == null || owner == null) return error(Status.NOT_FOUND, "expired item");
        okhttp3.Response response = fetch(owner, target.url, isPlaylistUrl(target.url, null) ? PLAYLIST_RANGE : null);
        ResponseBody body = response.body();
        if (body == null) {
            response.close();
            return error(Status.INTERNAL_ERROR, "empty item body");
        }
        String finalUrl = response.request().url().toString();
        MediaType type = body.contentType();
        if (isPlaylistUrl(target.url, type) || isPlaylistUrl(finalUrl, type)) {
            try (response; body) {
                String text = body.string();
                if (!looksLikePlaylist(text)) {
                    SpiderDebug.log(TAG, "invalid nested playlist id=%s code=%d bytes=%d url=%s", id, response.code(), text.length(), shortUrl(target.url));
                    return error(Status.BAD_REQUEST, "invalid playlist");
                }
                String rewritten = rewritePlaylist(finalUrl, text, target.sessionId);
                byte[] data = rewritten.getBytes(StandardCharsets.UTF_8);
                SpiderDebug.log(TAG, "nested playlist id=%s code=%d bytes=%d url=%s", id, response.code(), data.length, shortUrl(target.url));
                return noCache(newFixedLengthResponse(Status.OK, MIME_M3U8, new ByteArrayInputStream(data), data.length));
            }
        }

        InputStream stream = new CloseResponseInputStream(new PngPrefixStrippingInputStream(body.byteStream(), target.url), response);
        Response result = newChunkedResponse(toStatus(response.code()), mediaMime(type), stream);
        result.addHeader("Access-Control-Allow-Origin", "*");
        result.addHeader("Cache-Control", "no-cache");
        result.addHeader("Connection", "close");
        return result;
    }

    private okhttp3.Response fetch(Session session, String url, @Nullable String range) throws IOException {
        Request.Builder builder = new Request.Builder().url(url);
        for (Map.Entry<String, String> entry : session.headers.entrySet()) {
            if (TextUtils.isEmpty(entry.getKey()) || TextUtils.isEmpty(entry.getValue())) continue;
            builder.header(entry.getKey(), entry.getValue());
        }
        if (!TextUtils.isEmpty(range)) builder.header("Range", range);
        return client.newCall(builder.build()).execute();
    }

    private String rewritePlaylist(String playlistUrl, String text, int session) {
        String[] lines = text.split("\n", -1);
        StringBuilder out = new StringBuilder(text.length() + 256);
        for (int i = 0; i < lines.length; i++) {
            String raw = trimCr(lines[i]);
            String line = raw.trim();
            if (line.startsWith("#") && line.contains("URI=\"")) {
                out.append(rewriteUriAttributes(playlistUrl, raw, session));
            } else if (!line.isEmpty() && !line.startsWith("#")) {
                out.append(proxyItemUrl(resolve(playlistUrl, line), session));
            } else {
                out.append(raw);
            }
            if (i < lines.length - 1) out.append('\n');
        }
        return out.toString();
    }

    private String rewriteUriAttributes(String playlistUrl, String line, int session) {
        Matcher matcher = URI_ATTR.matcher(line);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            String value = matcher.group(1);
            String replacement = value;
            if (!TextUtils.isEmpty(value) && !value.startsWith("data:")) {
                replacement = proxyItemUrl(resolve(playlistUrl, value), session);
            }
            matcher.appendReplacement(buffer, "URI=\"" + Matcher.quoteReplacement(replacement) + "\"");
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }

    private String proxyItemUrl(String targetUrl, int session) {
        String id = Long.toString(nextId.incrementAndGet());
        targets.put(id, new Target(session, targetUrl, System.currentTimeMillis()));
        return baseUrl() + "/mpv/item?s=" + session + "&id=" + id;
    }

    private int parseSessionId(IHTTPSession session) {
        try {
            return Integer.parseInt(session.getParms().get("s"));
        } catch (Throwable e) {
            return sessionId;
        }
    }

    private void pruneExpiredSessions(long now) {
        for (Map.Entry<Integer, Session> entry : sessions.entrySet()) {
            if (entry.getKey() == sessionId) continue;
            if (now - entry.getValue().createdAtMs > SESSION_TTL_MS) sessions.remove(entry.getKey());
        }
        for (Map.Entry<String, Target> entry : targets.entrySet()) {
            Target target = entry.getValue();
            if (sessions.containsKey(target.sessionId)) continue;
            if (now - target.createdAtMs > SESSION_TTL_MS) targets.remove(entry.getKey());
        }
    }

    private String resolve(String baseUrl, String uri) {
        try {
            URI parsed = URI.create(uri);
            if (parsed.isAbsolute()) return uri;
            return URI.create(baseUrl).resolve(parsed).toString();
        } catch (Throwable e) {
            return uri;
        }
    }

    private static String trimCr(String value) {
        return value.endsWith("\r") ? value.substring(0, value.length() - 1) : value;
    }

    private static boolean isPlaylistUrl(String url, @Nullable MediaType type) {
        String mime = type == null ? "" : type.toString().toLowerCase(Locale.US);
        if (mime.contains("mpegurl") || mime.contains("m3u8")) return true;
        String lower = url == null ? "" : url.toLowerCase(Locale.US);
        int query = lower.indexOf('?');
        if (query >= 0) lower = lower.substring(0, query);
        return lower.endsWith(".m3u8") || lower.endsWith(".m3u");
    }

    private static boolean looksLikePlaylist(String text) {
        if (TextUtils.isEmpty(text)) return false;
        String value = text.trim();
        return value.startsWith("#EXTM3U") || value.contains("\n#EXTM3U");
    }

    private static String mediaMime(@Nullable MediaType type) {
        String value = type == null ? "" : type.toString();
        if (value.toLowerCase(Locale.US).contains("image/png")) return MIME_TS;
        return TextUtils.isEmpty(value) ? MIME_BINARY : value;
    }

    private static Response noCache(Response response) {
        response.addHeader("Access-Control-Allow-Origin", "*");
        response.addHeader("Cache-Control", "no-cache");
        response.addHeader("Connection", "close");
        return response;
    }

    private static Response error(Status status, String text) {
        return noCache(newFixedLengthResponse(status, MIME_PLAINTEXT, text == null ? "" : text));
    }

    private static Response.IStatus toStatus(int code) {
        Status status = Status.lookup(code);
        return status != null ? status : Status.OK;
    }

    private static Map<String, String> sanitize(Map<String, String> input) {
        Map<String, String> result = new LinkedHashMap<>();
        if (input == null) return result;
        for (Map.Entry<String, String> entry : input.entrySet()) {
            if (TextUtils.isEmpty(entry.getKey()) || TextUtils.isEmpty(entry.getValue())) continue;
            result.put(entry.getKey().trim(), entry.getValue().trim());
        }
        return result;
    }

    private static String shortUrl(String value) {
        if (value == null || value.length() <= 120) return value;
        return value.substring(0, 120) + "...";
    }

    private record Session(String url, Map<String, String> headers, long createdAtMs) {
    }

    private record Target(int sessionId, String url, long createdAtMs) {
    }

    private static final class CloseResponseInputStream extends FilterInputStream {

        private final okhttp3.Response response;

        CloseResponseInputStream(InputStream in, okhttp3.Response response) {
            super(in);
            this.response = response;
        }

        @Override
        public void close() throws IOException {
            try {
                super.close();
            } finally {
                response.close();
            }
        }
    }

    private static final class PngPrefixStrippingInputStream extends InputStream {

        private final InputStream upstream;
        private final String url;
        private byte[] prefix;
        private int prefixOffset;
        private int prefixLength;
        private boolean initialized;

        PngPrefixStrippingInputStream(InputStream upstream, String url) {
            this.upstream = upstream;
            this.url = url;
        }

        @Override
        public int read() throws IOException {
            ensureInitialized();
            if (prefixOffset < prefixLength) return prefix[prefixOffset++] & 0xFF;
            return upstream.read();
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            ensureInitialized();
            if (prefixOffset < prefixLength) {
                int count = Math.min(length, prefixLength - prefixOffset);
                System.arraycopy(prefix, prefixOffset, buffer, offset, count);
                prefixOffset += count;
                return count;
            }
            return upstream.read(buffer, offset, length);
        }

        @Override
        public void close() throws IOException {
            upstream.close();
        }

        private void ensureInitialized() throws IOException {
            if (initialized) return;
            initialized = true;
            prefix = readPrefix();
            prefixLength = prefix.length;
            int stripOffset = pngStripOffset(prefix);
            if (stripOffset > 0 && stripOffset < prefixLength && looksLikeTransportStream(prefix, stripOffset)) {
                prefixOffset = stripOffset;
                SpiderDebug.log(TAG, "strip png prefix offset=%d prefixBytes=%d url=%s", stripOffset, prefixLength, shortUrl(url));
            } else {
                prefixOffset = 0;
            }
        }

        private byte[] readPrefix() throws IOException {
            byte[] buffer = new byte[PREFIX_SCAN_LIMIT];
            int length = 0;
            while (length < buffer.length) {
                int read = upstream.read(buffer, length, buffer.length - length);
                if (read == -1) break;
                length += read;
                if (length >= PNG_SIGNATURE.length && !startsWith(buffer, length, PNG_SIGNATURE)) break;
                int offset = pngStripOffset(buffer, length);
                if (offset > 0 && length > offset + 188) break;
            }
            byte[] result = new byte[length];
            System.arraycopy(buffer, 0, result, 0, length);
            return result;
        }

        private static int pngStripOffset(byte[] data) {
            return pngStripOffset(data, data.length);
        }

        private static int pngStripOffset(byte[] data, int length) {
            if (!startsWith(data, length, PNG_SIGNATURE)) return -1;
            int iend = indexOf(data, length, PNG_IEND);
            return iend < 0 ? -1 : iend + PNG_IEND.length;
        }

        private static boolean looksLikeTransportStream(byte[] data, int offset) {
            if (offset >= data.length || data[offset] != 0x47) return false;
            if (offset + 188 < data.length && data[offset + 188] == 0x47) return true;
            if (offset + 376 < data.length && data[offset + 376] == 0x47) return true;
            return true;
        }

        private static boolean startsWith(byte[] data, int length, byte[] prefix) {
            if (length < prefix.length) return false;
            for (int i = 0; i < prefix.length; i++) if (data[i] != prefix[i]) return false;
            return true;
        }

        private static int indexOf(byte[] data, int length, byte[] needle) {
            int end = length - needle.length;
            for (int i = 0; i <= end; i++) {
                boolean match = true;
                for (int j = 0; j < needle.length; j++) {
                    if (data[i + j] != needle[j]) {
                        match = false;
                        break;
                    }
                }
                if (match) return i;
            }
            return -1;
        }
    }
}
