package com.fongmi.android.tv;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.startup.Initializer;

import com.fongmi.android.tv.event.EventIndex;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.ui.activity.CrashActivity;
import com.github.catvod.bean.Doh;
import com.github.catvod.net.OkHttp;
import com.orhanobut.logger.AndroidLogAdapter;
import com.orhanobut.logger.Logger;
import com.orhanobut.logger.PrettyFormatStrategy;

import org.greenrobot.eventbus.EventBus;

import java.util.Collections;
import java.util.List;

import cat.ereza.customactivityoncrash.config.CaocConfig;

public class Startup implements Initializer<Void> {

    @NonNull
    @Override
    public Void create(@NonNull Context context) {
        // :probe 探测子进程不安装崩溃恢复：探测到恶意源时直接让子进程死亡，
        // 由主进程 Binder 死亡通知判定危险，避免触发 CrashActivity / 应用重启
        if (App.isProbeProcess()) return null;
        CaocConfig.Builder.create().trackActivities(true).backgroundMode(CaocConfig.BACKGROUND_MODE_SILENT).errorActivity(CrashActivity.class).apply();
        // 覆盖于 CustomActivityOnCrash 之上：免疫第三方 spider.jar Init 子线程崩溃并永久屏蔽肇事路线
        com.fongmi.android.tv.api.JarCrashShield.install();
        // 常驻「自杀哨兵」：System.exit 不是异常，崩溃处理器抓不到，靠它记录调用栈定位重启来源
        com.fongmi.android.tv.utils.DiagLog.installExitSentinel();
        Logger.addLogAdapter(new AndroidLogAdapter(PrettyFormatStrategy.newBuilder().methodCount(0).showThreadInfo(false).tag("TV").build()));
        EventBus.builder().addIndex(new EventIndex()).installDefaultEventBus();
        OkHttp.dns().setDoh(() -> Doh.objectFrom(Setting.getDoh()));
        return null;
    }

    @NonNull
    @Override
    public List<Class<? extends Initializer<?>>> dependencies() {
        return Collections.emptyList();
    }
}
