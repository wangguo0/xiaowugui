package com.fongmi.android.tv.ui.dialog;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.CommunityProbe;
import com.fongmi.android.tv.bean.CommunitySource;
import com.fongmi.android.tv.bean.Config;
import com.fongmi.android.tv.databinding.DialogCommunityPickerBinding;
import com.fongmi.android.tv.event.ConfigEvent;
import com.fongmi.android.tv.ui.adapter.CommunitySourceAdapter;
import com.fongmi.android.tv.utils.Notify;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.tabs.TabLayout;

import java.util.ArrayList;
import java.util.List;

/**
 * 社区提取结果：点播/直播两个 Tab，勾选后一键添加。
 * 点播源添加到点播订阅源（type=0），直播源添加到直播源（type=1）；
 * 仅存储不激活，URL 已存在的自动跳过；添加成功即打社区标记并入队后台静默检测。
 */
public class CommunityPickerDialog extends BaseAlertDialog {

    private DialogCommunityPickerBinding binding;
    private CommunitySourceAdapter mAdapter;
    private final List<CommunitySource> all = new ArrayList<>();
    private final List<CommunitySource> vod = new ArrayList<>();
    private final List<CommunitySource> live = new ArrayList<>();

    public static CommunityPickerDialog create(List<CommunitySource> sources) {
        CommunityPickerDialog dialog = new CommunityPickerDialog();
        dialog.all.addAll(sources);
        return dialog;
    }

    @Override
    protected ViewBinding getBinding() {
        return binding = DialogCommunityPickerBinding.inflate(LayoutInflater.from(requireActivity()), null, false);
    }

    @NonNull
    @Override
    protected MaterialAlertDialogBuilder getBuilder() {
        return new MaterialAlertDialogBuilder(requireActivity(), R.style.ThemeOverlay_WebHTV_LightDialog).setView(getBinding().getRoot());
    }

    @Override
    protected void initView() {
        for (CommunitySource source : all) {
            if (source.getType() == 0) vod.add(source);
            else live.add(source);
        }
        binding.tabs.addTab(binding.tabs.newTab().setText(getString(R.string.community_tab_vod) + "(" + vod.size() + ")"));
        binding.tabs.addTab(binding.tabs.newTab().setText(getString(R.string.community_tab_live) + "(" + live.size() + ")"));
        binding.recycler.setLayoutManager(new LinearLayoutManager(requireContext()));
        binding.recycler.setAdapter(mAdapter = new CommunitySourceAdapter());
        // 默认勾选连通正常的条目（连通失败的置灰但仍可手动勾选）
        for (CommunitySource source : all) if (source.isReachable()) mAdapter.getSelected().add(source.getUrl());
        showTab(0);
    }

    @Override
    protected void initEvent() {
        binding.tabs.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {

            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                showTab(tab.getPosition());
            }

            @Override
            public void onTabUnselected(TabLayout.Tab tab) {
            }

            @Override
            public void onTabReselected(TabLayout.Tab tab) {
            }
        });
        binding.selectAll.setOnClickListener(v -> {
            List<CommunitySource> items = mAdapter.getItems();
            if (mAdapter.selectedCount() >= items.size() && !items.isEmpty()) mAdapter.deselectAll();
            else mAdapter.selectAll();
            updatePositive();
        });
        binding.negative.setOnClickListener(v -> dismiss());
        binding.positive.setOnClickListener(v -> onAdd());
        mAdapter.setOnSelectionChanged(this::updatePositive);
        updatePositive();
    }

    private void showTab(int position) {
        mAdapter.setItems(position == 0 ? vod : live);
        updatePositive();
    }

    private void updatePositive() {
        binding.positive.setText(getString(R.string.community_add, mAdapter.selectedCount()));
        binding.positive.setEnabled(mAdapter.selectedCount() > 0);
        binding.selectAll.setText(getString(allChecked() && !mAdapter.getItems().isEmpty() ? R.string.community_deselect_all : R.string.community_select_all));
    }

    // 当前 Tab 是否已全选（按钮文案切换依据）
    private boolean allChecked() {
        for (CommunitySource item : mAdapter.getItems()) {
            if (!mAdapter.getSelected().contains(item.getUrl())) return false;
        }
        return true;
    }

    private void onAdd() {
        List<CommunitySource> selected = new ArrayList<>();
        for (CommunitySource source : all) {
            if (mAdapter.getSelected().contains(source.getUrl())) selected.add(source);
        }
        if (selected.isEmpty()) return;
        List<String> vodUrls = new ArrayList<>();
        for (Config config : Config.getAll(0)) vodUrls.add(config.getUrl());
        List<String> liveUrls = new ArrayList<>();
        for (Config config : Config.getAll(1)) liveUrls.add(config.getUrl());
        int added = 0;
        int skipped = 0;
        List<Config> toProbe = new ArrayList<>();
        for (CommunitySource source : selected) {
            boolean exists = source.getType() == 0 ? vodUrls.contains(source.getUrl()) : liveUrls.contains(source.getUrl());
            if (exists) {
                skipped++;
                continue;
            }
            // create(...).insert() 仅入库不激活，与手动添加的「存储但不切换」一致
            Config config = Config.create(source.getType(), source.getUrl(), source.getName());
            CommunityProbe.markAdded(config.getUrl());
            toProbe.add(config);
            added++;
        }
        if (added > 0) {
            CommunityProbe.enqueue(toProbe);
            ConfigEvent.common();
        }
        String msg = skipped == 0 ? getString(R.string.community_added, added) : getString(R.string.community_added_skip, added, skipped);
        Notify.show(msg);
        dismissAllowingStateLoss();
    }

    @Override
    public void onStart() {
        super.onStart();
        if (getDialog() == null || getDialog().getWindow() == null) return;
        ViewGroup.LayoutParams params = getDialog().getWindow().getDecorView().getLayoutParams();
        // 弹窗占满屏宽，保证底部「添加(N)」按钮完整显示
        params.width = getDialog().getWindow().getDecorView().getResources().getDisplayMetrics().widthPixels;
        getDialog().getWindow().getDecorView().setLayoutParams(params);
    }
}
