package com.fongmi.android.tv.utils;

import com.google.common.net.HttpHeaders;

import java.util.LinkedHashMap;
import java.util.Map;

public final class WebSniffHeaders {

    private WebSniffHeaders() {
    }

    public static Map<String, String> forPage(Map<String, String> headers) {
        Map<String, String> result = new LinkedHashMap<>();
        if (headers == null) return result;
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            if (key == null || value == null) continue;
            if (HttpHeaders.USER_AGENT.equalsIgnoreCase(key) && !isBrowserUserAgent(value)) continue;
            result.put(key, value);
        }
        return result;
    }

    static boolean isBrowserUserAgent(String value) {
        String lower = value == null ? "" : value.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("mozilla/") && (lower.contains("applewebkit/") || lower.contains("gecko/"));
    }
}
