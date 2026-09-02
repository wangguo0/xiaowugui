package com.fongmi.android.tv.ui.dialog;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.DialogRestoreBinding;
import com.fongmi.android.tv.db.AppDatabase;
import com.fongmi.android.tv.impl.Callback;
import com.fongmi.android.tv.ui.adapter.RestoreAdapter;
import com.fongmi.android.tv.ui.custom.SpaceItemDecoration;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.File;

public class RestoreDialog extends BaseBottomSheetDialog implements RestoreAdapter.OnClickListener {

    private DialogRestoreBinding binding;
    private RestoreAdapter adapter;
    private Callback callback;

    public static RestoreDialog create() {
        return new RestoreDialog();
    }

    public void show(FragmentActivity activity, Callback callback) {
        for (Fragment f : activity.getSupportFragmentManager().getFragments()) if (f instanceof RestoreDialog) return;
        show(activity.getSupportFragmentManager(), null);
        this.callback = callback;
    }

    @Override
    protected boolean stableOverlay() {
        return true;
    }

    @Override
    protected boolean keepDim() {
        return true;
    }

    @Override
    protected ViewBinding getBinding(@NonNull LayoutInflater inflater, @Nullable ViewGroup container) {
        return binding = DialogRestoreBinding.inflate(inflater, container, false);
    }

    @Override
    protected void initView() {
        binding.recycler.setItemAnimator(null);
        binding.recycler.setHasFixedSize(false);
        binding.recycler.setAdapter(adapter = new RestoreAdapter(this));
        binding.recycler.addItemDecoration(new SpaceItemDecoration(1, 16));
        binding.recycler.setVisibility(adapter.getItemCount() == 0 ? View.GONE : View.VISIBLE);
    }

    @Override
    public void onItemClick(File item) {
        BackupProgressDialog progress = BackupProgressDialog.open(getParentFragmentManager(), "恢复应用数据");
        AppDatabase.restore(item, new Callback() {
            @Override
            public void success() {
                progress.finish();
                if (callback != null) callback.success();
            }

            @Override
            public void error() {
                progress.finish();
                if (callback != null) callback.error();
            }
        }, progress::update);
        dismiss();
    }

    @Override
    public void onDeleteClick(File item) {
        new MaterialAlertDialogBuilder(requireContext(), R.style.ThemeOverlay_WebHTV_LightDialog)
                .setTitle(R.string.video_error_title)
                .setMessage(R.string.setting_delete_backup_confirm)
                .setNegativeButton(R.string.dialog_negative, null)
                .setPositiveButton(R.string.dialog_positive, (dialog, which) -> {
                    if (adapter.remove(item) == 0) dismiss();
                })
                .show();
    }
}
