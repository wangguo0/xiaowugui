package com.fongmi.android.tv.ui.dialog;

import android.app.Dialog;
import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.view.inputmethod.EditorInfo;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.FragmentActivity;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.CommunityProbe;
import com.fongmi.android.tv.api.SourceProbe;
import com.fongmi.android.tv.api.TrialRun;
import com.fongmi.android.tv.api.config.LiveConfig;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.api.config.WallConfig;
import com.fongmi.android.tv.bean.Config;
import com.fongmi.android.tv.db.AppDatabase;
import com.fongmi.android.tv.databinding.DialogConfigBinding;
import com.fongmi.android.tv.event.ServerEvent;
import com.fongmi.android.tv.impl.ConfigListener;
import com.fongmi.android.tv.server.Server;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.ui.custom.CustomTextListener;
import com.fongmi.android.tv.utils.FileChooser;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.QRCode;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.utils.Task;
import com.github.catvod.utils.Path;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.util.Objects;

public class ConfigDialog extends BaseAlertDialog {

    private DialogConfigBinding binding;
    private boolean append = true;
    private boolean edit;
    private String url;
    private int type;

    public static ConfigDialog create() {
        return new ConfigDialog();
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

    public void show(FragmentActivity activity) {
        show(activity.getSupportFragmentManager(), null);
    }

    @Override
    @NonNull
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        Dialog dialog = LightDialog.create(requireContext(), getDialogTitle(), getBinding().getRoot());
        initView();
        initEvent();
        return dialog;
    }

    @Override
    protected ViewBinding getBinding() {
        return binding = DialogConfigBinding.inflate(getLayoutInflater());
    }

    @Override
    protected MaterialAlertDialogBuilder getBuilder() {
        return builder().setView(getBinding().getRoot());
    }

    @Override
    protected void initView() {
        Config config = getConfig();
        binding.name.setText(edit ? config.getName() : "");
        binding.text.setText(url = config.getUrl());
        binding.text.setSelection(TextUtils.isEmpty(url) ? 0 : url.length());
        binding.positive.setText(edit ? R.string.dialog_edit : R.string.dialog_positive);
        binding.code.setImageBitmap(QRCode.getLightBitmap(Server.get().getAddress(4), 200, 0));
        binding.info.setText(ResUtil.getString(R.string.push_info, Server.get().getAddress()).replace("\uff0c", "\n"));
    }

    @Override
    protected void initEvent() {
        binding.choose.setOnClickListener(this::onChoose);
        binding.positive.setOnClickListener(this::onPositive);
        binding.negative.setOnClickListener(this::onNegative);
        binding.text.addTextChangedListener(new CustomTextListener() {
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                detect(s.toString());
            }
        });
        binding.text.setOnEditorActionListener((textView, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) binding.positive.performClick();
            return true;
        });
    }

    private String getUrl() {
        return switch (type) {
            case 0 -> VodConfig.getUrl();
            case 1 -> LiveConfig.getUrl();
            case 2 -> WallConfig.getUrl();
            default -> "";
        };
    }

    private Config getConfig() {
        return switch (type) {
            case 0 -> VodConfig.get().getConfig();
            case 1 -> LiveConfig.get().getConfig();
            case 2 -> WallConfig.get().getConfig();
            default -> Config.create(type);
        };
    }

    private Config getStoredConfig() {
        return switch (type) {
            case 0 -> Config.vod();
            case 1 -> Config.live();
            case 2 -> Config.wall();
            default -> Config.create(type);
        };
    }

    private int getTypeName() {
        return switch (type) {
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
            binding.text.append("ttp://");
        } else if (append && "f".equalsIgnoreCase(s)) {
            append = false;
            binding.text.append("ile://");
        } else if (append && "a".equalsIgnoreCase(s)) {
            append = false;
            binding.text.append("ssets://");
        } else if (s.length() > 1) {
            append = false;
        } else if (s.isEmpty()) {
            append = true;
        }
    }

    private void onPositive(View view) {
        String text = SourceProbe.cleanUrl(binding.text.getText().toString());
        String name = binding.name.getText().toString().trim();
        if (text.isEmpty()) {
            finishSave(saveConfig(text, name));
            return;
        }
        // 添加前置静态扫描（开关开启，http 与本地文件源通用）：弹窗展示进度，命中恶意特征阻止添加
        if (needStaticScan(text, type)) {
            ProbeScanDialog scan = ProbeScanDialog.create();
            scan.show(requireActivity());
            binding.positive.setEnabled(false);
            Task.submitLarge(() -> {
                boolean dangerous = SourceProbe.scanDangerous(text, type, scan);
                App.post(() -> {
                    scan.dismissAllowingStateLoss();
                    if (!isAdded()) return;
                    binding.positive.setEnabled(true);
                    if (dangerous) {
                        ProbeDialog.showScanDanger(requireActivity());
                        return;
                    }
                    if (!proceed(text, type)) return;
                    Config saved = saveConfig(text, name);
                    if (saved != null && !edit) {
                        CommunityProbe.markAdded(text);
                        CommunityProbe.enqueue(text, type);
                    }
                    finishSave(saved);
                });
            });
            return;
        }
        // 统一安全检测闸门；编辑未改地址则沿用原配置
        if (!proceed(text, type)) return;
        Config saved = saveConfig(text, name);
        if (saved != null && !edit && (type == 0 || type == 1)) {
            CommunityProbe.track(text, type);
        }
        finishSave(saved);
    }

    // 需前置静态扫描：开关开启 + 点播/直播 + http 或本地文件源（本软件合并产物豁免、编辑未改地址）
    private boolean needStaticScan(String url, int scanType) {
        if (!Setting.isProbeAdd() || (scanType != 0 && scanType != 1)) return false;
        if (edit && url.equals(this.url)) return false;
        if (url.startsWith("file:") && SourceProbe.isSelfMerged(url)) return false;
        return url.startsWith("http://") || url.startsWith("https://") || url.startsWith("file:");
    }

    /**
     * 统一安全检测闸门（添加订阅开关开启时生效）：http 与「非本软件合并」的本地文件源
     * 均进入 8 秒试运行；本软件合并的源免检测（合并时已对各输入源逐一检测）。
     * 永久黑名单地址直接拦截并返回 false。
     */
    private boolean proceed(String url, int scanType) {
        boolean needTrial = Setting.isProbeAdd() && (scanType == 0 || scanType == 1) && isProbeable(url) && !(edit && url.equals(this.url));
        if (!needTrial) {
            markEnableChange(url, scanType);
            return true;
        }
        // 永久黑名单：曾被试运行实锤崩溃/杀进程的地址直接拦截
        if (SourceProbe.isRuntimeDangerous(url)) {
            ProbeDialog.showDanger(requireActivity());
            return false;
        }
        // 永久白名单：曾通过试运行的地址直接添加，免重复试用
        if (!SourceProbe.isRuntimePassed(url)) {
            TrialRun.begin(url, scanType);
            Notify.show(R.string.source_probe_trial_started);
        } else {
            // 缓存直接 PASS → 直接添加免试运行，成功即自动设全部站点为「参与换源」
            markEnableChange(url, scanType);
        }
        return true;
    }

    // 仅点播类型、且为新增链接（编辑未改地址除外）时记录待自动「参与换源」标记
    private void markEnableChange(String url, int scanType) {
        if (scanType != 0) return;
        if (edit && url.equals(this.url)) return;
        VodConfig.markEnableChange(url);
    }

    private boolean isHttp(String url) {
        return url.startsWith("http://") || url.startsWith("https://");
    }

    // 本地文件源需检测；本软件合并产物（头部含标记）豁免
    private boolean isProbeable(String url) {
        return isHttp(url) || (url.startsWith("file:") && !SourceProbe.isSelfMerged(url));
    }

    private void finishSave(Config config) {
        if (config == null) {
            Notify.show(R.string.remote_trust_config_url_required);
            binding.text.requestFocus();
            return;
        }
        ((ConfigListener) requireActivity()).setConfig(config);
        dismiss();
    }

    private Config saveConfig(String text, String name) {
        if (text.isEmpty()) {
            if (!edit) return null;
            if (!TextUtils.isEmpty(url)) Config.delete(url, type);
            return getStoredConfig();
        } else if (edit) {
            return Config.find(url, type).url(text).name(name).update();
        } else {
            Config exists = AppDatabase.get().getConfigDao().find(text, type);
            return exists != null ? exists : Config.create(type).url(text).name(name).update();
        }
    }

    private void onNegative(View view) {
        dismiss();
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onServerEvent(ServerEvent event) {
        if (event.type() != ServerEvent.Type.SETTING) return;
        binding.name.setText(event.name());
        binding.text.setText(event.text());
        binding.text.setSelection(binding.text.getText().length());
    }

    @Override
    public void onStart() {
        super.onStart();
        setWidth(0.55f);
        EventBus.getDefault().register(this);
    }

    @Override
    public void onStop() {
        super.onStop();
        EventBus.getDefault().unregister(this);
    }

    private final ActivityResultLauncher<Intent> launcher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
        if (result.getResultCode() != Activity.RESULT_OK || result.getData() == null || result.getData().getData() == null) return;
        FragmentActivity activity = requireActivity();
        String path = Objects.toString(FileChooser.getPathFromUri(result.getData().getData()), "");
        if (TextUtils.isEmpty(path)) return;
        App.post(() -> {
            if (activity.isFinishing() || activity.isDestroyed()) return;
            String url = "file:/" + path.replace(Path.rootPath(), "");
            // 本地文件添加同样经过安全检测闸门（本软件合并的源免检测）
            if (!proceed(url, type)) return;
            dismissAllowingStateLoss();
            App.post(() -> ((ConfigListener) activity).setConfig(saveConfig(url, binding.name.getText().toString().trim())), 100);
        }, 100);
    });
}
