package com.fongmi.android.tv.ui.dialog;

import android.app.Activity;
import android.app.Dialog;

import androidx.annotation.Nullable;

import com.fongmi.android.tv.R;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.List;

/**
 * 源安全相关「温馨提醒」居中弹窗集合：拦截提示、合并结果、危险源清单。
 */
public class ProbeDialog {

    private ProbeDialog() {
    }

    /**
     * 永久黑名单拦截弹窗（该源曾实锤崩溃 / 尝试杀进程）。
     */
    public static void showDanger(Activity activity) {
        new MaterialAlertDialogBuilder(activity, R.style.ThemeOverlay_WebHTV_LightDialog)
                .setTitle(R.string.video_error_title)
                .setMessage(R.string.source_probe_danger_message)
                .setCancelable(false)
                .setPositiveButton(R.string.dialog_positive, null)
                .show();
    }

    /**
     * 静态扫描拦截弹窗（源 jar 命中「自杀退出」特征或已被实锤拉黑，阻止添加）。
     */
    public static void showScanDanger(Activity activity) {
        new MaterialAlertDialogBuilder(activity, R.style.ThemeOverlay_WebHTV_LightDialog)
                .setTitle(R.string.video_error_title)
                .setMessage(R.string.source_probe_scan_danger_message)
                .setCancelable(false)
                .setPositiveButton(R.string.source_probe_trial_rollback_confirm, null)
                .show();
    }

    /**
     * 源合并完成后，逐条列出被移出合并名单的源；按原因分组：
     * runtime = 运行实锤危险，suspect = 沙箱预警（可单独添加试用）。
     */
    public static void showBlocked(Activity activity, List<String> runtime, List<String> suspect) {
        int total = runtime.size() + suspect.size();
        StringBuilder sb = new StringBuilder(activity.getString(R.string.source_merge_blocked_header, total));
        int i = 1;
        for (String url : runtime) {
            sb.append('\n').append(i++).append(". ").append(url).append('\n').append(activity.getString(R.string.source_merge_blocked_reason));
        }
        for (String url : suspect) {
            sb.append('\n').append(i++).append(". ").append(url).append('\n').append(activity.getString(R.string.source_merge_blocked_reason_suspect));
        }
        new MaterialAlertDialogBuilder(activity, R.style.ThemeOverlay_WebHTV_LightDialog)
                .setTitle(R.string.video_error_title)
                .setMessage(sb.toString())
                .setCancelable(false)
                .setPositiveButton(R.string.dialog_positive, null)
                .show();
    }

    /**
     * 通用「温馨提醒」居中弹窗：点击「确定」消失；onDismiss 用于串联下一个弹窗。
     */
    public static void showNotice(Activity activity, CharSequence message, @Nullable Runnable onDismiss) {
        Dialog dialog = new MaterialAlertDialogBuilder(activity, R.style.ThemeOverlay_WebHTV_LightDialog)
                .setTitle(R.string.video_error_title)
                .setMessage(message)
                .setCancelable(false)
                .setPositiveButton(R.string.dialog_positive, null)
                .show();
        if (onDismiss != null) dialog.setOnDismissListener(d -> onDismiss.run());
    }
}
