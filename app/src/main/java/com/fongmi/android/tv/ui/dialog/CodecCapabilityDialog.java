package com.fongmi.android.tv.ui.dialog;

import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.FragmentActivity;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.player.PlayerManager;
import com.fongmi.android.tv.player.codec.CodecCapabilityInspector;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.utils.Util;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.textview.MaterialTextView;

public final class CodecCapabilityDialog {

    private static final int MODE_CURRENT = -1;

    private final FragmentActivity activity;
    private final PlayerManager player;
    private MaterialTextView content;
    private MaterialButton current;
    private MaterialButton all;
    private MaterialButton video;
    private MaterialButton audio;
    private EditText search;
    private Dialog dialog;
    private int mode = MODE_CURRENT;

    private CodecCapabilityDialog(FragmentActivity activity, PlayerManager player) {
        this.activity = activity;
        this.player = player;
    }

    public static void show(FragmentActivity activity, PlayerManager player) {
        new CodecCapabilityDialog(activity, player).show();
    }

    private void show() {
        dialog = LightDialog.create(activity, activity.getString(R.string.codec_capability_title), createContent(), activity.getString(R.string.codec_capability_copy), v -> copy(), activity.getString(R.string.dialog_negative), null);
        update();
        dialog.show();
        applySize();
    }

    private View createContent() {
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);

        search = new EditText(activity);
        search.setSingleLine(true);
        search.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        search.setHint(R.string.codec_capability_search);
        search.setTextSize(14);
        search.setTextColor(Color.parseColor("#202124"));
        search.setHintTextColor(Color.parseColor("#7A7F85"));
        search.setPadding(ResUtil.dp2px(12), 0, ResUtil.dp2px(12), 0);
        search.setBackground(searchBackground());
        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                update();
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });
        root.addView(search, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ResUtil.dp2px(42)));

        LinearLayout tabs = new LinearLayout(activity);
        tabs.setGravity(Gravity.CENTER_VERTICAL);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams tabParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ResUtil.dp2px(38));
        tabParams.topMargin = ResUtil.dp2px(10);
        root.addView(tabs, tabParams);

        current = tab(R.string.codec_capability_current, v -> setMode(MODE_CURRENT));
        all = tab(R.string.codec_capability_all, v -> setMode(CodecCapabilityInspector.TYPE_ALL));
        video = tab(R.string.codec_capability_video, v -> setMode(CodecCapabilityInspector.TYPE_VIDEO));
        audio = tab(R.string.codec_capability_audio, v -> setMode(CodecCapabilityInspector.TYPE_AUDIO));
        tabs.addView(current);
        tabs.addView(all);
        tabs.addView(video);
        tabs.addView(audio);

        ScrollView scroll = new ScrollView(activity);
        scroll.setFillViewport(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        content = new MaterialTextView(activity);
        content.setTextColor(Color.parseColor("#202124"));
        content.setTextSize(13);
        content.setLineSpacing(0, 1.08f);
        content.setTypeface(Typeface.MONOSPACE);
        content.setTextIsSelectable(true);
        content.setPadding(ResUtil.dp2px(10), ResUtil.dp2px(10), ResUtil.dp2px(10), ResUtil.dp2px(10));
        scroll.addView(content, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ResUtil.dp2px(Util.isLeanback() ? 430 : 360));
        scrollParams.topMargin = ResUtil.dp2px(10);
        root.addView(scroll, scrollParams);
        return root;
    }

    private MaterialButton tab(int text, View.OnClickListener listener) {
        MaterialButton button = new MaterialButton(activity);
        button.setAllCaps(false);
        button.setText(text);
        button.setTextSize(13);
        button.setSingleLine(true);
        button.setEllipsize(TextUtils.TruncateAt.END);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setMinHeight(0);
        button.setInsetTop(0);
        button.setInsetBottom(0);
        button.setPadding(ResUtil.dp2px(4), 0, ResUtil.dp2px(4), 0);
        button.setFocusable(true);
        button.setFocusableInTouchMode(Util.isLeanback());
        button.setOnClickListener(listener);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1);
        params.leftMargin = ResUtil.dp2px(4);
        params.rightMargin = ResUtil.dp2px(4);
        button.setLayoutParams(params);
        return button;
    }

    private GradientDrawable searchBackground() {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(Color.parseColor("#F8F9FA"));
        drawable.setCornerRadius(ResUtil.dp2px(6));
        drawable.setStroke(ResUtil.dp2px(1), Color.parseColor("#DADCE0"));
        return drawable;
    }

    private void setMode(int mode) {
        this.mode = mode;
        update();
    }

    private void update() {
        if (content == null) return;
        setSelected(current, mode == MODE_CURRENT);
        setSelected(all, mode == CodecCapabilityInspector.TYPE_ALL);
        setSelected(video, mode == CodecCapabilityInspector.TYPE_VIDEO);
        setSelected(audio, mode == CodecCapabilityInspector.TYPE_AUDIO);
        String keyword = search == null || search.getText() == null ? "" : search.getText().toString();
        content.setText(mode == MODE_CURRENT ? CodecCapabilityInspector.buildCurrentMediaReport(activity, player, keyword) : CodecCapabilityInspector.buildDeviceReport(keyword, mode));
    }

    private void setSelected(@NonNull MaterialButton button, boolean selected) {
        button.setSelected(selected);
        button.setTextColor(ContextCompat.getColorStateList(activity, selected ? R.color.dialog_primary_button_text : R.color.dialog_outlined_button_text));
        button.setBackgroundTintList(ContextCompat.getColorStateList(activity, selected ? R.color.dialog_primary_button_bg : R.color.dialog_outlined_button_bg));
        button.setStrokeColor(ContextCompat.getColorStateList(activity, R.color.dialog_outlined_button_stroke));
        button.setStrokeWidth(selected ? 0 : ResUtil.dp2px(1));
    }

    private void copy() {
        if (content == null || TextUtils.isEmpty(content.getText())) return;
        ClipboardManager manager = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
        if (manager == null) return;
        manager.setPrimaryClip(ClipData.newPlainText(activity.getString(R.string.codec_capability_title), content.getText()));
        Notify.show(R.string.copied);
    }

    private void applySize() {
        if (dialog == null || dialog.getWindow() == null) return;
        int width = Math.min(Math.round(ResUtil.getScreenWidth(activity) * (ResUtil.isLand(activity) ? 0.78f : 0.94f)), ResUtil.dp2px(820));
        dialog.getWindow().setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
    }
}
