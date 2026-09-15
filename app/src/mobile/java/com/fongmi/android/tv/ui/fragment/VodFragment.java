package com.fongmi.android.tv.ui.fragment;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentStatePagerAdapter;
import androidx.lifecycle.ViewModelProvider;
import androidx.viewbinding.ViewBinding;
import androidx.viewpager.widget.PagerAdapter;
import androidx.viewpager.widget.ViewPager;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.SourceProbe;
import com.fongmi.android.tv.api.TrialRun;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Class;
import com.fongmi.android.tv.bean.Config;
import com.fongmi.android.tv.bean.Device;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.bean.Value;
import com.fongmi.android.tv.databinding.FragmentVodBinding;
import com.fongmi.android.tv.event.CastEvent;
import com.fongmi.android.tv.event.ConfigEvent;
import com.fongmi.android.tv.event.RefreshEvent;
import com.fongmi.android.tv.event.StateEvent;
import com.fongmi.android.tv.impl.Callback;
import com.fongmi.android.tv.impl.ConfigListener;
import com.fongmi.android.tv.impl.FilterListener;
import com.fongmi.android.tv.impl.SiteListener;
import com.fongmi.android.tv.model.SiteViewModel;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.ui.activity.HomeActivity;
import com.fongmi.android.tv.ui.activity.HistoryActivity;
import com.fongmi.android.tv.ui.activity.KeepActivity;
import com.fongmi.android.tv.ui.activity.ScanActivity;
import com.fongmi.android.tv.ui.activity.SearchActivity;
import com.fongmi.android.tv.ui.activity.SubSettingActivity;
import com.fongmi.android.tv.ui.adapter.TypeAdapter;
import com.fongmi.android.tv.ui.base.BaseFragment;
import com.fongmi.android.tv.ui.dialog.ApkPushDialog;
import com.fongmi.android.tv.ui.dialog.FilterDialog;
import com.fongmi.android.tv.ui.dialog.HistoryDialog;
import com.fongmi.android.tv.ui.dialog.LinkDialog;
import com.fongmi.android.tv.ui.dialog.OneKeySyncDialog;
import com.fongmi.android.tv.ui.dialog.ProbeDialog;
import com.fongmi.android.tv.ui.dialog.ProbeScanDialog;
import com.fongmi.android.tv.ui.dialog.PushPlayDialog;
import com.fongmi.android.tv.ui.dialog.PushPlayUrlDialog;
import com.fongmi.android.tv.ui.dialog.ReceiveDialog;
import com.fongmi.android.tv.ui.dialog.SiteDialog;
import com.fongmi.android.tv.ui.dialog.TypeDialog;
import com.fongmi.android.tv.utils.ImgUtil;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.Task;
import com.fongmi.android.tv.utils.PermissionUtil;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.web.HomeWebController;
import com.fongmi.android.tv.web.WebHomeChrome;
import com.fongmi.android.tv.web.WebHomeChromeStartup;
import com.fongmi.android.tv.web.WebHomeViewport;
import com.google.gson.JsonObject;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

public class VodFragment extends BaseFragment implements ConfigListener, SiteListener, FilterListener, TypeAdapter.OnClickListener, HomeWebController.Listener {

    private final ActivityResultLauncher<String[]> apkLauncher = registerForActivityResult(new ActivityResultContracts.OpenDocument(), this::onApkSelected);

    private final ActivityResultLauncher<Intent> scanLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> onScanResult(result.getResultCode(), result.getData()));

    private FragmentVodBinding mBinding;
    private SiteViewModel mViewModel;
    private HomeWebController mWeb;
    private TypeAdapter mAdapter;
    private Result mResult;
    private String mChromeMode = WebHomeChrome.NORMAL;
    private int mHomeWebTopMargin;
    private Device pendingApkDevice;

    public static VodFragment newInstance() {
        return new VodFragment();
    }

    private FolderFragment getFragment() {
        return (FolderFragment) mBinding.pager.getAdapter().instantiateItem(mBinding.pager, mBinding.pager.getCurrentItem());
    }

    private Site getHome() {
        return VodConfig.get().getHome();
    }

    private Config getConfig() {
        return VodConfig.get().getConfig();
    }

    @Override
    protected ViewBinding getBinding(@NonNull LayoutInflater inflater, @Nullable ViewGroup container) {
        return mBinding = FragmentVodBinding.inflate(inflater, container, false);
    }

    @Override
    protected void initView() {
        EventBus.getDefault().register(this);
        mBinding.title.setSelected(true);
        mHomeWebTopMargin = ((ViewGroup.MarginLayoutParams) mBinding.homeWeb.getLayoutParams()).topMargin;
        setRecyclerView();
        setWebView();
        setViewModel();
        showProgress();
        setTitle();
        setLogo();
        updateToolbarMenu();
        // 冷启动时完全没有订阅地址，直接展示引导空态；有订阅则等加载结果事件再决定
        if (VodConfig.get().getSites().isEmpty() && TextUtils.isEmpty(getConfig().getUrl())) showEmptyGuide();
    }

    @Override
    protected void initEvent() {
        mBinding.top.setOnClickListener(this::onTop);
        mBinding.logo.setOnClickListener(this::onLogo);
        mBinding.link.setOnClickListener(this::onLink);
        mBinding.title.setOnClickListener(this::onSite);
        mBinding.title.setOnLongClickListener(this::reloadConfig);
        mBinding.typeMore.setOnTouchListener(this::onTypeMoreTouch);
        mBinding.typeMore.setOnClickListener(this::onTypeMore);
        mBinding.filter.setOnClickListener(this::onFilter);
        mBinding.filter.setOnLongClickListener(this::onLink);
        mBinding.toolbar.setOnMenuItemClickListener(this::onMenuItemClick);
        mBinding.toolbar.post(this::setSearchLongClick);
        mBinding.emptyAdd.setOnClickListener(this::onEmptyAdd);
        mBinding.emptyScan.setOnClickListener(this::onEmptyScan);
        mBinding.appBar.addOnOffsetChangedListener((appBarLayout, verticalOffset) -> {
            int range = appBarLayout.getTotalScrollRange();
            if (range <= 0) return;
            float factor = Math.abs(verticalOffset * 1f / range);
            int padding = (int) (ResUtil.dp2px(12) * factor);
            if (mBinding.type.getPaddingTop() == padding) return;
            mBinding.type.setPadding(mBinding.type.getPaddingStart(), padding, mBinding.type.getPaddingEnd(), mBinding.type.getPaddingBottom());
        });
        mBinding.pager.addOnPageChangeListener(new ViewPager.SimpleOnPageChangeListener() {
            @Override
            public void onPageSelected(int position) {
                mBinding.type.smoothScrollToPosition(position);
                mAdapter.setSelected(position);
                setFabVisible(position);
            }
        });
    }

    private void setRecyclerView() {
        mBinding.type.setHasFixedSize(true);
        mBinding.type.setItemAnimator(null);
        mBinding.type.setAdapter(mAdapter = new TypeAdapter(this));
        mBinding.pager.setAdapter(new PageAdapter(getChildFragmentManager()));
    }

    private void setWebView() {
        mWeb = new HomeWebController(requireActivity(), mBinding.homeWeb, this);
        syncWebHomeChrome();
    }

    private void setViewModel() {
        mViewModel = new ViewModelProvider(this).get(SiteViewModel.class);
        mViewModel.getResult().observe(getViewLifecycleOwner(), this::setAdapter);
    }

    private void setAdapter(Result result) {
        if (mWeb != null && mWeb.isVisible()) return;
        // 防止 Fragment 重建时 LiveData 重放旧结果，覆盖引导空态
        if (VodConfig.get().getSites().isEmpty()) {
            showEmptyGuide();
            return;
        }
        mAdapter.addAll(mResult = result);
        notifyPagerAdapter();
        setFabVisible(0);
        mBinding.typeMore.setVisibility(View.GONE);
        mBinding.type.post(this::updateTypeMoreVisible);
        updateToolbarMenu();
        hideProgress();
        showContent();
    }

    private void updateTypeMoreVisible() {
        if (mBinding.type.getWidth() == 0 || mBinding.typeBar.getWidth() == 0) {
            mBinding.type.post(this::updateTypeMoreVisible);
            return;
        }
        int typeWidth = mBinding.typeBar.getWidth() - mBinding.typeBar.getPaddingStart() - mBinding.typeBar.getPaddingEnd();
        boolean visible = mAdapter.getItemCount() > 0 && mBinding.type.computeHorizontalScrollRange() > typeWidth;
        mBinding.typeMore.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    private void setFabVisible(int position) {
        // 引导空态展示期间，悬浮按钮一律隐藏
        if (mBinding.empty.getVisibility() == View.VISIBLE) {
            mBinding.top.setVisibility(View.GONE);
            mBinding.link.setVisibility(View.GONE);
            mBinding.filter.setVisibility(View.GONE);
            return;
        }
        if (isNativeChromeHidden()) {
            mBinding.top.setVisibility(View.GONE);
            mBinding.link.setVisibility(View.GONE);
            mBinding.filter.setVisibility(View.GONE);
            return;
        }
        if (mAdapter.getItemCount() == 0) {
            mBinding.top.setVisibility(View.INVISIBLE);
            mBinding.link.setVisibility(View.VISIBLE);
            mBinding.filter.setVisibility(View.GONE);
        } else if (!mAdapter.get(position).getFilters().isEmpty()) {
            mBinding.top.setVisibility(View.INVISIBLE);
            mBinding.link.setVisibility(View.GONE);
            mBinding.filter.show();
        } else if (position == 0 || mAdapter.get(position).getFilters().isEmpty()) {
            mBinding.top.setVisibility(View.INVISIBLE);
            mBinding.filter.setVisibility(View.GONE);
            mBinding.link.show();
        }
        // 用户关闭「首页直链播放悬浮窗」开关后，悬浮窗彻底隐藏
        if (!Setting.isLinkVisible()) mBinding.link.setVisibility(View.GONE);
    }

    private void setTitle() {
        List<String> items = Arrays.asList(getHome().getName(), getConfig().getName(), getString(R.string.app_name));
        Optional<String> optional = items.stream().filter(s -> !TextUtils.isEmpty(s)).findFirst();
        optional.ifPresent(s -> mBinding.title.setText(s));
    }

    private void onTop(View view) {
        getFragment().scrollToTop();
        mBinding.top.setVisibility(View.INVISIBLE);
        if (mBinding.filter.getVisibility() == View.INVISIBLE) mBinding.filter.show();
        else if (mBinding.link.getVisibility() == View.INVISIBLE) mBinding.link.show();
    }

    private boolean onLink(View view) {
        if (!Setting.isLinkVisible()) return false;
        LinkDialog.show(this);
        return true;
    }

    private void onTypeMore(View view) {
        if (mAdapter.getItemCount() > 0) TypeDialog.create().items(mAdapter.getItems()).show(this);
    }

    private boolean onTypeMoreTouch(View view, MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            view.animate().cancel();
            view.animate().scaleX(1.06f).scaleY(1.06f).setDuration(80).start();
        } else if (event.getAction() == MotionEvent.ACTION_UP || event.getAction() == MotionEvent.ACTION_CANCEL) {
            view.animate().cancel();
            view.animate().scaleX(1.0f).scaleY(1.0f).setDuration(120).start();
        }
        return false;
    }

    private void onLogo(View view) {
        HistoryDialog.create().vod().readOnly().show(this);
    }

    private void onSite(View view) {
        SiteDialog.create().change().show(this);
    }

    private boolean reloadConfig(View view) {
        VodConfig.get().clear().config(getConfig()).load(new Callback() {
            @Override
            public void start() {
                showProgress();
                hideContent();
            }

            @Override
            public void success() {
                hideProgress();
                showContent();
            }

            @Override
            public void error(String msg) {
                Notify.dismiss();
                Notify.show(msg);
                hideProgress();
                // 重载失败且无可用源时回到引导空态
                if (VodConfig.get().getSites().isEmpty()) showEmptyGuide();
                else showContent();
            }
        });
        return true;
    }

    private void onFilter(View view) {
        if (mAdapter.getItemCount() > 0) FilterDialog.create().filter(mAdapter.get(mBinding.pager.getCurrentItem()).getFilters()).show(this);
    }

    private boolean onMenuItemClick(MenuItem item) {
        if (item.getItemId() == R.id.refresh) {
            if (mWeb != null && mWeb.isVisible()) mWeb.reload();
            else homeContent();
        } else if (item.getItemId() == R.id.keep) KeepActivity.start(requireActivity());
        else if (item.getItemId() == R.id.search) SearchActivity.start(requireActivity());
        else if (item.getItemId() == R.id.history) HistoryActivity.start(requireActivity());
        else if (item.getItemId() == R.id.sync) OneKeySyncDialog.create().show(requireActivity());
        else if (item.getItemId() == R.id.push_apk) ApkPushDialog.create().listener(this::onApkDeviceSelected).show(requireActivity());
        else if (item.getItemId() == R.id.push_play) PushPlayDialog.create().listener(this::onPushPlayDeviceSelected).show(requireActivity());
        else if (item.getItemId() == R.id.enhance && homeActivity() != null) homeActivity().openEnhanceFromVod();
        else if (item.getItemId() == R.id.web_home_fullscreen) onWebHomeFullscreen();
        else return false;
        return true;
    }

    private void onApkSelected(Uri uri) {
        Device device = pendingApkDevice;
        pendingApkDevice = null;
        if (uri != null && device != null) ApkPushDialog.create(device, uri).show(requireActivity());
    }

    private void onApkDeviceSelected(Device device) {
        pendingApkDevice = device;
        App.post(() -> {
            if (isAdded()) apkLauncher.launch(new String[]{"application/vnd.android.package-archive", "application/octet-stream"});
        });
    }

    private void onPushPlayDeviceSelected(Device device) {
        App.post(() -> {
            if (isAdded()) PushPlayUrlDialog.create(device).show(requireActivity());
        });
    }

    private void onWebHomeFullscreen() {
        if (!Setting.isWebHomeFullscreen()) return;
        if (mWeb == null || !mWeb.isVisible()) return;
        JsonObject payload = new JsonObject();
        payload.addProperty("mode", WebHomeChrome.EDGE);
        setChrome(payload);
    }

    private void updateToolbarMenu() {
        Menu menu = mBinding.toolbar.getMenu();
        MenuItem fullscreen = menu.findItem(R.id.web_home_fullscreen);
        if (fullscreen != null) fullscreen.setVisible(Setting.isWebHomeFullscreen() && mWeb != null && mWeb.isVisible());
    }

    private void setSearchLongClick() {
        View search = mBinding.toolbar.findViewById(R.id.search);
        if (search == null) return;
        search.setOnLongClickListener(view -> {
            SearchActivity.start(requireActivity(), "", getHome().getKey());
            return true;
        });
    }

    private void showProgress() {
        mBinding.progress.getRoot().setVisibility(View.VISIBLE);
    }

    // 无生效订阅源的引导空态
    private void showEmptyGuide() {
        hideProgress();
        hideContent();
        mBinding.homeWeb.setVisibility(View.GONE);
        mBinding.empty.setVisibility(View.VISIBLE);
        mBinding.filter.setVisibility(View.GONE);
        mBinding.link.setVisibility(View.GONE);
        mBinding.top.setVisibility(View.GONE);
    }

    private void hideEmptyGuide() {
        if (mBinding.empty.getVisibility() != View.VISIBLE) return;
        mBinding.empty.setVisibility(View.GONE);
    }

    private void onEmptyAdd(View view) {
        // 直接跳转「点播订阅管理」页，页内含添加源 / 扫一扫入口
        SubSettingActivity.start(requireActivity(), 10);
    }

    private void onEmptyScan(View view) {
        PermissionUtil.requestFile(this, granted -> {
            if (granted) scanLauncher.launch(new Intent(requireActivity(), ScanActivity.class));
        });
    }

    private void onScanResult(int code, Intent data) {
        if (code != Activity.RESULT_OK || data == null) return;
        String address = SourceProbe.cleanUrl(data.getStringExtra("address"));
        if (TextUtils.isEmpty(address)) return;
        Config config = Config.find(address, 0);
        // 扫码添加与订阅管理页一致：开关开启先做前置静态扫描（弹窗展示进度），命中恶意特征阻止添加
        boolean http = address.startsWith("http://") || address.startsWith("https://");
        if (Setting.isProbeAdd() && http) {
            ProbeScanDialog scan = ProbeScanDialog.create();
            scan.show(requireActivity());
            Task.submitLarge(() -> {
                boolean dangerous = SourceProbe.scanDangerous(address, 0, scan);
                App.post(() -> {
                    scan.dismissAllowingStateLoss();
                    if (!isAdded()) return;
                    if (dangerous) ProbeDialog.showScanDanger(requireActivity());
                    else afterScan(address, config);
                });
            });
            return;
        }
        afterScan(address, config);
    }

    // 前置扫描通过（或免扫描）后的原添加逻辑：命中试运行规则时先做安全检测
    private void afterScan(String address, Config config) {
        boolean needTrial = Setting.isProbeAdd() && (address.startsWith("http://") || address.startsWith("https://"));
        if (needTrial) {
            if (SourceProbe.isRuntimeDangerous(address)) {
                ProbeDialog.showDanger(requireActivity());
                return;
            }
            if (!SourceProbe.isRuntimePassed(address)) {
                TrialRun.begin(address, 0);
                Notify.show(R.string.source_probe_trial_started);
            } else {
                // 需求4：缓存直接 PASS → 免试运行，成功即自动设全部站点为「参与换源」
                VodConfig.markEnableChange(address);
            }
        } else {
            // 需求4：点播扫码添加免试运行（关闭检测等）→ 成功即自动设全部站点为「参与换源」
            VodConfig.markEnableChange(address);
        }
        setConfig(config);
    }

    private void hideProgress() {
        mBinding.progress.getRoot().setVisibility(View.GONE);
    }

    private void hideContent() {
        mBinding.type.setVisibility(View.INVISIBLE);
        mBinding.typeMore.setVisibility(View.INVISIBLE);
        mBinding.pager.setVisibility(View.INVISIBLE);
    }

    private void showContent() {
        hideEmptyGuide();
        mBinding.type.setVisibility(View.VISIBLE);
        updateTypeMoreVisible();
        mBinding.pager.setVisibility(View.VISIBLE);
    }

    private void homeContent() {
        // 无任何可用源时不请求首页，直接展示引导空态
        if (VodConfig.get().getSites().isEmpty()) {
            showEmptyGuide();
            return;
        }
        hideEmptyGuide();
        requestNormalChrome();
        showProgress();
        mBinding.homeWeb.setVisibility(View.GONE);
        updateToolbarMenu();
        clearPagerTypes();
        mBinding.pager.setAdapter(new PageAdapter(getChildFragmentManager()));
        setFabVisible(0);
        mViewModel.homeContent();
    }

    private void loadHome() {
        hideEmptyGuide();
        Site home = getHome();
        WebHomeChromeStartup.remember(getConfig(), home);
        setTitle();
        if (mWeb != null && mWeb.load(home)) {
            clearPagerTypes();
            hideProgress();
            hideNativeContent();
        } else {
            showNativeContent();
            homeContent();
        }
    }

    private void clearPagerTypes() {
        mAdapter.clear();
        mBinding.typeMore.setVisibility(View.GONE);
        notifyPagerAdapter();
    }

    private void notifyPagerAdapter() {
        PagerAdapter adapter = mBinding.pager.getAdapter();
        if (adapter != null) adapter.notifyDataSetChanged();
    }

    public Result getResult() {
        return mResult == null ? new Result() : mResult;
    }

    private void setLogo() {
        ImgUtil.logo(mBinding.logo);
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onConfigEvent(ConfigEvent event) {
        if (event.type() == ConfigEvent.Type.VOD) setLogo();
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onRefreshEvent(RefreshEvent event) {
        switch (event.getType()) {
            case HOME:
                if (mWeb != null && mWeb.isVisible()) {
                    Site home = getHome();
                    WebHomeChromeStartup.remember(getConfig(), home);
                    requestNormalChrome();
                    setTitle();
                    if (!mWeb.load(home, true)) {
                        showNativeContent();
                        homeContent();
                    }
                } else {
                    loadHome();
                }
                break;
            case SIZE:
                if (mWeb != null && mWeb.isVisible()) return;
                homeContent();
                break;
            case CATEGORY:
                if (mWeb != null && mWeb.isVisible()) return;
                getFragment().onRefresh();
                break;
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onStateEvent(StateEvent event) {
        switch (event.type()) {
            case EMPTY:
                hideProgress();
                // 订阅加载失败且无任何可用源 → 展示引导空态
                if (VodConfig.get().getSites().isEmpty()) showEmptyGuide();
                break;
            case PROGRESS:
                showProgress();
                break;
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onCastEvent(CastEvent event) {
        ReceiveDialog.create().event(event).show(this);
    }

    @Override
    public void setConfig(Config config) {
        VodConfig.load(config, new Callback() {
            @Override
            public void start() {
                hideEmptyGuide();
                showProgress();
                hideContent();
                setTitle();
                setLogo();
            }

            @Override
            public void error(String msg) {
                Notify.dismiss();
                Notify.show(msg);
                // 加载失败且无可用源时回到引导空态
                if (VodConfig.get().getSites().isEmpty()) showEmptyGuide();
                else showContent();
            }
        });
    }

    @Override
    public void setSite(Site item) {
        VodConfig.get().setHome(item);
    }

    @Override
    public void onItemClick(int position, Class item) {
        mBinding.pager.setCurrentItem(position);
        mAdapter.setSelected(position);
    }

    @Override
    public void setFilter(String key, Value value) {
        getFragment().setFilter(key, value);
    }

    @Override
    public boolean canBack() {
        if (mWeb != null && mWeb.handleBack()) return false;
        if (isNativeChromeHidden()) {
            requestNormalChrome();
            return false;
        }
        if (mBinding.pager.getAdapter() == null || mBinding.pager.getAdapter().getCount() == 0) return true;
        if (!getFragment().canBack()) return true;
        getFragment().goBack();
        return false;
    }

    @Override
    public void onDestroyView() {
        requestNormalChrome();
        if (mWeb != null) mWeb.destroy();
        EventBus.getDefault().unregister(this);
        super.onDestroyView();
    }

    @Override
    public void onResume() {
        super.onResume();
        setFabVisible(mBinding.pager.getCurrentItem());
        if (mWeb != null) mWeb.onResume();
    }

    @Override
    public void onPause() {
        if (mWeb != null) mWeb.onPause();
        super.onPause();
    }

    @Override
    public void onWebLoading() {
        showProgress();
    }

    @Override
    public void onWebReady() {
        hideProgress();
    }

    @Override
    public void onWebError() {
        requestNormalChrome();
        showNativeContent();
        homeContent();
    }

    @Override
    public void setToolbar(boolean visible) {
        if (!Setting.isWebHomeFullscreen()) {
            applyWebHomeChrome(WebHomeChrome.NORMAL);
            return;
        }
        HomeActivity activity = homeActivity();
        if (activity != null) activity.setWebHomeLegacyToolbar(visible);
        else applyWebHomeChrome(visible ? WebHomeChrome.NORMAL : WebHomeChrome.IMMERSIVE);
    }

    @Override
    public void applyDefaultChrome(Site site) {
        if (!Setting.isWebHomeFullscreen()) {
            applyWebHomeChrome(WebHomeChrome.NORMAL);
            return;
        }
        HomeActivity activity = homeActivity();
        if (activity != null) activity.applyWebHomeDefaultChrome(site);
    }

    @Override
    public void setChrome(JsonObject payload) {
        if (!Setting.isWebHomeFullscreen()) {
            applyWebHomeChrome(WebHomeChrome.NORMAL);
            return;
        }
        HomeActivity activity = homeActivity();
        if (activity != null) activity.setWebHomeChrome(payload);
    }

    @Override
    public void restoreChrome() {
        if (!Setting.isWebHomeFullscreen()) {
            applyWebHomeChrome(WebHomeChrome.NORMAL);
            return;
        }
        HomeActivity activity = homeActivity();
        if (activity != null) activity.restoreWebHomeChrome();
    }

    @Override
    public WebHomeViewport getViewport() {
        HomeActivity activity = homeActivity();
        return activity == null ? WebHomeViewport.EMPTY : activity.getWebHomeViewport();
    }

    @Override
    public void openVod() {
        HomeActivity activity = homeActivity();
        if (activity != null) activity.openVod();
    }

    @Override
    public void openSetting() {
        if (getActivity() instanceof HomeActivity) ((HomeActivity) getActivity()).change(1);
    }

    private void hideNativeContent() {
        mBinding.appBar.setExpanded(true, false);
        boolean hidden = isNativeChromeHidden();
        mBinding.appBar.setVisibility(hidden ? View.GONE : View.VISIBLE);
        setHomeWebTopMargin(hidden ? 0 : mHomeWebTopMargin);
        mBinding.type.setVisibility(View.GONE);
        mBinding.typeMore.setVisibility(View.GONE);
        mBinding.pager.setVisibility(View.GONE);
        mBinding.filter.setVisibility(View.GONE);
        mBinding.link.setVisibility(View.GONE);
        mBinding.top.setVisibility(View.GONE);
        updateToolbarMenu();
    }

    private void showNativeContent() {
        requestNormalChrome();
        mBinding.type.setVisibility(View.VISIBLE);
        updateTypeMoreVisible();
        mBinding.pager.setVisibility(View.VISIBLE);
        mBinding.homeWeb.setVisibility(View.GONE);
        updateToolbarMenu();
    }

    public void applyWebHomeChrome(String mode) {
        mChromeMode = WebHomeChrome.normalize(mode, WebHomeChrome.NORMAL);
        boolean hidden = isNativeChromeHidden();
        mBinding.appBar.setExpanded(true, false);
        mBinding.appBar.setVisibility(hidden ? View.GONE : View.VISIBLE);
        setHomeWebTopMargin(hidden ? 0 : mHomeWebTopMargin);
        updateToolbarMenu();
        if (hidden) {
            mBinding.type.setVisibility(View.GONE);
            mBinding.typeMore.setVisibility(View.GONE);
            mBinding.pager.setVisibility(View.GONE);
            mBinding.filter.setVisibility(View.GONE);
            mBinding.link.setVisibility(View.GONE);
            mBinding.top.setVisibility(View.GONE);
        }
    }

    public void applyWebHomeViewport(WebHomeViewport viewport) {
        if (mWeb != null) mWeb.setViewport(viewport);
    }

    public void openVodHome() {
        homeContent();
    }

    private void setHomeWebTopMargin(int margin) {
        ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) mBinding.homeWeb.getLayoutParams();
        if (params.topMargin == margin) return;
        params.topMargin = margin;
        mBinding.homeWeb.setLayoutParams(params);
    }

    private void requestNormalChrome() {
        HomeActivity activity = homeActivity();
        if (activity != null) activity.setWebHomeLegacyToolbar(true);
        else applyWebHomeChrome(WebHomeChrome.NORMAL);
    }

    private boolean isNativeChromeHidden() {
        return WebHomeChrome.hidesNativeChrome(mChromeMode);
    }

    private void syncWebHomeChrome() {
        HomeActivity activity = homeActivity();
        if (activity == null) return;
        applyWebHomeChrome(activity.getWebHomeChromeMode());
        applyWebHomeViewport(activity.getWebHomeViewport());
    }

    private HomeActivity homeActivity() {
        return getActivity() instanceof HomeActivity ? (HomeActivity) getActivity() : null;
    }


    class PageAdapter extends FragmentStatePagerAdapter {

        public PageAdapter(@NonNull FragmentManager fm) {
            super(fm);
        }

        @NonNull
        @Override
        public Fragment getItem(int position) {
            Class type = mAdapter.get(position);
            return FolderFragment.newInstance(getHome().getKey(), type, 4);
        }

        @Override
        public int getCount() {
            return mAdapter.getItemCount();
        }

        @Override
        public void destroyItem(@NonNull ViewGroup container, int position, @NonNull Object object) {
        }
    }
}
