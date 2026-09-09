package com.fongmi.android.tv.ui.fragment;

import android.content.ContentResolver;
import android.net.Uri;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.viewbinding.ViewBinding;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.load.engine.GlideException;
import com.bumptech.glide.request.RequestListener;
import com.bumptech.glide.request.RequestOptions;
import com.bumptech.glide.request.target.Target;
import com.fongmi.android.tv.Updater;
import com.fongmi.android.tv.databinding.FragmentSettingBinding;
import com.fongmi.android.tv.ui.activity.AvatarCropActivity;
import com.fongmi.android.tv.ui.activity.SubSettingActivity;
import com.fongmi.android.tv.ui.base.BaseFragment;
import com.fongmi.android.tv.ui.dialog.AboutDialog;
import com.fongmi.android.tv.ui.dialog.ShareAppDialog;
import com.fongmi.android.tv.utils.ResUtil;
import com.github.catvod.utils.Prefers;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

public class SettingFragment extends BaseFragment {

    private static final String AVATAR_KEY = "avatar";
    private static final int AVATAR_SIZE_DP = 144;

    private FragmentSettingBinding mBinding;

    public static SettingFragment newInstance() {
        return new SettingFragment();
    }

    @Override
    protected ViewBinding getBinding(@NonNull LayoutInflater inflater, @Nullable ViewGroup container) {
        return mBinding = FragmentSettingBinding.inflate(inflater, container, false);
    }

    @Override
    protected void initView() {
        loadAvatar();
    }

    @Override
    protected void initEvent() {
        mBinding.avatar.setOnClickListener(view -> pickAvatar());
        mBinding.sourceGroup.setOnClickListener(view -> SubSettingActivity.start(requireActivity(), 5));
        mBinding.appearanceGroup.setOnClickListener(view -> SubSettingActivity.start(requireActivity(), 6));
        mBinding.playbackGroup.setOnClickListener(view -> SubSettingActivity.start(requireActivity(), 7));
        mBinding.advancedGroup.setOnClickListener(view -> SubSettingActivity.start(requireActivity(), 8));
        mBinding.dataGroup.setOnClickListener(view -> SubSettingActivity.start(requireActivity(), 9));
        mBinding.aboutRow.setOnClickListener(this::setVersion);
        mBinding.shareRow.setOnClickListener(view -> ShareAppDialog.create().show(this));
    }

    /** 第二步：裁剪完成后应用结果 */
    private final ActivityResultLauncher<android.content.Intent> cropLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result == null || result.getResultCode() != android.app.Activity.RESULT_OK || result.getData() == null) return;
                try {
                    String path = result.getData().getStringExtra(AvatarCropActivity.EXTRA_PATH);
                    if (path == null || path.isEmpty()) return;
                    deleteOldAvatar();
                    Prefers.put(AVATAR_KEY, path);
                    loadAvatar();
                } catch (Throwable e) {
                    e.printStackTrace();
                }
            });

    /** 第一步：选择本地图片 */
    private final ActivityResultLauncher<String> avatarPicker = registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
        if (uri == null) return;
        try {
            // 第二步：进入圆形裁剪页
            cropLauncher.launch(AvatarCropActivity.buildIntent(requireActivity(), uri));
        } catch (Throwable e) {
            e.printStackTrace();
        }
    });

    private void pickAvatar() {
        try {
            avatarPicker.launch("image/*");
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }

    private void deleteOldAvatar() {
        try {
            String old = Prefers.getString(AVATAR_KEY, "");
            if (!old.isEmpty()) {
                File f = new File(old);
                if (f.exists()) f.delete();
            }
        } catch (Throwable ignored) {
        }
    }

    private void loadAvatar() {
        String path = Prefers.getString(AVATAR_KEY, "");
        File file = path.isEmpty() ? null : new File(path);
        if (file == null || !file.exists()) return; // 无自定头像时保持 XML 默认（启动图）
        try {
            int size = ResUtil.dp2px(AVATAR_SIZE_DP);
            Glide.with(mBinding.avatar)
                    .load(file)
                    .diskCacheStrategy(DiskCacheStrategy.NONE)
                    .skipMemoryCache(true)
                    .apply(new RequestOptions().signature(new com.bumptech.glide.signature.ObjectKey(file.lastModified() + "_" + file.getName())))
                    .circleCrop()
                    .override(size, size)
                    .into(mBinding.avatar);
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }

    private void setVersion(View view) {
        AboutDialog.show(requireActivity(), () -> Updater.create().force().start(requireActivity()));
    }
}