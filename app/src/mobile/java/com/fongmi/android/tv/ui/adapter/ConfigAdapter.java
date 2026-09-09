package com.fongmi.android.tv.ui.adapter;

import android.graphics.Color;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.Config;
import com.fongmi.android.tv.databinding.AdapterConfigBinding;

import java.util.List;

public class ConfigAdapter extends RecyclerView.Adapter<ConfigAdapter.ViewHolder> {

    private final OnClickListener listener;
    private List<Config> mItems;
    private boolean readOnly;
    private String currentUrl;

    public ConfigAdapter(OnClickListener listener) {
        this.listener = listener;
    }

    public interface OnClickListener {

        void onTextClick(Config item);

        void onDeleteClick(Config item);
    }

    public ConfigAdapter readOnly(boolean readOnly) {
        this.readOnly = readOnly;
        return this;
    }

    public ConfigAdapter addAll(int type) {
        return addAll(type, null);
    }

    public ConfigAdapter addAll(int type, Config current) {
        mItems = Config.getAll(type);
        currentUrl = current == null ? null : current.getUrl();
        if (!readOnly && !TextUtils.isEmpty(currentUrl)) mItems.removeIf(item -> TextUtils.equals(item.getUrl(), currentUrl));
        return this;
    }

    public int remove(Config item) {
        int position = mItems.indexOf(item);
        if (position == -1) return -1;
        item.delete();
        mItems.remove(position);
        notifyItemRemoved(position);
        return getItemCount();
    }

    @Override
    public int getItemCount() {
        return mItems.size();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new ViewHolder(AdapterConfigBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        Config item = mItems.get(position);
        boolean current = TextUtils.equals(item.getUrl(), currentUrl);
        holder.binding.text.setText(item.getDesc());
        holder.binding.text.setOnClickListener(v -> listener.onTextClick(item));
        holder.binding.delete.setVisibility(readOnly ? View.GONE : View.VISIBLE);
        holder.binding.delete.setOnClickListener(v -> listener.onDeleteClick(item));
        holder.binding.current.setVisibility(current ? View.VISIBLE : View.GONE);
        if (current) {
            holder.binding.text.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.parseColor("#E8F0FE")));
            holder.binding.text.setStrokeColor(android.content.res.ColorStateList.valueOf(Color.parseColor("#0B57D0")));
            holder.binding.text.setTextColor(Color.parseColor("#0B57D0"));
        } else {
            holder.binding.text.setBackgroundTintList(holder.itemView.getContext().getColorStateList(R.color.dialog_outlined_button_bg));
            holder.binding.text.setStrokeColor(holder.itemView.getContext().getColorStateList(R.color.dialog_outlined_button_stroke));
            holder.binding.text.setTextColor(holder.itemView.getContext().getColorStateList(R.color.dialog_outlined_button_text));
        }
    }

    public class ViewHolder extends RecyclerView.ViewHolder {

        private final AdapterConfigBinding binding;

        ViewHolder(@NonNull AdapterConfigBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
