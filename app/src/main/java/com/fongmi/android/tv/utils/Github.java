package com.fongmi.android.tv.utils;

public class Github {

    private static final String GITHUB_LATEST = "https://github.com/wangguo0/xiaowugui/releases/latest/download";
    private static final String GITHUB_RELEASE = "https://github.com/wangguo0/xiaowugui/releases/download";
    private static final String GITHUB_API = "https://api.github.com/repos/wangguo0/xiaowugui/releases/tags";
    private static final String GITHUB_RELEASES_API = "https://api.github.com/repos/wangguo0/xiaowugui/releases";
    private static final String GITHUB_RELEASE_ASSETS_API = "https://api.github.com/repos/wangguo0/xiaowugui/releases/assets";

    public static String getGithubLatestAsset(String name) {
        return GITHUB_LATEST + "/" + name;
    }

    public static String getGithubReleaseAsset(String tag, String name) {
        return GITHUB_RELEASE + "/" + tag + "/" + name;
    }

    public static String getReleaseApi(String tag) {
        return GITHUB_API + "/" + tag;
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
