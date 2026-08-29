package com.fongmi.android.tv.ui.dialog;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.text.TextUtils;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.Config;
import com.fongmi.android.tv.databinding.DialogShareUrlBinding;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.QRCode;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

public class ShareUrlDialog extends BaseAlertDialog {

    private DialogShareUrlBinding binding;
    private Config config;

    public static ShareUrlDialog create(Config config) {
        ShareUrlDialog dialog = new ShareUrlDialog();
        dialog.config = config;
        return dialog;
    }

    public void show(Fragment fragment) {
        show(fragment.getChildFragmentManager(), null);
    }

    @Override
    protected ViewBinding getBinding() {
        return binding = DialogShareUrlBinding.inflate(getLayoutInflater());
    }

    @Override
    protected MaterialAlertDialogBuilder getBuilder() {
        return new MaterialAlertDialogBuilder(requireActivity(), R.style.ThemeOverlay_WebHTV_LightDialog).setView(getBinding().getRoot());
    }

    @Override
    protected void initView() {
        binding.name.setText(TextUtils.isEmpty(config.getName()) ? config.getUrl() : config.getName());
        binding.url.setText(config.getUrl());
        Bitmap qr = QRCode.getBitmap(config.getUrl(), 480, 2);
        binding.qrcode.setImageBitmap(qr);
    }

    @Override
    protected void initEvent() {
        binding.url.setOnClickListener(this::copy);
    }

    private void copy(View view) {
        ClipboardManager clipboard = (ClipboardManager) requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) clipboard.setPrimaryClip(ClipData.newPlainText("url", config.getUrl()));
        Notify.show(R.string.setting_subscription_share_copied);
    }
}