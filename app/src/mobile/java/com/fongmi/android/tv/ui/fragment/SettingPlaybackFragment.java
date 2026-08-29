package com.fongmi.android.tv.ui.fragment;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.FragmentSettingPlaybackBinding;
import com.fongmi.android.tv.event.ConfigEvent;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.ui.activity.SubSettingActivity;
import com.fongmi.android.tv.ui.base.BaseFragment;

public class SettingPlaybackFragment extends BaseFragment {

    private FragmentSettingPlaybackBinding mBinding;

    public static SettingPlaybackFragment newInstance() {
        return new SettingPlaybackFragment();
    }

    private String getSwitch(boolean value) {
        return getString(value ? R.string.setting_on : R.string.setting_off);
    }

    @Override
    protected ViewBinding getBinding(@NonNull LayoutInflater inflater, @Nullable ViewGroup container) {
        return mBinding = FragmentSettingPlaybackBinding.inflate(inflater, container, false);
    }

    @Override
    protected void initView() {
        mBinding.liveVisibleText.setText(getSwitch(Setting.isLiveVisible()));
    }

    @Override
    protected void initEvent() {
        mBinding.player.setOnClickListener(view -> SubSettingActivity.start(requireActivity(), 2));
        mBinding.danmaku.setOnClickListener(view -> SubSettingActivity.start(requireActivity(), 4));
        mBinding.liveVisible.setOnClickListener(this::setLiveVisible);
    }

    private void setLiveVisible(View view) {
        Setting.putLiveVisible(!Setting.isLiveVisible());
        mBinding.liveVisibleText.setText(getSwitch(Setting.isLiveVisible()));
        ConfigEvent.common();
    }
}