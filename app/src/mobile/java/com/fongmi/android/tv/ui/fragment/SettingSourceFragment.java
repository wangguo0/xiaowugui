package com.fongmi.android.tv.ui.fragment;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.PopupShield;
import com.fongmi.android.tv.databinding.FragmentSettingSourceBinding;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.ui.activity.SubSettingActivity;
import com.fongmi.android.tv.ui.base.BaseFragment;
import com.fongmi.android.tv.ui.dialog.SourceMergeDialog;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

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
    protected void initView() {
        mBinding.probeAdd.setChecked(Setting.isProbeAdd());
        mBinding.probeMerge.setChecked(Setting.isProbeMerge());
        mBinding.popupShield.setChecked(Setting.isPopupShield());
        mBinding.blockNotice.setChecked(Setting.isBlockNotice());
        mBinding.neutralizeKill.setChecked(Setting.isNeutralizeKill());
    }

    @Override
    protected void initEvent() {
        mBinding.vodManage.setOnClickListener(this::onVodManage);
        mBinding.liveManage.setOnClickListener(this::onLiveManage);
        mBinding.vodMerge.setOnClickListener(this::onVodMerge);
        mBinding.liveMerge.setOnClickListener(this::onLiveMerge);
        mBinding.probeAddRow.setOnClickListener(v -> mBinding.probeAdd.performClick());
        mBinding.probeMergeRow.setOnClickListener(v -> mBinding.probeMerge.performClick());
        mBinding.probeAdd.setOnClickListener(v -> toggleProbe(mBinding.probeAdd, true));
        mBinding.probeMerge.setOnClickListener(v -> toggleProbe(mBinding.probeMerge, false));
        mBinding.blockManage.setOnClickListener(this::onBlockManage);
        mBinding.popupShieldRow.setOnClickListener(v -> mBinding.popupShield.performClick());
        mBinding.popupShield.setOnClickListener(v -> togglePopupShield());
        mBinding.popupKeyword.setOnClickListener(this::onPopupKeyword);
        mBinding.blockNoticeRow.setOnClickListener(v -> mBinding.blockNotice.performClick());
        mBinding.blockNotice.setOnClickListener(this::toggleBlockNotice);
        mBinding.neutralizeKillRow.setOnClickListener(v -> mBinding.neutralizeKill.performClick());
        mBinding.neutralizeKill.setOnClickListener(v -> toggleNeutralizeKill());
    }

    // 开启「中和杀进程指令」= 放行含杀进程代码的源（安全放宽），需二次确认；关闭即时生效
    private void toggleNeutralizeKill() {
        boolean enable = mBinding.neutralizeKill.isChecked();
        if (!enable) {
            Setting.putNeutralizeKill(false);
            return;
        }
        new MaterialAlertDialogBuilder(requireActivity(), R.style.ThemeOverlay_WebHTV_LightDialog)
                .setTitle(R.string.video_error_title)
                .setMessage(R.string.neutralize_kill_confirm)
                .setNegativeButton(R.string.dialog_negative, (dialog, which) -> mBinding.neutralizeKill.setChecked(false))
                .setPositiveButton(R.string.dialog_positive, (dialog, which) -> Setting.putNeutralizeKill(true))
                .show();
    }

    // 弹窗拦截即时生效，无需二次确认
    private void togglePopupShield() {
        boolean enable = mBinding.popupShield.isChecked();
        Setting.putPopupShield(enable);
        PopupShield.setEnabled(enable);
    }

    // 接口提示拦截即时生效：Notify.showNotice 实时读设置，jar 自弹 toast 由 PopupShield 窗口级拦截
    private void toggleBlockNotice(View view) {
        Setting.putBlockNotice(mBinding.blockNotice.isChecked());
        PopupShield.reload();
    }

    private void onPopupKeyword(View view) {
        SubSettingActivity.start(requireActivity(), 13);
    }

    // 打开屏蔽管理子页面
    private void onBlockManage(View view) {
        SubSettingActivity.start(requireActivity(), 12);
    }

    // 打开即时生效；关闭需「温馨提醒」二次确认，取消则弹回
    private void toggleProbe(android.widget.CompoundButton switchView, boolean add) {
        boolean target = switchView.isChecked();
        if (target) {
            saveProbe(add, true);
            return;
        }
        new MaterialAlertDialogBuilder(requireActivity(), R.style.ThemeOverlay_WebHTV_LightDialog)
                .setTitle(R.string.video_error_title)
                .setMessage(R.string.source_probe_switch_confirm)
                .setNegativeButton(R.string.dialog_negative, (dialog, which) -> switchView.setChecked(true))
                .setPositiveButton(R.string.source_probe_switch_close, (dialog, which) -> saveProbe(add, false))
                .show();
    }

    private void saveProbe(boolean add, boolean enable) {
        if (add) Setting.putProbeAdd(enable);
        else Setting.putProbeMerge(enable);
    }

    private void onVodManage(View view) {
        openConfigManage(0);
    }

    private void onLiveManage(View view) {
        openConfigManage(1);
    }

    private void onVodMerge(View view) {
        SourceMergeDialog.create().vod().show(this);
    }

    private void onLiveMerge(View view) {
        SourceMergeDialog.create().live().show(this);
    }

    private void openConfigManage(int type) {
        SubSettingActivity.start(requireActivity(), 10 + type);
    }
}
