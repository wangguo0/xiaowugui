package com.fongmi.android.tv.ui.activity;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.databinding.ActivityHistoryBinding;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.ui.fragment.HistoryFragment;

public class HistoryActivity extends BaseActivity {

    private ActivityHistoryBinding mBinding;

    public static void start(Activity activity) {
        activity.startActivity(new Intent(activity, HistoryActivity.class));
    }

    @Override
    protected ViewBinding getBinding() {
        return mBinding = ActivityHistoryBinding.inflate(getLayoutInflater());
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        if (savedInstanceState == null) getSupportFragmentManager().beginTransaction().add(mBinding.container.getId(), HistoryFragment.newInstance()).commit();
    }
}
