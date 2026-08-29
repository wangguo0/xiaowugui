package com.fongmi.android.tv.ui.fragment;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.config.LiveConfig;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Config;
import com.fongmi.android.tv.bean.Depot;
import com.fongmi.android.tv.databinding.FragmentConfigManageBinding;
import com.fongmi.android.tv.event.ConfigEvent;
import com.fongmi.android.tv.impl.Callback;
import com.fongmi.android.tv.impl.ConfigListener;
import com.fongmi.android.tv.ui.activity.ScanActivity;
import com.fongmi.android.tv.ui.adapter.ConfigCardAdapter;
import com.fongmi.android.tv.ui.base.BaseFragment;
import com.fongmi.android.tv.ui.dialog.ConfigDialog;
import com.fongmi.android.tv.ui.dialog.ShareUrlDialog;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.PermissionUtil;
import com.fongmi.android.tv.utils.Task;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.util.ArrayList;
import java.util.List;

public class ConfigManageFragment extends BaseFragment implements ConfigCardAdapter.OnClickListener, ConfigListener {

    private FragmentConfigManageBinding mBinding;
    private ConfigCardAdapter mAdapter;
    private boolean isVod;

    public static ConfigManageFragment newInstance(int type) {
        Bundle args = new Bundle();
        args.putInt("type", type);
        ConfigManageFragment fragment = new ConfigManageFragment();
        fragment.setArguments(args);
        return fragment;
    }

    private int getType() {
        return getArguments().getInt("type");
    }

    @Override
    protected ViewBinding getBinding(@NonNull LayoutInflater inflater, @Nullable ViewGroup container) {
        return mBinding = FragmentConfigManageBinding.inflate(inflater, container, false);
    }

    @Override
    protected void initView() {
        EventBus.getDefault().register(this);
        isVod = getType() == 0;
        mBinding.toolbar.setTitle(getString(isVod ? R.string.setting_subscription_manage : R.string.setting_subscription_manage_live));
        mBinding.bottomBar.setVisibility(isVod ? View.VISIBLE : View.GONE);
        mBinding.recycler.setHasFixedSize(true);
        mBinding.recycler.setLayoutManager(new LinearLayoutManager(requireContext()));
        mBinding.recycler.setAdapter(mAdapter = new ConfigCardAdapter(isVod, this));
        refresh();
    }

    @Override
    protected void initEvent() {
        mBinding.toolbar.setOnMenuItemClickListener(this::onMenuItemClick);
        mBinding.scan.setOnClickListener(this::onScan);
        mBinding.tutorial.setOnClickListener(v -> Notify.show(R.string.setting_subscription_tutorial));
    }

    private boolean onMenuItemClick(MenuItem item) {
        if (item.getItemId() == R.id.add) {
            onAdd();
            return true;
        }
        return false;
    }

    private void onAdd() {
        ConfigDialog dialog = ConfigDialog.create();
        if (isVod) dialog.vod();
        else dialog.live();
        dialog.show(this);
    }

    private void onScan(View view) {
        PermissionUtil.requestFile(this, granted -> launcher.launch(new Intent(requireActivity(), ScanActivity.class)));
    }

    private void refresh() {
        String currentUrl = getCurrentUrl();
        mAdapter.setItems(Config.getAll(getType()), currentUrl);
        boolean empty = mAdapter.getItemCount() == 0;
        mBinding.empty.text.setText(R.string.setting_subscription_empty);
        mBinding.empty.getRoot().setVisibility(empty ? View.VISIBLE : View.GONE);
    }

    private String getCurrentUrl() {
        Config config = isVod ? VodConfig.get().getConfig() : LiveConfig.get().getConfig();
        return config == null ? "" : config.getUrl();
    }

    private void load(Config config) {
        Callback callback = getCallback();
        if (isVod) VodConfig.load(config, callback);
        else LiveConfig.load(config, callback);
    }

    private Callback getCallback() {
        return new Callback() {
            @Override
            public void start() {
                Notify.progress(requireActivity());
            }

            @Override
            public void success() {
                Notify.dismiss();
                refresh();
            }

            @Override
            public void error(String msg) {
                Notify.dismiss();
                Notify.show(msg);
            }
        };
    }

    @Override
    public void onSelect(Config item) {
        load(item);
    }

    @Override
    public void onDeactivate(Config item) {
        Config.clearActive(getType());
        load(Config.create(getType()));
    }

    @Override
    public void onPin(Config item) {
        item.setTime(System.currentTimeMillis());
        item.save();
        refresh();
    }

    @Override
    public void onEdit(Config item) {
        ConfigDialog dialog = ConfigDialog.create().target(item).edit();
        dialog.show(this);
    }

    @Override
    public void onCopy(Config item) {
        ClipboardManager clipboard = (ClipboardManager) requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) clipboard.setPrimaryClip(ClipData.newPlainText("url", item.getUrl()));
        Notify.show(R.string.setting_subscription_copied);
    }

    @Override
    public void onShare(Config item) {
        ShareUrlDialog.create(item).show(this);
    }

    @Override
    public void onDelete(Config item) {
        boolean active = TextUtils.equals(item.getUrl(), getCurrentUrl());
        mAdapter.remove(item);
        refresh();
        if (active) load(Config.create(getType()));
    }

    @Override
    public void onSwitchDepot(Config item) {
        Task.execute(() -> {
            List<Depot> depots = isVod ? VodConfig.getDepots(item) : LiveConfig.getDepots(item);
            requireActivity().runOnUiThread(() -> showDepotPicker(item, depots));
        });
    }

    private void showDepotPicker(Config item, List<Depot> depots) {
        if (depots.isEmpty()) {
            Notify.show(R.string.setting_subscription_depot_empty);
            return;
        }
        List<String> names = new ArrayList<>();
        for (Depot depot : depots) names.add(depot.getName());
        new MaterialAlertDialogBuilder(requireActivity())
                .setTitle(R.string.setting_subscription_select_depot)
                .setItems(names.toArray(new CharSequence[0]), (dialog, which) -> switchDepot(item, depots.get(which)))
                .show();
    }

    private void switchDepot(Config item, Depot depot) {
        Callback callback = getCallback();
        if (isVod) VodConfig.switchDepot(item, depot, callback);
        else LiveConfig.switchDepot(item, depot, callback);
    }

    @Override
    public void setConfig(Config config) {
        if (config == null) return;
        String url = config.getUrl();
        if (!TextUtils.isEmpty(url) && url.startsWith("file")) {
            requireView().post(() -> PermissionUtil.requestFile(this, allGranted -> load(config)));
        } else {
            load(config);
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onConfigEvent(ConfigEvent event) {
        if (event.type() == ConfigEvent.Type.COMMON || event.type() == (isVod ? ConfigEvent.Type.VOD : ConfigEvent.Type.LIVE)) refresh();
    }

    @Override
    public void onHiddenChanged(boolean hidden) {
        if (!hidden) refresh();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        EventBus.getDefault().unregister(this);
    }

    private final ActivityResultLauncher<Intent> launcher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
        if (result.getResultCode() != Activity.RESULT_OK || result.getData() == null) return;
        String address = result.getData().getStringExtra("address");
        if (TextUtils.isEmpty(address)) return;
        Config config = Config.find(address, getType());
        load(config);
    });
}