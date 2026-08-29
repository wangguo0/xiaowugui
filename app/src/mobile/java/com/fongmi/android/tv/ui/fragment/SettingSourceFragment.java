package com.fongmi.android.tv.ui.fragment;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.databinding.FragmentSettingSourceBinding;
import com.fongmi.android.tv.ui.activity.SubSettingActivity;
import com.fongmi.android.tv.ui.base.BaseFragment;

public class SettingSourceFragment extends BaseFragment {

    private FragmentSettingSourceBinding mBinding;

    public static SettingSourceFragment newInstance() {
        return new SettingSourceFragment();
    }

    @Override
    protected ViewBinding getBinding(@NonNull LayoutInflater inflater, @Nullable ViewGroup container) {
        return mBinding = FragmentSettingSourceBinding.inflate(inflater, container, false);
    }

    @Override
    protected void initEvent() {
        mBinding.vodManage.setOnClickListener(this::onVodManage);
        mBinding.liveManage.setOnClickListener(this::onLiveManage);
    }

    private void onVodManage(View view) {
        openConfigManage(0);
    }

    private void onLiveManage(View view) {
        openConfigManage(1);
    }

    private void openConfigManage(int type) {
        SubSettingActivity.start(requireActivity(), 10 + type);
    }
}