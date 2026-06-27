package com.fongmi.android.tv.ui.dialog;

import android.text.TextUtils;
import android.view.View;

import androidx.fragment.app.FragmentActivity;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.Update;
import com.fongmi.android.tv.databinding.DialogUpdateBinding;
import com.fongmi.android.tv.impl.UpdateListener;
import com.fongmi.android.tv.utils.AppVersion;
import com.fongmi.android.tv.utils.FileUtil;
import com.fongmi.android.tv.utils.MarkdownText;
import com.fongmi.android.tv.utils.Util;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.Locale;

public class UpdateDialog extends BaseAlertDialog {

    private DialogUpdateBinding binding;
    private UpdateListener listener;
    private Update stable;
    private Update beta;
    private String selected = Update.CHANNEL_STABLE;

    public static UpdateDialog create() {
        return new UpdateDialog();
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
        return builder().setView(getBinding().getRoot()).setCancelable(false);
    }

    @Override
    protected void initView() {
        binding.stable.setCheckable(true);
        binding.beta.setCheckable(true);
        binding.progress.setMax(100);
        render();
    }

    @Override
    protected void initEvent() {
        binding.stable.setOnClickListener(view -> select(Update.CHANNEL_STABLE));
        binding.beta.setOnClickListener(view -> select(Update.CHANNEL_BETA));
        binding.confirm.setOnClickListener(view -> listener.onConfirm(view));
        binding.cancel.setOnClickListener(view -> listener.onCancel(view));
    }

    @Override
    public void onStart() {
        super.onStart();
        setWidth(0.52f);
        binding.confirm.requestFocus();
    }

    private void select(String channel) {
        selected = channel;
        if (listener != null) listener.onChannel(channel);
        render();
    }

    private void render() {
        Update update = getSelected();
        boolean canUpdate = update != null && update.hasUpdate();
        binding.stable.setChecked(Update.CHANNEL_STABLE.equals(selected));
        binding.beta.setChecked(Update.CHANNEL_BETA.equals(selected));
        binding.version.setText(getTitle(update));
        binding.desc.setText(MarkdownText.render(getBody(update), getString(R.string.update_no_notes)));
        binding.confirm.setEnabled(canUpdate);
        binding.confirm.setText(R.string.update_confirm);
        binding.progressPanel.setVisibility(View.GONE);
    }

    private Update getSelected() {
        return Update.CHANNEL_BETA.equals(selected) ? beta : stable;
    }

    private String getTitle(Update update) {
        if (update != null && update.hasManifest()) return getString(R.string.update_version, AppVersion.stripPrefix(update.name));
        return getChannelName(update);
    }

    private String getBody(Update update) {
        if (update == null || !update.hasManifest()) return getString(R.string.update_channel_unavailable);
        if (!update.hasUpdate()) return getString(R.string.update_channel_latest);
        return update.getText();
    }

    private String getChannelName(Update update) {
        return update != null && update.isBeta() ? getString(R.string.update_channel_beta) : getString(R.string.update_channel_stable);
    }

    public void setProgress(int progress) {
        setProgress(progress, 0, 0, 0, 0);
    }

    public void setProgress(int progress, long bytes, long total, long speed, long elapsed) {
        boolean indeterminate = progress < 0;
        int value = Math.max(0, Math.min(100, progress));
        binding.stable.setEnabled(false);
        binding.beta.setEnabled(false);
        binding.progressPanel.setVisibility(View.VISIBLE);
        binding.progress.setIndeterminate(indeterminate);
        if (!indeterminate) binding.progress.setProgress(value);
        binding.progressText.setText(getProgressText(indeterminate, value, speed, elapsed));
        binding.confirm.setText(indeterminate ? getString(R.string.update_confirm) : String.format(Locale.getDefault(), "%1$d%%", value));
    }

    private String getProgressText(boolean indeterminate, int value, long speed, long elapsed) {
        if (speed <= 0 || elapsed <= 0) return indeterminate ? getString(R.string.update_downloading_unknown) : getString(R.string.update_downloading, value);
        String speedText = FileUtil.byteCountToDisplaySize(speed);
        String elapsedText = TextUtils.isEmpty(Util.timeMs(elapsed)) ? "00:00" : Util.timeMs(elapsed);
        return indeterminate ? getString(R.string.update_downloading_detail_unknown, speedText, elapsedText) : getString(R.string.update_downloading_detail, value, speedText, elapsedText);
    }
}
