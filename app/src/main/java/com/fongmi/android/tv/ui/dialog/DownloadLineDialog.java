package com.fongmi.android.tv.ui.dialog;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.BaseAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.utils.GithubProxy;
import com.fongmi.android.tv.utils.ResUtil;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

// 应用内更新的下载线路选择弹窗：仅展示已通过内容验证的线路（含延迟），
// 支持手动点选某条，或点「自动选择」使用最快线路
public class DownloadLineDialog {

    public interface OnPicked {
        void onPick(GithubProxy.Line line);
    }

    public static void show(Context context, List<GithubProxy.Line> lines, OnPicked onPicked, Runnable onAbort) {
        if (context == null || lines == null || lines.isEmpty()) return;
        ListView listView = new ListView(context);
        int pad = ResUtil.dp2px(16);
        listView.setPadding(pad, pad / 2, pad, 0);
        listView.setAdapter(new LineAdapter(context, lines));
        TextView title = new TextView(context);
        title.setText(R.string.update_line_title);
        title.setTextSize(18);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setPadding(pad, pad, pad, 0);
        LinearLayout shell = new LinearLayout(context);
        shell.setOrientation(LinearLayout.VERTICAL);
        shell.addView(title, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        shell.addView(listView, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ResUtil.dp2px(320)));
        AtomicBoolean picked = new AtomicBoolean(false);
        AlertDialog dialog = new AlertDialog.Builder(context)
                .setView(shell)
                .setNegativeButton(android.R.string.cancel, (d, which) -> {
                    if (!picked.get()) onAbort.run();
                })
                .setPositiveButton(R.string.update_line_auto, (d, which) -> {
                    picked.set(true);
                    onPicked.onPick(lines.get(0));
                })
                .create();
        dialog.setOnCancelListener(d -> {
            if (!picked.get()) onAbort.run();
        });
        listView.setOnItemClickListener((parent, view, position, id) -> {
            picked.set(true);
            dialog.dismiss();
            onPicked.onPick(lines.get(position));
        });
        dialog.show();
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
