package com.fongmi.android.tv.ui.activity;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

import androidx.annotation.Nullable;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.ActivitySubSettingBinding;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.ui.fragment.ConfigManageFragment;
import com.fongmi.android.tv.ui.fragment.PopupKeywordFragment;
import com.fongmi.android.tv.ui.fragment.SettingAdvancedFragment;
import com.fongmi.android.tv.ui.fragment.SettingAppearanceFragment;
import com.fongmi.android.tv.ui.fragment.SettingDanmakuFragment;
import com.fongmi.android.tv.ui.fragment.SettingDataFragment;
import com.fongmi.android.tv.ui.fragment.SettingPlaybackFragment;
import com.fongmi.android.tv.ui.fragment.SettingPlayerFragment;
import com.fongmi.android.tv.ui.fragment.SettingSourceFragment;
import com.fongmi.android.tv.ui.fragment.SiteManageFragment;

public class SubSettingActivity extends BaseActivity {

    private static final String EXTRA_TYPE = "type";

    private ActivitySubSettingBinding mBinding;

    public static void start(Activity activity, int type) {
        Intent intent = new Intent(activity, SubSettingActivity.class);
        intent.putExtra(EXTRA_TYPE, type);
        activity.startActivity(intent);
    }

    private Fragment newFragment(int type) {
        return switch (type) {
            case 2 -> SettingPlayerFragment.newInstance();
            case 4 -> SettingDanmakuFragment.newInstance();
            case 5 -> SettingSourceFragment.newInstance();
            case 6 -> SettingAppearanceFragment.newInstance();
            case 7 -> SettingPlaybackFragment.newInstance();
            case 8 -> SettingAdvancedFragment.newInstance();
            case 9 -> SettingDataFragment.newInstance();
            case 10 -> ConfigManageFragment.newInstance(0);
            case 11 -> ConfigManageFragment.newInstance(1);
            case 12 -> SiteManageFragment.newInstance();
            case 13 -> PopupKeywordFragment.newInstance();
            default -> SettingSourceFragment.newInstance();
        };
    }

    @Override
    protected ViewBinding getBinding() {
        return mBinding = ActivitySubSettingBinding.inflate(getLayoutInflater());
    }

    @Override
    protected void initView(@Nullable Bundle savedInstanceState) {
        applyInsets();
        if (savedInstanceState == null) {
            int type = getIntent().getIntExtra(EXTRA_TYPE, 5);
            getSupportFragmentManager().beginTransaction().replace(R.id.container, newFragment(type), String.valueOf(type)).commitNow();
        }
    }

    private void applyInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(mBinding.getRoot(), (view, insets) -> {
            int top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top;
            int bottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom;
            view.setPadding(0, top, 0, bottom);
            return insets;
        });
        ViewCompat.requestApplyInsets(mBinding.getRoot());
    }
}