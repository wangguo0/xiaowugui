package com.fongmi.android.tv.playback;

import android.text.TextUtils;

import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public class WebhookConfig {

    public static final String PRESET_SAFE = "safe";
    public static final String PRESET_STANDARD = "standard";
    public static final String PRESET_ANONYMOUS = "anonymous";
    public static final String PRESET_CUSTOM = "custom";

    public String id;
    public String name;
    public boolean enabled;
    public boolean suspended;
    public String url;
    public List<String> events;
    public List<String> siteKeys;
    public String fieldPreset;
    public List<String> fields;
    public String token;
    @Deprecated
    public String secret;
    @Deprecated
    public String keyId;
    public int progressIntervalSec;
    public int maxRetries;
    public int failureCount;
    public long lastFailureAt;
    public String lastError;

    public WebhookConfig() {
        this.id = UUID.randomUUID().toString();
        this.name = "";
        this.enabled = true;
        this.suspended = false;
        this.url = "";
        this.events = defaults();
        this.siteKeys = new ArrayList<>();
        this.fieldPreset = PRESET_SAFE;
        this.fields = new ArrayList<>();
        this.token = "";
        this.secret = "";
        this.keyId = "";
        this.progressIntervalSec = 30;
        this.maxRetries = 2;
    }

    public boolean isUsable() {
        return enabled && !suspended && !TextUtils.isEmpty(url) && url.startsWith("http");
    }

    public boolean acceptsEvent(String event) {
        if (TextUtils.isEmpty(event)) return false;
        if (events == null || events.isEmpty()) return true;
        String token = token(event);
        for (String item : events) {
            String value = normalize(item);
            if (event.equals(value) || token.equals(value)) return true;
        }
        return false;
    }

    public boolean matchesSite(String siteKey) {
        if (siteKeys == null || siteKeys.isEmpty()) return true;
        for (String item : siteKeys) if (TextUtils.equals(siteKey, normalize(item))) return true;
        return false;
    }

    public String displayName() {
        if (!TextUtils.isEmpty(name)) return name;
        String host = host(url);
        if (!TextUtils.isEmpty(host)) return host;
        if (!TextUtils.isEmpty(url)) return url;
        return "Webhook";
    }

    public static List<String> defaults() {
        return new ArrayList<>(Arrays.asList("progress", "ended"));
    }

    private static String host(String value) {
        try {
            URI uri = URI.create(value == null ? "" : value.trim());
            return uri.getHost() == null ? "" : uri.getHost();
        } catch (Exception e) {
            return "";
        }
    }

    public static String token(String event) {
        String value = normalize(event);
        int index = value.lastIndexOf('.');
        return index >= 0 ? value.substring(index + 1) : value;
    }

    public static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
