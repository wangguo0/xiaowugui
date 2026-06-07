package com.fongmi.android.tv.ui.dialog;

import android.content.DialogInterface;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.LinearLayoutCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.databinding.DialogWebHomeExtensionBinding;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.web.ext.WebHomeExtensionRegistry;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textview.MaterialTextView;

public class WebHomeExtensionDialog extends BaseAlertDialog {

    private DialogWebHomeExtensionBinding binding;
    private Runnable callback;
    private boolean enabled;

    public static void show(Fragment fragment, Runnable callback) {
        WebHomeExtensionDialog dialog = new WebHomeExtensionDialog();
        dialog.callback = callback;
        dialog.show(fragment.getChildFragmentManager(), null);
    }

    public static void show(FragmentActivity activity, Runnable callback) {
        WebHomeExtensionDialog dialog = new WebHomeExtensionDialog();
        dialog.callback = callback;
        dialog.show(activity.getSupportFragmentManager(), null);
    }

    @Override
    protected ViewBinding getBinding() {
        return binding = DialogWebHomeExtensionBinding.inflate(getLayoutInflater());
    }

    @Override
    protected MaterialAlertDialogBuilder getBuilder() {
        return new MaterialAlertDialogBuilder(requireActivity(), R.style.ThemeOverlay_WebHTV_LightDialog).setView(getBinding().getRoot());
    }

    @Override
    public void onStart() {
        super.onStart();
        if (getDialog() == null) return;
        Window window = getDialog().getWindow();
        if (window == null) return;
        WindowManager.LayoutParams params = window.getAttributes();
        int screenWidth = ResUtil.getScreenWidth(requireContext());
        int screenHeight = ResUtil.getScreenHeight(requireContext());
        boolean land = ResUtil.isLand(requireContext());
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        window.getDecorView().setPadding(0, 0, 0, 0);
        params.width = (int) (screenWidth * (land ? 0.72f : 0.94f));
        params.height = land ? (int) (screenHeight * 0.94f) : WindowManager.LayoutParams.WRAP_CONTENT;
        window.setAttributes(params);
        window.setLayout(params.width, params.height);
        ViewGroup.LayoutParams rootParams = binding.root.getLayoutParams();
        rootParams.height = land ? params.height : ViewGroup.LayoutParams.WRAP_CONTENT;
        binding.root.setLayoutParams(rootParams);
        LinearLayoutCompat.LayoutParams scrollParams = (LinearLayoutCompat.LayoutParams) binding.contentScroll.getLayoutParams();
        scrollParams.height = land ? 0 : ViewGroup.LayoutParams.WRAP_CONTENT;
        scrollParams.weight = land ? 1 : 0;
        binding.contentScroll.setLayoutParams(scrollParams);
        binding.contentScroll.setMaxHeight(land ? 0 : (int) (screenHeight * 0.54f));
        binding.enabled.requestFocus();
    }

    @Override
    protected void initView() {
        enabled = Setting.isWebHomeExtension();
        updateEnabledText();
        render();
        refresh(false);
    }

    @Override
    protected void initEvent() {
        binding.enabled.setOnClickListener(view -> {
            enabled = !enabled;
            updateEnabledText();
        });
        binding.refresh.setOnClickListener(view -> refresh(true));
        binding.clear.setOnClickListener(view -> clearCache());
        binding.negative.setOnClickListener(view -> dismiss());
        binding.positive.setOnClickListener(view -> onPositive());
    }

    @Override
    public void onCancel(@NonNull DialogInterface dialog) {
        if (callback != null) callback.run();
        super.onCancel(dialog);
    }

    private void updateEnabledText() {
        binding.enabled.setText(enabled ? R.string.setting_enable : R.string.setting_disable);
        binding.enabled.setAlpha(enabled ? 1.0f : 0.65f);
    }

    private void refresh(boolean manual) {
        binding.refresh.setEnabled(false);
        if (manual) binding.summary.setText(R.string.update_check);
        WebHomeExtensionRegistry.get().refresh(VodConfig.get().getHome(), () -> {
            if (binding == null) return;
            binding.refresh.setEnabled(true);
            render();
            if (callback != null) callback.run();
        });
    }

    private void clearCache() {
        WebHomeExtensionRegistry.get().clear();
        Notify.show(R.string.web_home_extension_clear_done);
        refresh(false);
    }

    private void onPositive() {
        boolean changed = Setting.isWebHomeExtension() != enabled;
        Setting.putWebHomeExtension(enabled);
        if (changed) WebHomeExtensionRegistry.get().refresh(VodConfig.get().getHome(), null);
        if (callback != null) callback.run();
        dismiss();
    }

    private void render() {
        WebHomeExtensionRegistry.Snapshot snapshot = WebHomeExtensionRegistry.get().snapshot();
        String siteKey = TextUtils.isEmpty(snapshot.siteKey) ? getString(R.string.web_home_extension_unknown_site) : snapshot.siteKey;
        binding.summary.setText(getString(R.string.web_home_extension_summary, snapshot.sourceCount, snapshot.installedCount, snapshot.matchedCount, snapshot.readyCount, siteKey));
        trimRows();
        binding.empty.setVisibility(snapshot.items.isEmpty() ? View.VISIBLE : View.GONE);
        for (WebHomeExtensionRegistry.Item item : snapshot.items) binding.list.addView(row(item));
    }

    private void trimRows() {
        while (binding.list.getChildCount() > 1) binding.list.removeViewAt(1);
    }

    private View row(WebHomeExtensionRegistry.Item item) {
        LinearLayoutCompat root = new LinearLayoutCompat(requireContext());
        root.setOrientation(LinearLayoutCompat.VERTICAL);
        root.setPadding(dp(10), dp(8), dp(10), dp(8));
        root.setBackground(rowBackground());
        LinearLayoutCompat.LayoutParams rootParams = new LinearLayoutCompat.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rootParams.topMargin = dp(8);
        root.setLayoutParams(rootParams);

        LinearLayoutCompat header = new LinearLayoutCompat(requireContext());
        header.setGravity(android.view.Gravity.CENTER_VERTICAL);
        header.setOrientation(LinearLayoutCompat.HORIZONTAL);
        root.addView(header, new LinearLayoutCompat.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayoutCompat titleBox = new LinearLayoutCompat(requireContext());
        titleBox.setOrientation(LinearLayoutCompat.VERTICAL);
        LinearLayoutCompat.LayoutParams titleParams = new LinearLayoutCompat.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        titleParams.rightMargin = dp(10);
        header.addView(titleBox, titleParams);

        MaterialTextView title = text(item.name + (TextUtils.isEmpty(item.version) ? "" : " " + item.version), 15, Color.BLACK, true);
        titleBox.addView(title);
        MaterialTextView status = text(item.id + " · " + item.runAt + " · " + statusText(item), 12, statusColor(item.status), false);
        titleBox.addView(status);

        MaterialButton button = actionButton(item.enabled);
        button.setOnClickListener(view -> toggle(item));
        header.addView(button);

        addDetail(root, getString(R.string.web_home_extension_source, source(item)));
        addDetail(root, getString(R.string.web_home_extension_match, empty(item.matchText)));
        if (!TextUtils.isEmpty(item.excludeText)) addDetail(root, getString(R.string.web_home_extension_exclude, item.excludeText));
        if (!TextUtils.isEmpty(item.dependsText)) addDetail(root, getString(R.string.web_home_extension_depends, item.dependsText));
        if (!TextUtils.isEmpty(item.reason)) addDetail(root, item.reason);
        if (!TextUtils.isEmpty(item.lastLog)) addDetail(root, getString(R.string.web_home_extension_last_log, item.lastLog));
        return root;
    }

    private MaterialButton actionButton(boolean enabled) {
        MaterialButton button = new MaterialButton(requireContext());
        button.setText(enabled ? R.string.setting_disable : R.string.setting_enable);
        button.setMinWidth(dp(76));
        button.setMinHeight(dp(36));
        button.setMinimumHeight(dp(36));
        button.setPadding(dp(8), 0, dp(8), 0);
        ColorStateList bg = ContextCompat.getColorStateList(requireContext(), enabled ? R.color.dialog_outlined_button_bg : R.color.dialog_tonal_button_bg);
        ColorStateList fg = ContextCompat.getColorStateList(requireContext(), enabled ? R.color.dialog_outlined_button_text : R.color.dialog_tonal_button_text);
        button.setBackgroundTintList(bg);
        button.setTextColor(fg);
        return button;
    }

    private void toggle(WebHomeExtensionRegistry.Item item) {
        if (item.enabled) {
            WebHomeExtensionRegistry.get().setExtensionEnabled(item.id, false);
            refresh(false);
        } else {
            confirmEnable(item);
        }
    }

    private void confirmEnable(WebHomeExtensionRegistry.Item item) {
        AlertDialog dialog = new MaterialAlertDialogBuilder(requireActivity(), R.style.ThemeOverlay_WebHTV_LightDialog)
                .setTitle(R.string.web_home_extension_enable_confirm_title)
                .setMessage(getString(R.string.web_home_extension_enable_confirm_message, item.name, source(item), empty(item.matchText)))
                .setNegativeButton(R.string.dialog_negative, null)
                .setPositiveButton(R.string.setting_enable, null)
                .create();
        dialog.setOnShowListener(d -> {
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).requestFocus();
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
                WebHomeExtensionRegistry.get().setExtensionEnabled(item.id, true);
                dialog.dismiss();
                refresh(false);
            });
        });
        dialog.show();
    }

    private void addDetail(LinearLayoutCompat root, String value) {
        MaterialTextView view = text(value, 12, Color.parseColor("#5F6368"), false);
        LinearLayoutCompat.LayoutParams params = new LinearLayoutCompat.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(3);
        root.addView(view, params);
    }

    private MaterialTextView text(String value, int sp, int color, boolean bold) {
        MaterialTextView view = new MaterialTextView(requireContext());
        view.setText(value);
        view.setTextColor(color);
        view.setTextSize(sp);
        view.setSingleLine(false);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private GradientDrawable rowBackground() {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(Color.parseColor("#F5F6F7"));
        drawable.setCornerRadius(dp(6));
        return drawable;
    }

    private String statusText(WebHomeExtensionRegistry.Item item) {
        int resId = switch (item.status) {
            case "ready" -> R.string.web_home_extension_status_ready;
            case "injected" -> R.string.web_home_extension_status_injected;
            case "disabled" -> R.string.web_home_extension_status_disabled;
            case "unmatched" -> R.string.web_home_extension_status_unmatched;
            case "skipped" -> R.string.web_home_extension_status_skipped;
            case "matched" -> R.string.web_home_extension_status_matched;
            default -> item.enabled ? R.string.setting_enable : R.string.setting_disable;
        };
        return getString(resId);
    }

    private int statusColor(String status) {
        return switch (status) {
            case "ready", "injected", "matched" -> Color.parseColor("#137333");
            case "skipped" -> Color.parseColor("#B3261E");
            case "disabled", "unmatched" -> Color.parseColor("#6F7378");
            default -> Color.parseColor("#5F6368");
        };
    }

    private String source(WebHomeExtensionRegistry.Item item) {
        return TextUtils.isEmpty(item.sourceUrl) ? getString(R.string.web_home_extension_inline_source) : item.sourceUrl;
    }

    private String empty(String value) {
        return TextUtils.isEmpty(value) ? getString(R.string.none) : value;
    }

    private int dp(int value) {
        return ResUtil.dp2px(value);
    }
}
