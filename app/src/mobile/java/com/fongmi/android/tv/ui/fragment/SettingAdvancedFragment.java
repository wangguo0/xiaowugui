package com.fongmi.android.tv.ui.fragment;

import android.content.Intent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.databinding.FragmentSettingAdvancedBinding;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.ui.activity.HomeActivity;
import com.fongmi.android.tv.ui.base.BaseFragment;
import com.fongmi.android.tv.ui.dialog.ChoiceDialog;
import com.github.catvod.bean.Doh;
import com.github.catvod.net.OkHttp;

import java.util.ArrayList;
import java.util.List;

public class SettingAdvancedFragment extends BaseFragment {

    private FragmentSettingAdvancedBinding mBinding;

    public static SettingAdvancedFragment newInstance() {
        return new SettingAdvancedFragment();
    }

    private String getSwitch(boolean value) {
        return getString(value ? R.string.setting_on : R.string.setting_off);
    }

    private int getDohIndex() {
        return Math.max(0, VodConfig.get().getDoh().indexOf(Doh.objectFrom(Setting.getDoh())));
    }

    private String[] getDohList() {
        List<String> list = new ArrayList<>();
        for (Doh item : VodConfig.get().getDoh()) list.add(item.getName());
        return list.toArray(new String[0]);
    }

    @Override
    protected ViewBinding getBinding(@NonNull LayoutInflater inflater, @Nullable ViewGroup container) {
        return mBinding = FragmentSettingAdvancedBinding.inflate(inflater, container, false);
    }

    @Override
    protected void initView() {
        mBinding.dohText.setText(getDohList()[getDohIndex()]);
        mBinding.incognitoText.setText(getSwitch(Setting.isIncognito()));
    }

    @Override
    protected void initEvent() {
        mBinding.enhance.setOnClickListener(view -> openEnhance());
        mBinding.incognito.setOnClickListener(this::setIncognito);
        mBinding.doh.setOnClickListener(this::setDoh);
    }

    private void openEnhance() {
        Intent intent = new Intent(requireContext(), HomeActivity.class)
                .putExtra(HomeActivity.EXTRA_NAV_POSITION, 3)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        requireContext().startActivity(intent);
    }

    private void setIncognito(View view) {
        Setting.putIncognito(!Setting.isIncognito());
        mBinding.incognitoText.setText(getSwitch(Setting.isIncognito()));
    }

    private void setDoh(View view) {
        ChoiceDialog.showSingle(this, R.string.setting_doh, getDohList(), getDohIndex(), which -> {
            setDoh(VodConfig.get().getDoh().get(which));
        });
    }

    private void setDoh(Doh doh) {
        OkHttp.dns().setDoh(doh);
        Setting.putDoh(doh.toString());
        mBinding.dohText.setText(doh.getName());
    }
}