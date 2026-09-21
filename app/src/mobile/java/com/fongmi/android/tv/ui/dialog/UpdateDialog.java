package com.fongmi.android.tv.ui.dialog;

import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;

import androidx.fragment.app.FragmentActivity;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.Update;
import com.fongmi.android.tv.databinding.DialogUpdateBinding;
import com.fongmi.android.tv.impl.UpdateListener;
import com.fongmi.android.tv.utils.AppVersion;
import com.fongmi.android.tv.utils.MarkdownText;
import com.fongmi.android.tv.utils.ResUtil;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

public class UpdateDialog extends BaseAlertDialog {

    private DialogUpdateBinding binding;
    private UpdateListener listener;
    private Update stable;
    private Update beta;
    private String selected = Update.CHANNEL_STABLE;
    private boolean stableExpanded = true;
    private boolean betaExpanded;
    private boolean forceMode;
    private String forceMsg = "";

    public static UpdateDialog create() {
        return new UpdateDialog();
    }

    // 强制更新态：不可关闭、不可取消下载，仅保留「立即更新」
    public UpdateDialog force(boolean forceMode, String forceMsg) {
        this.forceMode = forceMode;
        this.forceMsg = forceMsg == null ? "" : forceMsg;
        return this;
    }

    public UpdateDialog stable(Update stable) {
        this.stable = stable;
        return this;
    }

    public UpdateDialog beta(Update beta) {
        this.beta = beta;
        return this;
    }

    public UpdateDialog selected(String selected) {
        this.selected = selected;
        this.stableExpanded = !Update.CHANNEL_BETA.equals(selected);
        this.betaExpanded = Update.CHANNEL_BETA.equals(selected);
        return this;
    }

    public UpdateDialog listener(UpdateListener listener) {
        this.listener = listener;
        return this;
    }

    public UpdateDialog show(FragmentActivity activity) {
        show(activity.getSupportFragmentManager(), null);
        return this;
    }

    @Override
    protected ViewBinding getBinding() {
        return binding = DialogUpdateBinding.inflate(getLayoutInflater());
    }

    @Override
    protected MaterialAlertDialogBuilder getBuilder() {
        return new MaterialAlertDialogBuilder(requireActivity(), R.style.ThemeOverlay_WebHTV_LightDialog).setView(getBinding().getRoot()).setCancelable(false);
    }

    @Override
    protected void initView() {
        render();
    }

    @Override
    protected void initEvent() {
        binding.close.setOnClickListener(this::close);
        binding.stableItem.setOnClickListener(view -> toggle(Update.CHANNEL_STABLE));
        binding.betaItem.setOnClickListener(view -> toggle(Update.CHANNEL_BETA));
        binding.stableConfirm.setOnClickListener(view -> update(Update.CHANNEL_STABLE, view));
        binding.betaConfirm.setOnClickListener(view -> update(Update.CHANNEL_BETA, view));
        binding.cancel.setOnClickListener(this::action);
    }

    @Override
    public void onStart() {
        super.onStart();
        setCancelable(false);
        if (getDialog() != null) getDialog().setCanceledOnTouchOutside(false);
        setDialogWidth(ResUtil.isLand(requireActivity()) ? 0.62f : 0.92f);
    }

    private void select(String channel) {
        selected = channel;
        if (listener != null) listener.onChannel(channel);
    }

    private void toggle(String channel) {
        if (forceMode) return;
        if (isExpanded(channel)) {
            update(channel, getItem(channel));
            return;
        }
        if (!hasBeta()) return;
        selected = channel;
        stableExpanded = Update.CHANNEL_STABLE.equals(channel);
        betaExpanded = Update.CHANNEL_BETA.equals(channel);
        if (listener != null) listener.onChannel(channel);
        render();
    }

    private void action(View view) {
        Update update = getSelected();
        if (update != null && update.hasUpdate()) update(selected, view);
        else if (listener != null) listener.onCancel(view);
    }

    private void close(View view) {
        if (forceMode) return;
        if (listener != null) listener.onClose();
        dismissAllowingStateLoss();
    }

    private void update(String channel, View view) {
        // 强制更新态锁定目标版本，禁止切换通道
        if (forceMode && !channel.equals(selected)) return;
        select(channel);
        if (listener != null) listener.onConfirm(view);
    }

    private void render() {
        normalizeSelection();
        binding.betaItem.setVisibility(hasBeta() && !forceMode ? View.VISIBLE : View.GONE);
        renderItem(Update.CHANNEL_STABLE, forceMode ? getSelected() : stable);
        if (hasBeta() && !forceMode) renderItem(Update.CHANNEL_BETA, beta);
        renderAction();
        binding.close.setVisibility(forceMode ? View.GONE : View.VISIBLE);
        // 强制模式标题改为「强制更新」，普通模式仍「版本更新」
        binding.title.setText(forceMode ? R.string.update_force_title : R.string.update_title);
        binding.hint.setText(forceMode ? getForceHint() : getString(R.string.update_hint));
        configureScrollHeight();
    }

    private String getForceHint() {
        return TextUtils.isEmpty(forceMsg) ? getString(R.string.update_force_hint) : forceMsg;
    }

    // 滚动区高度随内容自适应：内容少时弹窗整体缩小，内容超高才限高 360dp
    private void configureScrollHeight() {
        binding.listScroll.post(() -> {
            View content = binding.listScroll.getChildAt(0);
            if (content == null || !isAdded()) return;
            int height = Math.min(content.getMeasuredHeight(), ResUtil.dp2px(360));
            ViewGroup.LayoutParams params = binding.listScroll.getLayoutParams();
            if (params.height == height) return;
            params.height = height;
            binding.listScroll.setLayoutParams(params);
        });
    }

    private void renderItem(String channel, Update update) {
        boolean stableChannel = Update.CHANNEL_STABLE.equals(channel);
        boolean expanded = stableChannel ? stableExpanded : betaExpanded;
        View item = stableChannel ? binding.stableItem : binding.betaItem;
        View content = stableChannel ? binding.stableContent : binding.betaContent;
        item.setSelected(expanded);
        content.setVisibility(expanded ? View.VISIBLE : View.GONE);
        if (stableChannel) {
            binding.stableVersion.setText(getVersion(update));
            binding.stableStatus.setText(getStatus(update));
            binding.stableExpand.setVisibility(hasBeta() && !expanded ? View.VISIBLE : View.GONE);
            binding.stableExpand.setText(R.string.update_expand);
            binding.stableDesc.setText(MarkdownText.render(getBody(update), getString(R.string.update_no_notes)));
            binding.stableConfirm.setEnabled(update != null && update.hasUpdate());
            binding.stableConfirm.setText(R.string.update_confirm);
            binding.stableConfirm.setVisibility(View.GONE);
        } else {
            binding.betaVersion.setText(getVersion(update));
            binding.betaStatus.setText(getStatus(update));
            binding.betaExpand.setVisibility(!expanded ? View.VISIBLE : View.GONE);
            binding.betaExpand.setText(R.string.update_expand);
            binding.betaDesc.setText(MarkdownText.render(getBody(update), getString(R.string.update_no_notes)));
            binding.betaConfirm.setEnabled(update != null && update.hasUpdate());
            binding.betaConfirm.setText(R.string.update_confirm);
            binding.betaConfirm.setVisibility(View.GONE);
        }
    }

    private void renderAction() {
        Update update = getSelected();
        if (forceMode) {
            binding.cancel.setText(R.string.update_confirm);
            binding.cancel.setEnabled(update != null && update.hasUpdate());
            return;
        }
        if (update == null || !update.hasUpdate()) binding.cancel.setText(R.string.about_acknowledge);
        else binding.cancel.setText(hasBeta() ? getString(R.string.update_confirm_channel, getSelectedName()) : getString(R.string.update_confirm));
    }

    private String getVersion(Update update) {
        return update != null && update.hasManifest() ? AppVersion.stripPrefix(update.name) : getString(R.string.update_status_unavailable);
    }

    private String getStatus(Update update) {
        if (update == null || !update.hasManifest()) return getString(R.string.update_status_unavailable);
        return update.hasUpdate() ? getString(R.string.update_status_available) : getString(R.string.update_status_latest);
    }

    private String getBody(Update update) {
        if (update == null || !update.hasManifest()) return getString(R.string.update_channel_unavailable);
        if (!TextUtils.isEmpty(update.getText())) return update.getText();
        if (!update.hasUpdate()) return getString(R.string.update_channel_latest);
        return update.getText();
    }

    private boolean hasBeta() {
        return beta != null && beta.hasManifest();
    }

    private void normalizeSelection() {
        if (hasBeta()) return;
        selected = Update.CHANNEL_STABLE;
        stableExpanded = true;
        betaExpanded = false;
    }

    private Update getSelected() {
        return Update.CHANNEL_BETA.equals(selected) ? beta : stable;
    }

    private boolean isExpanded(String channel) {
        return Update.CHANNEL_BETA.equals(channel) ? betaExpanded : stableExpanded;
    }

    private View getItem(String channel) {
        return Update.CHANNEL_BETA.equals(channel) ? binding.betaItem : binding.stableItem;
    }

    private String getSelectedName() {
        return getString(Update.CHANNEL_BETA.equals(selected) ? R.string.update_channel_beta : R.string.update_channel_stable);
    }

    private void setDialogWidth(float factor) {
        Window window = getDialog() == null ? null : getDialog().getWindow();
        if (window == null) return;
        int width = (int) (ResUtil.getScreenWidth(requireActivity()) * factor);
        WindowManager.LayoutParams params = window.getAttributes();
        params.width = width;
        params.height = WindowManager.LayoutParams.WRAP_CONTENT;
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        window.setAttributes(params);
        window.setLayout(width, WindowManager.LayoutParams.WRAP_CONTENT);
    }
}
