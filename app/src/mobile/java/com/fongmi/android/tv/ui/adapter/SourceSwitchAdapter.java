package com.fongmi.android.tv.ui.adapter;

import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.Flag;
import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.databinding.AdapterSourceSwitchBinding;

import java.util.ArrayList;
import java.util.List;

public class SourceSwitchAdapter extends RecyclerView.Adapter<SourceSwitchAdapter.ViewHolder> {

    public static final int TYPE_FLAG = 1;
    public static final int TYPE_VOD = 2;

    private final OnClickListener listener;
    private final List<Item> mItems;

    public SourceSwitchAdapter(OnClickListener listener) {
        this.listener = listener;
        this.mItems = new ArrayList<>();
    }

    public interface OnClickListener {

        void onItemClick(Item item);
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
    public int getItemCount() {
        return mItems.size();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new ViewHolder(AdapterSourceSwitchBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        Item item = mItems.get(position);
        String text = item.line.isEmpty() ? item.site : item.site + " | " + item.line;
        holder.binding.text.setText(text);
        holder.binding.text.setSelected(item.selected);
        holder.binding.icon.setSelected(item.selected);
        holder.binding.icon.setImageTintList(ContextCompat.getColorStateList(holder.binding.getRoot().getContext(), R.color.selector_video_text));
        holder.binding.check.setVisibility(item.selected ? android.view.View.VISIBLE : android.view.View.GONE);
        holder.binding.getRoot().setOnClickListener(v -> listener.onItemClick(item));
    }

    public class ViewHolder extends RecyclerView.ViewHolder {

        private final AdapterSourceSwitchBinding binding;

        ViewHolder(@NonNull AdapterSourceSwitchBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}