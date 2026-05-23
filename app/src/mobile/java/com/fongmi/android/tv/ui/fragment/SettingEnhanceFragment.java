package com.fongmi.android.tv.ui.fragment;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.Setting;
import com.fongmi.android.tv.databinding.FragmentSettingEnhanceBinding;
import com.fongmi.android.tv.server.Server;
import com.fongmi.android.tv.ui.base.BaseFragment;
import com.fongmi.android.tv.utils.Notify;
import com.github.catvod.crawler.SpiderDebug;

public class SettingEnhanceFragment extends BaseFragment {

    private FragmentSettingEnhanceBinding mBinding;

    public static SettingEnhanceFragment newInstance() {
        return new SettingEnhanceFragment();
    }

    private String getSwitch(boolean value) {
        return getString(value ? R.string.setting_on : R.string.setting_off);
    }

    @Override
    protected ViewBinding getBinding(@NonNull LayoutInflater inflater, @Nullable ViewGroup container) {
        return mBinding = FragmentSettingEnhanceBinding.inflate(inflater, container, false);
    }

    @Override
    protected void initView() {
        setText();
    }

    @Override
    protected void initEvent() {
        mBinding.driveCheck.setOnClickListener(this::setDriveCheck);
        mBinding.debugLog.setOnClickListener(this::setDebugLog);
    }

    private void setText() {
        mBinding.driveCheckText.setText(getSwitch(Setting.isDriveCheck()));
        mBinding.debugLogText.setText(getSwitch(Setting.isDebugLog()));
    }

    private void setDriveCheck(View view) {
        Setting.putDriveCheck(!Setting.isDriveCheck());
        mBinding.driveCheckText.setText(getSwitch(Setting.isDriveCheck()));
    }

    private void setDebugLog(View view) {
        Setting.putDebugLog(!Setting.isDebugLog());
        mBinding.debugLogText.setText(getSwitch(Setting.isDebugLog()));
        if (!Setting.isDebugLog()) return;
        Server.get().start();
        String url = Server.get().getAddress("/debug/logs");
        SpiderDebug.log("debug", "open logs url=%s lan=%s", url, Server.get().getAddress(false) + "/debug/logs");
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (ActivityNotFoundException e) {
            Notify.show(url);
        }
    }

    @Override
    public void onHiddenChanged(boolean hidden) {
        if (!hidden) setText();
    }
}
