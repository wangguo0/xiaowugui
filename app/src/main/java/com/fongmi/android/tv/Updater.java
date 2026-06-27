package com.fongmi.android.tv;

import android.text.TextUtils;
import android.view.View;

import androidx.fragment.app.FragmentActivity;

import com.fongmi.android.tv.bean.Update;
import com.fongmi.android.tv.impl.UpdateListener;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.ui.dialog.UpdateDialog;
import com.fongmi.android.tv.utils.Download;
import com.fongmi.android.tv.utils.FileUtil;
import com.fongmi.android.tv.utils.Github;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.Task;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Path;

import org.json.JSONObject;

import java.io.File;

public class Updater implements Download.Callback, UpdateListener {

    private UpdateDialog dialog;
    private Download download;
    private Update stable;
    private Update beta;
    private Update selected;
    private boolean force;

    private Updater() {
    }

    public static Updater create() {
        return new Updater();
    }

    private File getFile() {
        return Path.cache("update.apk");
    }

    private String getName() {
        return BuildConfig.FLAVOR_mode + "-" + BuildConfig.FLAVOR_abi;
    }

    private String getJson(String channel) {
        return Github.getJson(getName(), channel);
    }

    public Updater force() {
        force = true;
        Notify.show(R.string.update_check);
        Setting.putUpdate(true);
        return this;
    }

    public void start(FragmentActivity activity) {
        if (!Setting.getUpdate()) return;
        Task.execute(() -> doInBackground(activity));
    }

    private void doInBackground(FragmentActivity activity) {
        stable = getUpdate(Update.CHANNEL_STABLE);
        beta = getUpdate(Update.CHANNEL_BETA);
        if (!stable.hasUpdate() && !beta.hasUpdate()) {
            if (force) App.post(() -> Notify.show(hasErrorOnly() ? R.string.update_failed : R.string.update_latest));
            return;
        }
        selected = selectUpdate();
        App.post(() -> show(activity));
    }

    private Update getUpdate(String channel) {
        Update update = Update.empty(channel);
        try {
            JSONObject object = new JSONObject(OkHttp.string(getJson(channel)));
            update.name = object.optString("name");
            update.desc = object.optString("desc");
            update.notes = object.optString("notes");
            update.channel = object.optString("channel", channel);
            update.code = object.optInt("code");
            update.apk = object.optString("apk");
            update.size = object.optLong("size");
            update.apkUrl = getApkUrl(update);
            if (TextUtils.isEmpty(update.notes)) update.notes = getReleaseNotes(update.name);
        } catch (Exception e) {
            e.printStackTrace();
            update.error = e.getMessage();
        }
        return update;
    }

    private String getApkUrl(Update update) {
        if (TextUtils.isEmpty(update.apk)) return Github.getApk(getName(), update.channel);
        if (update.apk.startsWith("http://") || update.apk.startsWith("https://")) return update.apk;
        return Github.getAsset(update.apk, update.channel);
    }

    private String getReleaseNotes(String tag) {
        if (TextUtils.isEmpty(tag)) return "";
        try {
            return new JSONObject(OkHttp.string(Github.getReleaseApi(tag))).optString("body");
        } catch (Exception ignored) {
            return "";
        }
    }

    private boolean hasErrorOnly() {
        return !stable.hasManifest() && !beta.hasManifest() && (!TextUtils.isEmpty(stable.error) || !TextUtils.isEmpty(beta.error));
    }

    private Update selectUpdate() {
        String channel = Setting.getUpdateChannel();
        if (Update.CHANNEL_BETA.equals(channel) && beta.hasUpdate()) return beta;
        if (Update.CHANNEL_STABLE.equals(channel) && stable.hasUpdate()) return stable;
        if (stable.hasUpdate()) return stable;
        return beta;
    }

    private void show(FragmentActivity activity) {
        dismiss();
        dialog = UpdateDialog.create().stable(stable).beta(beta).selected(selected.channel).listener(this).show(activity);
    }

    @Override
    public void onConfirm(View view) {
        if (selected == null || !selected.hasUpdate()) {
            Notify.show(R.string.update_latest);
            return;
        }
        view.setEnabled(false);
        if (dialog != null) dialog.setProgress(0);
        download = Download.create(selected.apkUrl, getFile()).tag(selected.apkUrl);
        download.start(this);
    }

    @Override
    public void onCancel(View view) {
        Setting.putUpdate(false);
        if (download != null) download.cancel();
        dismiss();
    }

    @Override
    public void onChannel(String channel) {
        Setting.putUpdateChannel(channel);
        selected = Update.CHANNEL_BETA.equals(channel) ? beta : stable;
    }

    private void dismiss() {
        try {
            if (dialog != null) dialog.dismiss();
        } catch (Exception ignored) {
        }
    }

    @Override
    public void progress(int progress) {
        if (dialog != null) dialog.setProgress(progress);
    }

    @Override
    public void progress(int progress, long bytes, long total, long speed, long elapsed) {
        if (dialog != null) dialog.setProgress(progress, bytes, total, speed, elapsed);
    }

    @Override
    public void error(String msg) {
        Notify.show(msg);
        dismiss();
    }

    @Override
    public void success(File file) {
        FileUtil.openFile(file);
        dismiss();
    }
}
