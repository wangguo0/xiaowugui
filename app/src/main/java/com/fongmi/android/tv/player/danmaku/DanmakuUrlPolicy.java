package com.fongmi.android.tv.player.danmaku;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

public final class DanmakuUrlPolicy {

    private static final int MAX_LOG_PATH_LENGTH = 64;
    private static final int MAX_LOG_QUERY_VALUE_LENGTH = 32;

    private DanmakuUrlPolicy() {
    }

    public static SourceType classify(String url) {
        if (url == null || url.isBlank()) return SourceType.EMPTY;
        try {
            URI uri = URI.create(url.trim());
            String scheme = lower(uri.getScheme());
            if ("http".equals(scheme) || "https".equals(scheme)) return validNetworkUri(uri) ? SourceType.STATIC : SourceType.UNSUPPORTED;
            if ("ws".equals(scheme) || "wss".equals(scheme)) return validNetworkUri(uri) ? SourceType.LIVE : SourceType.UNSUPPORTED;
            if ("file".equals(scheme)) return uri.getPath() == null || uri.getPath().isBlank() ? SourceType.UNSUPPORTED : SourceType.STATIC;
            if ("content".equals(scheme)) return uri.getRawAuthority() == null || uri.getRawAuthority().isBlank() ? SourceType.UNSUPPORTED : SourceType.STATIC;
            return SourceType.UNSUPPORTED;
        } catch (IllegalArgumentException e) {
            return SourceType.UNSUPPORTED;
        }
    }

    public static String normalize(String apiUrl, String sourceUrl) {
        if (sourceUrl == null || sourceUrl.isBlank()) return "";
        String source = sourceUrl.trim();
        if (apiUrl == null || apiUrl.isBlank()) return source;
        try {
            URI api = URI.create(apiUrl.trim());
            URI result = URI.create(source);
            if (!"https".equalsIgnoreCase(api.getScheme()) || !sameAuthority(api, result)) return source;
            String scheme = lower(result.getScheme());
            if ("http".equals(scheme)) return withScheme(result, "https");
            if ("ws".equals(scheme)) return withScheme(result, "wss");
            return source;
        } catch (IllegalArgumentException | URISyntaxException e) {
            return source;
        }
    }

    public static String logSummary(String url) {
        if (url == null || url.isBlank()) return "source=EMPTY";
        String value = url.trim();
        SourceType type = classify(value);
        try {
            URI uri = URI.create(value);
            StringBuilder builder = new StringBuilder();
            builder.append("source=").append(type);
            builder.append(" scheme=").append(safeScheme(uri.getScheme()));
            builder.append(" host=").append(safeHost(uri.getHost()));
            if (uri.getPort() > 0) builder.append(" port=").append(uri.getPort());
            String path = uri.getRawPath();
            if (path != null && !path.isBlank()) builder.append(" path=").append(limit(path, MAX_LOG_PATH_LENGTH));
            appendAllowedQuery(builder, uri.getRawQuery(), "site");
            appendAllowedQuery(builder, uri.getRawQuery(), "room_id");
            builder.append(" len=").append(value.length());
            return builder.toString();
        } catch (IllegalArgumentException e) {
            return "source=UNSUPPORTED scheme=other host=unknown len=" + value.length();
        }
    }

    public static boolean isLoopback(String url) {
        if (url == null || url.isBlank()) return false;
        try {
            String host = URI.create(url.trim()).getHost();
            return host != null && ("localhost".equalsIgnoreCase(host)
                    || "127.0.0.1".equals(host)
                    || "::1".equals(host)
                    || "0:0:0:0:0:0:0:1".equals(host));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static boolean validNetworkUri(URI uri) {
        String host = uri.getHost();
        int port = uri.getPort();
        return host != null && !host.isBlank() && uri.getRawUserInfo() == null && port <= 65535;
    }

    private static boolean sameAuthority(URI first, URI second) {
        String firstHost = first.getHost();
        String secondHost = second.getHost();
        return firstHost != null
                && secondHost != null
                && firstHost.equalsIgnoreCase(secondHost)
                && first.getPort() == second.getPort();
    }

    private static String withScheme(URI uri, String scheme) throws URISyntaxException {
        return new URI(scheme, uri.getRawUserInfo(), uri.getHost(), uri.getPort(), uri.getRawPath(), uri.getRawQuery(), uri.getRawFragment()).toString();
    }

    private static void appendAllowedQuery(StringBuilder builder, String rawQuery, String name) {
        if (rawQuery == null || rawQuery.isBlank()) return;
        for (String part : rawQuery.split("&")) {
            int separator = part.indexOf('=');
            String key = separator < 0 ? part : part.substring(0, separator);
            if (!name.equalsIgnoreCase(key)) continue;
            String queryValue = separator < 0 ? "" : part.substring(separator + 1);
            builder.append(' ').append(name).append('=').append(safeQueryValue(queryValue));
            return;
        }
    }

    private static String safeQueryValue(String value) {
        if (value == null || value.isBlank()) return "empty";
        String limited = limit(value, MAX_LOG_QUERY_VALUE_LENGTH);
        return limited.matches("[A-Za-z0-9._%+-]+") ? limited : "redacted";
    }

    private static String safeScheme(String scheme) {
        String value = lower(scheme);
        return switch (value) {
            case "http", "https", "ws", "wss", "file", "content" -> value;
            default -> "other";
        };
    }

    private static String safeHost(String host) {
        if (host == null || host.isBlank()) return "unknown";
        return limit(host, 96);
    }

    private static String limit(String value, int maxLength) {
        return value.length() <= maxLength ? value : value.substring(0, maxLength) + "...";
    }

    private static String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    public enum SourceType {
        EMPTY,
        STATIC,
        LIVE,
        UNSUPPORTED;

        public boolean isStatic() {
            return this == STATIC;
        }

        public boolean isLive() {
            return this == LIVE;
        }

        public boolean isSupported() {
            return isStatic() || isLive();
        }
    }
}
