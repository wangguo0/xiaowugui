package com.fongmi.android.tv.bean;

import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.utils.UrlUtil;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.annotations.SerializedName;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

public class Danmaku {

    @SerializedName("name")
    private String name;
    @SerializedName("url")
    private String url;

    private boolean selected;

    public static List<Danmaku> arrayFrom(String str) {
        if (TextUtils.isEmpty(str)) return Collections.emptyList();
        Type listType = TypeToken.getParameterized(List.class, Danmaku.class).getType();
        str = str.trim();
        try {
            return arrayFrom(JsonParser.parseString(str), listType);
        } catch (Exception e) {
            return filter(List.of(Danmaku.from(str)));
        }
    }

    private static List<Danmaku> arrayFrom(JsonElement element, Type listType) {
        if (element == null || element.isJsonNull()) return Collections.emptyList();
        if (element.isJsonArray()) return filter(App.gson().fromJson(element, listType));
        if (element.isJsonPrimitive()) return arrayFromPrimitive(element.getAsString(), listType);
        if (!element.isJsonObject()) return Collections.emptyList();
        JsonObject object = element.getAsJsonObject();
        for (String key : new String[]{"data", "list", "result", "results", "items", "danmakus", "danmaku"}) {
            if (object.has(key)) {
                List<Danmaku> items = arrayFrom(object.get(key), listType);
                if (!items.isEmpty()) return items;
            }
        }
        return filter(List.of(App.gson().fromJson(object, Danmaku.class)));
    }

    private static List<Danmaku> arrayFromPrimitive(String text, Type listType) {
        text = TextUtils.isEmpty(text) ? "" : text.trim();
        if (text.isEmpty()) return Collections.emptyList();
        if (text.startsWith("[") || text.startsWith("{")) return arrayFrom(JsonParser.parseString(text), listType);
        return filter(List.of(Danmaku.from(text)));
    }

    private static List<Danmaku> filter(List<Danmaku> items) {
        if (items == null) return Collections.emptyList();
        return items.stream().filter(item -> item != null && !item.isEmpty()).collect(Collectors.toCollection(ArrayList::new));
    }

    public static Danmaku from(String path) {
        Danmaku danmaku = new Danmaku();
        danmaku.setName(path);
        danmaku.setUrl(path);
        return danmaku;
    }

    public static Danmaku empty() {
        return new Danmaku();
    }

    public String getName() {
        return TextUtils.isEmpty(name) ? getUrl() : name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getUrl() {
        return TextUtils.isEmpty(url) ? "" : url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public boolean isSelected() {
        return selected;
    }

    public void setSelected(boolean selected) {
        this.selected = selected;
    }

    public boolean isEmpty() {
        return getUrl().isEmpty();
    }

    public String getRealUrl() {
        return UrlUtil.convert(getUrl().startsWith("/") ? "file:/" + getUrl() : getUrl());
    }

    @Override
    public boolean equals(@Nullable Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof Danmaku it)) return false;
        return getUrl().equals(it.getUrl());
    }

    @NonNull
    @Override
    public String toString() {
        return App.gson().toJson(this);
    }
}
