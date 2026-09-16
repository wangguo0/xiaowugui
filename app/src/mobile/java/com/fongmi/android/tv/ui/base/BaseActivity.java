package com.fongmi.android.tv.ui.base;

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;

import android.content.Context;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.view.DisplayCutout;
import android.view.View;
import android.view.ViewGroup;

import androidx.activity.EdgeToEdge;
import androidx.activity.OnBackPressedCallback;
import androidx.activity.SystemBarStyle;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.FragmentActivity;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.Updater;
import com.fongmi.android.tv.event.RefreshEvent;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.ui.custom.CustomWallView;
import com.fongmi.android.tv.utils.ResUtil;
import com.google.android.material.color.DynamicColors;
import com.google.android.material.color.DynamicColorsOptions;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

public abstract class BaseActivity extends AppCompatActivity {

    protected abstract ViewBinding getBinding();

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(Setting.wrapDisplay(newBase));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        enableEdgeToEdge();
        enableDynamicColor();
        super.onCreate(savedInstanceState);
        setContentView(getBinding().getRoot());
        EventBus.getDefault().register(this);
        initView(savedInstanceState);
        setBackCallback();
        initEvent();
    }

    @Override
    public void setContentView(View view) {
        super.setContentView(view);
        if (!customWall()) return;
        addCustomWall();
    }

    private void addCustomWall() {
        ((ViewGroup) findViewById(android.R.id.content)).addView(new CustomWallView(this, null).setMotionEnabled(customWallMotion()), 0, new ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT));
    }

    protected FragmentActivity getActivity() {
        return this;
    }

    protected boolean customWall() {
        return true;
    }

    protected boolean customWallMotion() {
        return true;
    }

    protected void initView(Bundle savedInstanceState) {
    }

    protected void initEvent() {
    }

    protected boolean isVisible(View view) {
        return view.getVisibility() == View.VISIBLE;
    }

    protected boolean isGone(View view) {
        return view.getVisibility() == View.GONE;
    }

    protected void setPadding(ViewGroup layout) {
        setPadding(layout, false);
    }

    protected void setPadding(ViewGroup layout, boolean leftOnly) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return;
        DisplayCutout cutout = ResUtil.getDisplay(this).getCutout();
        if (cutout == null) return;
        int top = cutout.getSafeInsetTop();
        int left = cutout.getSafeInsetLeft();
        int right = cutout.getSafeInsetRight();
        int bottom = cutout.getSafeInsetBottom();
        int padding = left | right | top | bottom;
        layout.setPadding(padding, 0, leftOnly ? 0 : padding, 0);
    }

    protected void noPadding(ViewGroup layout) {
        layout.setPadding(0, 0, 0, 0);
    }

    private void setBackCallback() {
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                onBackInvoked();
            }
        });
    }

    private void enableEdgeToEdge() {
        EdgeToEdge.enable(this, SystemBarStyle.dark(Color.TRANSPARENT), SystemBarStyle.dark(Color.TRANSPARENT));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            getWindow().setStatusBarContrastEnforced(false);
            getWindow().setNavigationBarContrastEnforced(false);
        }
    }

    private void enableDynamicColor() {
        int color = Setting.getDynamicColor();
        if (color != 0) DynamicColors.applyToActivityIfAvailable(this, new DynamicColorsOptions.Builder().setContentBasedSource(color).build());
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onSubscribe(Object o) {
        if (o instanceof RefreshEvent event && event.getType() == RefreshEvent.Type.LANGUAGE) recreate();
    }

    protected void onBackInvoked() {
        finish();
    }

    @Override
    protected void onResume() {
        super.onResume();
        Updater.create().resume(this);
        showJarBlockNotice();
        showJarGuardNotice();
        showTrialNotice();
        showDexPatchNotice();
    }

    // 「中和杀进程指令」失败被拉黑后：在当前页面告知用户失败原因与处理结果
    private void showDexPatchNotice() {
        java.util.List<String[]> notices = com.fongmi.android.tv.api.DexPatch.takeNotice();
        if (notices == null || notices.isEmpty()) return;
        StringBuilder sb = new StringBuilder();
        StringBuilder urls = new StringBuilder();
        for (String[] n : notices) {
            if (sb.length() > 0) sb.append("\n\n");
            sb.append(dexPatchReason(n[1]));
            sb.append("\n").append(n[0]);
            if (urls.length() > 0) urls.append("、");
            urls.append(n[0]);
        }
        sb.append("\n\n").append(getString(R.string.dex_patch_notice_result));
        sb.append("\n").append(getString(R.string.dex_patch_notice_hint, urls.toString()));
        String message = sb.toString();
        postNotice(() -> new com.google.android.material.dialog.MaterialAlertDialogBuilder(this, R.style.ThemeOverlay_WebHTV_LightDialog)
                .setTitle(R.string.dex_patch_notice_title)
                .setMessage(message)
                .setCancelable(false)
                .setPositiveButton(R.string.source_probe_trial_rollback_confirm, null)
                .show());
    }

    private String dexPatchReason(String code) {
        switch (code) {
            case "1":
                return getString(R.string.dex_patch_notice_walk);
            case "3":
                return getString(R.string.dex_patch_notice_io);
            default:
                return getString(R.string.dex_patch_notice_not_found);
        }
    }

    // jar 强制退出软件被归因后：在当前页面（而非仅首页）告知用户原因与处理方案（知道了关闭）。
    // 消费点放 onResume 的原因：崩溃重启后任务栈可能恢复到搜索页/播放页，挂首页会被盖住看不见
    private void showJarGuardNotice() {
        String[] notice = com.fongmi.android.tv.api.JarGuard.takeNotice();
        if (notice == null) return;
        StringBuilder sb = new StringBuilder(getString("1".equals(notice[0]) ? R.string.jar_guard_notice_death : R.string.jar_guard_notice_autopsy));
        if ("1".equals(notice[2])) {
            int count = 2;
            try {
                count = Integer.parseInt(notice[1]);
            } catch (Throwable ignored) {
            }
            sb.append(getString(R.string.jar_guard_notice_locked, count));
        }
        postNotice(() -> new com.google.android.material.dialog.MaterialAlertDialogBuilder(this, R.style.ThemeOverlay_WebHTV_LightDialog)
                .setTitle(R.string.jar_block_notice_title)
                .setMessage(sb)
                .setCancelable(false)
                .setPositiveButton(R.string.source_probe_trial_rollback_confirm, null)
                .show());
    }

    // 试用源因闪退 / 尝试杀进程被自动回滚后：在当前页面提示用户
    private void showTrialNotice() {
        String[] notice = com.fongmi.android.tv.api.TrialRun.takeNotice();
        if (notice == null) return;
        boolean exit = "1".equals(notice[1]);
        int msg = exit ? R.string.source_probe_trial_exit_message : R.string.source_probe_trial_rollback_message;
        postNotice(() -> new com.google.android.material.dialog.MaterialAlertDialogBuilder(this, R.style.ThemeOverlay_WebHTV_LightDialog)
                .setTitle(R.string.source_probe_trial_rollback_title)
                .setMessage(msg)
                .setCancelable(false)
                .setPositiveButton(R.string.source_probe_trial_rollback_confirm, null)
                .show());
    }

    private void postNotice(Runnable show) {
        getWindow().getDecorView().post(() -> {
            if (isFinishing() || isDestroyed()) return;
            show.run();
        });
    }

    // 第三方 jar 崩溃被免疫后：在当前页面告知崩溃原因，确认后展示被永久屏蔽的播放路线地址
    private void showJarBlockNotice() {
        String[] notice = com.fongmi.android.tv.api.JarCrashShield.takeNotice();
        if (notice == null) return;
        String jar = notice[0];
        String reason = notice[1];
        getWindow().getDecorView().post(() -> {
            if (isFinishing() || isDestroyed()) return;
            new com.google.android.material.dialog.MaterialAlertDialogBuilder(this, com.fongmi.android.tv.R.style.ThemeOverlay_WebHTV_LightDialog)
                    .setTitle(com.fongmi.android.tv.R.string.jar_block_notice_title)
                    .setMessage(getString(com.fongmi.android.tv.R.string.jar_block_notice_message, reason))
                    .setCancelable(false)
                    .setPositiveButton(com.fongmi.android.tv.R.string.dialog_confirm, (d, w) -> showJarBlockUrl(jar))
                    .setNegativeButton(com.fongmi.android.tv.R.string.source_probe_trial_rollback_confirm, null)
                    .show();
        });
    }

    private void showJarBlockUrl(String jar) {
        if (isFinishing() || isDestroyed()) return;
        String text = android.text.TextUtils.isEmpty(jar) ? getString(com.fongmi.android.tv.R.string.jar_block_notice_detail) : jar;
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this, com.fongmi.android.tv.R.style.ThemeOverlay_WebHTV_LightDialog)
                .setTitle(com.fongmi.android.tv.R.string.jar_block_url_title)
                .setMessage(getString(com.fongmi.android.tv.R.string.jar_block_url_message, text))
                .setCancelable(false)
                .setPositiveButton(com.fongmi.android.tv.R.string.source_probe_trial_rollback_confirm, null)
                .show();
    }

    @Override
    protected void onDestroy() {
        EventBus.getDefault().unregister(this);
        super.onDestroy();
    }
}
