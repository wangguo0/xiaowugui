package com.fongmi.android.tv;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.Application;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.os.HandlerCompat;

import com.fongmi.android.tv.server.Server;
import com.fongmi.android.tv.playback.PlaybackRemoteSyncer;
import com.fongmi.android.tv.player.PlaybackMemoryMonitor;
import com.fongmi.android.tv.player.PlaybackSystemConditionMonitor;
import com.fongmi.android.tv.remote.RemoteAgent;
import com.fongmi.android.tv.setting.ProxySetting;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.utils.DanmakuSearchListFocusFixer;
import com.fongmi.android.tv.utils.DiagLog;
import com.fongmi.android.tv.utils.NsdDeviceDiscovery;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.PreviousProcessExitLogger;
import com.fongmi.hook.Hook;
import com.github.catvod.crawler.DebugLogStore;
import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.Init;
import com.google.gson.Gson;

public class App extends Application implements Application.ActivityLifecycleCallbacks {

    private static volatile App instance;

    private final Handler handler;
    private final Gson gson;
    private final long time;

    private Activity activity;
    private Hook hook;
    private String processName = "";

    public App() {
        instance = this;
        gson = new Gson();
        time = System.currentTimeMillis();
        handler = HandlerCompat.createAsync(Looper.getMainLooper());
    }

    public static App get() {
        return instance;
    }

    public static Gson gson() {
        return get().gson;
    }

    public static long time() {
        return get().time;
    }

    public static Activity activity() {
        return get().activity;
    }

    public static void post(Runnable runnable) {
        get().handler.post(runnable);
    }

    public static void post(Runnable runnable, long delayMillis) {
        get().handler.removeCallbacks(runnable);
        if (delayMillis >= 0) get().handler.postDelayed(runnable, delayMillis);
    }

    public static void removeCallbacks(Runnable runnable) {
        get().handler.removeCallbacks(runnable);
    }

    public static void removeCallbacks(Runnable... runnable) {
        for (Runnable r : runnable) get().handler.removeCallbacks(r);
    }

    public void setHook(Hook hook) {
        this.hook = hook;
    }

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        Init.set(base);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        processName = currentProcessName();
        if (!isProbeProcess()) DiagLog.init(this);
        // 主进程启动早期（任何 Activity 加载配置之前）消费「25 秒运行试用」遗留标记：
        // 上次试用源若导致崩溃，此处删除该源并恢复原激活配置，避免启动即崩循环
        if (!isProbeProcess()) com.fongmi.android.tv.api.TrialRun.checkOnStartup(this);
        PlaybackMemoryMonitor.process().initialize(this);
        PlaybackSystemConditionMonitor.process().initialize(this);
        Setting.applyLanguage();
        DebugLogStore.restoreEnabled();
        if (DebugLogStore.isEnabled()) {
            Setting.logDebugEnvironment("restore");
            PreviousProcessExitLogger.log(this);
        }
        Notify.createChannel();
        ProxySetting.apply();
        DanmakuSearchListFocusFixer.start();
        com.fongmi.android.tv.api.PopupShield.install(this);
        registerActivityLifecycleCallbacks(this);
        // :probe 探测子进程仅执行沙箱探测，不启动后台服务，避免端口/路由冲突
        if (!isProbeProcess()) post(this::startBackgroundServices, 1200);
    }

    public static boolean isProbeProcess() {
        String name = get().processName;
        if (android.text.TextUtils.isEmpty(name)) {
            name = get().currentProcessName();
            get().processName = name;
        }
        return name.endsWith(":probe");
    }

    private String currentProcessName() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) return Application.getProcessName();
            int pid = android.os.Process.myPid();
            ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null) return "";
            for (ActivityManager.RunningAppProcessInfo info : am.getRunningAppProcesses()) {
                if (info != null && info.pid == pid) return info.processName;
            }
        } catch (Throwable ignored) {
        }
        return "";
    }

    @Override
    public void onTrimMemory(int level) {
        PlaybackMemoryMonitor.process().onTrimMemory(level);
        super.onTrimMemory(level);
    }

    @Override
    public void onLowMemory() {
        PlaybackMemoryMonitor.process().onLowMemory();
        super.onLowMemory();
    }

    private void startBackgroundServices() {
        SpiderDebug.log("startup", "background services start cost=%sms", System.currentTimeMillis() - time);
        Server.get().start();
        PlaybackRemoteSyncer.start();
        RemoteAgent.get().start();
        NsdDeviceDiscovery.register();
        SpiderDebug.log("startup", "background services ready cost=%sms", System.currentTimeMillis() - time);
    }

    @Override
    public PackageManager getPackageManager() {
        return hook != null ? hook : getBaseContext().getPackageManager();
    }

    @Override
    public String getPackageName() {
        return hook != null ? hook.getPackageName() : getBaseContext().getPackageName();
    }

    @Override
    public void onActivityResumed(@NonNull Activity activity) {
        if (DiagLog.isEnabled()) DiagLog.log("activity", "resume " + activity.getClass().getSimpleName());
        if (activity != activity()) this.activity = activity;
    }

    @Override
    public void onActivityPaused(@NonNull Activity activity) {
        if (activity == activity()) this.activity = null;
    }

    @Override
    public void onActivityCreated(@NonNull Activity activity, @Nullable Bundle savedInstanceState) {
    }

    @Override
    public void onActivityDestroyed(@NonNull Activity activity) {
    }

    @Override
    public void onActivitySaveInstanceState(@NonNull Activity activity, @NonNull Bundle outState) {
    }

    @Override
    public void onActivityStarted(@NonNull Activity activity) {
    }

    @Override
    public void onActivityStopped(@NonNull Activity activity) {
    }
}
