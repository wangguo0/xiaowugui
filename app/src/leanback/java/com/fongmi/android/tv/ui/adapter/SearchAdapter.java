package com.fongmi.android.tv.ui.adapter;

import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.RequestBuilder;
import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.databinding.AdapterSearchBinding;
import com.fongmi.android.tv.databinding.AdapterSearchFooterBinding;
import com.fongmi.android.tv.utils.ImgUtil;
import com.fongmi.android.tv.utils.ResUtil;

import java.util.ArrayList;
import java.util.List;

public class SearchAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private static final int VIEW_TYPE_ITEM = 0;
    private static final int VIEW_TYPE_FOOTER = 1;
    private static final int VIEW_TYPE_END = 2;

    // 「加载更多」哨兵对象：作为列表最后一项，视图类型为 FOOTER，可聚焦、确认后触发加载
    public static final Vod FOOTER = new Vod();

    // 「到底了」哨兵对象：无更多结果时作为列表最后一项，视图类型为 END，不可交互
    public static final Vod END = new Vod();

    static {
        FOOTER.setId("search_load_more_footer");
        END.setId("search_end_footer");
    }

    private final OnClickListener listener;
    private final List<Vod> items;
    private final List<Vod> source;
    private final int height;
    private final int width;
    private Runnable loadMore;

    public SearchAdapter(OnClickListener listener, int width, int height) {
        this.listener = listener;
        this.items = new ArrayList<>();
        this.source = new ArrayList<>();
        this.width = width;
        this.height = height;
    }

    public interface OnClickListener {

        void onItemClick(Vod item);

        boolean onItemKey(int position, int keyCode, KeyEvent event);
    }

    public void addAll(List<Vod> items) {
        int start = this.items.size();
        this.items.addAll(items);
        notifyItemRangeInserted(start, items.size());
    }

    public void setLoadMore(Runnable loadMore) {
        this.loadMore = loadMore;
    }

    // 底部栏（FOOTER / END）统一识别，供网格模式占满整行
    public boolean isFooter(int position) {
        if (position < 0 || position >= items.size()) return false;
        return items.get(position) == FOOTER || items.get(position) == END;
    }

    public boolean isEnd(int position) {
        return position >= 0 && position < items.size() && items.get(position) == END;
    }

    @Override
    public int getItemViewType(int position) {
        if (isEnd(position)) return VIEW_TYPE_END;
        if (isFooter(position)) return VIEW_TYPE_FOOTER;
        return VIEW_TYPE_ITEM;
    }

    public void setItems(List<Vod> items, Runnable runnable) {
        this.items.clear();
        this.items.addAll(items);
        notifyDataSetChanged();
        if (runnable != null) runnable.run();
    }

    public void replaceFirst(List<Vod> items) {
        this.items.clear();
        this.items.addAll(items);
        notifyDataSetChanged();
    }

    public void setSource(List<Vod> items, int visibleCount) {
        source.clear();
        source.addAll(items);
        replaceFirst(new ArrayList<>(source.subList(0, Math.min(source.size(), visibleCount))));
    }

    public boolean ensureLoaded(int position, int preloadCount) {
        if (source.isEmpty()) return false;
        int target = Math.min(source.size(), Math.max(items.size(), position + preloadCount));
        if (target <= items.size()) return false;
        int start = items.size();
        items.addAll(source.subList(start, target));
        notifyItemRangeInserted(start, target - start);
        return true;
    }

    public void appendSource(List<Vod> items, int minVisibleCount) {
        source.addAll(items);
        int target = Math.min(source.size(), Math.max(this.items.size(), minVisibleCount));
        if (target <= this.items.size()) return;
        int start = this.items.size();
        this.items.addAll(source.subList(start, target));
        notifyItemRangeInserted(start, target - start);
    }

    public void clear() {
        this.items.clear();
        this.source.clear();
        loadMore = null;
        notifyDataSetChanged();
    }

    public RequestBuilder<?> getPreloadRequest(int position) {
        if (position < 0 || position >= items.size()) return null;
        Vod item = items.get(position);
        return Glide.with(App.get()).load(ImgUtil.getUrl(item.getPic())).override(width, height).centerCrop();
    }

    public void preload(int start, int count) {
        int end = Math.min(items.size(), start + count);
        for (int i = Math.max(0, start); i < end; i++) {
            RequestBuilder<?> request = getPreloadRequest(i);
            if (request != null) request.preload(width, height);
        }
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        if (viewType == VIEW_TYPE_FOOTER || viewType == VIEW_TYPE_END) {
            return new FooterHolder(AdapterSearchFooterBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
        }
        ViewHolder holder = new ViewHolder(AdapterSearchBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
        holder.binding.getRoot().getLayoutParams().width = width;
        holder.binding.getRoot().getLayoutParams().height = height + ResUtil.dp2px(34);
        holder.binding.image.getLayoutParams().height = height;
        return holder;
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        if (holder instanceof FooterHolder footerHolder) {
            if (isEnd(position)) {
                footerHolder.binding.loadMore.setText(R.string.search_load_end);
                footerHolder.binding.loadMore.setEnabled(false);
                footerHolder.binding.loadMore.setClickable(false);
                footerHolder.binding.loadMore.setOnClickListener(null);
                footerHolder.binding.loadMore.setFocusable(false);
            } else {
                footerHolder.binding.loadMore.setText(R.string.search_load_more);
                footerHolder.binding.loadMore.setEnabled(true);
                footerHolder.binding.loadMore.setClickable(true);
                footerHolder.binding.loadMore.setFocusable(true);
                footerHolder.binding.loadMore.setOnClickListener(v -> {
                    if (loadMore != null) loadMore.run();
                });
            }
            return;
        }
        if (holder instanceof ViewHolder viewHolder) {
            Vod item = items.get(position);
            viewHolder.bindName(item.getName());
            viewHolder.binding.site.setText(item.getSiteName());
            viewHolder.binding.remark.setText(item.getRemarks());
            viewHolder.binding.site.setVisibility(item.getSiteVisible());
            viewHolder.binding.remark.setVisibility(item.getRemarkVisible());
            viewHolder.binding.getRoot().setOnClickListener(v -> listener.onItemClick(item));
            viewHolder.binding.getRoot().setOnKeyListener((v, keyCode, event) -> listener.onItemKey(holder.getBindingAdapterPosition(), keyCode, event));
            ImgUtil.load(item.getName(), item.getPic(), viewHolder.binding.image, width, height);
        }
    }

    @Override
    public void onViewRecycled(@NonNull RecyclerView.ViewHolder holder) {
        if (holder instanceof ViewHolder vh) {
            Glide.with(vh.binding.image).clear(vh.binding.image);
            vh.setMarquee(false);
        }
    }

    public static class FooterHolder extends RecyclerView.ViewHolder {

        private final AdapterSearchFooterBinding binding;

        FooterHolder(@NonNull AdapterSearchFooterBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {

        private final AdapterSearchBinding binding;

        ViewHolder(@NonNull AdapterSearchBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
            binding.name.setSingleLine(true);
            binding.name.setHorizontallyScrolling(true);
            binding.name.setMarqueeRepeatLimit(-1);
            binding.getRoot().setOnFocusChangeListener((view, hasFocus) -> setMarquee(hasFocus));
        }

        private void bindName(String name) {
            binding.name.setText(name);
            setMarquee(binding.getRoot().hasFocus());
        }

        private void setMarquee(boolean focused) {
            binding.name.setEllipsize(focused ? TextUtils.TruncateAt.MARQUEE : TextUtils.TruncateAt.END);
            binding.name.setSelected(focused);
        }
    }
}