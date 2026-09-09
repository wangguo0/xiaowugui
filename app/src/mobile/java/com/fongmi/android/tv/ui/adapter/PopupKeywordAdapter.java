package com.fongmi.android.tv.ui.adapter;

import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.databinding.AdapterPopupKeywordBinding;

import java.util.ArrayList;
import java.util.List;

public class PopupKeywordAdapter extends RecyclerView.Adapter<PopupKeywordAdapter.ViewHolder> {

    public interface OnClickListener {

        void onDelete(String item);
    }

    private final OnClickListener listener;
    private final List<String> mItems = new ArrayList<>();

    public PopupKeywordAdapter(OnClickListener listener) {
        this.listener = listener;
    }

    public void setItems(List<String> items) {
        mItems.clear();
        mItems.addAll(items);
        notifyDataSetChanged();
    }

    public boolean contains(String item) {
        return mItems.contains(item);
    }

    public List<String> getItems() {
        return new ArrayList<>(mItems);
    }

    public void add(String item) {
        mItems.add(item);
        notifyItemInserted(mItems.size() - 1);
    }

    public void remove(String item) {
        int position = mItems.indexOf(item);
        if (position == -1) return;
        mItems.remove(position);
        notifyItemRemoved(position);
    }

    @Override
    public int getItemCount() {
        return mItems.size();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new ViewHolder(AdapterPopupKeywordBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        String item = mItems.get(position);
        holder.binding.text.setText(item);
        holder.binding.delete.setOnClickListener(v -> listener.onDelete(item));
    }

    public class ViewHolder extends RecyclerView.ViewHolder {

        private final AdapterPopupKeywordBinding binding;

        ViewHolder(@NonNull AdapterPopupKeywordBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
