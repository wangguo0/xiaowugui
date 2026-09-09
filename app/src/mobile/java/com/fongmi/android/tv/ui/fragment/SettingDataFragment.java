package com.fongmi.android.tv.ui.fragment;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.FragmentSettingDataBinding;
import com.fongmi.android.tv.db.AppDatabase;
import com.fongmi.android.tv.impl.Callback;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.ui.base.BaseFragment;
import com.fongmi.android.tv.ui.activity.LogActivity;
import com.fongmi.android.tv.ui.dialog.BackupProgressDialog;
import com.fongmi.android.tv.ui.dialog.RestoreDialog;
import com.fongmi.android.tv.utils.FileUtil;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.PermissionUtil;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

public class SettingDataFragment extends BaseFragment {

    private FragmentSettingDataBinding mBinding;

    public static SettingDataFragment newInstance() {
        return new SettingDataFragment();
    }

    @Override
    protected ViewBinding getBinding(@NonNull LayoutInflater inflater, @Nullable ViewGroup container) {
        return mBinding = FragmentSettingDataBinding.inflate(inflater, container, false);
    }

    @Override
    protected void initView() {
        setCacheText();
        mBinding.autoBackupText.setText(getSwitch(Setting.isAutoBackup()));
    }

    @Override
    protected void initEvent() {
        mBinding.cache.setOnClickListener(this::onCache);
        mBinding.backup.setOnClickListener(this::onBackup);
        mBinding.restore.setOnClickListener(this::onRestore);
        mBinding.autoBackup.setOnClickListener(this::setAutoBackup);
        mBinding.diagLog.setOnClickListener(v -> LogActivity.start(requireActivity()));
    }

    private String getSwitch(boolean value) {
        return getString(value ? R.string.setting_on : R.string.setting_off);
    }

    private void setAutoBackup(View view) {
        Setting.putAutoBackup(!Setting.isAutoBackup());
        mBinding.autoBackupText.setText(getSwitch(Setting.isAutoBackup()));
    }

    // 二次确认弹窗，样式与恢复弹窗一致（浅色居中）
    private void confirm(int messageRes, Runnable action) {
        new MaterialAlertDialogBuilder(requireActivity(), R.style.ThemeOverlay_WebHTV_LightDialog)
                .setTitle(R.string.video_error_title)
                .setMessage(messageRes)
                .setNegativeButton(R.string.dialog_negative, null)
                .setPositiveButton(R.string.dialog_positive, (dialog, which) -> action.run())
                .show();
    }

    private void setCacheText() {
        FileUtil.getCacheSize(new Callback() {
            @Override
            public void success(String result) {
                mBinding.cacheText.setText(result);
            }
        });
    }

    private void onCache(View view) {
        confirm(R.string.setting_clear_cache_confirm, () -> FileUtil.clearCache(new Callback() {
            @Override
            public void success() {
                setCacheText();
            }
        }));
    }

    private void onBackup(View view) {
        confirm(R.string.setting_backup_confirm, () -> PermissionUtil.requestFile(this, allGranted -> {
            BackupProgressDialog progress = BackupProgressDialog.open(getParentFragmentManager(), getString(R.string.app_name));
            AppDatabase.backup(new Callback() {
                @Override
                public void success() {
                    progress.finish();
                    Notify.show(R.string.backup_success);
                }

                @Override
                public void error() {
                    progress.finish();
                    Notify.show(R.string.backup_fail);
                }
            }, progress::update);
        }));
    }

    private void onRestore(View view) {
        PermissionUtil.requestFile(this, allGranted -> RestoreDialog.create().show(requireActivity(), new Callback() {
            @Override
            public void success() {
                Notify.show(R.string.restore_success);
            }

            @Override
            public void error() {
                Notify.show(R.string.restore_fail);
            }
        }));
    }

    @Override
    public void onHiddenChanged(boolean hidden) {
        if (hidden) return;
        setCacheText();
    }
}