package com.fongmi.android.tv.player.extractor;

import android.net.Uri;
import android.os.SystemClock;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.player.Source;
import com.fongmi.android.tv.ui.activity.VideoActivity;
import com.fongmi.android.tv.utils.UrlUtil;
import com.github.catvod.crawler.SpiderDebug;

public class Push implements Source.Extractor {

    @Override
    public boolean match(Uri uri) {
        return "push".equals(UrlUtil.scheme(uri));
    }

    @Override
    public String fetch(String url) throws Exception {
        String target = url.length() > 7 ? url.substring(7) : "";
        SpiderDebug.log("push", "extractor.fetch url=%s target=%s activity=%s", url, target, App.activity() == null ? "null" : App.activity().getClass().getSimpleName());
        if (App.activity() != null) VideoActivity.start(App.activity(), target);
        SystemClock.sleep(500);
        return "";
    }

    @Override
    public void stop() {
    }

    @Override
    public void exit() {
    }
}
