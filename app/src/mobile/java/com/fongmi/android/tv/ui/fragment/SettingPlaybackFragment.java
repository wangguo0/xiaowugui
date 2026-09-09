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

    @Override
    protected ViewBinding getBinding(@NonNull LayoutInflater inflater, @Nullable ViewGroup container) {
        return mBinding = FragmentSettingPlaybackBinding.inflate(inflater, container, false);
    }

    @Override
    protected void initView() {
        mBinding.liveVisibleSwitch.setChecked(Setting.isLiveVisible());
        mBinding.linkVisibleSwitch.setChecked(Setting.isLinkVisible());
        mBinding.vodVisibleSwitch.setChecked(Setting.isVodVisible());
        mBinding.historyVisibleSwitch.setChecked(Setting.isHistoryVisible());
        mBinding.keepVisibleSwitch.setChecked(Setting.isKeepVisible());
        mBinding.bangumiVisibleSwitch.setChecked(Setting.isBangumiVisible());
        mBinding.switchEpisodeThresholdText.setText(getString(R.string.setting_percent, Setting.getSwitchEpisodeThreshold()));
    }

    @Override
    protected void initEvent() {
        mBinding.player.setOnClickListener(view -> SubSettingActivity.start(requireActivity(), 2));
        mBinding.danmaku.setOnClickListener(view -> SubSettingActivity.start(requireActivity(), 4));
        mBinding.liveVisible.setOnClickListener(v -> setLiveVisible());
        mBinding.liveVisibleSwitch.setOnClickListener(v -> setLiveVisible());
        mBinding.linkVisible.setOnClickListener(v -> setLinkVisible());
        mBinding.linkVisibleSwitch.setOnClickListener(v -> setLinkVisible());
        mBinding.vodVisible.setOnClickListener(v -> setVodVisible());
        mBinding.vodVisibleSwitch.setOnClickListener(v -> setVodVisible());
        mBinding.historyVisible.setOnClickListener(v -> setHistoryVisible());
        mBinding.historyVisibleSwitch.setOnClickListener(v -> setHistoryVisible());
        mBinding.keepVisible.setOnClickListener(v -> setKeepVisible());
        mBinding.keepVisibleSwitch.setOnClickListener(v -> setKeepVisible());
        mBinding.bangumiVisible.setOnClickListener(v -> setBangumiVisible());
        mBinding.bangumiVisibleSwitch.setOnClickListener(v -> setBangumiVisible());
        mBinding.switchEpisodeThreshold.setOnClickListener(this::onSwitchEpisodeThreshold);
    }

    // 整行与开关均可点击切换；程序 setChecked 不触发点击，无回环风险。
    private void setVodVisible() {
        Setting.putVodVisible(!Setting.isVodVisible());
        mBinding.vodVisibleSwitch.setChecked(Setting.isVodVisible());
        ConfigEvent.common();
    }

    private void setHistoryVisible() {
        Setting.putHistoryVisible(!Setting.isHistoryVisible());
        mBinding.historyVisibleSwitch.setChecked(Setting.isHistoryVisible());
        ConfigEvent.common();
    }

    private void setKeepVisible() {
        Setting.putKeepVisible(!Setting.isKeepVisible());
        mBinding.keepVisibleSwitch.setChecked(Setting.isKeepVisible());
        ConfigEvent.common();
    }

    private void setBangumiVisible() {
        Setting.putBangumiVisible(!Setting.isBangumiVisible());
        mBinding.bangumiVisibleSwitch.setChecked(Setting.isBangumiVisible());
        ConfigEvent.common();
    }

    private void setLiveVisible() {
        Setting.putLiveVisible(!Setting.isLiveVisible());
        mBinding.liveVisibleSwitch.setChecked(Setting.isLiveVisible());
        ConfigEvent.common();
    }

    private void setLinkVisible() {
        Setting.putLinkVisible(!Setting.isLinkVisible());
        mBinding.linkVisibleSwitch.setChecked(Setting.isLinkVisible());
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