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
import com.fongmi.android.tv.ui.dialog.ChoiceDialog;

public class SettingPlaybackFragment extends BaseFragment {

    private FragmentSettingPlaybackBinding mBinding;

    private static final int[] THRESHOLD_VALUES = {0, 30, 50, 70, 80};

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
        mBinding.linkVisibleText.setText(getSwitch(Setting.isLinkVisible()));
        mBinding.vodVisibleText.setText(getSwitch(Setting.isVodVisible()));
        mBinding.historyVisibleText.setText(getSwitch(Setting.isHistoryVisible()));
        mBinding.keepVisibleText.setText(getSwitch(Setting.isKeepVisible()));
        mBinding.bangumiVisibleText.setText(getSwitch(Setting.isBangumiVisible()));
        mBinding.switchEpisodeThresholdText.setText(getString(R.string.setting_percent, Setting.getSwitchEpisodeThreshold()));
    }

    @Override
    protected void initEvent() {
        mBinding.player.setOnClickListener(view -> SubSettingActivity.start(requireActivity(), 2));
        mBinding.danmaku.setOnClickListener(view -> SubSettingActivity.start(requireActivity(), 4));
        mBinding.liveVisible.setOnClickListener(this::setLiveVisible);
        mBinding.linkVisible.setOnClickListener(this::setLinkVisible);
        mBinding.vodVisible.setOnClickListener(this::setVodVisible);
        mBinding.historyVisible.setOnClickListener(this::setHistoryVisible);
        mBinding.keepVisible.setOnClickListener(this::setKeepVisible);
        mBinding.bangumiVisible.setOnClickListener(this::setBangumiVisible);
        mBinding.switchEpisodeThreshold.setOnClickListener(this::onSwitchEpisodeThreshold);
    }

    private void setVodVisible(View view) {
        Setting.putVodVisible(!Setting.isVodVisible());
        mBinding.vodVisibleText.setText(getSwitch(Setting.isVodVisible()));
        ConfigEvent.common();
    }

    private void setHistoryVisible(View view) {
        Setting.putHistoryVisible(!Setting.isHistoryVisible());
        mBinding.historyVisibleText.setText(getSwitch(Setting.isHistoryVisible()));
        ConfigEvent.common();
    }

    private void setKeepVisible(View view) {
        Setting.putKeepVisible(!Setting.isKeepVisible());
        mBinding.keepVisibleText.setText(getSwitch(Setting.isKeepVisible()));
        ConfigEvent.common();
    }

    private void setBangumiVisible(View view) {
        Setting.putBangumiVisible(!Setting.isBangumiVisible());
        mBinding.bangumiVisibleText.setText(getSwitch(Setting.isBangumiVisible()));
        ConfigEvent.common();
    }

    private void setLiveVisible(View view) {
        Setting.putLiveVisible(!Setting.isLiveVisible());
        mBinding.liveVisibleText.setText(getSwitch(Setting.isLiveVisible()));
        ConfigEvent.common();
    }

    private void setLinkVisible(View view) {
        Setting.putLinkVisible(!Setting.isLinkVisible());
        mBinding.linkVisibleText.setText(getSwitch(Setting.isLinkVisible()));
    }

    private void onSwitchEpisodeThreshold(View view) {
        showThresholdDialog();
    }

    private String getThresholdText(int percent) {
        return percent <= 0 ? getString(R.string.setting_threshold_none) : getString(R.string.setting_percent, percent);
    }

    private CharSequence[] getThresholdItems() {
        CharSequence[] items = new CharSequence[THRESHOLD_VALUES.length];
        for (int i = 0; i < THRESHOLD_VALUES.length; i++) items[i] = getThresholdText(THRESHOLD_VALUES[i]);
        return items;
    }

    private void showThresholdDialog() {
        int current = Setting.getSwitchEpisodeThreshold();
        int index = 4;
        for (int i = 0; i < THRESHOLD_VALUES.length; i++) {
            if (THRESHOLD_VALUES[i] == current) index = i;
        }
        ChoiceDialog.showSingle(this, R.string.setting_switch_episode_threshold, getThresholdItems(), index, which -> {
            Setting.putSwitchEpisodeThreshold(THRESHOLD_VALUES[which]);
            mBinding.switchEpisodeThresholdText.setText(getThresholdText(THRESHOLD_VALUES[which]));
        });
    }
}