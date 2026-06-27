package com.fongmi.android.tv.ui.dialog;

import android.content.DialogInterface;

import androidx.fragment.app.FragmentActivity;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.databinding.DialogQuickSearchBinding;
import com.fongmi.android.tv.ui.adapter.QuickAdapter;
import com.fongmi.android.tv.ui.custom.SpaceItemDecoration;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.List;

public class QuickSearchDialog extends BaseAlertDialog implements QuickAdapter.OnClickListener {

    private final List<Vod> pending;
    private DialogQuickSearchBinding binding;
    private QuickAdapter.OnClickListener listener;
    private DialogInterface.OnDismissListener dismissListener;
    private QuickAdapter adapter;

    public QuickSearchDialog() {
        pending = new ArrayList<>();
    }

    public static QuickSearchDialog create() {
        return new QuickSearchDialog();
    }

    public QuickSearchDialog listener(QuickAdapter.OnClickListener listener) {
        this.listener = listener;
        return this;
    }

    public QuickSearchDialog dismissListener(DialogInterface.OnDismissListener dismissListener) {
        this.dismissListener = dismissListener;
        return this;
    }

    public QuickSearchDialog items(List<Vod> items) {
        pending.addAll(items);
        return this;
    }

    public void addAll(List<Vod> items) {
        if (adapter == null) pending.addAll(items);
        else adapter.addAll(items);
    }

    public void show(FragmentActivity activity) {
        show(activity.getSupportFragmentManager(), null);
    }

    @Override
    protected ViewBinding getBinding() {
        return binding = DialogQuickSearchBinding.inflate(getLayoutInflater());
    }

    @Override
    protected MaterialAlertDialogBuilder getBuilder() {
        return builder().setTitle(R.string.play_search).setView(getBinding().getRoot());
    }

    @Override
    protected void initView() {
        binding.recycler.setHasFixedSize(true);
        binding.recycler.setItemAnimator(null);
        binding.recycler.addItemDecoration(new SpaceItemDecoration(2, 8));
        binding.recycler.setAdapter(adapter = new QuickAdapter(this));
        adapter.setNextFocus(0, 0);
        if (!pending.isEmpty()) {
            adapter.addAll(pending);
            pending.clear();
        }
        binding.recycler.post(() -> focusPosition(0));
        binding.recycler.postDelayed(() -> focusPosition(0), 160);
    }

    private void focusPosition(int position) {
        if (adapter == null || adapter.getItemCount() <= position) return;
        binding.recycler.scrollToPosition(position);
        RecyclerView.ViewHolder holder = binding.recycler.findViewHolderForAdapterPosition(position);
        if (holder != null) holder.itemView.requestFocus();
        else binding.recycler.requestFocus();
    }

    @Override
    public void onItemClick(Vod item) {
        dismiss();
        if (listener != null) listener.onItemClick(item);
    }

    @Override
    public void onDismiss(DialogInterface dialog) {
        super.onDismiss(dialog);
        if (dismissListener != null) dismissListener.onDismiss(dialog);
    }

    @Override
    public void onStart() {
        super.onStart();
        setWidth(0.72f);
    }
}
