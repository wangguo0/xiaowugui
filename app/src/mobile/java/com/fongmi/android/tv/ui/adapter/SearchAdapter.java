package com.fongmi.android.tv.ui.adapter;

import android.graphics.Color;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.databinding.AdapterSearchBinding;
import com.fongmi.android.tv.databinding.AdapterSearchFooterBinding;
import com.fongmi.android.tv.databinding.AdapterVodRectBinding;
import com.fongmi.android.tv.utils.ImgUtil;

public class SearchAdapter extends BaseDiffAdapter<Vod, RecyclerView.ViewHolder> {

    private static final int VIEW_TYPE_LIST = 0;
    private static final int VIEW_TYPE_GRID = 1;
    private static final int VIEW_TYPE_FOOTER = 2;
    private static final int VIEW_TYPE_END = 3;

    // 「加载更多」哨兵对象：作为列表最后一项参与 diff，视图类型为 FOOTER
    public static final Vod FOOTER = new Vod();

    // 「到底了」哨兵对象：无更多结果时作为列表最后一项参与 diff，视图类型为 END，不可点击
    public static final Vod END = new Vod();

    static {
        FOOTER.setId("search_load_more_footer");
        END.setId("search_end_footer");
    }

    private final OnClickListener listener;
    private boolean grid;
    private Runnable loadMore;
    private int[] size = new int[]{0, 0};

    public SearchAdapter(OnClickListener listener) {
        this.listener = listener;
    }

    public interface OnClickListener {

        void onItemClick(Vod item);
    }

    public void setGrid(boolean grid, int[] size) {
        this.grid = grid;
        this.size = size;
        notifyDataSetChanged();
    }

    public void setLoadMore(Runnable loadMore) {
        this.loadMore = loadMore;
    }

    // 底部栏（FOOTER / END）统一识别，供网格模式占满整行使用
    public boolean isFooter(int position) {
        if (position < 0 || position >= getItemCount()) return false;
        Vod item = getItem(position);
        return item == FOOTER || item == END;
    }

    public boolean isEnd(int position) {
        return position >= 0 && position < getItemCount() && getItem(position) == END;
    }

    @Override
    public int getItemViewType(int position) {
        if (isEnd(position)) return VIEW_TYPE_END;
        if (isFooter(position)) return VIEW_TYPE_FOOTER;
        return grid ? VIEW_TYPE_GRID : VIEW_TYPE_LIST;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        if (viewType == VIEW_TYPE_FOOTER || viewType == VIEW_TYPE_END) return new FooterHolder(AdapterSearchFooterBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
        return viewType == VIEW_TYPE_GRID ? new GridHolder(AdapterVodRectBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false)) : new ListHolder(AdapterSearchBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        if (holder instanceof FooterHolder footerHolder) {
            var root = footerHolder.binding.getRoot();
            if (isEnd(position)) {
                root.setText(R.string.search_load_end);
                root.setTextColor(ContextCompat.getColor(root.getContext(), android.R.color.darker_gray));
                root.setClickable(false);
                root.setOnClickListener(null);
            } else {
                root.setText(R.string.search_load_more);
                root.setTextColor(Color.WHITE);
                root.setClickable(true);
                root.setOnClickListener(v -> {
                    if (loadMore != null) loadMore.run();
                });
            }
            return;
        }
        Vod item = getItem(position);
        if (holder instanceof GridHolder gridHolder) {
            gridHolder.initView(item);
            return;
        }
        if (!(holder instanceof ListHolder listHolder)) return;
        listHolder.initView(item);
    }

    @Override
    public void onViewRecycled(@NonNull RecyclerView.ViewHolder holder) {
        if (holder instanceof GridHolder gridHolder) {
            Glide.with(gridHolder.binding.image).clear(gridHolder.binding.image);
            gridHolder.setMarquee(false);
        }
        if (holder instanceof ListHolder listHolder) {
            Glide.with(listHolder.binding.image).clear(listHolder.binding.image);
            listHolder.setMarquee(false);
        }
    }

    public static class FooterHolder extends RecyclerView.ViewHolder {

        private final AdapterSearchFooterBinding binding;

        FooterHolder(@NonNull AdapterSearchFooterBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }

    public class ListHolder extends RecyclerView.ViewHolder {

        private final AdapterSearchBinding binding;

        ListHolder(@NonNull AdapterSearchBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
            binding.getRoot().setFocusable(true);
            binding.name.setMarqueeRepeatLimit(-1);
            binding.getRoot().setOnFocusChangeListener((view, hasFocus) -> setMarquee(hasFocus));
        }

        private void initView(Vod item) {
            binding.name.setText(item.getName());
            setMarquee(binding.getRoot().hasFocus());
            binding.site.setText(item.getSiteName());
            binding.remark.setText(item.getRemarks());
            binding.site.setVisibility(item.getSiteVisible());
            binding.remark.setVisibility(item.getRemarkVisible());
            binding.getRoot().setOnClickListener(v -> listener.onItemClick(item));
            ImgUtil.load(item.getName(), item.getPic(), binding.image);
        }

        private void setMarquee(boolean focused) {
            binding.name.setSingleLine(focused);
            if (!focused) binding.name.setMaxLines(3);
            binding.name.setHorizontallyScrolling(focused);
            binding.name.setEllipsize(focused ? TextUtils.TruncateAt.MARQUEE : TextUtils.TruncateAt.END);
            binding.name.setSelected(focused);
        }
    }

    public class GridHolder extends RecyclerView.ViewHolder {

        private final AdapterVodRectBinding binding;

        GridHolder(@NonNull AdapterVodRectBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
            binding.getRoot().setFocusable(true);
            binding.name.setSingleLine(true);
            binding.name.setHorizontallyScrolling(true);
            binding.name.setMarqueeRepeatLimit(-1);
            binding.getRoot().setOnFocusChangeListener((view, hasFocus) -> setMarquee(hasFocus));
            applySize();
        }

        private void initView(Vod item) {
            applySize();
            binding.name.setText(item.getName());
            setMarquee(binding.getRoot().hasFocus());
            binding.site.setText(item.getSiteName());
            binding.remark.setText(item.getRemarks());
            binding.year.setVisibility(android.view.View.GONE);
            binding.site.setVisibility(item.getSiteVisible());
            binding.name.setVisibility(item.getNameVisible());
            binding.remark.setVisibility(item.getRemarkVisible());
            binding.getRoot().setOnClickListener(v -> listener.onItemClick(item));
            ImgUtil.load(item.getName(), item.getPic(), binding.image);
        }

        private void applySize() {
            ViewGroup.LayoutParams imageParams = binding.image.getLayoutParams();
            imageParams.height = size[1];
            binding.image.setLayoutParams(imageParams);
            ViewGroup.LayoutParams rootParams = binding.getRoot().getLayoutParams();
            rootParams.width = size[0];
            if (rootParams instanceof ViewGroup.MarginLayoutParams params) {
                int margin = size.length > 2 ? size[2] : 0;
                params.setMargins(margin, margin, margin, margin);
            }
            binding.getRoot().setLayoutParams(rootParams);
        }

        private void setMarquee(boolean focused) {
            binding.name.setEllipsize(focused ? TextUtils.TruncateAt.MARQUEE : TextUtils.TruncateAt.END);
            binding.name.setSelected(focused);
        }
    }
}
