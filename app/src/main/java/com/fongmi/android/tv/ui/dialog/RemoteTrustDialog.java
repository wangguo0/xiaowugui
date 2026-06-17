package com.fongmi.android.tv.ui.dialog;

import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.text.InputType;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.LinearLayoutCompat;
import androidx.core.widget.NestedScrollView;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.remote.RemoteAgent;
import com.fongmi.android.tv.remote.RemoteAgentService;
import com.fongmi.android.tv.remote.RemoteClient;
import com.fongmi.android.tv.remote.RemoteModels.BindCodeResponse;
import com.fongmi.android.tv.remote.RemoteModels.RemoteBindGrant;
import com.fongmi.android.tv.remote.RemoteModels.RemoteProfile;
import com.fongmi.android.tv.remote.RemoteStore;
import com.fongmi.android.tv.remote.RemoteTokens;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.PermissionUtil;
import com.fongmi.android.tv.utils.Task;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.checkbox.MaterialCheckBox;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.google.android.material.textview.MaterialTextView;

public final class RemoteTrustDialog {

    private RemoteTrustDialog() {
    }

    public static void show(Fragment fragment, Runnable callback) {
        show(fragment.requireActivity(), callback);
    }

    public static void show(FragmentActivity activity, Runnable callback) {
        Binding binding = build(activity);
        AlertDialog dialog = new MaterialAlertDialogBuilder(activity, R.style.ThemeOverlay_WebHTV_LightDialog)
                .setTitle(R.string.setting_remote_trust)
                .setView(binding.scroll)
                .setNegativeButton(R.string.dialog_cancel, null)
                .create();
        binding.dialog = dialog;
        binding.callback = callback;
        render(activity, binding);
        dialog.setOnShowListener(d -> {
            binding.save.setOnClickListener(v -> saveAndRegister(activity, binding));
            binding.bind.setOnClickListener(v -> createBindCode(activity, binding));
            binding.open.setOnClickListener(v -> openConsole(activity, binding));
            binding.permission.setOnClickListener(v -> requestFileAccess(activity, binding));
            binding.clear.setOnClickListener(v -> confirmClear(activity, binding));
            binding.code.setOnClickListener(v -> copyCode(activity, binding));
        });
        dialog.show();
    }

    private static Binding build(Context context) {
        Binding binding = new Binding();
        binding.scroll = new NestedScrollView(context);
        LinearLayoutCompat root = new LinearLayoutCompat(context);
        root.setOrientation(LinearLayoutCompat.VERTICAL);
        root.setPadding(dp(context, 4), dp(context, 2), dp(context, 4), dp(context, 2));
        binding.scroll.addView(root, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        binding.status = text(context, "", 13, "#5F6368", false);
        root.addView(binding.status, matchWrap());

        TextInputLayout serverLayout = new TextInputLayout(context);
        serverLayout.setHint(context.getString(R.string.remote_trust_server_url));
        binding.server = new TextInputEditText(context);
        binding.server.setSingleLine(true);
        binding.server.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        serverLayout.addView(binding.server, matchWrap());
        root.addView(serverLayout, topMargin(matchWrap(), 10));

        binding.enabled = new MaterialCheckBox(context);
        binding.enabled.setText(R.string.remote_trust_enable);
        root.addView(binding.enabled, topMargin(matchWrap(), 8));

        binding.keepOnline = new MaterialCheckBox(context);
        binding.keepOnline.setText(R.string.remote_trust_keep_online);
        root.addView(binding.keepOnline, matchWrap());

        binding.code = text(context, "", 18, "#202124", true);
        binding.code.setPadding(0, dp(context, 10), 0, dp(context, 4));
        root.addView(binding.code, matchWrap());

        binding.hint = text(context, "", 12, "#5F6368", false);
        root.addView(binding.hint, matchWrap());

        LinearLayoutCompat row1 = new LinearLayoutCompat(context);
        row1.setOrientation(LinearLayoutCompat.HORIZONTAL);
        binding.save = button(context, R.string.remote_trust_save_register);
        binding.bind = button(context, R.string.remote_trust_create_bind_code);
        row1.addView(binding.save, weight());
        row1.addView(binding.bind, leftWeight(context));
        root.addView(row1, topMargin(matchWrap(), 12));

        LinearLayoutCompat row2 = new LinearLayoutCompat(context);
        row2.setOrientation(LinearLayoutCompat.HORIZONTAL);
        binding.open = button(context, R.string.remote_trust_open_console);
        binding.permission = button(context, R.string.remote_trust_file_permission);
        row2.addView(binding.open, weight());
        row2.addView(binding.permission, leftWeight(context));
        root.addView(row2, topMargin(matchWrap(), 8));

        binding.clear = button(context, R.string.remote_trust_clear);
        root.addView(binding.clear, topMargin(matchWrap(), 8));
        return binding;
    }

    private static void render(Context context, Binding binding) {
        RemoteProfile profile = RemoteStore.firstProfile();
        if (profile != null) {
            binding.server.setText(TextUtils.isEmpty(profile.serverUrl) ? profile.serverOrigin : profile.serverUrl);
            binding.enabled.setChecked(profile.enabled);
            binding.keepOnline.setChecked(profile.keepOnline);
        } else {
            binding.enabled.setChecked(true);
            binding.keepOnline.setChecked(false);
        }
        binding.status.setText(RemoteStore.summary(context) + (Setting.hasFileAccess() ? "" : "\n" + context.getString(R.string.remote_trust_file_permission_hint)));
        binding.permission.setVisibility(Setting.hasFileAccess() ? View.GONE : View.VISIBLE);
        if (TextUtils.isEmpty(binding.bindCode)) {
            binding.code.setText(R.string.remote_trust_no_bind_code);
            binding.hint.setText(R.string.remote_trust_dialog_hint);
        } else {
            binding.code.setText(binding.bindCode);
            binding.hint.setText(R.string.remote_trust_bind_code_hint);
        }
        if (binding.callback != null) binding.callback.run();
    }

    private static void saveAndRegister(FragmentActivity activity, Binding binding) {
        RemoteProfile profile;
        try {
            profile = prepare(binding);
        } catch (Throwable e) {
            Notify.show(e.getMessage());
            return;
        }
        setBusy(binding, true);
        Task.execute(() -> {
            try {
                new RemoteClient(profile).register();
                RemoteStore.upsertProfile(profile);
                RemoteAgent.get().start();
                App.post(() -> {
                    setBusy(binding, false);
                    Notify.show(R.string.remote_trust_register_done);
                    render(activity, binding);
                });
            } catch (Throwable e) {
                App.post(() -> {
                    setBusy(binding, false);
                    Notify.show(e.getMessage());
                });
            }
        });
    }

    private static void createBindCode(FragmentActivity activity, Binding binding) {
        RemoteProfile profile;
        try {
            profile = prepare(binding);
        } catch (Throwable e) {
            Notify.show(e.getMessage());
            return;
        }
        RemoteBindGrant grant = new RemoteBindGrant();
        grant.bindGrantToken = RemoteTokens.randomCapability("bgt");
        grant.grantId = RemoteTokens.bindGrantId(profile.serverOrigin, grant.bindGrantToken);
        grant.createdAt = System.currentTimeMillis();
        RemoteStore.addBindGrant(profile.serverOrigin, grant);
        setBusy(binding, true);
        Task.execute(() -> {
            try {
                RemoteClient client = new RemoteClient(profile);
                client.register();
                BindCodeResponse response = client.createBindCode(grant);
                RemoteStore.upsertProfile(profile);
                RemoteAgent.get().start();
                App.post(() -> {
                    setBusy(binding, false);
                    binding.bindCode = response == null ? "" : response.code;
                    Notify.show(R.string.remote_trust_bind_code_done);
                    render(activity, binding);
                });
            } catch (Throwable e) {
                App.post(() -> {
                    setBusy(binding, false);
                    Notify.show(e.getMessage());
                });
            }
        });
    }

    private static RemoteProfile prepare(Binding binding) {
        String serverUrl = binding.server.getText() == null ? "" : binding.server.getText().toString();
        return RemoteStore.prepareProfile(serverUrl, binding.enabled.isChecked(), binding.keepOnline.isChecked());
    }

    private static void openConsole(FragmentActivity activity, Binding binding) {
        String serverUrl = binding.server.getText() == null ? "" : binding.server.getText().toString();
        String origin = RemoteTokens.normalizeOrigin(serverUrl);
        if (TextUtils.isEmpty(origin)) {
            Notify.show(R.string.remote_trust_server_required);
            return;
        }
        try {
            activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(origin)));
        } catch (ActivityNotFoundException e) {
            Notify.show(R.string.manage_page_no_browser);
        }
    }

    private static void requestFileAccess(FragmentActivity activity, Binding binding) {
        PermissionUtil.requestFile(activity, granted -> {
            if (granted) RemoteStore.save(RemoteStore.get());
            render(activity, binding);
        });
    }

    private static void confirmClear(FragmentActivity activity, Binding binding) {
        new MaterialAlertDialogBuilder(activity, R.style.ThemeOverlay_WebHTV_LightDialog)
                .setTitle(R.string.remote_trust_clear)
                .setMessage(R.string.remote_trust_clear_message)
                .setNegativeButton(R.string.dialog_cancel, null)
                .setPositiveButton(R.string.dialog_confirm, (dialog, which) -> {
                    RemoteStore.clear();
                    RemoteAgent.get().stop();
                    RemoteAgentService.stop(activity);
                    binding.bindCode = "";
                    render(activity, binding);
                })
                .show();
    }

    private static void copyCode(Context context, Binding binding) {
        if (TextUtils.isEmpty(binding.bindCode)) return;
        ClipboardManager manager = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        if (manager == null) return;
        manager.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.setting_remote_trust), binding.bindCode));
        Notify.show(R.string.remote_trust_bind_code_copied);
    }

    private static void setBusy(Binding binding, boolean busy) {
        binding.save.setEnabled(!busy);
        binding.bind.setEnabled(!busy);
        binding.open.setEnabled(!busy);
        binding.clear.setEnabled(!busy);
        binding.permission.setEnabled(!busy);
    }

    private static MaterialTextView text(Context context, String value, int sp, String color, boolean bold) {
        MaterialTextView view = new MaterialTextView(context);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(Color.parseColor(color));
        if (bold) view.setTypeface(view.getTypeface(), android.graphics.Typeface.BOLD);
        return view;
    }

    private static MaterialButton button(Context context, int resId) {
        MaterialButton button = new MaterialButton(context);
        button.setText(resId);
        button.setAllCaps(false);
        button.setMinHeight(dp(context, 42));
        return button;
    }

    private static LinearLayoutCompat.LayoutParams matchWrap() {
        return new LinearLayoutCompat.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private static LinearLayoutCompat.LayoutParams weight() {
        return new LinearLayoutCompat.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
    }

    private static LinearLayoutCompat.LayoutParams leftWeight(Context context) {
        LinearLayoutCompat.LayoutParams params = weight();
        params.setMarginStart(dp(context, 8));
        return params;
    }

    private static LinearLayoutCompat.LayoutParams topMargin(LinearLayoutCompat.LayoutParams params, int topDp) {
        params.topMargin = dp(App.get(), topDp);
        return params;
    }

    private static int dp(Context context, int value) {
        return (int) (value * context.getResources().getDisplayMetrics().density + 0.5f);
    }

    private static final class Binding {
        private NestedScrollView scroll;
        private AlertDialog dialog;
        private Runnable callback;
        private TextInputEditText server;
        private MaterialCheckBox enabled;
        private MaterialCheckBox keepOnline;
        private MaterialTextView status;
        private MaterialTextView code;
        private MaterialTextView hint;
        private MaterialButton save;
        private MaterialButton bind;
        private MaterialButton open;
        private MaterialButton permission;
        private MaterialButton clear;
        private String bindCode = "";
    }
}
