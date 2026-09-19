package com.fongmi.android.tv.bean;

import android.text.TextUtils;

import com.google.gson.annotations.SerializedName;

/**
 * 资源社区「一键提取」得到的订阅源候选条目。
 * type：0=点播源，1=直播源；count 为站点/频道数（未知为 0）；reachable 为连通性探测结果。
 */
public class CommunitySource {

    @SerializedName("name")
    private String name;
    @SerializedName("url")
    private String url;
    @SerializedName("type")
    private int type;
    @SerializedName("count")
    private int count;
    @SerializedName("reachable")
    private boolean reachable;
    @SerializedName("desc")
    private String desc;
    // 仓库主推标记（站点数最多的点播源），仅提取结果弹窗展示，不参与序列化存储
    private transient boolean recommended;

    public CommunitySource() {
    }

    public CommunitySource(String name, String url, int type, int count, boolean reachable) {
        this.name = name;
        this.url = url;
        this.type = type;
        this.count = count;
        this.reachable = reachable;
    }

    public String getName() {
        return TextUtils.isEmpty(name) ? url : name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public int getType() {
        return type;
    }

    public void setType(int type) {
        this.type = type;
    }

    public int getCount() {
        return count;
    }

    public void setCount(int count) {
        this.count = count;
    }

    public boolean isReachable() {
        return reachable;
    }

    public void setReachable(boolean reachable) {
        this.reachable = reachable;
    }

    public String getDesc() {
        return desc;
    }

    public void setDesc(String desc) {
        this.desc = desc;
    }

    public boolean isRecommended() {
        return recommended;
    }

    public void setRecommended(boolean recommended) {
        this.recommended = recommended;
    }
}
