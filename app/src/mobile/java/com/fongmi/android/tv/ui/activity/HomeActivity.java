package com.fongmi.android.tv.ui.activity;

import android.app.Activity;
import android.app.PendingIntent;
import android.app.SearchManager;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.RelativeLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.pm.ShortcutInfoCompat;
import androidx.core.content.pm.ShortcutManagerCompat;
import androidx.core.graphics.drawable.IconCompat;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.Updater;
import com.fongmi.android.tv.api.config.LiveConfig;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.api.config.WallConfig;
import com.fongmi.android.tv.bean.Config;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.databinding.ActivityHomeBinding;
import com.fongmi.android.tv.db.AppDatabase;
import com.fongmi.android.tv.event.ConfigEvent;
import com.fongmi.android.tv.event.RefreshEvent;
import com.fongmi.android.tv.event.ServerEvent;
import com.fongmi.android.tv.event.StateEvent;
import com.fongmi.android.tv.impl.Callback;
import com.fongmi.android.tv.player.Source;
import com.fongmi.android.tv.receiver.ShortcutReceiver;
import com.fongmi.android.tv.server.Server;
import com.fongmi.android.tv.service.PlaybackService;
import com.fongmi.android.tv.setting.PlayerSetting;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.ui.custom.FragmentStateManager;
import com.fongmi.android.tv.ui.dialog.DisclaimerDialog;
import com.fongmi.android.tv.ui.fragment.BangumiFragment;
import com.fongmi.android.tv.ui.fragment.CollectFragment;
import com.fongmi.android.tv.ui.fragment.ConfigManageFragment;
import com.fongmi.android.tv.ui.fragment.EpisodeFragment;
import com.fongmi.android.tv.ui.fragment.FolderFragment;
import com.fongmi.android.tv.ui.fragment.HistoryFragment;
import com.fongmi.android.tv.ui.fragment.KeepFragment;
import com.fongmi.android.tv.ui.fragment.SearchFragment;
import com.fongmi.android.tv.ui.fragment.SettingAdvancedFragment;
import com.fongmi.android.tv.ui.fragment.SettingAppearanceFragment;
import com.fongmi.android.tv.ui.fragment.SettingDataFragment;
import com.fongmi.android.tv.ui.fragment.SettingDanmakuFragment;
import com.fongmi.android.tv.ui.fragment.SettingEnhanceFragment;
import com.fongmi.android.tv.ui.fragment.SettingFragment;
import com.fongmi.android.tv.ui.fragment.SettingPlaybackFragment;
import com.fongmi.android.tv.ui.fragment.SettingPlayerFragment;
import com.fongmi.android.tv.ui.fragment.SettingSourceFragment;
import com.fongmi.android.tv.ui.fragment.VodFragment;
import com.fongmi.android.tv.utils.DiagLog;
import com.fongmi.android.tv.utils.FileChooser;
import com.fongmi.android.tv.utils.MobileWindow;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.PermissionUtil;
import com.fongmi.android.tv.utils.UrlUtil;
import com.fongmi.android.tv.utils.Util;
import com.fongmi.android.tv.web.WebHomeChromeStartup;
import com.fongmi.android.tv.web.WebHomeViewport;
import com.github.catvod.net.OkHttp;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.navigation.NavigationBarView;
import com.google.gson.JsonObject;

import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

public class HomeActivity extends BaseActivity implements NavigationBarView.OnItemSelectedListener, WebHomeChromeController.Host {

    public static final String EXTRA_NAV_POSITION = "nav_position";
    public static final String EXTRA_FROM_ADVANCED = "from_advanced";
    private static final String STATE_RETURN_VOD_FROM_ENHANCE = "returnVodFromEnhance";
    private static final String STATE_RETURN_ADVANCED_FROM_ENHANCE = "returnAdvancedFromEnhance";
    private static final String STATE_CURRENT_POSITION = "currentPosition";

    private FragmentStateManager mManager;
    private ActivityHomeBinding mBinding;
    private WebHomeChromeController mChrome;
    private Config mStartupConfig;
    private boolean wideWindow;
    private boolean navInitialized;
    private int currentPosition;
    private boolean returnVodFromEnhance;
    private boolean returnAdvancedFromEnhance;
    private boolean disclaimerShowing;

    @Override
    protected ViewBinding getBinding() {
        return mBinding = ActivityHomeBinding.inflate(getLayoutInflater());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        checkAction(intent);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        setTheme(R.style.Theme_App);
        super.onCreate(savedInstanceState);
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        wideWindow = MobileWindow.isWide(this);
        returnVodFromEnhance = savedInstanceState != null && savedInstanceState.getBoolean(STATE_RETURN_VOD_FROM_ENHANCE);
        returnAdvancedFromEnhance = savedInstanceState != null && savedInstanceState.getBoolean(STATE_RETURN_ADVANCED_FROM_ENHANCE);
        currentPosition = savedInstanceState == null ? 0 : savedInstanceState.getInt(STATE_CURRENT_POSITION, 0);
        mStartupConfig = Config.vod();
        mChrome = new WebHomeChromeController(this, mBinding, this, savedInstanceState, WebHomeChromeStartup.restore(mStartupConfig));
        mBinding.getRoot().addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> checkWindowShape(right - left, bottom - top));
        mBinding.navigation.setOnItemSelectedListener(this);
        // 打开软件即申请通知权限，随后申请文件权限；免责声明不再挂在权限回调上（文件权限跳系统设置页返回时回调链不可靠），
        // 改由 onWindowFocusChanged 状态机在「文件权限已就绪 + 窗口拿到焦点」时触发
        PermissionUtil.requestNotify(this, granted -> PermissionUtil.requestFile(this, ignored -> DiagLog.log("disclaimer", "permission chain done fileAccess=%s", Setting.hasFileAccess())));
        initFragment(savedInstanceState);
        initConfig();
        Updater.create().checkOnLaunch(this);
    }

    // 首次使用软件时说明悬浮窗权限用途（小窗播放需要）；有其它弹窗时让路，留待下次进入再问
    private void requestOverlayPermissionIfNeeded() {
        requestOverlayPermissionIfNeeded(4);
    }

    private void requestOverlayPermissionIfNeeded(int retry) {
        if (!DisclaimerDialog.isAgreed()) return;
        if (PlayerSetting.isOverlayPermissionAsked() || Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return;
        if (Settings.canDrawOverlays(this)) return;
        if (!hasWindowFocus()) {
            if (retry > 0) App.post(() -> requestOverlayPermissionIfNeeded(retry - 1), 1500);
            return;
        }
        PlayerSetting.putOverlayPermissionAsked(true);
        View content = LayoutInflater.from(this).inflate(R.layout.dialog_bangumi_bind, null);
        AlertDialog dialog = new MaterialAlertDialogBuilder(this).setView(content).create();
        ((TextView) content.findViewById(R.id.title)).setText(R.string.video_float_permission_title);
        ((TextView) content.findViewById(R.id.message)).setText(R.string.video_float_permission_message);
        ((TextView) content.findViewById(R.id.cancel)).setText(R.string.video_float_permission_later);
        ((TextView) content.findViewById(R.id.confirm)).setText(R.string.video_float_permission_go);
        content.findViewById(R.id.cancel).setOnClickListener(v -> dialog.dismiss());
        content.findViewById(R.id.confirm).setOnClickListener(v -> {
            dialog.dismiss();
            Notify.show(R.string.video_float_permission_toast);
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())));
            } catch (Exception e) {
                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION));
            }
        });
        dialog.show();
        if (dialog.getWindow() != null) dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        outState.putBoolean(STATE_RETURN_VOD_FROM_ENHANCE, returnVodFromEnhance);
        outState.putBoolean(STATE_RETURN_ADVANCED_FROM_ENHANCE, returnAdvancedFromEnhance);
        outState.putInt(STATE_CURRENT_POSITION, currentPosition);
        if (mChrome != null) mChrome.save(outState);
        super.onSaveInstanceState(outState);
    }

    @Override
    protected void initEvent() {
        mBinding.navigation.findViewById(R.id.live).setOnLongClickListener(this::addShortcut);
    }

    private void checkAction(Intent intent) {
        if (intent.hasExtra(EXTRA_NAV_POSITION)) {
            returnAdvancedFromEnhance = intent.getBooleanExtra(EXTRA_FROM_ADVANCED, false);
            change(intent.getIntExtra(EXTRA_NAV_POSITION, 0));
            intent.removeExtra(EXTRA_NAV_POSITION);
            intent.removeExtra(EXTRA_FROM_ADVANCED);
        } else if (Intent.ACTION_SEND.equals(intent.getAction())) {
            VideoActivity.push(this, intent.getStringExtra(Intent.EXTRA_TEXT));
        } else if (Intent.ACTION_VIEW.equals(intent.getAction()) && intent.getData() != null) {
            PermissionUtil.requestFile(this, allGranted -> checkType(intent));
        } else if (Intent.ACTION_SEARCH.equals(intent.getAction())) {
            String keyword = intent.getStringExtra(SearchManager.QUERY);
            if (!TextUtils.isEmpty(keyword)) SearchActivity.start(this, keyword);
        }
    }

    private void checkType(Intent intent) {
        if ("text/plain".equals(intent.getType()) || UrlUtil.path(intent.getData()).endsWith(".m3u")) {
            loadLive("file:/" + FileChooser.getPathFromUri(intent.getData()));
        } else {
            VideoActivity.push(this, intent.getData().toString());
        }
    }

    private void initFragment(Bundle savedInstanceState) {
        mManager = new FragmentStateManager(mBinding.container, getSupportFragmentManager(), position -> switch (position) {
            case 0 -> VodFragment.newInstance();
            case 1 -> SettingFragment.newInstance();
            case 2 -> HistoryFragment.newInstance();
            case 3 -> SettingEnhanceFragment.newInstance();
            case 4 -> KeepFragment.newInstance();
            case 5 -> BangumiFragment.newInstance();
            default -> null;
        });
        if (savedInstanceState == null) change(defaultPosition());
        else restorePosition(currentPosition);
    }

    private void restorePosition(int position) {
        setNavigation();
        syncNavigationSelection();
        int target = position == 3 || isEntryClosed(position) ? defaultPosition() : position;
        setNavigationVisible(true);
        changeFragment(target);
    }

    private void initConfig() {
        VodConfig.get().config(mStartupConfig == null ? Config.vod() : mStartupConfig).load(getCallback());
        LiveConfig.get().init().load();
        WallConfig.get().init();
    }

    private Callback getCallback() {
        return new Callback() {
            @Override
            public void success() {
                checkAction(getIntent());
            }

            @Override
            public void error(String msg) {
                resetVodChrome();
                checkAction(getIntent());
                StateEvent.empty();
                Notify.show(msg);
            }
        };
    }

    private void loadLive(String url) {
        LiveConfig.load(Config.find(url, 1), new Callback() {
            @Override
            public void success() {
                openLive();
            }
        });
    }

    private void setNavigation() {
        navInitialized = true;
        mBinding.navigation.getMenu().findItem(R.id.vod).setVisible(Setting.isVodVisible());
        mBinding.navigation.getMenu().findItem(R.id.history).setVisible(Setting.isHistoryVisible());
        mBinding.navigation.getMenu().findItem(R.id.keep).setVisible(Setting.isKeepVisible());
        mBinding.navigation.getMenu().findItem(R.id.bangumi).setVisible(Setting.isBangumiVisible());
        mBinding.navigation.getMenu().findItem(R.id.setting).setVisible(true);
        mBinding.navigation.getMenu().findItem(R.id.live).setVisible(Setting.isLiveVisible() && LiveConfig.hasUrl());
        syncNavigationSelection();
    }

    // 首页入口被关闭时落地页回退到设置页
    private int defaultPosition() {
        return Setting.isVodVisible() ? 0 : 1;
    }

    private boolean isNavigationInitialized() {
        return navInitialized;
    }

    private boolean openLive() {
        LiveActivity.start(this);
        return false;
    }

    private boolean addShortcut(View view) {
        ShortcutInfoCompat info = new ShortcutInfoCompat.Builder(this, getString(R.string.nav_live)).setIcon(IconCompat.createWithResource(this, R.mipmap.ic_launcher)).setIntent(new Intent(Intent.ACTION_VIEW, null, this, LiveActivity.class)).setShortLabel(getString(R.string.nav_live)).build();
        PendingIntent pendingIntent = PendingIntent.getBroadcast(this, 0, new Intent(this, ShortcutReceiver.class).setAction(ShortcutReceiver.ACTION), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        ShortcutManagerCompat.requestPinShortcut(this, info, pendingIntent.getIntentSender());
        return true;
    }

    public void change(int position) {
        if (position != 3) {
            returnVodFromEnhance = false;
            returnAdvancedFromEnhance = false;
        }
        if (isEntryClosed(position)) position = defaultPosition();
        setNavigationVisible(position != 3);
        if (position == 3) changeFragment(position);
        else selectNavigation(position);
    }

    // 入口被关闭的页面不可进入
    private boolean isEntryClosed(int position) {
        if (position == 0) return !Setting.isVodVisible();
        if (position == 2) return !Setting.isHistoryVisible();
        if (position == 4) return !Setting.isKeepVisible();
        if (position == 5) return !Setting.isBangumiVisible();
        return false;
    }

    public void setNavigationVisible(boolean visible) {
        RelativeLayout.LayoutParams params = (RelativeLayout.LayoutParams) mBinding.container.getLayoutParams();
        if (visible) {
            params.addRule(RelativeLayout.ABOVE, R.id.navigation);
            mBinding.navigation.setVisibility(View.VISIBLE);
        } else {
            params.removeRule(RelativeLayout.ABOVE);
            mBinding.navigation.setVisibility(View.GONE);
        }
        mBinding.container.setLayoutParams(params);
        mBinding.getRoot().requestApplyInsets();
    }

    public static void start(Activity activity, int position) {
        activity.startActivity(new Intent(activity, HomeActivity.class).putExtra(EXTRA_NAV_POSITION, position));
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onConfigEvent(ConfigEvent event) {
        switch (event.type()) {
            case VOD:
                RefreshEvent.home();
                break;
            case COMMON:
                setNavigation();
                // 当前停留页的入口被关闭时，立即回退到落地页
                if (isEntryClosed(currentPosition)) change(defaultPosition());
                break;
            case BOOT:
                LiveActivity.start(this);
                break;
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onRefreshEvent(RefreshEvent event) {
        if (event.getType() == RefreshEvent.Type.THEME) recreate();
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onServerEvent(ServerEvent event) {
        if (event.type() == ServerEvent.Type.PUSH) VideoActivity.push(this, event.text());
        if (event.type() == ServerEvent.Type.SEARCH) SearchActivity.start(this, event.text());
    }

    @Override
    public boolean onNavigationItemSelected(@NonNull MenuItem item) {
        returnVodFromEnhance = false;
        returnAdvancedFromEnhance = false;
        setNavigationVisible(true);
        if (item.getItemId() == R.id.setting) return changeFragment(1);
        if (item.getItemId() == R.id.vod) return changeFragment(0);
        if (item.getItemId() == R.id.history) return changeFragment(2);
        if (item.getItemId() == R.id.keep) return changeFragment(4);
        if (item.getItemId() == R.id.bangumi) return changeFragment(5);
        if (item.getItemId() == R.id.live) return openLive();
        return false;
    }

    private void selectNavigation(int position) {
        int itemId = getItemId(position);
        if (mBinding.navigation.getSelectedItemId() == itemId) changeFragment(position);
        else mBinding.navigation.setSelectedItemId(itemId);
    }

    // 位置 → 导航标签；首页入口关闭时落地页由 vod 回退为 setting
    private int getItemId(int position) {
        if (position == 2) return R.id.history;
        if (position == 4) return R.id.keep;
        if (position == 5) return R.id.bangumi;
        if (position != 0) return R.id.setting;
        return Setting.isVodVisible() ? R.id.vod : R.id.setting;
    }

    private void syncNavigationSelection() {
        int itemId = getItemId(currentPosition);
        if (mBinding.navigation.getSelectedItemId() == itemId) return;
        mBinding.navigation.setOnItemSelectedListener(null);
        mBinding.navigation.setSelectedItemId(itemId);
        mBinding.navigation.setOnItemSelectedListener(this);
    }

    private boolean changeFragment(int position) {
        setNavigationVisible(position != 3);
        boolean changed = mManager.change(position);
        if (changed) currentPosition = position;
        refreshWebHomeChromeLayout();
        return changed;
    }

    private void refreshWebHomeChromeLayout() {
        if (mChrome != null) mChrome.refreshLayout();
    }

    private void resetVodChrome() {
        if (mChrome != null) mChrome.setLegacyToolbar(true);
    }

    public void applyWebHomeDefaultChrome(Site site) {
        if (!Setting.isWebHomeFullscreen()) {
            if (mChrome != null) mChrome.setChrome(normalWebHomeChrome());
            return;
        }
        if (mChrome != null) mChrome.applyDefault(WebHomeChromeStartup.resolve(VodConfig.get().getConfig(), site));
    }

    public void setWebHomeChrome(JsonObject payload) {
        if (!Setting.isWebHomeFullscreen()) {
            if (mChrome != null) mChrome.setChrome(normalWebHomeChrome());
            return;
        }
        if (isStartupChrome(payload)) WebHomeChromeStartup.remember(VodConfig.get().getConfig(), VodConfig.get().getHome(), payload);
        if (mChrome != null) mChrome.setChrome(payload);
    }

    private boolean isStartupChrome(JsonObject payload) {
        try {
            return payload != null && payload.has("startup") && payload.get("startup").getAsBoolean();
        } catch (Throwable e) {
            return false;
        }
    }

    public void restoreWebHomeChrome() {
        if (!Setting.isWebHomeFullscreen()) {
            if (mChrome != null) mChrome.setChrome(normalWebHomeChrome());
            return;
        }
        if (mChrome != null) mChrome.restore();
    }

    public void setWebHomeLegacyToolbar(boolean visible) {
        if (!Setting.isWebHomeFullscreen()) {
            if (mChrome != null) mChrome.setChrome(normalWebHomeChrome());
            return;
        }
        if (mChrome != null) mChrome.setLegacyToolbar(visible);
    }

    public void refreshWebHomeChromeState() {
        onWebHomeChromeChanged(getWebHomeChromeMode());
    }

    private JsonObject normalWebHomeChrome() {
        JsonObject object = new JsonObject();
        object.addProperty("mode", "normal");
        return object;
    }

    public void openVod() {
        resetVodChrome();
        setNavigationVisible(true);
        mBinding.navigation.setSelectedItemId(R.id.vod);
        VodFragment fragment = (VodFragment) mManager.getFragment(0);
        if (fragment != null) fragment.openVodHome();
    }

    public void openEnhanceFromVod() {
        returnVodFromEnhance = true;
        setNavigationVisible(false);
        changeFragment(3);
    }

    public String getWebHomeChromeMode() {
        return mChrome == null ? "normal" : mChrome.getMode();
    }

    public WebHomeViewport getWebHomeViewport() {
        return mChrome == null ? WebHomeViewport.EMPTY : mChrome.getViewport();
    }

    @Override
    public boolean isWebHomeChromeActive() {
        return mManager != null && mManager.isVisible(0);
    }

    @Override
    public void onWebHomeChromeChanged(String mode) {
        if (mManager == null) return;
        VodFragment fragment = (VodFragment) mManager.getFragment(0);
        if (fragment != null) fragment.applyWebHomeChrome(mode);
    }

    @Override
    public void onWebHomeViewportChanged(WebHomeViewport viewport) {
        if (mManager == null) return;
        VodFragment fragment = (VodFragment) mManager.getFragment(0);
        if (fragment != null) fragment.applyWebHomeViewport(viewport);
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (mChrome != null) mChrome.onConfigurationChanged();
        App.post(this::checkWindowShape, 100);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (mChrome != null) mChrome.onWindowFocusChanged(hasFocus);
        if (hasFocus) onHomeWindowReady();
    }

    // 免责声明状态机：不依赖权限回调（文件权限跳系统设置页返回时回调可能丢失），
    // 改为每次窗口拿到焦点时判断——文件权限就绪且未签署则弹免责声明，同意后引导悬浮窗权限；已签署用户仅走悬浮窗引导
    private void onHomeWindowReady() {
        if (DisclaimerDialog.isAgreed()) {
            requestOverlayPermissionIfNeeded();
            return;
        }
        if (!Setting.hasFileAccess()) return;
        if (disclaimerShowing || isFinishing() || isDestroyed()) return;
        disclaimerShowing = true;
        DiagLog.log("disclaimer", "show onWindowFocus fileAccess=true");
        DisclaimerDialog.showIfNeeded(this, () -> {
            disclaimerShowing = false;
            requestOverlayPermissionIfNeeded();
        });
    }

    private void checkWindowShape() {
        checkWindowShape(MobileWindow.getWidth(this), MobileWindow.getHeight(this));
    }

    private void checkWindowShape(int width, int height) {
        if (width <= 0 || height <= 0) return;
        boolean wide = width > height;
        if (wideWindow != wide) {
            wideWindow = wide;
            RefreshEvent.home();
        }
    }

    @Override
    protected void onBackInvoked() {
        if (mChrome != null && mChrome.consumeBack()) {
            return;
        } else if (!isNavigationInitialized()) {
            setNavigation();
        } else if (mManager.isVisible(3)) {
            boolean fromVod = returnVodFromEnhance;
            boolean fromAdvanced = returnAdvancedFromEnhance;
            returnVodFromEnhance = false;
            returnAdvancedFromEnhance = false;
            if (fromAdvanced) {
                change(1);
                SubSettingActivity.start(this, 8);
            } else {
                change(fromVod && Setting.isVodVisible() ? 0 : 1);
            }
        } else if (mManager.isVisible(2)) {
            if (!mManager.canBack(2)) return;
            change(defaultPosition());
        } else if (mManager.isVisible(4)) {
            if (!mManager.canBack(4)) return;
            change(defaultPosition());
        } else if (mManager.isVisible(1) && Setting.isVodVisible()) {
            change(0);
        } else if (mManager.canBack(0)) {
            if (PlaybackService.isRunning()) Util.moveToBackground(this);
            else super.onBackInvoked();
        }
    }

    @Override
    protected void onDestroy() {
        if (mChrome != null) mChrome.destroy();
        LiveConfig.get().clear();
        VodConfig.get().clear();
        if (Setting.isAutoBackup()) AppDatabase.backup();
        OkHttp.get().clear();
        Source.get().exit();
        Server.get().stop();
        super.onDestroy();
    }
}
