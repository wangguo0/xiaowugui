package com.fongmi.android.tv.ui.dialog;

import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.TextView;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.utils.ResUtil;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.slider.Slider;

import java.util.function.IntConsumer;

/**
 * 播放控制条功能按钮的锚定下拉框：紧贴按钮正上方向上弹出，
 * 单选列表宽度=最长选项所需宽度（不换行）；片头/片尾为宽幅横向滑条选择框。
 */
public final class ActionChoiceSheet {

    public interface Listener {
        void onSelected(int which);
    }

    private ActionChoiceSheet() {
    }

    /**
     * 在按钮正上方弹出单选列表（宽度=最长选项文字所需宽度、不换行，超高可滚动）
     *
     * @param anchor    功能按钮
     * @param items     选项文字
     * @param checked   当前选中项下标
     * @param listener  选中回调
     * @param onDismiss 弹窗关闭回调（用于恢复控制条自动隐藏计时）
     */
    public static void show(View anchor, String[] items, int checked, Listener listener, Runnable onDismiss) {
        if (anchor == null || items == null || items.length == 0) return;
        int accent = MaterialColors.getColor(anchor, androidx.appcompat.R.attr.colorPrimary);
        final PopupWindow[] ref = new PopupWindow[1];
        LinearLayout list = new LinearLayout(anchor.getContext());
        list.setOrientation(LinearLayout.VERTICAL);
        int pad = ResUtil.dp2px(4);
        list.setPadding(pad, pad, pad, pad);
        int maxItemWidth = 0;
        for (int i = 0; i < items.length; i++) {
            final int index = i;
            boolean selected = i == checked;
            TextView item = new TextView(anchor.getContext());
            item.setText(selected ? "✓ " + items[i] : items[i]);
            item.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            item.setTypeface(selected ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            item.setGravity(Gravity.CENTER);
            item.setSingleLine(true);
            item.setTextColor(selected ? accent : 0xFFFFFFFF);
            item.setPadding(ResUtil.dp2px(12), ResUtil.dp2px(8), ResUtil.dp2px(12), ResUtil.dp2px(8));
            item.setBackgroundResource(selectable(anchor));
            item.setOnClickListener(v -> {
                if (ref[0] != null) ref[0].dismiss();
                if (listener != null) listener.onSelected(index);
            });
            list.addView(item, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            // 以单行测量每个选项（含 ✓ 前缀），取最宽者作为弹窗内容宽度
            item.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            maxItemWidth = Math.max(maxItemWidth, item.getMeasuredWidth());
        }
        int width = Math.max(maxItemWidth + pad * 2, anchor.getWidth());
        width = Math.min(width, ResUtil.getScreenWidth() - ResUtil.dp2px(16));
        ScrollView scroll = new ScrollView(anchor.getContext());
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.addView(list);
        PopupWindow popup = new PopupWindow(scroll, width, ViewGroup.LayoutParams.WRAP_CONTENT, true);
        ref[0] = popup;
        popup.setBackgroundDrawable(background());
        popup.setOutsideTouchable(true);
        popup.setOnDismissListener(() -> {
            if (onDismiss != null) onDismiss.run();
        });
        int[] loc = new int[2];
        anchor.getLocationOnScreen(loc);
        // 最大高度限制在按钮上沿以上，避免超出屏幕；超出时内部可滚动
        int max = Math.max(loc[1] - ResUtil.dp2px(16), ResUtil.dp2px(120));
        list.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        // 弹窗在按钮上方水平居中，贴边时收进屏幕内
        int left = Math.min(Math.max(loc[0] + (anchor.getWidth() - width) / 2, ResUtil.dp2px(8)), ResUtil.getScreenWidth() - width - ResUtil.dp2px(8));
        showAbove(popup, anchor, left - loc[0], Math.min(list.getMeasuredHeight(), max));
    }

    /**
     * 在按钮正上方弹出横向滑条选择框（0–300 秒、1 秒步进、实时显示秒数）
     *
     * @param anchor         功能按钮
     * @param title          小标题（如"片头"/"片尾"）
     * @param currentSeconds 初始秒数
     * @param onConfirm      点击确定后的秒数回调
     * @param onDismiss      弹窗关闭回调（用于恢复控制条自动隐藏计时）
     */
    public static void showSecondsPicker(View anchor, CharSequence title, int currentSeconds, IntConsumer onConfirm, Runnable onDismiss) {
        if (anchor == null) return;
        int accent = MaterialColors.getColor(anchor, androidx.appcompat.R.attr.colorPrimary);
        final PopupWindow[] ref = new PopupWindow[1];
        LinearLayout root = new LinearLayout(anchor.getContext());
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = ResUtil.dp2px(8);
        root.setPadding(pad, pad, pad, pad);
        Slider slider = new Slider(anchor.getContext());
        slider.setValueFrom(0f);
        slider.setValueTo(300f);
        slider.setStepSize(1f);
        slider.setValue(Math.min(Math.max(currentSeconds, 0), 300));
        slider.setLabelFormatter(value -> String.valueOf((int) value));
        // 第一行：标题+当前秒数（左） 取消/确定（右），与滑条合并压缩高度
        LinearLayout row = new LinearLayout(anchor.getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView label = new TextView(anchor.getContext());
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        label.setTypeface(Typeface.DEFAULT_BOLD);
        label.setTextColor(0xFFFFFFFF);
        row.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(button(anchor, ResUtil.getString(R.string.dialog_cancel), 0xD9FFFFFF, v -> {
            if (ref[0] != null) ref[0].dismiss();
        }));
        row.addView(button(anchor, ResUtil.getString(R.string.dialog_confirm), accent, v -> {
            if (ref[0] != null) ref[0].dismiss();
            if (onConfirm != null) onConfirm.accept((int) slider.getValue());
        }));
        root.addView(row, matchWrap(0));
        slider.addOnChangeListener((s, v, fromUser) -> label.setText(buildLabel(title, (int) v)));
        root.addView(slider, matchWrap(0));
        LinearLayout ends = new LinearLayout(anchor.getContext());
        ends.setOrientation(LinearLayout.HORIZONTAL);
        TextView end0 = endText(anchor.getContext(), 0);
        TextView end300 = endText(anchor.getContext(), 300);
        LinearLayout.LayoutParams space = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        ends.addView(end0);
        ends.addView(new View(anchor.getContext()), space);
        ends.addView(end300);
        root.addView(ends, matchWrap(0));
        label.setText(buildLabel(title, (int) slider.getValue()));
        // 宽度加倍：440dp，小屏自动收窄不越界
        int width = Math.min(ResUtil.dp2px(440), ResUtil.getScreenWidth() - ResUtil.dp2px(16));
        PopupWindow popup = new PopupWindow(root, width, ViewGroup.LayoutParams.WRAP_CONTENT, true);
        ref[0] = popup;
        popup.setBackgroundDrawable(background());
        popup.setOutsideTouchable(true);
        popup.setOnDismissListener(() -> {
            if (onDismiss != null) onDismiss.run();
        });
        // 弹窗在按钮上方水平居中，贴边时收进屏幕内
        int[] loc = new int[2];
        anchor.getLocationOnScreen(loc);
        int screenW = ResUtil.getScreenWidth();
        int left = Math.min(Math.max(loc[0] + (anchor.getWidth() - width) / 2, ResUtil.dp2px(8)), screenW - width - ResUtil.dp2px(8));
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        showAbove(popup, anchor, left - loc[0], root.getMeasuredHeight());
    }

    private static CharSequence buildLabel(CharSequence title, int seconds) {
        return title + "  " + ResUtil.getString(R.string.seconds_format, seconds);
    }

    private static TextView endText(android.content.Context context, int seconds) {
        TextView view = new TextView(context);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        view.setTextColor(0xFF8B8F98);
        view.setText(ResUtil.getString(R.string.seconds_format, seconds));
        return view;
    }

    private static LinearLayout.LayoutParams matchWrap(int topDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = ResUtil.dp2px(topDp);
        return params;
    }

    private static TextView button(View anchor, String label, int color, View.OnClickListener click) {
        TextView view = new TextView(anchor.getContext());
        view.setText(label);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        view.setTextColor(color);
        view.setGravity(Gravity.CENTER);
        view.setPadding(ResUtil.dp2px(12), ResUtil.dp2px(6), ResUtil.dp2px(12), ResUtil.dp2px(6));
        view.setBackgroundResource(selectable(anchor));
        view.setOnClickListener(click);
        return view;
    }

    private static void showAbove(PopupWindow popup, View anchor, int xoff, int height) {
        if (height > 0) popup.setHeight(height);
        int h = height > 0 ? height : popup.getHeight();
        popup.showAsDropDown(anchor, xoff, -anchor.getHeight() - h - ResUtil.dp2px(6));
    }

    private static GradientDrawable background() {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(0xF0232529);
        drawable.setCornerRadius(ResUtil.dp2px(8));
        return drawable;
    }

    private static int selectable(View anchor) {
        TypedValue tv = new TypedValue();
        anchor.getContext().getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true);
        return tv.resourceId;
    }
}
