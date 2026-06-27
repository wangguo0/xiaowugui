package com.fongmi.android.tv.ui.dialog;

import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.LayoutInflater;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.AdapterPlayerButtonConfigBinding;
import com.fongmi.android.tv.databinding.DialogPlayerButtonConfigBinding;
import com.fongmi.android.tv.setting.PlayerButtonSetting;
import com.fongmi.android.tv.utils.ResUtil;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.List;

public class PlayerButtonConfigDialog extends BaseAlertDialog {

    private DialogPlayerButtonConfigBinding binding;
    private ButtonAdapter adapter;
    private Runnable callback;

    public static void show(Fragment fragment, Runnable callback) {
        PlayerButtonConfigDialog dialog = new PlayerButtonConfigDialog();
        dialog.callback = callback;
        dialog.show(fragment.getChildFragmentManager(), null);
    }

    public static void show(FragmentActivity activity, Runnable callback) {
        PlayerButtonConfigDialog dialog = new PlayerButtonConfigDialog();
        dialog.callback = callback;
        dialog.show(activity.getSupportFragmentManager(), null);
    }

    @Override
    protected ViewBinding getBinding() {
        return binding = DialogPlayerButtonConfigBinding.inflate(getLayoutInflater());
    }

    @Override
    protected MaterialAlertDialogBuilder getBuilder() {
        return new MaterialAlertDialogBuilder(requireActivity(), R.style.ThemeOverlay_WebHTV_LightDialog).setView(getBinding().getRoot());
    }

    @Override
    public void onStart() {
        super.onStart();
        Dialog dialog = getDialog();
        if (dialog == null) return;
        Window window = dialog.getWindow();
        if (window == null) return;
        int screenWidth = ResUtil.getScreenWidth(requireContext());
        int screenHeight = ResUtil.getScreenHeight(requireContext());
        boolean land = ResUtil.isLand(requireContext());
        WindowManager.LayoutParams params = window.getAttributes();
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        window.getDecorView().setPadding(0, 0, 0, 0);
        params.width = (int) (screenWidth * (land ? 0.56f : 0.92f));
        params.height = (int) (screenHeight * (land ? 0.9f : 0.72f));
        window.setAttributes(params);
        window.setLayout(params.width, params.height);
        binding.recycler.post(() -> {
            if (binding.recycler.getChildCount() > 0) binding.recycler.getChildAt(0).requestFocus();
            else binding.recycler.requestFocus();
        });
    }

    @Override
    protected void initView() {
        adapter = new ButtonAdapter();
        binding.recycler.setLayoutManager(new LinearLayoutManager(requireContext()));
        binding.recycler.setItemAnimator(null);
        binding.recycler.setAdapter(adapter);
        adapter.reload();
        setSummary();
    }

    @Override
    protected void initEvent() {
        binding.reset.setOnClickListener(view -> {
            PlayerButtonSetting.reset();
            adapter.reload();
            setSummary();
            notifyChanged();
        });
        binding.close.setOnClickListener(view -> dismiss());
    }

    private void setSummary() {
        binding.summary.setText(getString(R.string.player_button_config_summary, PlayerButtonSetting.getVisibleCount(), PlayerButtonSetting.getTotalCount()));
    }

    private void notifyChanged() {
        if (callback != null) callback.run();
    }

    private class ButtonAdapter extends RecyclerView.Adapter<ButtonAdapter.ViewHolder> {

        private final List<PlayerButtonSetting.Item> items = new ArrayList<>();

        void reload() {
            items.clear();
            items.addAll(PlayerButtonSetting.getItems());
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new ViewHolder(AdapterPlayerButtonConfigBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            PlayerButtonSetting.Item item = items.get(position);
            holder.binding.name.setText(item.name());
            holder.binding.visible.setText(null);
            holder.binding.visible.setIconResource(item.visible() ? R.drawable.ic_player_button_visible : R.drawable.ic_player_button_hidden);
            holder.binding.visible.setContentDescription(getString(item.visible() ? R.string.setting_show : R.string.setting_hide));
            holder.binding.up.setText(null);
            holder.binding.down.setText(null);
            holder.binding.up.setEnabled(position > 0);
            holder.binding.down.setEnabled(position < items.size() - 1);
            holder.binding.root.setAlpha(item.visible() ? 1f : 0.55f);
            holder.binding.root.setOnClickListener(view -> toggle(item));
            holder.binding.visible.setOnClickListener(view -> toggle(item));
            holder.binding.up.setOnClickListener(view -> move(item, -1, holder.getBindingAdapterPosition()));
            holder.binding.down.setOnClickListener(view -> move(item, 1, holder.getBindingAdapterPosition()));
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        private void toggle(PlayerButtonSetting.Item item) {
            PlayerButtonSetting.putVisible(item.id(), !item.visible());
            reload();
            setSummary();
            notifyChanged();
        }

        private void move(PlayerButtonSetting.Item item, int offset, int position) {
            if (position == RecyclerView.NO_POSITION) return;
            PlayerButtonSetting.move(item.id(), offset);
            reload();
            int target = Math.max(0, Math.min(items.size() - 1, position + offset));
            binding.recycler.scrollToPosition(target);
            notifyChanged();
        }

        private class ViewHolder extends RecyclerView.ViewHolder {

            private final AdapterPlayerButtonConfigBinding binding;

            ViewHolder(AdapterPlayerButtonConfigBinding binding) {
                super(binding.getRoot());
                this.binding = binding;
            }
        }
    }
}
