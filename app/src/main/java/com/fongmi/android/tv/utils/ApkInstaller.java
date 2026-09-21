package com.fongmi.android.tv.utils;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Parcelable;
import android.provider.MediaStore;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

// 送装：把校验通过的 APK 交给系统安装器，并取回真实结果
// 旧实现只有 ACTION_VIEW + 私有缓存 content URI，既没有返回码，也无法摆脱安装器对路径/URI/MIME 的不确定处理
public final class ApkInstaller {

    private static final String LOG = "update";
    private static final String ACTION = "com.xiaowugui.ci.action.INSTALL_RESULT";
    public static final String APK_MIME = "application/vnd.android.package-archive";

    public interface Callback {

        // ok=true：安装器已受理（会话成功或已把确认界面交给用户）
        // ok=false：安装未完成，detail 是系统安装器的真实原因（状态码 + 消息）
        void onResult(boolean ok, String detail);
    }

    // 公共「下载」目录里的副本信息：既作为兜底送装对象，也是用户可手动安装的可见文件
    private static final class Copy {

        private final Uri uri;
        private final String path;

        private Copy(Uri uri, String path) {
            this.uri = uri;
            this.path = path;
        }
    }

    private ApkInstaller() {
    }

    public static void install(File apk, String name, Callback callback) {
        if (apk == null || !apk.exists() || apk.length() <= 0) {
            DiagLog.log(LOG, "[送装] 中止：安装包不存在");
            callback.onResult(false, ResUtil.getString(R.string.update_download_invalid));
            return;
        }
        Task.execute(() -> {
            Copy copy = publicCopy(apk, name);
            App.post(() -> {
                DiagLog.log(LOG, "[送装] 文件=%s 大小=%s 公共副本=%s", apk.getAbsolutePath(), apk.length(), copy == null ? "无" : copy.path);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !App.get().getPackageManager().canRequestPackageInstalls()) {
                    DiagLog.log(LOG, "[送装] 本应用未开启「安装未知应用」，直接转交系统安装器");
                    openWithSystem(apk, copy, callback, ResUtil.getString(R.string.update_install_permission));
                    return;
                }
                session(apk, copy, callback);
            });
        });
    }

    // 主路径：以字节流递交（不经过文件路径、content URI 与 MIME），因此能拿到安装器的真实状态码
    private static void session(File apk, Copy copy, Callback callback) {
        String action = ACTION + "." + System.currentTimeMillis();
        BroadcastReceiver receiver = new BroadcastReceiver() {

            @Override
            public void onReceive(Context context, Intent intent) {
                try {
                    context.unregisterReceiver(this);
                } catch (Exception ignored) {
                }
                int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
                String message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
                DiagLog.log(LOG, "[送装] 会话结果 status=%s msg=%s", status, message);
                if (status == PackageInstaller.STATUS_SUCCESS) {
                    callback.onResult(true, "");
                    return;
                }
                // 系统/ROM 要求用户确认：把确认界面转交给用户，不计为失败
                if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                    Parcelable extra = intent.getParcelableExtra(Intent.EXTRA_INTENT);
                    Intent confirm = extra instanceof Intent ? (Intent) extra : null;
                    if (confirm != null) {
                        confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        try {
                            App.get().startActivity(confirm);
                            DiagLog.log(LOG, "[送装] 等待用户确认安装");
                            callback.onResult(true, "");
                            return;
                        } catch (Exception e) {
                            DiagLog.log(LOG, "[送装] 确认界面无法打开 %s", e.getMessage());
                        }
                    }
                }
                openWithSystem(apk, copy, callback, describe(status, message));
            }
        };
        try {
            App.get().registerReceiver(receiver, new IntentFilter(action));
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) flags |= PendingIntent.FLAG_MUTABLE;
            Intent target = new Intent(action).setPackage(App.get().getPackageName());
            PendingIntent pending = PendingIntent.getBroadcast(App.get(), 0, target, flags);
            PackageInstaller installer = App.get().getPackageManager().getPackageInstaller();
            PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            params.setSize(apk.length());
            int id = installer.createSession(params);
            try (PackageInstaller.Session session = installer.openSession(id)) {
                try (OutputStream os = session.openWrite("base.apk", 0, apk.length()); InputStream is = new FileInputStream(apk)) {
                    copy(is, os);
                    session.fsync(os);
                }
                session.commit(pending.getIntentSender());
            }
            DiagLog.log(LOG, "[送装] 会话已提交 session=%s 字节=%s", id, apk.length());
        } catch (Exception e) {
            DiagLog.log(LOG, "[送装] 会话建立失败 %s", e.getMessage());
            openWithSystem(apk, copy, callback, String.valueOf(e.getMessage()));
        }
    }

    // 只构造「交给系统安装器」Intent 不启动：供通知点击入口使用——点通知属用户行为，Android 10+ 可合法拉起安装器
    public static Intent buildInstallIntent(File apk) {
        return installIntent(FileUtil.getShareUri(apk));
    }

    private static Intent installIntent(Uri uri) {
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.setDataAndType(uri, APK_MIME);
        intent.setClipData(android.content.ClipData.newRawUri("apk", uri));
        return intent;
    }

    // 兜底：用系统安装器打开「下载」目录里的副本（MIME 写死 + ClipData 授权，与浏览器下载路径等价）
    private static void openWithSystem(File apk, Copy copy, Callback callback, String detail) {
        Uri uri = copy == null ? FileUtil.getShareUri(apk) : copy.uri;
        Intent intent = installIntent(uri);
        try {
            App.get().startActivity(intent);
            DiagLog.log(LOG, "[送装] 已转交系统安装器 uri=%s 原因=%s", uri, detail);
        } catch (Exception e) {
            DiagLog.log(LOG, "[送装] 系统安装器不可用 %s", e.getMessage());
            detail = detail + " / " + e.getMessage();
        }
        callback.onResult(false, detail);
    }

    // 复制到公共「下载」目录：Android 10+ 走 MediaStore（无需存储权限），更低版本走公共目录直写
    private static Copy publicCopy(File apk, String name) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
                values.put(MediaStore.MediaColumns.MIME_TYPE, APK_MIME);
                values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                Uri uri = App.get().getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                if (uri == null) return null;
                OutputStream os = App.get().getContentResolver().openOutputStream(uri);
                if (os == null) return null;
                try (OutputStream output = os; InputStream is = new FileInputStream(apk)) {
                    copy(is, output);
                }
                return new Copy(uri, Environment.DIRECTORY_DOWNLOADS + "/" + name);
            }
            File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            if (dir == null) return null;
            if (!dir.exists() && !dir.mkdirs()) return null;
            File out = new File(dir, name);
            try (OutputStream os = new FileOutputStream(out); InputStream is = new FileInputStream(apk)) {
                copy(is, os);
            }
            return new Copy(FileUtil.getShareUri(out), Environment.DIRECTORY_DOWNLOADS + "/" + name);
        } catch (Exception e) {
            DiagLog.log(LOG, "[送装] 公共目录副本写入失败 %s", e.getMessage());
            return null;
        }
    }

    private static void copy(InputStream is, OutputStream os) throws IOException {
        byte[] buffer = new byte[16384];
        int read;
        while ((read = is.read(buffer)) != -1) os.write(buffer, 0, read);
        os.flush();
    }

    private static String describe(int status, String message) {
        return message == null ? ("status=" + status) : ("status=" + status + " " + message);
    }
}