package com.fongmi.android.tv.ui.dialog;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.LinearLayoutCompat;
import androidx.fragment.app.Fragment;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.SourceProbe;
import com.fongmi.android.tv.api.config.LiveConfig;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Config;
import com.fongmi.android.tv.databinding.DialogSourceMergeBinding;
import com.fongmi.android.tv.event.ConfigEvent;
import com.fongmi.android.tv.impl.Callback;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.utils.ConfigMerger;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.PermissionUtil;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.utils.Task;
import com.google.android.material.checkbox.MaterialCheckBox;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.google.android.material.textview.MaterialTextView;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class SourceMergeDialog extends BaseAlertDialog {

    private DialogSourceMergeBinding binding;
    private List<Config> configs = new ArrayList<>();
    private Set<String> selected = new HashSet<>();
    private int type;

    public static SourceMergeDialog create() {
        return new SourceMergeDialog();
    }

    public SourceMergeDialog vod() {
        type = 0;
        return this;
    }

    public SourceMergeDialog live() {
        type = 1;
        return this;
    }

    public void show(Fragment fragment) {
        show(fragment.getChildFragmentManager(), null);
    }

    @Override
    protected ViewBinding getBinding() {
        return binding = DialogSourceMergeBinding.inflate(getLayoutInflater());
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        Dialog dialog = super.onCreateDialog(savedInstanceState);
        dialog.setCanceledOnTouchOutside(false);
        return dialog;
    }

    @Override
    protected MaterialAlertDialogBuilder getBuilder() {
        return new MaterialAlertDialogBuilder(requireActivity(), R.style.ThemeOverlay_WebHTV_LightDialog).setView(getBinding().getRoot());
    }

    @Override
    protected void initView() {
        binding.title.setText(type == 0 ? R.string.source_merge_title_vod : R.string.source_merge_title_live);
        configs = Config.getAll(type);
        buildList();
        setupStrategy();
        addManualRow();
    }

    @Override
    protected void initEvent() {
        binding.negative.setOnClickListener(v -> dismiss());
        binding.positive.setOnClickListener(v -> onPositive());
        binding.manualAdd.setOnClickListener(v -> addManualRow());
    }

    @Override
    public void onStart() {
        super.onStart();
        configureWindow();
    }

    private void setupStrategy() {
        MaterialAutoCompleteTextView strategy = binding.strategy;
        String[] items = {getString(R.string.source_merge_keep_first), getString(R.string.source_merge_keep_all)};
        strategy.setSimpleItems(items);
        strategy.setText(items[0], false);
        strategy.setOnClickListener(v -> strategy.showDropDown());
    }

    private void buildList() {
        binding.sourceList.removeAllViews();
        if (configs.isEmpty()) {
            MaterialTextView empty = new MaterialTextView(requireContext());
            empty.setText(R.string.setting_subscription_empty);
            empty.setTextColor(Color.parseColor("#8A8F98"));
            empty.setTextSize(13);
            binding.sourceList.addView(empty);
            return;
        }
        for (Config config : configs) binding.sourceList.addView(buildRow(config));
    }

    private View buildRow(Config config) {
        LinearLayoutCompat row = new LinearLayoutCompat(requireContext());
        row.setOrientation(LinearLayoutCompat.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, 6, 0, 6);

        MaterialCheckBox check = new MaterialCheckBox(requireContext());
        check.setId(View.generateViewId());
        check.setChecked(selected.contains(config.getUrl()));
        check.setClickable(false);

        LinearLayoutCompat text = new LinearLayoutCompat(requireContext());
        text.setOrientation(LinearLayoutCompat.VERTICAL);
        text.setLayoutParams(new LinearLayoutCompat.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        MaterialTextView name = new MaterialTextView(requireContext());
        // name 可能为 null（Config.find 对未知地址插入的匿名记录），直接 isEmpty() 会 NPE
        name.setText(TextUtils.isEmpty(config.getName()) ? config.getUrl() : config.getName());
        name.setTextColor(Color.parseColor("#202124"));
        name.setTextSize(14);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);

        MaterialTextView url = new MaterialTextView(requireContext());
        url.setText(config.getUrl());
        url.setTextColor(Color.parseColor("#5F6368"));
        url.setTextSize(12);
        url.setSingleLine(true);
        url.setEllipsize(TextUtils.TruncateAt.END);

        text.addView(name);
        text.addView(url);
        row.addView(check);
        row.addView(text);

        row.setOnClickListener(v -> {
            boolean toggled = !check.isChecked();
            check.setChecked(toggled);
            if (toggled) selected.add(config.getUrl());
            else selected.remove(config.getUrl());
        });
        return row;
    }

    private void addManualRow() {
        LinearLayoutCompat row = new LinearLayoutCompat(requireContext());
        row.setOrientation(LinearLayoutCompat.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setLayoutParams(new LinearLayoutCompat.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        ((LinearLayoutCompat.LayoutParams) row.getLayoutParams()).topMargin = ResUtil.dp2px(8);

        TextInputLayout inputLayout = new TextInputLayout(requireContext());
        inputLayout.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_OUTLINE);
        inputLayout.setBoxBackgroundColor(Color.WHITE);
        inputLayout.setBoxStrokeColor(ResUtil.getColor(R.color.dialog_outlined_button_stroke));
        inputLayout.setHintTextColor(getColorStateList(R.color.dialog_outlined_button_stroke));
        inputLayout.setLayoutParams(new LinearLayoutCompat.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        TextInputEditText edit = new TextInputEditText(requireContext());
        edit.setSingleLine(true);
        edit.setHorizontallyScrolling(true);
        edit.setTextColor(Color.parseColor("#202124"));
        edit.setHintTextColor(Color.parseColor("#8A8F98"));
        edit.setTextSize(14);
        inputLayout.addView(edit);

        MaterialTextView remove = new MaterialTextView(requireContext());
        remove.setText("✕");
        remove.setTextColor(Color.parseColor("#5F6368"));
        remove.setTextSize(16);
        remove.setPadding(ResUtil.dp2px(12), 0, 0, 0);
        remove.setGravity(Gravity.CENTER);
        remove.setOnClickListener(v -> {
            binding.manualList.removeView(row);
            refreshManualRows();
        });

        row.addView(inputLayout);
        row.addView(remove);
        binding.manualList.addView(row);
        refreshManualRows();
    }

    // 逐行刷新序号提示与删除按钮可见性（仅剩一行时隐藏删除，保证至少保留一个输入框）
    private void refreshManualRows() {
        int count = binding.manualList.getChildCount();
        for (int i = 0; i < count; i++) {
            if (!(binding.manualList.getChildAt(i) instanceof LinearLayoutCompat row)) continue;
            if (row.getChildAt(0) instanceof TextInputLayout layout) layout.setHint(getString(R.string.source_merge_manual, i + 1));
            if (row.getChildAt(1) instanceof MaterialTextView remove) remove.setVisibility(count > 1 ? View.VISIBLE : View.GONE);
        }
    }

    private List<String> getManualUrls() {
        List<String> urls = new ArrayList<>();
        for (int i = 0; i < binding.manualList.getChildCount(); i++) {
            if (!(binding.manualList.getChildAt(i) instanceof LinearLayoutCompat row)) continue;
            if (!(row.getChildAt(0) instanceof TextInputLayout layout)) continue;
            android.widget.EditText edit = layout.getEditText();
            String url = edit == null || edit.getText() == null ? "" : edit.getText().toString().trim();
            if (!url.isEmpty()) urls.add(url);
        }
        return urls;
    }

    private android.content.res.ColorStateList getColorStateList(int res) {
        return getResources().getColorStateList(res);
    }

    private void onPositive() {
        String name = binding.mergeName.getText().toString().trim();
        if (name.isEmpty()) {
            Notify.show(R.string.source_merge_name_required);
            return;
        }
        List<String> urls = new ArrayList<>();
        for (Config config : configs) if (selected.contains(config.getUrl())) urls.add(config.getUrl());
        urls.addAll(getManualUrls());
        if (urls.isEmpty()) {
            Notify.show(R.string.source_merge_no_source);
            return;
        }
        boolean keepAll = getString(R.string.source_merge_keep_all).equals(binding.strategy.getText().toString().trim());
        PermissionUtil.requestFile(this, granted -> {
            if (!isAdded()) return;
            if (!granted) {
                Notify.show(R.string.source_merge_permission);
                return;
            }
            merge(name, urls, keepAll);
        });
    }

    private void merge(String name, List<String> urls, boolean keepAll) {
        setMerging(true);
        boolean probe = Setting.isProbeMerge();
        float probeWeight = probe ? 30f : 0f;
        int urlTotal = urls.size();
        ConfigMerger.ProgressCallback callback = new ConfigMerger.ProgressCallback() {

            @Override
            public void onProgress(int index, int total) {
                App.post(() -> {
                    if (!isAdded()) return;
                    mergeTotal = total;
                    binding.overlay.setVisibility(View.VISIBLE);
                    binding.progressText.setText(getString(R.string.source_merge_progress, Math.min(index, total), total));
                    binding.progressDetail.setText("");
                    setProgress(sliceEnd(index, total, probeWeight, 100f));
                });
            }

            @Override
            public void onSite(int sourceIndex, int count, String siteName) {
                // 源内按已解析站点数渐近推进（总数未知，count/(count+8) 平滑逼近该源切片末端）
                float frac = Math.min(0.95f, count / (count + 8f));
                App.post(() -> {
                    if (!isAdded() || mergeTotal <= 0) return;
                    binding.progressDetail.setText(getString(R.string.source_merge_site_detail, count));
                    float start = sliceStart(sourceIndex, mergeTotal, probeWeight, 100f);
                    float end = sliceEnd(sourceIndex, mergeTotal, probeWeight, 100f);
                    setProgress(start + (end - start) * frac);
                });
            }
        };
        Task.execute(() -> {
            // 合并前逐个安全检测（开关关闭则跳过）：危险/预警源强制移出合并名单。
            // 合并结果为单一配置，闪退无法定位问题接口，因此不走试运行，仅靠前置检测。
            List<String> safe = new ArrayList<>();
            List<String> runtimeBlocked = new ArrayList<>();
            List<String> suspectBlocked = new ArrayList<>();
            for (int i = 0; i < urls.size(); i++) {
                String url = urls.get(i);
                int index = i;
                if (probe) App.post(() -> {
                    if (!isAdded()) return;
                    binding.overlay.setVisibility(View.VISIBLE);
                    binding.progressText.setText(getString(R.string.source_merge_probing, index + 1, urlTotal));
                    binding.progressDetail.setText("");
                    setProgress(sliceStart(index + 1, urlTotal, 0f, probeWeight));
                });
                boolean http = url.startsWith("http://") || url.startsWith("https://");
                // 免检测：本软件合并产物（合并时已逐一检测）或曾通过 25 秒试运行（永久信任）
                boolean merged = SourceProbe.isSelfMerged(url);
                boolean probeable = http || (url.startsWith("file:") && !merged);
                int verdict = SourceProbe.PASS;
                boolean skip = merged || (probeable && SourceProbe.isRuntimePassed(url));
                if (probe && probeable && !skip) {
                    SourceProbe.Listener listener = new SourceProbe.Listener() {

                        @Override
                        public void onScan(int i2, int n2, String jarName) {
                            onSub(i2, n2, jarName);
                        }

                        @Override
                        public void onProgress(int i2, int n2, String siteName) {
                            onSub(i2, n2, siteName);
                        }

                        private void onSub(int i2, int n2, String label) {
                            App.post(() -> {
                                if (!isAdded()) return;
                                binding.overlay.setVisibility(View.VISIBLE);
                                binding.progressDetail.setText(getString(R.string.source_merge_probe_detail, label == null || label.isEmpty() ? "?" : label, i2, Math.max(n2, 1)));
                                float start = sliceStart(index + 1, urlTotal, 0f, probeWeight);
                                float end = sliceEnd(index + 1, urlTotal, 0f, probeWeight);
                                setProgress(start + (end - start) * (i2 - 1) / (float) Math.max(n2, 1));
                            });
                        }

                        @Override
                        public void onResult(int verdict, String reason) {
                        }
                    };
                    verdict = SourceProbe.checkSync(url, type, listener);
                }
                // DANGEROUS（运行实锤）与 SUSPECT（沙箱预警）均移出合并名单，原因文案区分
                if (verdict == SourceProbe.DANGEROUS) runtimeBlocked.add(url);
                else if (verdict == SourceProbe.SUSPECT) suspectBlocked.add(url);
                else safe.add(url);
                if (probe) App.post(() -> {
                    if (isAdded()) setProgress(sliceEnd(index + 1, urlTotal, 0f, probeWeight));
                });
            }
            List<String> blocked = new ArrayList<>(runtimeBlocked);
            blocked.addAll(suspectBlocked);
            if (safe.isEmpty()) {
                App.post(() -> {
                    if (!isAdded()) return;
                    setMerging(false);
                    Activity activity = requireActivity();
                    dismissAllowingStateLoss();
                    if (!blocked.isEmpty()) ProbeDialog.showBlocked(activity, runtimeBlocked, suspectBlocked);
                    else ProbeDialog.showNotice(activity, getString(R.string.source_merge_no_source), null);
                });
                return;
            }
            App.post(() -> {
                if (!isAdded()) return;
                mergeTotal = safe.size();
                binding.overlay.setVisibility(View.VISIBLE);
                binding.progressText.setText(getString(R.string.source_merge_progress, 0, safe.size()));
                binding.progressDetail.setText("");
                setProgress(probeWeight);
            });
            try {
                ConfigMerger.Result result = type == 0 ? ConfigMerger.mergeVod(safe, keepAll, callback) : ConfigMerger.mergeLive(name, safe, keepAll, callback);
                if (result.count == 0) throw new Exception(getString(R.string.source_merge_no_source));
                String fileUrl = ConfigMerger.save(result.json, name);
                Config config = Config.find(fileUrl, name, type);
                config.update();
                App.post(() -> {
                    if (!isAdded()) return;
                    setMerging(false);
                    Activity activity = requireActivity();
                    dismissAllowingStateLoss();
                    // 合并成功：激活合并结果，并立即删除已勾选且成功并入的原订阅
                    Callback load = new Callback() {
                    };
                    if (type == 0) VodConfig.load(config, load);
                    else LiveConfig.load(config, load);
                    deleteSources(fileUrl, result);
                    if (type == 0) ConfigEvent.vod();
                    else ConfigEvent.live();
                    String msg = buildResultMessage(result);
                    // 先弹合并结果，确认后再串联危险源拦截清单（原因区分）
                    ProbeDialog.showNotice(activity, msg, blocked.isEmpty() ? null : () -> ProbeDialog.showBlocked(activity, runtimeBlocked, suspectBlocked));
                });
            } catch (Exception e) {
                e.printStackTrace();
                String reason = e.getMessage() == null ? "" : e.getMessage();
                App.post(() -> {
                    if (!isAdded()) return;
                    setMerging(false);
                    Activity activity = requireActivity();
                    String msg = getString(R.string.source_merge_failed, reason);
                    dismissAllowingStateLoss();
                    // 先弹失败原因，确认后再串联危险源拦截清单
                    ProbeDialog.showNotice(activity, msg, blocked.isEmpty() ? null : () -> ProbeDialog.showBlocked(activity, runtimeBlocked, suspectBlocked));
                });
            }
        });
    }

    // 合并成功后立即删除：勾选的已添加源内容已成功进入合并结果则删除；
    // 拉取失败（failedUrls）与结果自身保留。
    private void deleteSources(String mergedUrl, ConfigMerger.Result result) {
        for (Config config : configs) {
            if (!selected.contains(config.getUrl())) continue;
            if (result.failedUrls.contains(config.getUrl())) continue;
            if (TextUtils.equals(config.getUrl(), mergedUrl)) continue; // 同名二次合并：不清除结果自身
            try {
                Config.delete(config.getUrl(), type);
            } catch (Throwable ignored) {
            }
        }
    }

    // ===== 全局进度引擎：检测 0~probeWeight%，合并 probeWeight~100%，源内再按站点细分 =====

    private int mergeTotal;
    private float shownProgress;
    private android.animation.ValueAnimator progressAnim;

    private float sliceStart(int index, int total, float from, float to) {
        return from + (to - from) * (index - 1) / (float) Math.max(total, 1);
    }

    private float sliceEnd(int index, int total, float from, float to) {
        return from + (to - from) * Math.min(index, total) / (float) Math.max(total, 1);
    }

    // 目标进度 300ms 补间，小步也平滑滑动；百分比保留 1 位小数
    private void setProgress(float target) {
        target = Math.max(0f, Math.min(100f, target));
        if (progressAnim != null) progressAnim.cancel();
        progressAnim = android.animation.ValueAnimator.ofFloat(shownProgress, target);
        progressAnim.setDuration(300);
        progressAnim.addUpdateListener(a -> {
            shownProgress = (float) a.getAnimatedValue();
            binding.progressBar.setProgress(Math.round(shownProgress));
            binding.progressPercent.setText(String.format(java.util.Locale.US, "%.1f%%", shownProgress));
        });
        progressAnim.start();
    }

    private String buildResultMessage(ConfigMerger.Result result) {
        int res = type == 0 ? R.string.source_merge_success_vod : R.string.source_merge_success_live;
        String msg = getString(res, result.count);
        if (result.failed > 0) msg += "\n" + getString(R.string.source_merge_partial, result.failed);
        return msg;
    }

    private void setMerging(boolean merging) {
        // 需求3：合并进行中禁用弹窗可取消（含系统返回键），防止合并中途被关
        if (getDialog() != null) getDialog().setCancelable(!merging);
        binding.overlay.setVisibility(merging ? View.VISIBLE : View.GONE);
        binding.positive.setEnabled(!merging);
        binding.negative.setEnabled(!merging);
        binding.positive.setText(merging ? R.string.source_merge_merging : R.string.source_merge_start);
        if (merging) {
            if (progressAnim != null) progressAnim.cancel();
            shownProgress = 0f;
            mergeTotal = 0;
            binding.progressBar.setProgress(0);
            binding.progressPercent.setText("0.0%");
            binding.progressDetail.setText("");
        }
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
}
