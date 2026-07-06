package com.fongmi.android.tv.ui.dialog;

import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ScrollView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.setting.PlaybackPerformanceSetting;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.utils.Util;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textview.MaterialTextView;

public final class PlaybackPerformanceDialog extends DialogFragment {

    private Runnable callback;
    private MaterialTextView detail;

    public static void show(Fragment fragment, Runnable callback) {
        PlaybackPerformanceDialog dialog = new PlaybackPerformanceDialog();
        dialog.callback = callback;
        dialog.show(fragment.getChildFragmentManager(), PlaybackPerformanceDialog.class.getSimpleName());
    }

    public static void show(FragmentActivity activity, Runnable callback) {
        PlaybackPerformanceDialog dialog = new PlaybackPerformanceDialog();
        dialog.callback = callback;
        dialog.show(activity.getSupportFragmentManager(), PlaybackPerformanceDialog.class.getSimpleName());
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        PlaybackPerformanceSetting.ensureInitialized();
        return new MaterialAlertDialogBuilder(requireActivity(), R.style.ThemeOverlay_WebHTV_LightDialog).setView(createView(LayoutInflater.from(requireContext()))).create();
    }

    @Override
    public void onStart() {
        super.onStart();
        Dialog dialog = getDialog();
        Window window = dialog == null ? null : dialog.getWindow();
        if (window == null) return;
        WindowManager.LayoutParams params = window.getAttributes();
        params.width = (int) (ResUtil.getScreenWidth(requireContext()) * (ResUtil.isLand(requireContext()) ? 0.58f : 0.92f));
        params.height = WindowManager.LayoutParams.WRAP_CONTENT;
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        window.getDecorView().setPadding(0, 0, 0, 0);
        window.setAttributes(params);
        window.setLayout(params.width, params.height);
    }

    private View createView(LayoutInflater inflater) {
        LinearLayout root = new LinearLayout(requireContext());
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundResource(R.drawable.shape_shell_proxy_dialog);
        root.setPadding(dp(22), dp(22), dp(22), dp(18));

        MaterialTextView title = new MaterialTextView(requireContext());
        title.setText(R.string.player_performance);
        title.setTextColor(Color.parseColor("#202124"));
        title.setTextSize(18);
        title.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(title, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout actions = new LinearLayout(requireContext());
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER);
        actions.addView(actionButton(R.string.player_performance_recommended, view -> apply(true)), actionParams(true));
        actions.addView(actionButton(R.string.player_performance_compatible, view -> apply(false)), actionParams(false));
        actions.addView(actionButton(R.string.dialog_reset, view -> apply(PlaybackPerformanceSetting.isCompatible() ? false : true)), actionParams(false));
        LinearLayout.LayoutParams actionLayout = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40));
        actionLayout.topMargin = dp(14);
        root.addView(actions, actionLayout);

        detail = new MaterialTextView(requireContext());
        detail.setTextColor(Color.parseColor("#3C4043"));
        detail.setTextSize(14);
        detail.setLineSpacing(dp(2), 1f);
        detail.setText(PlaybackPerformanceSetting.getDetail());

        ScrollView scroll = new ScrollView(requireContext());
        scroll.setFillViewport(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.addView(detail, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.min(dp(460), Math.max(dp(300), ResUtil.getScreenHeight(requireContext()) * 2 / 3)));
        scrollParams.topMargin = dp(16);
        root.addView(scroll, scrollParams);
        return root;
    }

    private MaterialButton actionButton(int text, View.OnClickListener listener) {
        MaterialButton button = new MaterialButton(requireContext());
        button.setAllCaps(false);
        button.setText(text);
        button.setSingleLine(true);
        button.setGravity(Gravity.CENTER);
        button.setTextSize(14);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setMinHeight(dp(38));
        button.setInsetTop(0);
        button.setInsetBottom(0);
        button.setFocusable(true);
        button.setFocusableInTouchMode(Util.isLeanback());
        button.setOnClickListener(listener);
        return button;
    }

    private LinearLayout.LayoutParams actionParams(boolean first) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1);
        params.leftMargin = first ? 0 : dp(8);
        return params;
    }

    private void apply(boolean recommended) {
        if (recommended) PlaybackPerformanceSetting.applyRecommended();
        else PlaybackPerformanceSetting.applyCompatible();
        detail.setText(PlaybackPerformanceSetting.getDetail());
        if (callback != null) callback.run();
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
