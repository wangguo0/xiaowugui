package com.fongmi.android.tv.ui.dialog;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
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
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.LinearLayoutCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.databinding.DialogWebHomeExtensionBinding;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.utils.FileChooser;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.web.HomeWebController;
import com.fongmi.android.tv.web.ext.WebHomeExtensionRegistry;
import com.fongmi.android.tv.web.ext.WebHomeExtensionSourceStore;
import com.github.catvod.utils.Path;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.google.android.material.textview.MaterialTextView;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

public class WebHomeExtensionDialog extends BaseAlertDialog {

    private DialogWebHomeExtensionBinding binding;
    private Runnable callback;
    private boolean enabled;
    private boolean textMode;
    private WebHomeExtensionSourceStore.Entry pendingFileEdit;

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
        binding.modeGroup.check(R.id.uiMode);
        setupScrollableText(binding.jsonText);
        showTextMode(false);
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
        binding.debug.setOnClickListener(view -> WebHomeExtensionDebugDialog.show(requireActivity(), callback));
        binding.modeGroup.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (!isChecked) return;
            if (checkedId == R.id.textMode) showTextMode(true);
            if (checkedId == R.id.uiMode && !showTextMode(false)) binding.modeGroup.check(R.id.textMode);
        });
        binding.refresh.setOnClickListener(view -> refresh(true));
        binding.report.setOnClickListener(view -> showReport());
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

    private boolean showTextMode(boolean text) {
        if (text == textMode) {
            updateModeVisibility();
            return true;
        }
        if (text) {
            binding.jsonText.setText(new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(WebHomeExtensionSourceStore.list()));
        } else if (!saveTextSources(true)) {
            return false;
        }
        textMode = text;
        updateModeVisibility();
        return true;
    }

    private void updateModeVisibility() {
        binding.contentScroll.setVisibility(textMode ? View.GONE : View.VISIBLE);
        binding.jsonLayout.setVisibility(textMode ? View.VISIBLE : View.GONE);
        binding.add.setVisibility(textMode ? View.GONE : View.VISIBLE);
    }

    private boolean saveTextSources(boolean notify) {
        try {
            WebHomeExtensionSourceStore.saveRawJson(inputText(binding.jsonText));
            onSourceSaved(false);
            return true;
        } catch (Throwable e) {
            if (notify) Notify.show(R.string.web_home_extension_source_invalid);
            return false;
        }
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
        if (textMode && !saveTextSources(true)) return;
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
        List<RowModel> rows = rows(snapshot);
        String siteKey = TextUtils.isEmpty(snapshot.siteKey) ? getString(R.string.web_home_extension_unknown_site) : snapshot.siteKey;
        binding.summary.setText(getString(R.string.web_home_extension_summary, snapshot.sourceCount, snapshot.installedCount, snapshot.matchedCount, snapshot.readyCount, siteKey));
        trimRows();
        binding.empty.setVisibility(rows.isEmpty() ? View.VISIBLE : View.GONE);
        for (RowModel row : rows) binding.list.addView(row(row));
    }

    private List<RowModel> rows(WebHomeExtensionRegistry.Snapshot snapshot) {
        List<RowModel> rows = new ArrayList<>();
        for (WebHomeExtensionSourceStore.Entry source : WebHomeExtensionSourceStore.list()) {
            WebHomeExtensionRegistry.Item matched = findBySource(snapshot.items, source.getRaw());
            rows.add(new RowModel(source, matched));
        }
        for (WebHomeExtensionRegistry.Item item : snapshot.items) {
            if (findByItem(rows, item)) continue;
            rows.add(new RowModel(null, item));
        }
        return rows;
    }

    private WebHomeExtensionRegistry.Item findBySource(List<WebHomeExtensionRegistry.Item> items, String raw) {
        for (WebHomeExtensionRegistry.Item item : items) if (matchesRaw(item, raw)) return item;
        return null;
    }

    private boolean findByItem(List<RowModel> rows, WebHomeExtensionRegistry.Item item) {
        for (RowModel row : rows) if (row.item == item) return true;
        return false;
    }

    private boolean matchesRaw(WebHomeExtensionRegistry.Item item, String raw) {
        if (item == null || TextUtils.isEmpty(raw)) return false;
        if (!TextUtils.isEmpty(item.sourceUrl) && raw.contains(item.sourceUrl)) return true;
        if (raw.contains("\"id\"") && raw.contains(item.id)) return true;
        if (raw.contains("\"name\"") && raw.contains(item.name)) return true;
        return false;
    }

    private void trimRows() {
        while (binding.list.getChildCount() > 1) binding.list.removeViewAt(1);
    }

    private View row(RowModel model) {
        WebHomeExtensionSourceStore.Entry source = model.source;
        WebHomeExtensionRegistry.Item item = model.item;
        LinearLayoutCompat root = new LinearLayoutCompat(requireContext());
        root.setOrientation(LinearLayoutCompat.VERTICAL);
        root.setPadding(dp(10), dp(8), dp(10), dp(8));
        root.setBackground(rowBackground());
        LinearLayoutCompat.LayoutParams rootParams = new LinearLayoutCompat.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rootParams.topMargin = dp(8);
        root.setLayoutParams(rootParams);

        MaterialTextView title = text(model.name(), 15, Color.BLACK, true);
        root.addView(title);
        MaterialTextView status = text(model.status(), 12, statusColor(model.statusKey()), false);
        root.addView(status);
        if (source != null) addDetail(root, getString(R.string.web_home_extension_site_scope, empty(source.getSiteKey())));
        addDetail(root, getString(R.string.web_home_extension_source, shortText(model.source())));
        if (item != null) {
            addDetail(root, getString(R.string.web_home_extension_match, empty(item.matchText)));
            if (!TextUtils.isEmpty(item.excludeText)) addDetail(root, getString(R.string.web_home_extension_exclude, item.excludeText));
            if (!TextUtils.isEmpty(item.dependsText)) addDetail(root, getString(R.string.web_home_extension_depends, item.dependsText));
            if (!TextUtils.isEmpty(item.reason)) addDetail(root, item.reason);
            if (!TextUtils.isEmpty(item.lastLog)) addDetail(root, getString(R.string.web_home_extension_last_log, item.lastLog));
        }

        LinearLayoutCompat actions = new LinearLayoutCompat(requireContext());
        actions.setGravity(Gravity.CENTER_VERTICAL);
        actions.setOrientation(LinearLayoutCompat.HORIZONTAL);
        LinearLayoutCompat.LayoutParams actionParams = new LinearLayoutCompat.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        actionParams.topMargin = dp(7);
        root.addView(actions, actionParams);

        boolean rowEnabled = model.enabled();
        MaterialButton toggle = sourceActionButton(rowEnabled ? R.string.setting_disable : R.string.setting_enable, !rowEnabled, false);
        toggle.setOnClickListener(view -> {
            if (source != null) {
                WebHomeExtensionSourceStore.setEnabled(source.getId(), !source.isEnabled());
                onSourceSaved();
            } else if (item != null) {
                toggle(item);
            }
        });
        actions.addView(toggle, actionLayout(0));

        MaterialButton edit = sourceActionButton(R.string.dialog_edit, true, false);
        edit.setVisibility(source == null ? View.GONE : View.VISIBLE);
        edit.setOnClickListener(view -> editSource(source));
        actions.addView(edit, actionLayout(8));

        MaterialButton delete = sourceActionButton(R.string.setting_delete, false, true);
        delete.setVisibility(source == null ? View.GONE : View.VISIBLE);
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
            String[] items = {
                    getString(R.string.web_home_extension_add_file),
                    getString(R.string.web_home_extension_add_link),
                    getString(R.string.web_home_extension_add_code),
                    getString(R.string.web_home_extension_add_form)
            };
            new MaterialAlertDialogBuilder(requireActivity(), R.style.ThemeOverlay_WebHTV_LightDialog)
                    .setTitle(R.string.web_home_extension_add_source)
                    .setItems(items, (dialog, which) -> {
                        if (which == 0) chooseFile(null);
                        else if (which == 1) editRawSource(null);
                        else if (which == 2) editCodeSource(null);
                        else editFormSource(null);
                    })
                    .show();
            return;
        }
        if (WebHomeExtensionSourceStore.isCodeSource(source)) {
            editCodeSource(source);
            return;
        }
        String[] items = {
                getString(R.string.web_home_extension_add_form),
                getString(R.string.web_home_extension_add_link),
                getString(R.string.web_home_extension_add_file)
        };
        new MaterialAlertDialogBuilder(requireActivity(), R.style.ThemeOverlay_WebHTV_LightDialog)
                .setTitle(R.string.web_home_extension_edit_source)
                .setItems(items, (dialog, which) -> {
                    if (which == 0) editFormSource(source);
                    else if (which == 1) editRawSource(source);
                    else chooseFile(source);
                })
                .show();
    }

    private void chooseFile(WebHomeExtensionSourceStore.Entry source) {
        pendingFileEdit = source;
        FileChooser.from(fileLauncher).show("text/*", new String[]{"text/*", "application/javascript", "application/json", "application/octet-stream"});
    }

    private final ActivityResultLauncher<Intent> fileLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
        if (result.getResultCode() != Activity.RESULT_OK || result.getData() == null || result.getData().getData() == null) {
            pendingFileEdit = null;
            return;
        }
        String path = FileChooser.getPathFromUri(result.getData().getData());
        if (TextUtils.isEmpty(path)) {
            pendingFileEdit = null;
            return;
        }
        String code = Path.read(Path.local(path));
        if (TextUtils.isEmpty(code)) {
            Notify.show(R.string.web_home_extension_source_empty);
            pendingFileEdit = null;
            return;
        }
        WebHomeExtensionSourceStore.Entry source = pendingFileEdit;
        String name = path.substring(path.lastIndexOf('/') + 1);
        if (isJsonFile(name, code)) {
            WebHomeExtensionSourceStore.save(source == null ? "" : source.getId(), code, source == null || source.isEnabled(), currentSiteKey(source));
        } else {
            WebHomeExtensionSourceStore.saveCode(source == null ? "" : source.getId(), name, fileCode(name, code), source == null || source.isEnabled(), currentSiteKey(source));
        }
        pendingFileEdit = null;
        onSourceSaved();
    });

    private boolean isJsonFile(String name, String code) {
        String lower = name == null ? "" : name.toLowerCase();
        String value = code == null ? "" : code.trim();
        return lower.endsWith(".json") || value.startsWith("{") || value.startsWith("[");
    }

    private String fileCode(String name, String code) {
        String lower = name == null ? "" : name.toLowerCase();
        if (lower.endsWith(".css")) return "GM_addStyle(" + App.gson().toJson(code) + ");";
        return code;
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

    private void editFormSource(WebHomeExtensionSourceStore.Entry source) {
        TextInputEditText name = createInput(false);
        TextInputEditText extensionId = createInput(false);
        TextInputEditText runAt = createInput(false);
        TextInputEditText jsUrl = createInput(false);
        TextInputEditText match = createInput(false);
        fillForm(source, name, extensionId, runAt, jsUrl, match);
        TextInputLayout jsLayout = inputLayout(R.string.web_home_extension_js_hint, jsUrl);
        AlertDialog dialog = new MaterialAlertDialogBuilder(requireActivity(), R.style.ThemeOverlay_WebHTV_LightDialog)
                .setTitle(source == null ? R.string.web_home_extension_add_form : R.string.web_home_extension_edit_form)
                .setView(inputPanel(
                        inputLayout(R.string.web_home_extension_name_hint, name),
                        inputLayout(R.string.web_home_extension_id_hint, extensionId),
                        inputLayout(R.string.web_home_extension_run_at_hint, runAt),
                        jsLayout,
                        inputLayout(R.string.web_home_extension_match_hint, match)
                ))
                .setNegativeButton(R.string.dialog_negative, null)
                .setPositiveButton(R.string.dialog_positive, null)
                .create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            try {
                WebHomeExtensionSourceStore.saveForm(source == null ? "" : source.getId(), inputText(name), inputText(extensionId), inputText(runAt), inputText(jsUrl), inputText(match), source == null || source.isEnabled(), currentSiteKey(source));
                dialog.dismiss();
                onSourceSaved();
            } catch (Throwable e) {
                jsLayout.setError(errorText(e));
            }
        }));
        dialog.show();
    }

    private void fillForm(WebHomeExtensionSourceStore.Entry source, TextInputEditText name, TextInputEditText extensionId, TextInputEditText runAt, TextInputEditText jsUrl, TextInputEditText match) {
        runAt.setText("document-end");
        match.setText(VodConfig.get().getHome().getKey());
        if (source == null) return;
        try {
            JsonElement element = WebHomeExtensionSourceStore.parse(source.getRaw());
            if (!element.isJsonObject()) {
                jsUrl.setText(source.getRaw());
                return;
            }
            JsonObject object = element.getAsJsonObject();
            name.setText(safeString(object, "name"));
            extensionId.setText(safeString(object, "id"));
            String value = safeString(object, "runAt");
            if (!TextUtils.isEmpty(value)) runAt.setText(value);
            if (object.has("js") && object.get("js").isJsonArray() && object.getAsJsonArray("js").size() > 0) jsUrl.setText(object.getAsJsonArray("js").get(0).getAsString());
            else jsUrl.setText(first(object, "manifestUrl", "manifest", "sourceUrl", "url"));
            if (object.has("cspKeyRegex") && object.get("cspKeyRegex").isJsonArray() && object.getAsJsonArray("cspKeyRegex").size() > 0) match.setText(object.getAsJsonArray("cspKeyRegex").get(0).getAsString());
        } catch (Throwable e) {
            jsUrl.setText(source.getRaw());
        }
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
        onSourceSaved(true);
    }

    private void onSourceSaved(boolean notify) {
        WebHomeExtensionRegistry.get().clear();
        HomeWebController.requestExtensionReload();
        refresh(false);
        if (notify) Notify.show(R.string.web_home_extension_source_saved);
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

    private String safeString(JsonObject object, String key) {
        try {
            return object.getAsJsonPrimitive(key).getAsString().trim();
        } catch (Throwable e) {
            return "";
        }
    }

    private String first(JsonObject object, String... keys) {
        for (String key : keys) {
            String value = safeString(object, key);
            if (!TextUtils.isEmpty(value)) return value;
        }
        return "";
    }

    private String currentSiteKey(WebHomeExtensionSourceStore.Entry source) {
        if (source != null && !TextUtils.isEmpty(source.getSiteKey())) return source.getSiteKey();
        return VodConfig.get().getHome().getKey();
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

    private class RowModel {
        private final WebHomeExtensionSourceStore.Entry source;
        private final WebHomeExtensionRegistry.Item item;

        private RowModel(WebHomeExtensionSourceStore.Entry source, WebHomeExtensionRegistry.Item item) {
            this.source = source;
            this.item = item;
        }

        private String name() {
            if (item != null) return item.name + (TextUtils.isEmpty(item.version) ? "" : " " + item.version);
            return source == null ? "" : source.getName();
        }

        private String status() {
            String state = item != null ? statusText(item) : (source != null && source.isEnabled() ? getString(R.string.setting_enable) : getString(R.string.setting_disable));
            String id = item != null ? item.id : source == null ? "" : source.getId();
            String runAt = item == null ? "" : " · " + item.runAt;
            return id + runAt + " · " + state;
        }

        private String statusKey() {
            if (item != null) return item.status;
            return source != null && source.isEnabled() ? "matched" : "disabled";
        }

        private String source() {
            if (source != null) return source.getRaw();
            return item == null ? "" : WebHomeExtensionDialog.this.source(item);
        }

        private boolean enabled() {
            if (source != null) return source.isEnabled();
            return item != null && item.enabled;
        }
    }
}
