package com.fongmi.android.tv.ui.dialog;

import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
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
import com.fongmi.android.tv.remote.RemoteModels.ClaimResponse;
import com.fongmi.android.tv.remote.RemoteModels.CommandDetailResponse;
import com.fongmi.android.tv.remote.RemoteModels.CommandResponse;
import com.fongmi.android.tv.remote.RemoteModels.DevicesResponse;
import com.fongmi.android.tv.remote.RemoteModels.RemoteBindGrant;
import com.fongmi.android.tv.remote.RemoteModels.RemoteCommand;
import com.fongmi.android.tv.remote.RemoteModels.RemoteCommandResult;
import com.fongmi.android.tv.remote.RemoteModels.RemoteDevice;
import com.fongmi.android.tv.remote.RemoteModels.RemoteGroup;
import com.fongmi.android.tv.remote.RemoteModels.RemoteProfile;
import com.fongmi.android.tv.remote.RemoteStore;
import com.fongmi.android.tv.remote.RemoteTokens;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.PermissionUtil;
import com.fongmi.android.tv.utils.Task;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.google.android.material.textview.MaterialTextView;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

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
            binding.open.setOnClickListener(v -> openService(activity, binding));
            binding.permission.setOnClickListener(v -> requestFileAccess(activity, binding));
            binding.clear.setOnClickListener(v -> confirmClear(activity, binding));
            binding.code.setOnClickListener(v -> copyCode(activity, binding));
            binding.addDevice.setOnClickListener(v -> addDevice(activity, binding));
            binding.refreshDevices.setOnClickListener(v -> refreshDevices(activity, binding));
            binding.statusAction.setOnClickListener(v -> sendCommand(activity, binding, "device.status"));
            binding.searchAction.setOnClickListener(v -> sendCommand(activity, binding, "action.search"));
            binding.pushAction.setOnClickListener(v -> sendCommand(activity, binding, "action.push"));
            binding.logAction.setOnClickListener(v -> sendCommand(activity, binding, "log.recent"));
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

        root.addView(sectionTitle(context, R.string.remote_trust_section_local), topMargin(matchWrap(), 12));
        root.addView(caption(context, R.string.remote_trust_section_local_hint), topMargin(matchWrap(), 4));

        binding.server = input(context, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI, true);
        root.addView(inputLayout(context, R.string.remote_trust_server_url, binding.server), topMargin(matchWrap(), 10));

        binding.enabled = check(context, R.string.remote_trust_enable);
        root.addView(binding.enabled, topMargin(matchWrap(), 8));

        binding.keepOnline = check(context, R.string.remote_trust_keep_online);
        root.addView(binding.keepOnline, matchWrap());

        binding.code = text(context, "", 20, "#202124", true);
        binding.code.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        binding.code.setPadding(0, dp(context, 10), 0, dp(context, 4));
        root.addView(binding.code, matchWrap());

        binding.hint = text(context, "", 12, "#5F6368", false);
        root.addView(binding.hint, matchWrap());

        LinearLayoutCompat localRow = row(context);
        binding.save = button(context, R.string.remote_trust_save_register);
        binding.bind = button(context, R.string.remote_trust_create_bind_code);
        localRow.addView(binding.save, weight());
        localRow.addView(binding.bind, leftWeight(context));
        root.addView(localRow, topMargin(matchWrap(), 12));

        LinearLayoutCompat utilityRow = row(context);
        binding.open = button(context, R.string.remote_trust_open_console);
        binding.permission = button(context, R.string.remote_trust_file_permission);
        utilityRow.addView(binding.open, weight());
        utilityRow.addView(binding.permission, leftWeight(context));
        root.addView(utilityRow, topMargin(matchWrap(), 8));

        root.addView(divider(context), topMargin(matchWrap(), 14));
        root.addView(sectionTitle(context, R.string.remote_trust_section_manage), topMargin(matchWrap(), 14));
        root.addView(caption(context, R.string.remote_trust_section_manage_hint), topMargin(matchWrap(), 4));

        binding.bindCodeInput = input(context, InputType.TYPE_CLASS_NUMBER, true);
        root.addView(inputLayout(context, R.string.remote_trust_bind_code, binding.bindCodeInput), topMargin(matchWrap(), 10));

        binding.alias = input(context, InputType.TYPE_CLASS_TEXT, true);
        root.addView(inputLayout(context, R.string.remote_trust_device_alias, binding.alias), topMargin(matchWrap(), 8));

        LinearLayoutCompat manageRow = row(context);
        binding.addDevice = button(context, R.string.remote_trust_add_device);
        binding.refreshDevices = button(context, R.string.remote_trust_refresh_devices);
        manageRow.addView(binding.addDevice, weight());
        manageRow.addView(binding.refreshDevices, leftWeight(context));
        root.addView(manageRow, topMargin(matchWrap(), 10));

        binding.devices = new LinearLayoutCompat(context);
        binding.devices.setOrientation(LinearLayoutCompat.VERTICAL);
        root.addView(binding.devices, topMargin(matchWrap(), 10));

        binding.selected = text(context, "", 13, "#3C4043", true);
        root.addView(binding.selected, topMargin(matchWrap(), 10));

        binding.searchWord = input(context, InputType.TYPE_CLASS_TEXT, true);
        root.addView(inputLayout(context, R.string.remote_trust_search_keyword, binding.searchWord), topMargin(matchWrap(), 8));

        binding.pushUrl = input(context, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI, true);
        root.addView(inputLayout(context, R.string.remote_trust_push_url, binding.pushUrl), topMargin(matchWrap(), 8));

        LinearLayoutCompat commandRow1 = row(context);
        binding.statusAction = button(context, R.string.remote_trust_action_status);
        binding.searchAction = button(context, R.string.remote_trust_action_search);
        commandRow1.addView(binding.statusAction, weight());
        commandRow1.addView(binding.searchAction, leftWeight(context));
        root.addView(commandRow1, topMargin(matchWrap(), 10));

        LinearLayoutCompat commandRow2 = row(context);
        binding.pushAction = button(context, R.string.remote_trust_action_push);
        binding.logAction = button(context, R.string.remote_trust_action_log);
        commandRow2.addView(binding.pushAction, weight());
        commandRow2.addView(binding.logAction, leftWeight(context));
        root.addView(commandRow2, topMargin(matchWrap(), 8));

        binding.result = text(context, "", 12, "#5F6368", false);
        binding.result.setTextIsSelectable(true);
        root.addView(binding.result, topMargin(matchWrap(), 8));

        binding.clear = button(context, R.string.remote_trust_clear);
        root.addView(binding.clear, topMargin(matchWrap(), 14));
        return binding;
    }

    private static void render(Context context, Binding binding) {
        RemoteProfile profile = currentProfile(binding);
        if (profile == null) profile = RemoteStore.firstProfile();
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
        renderDevices(context, binding, currentProfile(binding));
        if (binding.callback != null) binding.callback.run();
    }

    private static void renderDevices(Context context, Binding binding, RemoteProfile profile) {
        binding.devices.removeAllViews();
        List<DeviceRow> rows = deviceRows(profile);
        if (rows.isEmpty()) {
            binding.devices.addView(caption(context, R.string.remote_trust_no_devices), matchWrap());
            binding.selected.setText("");
            return;
        }
        if (TextUtils.isEmpty(binding.selectedDeviceId) || findRow(rows, binding.selectedGroupId, binding.selectedDeviceId) == null) {
            DeviceRow first = rows.get(0);
            binding.selectedGroupId = first.group.groupId;
            binding.selectedDeviceId = first.device.deviceId;
        }
        for (DeviceRow row : rows) {
            MaterialButton item = button(context, deviceText(context, profile, row.group, row.device));
            item.setCheckable(true);
            item.setTextAlignment(View.TEXT_ALIGNMENT_TEXT_START);
            item.setChecked(TextUtils.equals(binding.selectedGroupId, row.group.groupId) && TextUtils.equals(binding.selectedDeviceId, row.device.deviceId));
            item.setOnClickListener(v -> {
                binding.selectedGroupId = row.group.groupId;
                binding.selectedDeviceId = row.device.deviceId;
                renderDevices(context, binding, profile);
            });
            binding.devices.addView(item, topMargin(matchWrap(), 6));
        }
        DeviceRow selected = findRow(rows, binding.selectedGroupId, binding.selectedDeviceId);
        String name = selected == null ? "" : (TextUtils.isEmpty(selected.device.name) ? shortId(selected.device.deviceId) : selected.device.name);
        binding.selected.setText(selected == null ? "" : context.getString(R.string.remote_trust_selected_device, name));
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

    private static void addDevice(FragmentActivity activity, Binding binding) {
        RemoteProfile profile;
        try {
            profile = prepare(binding);
        } catch (Throwable e) {
            Notify.show(e.getMessage());
            return;
        }
        String code = textOf(binding.bindCodeInput);
        if (TextUtils.isEmpty(code)) {
            Notify.show(R.string.remote_trust_code_required);
            return;
        }
        String alias = textOf(binding.alias);
        String groupToken = firstGroupToken(profile);
        setBusy(binding, true);
        Task.execute(() -> {
            try {
                RemoteClient client = new RemoteClient(profile);
                client.register();
                ClaimResponse response = client.claim(code, groupToken, alias);
                RemoteGroup group = RemoteStore.upsertClaimGroup(profile.serverOrigin, response, alias);
                RemoteProfile updated = RemoteStore.getProfileByOrigin(profile.serverOrigin);
                if (updated != null) {
                    RemoteClient updatedClient = new RemoteClient(updated);
                    updatedClient.register();
                    if (group != null) refreshGroup(updatedClient, updated.serverOrigin, group);
                }
                RemoteAgent.get().start();
                App.post(() -> {
                    setBusy(binding, false);
                    if (response != null) {
                        binding.selectedGroupId = response.groupId;
                        binding.selectedDeviceId = response.deviceId;
                    }
                    binding.bindCodeInput.setText("");
                    Notify.show(R.string.remote_trust_add_done);
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

    private static void refreshDevices(FragmentActivity activity, Binding binding) {
        RemoteProfile profile;
        try {
            profile = prepare(binding);
        } catch (Throwable e) {
            Notify.show(e.getMessage());
            return;
        }
        if (profile.groups == null || profile.groups.isEmpty()) {
            Notify.show(R.string.remote_trust_no_group);
            return;
        }
        setBusy(binding, true);
        Task.execute(() -> {
            try {
                RemoteClient client = new RemoteClient(profile);
                client.register();
                int count = 0;
                for (RemoteGroup group : new ArrayList<>(profile.groups)) count += refreshGroup(client, profile.serverOrigin, group);
                RemoteAgent.get().start();
                int refreshed = count;
                App.post(() -> {
                    setBusy(binding, false);
                    Notify.show(activity.getString(R.string.remote_trust_devices_refreshed, refreshed));
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

    private static int refreshGroup(RemoteClient client, String serverOrigin, RemoteGroup group) throws Exception {
        DevicesResponse response = client.listDevices(group);
        List<RemoteDevice> devices = response == null ? new ArrayList<>() : response.devices;
        RemoteStore.upsertDevices(serverOrigin, group.groupId, devices);
        return devices == null ? 0 : devices.size();
    }

    private static void sendCommand(FragmentActivity activity, Binding binding, String type) {
        RemoteProfile profile = currentProfile(binding);
        if (profile == null) {
            Notify.show(R.string.remote_trust_no_profile);
            return;
        }
        RemoteGroup group = selectedGroup(profile, binding.selectedGroupId);
        RemoteDevice device = selectedDevice(group, binding.selectedDeviceId);
        if (group == null || device == null) {
            Notify.show(R.string.remote_trust_no_device_selected);
            return;
        }
        JsonObject payload = payloadFor(activity, binding, type);
        if (payload == null) return;
        setBusy(binding, true);
        binding.result.setText("");
        Task.execute(() -> {
            try {
                RemoteClient client = new RemoteClient(profile);
                CommandResponse response = client.createCommand(group, device.deviceId, type, payload);
                String commandId = response == null ? "" : response.commandId;
                if (TextUtils.isEmpty(commandId) && response != null && response.command != null) commandId = response.command.id;
                RemoteCommand command = waitCommand(client, group, commandId, response == null ? null : response.command);
                String result = formatCommand(activity, type, command);
                App.post(() -> {
                    setBusy(binding, false);
                    binding.result.setText(result);
                    renderDevices(activity, binding, currentProfile(binding));
                });
            } catch (Throwable e) {
                App.post(() -> {
                    setBusy(binding, false);
                    binding.result.setText(e.getMessage());
                    Notify.show(e.getMessage());
                });
            }
        });
    }

    private static JsonObject payloadFor(Context context, Binding binding, String type) {
        JsonObject payload = new JsonObject();
        if ("action.search".equals(type)) {
            String word = textOf(binding.searchWord);
            if (TextUtils.isEmpty(word)) {
                Notify.show(context.getString(R.string.remote_trust_search_keyword));
                return null;
            }
            payload.addProperty("word", word);
        } else if ("action.push".equals(type)) {
            String url = textOf(binding.pushUrl);
            if (TextUtils.isEmpty(url)) {
                Notify.show(context.getString(R.string.remote_trust_push_url));
                return null;
            }
            payload.addProperty("url", url);
        } else if ("log.recent".equals(type)) {
            payload.addProperty("limit", 200);
        }
        return payload;
    }

    private static RemoteCommand waitCommand(RemoteClient client, RemoteGroup group, String commandId, RemoteCommand fallback) throws Exception {
        if (TextUtils.isEmpty(commandId)) return fallback;
        RemoteCommand command = fallback;
        for (int i = 0; i < 8; i++) {
            Thread.sleep(i == 0 ? 700 : 1000);
            CommandDetailResponse detail = client.getCommand(group, commandId);
            if (detail != null && detail.command != null) command = detail.command;
            if (command != null && ("done".equals(command.status) || "failed".equals(command.status))) break;
        }
        return command;
    }

    private static String formatCommand(Context context, String type, RemoteCommand command) {
        if (command == null) return context.getString(R.string.remote_trust_empty_result);
        StringBuilder builder = new StringBuilder();
        builder.append(context.getString(R.string.remote_trust_command_result, type, TextUtils.isEmpty(command.status) ? "queued" : command.status));
        RemoteCommandResult result = command.result;
        if (result == null) {
            builder.append('\n').append(context.getString(R.string.remote_trust_command_waiting));
            return builder.toString();
        }
        builder.append('\n').append(result.ok ? context.getString(R.string.remote_trust_command_success) : context.getString(R.string.remote_trust_command_failed));
        if (!TextUtils.isEmpty(result.message)) builder.append(": ").append(result.message);
        String data = formatData(result.data);
        if (!TextUtils.isEmpty(data)) builder.append('\n').append(data);
        return builder.toString();
    }

    private static String formatData(JsonElement data) {
        if (data == null || data.isJsonNull()) return "";
        if (data.isJsonObject()) {
            JsonObject object = data.getAsJsonObject();
            if (object.has("lines") && object.get("lines").isJsonArray()) return lines(object.getAsJsonArray("lines"));
        }
        return App.gson().toJson(data);
    }

    private static String lines(JsonArray array) {
        StringBuilder builder = new StringBuilder();
        int start = Math.max(0, array.size() - 80);
        for (int i = start; i < array.size(); i++) {
            if (builder.length() > 0) builder.append('\n');
            builder.append(array.get(i).getAsString());
        }
        return builder.toString();
    }

    private static RemoteProfile prepare(Binding binding) {
        String serverUrl = textOf(binding.server);
        return RemoteStore.prepareProfile(serverUrl, binding.enabled.isChecked(), binding.keepOnline.isChecked());
    }

    private static RemoteProfile currentProfile(Binding binding) {
        String serverUrl = textOf(binding.server);
        if (TextUtils.isEmpty(serverUrl)) return RemoteStore.firstProfile();
        String origin = RemoteTokens.normalizeOrigin(serverUrl);
        return TextUtils.isEmpty(origin) ? null : RemoteStore.getProfileByOrigin(origin);
    }

    private static void openService(FragmentActivity activity, Binding binding) {
        String origin = RemoteTokens.normalizeOrigin(textOf(binding.server));
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
                    binding.selectedGroupId = "";
                    binding.selectedDeviceId = "";
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

    private static List<DeviceRow> deviceRows(RemoteProfile profile) {
        List<DeviceRow> rows = new ArrayList<>();
        if (profile == null || profile.groups == null) return rows;
        for (RemoteGroup group : profile.groups) {
            if (group == null || group.devices == null) continue;
            for (RemoteDevice device : group.devices) {
                if (device != null && !TextUtils.isEmpty(device.deviceId)) rows.add(new DeviceRow(group, device));
            }
        }
        return rows;
    }

    private static DeviceRow findRow(List<DeviceRow> rows, String groupId, String deviceId) {
        for (DeviceRow row : rows) {
            if (TextUtils.equals(row.group.groupId, groupId) && TextUtils.equals(row.device.deviceId, deviceId)) return row;
        }
        return null;
    }

    private static RemoteGroup selectedGroup(RemoteProfile profile, String groupId) {
        if (profile == null || profile.groups == null) return null;
        for (RemoteGroup group : profile.groups) if (group != null && TextUtils.equals(group.groupId, groupId)) return group;
        return null;
    }

    private static RemoteDevice selectedDevice(RemoteGroup group, String deviceId) {
        if (group == null || group.devices == null) return null;
        for (RemoteDevice device : group.devices) if (device != null && TextUtils.equals(device.deviceId, deviceId)) return device;
        return null;
    }

    private static String firstGroupToken(RemoteProfile profile) {
        if (profile == null || profile.groups == null) return "";
        for (RemoteGroup group : profile.groups) if (group != null && !TextUtils.isEmpty(group.groupToken)) return group.groupToken;
        return "";
    }

    private static String deviceText(Context context, RemoteProfile profile, RemoteGroup group, RemoteDevice device) {
        String name = TextUtils.isEmpty(device.name) ? shortId(device.deviceId) : device.name;
        if (profile != null && TextUtils.equals(profile.deviceId, device.deviceId)) name += " · " + context.getString(R.string.remote_trust_self_device);
        String status = device.online ? context.getString(R.string.remote_trust_device_online) : context.getString(R.string.remote_trust_device_offline);
        String time = device.lastSeen <= 0 ? "" : " · " + new SimpleDateFormat("MM-dd HH:mm", Locale.ROOT).format(new Date(device.lastSeen));
        String groupName = TextUtils.isEmpty(group.name) ? context.getString(R.string.remote_trust_group_title, shortId(group.groupId)) : group.name;
        return name + " · " + status + time + "\n" + groupName + " · " + shortId(device.deviceId);
    }

    private static String shortId(String value) {
        if (TextUtils.isEmpty(value)) return "";
        return value.length() <= 8 ? value : value.substring(value.length() - 8);
    }

    private static String textOf(TextInputEditText input) {
        return input == null || input.getText() == null ? "" : input.getText().toString().trim();
    }

    private static void setBusy(Binding binding, boolean busy) {
        binding.save.setEnabled(!busy);
        binding.bind.setEnabled(!busy);
        binding.open.setEnabled(!busy);
        binding.clear.setEnabled(!busy);
        binding.permission.setEnabled(!busy);
        binding.addDevice.setEnabled(!busy);
        binding.refreshDevices.setEnabled(!busy);
        binding.statusAction.setEnabled(!busy);
        binding.searchAction.setEnabled(!busy);
        binding.pushAction.setEnabled(!busy);
        binding.logAction.setEnabled(!busy);
    }

    private static LinearLayoutCompat row(Context context) {
        LinearLayoutCompat row = new LinearLayoutCompat(context);
        row.setOrientation(LinearLayoutCompat.HORIZONTAL);
        return row;
    }

    private static MaterialTextView sectionTitle(Context context, int resId) {
        MaterialTextView view = text(context, context.getString(resId), 15, "#202124", true);
        view.setTypeface(view.getTypeface(), Typeface.BOLD);
        return view;
    }

    private static MaterialTextView caption(Context context, int resId) {
        return text(context, context.getString(resId), 12, "#5F6368", false);
    }

    private static MaterialTextView text(Context context, String value, int sp, String color, boolean bold) {
        MaterialTextView view = new MaterialTextView(context);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(Color.parseColor(color));
        if (bold) view.setTypeface(view.getTypeface(), Typeface.BOLD);
        return view;
    }

    private static TextInputEditText input(Context context, int inputType, boolean singleLine) {
        TextInputEditText input = new TextInputEditText(context);
        input.setInputType(inputType);
        input.setSingleLine(singleLine);
        return input;
    }

    private static TextInputLayout inputLayout(Context context, int hint, TextInputEditText input) {
        TextInputLayout layout = new TextInputLayout(context);
        layout.setHint(context.getString(hint));
        layout.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_OUTLINE);
        layout.addView(input, matchWrap());
        return layout;
    }

    private static com.google.android.material.checkbox.MaterialCheckBox check(Context context, int resId) {
        com.google.android.material.checkbox.MaterialCheckBox box = new com.google.android.material.checkbox.MaterialCheckBox(context);
        box.setText(resId);
        return box;
    }

    private static MaterialButton button(Context context, int resId) {
        return button(context, context.getString(resId));
    }

    private static MaterialButton button(Context context, String text) {
        MaterialButton button = new MaterialButton(context);
        button.setText(text);
        button.setAllCaps(false);
        button.setMinHeight(dp(context, 42));
        button.setMaxLines(3);
        button.setEllipsize(TextUtils.TruncateAt.END);
        return button;
    }

    private static View divider(Context context) {
        View view = new View(context);
        view.setBackgroundColor(Color.parseColor("#E8EAED"));
        view.setMinimumHeight(dp(context, 1));
        return view;
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

    private static final class DeviceRow {
        private final RemoteGroup group;
        private final RemoteDevice device;

        private DeviceRow(RemoteGroup group, RemoteDevice device) {
            this.group = group;
            this.device = device;
        }
    }

    private static final class Binding {
        private NestedScrollView scroll;
        private AlertDialog dialog;
        private Runnable callback;
        private TextInputEditText server;
        private TextInputEditText bindCodeInput;
        private TextInputEditText alias;
        private TextInputEditText searchWord;
        private TextInputEditText pushUrl;
        private com.google.android.material.checkbox.MaterialCheckBox enabled;
        private com.google.android.material.checkbox.MaterialCheckBox keepOnline;
        private MaterialTextView status;
        private MaterialTextView code;
        private MaterialTextView hint;
        private MaterialTextView selected;
        private MaterialTextView result;
        private LinearLayoutCompat devices;
        private MaterialButton save;
        private MaterialButton bind;
        private MaterialButton open;
        private MaterialButton permission;
        private MaterialButton clear;
        private MaterialButton addDevice;
        private MaterialButton refreshDevices;
        private MaterialButton statusAction;
        private MaterialButton searchAction;
        private MaterialButton pushAction;
        private MaterialButton logAction;
        private String bindCode = "";
        private String selectedGroupId = "";
        private String selectedDeviceId = "";
    }
}
