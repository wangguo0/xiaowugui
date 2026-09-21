package com.fongmi.android.tv.ui.dialog;

import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.FragmentActivity;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.utils.FileUtil;
import com.fongmi.android.tv.utils.Util;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.progressindicator.LinearProgressIndicator;

// 独立的「正在下载」弹窗（双端共用）：选定线路后弹出，下载期间常驻；
// 版本弹窗在点「更新」时即关闭，下载进度不再画在版本弹窗内
public class DownloadDialog {

    private final FragmentActivity activity;
    private final Runnable onCancel;
    private AlertDialog dialog;
    private LinearProgressIndicator progress;
    private TextView progressText;
    // 缓存最近一次进度：弹窗随页面销毁失效后重弹时据此无缝续接
    private int lastProgress = -1;
    private long lastBytes;
    private long lastTotal;
    private long lastSpeed;
    private long lastElapsed;

    private DownloadDialog(FragmentActivity activity, Runnable onCancel) {
        this.activity = activity;
        this.onCancel = onCancel;
    }

    // 在指定页面上弹出下载弹窗：强制更新无「取消」按钮且不可关闭；返回实例供 Updater 持有
    public static DownloadDialog show(FragmentActivity activity, boolean forceMode, Runnable onCancel) {
        DownloadDialog instance = new DownloadDialog(activity, onCancel);
        instance.create(forceMode);
        return instance;
    }

    private void create(boolean forceMode) {
        View view = activity.getLayoutInflater().inflate(R.layout.dialog_download, null);
        progress = view.findViewById(R.id.progress);
        progressText = view.findViewById(R.id.progressText);
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(activity, R.style.ThemeOverlay_WebHTV_LightDialog)
                .setView(view)
                .setCancelable(false);
        // 普通更新提供「取消」；强制更新不留任何关闭出口，直到送装或失败重试
        if (!forceMode) builder.setNegativeButton(R.string.update_cancel, (d, which) -> {
            if (onCancel != null) onCancel.run();
        });
        dialog = builder.create();
        // 常驻规则：点外不关 + 返回键不关，切后台/回前台绝不主动 dismiss
        dialog.setCanceledOnTouchOutside(false);
        dialog.setOnKeyListener((d, keyCode, event) -> keyCode == KeyEvent.KEYCODE_BACK);
        dialog.show();
        applyProgress();
    }

    // 弹窗是否仍在指定页面上有效展示（Activity 重建后旧弹窗即失效）
    public boolean isShowingFor(FragmentActivity act) {
        return dialog != null && dialog.isShowing() && activity == act;
    }

    // 回前台时置顶：重新 attach 弹窗窗口到窗口栈顶，避免被其他弹窗盖住
    public void bringToFront() {
        try {
            if (dialog != null && dialog.isShowing()) dialog.show();
        } catch (Exception ignored) {
        }
    }

    // 刷新下载进度（progress<0 表示进度条走不确定态）
    public void setProgress(int progress, long bytes, long total, long speed, long elapsed) {
        lastProgress = progress;
        lastBytes = bytes;
        lastTotal = total;
        lastSpeed = speed;
        lastElapsed = elapsed;
        applyProgress();
    }

    private void applyProgress() {
        if (progress == null || progressText == null) return;
        boolean indeterminate = lastProgress < 0;
        int value = Math.max(0, Math.min(100, lastProgress));
        progress.setIndeterminate(indeterminate);
        if (!indeterminate) progress.setProgress(value);
        progressText.setText(getProgressText(indeterminate, value, lastBytes, lastTotal, lastSpeed, lastElapsed));
    }

    private String getProgressText(boolean indeterminate, int value, long bytes, long total, long speed, long elapsed) {
        if (speed <= 0 || elapsed <= 0) return indeterminate ? getString(R.string.update_downloading_unknown) : getString(R.string.update_downloading, value);
        String speedText = FileUtil.byteCountToDisplaySize(speed);
        String elapsedText = formatDuration(elapsed);
        if (!indeterminate && total > 0 && bytes >= 0) {
            long remaining = Math.max(0, total - bytes) * 1000 / speed;
            return getString(R.string.update_downloading_detail_remaining, value, speedText, formatDuration(remaining), elapsedText);
        }
        return indeterminate ? getString(R.string.update_downloading_detail_unknown, speedText, elapsedText) : getString(R.string.update_downloading_detail, value, speedText, elapsedText);
    }

    private String formatDuration(long time) {
        String text = Util.timeMs(Math.max(0, time));
        return TextUtils.isEmpty(text) ? "00:00" : text;
    }

    private String getString(int resId, Object... args) {
        return activity.getString(resId, args);
    }

    // 静默关窗（下载完成/失败/取消等由调用方统一收口）
    public void dismissQuietly() {
        try {
            if (dialog != null && dialog.isShowing()) dialog.dismiss();
        } catch (Exception ignored) {
        } finally {
            dialog = null;
        }
    }
}
