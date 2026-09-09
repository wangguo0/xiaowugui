package com.fongmi.android.tv.ui.fragment;

import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.PopupShield;
import com.fongmi.android.tv.databinding.FragmentPopupKeywordBinding;
import com.fongmi.android.tv.ui.adapter.PopupKeywordAdapter;
import com.fongmi.android.tv.ui.base.BaseFragment;
import com.fongmi.android.tv.ui.custom.SpaceItemDecoration;
import com.fongmi.android.tv.utils.Notify;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.List;

/**
 * 弹窗拦截关键字管理（子页面）：命中任一关键字的第三方源弹窗会被自动屏蔽。
 */
public class PopupKeywordFragment extends BaseFragment implements PopupKeywordAdapter.OnClickListener {

    private FragmentPopupKeywordBinding mBinding;
    private PopupKeywordAdapter adapter;

    public static PopupKeywordFragment newInstance() {
        return new PopupKeywordFragment();
    }

    @Override
    protected ViewBinding getBinding(@NonNull LayoutInflater inflater, @Nullable ViewGroup container) {
        return mBinding = FragmentPopupKeywordBinding.inflate(inflater, container, false);
    }

    @Override
    protected void initView() {
        mBinding.recycler.setItemAnimator(null);
        mBinding.recycler.setHasFixedSize(true);
        mBinding.recycler.addItemDecoration(new SpaceItemDecoration(1, 8));
        mBinding.recycler.setAdapter(adapter = new PopupKeywordAdapter(this));
        refresh(PopupShield.getKeywords());
    }

    @Override
    protected void initEvent() {
        mBinding.toolbar.setNavigationOnClickListener(v -> requireActivity().finish());
        mBinding.add.setOnClickListener(v -> onAdd());
        mBinding.reset.setOnClickListener(v -> onReset());
        mBinding.keyword.setOnEditorActionListener((textView, actionId, event) -> {
            onAdd();
            return true;
        });
    }

    private void refresh(List<String> items) {
        adapter.setItems(items);
        toggleEmpty(items.isEmpty());
    }

    private void toggleEmpty(boolean empty) {
        mBinding.empty.setVisibility(empty ? View.VISIBLE : View.GONE);
        mBinding.recycler.setVisibility(empty ? View.GONE : View.VISIBLE);
    }

    private void onAdd() {
        String keyword = mBinding.keyword.getText() == null ? "" : mBinding.keyword.getText().toString().trim();
        if (TextUtils.isEmpty(keyword)) {
            Notify.show(R.string.popup_keyword_blank);
            return;
        }
        if (adapter.contains(keyword)) {
            Notify.show(R.string.popup_keyword_exists);
            return;
        }
        mBinding.keyword.setText("");
        adapter.add(keyword);
        save();
    }

    private void onReset() {
        new MaterialAlertDialogBuilder(requireActivity())
                .setTitle(R.string.popup_keyword_reset)
                .setMessage(R.string.popup_keyword_reset_msg)
                .setNegativeButton(R.string.dialog_cancel, null)
                .setPositiveButton(R.string.dialog_confirm, (dialog, which) -> {
                    PopupShield.resetKeywords();
                    refresh(PopupShield.getKeywords());
                })
                .show();
    }

    @Override
    public void onDelete(String item) {
        adapter.remove(item);
        save();
    }

    private void save() {
        PopupShield.saveKeywords(adapter.getItems());
        toggleEmpty(adapter.getItemCount() == 0);
    }
}
