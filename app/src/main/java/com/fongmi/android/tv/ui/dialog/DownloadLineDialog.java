package com.fongmi.android.tv.ui.dialog;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.utils.GithubProxy;
import com.fongmi.android.tv.utils.ResUtil;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

// 应用内更新的下载线路选择弹窗：点「更新」立即弹出（探测期间常驻转圈），
// 线路就绪后展示列表并启动 5 秒倒计时，超时自动选择最快线路，用户随时可手点某条立即选定
public class DownloadLineDialog {

    public interface OnPicked {
        void onPick(GithubProxy.Line line);
    }

    // 倒计时秒数：线路就绪后用户 5 秒内未手选则自动选最快
    private static final int COUNTDOWN_SECONDS = 5;

    private final Context context;
    // 强制更新态：不提供「取消」按钮，用户只能选线或等倒计时自动选最快
    private final boolean force;
    private final OnPicked onPicked;
    private final Runnable onAbort;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean picked = new AtomicBoolean(false);
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private AlertDialog dialog;
    private LinearLayout shell;
    private View loadingView;
    private ListView listView;
    private TextView countdownView;
    private List<GithubProxy.Line> lines;
    private int remaining;
    private Runnable ticker;

    public DownloadLineDialog(Context context, boolean force, OnPicked onPicked, Runnable onAbort) {
        this.context = context;
        this.force = force;
        this.onPicked = onPicked;
        this.onAbort = onAbort;
    }

    // 加载态：线路探测完成前先展示转圈，「自动选择」按钮置灰待线路就绪
    public void showLoading() {
        if (closed.get()) return;
        int pad = ResUtil.dp2px(16);
        TextView title = new TextView(context);
        title.setText(R.string.update_line_title);
        title.setTextSize(18);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setPadding(pad, pad, pad, 0);
        shell = new LinearLayout(context);
        shell.setOrientation(LinearLayout.VERTICAL);
        shell.addView(title, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        loadingView = buildLoading(pad);
        shell.addView(loadingView, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ResUtil.dp2px(320)));
        AlertDialog.Builder builder = new AlertDialog.Builder(context)
                .setView(shell)
                .setPositiveButton(R.string.update_line_auto, (d, which) -> {
                    if (lines != null && !lines.isEmpty()) pick(lines.get(0));
                });
        // 强制更新态：不提供取消按钮，返回键也不关（setCancelable(false)），只能选线或等倒计时自动选最快
        if (!force) builder.setNegativeButton(android.R.string.cancel, (d, which) -> abort());
        dialog = builder.create();
        dialog.setCancelable(false);
        dialog.show();
        Button positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        if (positive != null) positive.setEnabled(false);
    }

    private View buildLoading(int pad) {
        LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setPadding(pad, pad, pad, pad);
        box.addView(new ProgressBar(context, null, android.R.attr.progressBarStyleLarge));
        TextView text = new TextView(context);
        text.setText(R.string.update_line_loading);
        text.setTextSize(15);
        text.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = ResUtil.dp2px(12);
        box.addView(text, lp);
        return box;
    }

    // 探测完成：撤掉转圈填充线路列表，底部启动倒计时；返回是否成功展示（弹窗不可用时由调用方兜底自动选最快）
    public boolean setLines(List<GithubProxy.Line> lines) {
        if (closed.get() || picked.get() || dialog == null || !dialog.isShowing()) return false;
        if (lines == null || lines.isEmpty()) return false;
        this.lines = lines;
        int pad = ResUtil.dp2px(16);
        shell.removeView(loadingView);
        listView = new ListView(context);
        listView.setPadding(pad, pad / 2, pad, 0);
        listView.setAdapter(new LineAdapter(context, lines));
        listView.setOnItemClickListener((parent, view, position, id) -> pick(lines.get(position)));
        shell.addView(listView, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ResUtil.dp2px(320)));
        countdownView = new TextView(context);
        countdownView.setTextSize(13);
        countdownView.setGravity(Gravity.CENTER);
        countdownView.setTextColor(ResUtil.getColor(android.R.color.darker_gray));
        countdownView.setPadding(pad, ResUtil.dp2px(6), pad, ResUtil.dp2px(2));
        shell.addView(countdownView, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        Button positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        if (positive != null) positive.setEnabled(true);
        startCountdown();
        return true;
    }

    // 弹窗是否仍在指定页面上有效展示（Activity 重建后旧弹窗即失效）
    public boolean isShowingFor(Context act) {
        return dialog != null && dialog.isShowing() && context == act && !picked.get() && !closed.get();
    }

    // 回前台时置顶：重新 attach 弹窗窗口到窗口栈顶，避免被重弹的版本弹窗盖住
    public void bringToFront() {
        try {
            if (dialog != null && dialog.isShowing()) dialog.show();
        } catch (Exception ignored) {
        }
    }

    // 已就绪的线路列表（null 表示探测未完成）
    public List<GithubProxy.Line> getReadyLines() {
        return lines;
    }

    private void startCountdown() {
        remaining = COUNTDOWN_SECONDS;
        updateCountdown();
        ticker = new Runnable() {
            @Override
            public void run() {
                if (picked.get() || closed.get() || lines == null) return;
                remaining--;
                if (remaining <= 0) {
                    pick(lines.get(0));
                    return;
                }
                updateCountdown();
                handler.postDelayed(this, 1000);
            }
        };
        handler.postDelayed(ticker, 1000);
    }

    private void updateCountdown() {
        if (countdownView != null) countdownView.setText(context.getString(R.string.update_line_countdown, remaining));
    }

    private void pick(GithubProxy.Line line) {
        if (picked.get() || closed.get()) return;
        picked.set(true);
        stopCountdown();
        dismissQuietly();
        onPicked.onPick(line);
    }

    // 用户主动取消：中止本次更新下载
    private void abort() {
        if (picked.get() || closed.get()) return;
        closed.set(true);
        stopCountdown();
        onAbort.run();
    }

    // 外部中止（探测后任务已被取消等）：静默关窗，不触发 onAbort
    public void dismissQuietly() {
        closed.set(true);
        stopCountdown();
        try {
            if (dialog != null && dialog.isShowing()) dialog.dismiss();
        } catch (Exception ignored) {
        } finally {
            dialog = null;
        }
    }

    // 倒计时回调随窗口关闭一并移除，防止泄漏
    private void stopCountdown() {
        if (ticker != null) {
            handler.removeCallbacks(ticker);
            ticker = null;
        }
    }

    private static class LineAdapter extends BaseAdapter {

        private final Context context;
        private final List<GithubProxy.Line> lines;

        LineAdapter(Context context, List<GithubProxy.Line> lines) {
            this.context = context;
            this.lines = lines;
        }

        @Override
        public int getCount() {
            return lines.size();
        }

        @Override
        public GithubProxy.Line getItem(int position) {
            return lines.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, android.view.ViewGroup parent) {
            GithubProxy.Line line = getItem(position);
            LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(ResUtil.dp2px(4), ResUtil.dp2px(14), ResUtil.dp2px(4), ResUtil.dp2px(14));
            TextView name = new TextView(context);
            name.setTextSize(15);
            name.setText(line.prefix.isEmpty() ? context.getString(R.string.update_line_direct) : GithubProxy.name(line));
            name.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
            name.setSingleLine(true);
            LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
            row.addView(name, nameLp);
            TextView cost = new TextView(context);
            cost.setTextSize(13);
            cost.setTextColor(ResUtil.getColor(android.R.color.darker_gray));
            cost.setText(line.cost + " ms");
            row.addView(cost, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            return row;
        }
    }
}
