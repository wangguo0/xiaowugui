package com.fongmi.android.tv.ui.fragment;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.Updater;
import com.fongmi.android.tv.databinding.FragmentSettingBinding;
import com.fongmi.android.tv.ui.activity.SubSettingActivity;
import com.fongmi.android.tv.ui.base.BaseFragment;
import com.fongmi.android.tv.ui.dialog.AboutDialog;

public class SettingFragment extends BaseFragment {

    private FragmentSettingBinding mBinding;

    public static SettingFragment newInstance() {
        return new SettingFragment();
    }

    @Override
    protected ViewBinding getBinding(@NonNull LayoutInflater inflater, @Nullable ViewGroup container) {
        return mBinding = FragmentSettingBinding.inflate(inflater, container, false);
    }

    @Override
    protected void initEvent() {
        mBinding.sourceGroup.setOnClickListener(view -> SubSettingActivity.start(requireActivity(), 5));
        mBinding.appearanceGroup.setOnClickListener(view -> SubSettingActivity.start(requireActivity(), 6));
        mBinding.playbackGroup.setOnClickListener(view -> SubSettingActivity.start(requireActivity(), 7));
        mBinding.advancedGroup.setOnClickListener(view -> SubSettingActivity.start(requireActivity(), 8));
        mBinding.dataGroup.setOnClickListener(view -> SubSettingActivity.start(requireActivity(), 9));
        mBinding.aboutRow.setOnClickListener(this::setVersion);
    }

    private void setVersion(View view) {
        AboutDialog.show(requireActivity(), () -> Updater.create().force().start(requireActivity()));
    }
}