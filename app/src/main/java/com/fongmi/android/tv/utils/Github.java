package com.fongmi.android.tv.utils;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;

public class Github {

    private static final String GITHUB_LATEST = "https://github.com/wangguo0/xiaowugui/releases/latest/download";
    private static final String GITHUB_RELEASE = "https://github.com/wangguo0/xiaowugui/releases/download";
    private static final String GITHUB_API = "https://api.github.com/repos/wangguo0/xiaowugui/releases/tags";
    private static final String GITHUB_RELEASES_API = "https://api.github.com/repos/wangguo0/xiaowugui/releases";
    private static final String GITHUB_RELEASE_ASSETS_API = "https://api.github.com/repos/wangguo0/xiaowugui/releases/assets";

    // release tag 可能是中文（如「小乌龟1.1」），拼进 URL 前必须百分号编码，否则镜像加速与 API 请求会 404
    private static String encode(String tag) {
        if (tag == null || tag.isEmpty()) return "";
        try {
            return URLEncoder.encode(tag, "UTF-8").replace("+", "%20");
        } catch (UnsupportedEncodingException e) {
            return tag;
        }
    }

    public static String getGithubLatestAsset(String name) {
        return GITHUB_LATEST + "/" + name;
    }

    public static String getGithubReleaseAsset(String tag, String name) {
        return GITHUB_RELEASE + "/" + encode(tag) + "/" + name;
    }

    public static String getReleaseApi(String tag) {
        return GITHUB_API + "/" + encode(tag);
    }

    public static String getReleasesApi() {
        return GITHUB_RELEASES_API + "?per_page=20";
    }

    public static String getLatestReleaseApi() {
        return GITHUB_RELEASES_API + "/latest";
    }

    public static String getReleaseAssetApi(long id) {
        return GITHUB_RELEASE_ASSETS_API + "/" + id;
    }
}
