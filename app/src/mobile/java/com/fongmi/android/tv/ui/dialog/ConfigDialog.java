package com.fongmi.android.tv.ui.dialog;

import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.SourceProbe;
import com.fongmi.android.tv.api.TrialRun;
import com.fongmi.android.tv.api.config.LiveConfig;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.api.config.WallConfig;
import com.fongmi.android.tv.bean.Config;
import com.fongmi.android.tv.db.AppDatabase;
import com.fongmi.android.tv.databinding.DialogConfigBinding;
import com.fongmi.android.tv.impl.ConfigListener;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.ui.custom.CustomTextListener;
import com.fongmi.android.tv.utils.FileChooser;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.utils.Task;
import com.github.catvod.utils.Path;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

public class ConfigDialog extends BaseAlertDialog {

    private DialogConfigBinding binding;
    private boolean append = true;
    private boolean edit;
    private String ori;
    private int type;
    private Config target;

    public static ConfigDialog create() {
        return new ConfigDialog();
    }

    public ConfigDialog target(Config target) {
        this.target = target;
        return this;
    }

    public ConfigDialog vod() {
        type = 0;
        return this;
    }

    public ConfigDialog live() {
        type = 1;
        return this;
    }

    public ConfigDialog wall() {
        type = 2;
        return this;
    }

    public ConfigDialog edit() {
        edit = true;
        return this;
    }

    public void show(Fragment fragment) {
        show(fragment.getChildFragmentManager(), null);
    }

    @Override
    protected ViewBinding getBinding() {
        return binding = DialogConfigBinding.inflate(getLayoutInflater());
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        Dialog dialog = super.onCreateDialog(savedInstanceState);
        // 仅允许通过「取消/保存」按钮关闭，避免误触弹窗外部导致输入内容丢失
        dialog.setCanceledOnTouchOutside(false);
        return dialog;
    }

    @Override
    protected MaterialAlertDialogBuilder getBuilder() {
        return new MaterialAlertDialogBuilder(requireActivity(), R.style.ThemeOverlay_WebHTV_LightDialog).setView(getBinding().getRoot());
    }

    @Override
    protected void initView() {
        Config config = getConfig();
        binding.title.setText(getDialogTitle());
        binding.positive.setText(edit ? R.string.dialog_edit : R.string.dialog_positive);
        binding.name.setText(edit ? config.getName() : "");
        binding.url.setText(ori = edit ? config.getUrl() : "");
        binding.url.setSelection(TextUtils.isEmpty(ori) ? 0 : ori.length());
    }

    @Override
    protected void initEvent() {
        binding.negative.setOnClickListener(v -> dismiss());
        binding.positive.setOnClickListener(v -> onPositive());
        binding.choose.setEndIconOnClickListener(this::onChoose);
        binding.url.addTextChangedListener(new CustomTextListener() {
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                detect(s.toString());
            }
        });
        binding.url.setOnEditorActionListener((textView, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) onPositive();
            return true;
        });
        binding.name.setOnEditorActionListener((textView, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) onPositive();
            return true;
        });
    }

    @Override
    public void onStart() {
        super.onStart();
        configureWindow();
        binding.url.requestFocus();
    }

    private Config getConfig() {
        if (target != null) return target;
        return switch (type) {
            case 0 -> VodConfig.get().getConfig();
            case 1 -> LiveConfig.get().getConfig();
            case 2 -> WallConfig.get().getConfig();
            default -> null;
        };
    }

    private Config getStoredConfig() {
        if (target != null) return target;
        return switch (type) {
            case 0 -> Config.vod();
            case 1 -> Config.live();
            case 2 -> Config.wall();
            default -> Config.create(type);
        };
    }

    private int getType() {
        if (target != null) return target.getType();
        return type;
    }

    private int getTypeName() {
        return switch (getType()) {
            case 0 -> R.string.setting_vod;
            case 1 -> R.string.setting_live;
            case 2 -> R.string.setting_wall;
            default -> R.string.remote_trust_config_type;
        };
    }

    private String getDialogTitle() {
        int action = edit ? R.string.remote_trust_config_edit : R.string.remote_trust_config_add;
        return getString(R.string.setting_config_dialog_title, getString(action), getString(getTypeName()));
    }

    private void onChoose(View view) {
        FileChooser.from(launcher).show();
    }

    private void detect(String s) {
        if (append && "h".equalsIgnoreCase(s)) {
            append = false;
            binding.url.append("ttp://");
        } else if (append && "f".equalsIgnoreCase(s)) {
            append = false;
            binding.url.append("ile://");
        } else if (append && "a".equalsIgnoreCase(s)) {
            append = false;
            binding.url.append("ssets://");
        } else if (s.length() > 1) {
            append = false;
        } else if (s.isEmpty()) {
            append = true;
        }
    }

    private void onPositive() {
        String url = binding.url.getText().toString().trim();
        String name = binding.name.getText().toString().trim();
        if (url.isEmpty()) {
            finishSave(saveConfig(url, name));
            return;
        }
        // 添加前置静态扫描（开关开启，http 与本地文件源通用）：弹窗展示进度，命中恶意特征阻止添加
        if (needStaticScan(url)) {
            ProbeScanDialog scan = ProbeScanDialog.create();
            scan.show(requireActivity());
            binding.positive.setEnabled(false);
            Task.submitLarge(() -> {
                boolean dangerous = SourceProbe.scanDangerous(url, getType(), scan);
                App.post(() -> {
                    scan.dismissAllowingStateLoss();
                    if (!isAdded()) return;
                    binding.positive.setEnabled(true);
                    if (dangerous) {
                        ProbeDialog.showScanDanger(requireActivity());
                        return;
                    }
                    if (!proceed(url)) return;
                    finishSave(saveConfig(url, name));
                });
            });
            return;
        }
        // 统一安全检测闸门；编辑未改地址则沿用原配置
        if (!proceed(url)) return;
        finishSave(saveConfig(url, name));
    }

    // 需前置静态扫描：开关开启 + 点播/直播 + http 或本地文件源（本软件合并产物豁免、非编辑未改地址）
    private boolean needStaticScan(String url) {
        if (!Setting.isProbeAdd() || (getType() != 0 && getType() != 1)) return false;
        if (edit && url.equals(ori)) return false;
        if (url.startsWith("file:") && SourceProbe.isSelfMerged(url)) return false;
        return url.startsWith("http://") || url.startsWith("https://") || url.startsWith("file:");
    }

    /**
     * 统一安全检测闸门（添加订阅开关开启时生效）：http 与「非本软件合并」的本地文件源
     * 均进入 25 秒试运行；本软件合并的源免检测（合并时已对各输入源逐一检测）。
     * 永久黑名单地址直接拦截并返回 false。
     */
    private boolean proceed(String url) {
        boolean needTrial = Setting.isProbeAdd() && (getType() == 0 || getType() == 1) && isProbeable(url) && !(edit && url.equals(ori));
        if (!needTrial) {
            // 需求4：点播订阅「免试运行」（本软件合并免检/关闭检测等）添加成功后直接自动参与换源
            markEnableChange(url);
            return true;
        }
        // 永久黑名单：曾被试运行实锤崩溃/杀进程的地址直接拦截
        if (SourceProbe.isRuntimeDangerous(url)) {
            ProbeDialog.showDanger(requireActivity());
            return false;
        }
        // 永久白名单：曾通过试运行的地址直接添加，免重复试用
        if (!SourceProbe.isRuntimePassed(url)) {
            TrialRun.begin(url, getType());
            Notify.show(R.string.source_probe_trial_started);
        } else {
            // 需求4：缓存直接 PASS → 直接添加免试运行，成功即自动设全部站点为「参与换源」
            markEnableChange(url);
        }
        return true;
    }

    // 需求4：仅点播类型，且为新增链接（编辑未改地址除外）时记录待自动「参与换源」标记
    private void markEnableChange(String url) {
        if (getType() != 0) return;
        if (edit && url.equals(ori)) return;
        VodConfig.markEnableChange(url);
    }

    private void finishSave(Config config) {
        if (config == null) {
            Notify.show(R.string.remote_trust_config_url_required);
            binding.url.requestFocus();
            return;
        }
        ((ConfigListener) requireParentFragment()).setConfig(config);
        dismiss();
    }

    private boolean isHttp(String url) {
        return url.startsWith("http://") || url.startsWith("https://");
    }

    // 本地文件源需检测；本软件合并产物（头部含标记）豁免
    private boolean isProbeable(String url) {
        return isHttp(url) || (url.startsWith("file:") && !SourceProbe.isSelfMerged(url));
    }

    private Config saveConfig(String url, String name) {
        Config config;
        int saveType = getType();
        if (url.isEmpty()) {
            if (!edit) return null;
            if (!TextUtils.isEmpty(ori)) Config.delete(ori, saveType);
            return getStoredConfig();
        } else if (edit) {
            config = Config.find(ori, saveType).url(url).name(name).update();
        } else {
            Config exists = AppDatabase.get().getConfigDao().find(url, saveType);
            config = exists != null ? exists : Config.create(saveType).url(url).name(name).update();
        }
        return config;
    }

    private void configureWindow() {
        if (getDialog() == null || getDialog().getWindow() == null) return;
        Window window = getDialog().getWindow();
        WindowManager.LayoutParams params = window.getAttributes();
        boolean land = ResUtil.isLand(requireContext());
        int width = Math.min(Math.round(ResUtil.getScreenWidth(requireContext()) * (land ? 0.58f : 0.92f)), ResUtil.dp2px(560));
        params.width = Math.max(width, ResUtil.dp2px(320));
        params.height = WindowManager.LayoutParams.WRAP_CONTENT;
        params.gravity = Gravity.CENTER;
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        window.getDecorView().setPadding(0, 0, 0, 0);
        window.setAttributes(params);
        window.setLayout(params.width, WindowManager.LayoutParams.WRAP_CONTENT);
    }

    private final ActivityResultLauncher<Intent> launcher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
        if (result.getResultCode() != Activity.RESULT_OK || result.getData() == null || result.getData().getData() == null) return;
        String name = binding.name.getText().toString().trim();
        String path = FileChooser.getPathFromUri(result.getData().getData());
        if (TextUtils.isEmpty(path)) return;
        String url = "file:/" + path.replace(Path.rootPath(), "");
        // 本地文件添加同样经过安全检测闸门（本软件合并的源免检测）
        if (!proceed(url)) return;
        ((ConfigListener) requireParentFragment()).setConfig(saveConfig(url, name));
        dismiss();
    });
}
