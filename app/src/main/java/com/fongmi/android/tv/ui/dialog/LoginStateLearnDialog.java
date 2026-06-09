package com.fongmi.android.tv.ui.dialog;

import android.view.ViewGroup;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.LinearLayoutCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.utils.LoginStateSync;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.utils.Task;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.List;

public final class LoginStateLearnDialog {

    private LoginStateLearnDialog() {
    }

    public static void show(Fragment fragment, Runnable callback) {
        show(fragment.requireActivity(), callback);
    }

    public static void show(FragmentActivity activity, Runnable callback) {
        boolean learning = LoginStateSync.hasLearningSnapshot();
        int learned = LoginStateSync.learnedCount();
        AlertDialog dialog = new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.setting_login_state)
                .setView(view(activity, learning, learned))
                .setNegativeButton(R.string.dialog_negative, null)
                .setNeutralButton(R.string.login_state_manage_paths, null)
                .setPositiveButton(learning ? R.string.login_state_finish : R.string.login_state_start, null)
                .create();
        dialog.setOnShowListener(d -> {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
                dialog.dismiss();
                LoginStatePathDialog.show(activity, callback);
            });
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                dialog.dismiss();
                run(activity, learning, callback);
            });
        });
        dialog.show();
    }

    private static LinearLayoutCompat view(FragmentActivity activity, boolean learning, int learned) {
        LinearLayoutCompat container = new LinearLayoutCompat(activity);
        container.setOrientation(LinearLayoutCompat.VERTICAL);
        container.setPadding(ResUtil.dp2px(24), ResUtil.dp2px(8), ResUtil.dp2px(24), 0);

        TextView message = new TextView(activity);
        message.setText(activity.getString(learning ? R.string.login_state_learning_message : R.string.login_state_message, learned));
        message.setTextColor(0xDE000000);
        message.setTextSize(14);
        message.setLineSpacing(0, 1.12f);
        container.addView(message, new LinearLayoutCompat.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView paths = new TextView(activity);
        paths.setText(quickText());
        paths.setTextColor(0xB3000000);
        paths.setTextSize(13);
        paths.setLineSpacing(0, 1.18f);
        paths.setTextIsSelectable(true);
        paths.setPadding(ResUtil.dp2px(12), ResUtil.dp2px(10), ResUtil.dp2px(12), ResUtil.dp2px(10));

        ScrollView scroll = new ScrollView(activity);
        scroll.setFillViewport(false);
        scroll.setScrollbarFadingEnabled(false);
        scroll.addView(paths, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayoutCompat.LayoutParams params = new LinearLayoutCompat.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ResUtil.dp2px(190));
        params.topMargin = ResUtil.dp2px(12);
        container.addView(scroll, params);
        return container;
    }

    private static String quickText() {
        StringBuilder builder = new StringBuilder();
        append(builder, R.string.login_state_learned_title, LoginStateSync.learnedPaths());
        append(builder, R.string.login_state_pending_title, LoginStateSync.pendingPaths());
        appendFindings(builder);
        if (builder.length() == 0) builder.append(ResUtil.getString(R.string.login_state_empty));
        return builder.toString();
    }

    private static void append(StringBuilder builder, int title, List<String> paths) {
        if (paths.isEmpty()) return;
        if (builder.length() > 0) builder.append("\n\n");
        builder.append(ResUtil.getString(title));
        for (String path : paths) builder.append("\n").append(LoginStateSync.displayPath(path));
    }

    private static void appendFindings(StringBuilder builder) {
        List<LoginStateSync.Candidate> findings = LoginStateSync.findings();
        if (findings.isEmpty()) return;
        if (builder.length() > 0) builder.append("\n\n");
        builder.append(ResUtil.getString(R.string.login_state_findings_title));
        for (LoginStateSync.Candidate item : findings) {
            builder.append("\n[").append(item.getConfidence()).append("] ").append(item.getReason()).append("\n").append(item.getDisplayPath());
        }
    }

    private static void run(FragmentActivity activity, boolean finish, Runnable callback) {
        Task.execute(() -> {
            if (finish) finish(activity, callback);
            else begin(callback);
        });
    }

    private static void begin(Runnable callback) {
        LoginStateSync.beginLearning();
        App.post(() -> {
            Notify.show(R.string.login_state_started);
            if (callback != null) callback.run();
        });
    }

    private static void finish(FragmentActivity activity, Runnable callback) {
        LoginStateSync.LearnResult result = LoginStateSync.finishLearning();
        int selected = result.getSelected().size();
        int pending = LoginStateSync.pendingPaths().size();
        App.post(() -> {
            Notify.show(App.get().getString(R.string.login_state_finished, selected, pending));
            if (callback != null) callback.run();
            if (pending > 0 && !activity.isFinishing()) LoginStatePathDialog.show(activity, callback);
        });
    }
}
