package com.fongmi.android.tv.utils;

import com.fongmi.android.tv.bean.Vod;

import java.util.ArrayList;
import java.util.List;

// 搜索结果页 → 播放详情页的一次性簇交接持有器：
// 点卡片进播放页前把该卡片的同簇线路（代表+全部成员，搜索页已完成5维冲突清洗）put 进来，
// 播放页 take 即清空（一次性消费），切换站源列表直接复用这条线路，不再重新发起全站搜索。
public class QuickPoolHandoff {

    private static List<Vod> sPool;

    public static void put(List<Vod> items) {
        sPool = items == null ? null : new ArrayList<>(items);
    }

    public static List<Vod> take() {
        List<Vod> pool = sPool;
        sPool = null;
        return pool;
    }
}
