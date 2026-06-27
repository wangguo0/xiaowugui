package com.fongmi.android.tv.ui.dialog;

import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.AdapterPlayerButtonConfigBinding;
import com.fongmi.android.tv.databinding.DialogPlayerButtonConfigBinding;
import com.fongmi.android.tv.setting.PlayerButtonSetting;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.utils.Util;
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
        if (Util.isMobile()) attachTouchHelper();
        adapter.reload();
        setSummary();
    }

    private void attachTouchHelper() {
        ItemTouchHelper helper = new ItemTouchHelper(new ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP | ItemTouchHelper.DOWN, 0) {
            @Override
            public boolean isLongPressDragEnabled() {
                return true;
            }

            @Override
            public boolean isItemViewSwipeEnabled() {
                return false;
            }

            @Override
            public boolean onMove(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder source, @NonNull RecyclerView.ViewHolder target) {
                return adapter.drag(source.getBindingAdapterPosition(), target.getBindingAdapterPosition());
            }

            @Override
            public void onSwiped(@NonNull RecyclerView.ViewHolder viewHolder, int direction) {
            }
        });
        helper.attachToRecyclerView(binding.recycler);
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
            holder.binding.up.setOnClickListener(view -> move(item, -1, holder.getBindingAdapterPosition(), view.getId()));
            holder.binding.down.setOnClickListener(view -> move(item, 1, holder.getBindingAdapterPosition(), view.getId()));
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

        private void move(PlayerButtonSetting.Item item, int offset, int position, int focusId) {
            if (position == RecyclerView.NO_POSITION) return;
            int target = Math.max(0, Math.min(items.size() - 1, position + offset));
            if (position == target) return;
            items.remove(position);
            items.add(target, item);
            PlayerButtonSetting.putOrder(getIds());
            notifyItemMoved(position, target);
            notifyItemRangeChanged(Math.min(position, target), Math.abs(position - target) + 1);
            binding.recycler.scrollToPosition(target);
            focus(target, focusId);
            notifyChanged();
        }

        private boolean drag(int fromPosition, int toPosition) {
            if (fromPosition == RecyclerView.NO_POSITION || toPosition == RecyclerView.NO_POSITION) return false;
            if (fromPosition < 0 || toPosition < 0 || fromPosition >= items.size() || toPosition >= items.size()) return false;
            if (fromPosition == toPosition) return false;
            PlayerButtonSetting.Item item = items.remove(fromPosition);
            items.add(toPosition, item);
            PlayerButtonSetting.putOrder(getIds());
            notifyItemMoved(fromPosition, toPosition);
            notifyItemRangeChanged(Math.min(fromPosition, toPosition), Math.abs(fromPosition - toPosition) + 1);
            notifyChanged();
            return true;
        }

        private void focus(int position, int focusId) {
            binding.recycler.post(() -> {
                RecyclerView.ViewHolder viewHolder = binding.recycler.findViewHolderForAdapterPosition(position);
                if (!(viewHolder instanceof ViewHolder holder)) return;
                View view = holder.itemView.findViewById(focusId);
                if (view != null && view.isEnabled()) view.requestFocus();
                else holder.binding.root.requestFocus();
            });
        }

        private List<String> getIds() {
            List<String> ids = new ArrayList<>();
            for (PlayerButtonSetting.Item item : items) ids.add(item.id());
            return ids;
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
