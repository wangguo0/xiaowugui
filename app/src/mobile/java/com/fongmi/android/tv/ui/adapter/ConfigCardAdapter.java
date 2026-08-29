package com.fongmi.android.tv.ui.adapter;

import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.appcompat.widget.PopupMenu;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.Config;
import com.fongmi.android.tv.databinding.AdapterConfigCardBinding;

import java.util.ArrayList;
import java.util.List;

public class ConfigCardAdapter extends RecyclerView.Adapter<ConfigCardAdapter.ViewHolder> {

    private final OnClickListener listener;
    private final boolean isVod;
    private List<Config> mItems;
    private String currentUrl;

    public ConfigCardAdapter(boolean isVod, OnClickListener listener) {
        this.isVod = isVod;
        this.listener = listener;
        this.mItems = new ArrayList<>();
    }

    public interface OnClickListener {
        void onSelect(Config item);

        void onDeactivate(Config item);

        void onPin(Config item);

        void onEdit(Config item);

        void onCopy(Config item);

        void onShare(Config item);

        void onDelete(Config item);

        void onSwitchDepot(Config item);
    }

    public void setItems(List<Config> items, String currentUrl) {
        mItems = items == null ? new ArrayList<>() : new ArrayList<>(items);
        this.currentUrl = currentUrl;
        notifyDataSetChanged();
    }

    public int remove(Config item) {
        int position = mItems.indexOf(item);
        if (position == -1) return -1;
        item.delete();
        mItems.remove(position);
        notifyItemRemoved(position);
        return getItemCount();
    }

    public void refresh() {
        notifyDataSetChanged();
    }

    @Override
    public int getItemCount() {
        return mItems.size();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new ViewHolder(AdapterConfigCardBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        Config item = mItems.get(position);
        boolean active = TextUtils.equals(item.getUrl(), currentUrl);
        holder.binding.name.setText(TextUtils.isEmpty(item.getName()) ? item.getUrl() : item.getName());
        holder.binding.url.setText(item.getUrl());
        holder.binding.activeSwitch.setOnCheckedChangeListener(null);
        holder.binding.activeSwitch.setChecked(active);
        if (isVod && item.isDepot()) {
            holder.binding.badgeRow.setVisibility(View.VISIBLE);
            if (TextUtils.isEmpty(item.getDepotActiveName())) holder.binding.usedSource.setVisibility(View.INVISIBLE);
            else {
                holder.binding.usedSource.setVisibility(View.VISIBLE);
                holder.binding.usedSource.setClickable(true);
                holder.binding.usedSource.setFocusable(true);
                holder.binding.usedSource.setText(holder.binding.usedSource.getResources().getString(R.string.setting_subscription_used_source, item.getDepotActiveName()));
                holder.binding.usedSource.setOnClickListener(v -> listener.onSwitchDepot(item));
            }
        } else {
            holder.binding.badgeRow.setVisibility(View.GONE);
        }
        holder.binding.activeSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked && !active) listener.onSelect(item);
            else if (!isChecked && active) listener.onDeactivate(item);
        });
        holder.binding.menu.setOnClickListener(v -> showMenu(holder, item));
    }

    private void showMenu(ViewHolder holder, Config item) {
        PopupMenu menu = new PopupMenu(holder.itemView.getContext(), holder.binding.menu);
        menu.inflate(R.menu.menu_config_card);
        menu.setOnMenuItemClickListener(menuItem -> {
            int id = menuItem.getItemId();
            if (id == R.id.pin) listener.onPin(item);
            else if (id == R.id.edit) listener.onEdit(item);
            else if (id == R.id.copy) listener.onCopy(item);
            else if (id == R.id.share) listener.onShare(item);
            else if (id == R.id.delete) listener.onDelete(item);
            return true;
        });
        menu.show();
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {

        private final AdapterConfigCardBinding binding;

        ViewHolder(@NonNull AdapterConfigCardBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}