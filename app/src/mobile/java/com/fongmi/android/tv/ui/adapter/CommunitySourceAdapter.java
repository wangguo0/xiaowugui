package com.fongmi.android.tv.ui.adapter;

import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.CommunitySource;
import com.fongmi.android.tv.databinding.ItemCommunitySourceBinding;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 社区提取结果列表：勾选 + 名称 + 站点数/URL + 连通状态。
 */
public class CommunitySourceAdapter extends RecyclerView.Adapter<CommunitySourceAdapter.ViewHolder> {

    private final List<CommunitySource> mItems = new ArrayList<>();
    private final Set<String> mSelected = new HashSet<>();
    private Runnable onSelectionChanged;

    public void setOnSelectionChanged(Runnable callback) {
        this.onSelectionChanged = callback;
    }

    private void notifySelection() {
        if (onSelectionChanged != null) onSelectionChanged.run();
    }

    public void setItems(List<CommunitySource> items) {
        mItems.clear();
        if (items != null) mItems.addAll(items);
        notifyDataSetChanged();
        notifySelection();
    }

    public List<CommunitySource> getItems() {
        return mItems;
    }

    public Set<String> getSelected() {
        return mSelected;
    }

    public int selectedCount() {
        return mSelected.size();
    }

    public boolean selectAll() {
        for (CommunitySource item : mItems) mSelected.add(item.getUrl());
        notifyDataSetChanged();
        notifySelection();
        return true;
    }

    public boolean deselectAll() {
        for (CommunitySource item : mItems) mSelected.remove(item.getUrl());
        notifyDataSetChanged();
        notifySelection();
        return true;
    }

    @Override
    public int getItemCount() {
        return mItems.size();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new ViewHolder(ItemCommunitySourceBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        CommunitySource item = mItems.get(position);
        String url = item.getUrl();
        android.content.Context context = holder.itemView.getContext();
        // 每项一行：标题（灰色小字）+ 数据。接口地址插入零宽空格保证长 URL 可折行完整显示
        holder.binding.name.setText(labeled(context, R.string.community_label_name, item.getName(), true));
        int countRes = item.getType() == 0 ? R.string.community_site_count : R.string.community_channel_count;
        int labelRes = item.getType() == 0 ? R.string.community_label_sites : R.string.community_label_channels;
        holder.binding.count.setText(labeled(context, labelRes, context.getString(countRes, item.getCount()), false));
        holder.binding.url.setText(labeled(context, R.string.community_label_url, url.replace("/", "/\u200B"), false));
        holder.binding.badge.setVisibility(item.isRecommended() ? android.view.View.VISIBLE : android.view.View.GONE);
        holder.binding.check.setChecked(mSelected.contains(url));
        // README 文字说明：无匹配则隐藏
        if (TextUtils.isEmpty(item.getDesc())) {
            holder.binding.desc.setVisibility(android.view.View.GONE);
        } else {
            holder.binding.desc.setVisibility(android.view.View.VISIBLE);
            holder.binding.desc.setText(labeled(context, R.string.community_label_desc, item.getDesc(), false));
        }
        holder.binding.status.setText("✓");
        holder.binding.status.setTextColor(context.getColor(R.color.community_ok));
        holder.itemView.setOnClickListener(v -> {
            int pos = holder.getBindingAdapterPosition();
            if (pos == RecyclerView.NO_POSITION) return;
            String target = mItems.get(pos).getUrl();
            if (TextUtils.isEmpty(target)) return;
            if (mSelected.contains(target)) mSelected.remove(target);
            else mSelected.add(target);
            notifyItemChanged(pos);
            notifySelection();
        });
    }

    // 「标题：数据」单行文本：标题灰色，数据可选加粗
    private CharSequence labeled(android.content.Context context, int labelRes, String value, boolean bold) {
        String label = context.getString(labelRes);
        android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder(label).append(value == null ? "" : value);
        sb.setSpan(new android.text.style.ForegroundColorSpan(0xFF5F6368), 0, label.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        if (bold) sb.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD), label.length(), sb.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return sb;
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {

        private final ItemCommunitySourceBinding binding;

        ViewHolder(@NonNull ItemCommunitySourceBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
