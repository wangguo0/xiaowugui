package com.fongmi.android.tv.ui.dialog;

import android.app.Dialog;
import android.content.Context;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.view.WindowCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.databinding.DialogQuickSearchBinding;
import com.fongmi.android.tv.ui.adapter.QuickAdapter;
import com.fongmi.android.tv.utils.ResUtil;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;

import java.util.ArrayList;
import java.util.List;

public class QuickSearchDialog extends BaseBottomSheetDialog implements QuickAdapter.OnClickListener {

    // 当前页：由 Activity 推送的冻结结果（首屏钉死卡片 + 可选「加载更多」哨兵），弹窗只做渲染
    private final List<Vod> mPage;
    private DialogQuickSearchBinding binding;
    private QuickAdapter.OnClickListener listener;
    private OnSearchListener searchListener;
    private QuickAdapter adapter;
    private Runnable loadMore;
    private String keyword;
    private String title;

    public QuickSearchDialog() {
        this.mPage = new ArrayList<>();
    }

    public static QuickSearchDialog create() {
        return new QuickSearchDialog();
    }

    public interface OnSearchListener {
        void onSearch(String keyword);
    }

    public QuickSearchDialog title(String title) {
        this.title = title;
        return this;
    }

    public QuickSearchDialog keyword(String keyword) {
        this.keyword = keyword;
        return this;
    }

    public QuickSearchDialog listener(QuickAdapter.OnClickListener listener) {
        this.listener = listener;
        return this;
    }

    public QuickSearchDialog searchListener(OnSearchListener listener) {
        this.searchListener = listener;
        return this;
    }

    public QuickSearchDialog loadMore(Runnable loadMore) {
        this.loadMore = loadMore;
        return this;
    }

    public QuickSearchDialog items(List<Vod> items) {
        mPage.clear();
        if (items != null) mPage.addAll(items);
        return this;
    }

    public void show(FragmentActivity activity) {
        for (Fragment fragment : activity.getSupportFragmentManager().getFragments()) if (fragment instanceof QuickSearchDialog) return;
        show(activity.getSupportFragmentManager(), null);
    }

    public boolean isActive() {
        return isAdded() && !isRemoving();
    }

    public void clear() {
        mPage.clear();
        if (adapter != null) adapter.clear();
        if (binding != null) binding.empty.setVisibility(View.GONE);
    }

    // Activity 推送新一页：已展示卡片钉死，「加载更多」只在末尾追加，弹窗不自行分页
    public void setPage(List<Vod> items) {
        mPage.clear();
        if (items != null) mPage.addAll(items);
        applyPage();
    }

    private void applyPage() {
        if (adapter == null) return;
        adapter.setItems(new ArrayList<>(mPage));
        updateEmpty();
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        Dialog dialog = super.onCreateDialog(savedInstanceState);
        configureWindow(dialog);
        return dialog;
    }

    @Override
    public void onStart() {
        super.onStart();
        configureWindow(getDialog());
    }

    @Override
    protected ViewBinding getBinding(@NonNull LayoutInflater inflater, @Nullable ViewGroup container) {
        return binding = DialogQuickSearchBinding.inflate(inflater, container, false);
    }

    @Override
    protected void initView() {
        binding.title.setText(title);
        binding.keyword.setText(keyword);
        binding.keyword.setSelection(binding.keyword.length());
        binding.search.setOnClickListener(view -> submitSearch());
        binding.keyword.setOnEditorActionListener((view, actionId, event) -> {
            if (actionId != EditorInfo.IME_ACTION_SEARCH) return false;
            submitSearch();
            return true;
        });
        binding.recycler.setHasFixedSize(true);
        binding.recycler.setItemAnimator(null);
        binding.recycler.setAdapter(adapter = new QuickAdapter(this));
        adapter.setLoadMore(() -> {
            if (loadMore != null) loadMore.run();
        });
        applyPage();
        binding.empty.setVisibility(View.GONE);
    }

    @Override
    protected boolean transparent() {
        return true;
    }

    @Override
    protected boolean stableOverlay() {
        return true;
    }

    @Override
    protected void setBehavior(BottomSheetDialog dialog) {
        FrameLayout sheet = dialog.findViewById(com.google.android.material.R.id.design_bottom_sheet);
        if (sheet == null) return;
        sheet.setBackgroundColor(ResUtil.getColor(R.color.transparent));
        int height = getPanelHeight();
        ViewGroup.LayoutParams params = sheet.getLayoutParams();
        params.height = height;
        sheet.setLayoutParams(params);
        BottomSheetBehavior<FrameLayout> behavior = BottomSheetBehavior.from(sheet);
        behavior.setPeekHeight(height);
        behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
        behavior.setSkipCollapsed(true);
        behavior.setDraggable(false);
    }

    private void configureWindow(Dialog dialog) {
        if (dialog == null || dialog.getWindow() == null) return;
        Window window = dialog.getWindow();
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND | WindowManager.LayoutParams.FLAG_FULLSCREEN);
        window.setDimAmount(0f);
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING);
        WindowCompat.setDecorFitsSystemWindows(window, true);
    }

    private int getPanelHeight() {
        int screen = ResUtil.getScreenHeight(requireContext());
        if (ResUtil.isLand(requireContext())) return Math.max(ResUtil.dp2px(260), Math.min(ResUtil.dp2px(430), Math.round(screen * 0.78f)));
        return Math.max(ResUtil.dp2px(360), Math.min(ResUtil.dp2px(560), Math.round(screen * 0.58f)));
    }

    private void updateEmpty() {
        if (binding == null || adapter == null) return;
        binding.empty.setVisibility(adapter.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void submitSearch() {
        if (binding == null) return;
        String value = binding.keyword.getText() == null ? "" : binding.keyword.getText().toString().trim();
        if (TextUtils.isEmpty(value)) return;
        keyword = value;
        binding.title.setText(getString(R.string.detail_search, value));
        hideKeyboard();
        clear();
        if (searchListener != null) searchListener.onSearch(value);
    }

    private void hideKeyboard() {
        InputMethodManager manager = (InputMethodManager) requireContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (manager != null) manager.hideSoftInputFromWindow(binding.keyword.getWindowToken(), 0);
        binding.keyword.clearFocus();
    }

    @Override
    public void onItemClick(Vod item) {
        if (listener != null) listener.onItemClick(item);
        dismiss();
    }
}
