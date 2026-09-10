package com.fongmi.android.tv.ui.dialog;

import android.view.View;

import androidx.fragment.app.FragmentActivity;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.bean.Flag;
import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.databinding.DialogSourceSwitchBinding;
import com.fongmi.android.tv.ui.adapter.SourceSwitchAdapter;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.List;

public class SourceSwitchDialog extends BaseAlertDialog implements SourceSwitchAdapter.OnClickListener {

    private DialogSourceSwitchBinding binding;
    private SourceSwitchAdapter adapter;
    private final List<SourceSwitchAdapter.Item> items = new ArrayList<>();
    private Callback callback;
    private Runnable loadMore;
    private String title = "";

    public interface Callback {

        void switchFlag(Flag flag);

        void switchVod(Vod vod);
    }

    public static SourceSwitchDialog create() {
        return new SourceSwitchDialog();
    }

    public SourceSwitchDialog title(CharSequence title) {
        this.title = title == null ? "" : title.toString();
        return this;
    }

    public SourceSwitchDialog callback(Callback callback) {
        this.callback = callback;
        return this;
    }

    public SourceSwitchDialog loadMore(Runnable loadMore) {
        this.loadMore = loadMore;
        return this;
    }

    public void setItems(List<SourceSwitchAdapter.Item> items) {
        this.items.clear();
        if (items != null) this.items.addAll(items);
        if (binding != null && adapter != null) {
            adapter.setItems(this.items);
            updateEmpty();
        }
    }

    public boolean isActive() {
        return isAdded();
    }

    public void show(FragmentActivity activity) {
        show(activity.getSupportFragmentManager(), SourceSwitchDialog.class.getSimpleName());
    }

    @Override
    protected ViewBinding getBinding() {
        return binding = DialogSourceSwitchBinding.inflate(getLayoutInflater());
    }

    @Override
    protected MaterialAlertDialogBuilder getBuilder() {
        return builder().setView(getBinding().getRoot());
    }

    @Override
    public void onStart() {
        super.onStart();
        setWidth(0.92f);
    }

    @Override
    protected void initView() {
        binding.title.setText(title);
        binding.recycler.setAdapter(adapter = new SourceSwitchAdapter(this));
        adapter.setLoadMore(() -> {
            if (loadMore != null) loadMore.run();
        });
        adapter.setItems(items);
        updateEmpty();
    }

    @Override
    protected void initEvent() {
        binding.back.setOnClickListener(v -> dismiss());
    }

    private void updateEmpty() {
        boolean empty = adapter == null || adapter.isEmpty();
        binding.empty.setVisibility(empty ? View.VISIBLE : View.GONE);
        binding.recycler.setVisibility(empty ? View.GONE : View.VISIBLE);
    }

    @Override
    public void onItemClick(SourceSwitchAdapter.Item item) {
        if (item == SourceSwitchAdapter.FOOTER) return;
        if (callback == null) {
            dismiss();
            return;
        }
        if (item.type == SourceSwitchAdapter.TYPE_FLAG && item.flag != null) callback.switchFlag(item.flag);
        else if (item.vod != null) callback.switchVod(item.vod);
        dismiss();
    }
}
