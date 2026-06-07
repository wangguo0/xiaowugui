package com.fongmi.android.tv.ui.dialog;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;

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
import com.fongmi.android.tv.web.HomeWebController;
import com.fongmi.android.tv.web.ext.WebHomeExtensionRegistry;
import com.fongmi.android.tv.web.ext.WebHomeExtensionSourceStore;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
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
        binding.add.setOnClickListener(view -> editSource(null));
        binding.refresh.setOnClickListener(view -> refresh(true));
        binding.preview.setOnClickListener(view -> reloadPreview());
        binding.preview.setOnLongClickListener(view -> {
            showReport();
            return true;
        });
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
        HomeWebController.requestExtensionReload();
        Notify.show(R.string.web_home_extension_clear_done);
        refresh(false);
    }

    private void reloadPreview() {
        WebHomeExtensionRegistry.get().clear();
        HomeWebController.requestExtensionReload();
        refresh(false);
        Notify.show(R.string.web_home_extension_preview_reloaded);
    }

    private void showReport() {
        String report = WebHomeExtensionRegistry.get().debugReport();
        AlertDialog dialog = new MaterialAlertDialogBuilder(requireActivity(), R.style.ThemeOverlay_WebHTV_LightDialog)
                .setTitle(R.string.web_home_extension_report_title)
                .setMessage(report)
                .setNegativeButton(R.string.dialog_negative, null)
                .setPositiveButton(R.string.web_home_extension_copy_report, null)
                .create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> copyReport(report)));
        dialog.show();
    }

    private void copyReport(String report) {
        ClipboardManager manager = (ClipboardManager) requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
        if (manager == null) return;
        manager.setPrimaryClip(ClipData.newPlainText(getString(R.string.web_home_extension_report_title), report));
        Notify.show(R.string.web_home_extension_report_copied);
    }

    private void onPositive() {
        boolean changed = Setting.isWebHomeExtension() != enabled;
        Setting.putWebHomeExtension(enabled);
        if (changed) {
            WebHomeExtensionRegistry.get().refresh(VodConfig.get().getHome(), null);
            HomeWebController.requestExtensionReload();
        }
        if (callback != null) callback.run();
        dismiss();
    }

    private void render() {
        WebHomeExtensionRegistry.Snapshot snapshot = WebHomeExtensionRegistry.get().snapshot();
        java.util.List<WebHomeExtensionSourceStore.Entry> sources = WebHomeExtensionSourceStore.list();
        String siteKey = TextUtils.isEmpty(snapshot.siteKey) ? getString(R.string.web_home_extension_unknown_site) : snapshot.siteKey;
        binding.summary.setText(getString(R.string.web_home_extension_summary, snapshot.sourceCount, snapshot.installedCount, snapshot.matchedCount, snapshot.readyCount, siteKey));
        trimRows();
        binding.empty.setVisibility(sources.isEmpty() && snapshot.items.isEmpty() ? View.VISIBLE : View.GONE);
        if (!sources.isEmpty()) {
            binding.list.addView(section(R.string.web_home_extension_user_sources));
            for (WebHomeExtensionSourceStore.Entry source : sources) binding.list.addView(sourceRow(source));
        }
        if (!snapshot.items.isEmpty()) binding.list.addView(section(R.string.web_home_extension_loaded_extensions));
        for (WebHomeExtensionRegistry.Item item : snapshot.items) binding.list.addView(row(item));
    }

    private void trimRows() {
        while (binding.list.getChildCount() > 1) binding.list.removeViewAt(1);
    }

    private View section(int title) {
        MaterialTextView view = text(getString(title), 13, Color.parseColor("#3C4043"), true);
        LinearLayoutCompat.LayoutParams params = new LinearLayoutCompat.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(12);
        view.setLayoutParams(params);
        return view;
    }

    private View sourceRow(WebHomeExtensionSourceStore.Entry source) {
        LinearLayoutCompat root = new LinearLayoutCompat(requireContext());
        root.setOrientation(LinearLayoutCompat.VERTICAL);
        root.setPadding(dp(10), dp(8), dp(10), dp(8));
        root.setBackground(rowBackground());
        LinearLayoutCompat.LayoutParams rootParams = new LinearLayoutCompat.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rootParams.topMargin = dp(8);
        root.setLayoutParams(rootParams);

        MaterialTextView title = text(source.getName(), 15, Color.BLACK, true);
        root.addView(title);
        MaterialTextView status = text(getString(R.string.web_home_extension_user_source_status, source.isEnabled() ? getString(R.string.setting_enable) : getString(R.string.setting_disable)), 12, source.isEnabled() ? Color.parseColor("#137333") : Color.parseColor("#6F7378"), false);
        root.addView(status);
        addDetail(root, getString(R.string.web_home_extension_site_scope, empty(source.getSiteKey())));
        addDetail(root, getString(R.string.web_home_extension_source, shortText(source.getRaw())));

        LinearLayoutCompat actions = new LinearLayoutCompat(requireContext());
        actions.setGravity(Gravity.CENTER_VERTICAL);
        actions.setOrientation(LinearLayoutCompat.HORIZONTAL);
        LinearLayoutCompat.LayoutParams actionParams = new LinearLayoutCompat.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        actionParams.topMargin = dp(7);
        root.addView(actions, actionParams);

        MaterialButton toggle = sourceActionButton(source.isEnabled() ? R.string.setting_disable : R.string.setting_enable, !source.isEnabled(), false);
        toggle.setOnClickListener(view -> {
            WebHomeExtensionSourceStore.setEnabled(source.getId(), !source.isEnabled());
            onSourceSaved();
        });
        actions.addView(toggle, actionLayout(0));

        MaterialButton edit = sourceActionButton(R.string.dialog_edit, true, false);
        edit.setOnClickListener(view -> editSource(source));
        actions.addView(edit, actionLayout(8));

        MaterialButton delete = sourceActionButton(R.string.setting_delete, false, true);
        delete.setOnClickListener(view -> deleteSource(source));
        actions.addView(delete, actionLayout(8));
        return root;
    }

    private LinearLayoutCompat.LayoutParams actionLayout(int marginStart) {
        LinearLayoutCompat.LayoutParams params = new LinearLayoutCompat.LayoutParams(0, dp(36), 1);
        params.leftMargin = dp(marginStart);
        return params;
    }

    private MaterialButton sourceActionButton(int text, boolean tonal, boolean danger) {
        MaterialButton button = new MaterialButton(requireContext());
        button.setText(text);
        button.setMinWidth(0);
        button.setMinHeight(dp(36));
        button.setMinimumHeight(dp(36));
        button.setPadding(dp(6), 0, dp(6), 0);
        ColorStateList bg = ContextCompat.getColorStateList(requireContext(), tonal ? R.color.dialog_tonal_button_bg : R.color.dialog_outlined_button_bg);
        ColorStateList fg = danger ? ColorStateList.valueOf(Color.parseColor("#B3261E")) : ContextCompat.getColorStateList(requireContext(), tonal ? R.color.dialog_tonal_button_text : R.color.dialog_outlined_button_text);
        button.setBackgroundTintList(bg);
        button.setTextColor(fg);
        if (!tonal) {
            button.setStrokeColor(ContextCompat.getColorStateList(requireContext(), R.color.dialog_outlined_button_stroke));
            button.setStrokeWidth(dp(1));
        }
        return button;
    }

    private void editSource(WebHomeExtensionSourceStore.Entry source) {
        if (source == null) {
            String[] items = {getString(R.string.web_home_extension_add_external), getString(R.string.web_home_extension_add_code)};
            new MaterialAlertDialogBuilder(requireActivity(), R.style.ThemeOverlay_WebHTV_LightDialog)
                    .setTitle(R.string.web_home_extension_add_source)
                    .setItems(items, (dialog, which) -> {
                        if (which == 0) editRawSource(null);
                        else editCodeSource(null);
                    })
                    .show();
            return;
        }
        if (WebHomeExtensionSourceStore.isCodeSource(source)) editCodeSource(source);
        else editRawSource(source);
    }

    private void editRawSource(WebHomeExtensionSourceStore.Entry source) {
        TextInputEditText input = createInput(true);
        input.setMinLines(3);
        input.setMaxLines(8);
        if (source != null) input.setText(source.getRaw());
        setupScrollableText(input);
        TextInputLayout layout = inputLayout(R.string.web_home_extension_source_hint, input);
        AlertDialog dialog = new MaterialAlertDialogBuilder(requireActivity(), R.style.ThemeOverlay_WebHTV_LightDialog)
                .setTitle(source == null ? R.string.web_home_extension_add_external : R.string.web_home_extension_edit_source)
                .setView(inputPanel(layout))
                .setNegativeButton(R.string.dialog_negative, null)
                .setPositiveButton(R.string.dialog_positive, null)
                .create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            try {
                WebHomeExtensionSourceStore.save(source == null ? "" : source.getId(), inputText(input), source == null || source.isEnabled(), currentSiteKey(source));
                dialog.dismiss();
                onSourceSaved();
            } catch (Throwable e) {
                layout.setError(errorText(e));
            }
        }));
        dialog.show();
    }

    private void editCodeSource(WebHomeExtensionSourceStore.Entry source) {
        TextInputEditText name = createInput(false);
        TextInputEditText code = createInput(true);
        name.setText(source == null ? getString(R.string.web_home_extension_local_code_default, WebHomeExtensionSourceStore.list().size() + 1) : source.getName());
        code.setMinLines(10);
        code.setMaxLines(18);
        code.setText(source == null ? "GM_log('ready');\n" : WebHomeExtensionSourceStore.code(source));
        setupScrollableText(code);
        TextInputLayout nameLayout = inputLayout(R.string.web_home_extension_name_hint, name);
        TextInputLayout codeLayout = inputLayout(R.string.web_home_extension_code_hint, code);
        AlertDialog dialog = new MaterialAlertDialogBuilder(requireActivity(), R.style.ThemeOverlay_WebHTV_LightDialog)
                .setTitle(source == null ? R.string.web_home_extension_add_code : R.string.web_home_extension_edit_code)
                .setView(inputPanel(nameLayout, codeLayout))
                .setNegativeButton(R.string.dialog_negative, null)
                .setPositiveButton(R.string.dialog_positive, null)
                .create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            try {
                WebHomeExtensionSourceStore.saveCode(source == null ? "" : source.getId(), inputText(name), inputText(code), source == null || source.isEnabled(), currentSiteKey(source));
                dialog.dismiss();
                onSourceSaved();
            } catch (Throwable e) {
                codeLayout.setError(errorText(e));
            }
        }));
        dialog.show();
    }

    private void deleteSource(WebHomeExtensionSourceStore.Entry source) {
        new MaterialAlertDialogBuilder(requireActivity(), R.style.ThemeOverlay_WebHTV_LightDialog)
                .setTitle(R.string.web_home_extension_delete_source_title)
                .setMessage(getString(R.string.web_home_extension_delete_source_message, source.getName()))
                .setNegativeButton(R.string.dialog_negative, null)
                .setPositiveButton(R.string.setting_delete, (dialog, which) -> {
                    WebHomeExtensionSourceStore.remove(source.getId());
                    onSourceSaved();
                })
                .show();
    }

    private void onSourceSaved() {
        WebHomeExtensionRegistry.get().clear();
        HomeWebController.requestExtensionReload();
        refresh(false);
        Notify.show(R.string.web_home_extension_source_saved);
    }

    private TextInputEditText createInput(boolean multiline) {
        TextInputEditText input = new TextInputEditText(requireContext());
        input.setSelectAllOnFocus(false);
        input.setSingleLine(!multiline);
        input.setTextColor(Color.BLACK);
        input.setHintTextColor(Color.parseColor("#666666"));
        input.setInputType(multiline ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setGravity(multiline ? Gravity.START | Gravity.TOP : Gravity.CENTER_VERTICAL);
        return input;
    }

    private TextInputLayout inputLayout(int hint, TextInputEditText input) {
        TextInputLayout layout = new TextInputLayout(requireContext());
        layout.setHint(hint);
        layout.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_OUTLINE);
        layout.setBoxBackgroundColor(Color.WHITE);
        layout.setBoxStrokeColor(ResUtil.getColor(R.color.dialog_outlined_button_stroke));
        layout.setHintTextColor(ColorStateList.valueOf(Color.parseColor("#5F6368")));
        layout.addView(input, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return layout;
    }

    private View inputPanel(TextInputLayout... layouts) {
        LinearLayoutCompat container = new LinearLayoutCompat(requireContext());
        container.setOrientation(LinearLayoutCompat.VERTICAL);
        container.setPadding(dp(20), dp(8), dp(20), 0);
        for (int i = 0; i < layouts.length; i++) {
            LinearLayoutCompat.LayoutParams params = new LinearLayoutCompat.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (i > 0) params.topMargin = dp(10);
            container.addView(layouts[i], params);
        }
        return container;
    }

    private void setupScrollableText(EditText input) {
        input.setHorizontallyScrolling(true);
        input.setHorizontalScrollBarEnabled(true);
        input.setVerticalScrollBarEnabled(true);
        input.setOnTouchListener((view, event) -> {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) view.post(() -> disallowParentIntercept(view, false));
            else disallowParentIntercept(view, true);
            return false;
        });
    }

    private void disallowParentIntercept(View view, boolean disallow) {
        ViewParent parent = view.getParent();
        while (parent != null) {
            parent.requestDisallowInterceptTouchEvent(disallow);
            parent = parent.getParent();
        }
    }

    private String inputText(EditText input) {
        return input.getText() == null ? "" : input.getText().toString().trim();
    }

    private String errorText(Throwable e) {
        return "empty".equals(e.getMessage()) ? getString(R.string.web_home_extension_source_empty) : getString(R.string.web_home_extension_source_invalid);
    }

    private String currentSiteKey(WebHomeExtensionSourceStore.Entry source) {
        if (source != null && !TextUtils.isEmpty(source.getSiteKey())) return source.getSiteKey();
        return VodConfig.get().getHome().getKey();
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
            HomeWebController.requestExtensionReload();
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
                HomeWebController.requestExtensionReload();
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

    private String shortText(String value) {
        if (TextUtils.isEmpty(value)) return getString(R.string.none);
        return value.length() <= 180 ? value : value.substring(0, 180) + "...";
    }

    private int dp(int value) {
        return ResUtil.dp2px(value);
    }
}
