package com.fongmi.android.tv.ui.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.bean.Bangumi;
import com.fongmi.android.tv.databinding.AdapterBangumiBinding;
import com.fongmi.android.tv.utils.ImgUtil;

public class BangumiAdapter extends BaseDiffAdapter<Bangumi, BangumiAdapter.ViewHolder> {

    private final OnClickListener listener;
    private int width;

    public BangumiAdapter(OnClickListener listener) {
        this.listener = listener;
    }

    public interface OnClickListener {

        void onItemClick(Bangumi item);
    }

    public void setSize(int[] size) {
        this.width = size[0];
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ViewHolder holder = new ViewHolder(AdapterBangumiBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
        holder.binding.getRoot().getLayoutParams().width = width;
        return holder;
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        Bangumi item = getItem(position);
        holder.binding.name.setText(item.getName());
        holder.binding.desc.setText(item.getDesc());
        String badge = item.getBadge();
        holder.binding.badge.setVisibility(badge.isEmpty() ? View.GONE : View.VISIBLE);
        holder.binding.badge.setText(badge);
        String mark = item.getMark();
        holder.binding.mark.setVisibility(mark.isEmpty() ? View.GONE : View.VISIBLE);
        holder.binding.mark.setText(mark);
        ImgUtil.load(item.getName(), item.getPic(), holder.binding.image);
        holder.binding.getRoot().setOnClickListener(view -> listener.onItemClick(item));
    }

    public class ViewHolder extends RecyclerView.ViewHolder {

        private final AdapterBangumiBinding binding;

        ViewHolder(@NonNull AdapterBangumiBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
