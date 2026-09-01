package com.fongmi.android.tv.api.bangumi;

import android.text.TextUtils;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.utils.WebViewUtil;
import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.utils.Prefers;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// 模块 ID 自愈工具：接口改版返回空时，用 WebView 打开动漫页，
// 从 DOM 的 dt-params 属性中提取"每日更新"模块的 un_mod_id（仅后台线程低频调用）
public class BangumiModule {

    public static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    private static final String TAG = "TV-Bangumi";
    private static final String URL = "https://v.qq.com/channel/cartoon";
    private static final String KEY_MOD = "bangumi_mod_id";
    private static final Pattern PATTERN = Pattern.compile("un_mod_id=([A-Za-z0-9_]+)");
    private static final long TIMEOUT = 20;

    // 在页面所有元素的 dt-params / data-* 属性里找每日更新模块 ID（tab 文案含"周"的优先）
    private static final String JS = "(function(){" +
            "function t(e){return e?(e.textContent||'').trim():''}" +
            "var re=/un_mod_id=([A-Za-z0-9_]+)/;" +
            "var all=document.querySelectorAll('[dt-params],[data-params],[class*=tabname]');" +
            "for(var i=0;i<all.length;i++){" +
            "var e=all[i];var txt=t(e);" +
            "if(!/周|今天/.test(txt))continue;" +
            "var attrs=e.attributes;" +
            "for(var j=0;j<attrs.length;j++){var m=re.exec(attrs[j].value||'');if(m)return m[1]}}" +
            "var html=document.documentElement.innerHTML;" +
            "var m=re.exec(html);return m?m[1]:''})()";

    private BangumiModule() {
    }

    // 同步获取（仅在后台线程调用），失败返回空串
    public static String fetch() {
        CountDownLatch latch = new CountDownLatch(1);
        String[] result = {""};
        App.post(() -> {
            try {
                WebView webView = new WebView(App.get());
                WebViewUtil.configureBase(webView, "bangumi-mod");
                WebSettings settings = webView.getSettings();
                settings.setJavaScriptEnabled(true);
                settings.setDomStorageEnabled(true);
                settings.setUserAgentString(UA);
                webView.setWebViewClient(new WebViewClient() {
                    @Override
                    public void onPageFinished(WebView view, String url) {
                        super.onPageFinished(view, url);
                        App.post(() -> {
                            if (result[0].isEmpty()) extract(view, result, latch);
                        }, 3000);
                    }
                });
                webView.loadUrl(URL);
                App.post(() -> {
                    if (result[0].isEmpty()) extract(webView, result, latch);
                }, 8000);
            } catch (Throwable e) {
                SpiderDebug.log(TAG, "module start error=%s", e.getMessage());
                latch.countDown();
            }
        });
        try {
            latch.await(TIMEOUT, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
        }
        String mod = result[0];
        if (!TextUtils.isEmpty(mod)) Prefers.getPrefers().edit().putString(KEY_MOD, mod).apply();
        SpiderDebug.log(TAG, "module fetch=%s", mod);
        return mod;
    }

    private static void extract(WebView view, String[] result, CountDownLatch latch) {
        try {
            view.evaluateJavascript(JS, value -> {
                try {
                    String mod = unquote(value);
                    if (!TextUtils.isEmpty(mod)) result[0] = mod;
                } catch (Exception ignored) {
                }
                latch.countDown();
                App.post(() -> {
                    try {
                        view.stopLoading();
                        view.loadUrl("about:blank");
                        view.destroy();
                    } catch (Throwable ignored2) {
                    }
                });
            });
        } catch (Throwable e) {
            latch.countDown();
        }
    }

    private static String unquote(String value) {
        if (TextUtils.isEmpty(value) || "null".equals(value)) return "";
        try {
            if (value.startsWith("\"") && value.endsWith("\"")) {
                return com.google.gson.JsonParser.parseString(value).getAsString();
            }
        } catch (Exception e) {
            SpiderDebug.log(TAG, "module unquote error=%s", e.getMessage());
        }
        return value;
    }
}
