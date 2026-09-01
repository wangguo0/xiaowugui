package com.fongmi.android.tv.bean;

import android.text.TextUtils;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.impl.Diffable;
import com.google.gson.annotations.SerializedName;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

public class Bangumi implements Diffable<Bangumi> {

    public static final int MONDAY = 1;
    public static final int SUNDAY = 7;

    @SerializedName("name")
    private String name;
    @SerializedName("pic")
    private String pic;
    @SerializedName("badge")
    private String badge;
    @SerializedName("episode")
    private String episode;
    @SerializedName("desc")
    private String desc;
    @SerializedName("cid")
    private String cid;
    @SerializedName("days")
    private List<Integer> days = new ArrayList<>();
    @SerializedName("source")
    private String source;

    public static List<Bangumi> from(String json) {
        Type type = new TypeToken<List<Bangumi>>() {}.getType();
        List<Bangumi> items = App.gson().fromJson(json, type);
        return items == null ? new ArrayList<>() : items;
    }

    public static String toJson(List<Bangumi> items) {
        return App.gson().toJson(items);
    }

    public String getName() {
        return safe(name);
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getPic() {
        return safe(pic);
    }

    public void setPic(String pic) {
        this.pic = pic;
    }

    public String getBadge() {
        return safe(badge);
    }

    public void setBadge(String badge) {
        this.badge = badge;
    }

    public String getEpisode() {
        return safe(episode);
    }

    public void setEpisode(String episode) {
        this.episode = episode;
    }

    public String getDesc() {
        return safe(desc);
    }

    public void setDesc(String desc) {
        this.desc = desc;
    }

    public String getCid() {
        return safe(cid);
    }

    public void setCid(String cid) {
        this.cid = cid;
    }

    public String getSource() {
        return safe(source);
    }

    public void setSource(String source) {
        this.source = source;
    }

    public List<Integer> getDays() {
        return days == null ? new ArrayList<>() : days;
    }

    public void setDays(List<Integer> days) {
        this.days = days == null ? new ArrayList<>() : new ArrayList<>(days);
    }

    public boolean contains(int day) {
        return getDays().contains(day);
    }

    public void addDay(int day) {
        if (day < MONDAY || day > SUNDAY) return;
        if (!getDays().contains(day)) getDays().add(day);
    }

    // 卡片右下角角标：优先用原始集数文案，缺失时从更新文案中提取
    public String getMark() {
        if (!TextUtils.isEmpty(getEpisode())) return getEpisode();
        int count = parseFromDesc();
        return count > 0 ? "更新至" + count + "集" : "";
    }

    private int parseFromDesc() {
        String text = getDesc();
        if (TextUtils.isEmpty(text)) return 0;
        int idx = text.indexOf("更新至");
        if (idx < 0) return 0;
        int i = idx + 3;
        int start = i;
        while (i < text.length() && Character.isDigit(text.charAt(i))) i++;
        if (start == i) return 0;
        try {
            return Integer.parseInt(text.substring(start, i));
        } catch (Exception e) {
            return 0;
        }
    }

    @Override
    public boolean isSameItem(Bangumi other) {
        return other != null && TextUtils.equals(getName(), other.getName());
    }

    @Override
    public boolean isSameContent(Bangumi other) {
        return other != null
                && TextUtils.equals(getPic(), other.getPic())
                && TextUtils.equals(getBadge(), other.getBadge())
                && TextUtils.equals(getEpisode(), other.getEpisode())
                && TextUtils.equals(getDesc(), other.getDesc())
                && getDays().equals(other.getDays());
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}
