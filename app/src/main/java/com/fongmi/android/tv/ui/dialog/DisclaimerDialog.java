package com.fongmi.android.tv.ui.dialog;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.CheckBox;

import androidx.appcompat.app.AlertDialog;
import androidx.core.widget.NestedScrollView;

import com.fongmi.android.tv.R;
import com.github.catvod.utils.Prefers;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.textview.MaterialTextView;

// 首次使用免责声明签署弹窗：不可取消、返回键无效；须滚动到底才可勾选，勾选后才可同意
public class DisclaimerDialog {

    public static final String VERSION = "1.0";

    private static final String KEY_VERSION = "disclaimer_agreed_version";
    private static final String KEY_TIME = "disclaimer_agreed_time";

    public static boolean isAgreed() {
        return VERSION.equals(Prefers.getString(KEY_VERSION));
    }

    public static void showIfNeeded(Activity activity) {
        showIfNeeded(activity, null);
    }

    // onAgreed：已签署时立即回调；本次点击"同意并进入"后回调
    public static void showIfNeeded(Activity activity, Runnable onAgreed) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        if (isAgreed()) {
            if (onAgreed != null) onAgreed.run();
            return;
        }
        View content = LayoutInflater.from(activity).inflate(R.layout.dialog_disclaimer, null);
        NestedScrollView scroll = content.findViewById(R.id.scroll);
        MaterialTextView hint = content.findViewById(R.id.scrollHint);
        CheckBox agree = content.findViewById(R.id.agree);
        MaterialButton accept = content.findViewById(R.id.accept);
        MaterialButton reject = content.findViewById(R.id.reject);
        AlertDialog dialog = new AlertDialog.Builder(activity).setView(content).setCancelable(false).create();
        Runnable unlock = () -> {
            if (agree.isEnabled()) return;
            agree.setEnabled(true);
            hint.setVisibility(View.GONE);
        };
        scroll.setOnScrollChangeListener((NestedScrollView.OnScrollChangeListener) (v, x, y, ox, oy) -> {
            if (!v.canScrollVertically(1)) unlock.run();
        });
        content.post(() -> {
            if (!scroll.canScrollVertically(1)) unlock.run();
        });
        agree.setOnCheckedChangeListener((buttonView, checked) -> accept.setEnabled(checked));
        accept.setOnClickListener(v -> {
            Prefers.put(KEY_VERSION, VERSION);
            Prefers.put(KEY_TIME, System.currentTimeMillis());
            dialog.dismiss();
            if (onAgreed != null) onAgreed.run();
        });
        reject.setOnClickListener(v -> {
            dialog.dismiss();
            activity.finish();
        });
        dialog.show();
        if (dialog.getWindow() != null) dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
    }
}
