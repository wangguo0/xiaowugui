package com.fongmi.android.tv.ui.dialog;

import android.app.Dialog;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.player.PlayerManager;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.utils.Util;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

public final class DanmakuSearchInputDialog extends DialogFragment {

    private TextInputEditText input;
    private PlayerManager player;
    private boolean submitted;
    private boolean restoreParent;

    public static DanmakuSearchInputDialog create() {
        return new DanmakuSearchInputDialog();
    }

    public DanmakuSearchInputDialog player(PlayerManager player) {
        this.player = player;
        return this;
    }

    public DanmakuSearchInputDialog restoreParent(boolean restoreParent) {
        this.restoreParent = restoreParent;
        return this;
    }

    public void show(FragmentActivity activity) {
        for (Fragment f : activity.getSupportFragmentManager().getFragments()) if (f instanceof DanmakuSearchInputDialog) return;
        show(activity.getSupportFragmentManager(), null);
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable android.os.Bundle savedInstanceState) {
        input = createInput();
        AlertDialog dialog = new MaterialAlertDialogBuilder(requireActivity(), R.style.ThemeOverlay_WebHTV_LightDialog)
                .setTitle(R.string.play_search)
                .setView(createInputView())
                .setNegativeButton(R.string.dialog_negative, null)
                .setPositiveButton(R.string.play_search, null)
                .create();
        dialog.setOnShowListener(d -> {
            input.setOnEditorActionListener((textView, actionId, event) -> {
                if (actionId != EditorInfo.IME_ACTION_SEARCH) return false;
                submit(dialog);
                return true;
            });
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> submit(dialog));
            Util.showKeyboard(input);
        });
        return dialog;
    }

    @Override
    public void onStart() {
        super.onStart();
        Dialog dialog = getDialog();
        Window window = dialog == null ? null : dialog.getWindow();
        if (window != null) window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
    }

    @Override
    public void onDismiss(@NonNull android.content.DialogInterface dialog) {
        super.onDismiss(dialog);
        FragmentActivity activity = getActivity();
        if (submitted || !restoreParent || activity == null || activity.isFinishing()) return;
        DanmakuDialog.create().player(player).show(activity);
    }

    private TextInputEditText createInput() {
        TextInputEditText edit = new TextInputEditText(requireContext());
        edit.setSingleLine(true);
        edit.setMaxLines(1);
        edit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS | InputType.TYPE_TEXT_FLAG_AUTO_CORRECT | InputType.TYPE_TEXT_FLAG_AUTO_COMPLETE);
        edit.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        edit.setText(player.getMetadata() == null ? "" : player.getMetadata().title);
        if (edit.getText() != null) edit.setSelection(edit.getText().length());
        return edit;
    }

    private FrameLayout createInputView() {
        TextInputLayout layout = new TextInputLayout(requireContext());
        layout.setHint(getString(R.string.search_keyword));
        layout.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_OUTLINE);
        layout.addView(input, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        FrameLayout frame = new FrameLayout(requireContext());
        int horizontal = ResUtil.dp2px(20);
        int top = ResUtil.dp2px(8);
        frame.setPadding(horizontal, top, horizontal, 0);
        frame.addView(layout, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT));
        return frame;
    }

    private void submit(AlertDialog dialog) {
        String keyword = input.getText() == null ? "" : input.getText().toString().trim();
        if (TextUtils.isEmpty(keyword)) {
            input.setError(getString(R.string.error_empty));
            return;
        }
        submitted = true;
        Util.hideKeyboard(input);
        FragmentActivity activity = getActivity();
        dialog.dismiss();
        if (activity == null || activity.isFinishing()) return;
        DanmakuSearchDialog.create().player(player).restoreParent(true).keyword(keyword).autoSearch(true).hideKeyword(true).show(activity);
    }
}
