package com.fongmi.android.tv.ui.activity;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.databinding.ActivityKeepBinding;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.ui.fragment.KeepFragment;

public class KeepActivity extends BaseActivity {

    private ActivityKeepBinding mBinding;

    public static void start(Activity activity) {
        activity.startActivity(new Intent(activity, KeepActivity.class));
    }

    @Override
    protected ViewBinding getBinding() {
        return mBinding = ActivityKeepBinding.inflate(getLayoutInflater());
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        if (savedInstanceState == null) getSupportFragmentManager().beginTransaction().add(mBinding.container.getId(), KeepFragment.newInstance()).commit();
    }
}
