package com.fongmi.android.tv.ui.adapter;

import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.databinding.AdapterQuickBinding;
import com.fongmi.android.tv.databinding.AdapterSearchFooterBinding;
import com.fongmi.android.tv.setting.SiteHealthStore;
import com.fongmi.android.tv.utils.ImgUtil;

import java.util.ArrayList;
import java.util.List;

public class QuickAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private static final int VIEW_TYPE_ITEM = 0;
    private static final int VIEW_TYPE_FOOTER = 1;

    // 「加载更多」哨兵对象：作为分页列表最后一项，点击追加下一页（与搜索结果页一致）
    public static final Vod FOOTER = new Vod();

    static {
        FOOTER.setId("quick_load_more_footer");
    }

    private final OnClickListener listener;
    private final List<Vod> mItems;
    private Runnable loadMore;

    public QuickAdapter(OnClickListener listener) {
        this.listener = listener;
        this.mItems = new ArrayList<>();
    }

    public interface OnClickListener {

        void onItemClick(Vod item);
    }

    public void setLoadMore(Runnable loadMore) {
        this.loadMore = loadMore;
    }

    public void clear() {
        mItems.clear();
        notifyDataSetChanged();
    }

    public void setItems(List<Vod> items) {
        mItems.clear();
        mItems.addAll(items);
        notifyDataSetChanged();
    }

    public void addAll(List<Vod> items) {
        int start = mItems.size();
        mItems.addAll(items);
        notifyItemRangeInserted(start, items.size());
    }

    public Vod get(int position) {
        return mItems.get(position);
    }

    public List<Vod> getItems() {
        return new ArrayList<>(mItems);
    }

    public int getBestPosition() {
        int position = 0;
        for (int i = 1; i < mItems.size(); i++) {
            if (SiteHealthStore.compareVods(mItems.get(i), mItems.get(position)) < 0) position = i;
        }
        return position;
    }

    public void remove(int position) {
        mItems.remove(position);
        notifyItemRemoved(position);
    }

    public boolean isEmpty() {
        return getItemCount() == 0;
    }

    public boolean isFooter(int position) {
        return position >= 0 && position < mItems.size() && mItems.get(position) == FOOTER;
    }

    @Override
    public int getItemViewType(int position) {
        return isFooter(position) ? VIEW_TYPE_FOOTER : VIEW_TYPE_ITEM;
    }

    @Override
    public int getItemCount() {
        return mItems.size();
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        if (viewType == VIEW_TYPE_FOOTER) return new FooterHolder(AdapterSearchFooterBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
        return new ViewHolder(AdapterQuickBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        if (holder instanceof FooterHolder footerHolder) {
            footerHolder.binding.getRoot().setOnClickListener(v -> {
                if (loadMore != null) loadMore.run();
            });
            return;
        }
        Vod item = mItems.get(position);
        ViewHolder viewHolder = (ViewHolder) holder;
        viewHolder.binding.name.setText(item.getName());
        viewHolder.binding.site.setText(item.getSiteName());
        viewHolder.binding.remark.setText(item.getRemarks());
        viewHolder.binding.site.setVisibility(item.getSiteVisible());
        viewHolder.binding.remark.setVisibility(item.getRemarkVisible());
        viewHolder.binding.getRoot().setOnClickListener(v -> listener.onItemClick(item));
        ImgUtil.load(item.getName(), item.getPic(), viewHolder.binding.image);
    }

    @Override
    public void onViewRecycled(@NonNull RecyclerView.ViewHolder holder) {
        if (!(holder instanceof ViewHolder viewHolder)) return;
        Glide.with(viewHolder.binding.image).clear(viewHolder.binding.image);
    }

    public static class FooterHolder extends RecyclerView.ViewHolder {

        private final AdapterSearchFooterBinding binding;

        FooterHolder(@NonNull AdapterSearchFooterBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }

    public class ViewHolder extends RecyclerView.ViewHolder {

        private final AdapterQuickBinding binding;

        ViewHolder(@NonNull AdapterQuickBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
