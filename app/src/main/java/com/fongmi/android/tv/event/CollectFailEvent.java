package com.fongmi.android.tv.event;

import org.greenrobot.eventbus.EventBus;

// 搜索结果页卡片进入详情页「立马返回」（详情为空）时发出，
// 通知发起点击的搜索页把该卡片原地轮转到下一个同名站源
public record CollectFailEvent(String siteKey, String vodId) {

    public static void post(String siteKey, String vodId) {
        EventBus.getDefault().post(new CollectFailEvent(siteKey, vodId));
    }
}
