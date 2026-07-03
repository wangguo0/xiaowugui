package com.fongmi.android.tv.ui.dialog;

import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.AdapterPlayerOsdBinding;
import com.fongmi.android.tv.databinding.DialogPlayerOsdBinding;
import com.fongmi.android.tv.utils.ResUtil;

import java.util.Arrays;

public final class PlayerOsdDialog extends DialogFragment {

    private String[] items;
    private boolean[] checked;
    private Callback callback;

    public interface Callback {

        void onApply(boolean[] checked);
    }

    public static void show(FragmentActivity activity, String[] items, boolean[] checked, Callback callback) {
        for (Fragment fragment : activity.getSupportFragmentManager().getFragments()) {
            if (fragment instanceof PlayerOsdDialog) return;
        }
        PlayerOsdDialog dialog = new PlayerOsdDialog();
        dialog.items = items == null ? new String[0] : Arrays.copyOf(items, items.length);
        dialog.checked = checked == null ? new boolean[0] : Arrays.copyOf(checked, checked.length);
        dialog.callback = callback;
        dialog.show(activity.getSupportFragmentManager(), PlayerOsdDialog.class.getSimpleName());
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        DialogPlayerOsdBinding binding = DialogPlayerOsdBinding.inflate(LayoutInflater.from(requireContext()));
        Dialog dialog = new Dialog(requireContext());
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(binding.getRoot());
        dialog.setCanceledOnTouchOutside(false);
        if (items == null) items = new String[0];
        if (checked == null) checked = new boolean[0];
        bindOptions(binding);
        binding.negative.setOnClickListener(view -> dismiss());
        binding.positive.setOnClickListener(view -> {
            Callback apply = callback;
            boolean[] values = Arrays.copyOf(checked, checked.length);
            dismissAllowingStateLoss();
            if (apply != null) apply.onApply(values);
        });
        dialog.setOnShowListener(view -> binding.options.post(() -> focusFirstOption(binding)));
        return dialog;
    }

    @Override
    public void onStart() {
        super.onStart();
        Dialog dialog = getDialog();
        Window window = dialog == null ? null : dialog.getWindow();
        if (window == null) return;
        int screenWidth = ResUtil.getScreenWidth(requireContext());
        int width = Math.max(ResUtil.dp2px(520), Math.min((int) (screenWidth * 0.46f), ResUtil.dp2px(680)));
        WindowManager.LayoutParams params = window.getAttributes();
        params.width = width;
        params.height = WindowManager.LayoutParams.WRAP_CONTENT;
        params.gravity = Gravity.CENTER;
        params.dimAmount = 0.58f;
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        window.setAttributes(params);
        window.setLayout(width, WindowManager.LayoutParams.WRAP_CONTENT);
    }

    private void bindOptions(DialogPlayerOsdBinding binding) {
        int count = Math.min(items.length, checked.length);
        LayoutInflater inflater = LayoutInflater.from(requireContext());
        binding.options.removeAllViews();
        for (int i = 0; i < count; i++) {
            AdapterPlayerOsdBinding item = AdapterPlayerOsdBinding.inflate(inflater, binding.options, false);
            int index = i;
            item.name.setText(items[index]);
            item.getRoot().setOnClickListener(view -> toggle(item, index));
            setChecked(item, checked[index]);
            binding.options.addView(item.getRoot());
        }
    }

    private void toggle(AdapterPlayerOsdBinding binding, int index) {
        checked[index] = !checked[index];
        setChecked(binding, checked[index]);
        binding.getRoot().requestFocus();
    }

    private void setChecked(AdapterPlayerOsdBinding binding, boolean value) {
        binding.getRoot().setActivated(value);
        binding.name.setActivated(value);
        binding.mark.setActivated(value);
        binding.state.setActivated(value);
        binding.state.setText(value ? R.string.setting_on : R.string.setting_off);
    }

    private void focusFirstOption(DialogPlayerOsdBinding binding) {
        View first = binding.options.getChildAt(0);
        if (first != null && first.requestFocus()) return;
        binding.positive.requestFocus();
    }
}
