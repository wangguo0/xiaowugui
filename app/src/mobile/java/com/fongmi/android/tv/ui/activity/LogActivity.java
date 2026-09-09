package com.fongmi.android.tv.ui.activity;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.MenuItem;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.Toolbar;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.ActivityLogBinding;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.utils.DiagLog;
import com.fongmi.android.tv.utils.FileUtil;
import com.fongmi.android.tv.utils.Notify;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.File;

public class LogActivity extends BaseActivity {

    private ActivityLogBinding mBinding;
    private final Runnable mRefresh = this::refresh;

    public static void start(Activity activity) {
        activity.startActivity(new Intent(activity, LogActivity.class));
    }

    @Override
    protected ViewBinding getBinding() {
        return mBinding = ActivityLogBinding.inflate(getLayoutInflater());
    }

    @Override
    public void setSupportActionBar(@Nullable Toolbar toolbar) {
        super.setSupportActionBar(toolbar);
        getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        setTitle("");
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        setSupportActionBar(mBinding.toolbar);
        mBinding.switchEnable.setChecked(DiagLog.isEnabled());
        refresh();
    }

    @Override
    protected void initEvent() {
        mBinding.switchEnable.setOnCheckedChangeListener((v, checked) -> DiagLog.setEnabled(checked));
        mBinding.refresh.setOnClickListener(v -> refresh());
        mBinding.export.setOnClickListener(v -> onExport());
        mBinding.copy.setOnClickListener(v -> onCopy());
        mBinding.clear.setOnClickListener(v -> onClear());
    }

    private void onCopy() {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) clipboard.setPrimaryClip(ClipData.newPlainText("log", DiagLog.text()));
        Notify.show(R.string.copied);
    }

    private void refresh() {
        mBinding.content.setText(DiagLog.text());
        mBinding.scroll.post(() -> mBinding.scroll.fullScroll(android.view.View.FOCUS_DOWN));
    }

    private void onExport() {
        File file = DiagLog.export();
        if (file == null) {
            Notify.show(R.string.setting_diag_log_export_fail);
            return;
        }
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_STREAM, FileUtil.getShareUri(file));
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(Intent.createChooser(intent, getString(R.string.setting_diag_log_export)));
        } catch (Exception e) {
            Notify.show(R.string.setting_diag_log_export_fail);
        }
    }

    private void onClear() {
        new MaterialAlertDialogBuilder(this, R.style.ThemeOverlay_WebHTV_LightDialog)
                .setTitle(R.string.video_error_title)
                .setMessage(R.string.setting_diag_log_clear_confirm)
                .setNegativeButton(R.string.dialog_negative, null)
                .setPositiveButton(R.string.dialog_positive, (d, w) -> {
                    DiagLog.clear();
                    refresh();
                })
                .show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        App.post(mRefresh, 0);
        App.post(mRefresh, 2000);
    }

    @Override
    protected void onPause() {
        super.onPause();
        App.removeCallbacks(mRefresh);
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == android.R.id.home) onBackInvoked();
        return super.onOptionsItemSelected(item);
    }
}
