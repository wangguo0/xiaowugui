package com.fongmi.android.tv.service;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.text.format.Formatter;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationChannelCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.app.ServiceCompat;
import androidx.core.content.ContextCompat;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.ResUtil;

// 更新下载前台服务：下载全程把进程钉在前台优先级，杜绝切后台被系统/OEM回收导致下载中断；
// 通知栏常驻「正在下载新版本」进度条，点按回到应用续接下载弹窗
public class UpdateDownloadService extends Service {

    private static final String ACTION_START = "update_download_start";
    private static final String ACTION_STOP = "update_download_stop";
    // 独立通知 id：避开 Notify.ID(9527)、安装就绪(9528)、ManageService(9529)
    private static final int ID = 9530;
    private static volatile boolean running;

    public static void start(Context context) {
        if (running) return;
        ContextCompat.startForegroundService(context, new Intent(context, UpdateDownloadService.class).setAction(ACTION_START));
    }

    // 服务已处于 started 状态，后台调用 startService 传递停止指令合法
    public static void stop(Context context) {
        if (!running) return;
        running = false;
        try {
            context.startService(new Intent(context, UpdateDownloadService.class).setAction(ACTION_STOP));
        } catch (Exception ignored) {
        }
    }

    // 下载进度刷新通知（Download 回调已按 1 秒/1% 节流，直接原地更新即可）
    public static void progress(long bytes, long total, long speed) {
        if (!running) return;
        try {
            NotificationManagerCompat.from(App.get()).notify(ID, build(bytes, total, speed));
        } catch (Exception ignored) {
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        createChannel();
        running = true;
        Notification notification = build(0, 0, 0);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(ID, notification);
        }
        return START_NOT_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        running = false;
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE);
    }

    private void createChannel() {
        NotificationManagerCompat.from(this).createNotificationChannel(new NotificationChannelCompat.Builder(Notify.DOWNLOAD, NotificationManagerCompat.IMPORTANCE_LOW).setName(ResUtil.getString(R.string.update_download_channel)).build());
    }

    private static Notification build(long bytes, long total, long speed) {
        Intent launch = App.get().getPackageManager().getLaunchIntentForPackage(App.get().getPackageName());
        PendingIntent pending = null;
        if (launch != null) {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) flags |= PendingIntent.FLAG_IMMUTABLE;
            pending = PendingIntent.getActivity(App.get(), 0, launch, flags);
        }
        NotificationCompat.Builder builder = new NotificationCompat.Builder(App.get(), Notify.DOWNLOAD)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(ResUtil.getString(R.string.update_download_title))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setContentIntent(pending);
        if (total > 0) {
            int percent = (int) (bytes * 100 / total);
            builder.setProgress(100, percent, false).setContentText(percent + "% · " + size(bytes) + " / " + size(total) + " · " + size(speed) + "/s");
        } else {
            builder.setProgress(0, 0, true).setContentText(size(bytes) + " · " + size(speed) + "/s");
        }
        return builder.build();
    }

    private static String size(long bytes) {
        return Formatter.formatShortFileSize(App.get(), Math.max(0, bytes));
    }
}
