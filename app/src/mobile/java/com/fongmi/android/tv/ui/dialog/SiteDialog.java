package com.fongmi.android.tv.ui.dialog;

import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.text.Editable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;

import androidx.annotation.NonNull;
import androidx.appcompat.widget.LinearLayoutCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.databinding.DialogSiteBinding;
import com.fongmi.android.tv.impl.Callback;
import com.fongmi.android.tv.impl.SiteListener;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.setting.SiteBlockSetting;
import com.fongmi.android.tv.setting.SiteHealthStore;
import com.fongmi.android.tv.ui.adapter.SiteAdapter;
import com.fongmi.android.tv.ui.custom.CustomTextListener;
import com.fongmi.android.tv.ui.custom.SiteTester;
import com.fongmi.android.tv.ui.custom.SpaceItemDecoration;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.utils.Util;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textview.MaterialTextView;

import java.util.ArrayList;
import java.util.List;

public class SiteDialog extends BaseAlertDialog implements SiteAdapter.OnClickListener {

    private DialogSiteBinding binding;
    private SiteListener listener;
    private SiteAdapter adapter;
    private ItemTouchHelper sortTouchHelper;
    private SpaceItemDecoration itemDecoration;
    private SiteTester tester;
    private List<String> groups;
    private String selectedGroup = "";
    private boolean search;
    private boolean change;
    private boolean block;
    private int columnCount = 1;

    public static SiteDialog create() {
        return new SiteDialog();
    }

    public SiteDialog search() {
        search = true;
        return this;
    }

    public SiteDialog change() {
        change = true;
        return this;
    }

    public void show(Fragment fragment) {
        show(fragment.getChildFragmentManager(), null);
        if (fragment instanceof SiteListener) listener = (SiteListener) fragment;
    }

    @Override
    protected ViewBinding getBinding() {
        return binding = DialogSiteBinding.inflate(getLayoutInflater());
    }

    @Override
    protected MaterialAlertDialogBuilder getBuilder() {
        return new MaterialAlertDialogBuilder(requireActivity(), R.style.ThemeOverlay_WebHTV_LightDialog).setView(getBinding().getRoot());
    }

    @Override
    protected void initView() {
        adapter = new SiteAdapter(this);
        groups = getGroups();
        binding.recycler.setAdapter(adapter);
        adapter.search(search).change(change);
        setColumnCount(Setting.getSiteColumn());
        setGroupView();
        filter();
        binding.recycler.setItemAnimator(null);
        binding.recycler.setHasFixedSize(true);
        attachSortTouchHelper();
        binding.recycler.post(() -> binding.recycler.scrollToPosition(0));
        updateBlockVisibility();
    }

    private void attachSortTouchHelper() {
        sortTouchHelper = new ItemTouchHelper(new ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP | ItemTouchHelper.DOWN | ItemTouchHelper.LEFT | ItemTouchHelper.RIGHT, 0) {
            @Override
            public boolean isLongPressDragEnabled() {
                return false;
            }

            @Override
            public boolean isItemViewSwipeEnabled() {
                return false;
            }

            @Override
            public boolean onMove(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder source, @NonNull RecyclerView.ViewHolder target) {
                return adapter.drag(source.getBindingAdapterPosition(), target.getBindingAdapterPosition());
            }

            @Override
            public void onSwiped(@NonNull RecyclerView.ViewHolder viewHolder, int direction) {
            }
        });
        sortTouchHelper.attachToRecyclerView(binding.recycler);
    }

    @Override
    protected void initEvent() {
        binding.keyword.setOnEditorActionListener((textView, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) Util.hideKeyboard(binding.keyword);
            return false;
        });
        binding.keyword.addTextChangedListener(new CustomTextListener() {
            @Override
            public void afterTextChanged(Editable s) {
                filter();
                binding.recycler.scrollToPosition(0);
            }
        });
        binding.block.setOnClickListener(this::onBlockToggle);
        binding.search.setOnClickListener(this::onColumnToggle);
        binding.test.setOnClickListener(v -> onTestClick());
        binding.reset.setOnClickListener(v -> onResetClick());
        binding.searchBox.setOnClickListener(v -> expandSearch());
        binding.searchCancel.setOnClickListener(v -> collapseSearch());
        if (getDialog() != null) getDialog().setOnKeyListener((d, keyCode, event) -> {
            if (keyCode == android.view.KeyEvent.KEYCODE_BACK && event.getAction() == android.view.KeyEvent.ACTION_UP && binding.searchRow.getVisibility() == View.VISIBLE) {
                collapseSearch();
                return true;
            }
            return false;
        });
    }

    private void expandSearch() {
        binding.topRow.setVisibility(View.GONE);
        binding.searchRow.setVisibility(View.VISIBLE);
        binding.headerRow.setVisibility(View.GONE);
        binding.resetRow.setVisibility(View.GONE);
        binding.keyword.requestFocus();
        Util.showKeyboard(binding.keyword);
    }

    private void collapseSearch() {
        binding.keyword.setText("");
        Util.hideKeyboard(binding.keyword);
        binding.searchRow.setVisibility(View.GONE);
        binding.topRow.setVisibility(View.VISIBLE);
        binding.headerRow.setVisibility(View.VISIBLE);
        updateBlockVisibility();
    }

    private void onTestClick() {
        if (tester != null) return;
        List<Site> sites = new ArrayList<>(adapter.getItems());
        if (sites.isEmpty()) return;
        binding.test.setEnabled(false);
        tester = SiteTester.start(sites, new SiteTester.Callback() {
            @Override
            public void onResult(Site site, SiteHealthStore.Status status, int index, int total) {
                App.post(() -> {
                    if (binding == null || tester == null) return;
                    binding.test.setText(getString(R.string.site_testing_progress, index, total));
                    int position = adapter.indexOf(site);
                    if (position >= 0) adapter.notifyItemChanged(position);
                });
            }

            @Override
            public void onFinish(int good, int warn, int bad) {
                App.post(() -> {
                    tester = null;
                    if (binding == null) return;
                    binding.test.setEnabled(true);
                    binding.test.setText(R.string.site_test_connect);
                    Notify.show(getString(R.string.site_test_result, good, warn, bad));
                });
            }
        });
    }

    private void onResetClick() {
        new MaterialAlertDialogBuilder(requireActivity(), R.style.ThemeOverlay_WebHTV_LightDialog)
                .setTitle(R.string.site_reset_title)
                .setMessage(R.string.site_reset_msg)
                .setNegativeButton(R.string.dialog_cancel, null)
                .setPositiveButton(R.string.dialog_confirm, (d, w) -> doReset())
                .show();
    }

    private void doReset() {
        SiteBlockSetting.clear();
        Site.deleteAll();
        VodConfig.load(VodConfig.get().getConfig(), new Callback() {
            @Override
            public void success() {
                if (binding == null) return;
                groups = getGroups();
                setGroupView();
                adapter.reload();
                filter();
                Notify.show(R.string.site_reset_done);
            }

            @Override
            public void error(String msg) {
                if (binding == null) return;
                adapter.reload();
                filter();
            }
        });
    }

    private void onBlockToggle(View view) {
        Util.hideKeyboard(binding.keyword);
        block = !block;
        binding.block.setSelected(block);
        int color = block ? R.color.site_state_active : R.color.dialog_outlined_button_text;
        binding.block.setTextColor(ContextCompat.getColor(requireContext(), color));
        binding.block.setSupportCompoundDrawablesTintList(ContextCompat.getColorStateList(requireContext(), color));
        updateBlockVisibility();
        adapter.block(block);
        groups = getGroups();
        setGroupView();
        filter();
        binding.recycler.scrollToPosition(0);
    }

    // 屏蔽管理关闭时隐藏「屏蔽状态」标题与「恢复初始设置」
    private void updateBlockVisibility() {
        binding.blockHeader.setVisibility(block ? View.VISIBLE : View.GONE);
        binding.resetRow.setVisibility(block ? View.VISIBLE : View.GONE);
    }

    private void onColumnToggle(View view) {
        Util.hideKeyboard(binding.keyword);
        int nextColumn = columnCount == 1 ? 2 : 1;
        Setting.putSiteColumn(nextColumn);
        setColumnCount(nextColumn);
    }

    private void setColumnCount(int count) {
        columnCount = count == 2 ? 2 : 1;
        if (itemDecoration != null) binding.recycler.removeItemDecoration(itemDecoration);
        itemDecoration = new SpaceItemDecoration(columnCount, 8);
        binding.recycler.addItemDecoration(itemDecoration);
        binding.recycler.setLayoutManager(columnCount == 1 ? new LinearLayoutManager(requireContext()) : new GridLayoutManager(requireContext(), columnCount));
        binding.search.setText(columnCount == 1 ? R.string.site_column_double : R.string.site_column_single);
        binding.search.setCompoundDrawablesRelativeWithIntrinsicBounds(0, columnCount == 1 ? R.drawable.ic_site_double_column : R.drawable.ic_site_single_column, 0, 0);
        if (adapter != null) adapter.column(columnCount);
    }

    private List<String> getGroups() {
        return new ArrayList<>(Site.getGroups(SiteBlockSetting.filter(VodConfig.get().getSites(), block)));
    }

    private void setGroupView() {
        if (groups.isEmpty()) {
            selectedGroup = "";
            binding.groupScroll.setVisibility(View.GONE);
            return;
        }
        if (!TextUtils.isEmpty(selectedGroup) && !groups.contains(selectedGroup)) selectedGroup = "";
        binding.groupScroll.setVisibility(View.VISIBLE);
        binding.groupList.removeAllViews();
        for (String group : groups) binding.groupList.addView(getGroupView(group));
        updateGroupView();
    }

    private MaterialButton getGroupView(String group) {
        MaterialButton button = new MaterialButton(requireContext(), null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
        LinearLayoutCompat.LayoutParams params = new LinearLayoutCompat.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMarginEnd(ResUtil.dp2px(8));
        button.setLayoutParams(params);
        button.setText(group);
        button.setSingleLine(true);
        button.setAllCaps(false);
        button.setMinWidth(0);
        button.setMinHeight(0);
        button.setMinimumWidth(0);
        button.setMinimumHeight(0);
        button.setInsetTop(0);
        button.setInsetBottom(0);
        button.setPadding(ResUtil.dp2px(14), ResUtil.dp2px(6), ResUtil.dp2px(14), ResUtil.dp2px(6));
        button.setTextColor(ContextCompat.getColorStateList(requireContext(), R.color.dialog_outlined_button_text));
        button.setBackgroundTintList(ContextCompat.getColorStateList(requireContext(), R.color.dialog_outlined_button_bg));
        button.setStrokeColor(ContextCompat.getColorStateList(requireContext(), R.color.dialog_outlined_button_stroke));
        button.setOnClickListener(v -> onGroupClick(group, button));
        return button;
    }

    private void onGroupClick(String group, View view) {
        selectedGroup = group.equals(selectedGroup) ? "" : group;
        updateGroupView();
        filter();
        binding.recycler.scrollToPosition(0);
        if (!TextUtils.isEmpty(selectedGroup)) centerGroup(view);
    }

    private void updateGroupView() {
        for (int i = 0; i < binding.groupList.getChildCount(); i++) {
            View view = binding.groupList.getChildAt(i);
            boolean selected = ((MaterialButton) view).getText().toString().equals(selectedGroup);
            view.setSelected(selected);
            view.setAlpha(TextUtils.isEmpty(selectedGroup) || selected ? 1.0f : 0.5f);
        }
    }

    private void centerGroup(View view) {
        binding.groupScroll.post(() -> binding.groupScroll.smoothScrollTo(Math.max(0, view.getLeft() + view.getWidth() / 2 - binding.groupScroll.getWidth() / 2), 0));
    }

    private void filter() {
        adapter.filter(selectedGroup, binding.keyword.getText().toString());
    }

    @Override
    public void onTextClick(Site item) {
        if (block) {
            if (SiteBlockSetting.isLocked(item)) {
                Notify.show(R.string.site_block_locked_toast);
                return;
            }
            SiteBlockSetting.toggle(item);
            filter();
            return;
        }
        if (listener != null) listener.setSite(item);
        dismiss();
    }

    @Override
    public void onSearchClick(int position, Site item, View anchor) {
        String[] labels = {getString(R.string.site_state_search_on), getString(R.string.site_state_search_off)};
        int current = item.isSearchable() ? 0 : 1;
        showMenu(anchor, labels, null, current, index -> {
            boolean next = index == 0;
            runWithConfirm(item, item.getSearchable() == 0, position, () -> item.setSearchable(next).save());
        });
    }

    @Override
    public void onChangeClick(int position, Site item, View anchor) {
        String[] labels = {getString(R.string.site_state_change_on), getString(R.string.site_state_change_off), getString(R.string.site_state_blocked)};
        String[] descs = {getString(R.string.site_desc_change_on), getString(R.string.site_desc_change_off), getString(R.string.site_desc_blocked)};
        int current = SiteBlockSetting.isBlocked(item) ? 2 : (item.isChangeable() ? 0 : 1);
        showMenu(anchor, labels, descs, current, index -> runWithConfirm(item, item.getChangeable() == 0, position, () -> {
            item.setChangeable(index != 1).save();
            SiteBlockSetting.setBlocked(item, index == 2);
        }));
    }

    // 点击状态文字弹出下拉菜单，由用户选择目标状态；每项下方附小号字功能说明
    private void showMenu(View anchor, String[] labels, String[] descs, int checked, java.util.function.IntConsumer onSelect) {
        anchor.post(() -> {
            android.widget.PopupWindow menu = new android.widget.PopupWindow();
            LinearLayoutCompat container = new LinearLayoutCompat(requireActivity());
            container.setOrientation(LinearLayoutCompat.VERTICAL);
            int paddingV = ResUtil.dp2px(6);
            int paddingH = ResUtil.dp2px(16);
            for (int i = 0; i < labels.length; i++) {
                container.addView(getMenuItemView(i, i == checked, labels[i], descs == null ? null : descs[i], paddingV, paddingH, view -> {
                    menu.dismiss();
                    onSelect.accept((Integer) view.getTag());
                }));
            }
            container.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
            menu.setContentView(container);
            menu.setWidth(Math.max(container.getMeasuredWidth(), anchor.getWidth()));
            menu.setHeight(ViewGroup.LayoutParams.WRAP_CONTENT);
            // 必须使用不透明背景：透明 window background 会导致弹窗立即自动关闭
            menu.setBackgroundDrawable(ContextCompat.getDrawable(requireActivity(), R.drawable.shape_site_menu_bg));
            menu.setOutsideTouchable(true);
            menu.setFocusable(false);
            menu.showAsDropDown(anchor);
        });
    }

    private View getMenuItemView(int index, boolean checked, String label, String desc, int paddingV, int paddingH, View.OnClickListener listener) {
        LinearLayoutCompat item = new LinearLayoutCompat(requireActivity());
        item.setOrientation(LinearLayoutCompat.VERTICAL);
        item.setTag(index);
        item.setPadding(paddingH, paddingV, paddingH, paddingV);
        item.setBackgroundResource(getSelectableBg());
        item.setOnClickListener(listener);
        item.addView(getMenuLabelView((checked ? "✓  " : "    ") + label, 14f, true));
        if (!TextUtils.isEmpty(desc)) item.addView(getMenuLabelView(desc, 11f, false));
        return item;
    }

    private MaterialTextView getMenuLabelView(String text, float sizeSp, boolean isTitle) {
        MaterialTextView view = new MaterialTextView(requireActivity());
        LinearLayoutCompat.LayoutParams params = new LinearLayoutCompat.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        if (!isTitle) params.topMargin = ResUtil.dp2px(2);
        view.setLayoutParams(params);
        view.setText(text);
        view.setTextSize(sizeSp);
        view.setMaxWidth(ResUtil.dp2px(260));
        view.setTextColor(isTitle ? Color.parseColor("#202124") : Color.parseColor("#8A8F98"));
        return view;
    }

    private int getSelectableBg() {
        android.util.TypedValue value = new android.util.TypedValue();
        requireActivity().getTheme().resolveAttribute(android.R.attr.selectableItemBackground, value, true);
        return value.resourceId;
    }

    private void runWithConfirm(Site item, boolean locked, int position, Runnable action) {
        if (!locked) {
            action.run();
            adapter.notifyItemChanged(position);
            return;
        }
        new MaterialAlertDialogBuilder(requireActivity(), R.style.ThemeOverlay_WebHTV_LightDialog)
                .setTitle(R.string.site_lock_warning_title)
                .setMessage(getString(R.string.site_lock_warning_msg, item.getName()))
                .setNegativeButton(R.string.dialog_cancel, null)
                .setPositiveButton(R.string.dialog_confirm, (d, w) -> {
                    action.run();
                    adapter.notifyItemChanged(position);
                })
                .show();
    }

    @Override
    public boolean onTextLongClick(SiteAdapter.ViewHolder holder) {
        if (sortTouchHelper == null || holder.getBindingAdapterPosition() == RecyclerView.NO_POSITION) return false;
        Util.hideKeyboard(binding.keyword);
        sortTouchHelper.startDrag(holder);
        return true;
    }

    @Override
    public boolean onSearchLongClick(Site item) {
        boolean result = !item.isSearchable();
        adapter.getItems().forEach(site -> site.setSearchable(result).save());
        adapter.notifyItemRangeChanged(0, adapter.getItemCount());
        return true;
    }

    @Override
    public boolean onChangeLongClick(Site item) {
        boolean result = !item.isChangeable();
        adapter.getItems().forEach(site -> site.setChangeable(result).save());
        adapter.notifyItemRangeChanged(0, adapter.getItemCount());
        return true;
    }

    @Override
    public void onDestroyView() {
        if (tester != null) {
            tester.cancel();
            tester = null;
        }
        binding = null;
        super.onDestroyView();
    }

    @Override
    public void onStart() {
        super.onStart();
        if (adapter.getItemCount() == 0 && SiteBlockSetting.filter(VodConfig.get().getSites(), true).isEmpty()) dismiss();
        else configureWindow();
    }

    private void configureWindow() {
        if (getDialog() == null || getDialog().getWindow() == null) return;
        Window window = getDialog().getWindow();
        WindowManager.LayoutParams params = window.getAttributes();
        boolean land = ResUtil.isLand(requireContext());
        int width = Math.min(Math.round(ResUtil.getScreenWidth(requireContext()) * (land ? 0.5f : 0.92f)), ResUtil.dp2px(620));
        params.width = Math.max(width, ResUtil.dp2px(320));
        params.height = WindowManager.LayoutParams.WRAP_CONTENT;
        params.gravity = Gravity.CENTER;
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        window.clearFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM);
        window.getDecorView().setPadding(0, 0, 0, 0);
        window.setAttributes(params);
        window.setLayout(params.width, WindowManager.LayoutParams.WRAP_CONTENT);
    }
}
