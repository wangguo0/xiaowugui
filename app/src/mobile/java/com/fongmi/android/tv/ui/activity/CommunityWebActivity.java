package com.fongmi.android.tv.ui.activity;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.CommunityExtractor;
import com.fongmi.android.tv.bean.CommunitySource;
import com.fongmi.android.tv.databinding.ActivityCommunityWebBinding;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.ui.dialog.CommunityExtractDialog;
import com.fongmi.android.tv.ui.dialog.CommunityPickerDialog;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.Task;
import com.fongmi.android.tv.utils.WebViewUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * 订阅源全网资源社区：应用内网页浏览 + 右上角「一键提取订阅源」。
 * <p>
 * 提取走 GitHub git/trees 递归接口（经 {@link com.fongmi.android.tv.utils.GithubProxy} 加速），
 * 不依赖网页加载成功。
 */
public class CommunityWebActivity extends BaseActivity {

    private static final String EXTRA_URL = "url";
    private static final String EXTRA_NAME = "name";
    private static final String EXTRA_EXTRACTABLE = "extractable";

    private ActivityCommunityWebBinding mBinding;
    private String mUrl;
    private String mName;
    private boolean mExtractable;
    private boolean mExtracting;
    private CommunityExtractDialog mProgress;

    public static void start(Activity activity, String url, String name, boolean extractable) {
        Intent intent = new Intent(activity, CommunityWebActivity.class);
        intent.putExtra(EXTRA_URL, url);
        intent.putExtra(EXTRA_NAME, name);
        intent.putExtra(EXTRA_EXTRACTABLE, extractable);
        activity.startActivity(intent);
    }

    @Override
    protected ViewBinding getBinding() {
        return mBinding = ActivityCommunityWebBinding.inflate(getLayoutInflater());
    }

    @Override
    protected boolean customWall() {
        return false;
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void initView(@Nullable Bundle savedInstanceState) {
        mUrl = getIntent().getStringExtra(EXTRA_URL);
        mName = getIntent().getStringExtra(EXTRA_NAME);
        mExtractable = getIntent().getBooleanExtra(EXTRA_EXTRACTABLE, true);
        setSupportActionBar(mBinding.toolbar);
        getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        mBinding.toolbar.setTitle(TextUtils.isEmpty(mName) ? getString(R.string.community_repo_title) : mName);
        mBinding.toolbar.setSubtitle(mUrl);
        WebViewUtil.configureBase(mBinding.webview, "community");
        WebViewUtil.logProvider("community");
        mBinding.webview.setWebViewClient(new WebViewClient() {

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return false;
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                mBinding.progress.setVisibility(View.VISIBLE);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                mBinding.progress.setVisibility(View.GONE);
            }
        });
        mBinding.webview.setWebChromeClient(new WebChromeClient() {

            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                mBinding.progress.setProgress(newProgress);
                if (newProgress >= 100) mBinding.progress.setVisibility(View.GONE);
            }
        });
        mBinding.webview.loadUrl(mUrl);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_community_web, menu);
        // 用户自定义仓库不显示「提取订阅源」
        menu.findItem(R.id.extract).setVisible(mExtractable);
        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == R.id.extract) {
            onExtract();
            return true;
        }
        if (item.getItemId() == android.R.id.home) onBackInvoked();
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onBackInvoked() {
        if (mBinding.webview.canGoBack()) mBinding.webview.goBack();
        else finish();
    }

    private void onExtract() {
        if (mExtracting) return;
        mExtracting = true;
        String[] repo = CommunityExtractor.parseRepo(mUrl);
        if (repo == null) {
            finishExtract();
            Notify.show(R.string.community_extract_github_only);
            return;
        }
        mProgress = CommunityExtractDialog.create();
        mProgress.show(this);
        Task.submitLarge(() -> {
            List<CommunitySource> sources = new ArrayList<>();
            try {
                sources.addAll(CommunityExtractor.extractFromRepo(repo[0], repo[1], mProgress));
            } catch (Throwable ignored) {
            }
            App.post(() -> onExtracted(sources));
        });
    }

    private void onExtracted(List<CommunitySource> sources) {
        finishExtract();
        if (isFinishing() || isDestroyed()) return;
        if (sources.isEmpty()) {
            Notify.show(R.string.community_empty);
            return;
        }
        CommunityPickerDialog.create(sources).show(getSupportFragmentManager(), null);
    }

    private void finishExtract() {
        mExtracting = false;
        if (mProgress != null) {
            mProgress.dismissAllowingStateLoss();
            mProgress = null;
        }
    }

    @Override
    protected void onDestroy() {
        try {
            mBinding.webview.destroy();
        } catch (Throwable ignored) {
        }
        super.onDestroy();
    }
}
