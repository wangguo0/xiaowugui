package com.fongmi.android.tv.ui.adapter;

import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.Flag;
import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.databinding.AdapterSearchFooterBinding;
import com.fongmi.android.tv.databinding.AdapterSourceSwitchBinding;

import java.util.ArrayList;
import java.util.List;

public class SourceSwitchAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    public static final int TYPE_FLAG = 1;
    public static final int TYPE_VOD = 2;

    private static final int VIEW_TYPE_ITEM = 0;
    private static final int VIEW_TYPE_FOOTER = 1;

    // 「加载更多」哨兵对象：作为分页列表最后一项，点击追加下一页（与搜索结果页一致）
    public static final Item FOOTER = new Item(-1, "load_more_footer", "", false, null, null);

    private final OnClickListener listener;
    private final List<Item> mItems;
    private Runnable loadMore;

    public SourceSwitchAdapter(OnClickListener listener) {
        this.listener = listener;
        this.mItems = new ArrayList<>();
    }

    public interface OnClickListener {

        void onItemClick(Item item);
    }

    public void setLoadMore(Runnable loadMore) {
        this.loadMore = loadMore;
    }

    public boolean isFooter(int position) {
        return position >= 0 && position < mItems.size() && mItems.get(position) == FOOTER;
    }

    public static class Item {

        public final int type;
        public final String site;
        public final String line;
        public final boolean selected;
        public final Flag flag;
        public final Vod vod;

        public static Item flag(String site, Flag flag) {
            return new Item(TYPE_FLAG, site, flag.getShow(), flag.isSelected(), flag, null);
        }

        public static Item vod(String site, Vod vod) {
            return new Item(TYPE_VOD, site, vod.getRemarks(), false, null, vod);
        }

        private Item(int type, String site, String line, boolean selected, Flag flag, Vod vod) {
            this.type = type;
            this.site = site;
            this.line = line;
            this.selected = selected;
            this.flag = flag;
            this.vod = vod;
        }
    }

    public void setItems(List<Item> items) {
        mItems.clear();
        mItems.addAll(items);
        notifyDataSetChanged();
    }

    public boolean isEmpty() {
        return getItemCount() == 0;
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
        return new ViewHolder(AdapterSourceSwitchBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        if (holder instanceof FooterHolder footerHolder) {
            footerHolder.binding.getRoot().setOnClickListener(v -> {
                if (loadMore != null) loadMore.run();
            });
            return;
        }
        Item item = mItems.get(position);
        ViewHolder viewHolder = (ViewHolder) holder;
        String text = item.line.isEmpty() ? item.site : item.site + " | " + item.line;
        viewHolder.binding.text.setText(text);
        viewHolder.binding.text.setSelected(item.selected);
        viewHolder.binding.icon.setSelected(item.selected);
        viewHolder.binding.icon.setImageTintList(ContextCompat.getColorStateList(viewHolder.binding.getRoot().getContext(), R.color.selector_video_text));
        viewHolder.binding.check.setVisibility(item.selected ? android.view.View.VISIBLE : android.view.View.GONE);
        viewHolder.binding.getRoot().setOnClickListener(v -> listener.onItemClick(item));
    }

    public static class FooterHolder extends RecyclerView.ViewHolder {

        private final AdapterSearchFooterBinding binding;

        FooterHolder(@NonNull AdapterSearchFooterBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }

    public class ViewHolder extends RecyclerView.ViewHolder {

        private final AdapterSourceSwitchBinding binding;

        ViewHolder(@NonNull AdapterSourceSwitchBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}