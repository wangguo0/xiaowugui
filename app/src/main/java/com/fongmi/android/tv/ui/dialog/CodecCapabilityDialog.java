package com.fongmi.android.tv.ui.dialog;

import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.text.SpannableStringBuilder;
import android.text.Editable;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
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

        MaterialTextView note = new MaterialTextView(activity);
        note.setText("芯片 " + chipText() + "\n视频只列硬件 decoder；音频列系统 decoder，包含平台软件 decoder。当前媒体页显示轨道选中状态；视频/音频页高亮表示该 decoder 可解当前轨道。");
        note.setTextColor(Color.parseColor("#5F6368"));
        note.setTextSize(12);
        note.setLineSpacing(0, 1.12f);
        LinearLayout.LayoutParams noteParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        noteParams.bottomMargin = ResUtil.dp2px(10);
        root.addView(note, noteParams);

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
        LinearLayout.LayoutParams tabParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ResUtil.dp2px(36));
        tabParams.topMargin = ResUtil.dp2px(9);
        root.addView(tabs, tabParams);

        current = tab(R.string.codec_capability_current, v -> setMode(MODE_CURRENT));
        all = tab(R.string.codec_capability_all, v -> setMode(CodecCapabilityInspector.TYPE_ALL));
        video = tab(R.string.codec_capability_video, v -> setMode(CodecCapabilityInspector.TYPE_VIDEO));
        audio = tab(R.string.codec_capability_audio, v -> setMode(CodecCapabilityInspector.TYPE_AUDIO));
        tabs.addView(current);
        tabs.addView(all);
        tabs.addView(video);
        tabs.addView(audio);

        FrameLayout report = new FrameLayout(activity);
        report.setBackground(reportBackground());
        report.setPadding(ResUtil.dp2px(2), ResUtil.dp2px(2), ResUtil.dp2px(2), ResUtil.dp2px(2));
        LinearLayout.LayoutParams reportParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, getReportHeight());
        reportParams.topMargin = ResUtil.dp2px(10);
        root.addView(report, reportParams);

        ScrollView scroll = new ScrollView(activity);
        scroll.setFillViewport(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        content = new MaterialTextView(activity);
        content.setTextColor(Color.parseColor("#202124"));
        content.setTextSize(12);
        content.setLineSpacing(ResUtil.dp2px(2), 1.0f);
        content.setTypeface(Typeface.MONOSPACE);
        content.setTextIsSelectable(true);
        content.setPadding(ResUtil.dp2px(12), ResUtil.dp2px(12), ResUtil.dp2px(12), ResUtil.dp2px(12));
        scroll.addView(content, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        report.addView(scroll, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
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

    private String chipText() {
        String soc = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ? join(" ", Build.SOC_MANUFACTURER, Build.SOC_MODEL) : "";
        return join(" / ", soc, "hardware " + empty(Build.HARDWARE), "board " + empty(Build.BOARD));
    }

    private String join(String separator, String... values) {
        StringBuilder builder = new StringBuilder();
        for (String value : values) {
            if (TextUtils.isEmpty(value)) continue;
            if (builder.length() > 0) builder.append(separator);
            builder.append(value);
        }
        return builder.toString();
    }

    private String empty(String value) {
        return TextUtils.isEmpty(value) ? "-" : value;
    }

    private int getReportHeight() {
        int screen = ResUtil.getScreenHeight(activity);
        if (Util.isLeanback()) return Math.min(ResUtil.dp2px(430), Math.round(screen * 0.58f));
        return Math.min(ResUtil.dp2px(330), Math.round(screen * (ResUtil.isLand(activity) ? 0.44f : 0.36f)));
    }

    private GradientDrawable searchBackground() {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(Color.parseColor("#F8F9FA"));
        drawable.setCornerRadius(ResUtil.dp2px(6));
        drawable.setStroke(ResUtil.dp2px(1), Color.parseColor("#DADCE0"));
        return drawable;
    }

    private GradientDrawable reportBackground() {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(Color.parseColor("#F8F9FA"));
        drawable.setCornerRadius(ResUtil.dp2px(8));
        drawable.setStroke(ResUtil.dp2px(1), Color.parseColor("#E0E3E7"));
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
        if (mode == MODE_CURRENT) content.setText(highlightTrackBlocks(CodecCapabilityInspector.buildCurrentMediaReport(activity, player, keyword)));
        else content.setText(highlightDecoderMatches(CodecCapabilityInspector.buildDeviceReport(activity, player, keyword, mode)));
    }

    private SpannableStringBuilder highlightTrackBlocks(String text) {
        SpannableStringBuilder builder = new SpannableStringBuilder(text);
        int cursor = 0;
        while (cursor < text.length()) {
            int start = text.indexOf("轨 ", cursor);
            if (start < 0) break;
            int lineStart = text.lastIndexOf('\n', start);
            lineStart = lineStart < 0 ? 0 : lineStart + 1;
            int end = text.indexOf("\n\n", start);
            end = end < 0 ? text.length() : end;
            boolean selected = text.substring(lineStart, end).contains(" / 已选中");
            applyBlockHighlight(builder, lineStart, end, selected);
            cursor = end + 2;
        }
        return builder;
    }

    private SpannableStringBuilder highlightDecoderMatches(String text) {
        SpannableStringBuilder builder = new SpannableStringBuilder(text);
        highlightDecoderMarker(builder, text, "可解当前媒体", false);
        highlightDecoderMarker(builder, text, "可解当前选中", true);
        return builder;
    }

    private void highlightDecoderMarker(SpannableStringBuilder builder, String text, String marker, boolean selected) {
        int index = text.indexOf(marker);
        while (index >= 0) {
            int start = text.lastIndexOf("\n\n", index);
            start = start < 0 ? 0 : start + 2;
            int end = text.indexOf("\n\n", index);
            end = end < 0 ? text.length() : end;
            applyBlockHighlight(builder, start, end, selected);
            int lineEnd = text.indexOf('\n', index);
            lineEnd = lineEnd < 0 || lineEnd > end ? end : lineEnd;
            builder.setSpan(new StyleSpan(Typeface.BOLD), index, lineEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            index = text.indexOf(marker, end);
        }
    }

    private void applyBlockHighlight(SpannableStringBuilder builder, int start, int end, boolean selected) {
        builder.setSpan(new BackgroundColorSpan(Color.parseColor(selected ? "#E8F0FE" : "#FFF7D6")), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        builder.setSpan(new ForegroundColorSpan(Color.parseColor(selected ? "#174EA6" : "#5F4211")), start, Math.min(end, start + 40), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        if (selected) builder.setSpan(new StyleSpan(Typeface.BOLD), start, Math.min(end, start + 80), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
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
