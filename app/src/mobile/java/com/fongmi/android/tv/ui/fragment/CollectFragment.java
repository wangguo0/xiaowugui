package com.fongmi.android.tv.ui.fragment;

import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.view.MenuProvider;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.Product;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.databinding.FragmentCollectBinding;
import com.fongmi.android.tv.model.SiteViewModel;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.setting.SiteBlockSetting;
import com.fongmi.android.tv.setting.SiteHealthStore;
import com.fongmi.android.tv.ui.activity.FolderActivity;
import com.fongmi.android.tv.ui.activity.VideoActivity;
import com.fongmi.android.tv.ui.adapter.SearchAdapter;
import com.fongmi.android.tv.ui.base.BaseFragment;
import com.fongmi.android.tv.utils.MobileWindow;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.utils.Task;
import com.fongmi.android.tv.utils.VodMatcher;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class CollectFragment extends BaseFragment implements MenuProvider, SearchAdapter.OnClickListener {

    private static final int GRID_ITEM_MARGIN_DP = 4;
    private static final int GRID_TOP_PADDING_DP = 8;
    private static final long REFRESH_THROTTLE_MS = 400;
    private static final int MAX_RAW_RESULTS = 5000;
    private static final int MAX_DISPLAY_RESULTS = 2000;
    private static final int PAGE_SIZE = 12;
    private static final int LOAD_MORE_SIZE = 10;

    private FragmentCollectBinding mBinding;
    private SearchAdapter mSearchAdapter;
    private SiteViewModel mViewModel;
    private List<Site> mSites;
    private final List<Vod> mAllResults = new ArrayList<>();
    private List<Vod> mSortedItems = new ArrayList<>();
    private int mDisplayCount = PAGE_SIZE;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private boolean mRefreshPending;
    private boolean mRefreshing;
    private boolean mRefreshAgain;
    // 首页集满冻结：新到批次是否含 100% 精确匹配（唯一允许打破冻结的条件）
    private boolean mPendingExact;
    // 已提交到列表的卡片数（不含 footer），达到 PAGE_SIZE 即视为首页已满
    private int mCommittedCount;

    public static CollectFragment newInstance(String keyword) {
        return newInstance(keyword, null);
    }

    public static CollectFragment newInstance(String keyword, String siteKey) {
        return newInstance(keyword, siteKey, null, null);
    }

    public static CollectFragment newInstance(String keyword, String siteKey, String pic, String wallPic) {
        return newInstance(keyword, siteKey, pic, wallPic, null);
    }

    public static CollectFragment newInstance(String keyword, String siteKey, String pic, String wallPic, String bangumiName) {
        Bundle args = new Bundle();
        args.putString("keyword", keyword);
        args.putString("siteKey", siteKey);
        args.putString("pic", pic);
        args.putString("wallPic", wallPic);
        args.putString("bangumiName", bangumiName);
        CollectFragment fragment = new CollectFragment();
        fragment.setArguments(args);
        return fragment;
    }

    private String getKeyword() {
        return getArguments().getString("keyword");
    }

    private String getSiteKey() {
        return getArguments().getString("siteKey");
    }

    private String getPic() {
        return getArguments().getString("pic");
    }

    private String getWallPic() {
        return getArguments().getString("wallPic");
    }

    private String getBangumiName() {
        return getArguments().getString("bangumiName");
    }

    @Override
    protected ViewBinding getBinding(@NonNull LayoutInflater inflater, @Nullable ViewGroup container) {
        return mBinding = FragmentCollectBinding.inflate(getLayoutInflater());
    }

    @Override
    protected void initMenu() {
        if (isHidden()) return;
        AppCompatActivity activity = (AppCompatActivity) requireActivity();
        activity.setSupportActionBar(mBinding.toolbar);
        activity.getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        activity.addMenuProvider(this, getViewLifecycleOwner(), Lifecycle.State.RESUMED);
        activity.setTitle(getKeyword());
    }

    @Override
    protected void initView() {
        setSites();
        setRecyclerView();
        setViewModel();
        search();
    }

    @Override
    protected void initEvent() {
        mBinding.toolbar.setOnClickListener(v -> {
            Bundle result = new Bundle();
            result.putBoolean("edit", true);
            getParentFragmentManager().setFragmentResult("result", result);
            getParentFragmentManager().popBackStack();
        });
    }

    private void setRecyclerView() {
        mBinding.recycler.setHasFixedSize(true);
        // 关闭重排动画：搜索结果持续插入/换位时避免逐条动画卡顿，且保证顶部即时刷新
        mBinding.recycler.setItemAnimator(null);
        mBinding.recycler.setAdapter(mSearchAdapter = new SearchAdapter(this));
        mSearchAdapter.setLoadMore(this::loadMore);
        setSpanSizeLookup();
        setResultLayout(false);
        mBinding.recycler.post(() -> setResultLayout(false));
    }

    // 「加载更多」footer 在网格模式下占满整行
    private void setSpanSizeLookup() {
        if (!(mBinding.recycler.getLayoutManager() instanceof GridLayoutManager manager)) return;
        manager.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
            @Override
            public int getSpanSize(int position) {
                return mSearchAdapter.isFooter(position) ? manager.getSpanCount() : 1;
            }
        });
    }

    private void setViewModel() {
        mViewModel = new ViewModelProvider(this).get(SiteViewModel.class).init();
        mViewModel.getSearch().observe(this, this::setCollect);
    }

    private void setSites() {
        String siteKey = getSiteKey();
        mSites = SiteBlockSetting.filter(VodConfig.get().getSites(), false);
        mSites.removeIf(site -> !site.isSearchable());
        if (!TextUtils.isEmpty(siteKey)) mSites.removeIf(site -> !site.getKey().equals(siteKey));
        SiteHealthStore.sortSites(mSites);
    }

    private void search() {
        if (mSites.isEmpty()) return;
        mAllResults.clear();
        mSortedItems.clear();
        mDisplayCount = PAGE_SIZE;
        mRefreshPending = false;
        mRefreshing = false;
        mRefreshAgain = false;
        mPendingExact = false;
        mCommittedCount = 0;
        mViewModel.searchContent(mSites, getKeyword(), false);
    }

    private int getCount() {
        return Setting.getSearchColumn();
    }

    private boolean isGrid() {
        return getCount() == 2;
    }

    private int getSpanCount() {
        if (!isGrid()) return 1;
        if (!MobileWindow.isWide(requireActivity())) return 2;
        int column = Product.getColumn(requireActivity());
        int targetWidth = Product.getSpec(requireActivity(), column)[0];
        int available = getResultWidth() - getResultPadding();
        int span = targetWidth > 0 ? available / targetWidth : 2;
        return Math.max(2, Math.min(column, span));
    }

    private int getResultWidth() {
        int width = mBinding.recycler.getWidth();
        return width > 0 ? width : ResUtil.getScreenWidth(requireActivity());
    }

    private int getResultPadding() {
        return mBinding.recycler.getPaddingStart() + mBinding.recycler.getPaddingEnd();
    }

    private int[] getGridSize() {
        int span = getSpanCount();
        int margin = ResUtil.dp2px(GRID_ITEM_MARGIN_DP);
        int space = getResultPadding() + margin * 2 * span;
        int width = (getResultWidth() - space) / span;
        width = Math.max(ResUtil.dp2px(96), width);
        return new int[]{width, (int) (width / 0.75f), margin};
    }

    private void setResultLayout(boolean scrollTop) {
        int span = getSpanCount();
        ((GridLayoutManager) (mBinding.recycler.getLayoutManager())).setSpanCount(span);
        setResultPadding();
        mSearchAdapter.setGrid(isGrid(), getGridSize());
        if (scrollTop) mBinding.recycler.scrollToPosition(0);
    }

    private void setResultPadding() {
        int top = isGrid() ? ResUtil.dp2px(GRID_TOP_PADDING_DP) : 0;
        mBinding.recycler.setPadding(mBinding.recycler.getPaddingStart(), top, mBinding.recycler.getPaddingEnd(), mBinding.recycler.getPaddingBottom());
    }

    private void onColumnToggle() {
        Setting.putSearchColumn(getCount() == 1 ? 2 : 1);
        setResultLayout(true);
        requireActivity().invalidateOptionsMenu();
    }

    private void setCollect(Result result) {
        if (result == null || result.getList().isEmpty()) return;
        mAllResults.addAll(result.getList());
        if (mAllResults.size() > MAX_RAW_RESULTS) {
            mAllResults.subList(0, mAllResults.size() - MAX_RAW_RESULTS).clear();
        }
        // 检测本批是否含 100% 精确匹配（片名与关键词完全一致），这是打破首页冻结的唯一条件
        if (!mPendingExact) {
            String word = normalize(getKeyword());
            if (!word.isEmpty()) {
                for (Vod vod : result.getList()) {
                    if (normalize(vod.getName()).equals(word)) {
                        mPendingExact = true;
                        break;
                    }
                }
            }
        }
        scheduleRefresh();
    }

    // 节流刷新：无论有多少站点陆续返回，每 REFRESH_THROTTLE_MS 最多重排并刷新一次，
    // 去重与排序在后台线程完成，避免源过多时主线程被占满导致卡死。
    // 首页集满（PAGE_SIZE）后进入冻结：非 force 且新批次无 100% 精确匹配时只静默累积，
    // 不再重排提交，杜绝卡片被替换；点「加载更多」(force) 或精确匹配到达时才刷新
    private void scheduleRefresh() {
        if (mRefreshPending) return;
        mRefreshPending = true;
        mHandler.postDelayed(() -> doRefresh(false), REFRESH_THROTTLE_MS);
    }

    private void doRefresh(boolean force) {
        mRefreshPending = false;
        if (!isAdded() || mRefreshing) {
            mRefreshAgain = true;
            return;
        }
        if (!force && !mPendingExact && mCommittedCount >= PAGE_SIZE) return;
        List<Vod> snapshot = new ArrayList<>(mAllResults);
        String keyword = getKeyword();
        mRefreshing = true;
        Task.execute(() -> {
            List<Vod> sorted = sortByRelevance(dedupe(snapshot), keyword);
            List<Vod> items = sorted.size() > MAX_DISPLAY_RESULTS ? new ArrayList<>(sorted.subList(0, MAX_DISPLAY_RESULTS)) : sorted;
            App.post(() -> {
                mRefreshing = false;
                if (!isAdded()) return;
                mSortedItems = items;
                mPendingExact = false;
                applyItems();
                if (mRefreshAgain) {
                    mRefreshAgain = false;
                    scheduleRefresh();
                }
            });
        });
    }

    // 分页提交：仅显示前 mDisplayCount 条，末尾追加「加载更多」哨兵行（滑到列表底部才可见），
    // 使 DiffUtil 每次计算量固定在页大小级别，避免结果过多卡顿
    private void applyItems() {
        int total = mSortedItems.size();
        int end = Math.min(mDisplayCount, total);
        List<Vod> page = new ArrayList<>(mSortedItems.subList(0, end));
        if (end < total) page.add(SearchAdapter.FOOTER);
        mCommittedCount = end;
        boolean atTop = !mBinding.recycler.canScrollVertically(-1);
        mSearchAdapter.setItems(page, () -> {
            if (!isAdded()) return;
            if (atTop) mBinding.recycler.scrollToPosition(0);
        });
    }

    // 加载更多：强制用后台最新全量结果重排一次（冻结期间晚到的高相关结果此时一并展示），再追加一页
    private void loadMore() {
        mDisplayCount += LOAD_MORE_SIZE;
        if (mRefreshing) {
            mRefreshAgain = true;
            return;
        }
        doRefresh(true);
    }

    // 同名聚类去重：片名相同的条目按 5 维指纹（VodMatcher）分簇，
    // 无冲突者合并为一簇（取站点健康度最优为代表，并用其它成员补齐空字段），
    // 类型/年份/演员等冲突的（如动漫版与真人版同名）各自独立成卡片
    private List<Vod> dedupe(List<Vod> items) {
        Map<String, List<Vod>> groups = new LinkedHashMap<>();
        for (Vod vod : items) {
            String key = vod.getName().trim().toLowerCase(Locale.ROOT);
            List<Vod> group = groups.get(key);
            if (group == null) {
                group = new ArrayList<>();
                groups.put(key, group);
            }
            group.add(vod);
        }
        List<Vod> result = new ArrayList<>();
        for (List<Vod> group : groups.values()) {
            List<Vod> representatives = new ArrayList<>();
            for (Vod vod : group) {
                int index = -1;
                for (int i = 0; i < representatives.size(); i++) {
                    if (!VodMatcher.isConflict(representatives.get(i), vod)) {
                        index = i;
                        break;
                    }
                }
                if (index < 0) {
                    representatives.add(vod);
                } else {
                    // 同簇：健康度更优的站点作为代表卡片，另一方空字段用于补齐
                    Vod rep = representatives.get(index);
                    if (SiteHealthStore.compareVods(rep, vod) > 0) {
                        fillFields(vod, rep);
                        representatives.set(index, vod);
                    } else {
                        fillFields(rep, vod);
                    }
                }
            }
            result.addAll(representatives);
        }
        return result;
    }

    // 簇内补齐代表条目的空字段（仅使用 Vod 已有 setter，避免改动共享 bean 影响 TV 版）
    private void fillFields(Vod target, Vod other) {
        if (target.getDirector().isEmpty()) target.setDirector(other.getDirector());
        if (target.getPic().isEmpty()) target.setPic(other.getPic());
        if (target.getContent().isEmpty()) target.setContent(other.getContent());
    }

    // 按「关键词长度 / 影片名长度」计算匹配度，降序排列；完全不包含关键词的结果隐藏。
    private List<Vod> sortByRelevance(List<Vod> items, String keyword) {
        String word = normalize(keyword);
        if (word.isEmpty()) return items;
        List<Vod> result = new ArrayList<>();
        for (Vod vod : items) {
            if (normalize(vod.getName()).contains(word)) result.add(vod);
        }
        result.sort(Comparator.comparingDouble((Vod vod) -> getScore(normalize(vod.getName()), word)).reversed());
        return result;
    }

    private double getScore(String name, String keyword) {
        return (double) keyword.length() / name.length();
    }

    private String normalize(String text) {
        return text == null ? "" : text.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    @Override
    public void onItemClick(Vod item) {
        if (item.isFolder()) FolderActivity.start(requireActivity(), item.getSiteKey(), Result.folder(item));
        else {
            String pic = item.getPic().isEmpty() ? getPic() : item.getPic();
            String bangumiName = getBangumiName();
            if (bangumiName == null || bangumiName.isEmpty()) VideoActivity.collect(requireActivity(), item.getSiteKey(), item.getId(), item.getName(), pic, getWallPic());
            else VideoActivity.start(requireActivity(), item.getSiteKey(), item.getId(), item.getName(), pic, null, true, getWallPic(), null, bangumiName);
        }
    }

    @Override
    public void onCreateMenu(@NonNull Menu menu, @NonNull MenuInflater menuInflater) {
        menuInflater.inflate(R.menu.menu_collect, menu);
    }

    @Override
    public void onPrepareMenu(@NonNull Menu menu) {
        MenuItem item = menu.findItem(R.id.action_column);
        if (item == null) return;
        Drawable icon = ContextCompat.getDrawable(requireContext(), getCount() == 1 ? R.drawable.ic_site_double_column : R.drawable.ic_site_single_column);
        if (icon == null) return;
        icon = icon.mutate();
        icon.setTint(Color.WHITE);
        item.setIcon(icon);
    }

    @Override
    public boolean onMenuItemSelected(@NonNull MenuItem menuItem) {
        if (menuItem.getItemId() == android.R.id.home) requireActivity().getOnBackPressedDispatcher().onBackPressed();
        if (menuItem.getItemId() == R.id.action_column) onColumnToggle();
        return true;
    }

    @Override
    public void onHiddenChanged(boolean hidden) {
        if (hidden) requireActivity().removeMenuProvider(this);
        else initMenu();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        mHandler.removeCallbacksAndMessages(null);
        mViewModel.stopSearch();
        SiteHealthStore.flush();
        requireActivity().removeMenuProvider(this);
    }
}