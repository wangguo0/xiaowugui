package com.fongmi.android.tv.utils;

import android.Manifest;
import android.app.Notification;
import android.content.Context;
import android.content.pm.PackageManager;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.core.app.NotificationChannelCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.databinding.ViewProgressBinding;
import com.fongmi.android.tv.setting.Setting;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Set;

public class Notify {

    public static final String DEFAULT = "default";
    // 应用内更新「新版本已就绪」安装通知渠道（高优先级）
    public static final String UPDATE = "update";
    // 更新下载前台服务进度通知渠道（静默低优先级）
    public static final String DOWNLOAD = "download";
    public static final int ID = 9527;
    // 本应用自身弹过的 toast 文本，供窗口级拦截区分「自己人」与源内（jar/ext）弹出的提示
    private static final Set<String> RECENT_TOASTS = new LinkedHashSet<>();
    private AlertDialog mDialog;
    private Toast mToast;

    private static class Loader {
        static volatile Notify INSTANCE = new Notify();
    }

    private static Notify get() {
        return Loader.INSTANCE;
    }

    public static void createChannel() {
        NotificationManagerCompat notifyMgr = NotificationManagerCompat.from(App.get());
        notifyMgr.createNotificationChannel(new NotificationChannelCompat.Builder(DEFAULT, NotificationManagerCompat.IMPORTANCE_LOW).setName("TV").build());
    }

    // 更新送装通知渠道：高优先级，幂等创建（系统对重复创建同名渠道无副作用）
    public static void createUpdateChannel() {
        NotificationManagerCompat notifyMgr = NotificationManagerCompat.from(App.get());
        notifyMgr.createNotificationChannel(new NotificationChannelCompat.Builder(UPDATE, NotificationManagerCompat.IMPORTANCE_HIGH).setName("更新").build());
    }

    public static String getError(int resId, Throwable e) {
        if (TextUtils.isEmpty(e.getMessage())) return ResUtil.getString(resId);
        return ResUtil.getString(resId) + "\n" + e.getMessage();
    }

    public static void show(Notification notification) {
        if (ContextCompat.checkSelfPermission(App.get(), Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return;
        NotificationManagerCompat.from(App.get()).notify(ID, notification);
    }

    public static void show(int resId) {
        if (resId != 0) show(ResUtil.getString(resId));
    }

    public static void show(String text) {
        if (!TextUtils.isEmpty(text)) get().makeText(text);
    }

    // 接口返回的 toast 专用入口：开启「拦截源提示」时直接丢弃，不影响本地操作提示
    public static void showNotice(String text) {
        if (Setting.isBlockNotice()) return;
        if (!TextUtils.isEmpty(text)) get().makeText(text);
    }

    public static void progress(Context context) {
        dismiss();
        get().create(context);
    }

    public static void dismiss() {
        try {
            if (get().mDialog != null) get().mDialog.dismiss();
        } catch (Exception ignored) {
        }
    }

    public static void dismissToast() {
        try {
            if (get().mToast != null) get().mToast.cancel();
        } catch (Exception ignored) {
        }
    }

    private void create(Context context) {
        ViewProgressBinding binding = ViewProgressBinding.inflate(LayoutInflater.from(context));
        mDialog = new MaterialAlertDialogBuilder(context).setView(binding.getRoot()).create();
        mDialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        mDialog.show();
    }

    private void makeText(String text) {
        remember(text);
        if (mToast != null) mToast.cancel();
        mToast = Toast.makeText(App.get(), text, Toast.LENGTH_LONG);
        mToast.show();
    }

    // 文本是否属于本应用最近弹出的 toast（窗口级拦截放行依据）
    public static boolean isRecent(String text) {
        if (TextUtils.isEmpty(text)) return false;
        synchronized (RECENT_TOASTS) {
            return RECENT_TOASTS.contains(text);
        }
    }

    private static void remember(String text) {
        synchronized (RECENT_TOASTS) {
            if (RECENT_TOASTS.size() >= 16) {
                Iterator<String> iterator = RECENT_TOASTS.iterator();
                iterator.next();
                iterator.remove();
            }
            RECENT_TOASTS.add(text);
        }
    }
}
