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
import com.fongmi.android.tv.event.CollectFailEvent;
import com.fongmi.android.tv.model.SiteViewModel;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.setting.SiteBlockSetting;
import com.fongmi.android.tv.setting.SiteHealthStore;
import com.fongmi.android.tv.ui.activity.FolderActivity;
import com.fongmi.android.tv.ui.activity.VideoActivity;
import com.fongmi.android.tv.ui.adapter.SearchAdapter;
import com.fongmi.android.tv.ui.base.BaseFragment;
import com.fongmi.android.tv.utils.MobileWindow;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.utils.Task;
import com.fongmi.android.tv.utils.VodMatcher;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class CollectFragment extends BaseFragment implements MenuProvider, SearchAdapter.OnClickListener {

    private static final int GRID_ITEM_MARGIN_DP = 4;
    private static final int GRID_TOP_PADDING_DP = 8;
    private static final long REFRESH_THROTTLE_MS = 400;
    private static final int MAX_RAW_RESULTS = 5000;
    private static final int PAGE_SIZE = 12;
    private static final int LOAD_MORE_SIZE = 10;

    private FragmentCollectBinding mBinding;
    private SearchAdapter mSearchAdapter;
    private SiteViewModel mViewModel;
    private List<Site> mSites;
    // 后备池：所有站点返回的原始结果，冻结后只静默累积，不做任何加工
    private final List<Vod> mAllResults = new ArrayList<>();
    // 已展示卡片：首屏 PAGE_SIZE 条一旦提交即钉死，「加载更多」只在其后追加，绝不重排
    private final List<Vod> mDisplayed = new ArrayList<>();
    // 剩余候选：最近一次全量清洗（去重+排序）后、尚未展示的条目，按相关性降序
    private final List<Vod> mRest = new ArrayList<>();
    // 上次清洗时后备池的规模：用于判断池内是否还有未参与过排序的新结果
    private int mCleanedCount;
    // 已展示卡片按归一化片名分桶，供「加载更多」O(1) 判重（不改动已展示卡片，重复项直接丢弃）
    // 冻结后原位换源的相关度门槛：关键词长度/片名长度 > 0.9 才允许替换已展示卡片
    private static final double UPGRADE_MIN_SCORE = 0.9;
    private final Map<String, List<Vod>> mShownByName = new HashMap<>();
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private boolean mRefreshPending;
    private boolean mRefreshing;
    private boolean mRefreshAgain;
    // 首屏集满即绝对冻结：后到结果（含 100% 精确匹配）一律不改变已展示卡片
    private boolean mFrozen;
    // 「加载更多」后台清洗单飞：未完成前不叠加任务，避免低性能设备排序任务堆积
    private boolean mLoadingMore;
    // 全部站点是否均已返回（决定后备池还会不会继续增长）
    private boolean mAllReturned;

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
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 订阅「立马返回」事件：详情失败的卡片需要原地轮转到下一个同名站源
        EventBus.getDefault().register(this);
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
        mViewModel.getSearchProgress().observe(this, progress -> {
            // 全部站点均已返回（成功或失败）：后备池不再增长，补做一次收尾清洗提交
            if (progress == null || !progress.finished()) return;
            mAllReturned = true;
            if (!mFrozen) scheduleRefresh();
            // 冻结状态下也要重提交一次：否则末次提交（当时还有站点未返回）留下的
            // 「加载更多」footer 会一直残留，即使剩余候选已耗尽、再无新结果
            else submitPage();
        });
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
        mDisplayed.clear();
        mRest.clear();
        mShownByName.clear();
        mCleanedCount = 0;
        mRefreshPending = false;
        mRefreshing = false;
        mRefreshAgain = false;
        mFrozen = false;
        mLoadingMore = false;
        mAllReturned = false;
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
        // 绝对冻结：首屏集满后本方法只做一次入池，不拷贝快照、不排期刷新；
        // 仅允许相关度 > 90% 的高相关卡片原位换到更健康的播放站点
        if (mFrozen) {
            upgradeShownForFrozen(result.getList());
            return;
        }
        scheduleRefresh();
    }

    // 首屏填充节流：未冻结时每 REFRESH_THROTTLE_MS 最多整理并提交一次，
    // 去重与排序在后台线程完成；集满 PAGE_SIZE 条即置 mFrozen，此后不再刷新界面
    private void scheduleRefresh() {
        if (mFrozen || mRefreshPending) return;
        mRefreshPending = true;
        mHandler.postDelayed(this::doRefresh, REFRESH_THROTTLE_MS);
    }

    private void doRefresh() {
        mRefreshPending = false;
        if (!isAdded() || mFrozen) return;
        if (mRefreshing) {
            mRefreshAgain = true;
            return;
        }
        List<Vod> snapshot = new ArrayList<>(mAllResults);
        String keyword = getKeyword();
        mRefreshing = true;
        Task.execute(() -> {
            List<Vod> sorted = sortByRelevance(dedupe(snapshot), keyword);
            App.post(() -> {
                mRefreshing = false;
                if (!isAdded()) return;
                commitFirstPage(sorted, snapshot.size());
                if (mRefreshAgain) {
                    mRefreshAgain = false;
                    scheduleRefresh();
                }
            });
        });
    }

    // 提交首屏：前 PAGE_SIZE 条钉死展示，其余清洗结果留在剩余候选中等「加载更多」追加。
    // 集满即冻结；未集满则等全部站点返回后由 progress 收尾冻结
    private void commitFirstPage(List<Vod> sorted, int cleanedCount) {
        if (sorted.isEmpty()) return;
        int end = Math.min(PAGE_SIZE, sorted.size());
        mDisplayed.clear();
        mShownByName.clear();
        mRest.clear();
        for (int i = 0; i < end; i++) {
            Vod vod = sorted.get(i);
            mDisplayed.add(vod);
            trackShown(vod);
        }
        mRest.addAll(sorted.subList(end, sorted.size()));
        mCleanedCount = cleanedCount;
        if (mDisplayed.size() >= PAGE_SIZE) mFrozen = true;
        submitPage();
    }

    // 是否还有可能追加新卡片：剩余候选非空，或站点未全部返回（池子还会增长），
    // 或清洗池尚有未清洗结果（冻结期间入池的新结果要等「加载更多」重清洗后才可见）
    private boolean hasMore() {
        return !mRest.isEmpty() || mAllResults.size() != mCleanedCount || !mAllReturned;
    }

    // 提交界面：已展示卡片 + 「加载更多」哨兵（无更多候选则不显示入口）。
    // 卡片总量只随用户点击增长，DiffUtil 计算量始终停留在页大小量级
    private void submitPage() {
        List<Vod> page = new ArrayList<>(mDisplayed);
        if (hasMore()) page.add(SearchAdapter.FOOTER);
        else if (!mDisplayed.isEmpty()) page.add(SearchAdapter.END);
        boolean atTop = !mBinding.recycler.canScrollVertically(-1);
        mSearchAdapter.setItems(page, () -> {
            if (!isAdded() || !atTop) return;
            mBinding.recycler.scrollToPosition(0);
        });
    }

    // 加载更多：冻结期间若有新结果入池，此刻对全池重清洗一次（后台单飞，只此一轮），
    // 然后从剩余候选按相关性顺序取一页「追加」到末尾——已展示卡片的位置与内容绝不变动
    private void loadMore() {
        if (mLoadingMore) return;
        if (mAllResults.size() != mCleanedCount) reclean();
        else appendFromRest();
    }

    // 全池重清洗：去重 + 相关性排序，剔除已展示条目后重建剩余候选，再追加一页
    private void reclean() {
        mLoadingMore = true;
        List<Vod> snapshot = new ArrayList<>(mAllResults);
        String keyword = getKeyword();
        Task.execute(() -> {
            List<Vod> sorted = sortByRelevance(dedupe(snapshot), keyword);
            App.post(() -> {
                mLoadingMore = false;
                if (!isAdded()) return;
                mRest.clear();
                for (Vod vod : sorted) {
                    if (!mergeIntoShown(vod)) mRest.add(vod);
                }
                mCleanedCount = snapshot.size();
                appendFromRest();
            });
        });
    }

    // 从剩余候选取一页追加展示（已展示卡片钉死，只做末尾追加）
    private void appendFromRest() {
        int end = Math.min(LOAD_MORE_SIZE, mRest.size());
        if (end == 0) {
            submitPage();
            return;
        }
        for (Vod vod : mRest.subList(0, end)) {
            mDisplayed.add(vod);
            trackShown(vod);
        }
        mRest.subList(0, end).clear();
        submitPage();
    }

    // 登记已展示卡片到片名分桶，供后续判重
    private void trackShown(Vod vod) {
        mShownByName.computeIfAbsent(normalize(vod.getName()), k -> new ArrayList<>()).add(vod);
    }

    // 与已展示卡片同片名且内容不冲突（VodMatcher 五维指纹一致）即视为重复：
    // 已展示卡片钉死不可改动，故直接丢弃新到条目并返回 true
    private boolean mergeIntoShown(Vod vod) {
        List<Vod> shown = mShownByName.get(normalize(vod.getName()));
        if (shown == null) return false;
        for (Vod item : shown) {
            if (!VodMatcher.isConflict(item, vod)) return true;
        }
        return false;
    }

    // 冻结后原位换源：新到结果与已展示卡片同名同簇、相关度 > 90% 且新站点健康度严格更优时，
    // 原位替换该卡片（位置不变，仅换播放站点），保证高相关结果拿到最优播放源。
    // 每条新结果仅做一次归一化 + 哈希查桶，未命中直接跳过，冻结期主线程开销可忽略
    private void upgradeShownForFrozen(List<Vod> items) {
        String word = normalize(getKeyword());
        if (word.isEmpty()) return;
        boolean changed = false;
        for (Vod vod : items) {
            String key = normalize(vod.getName());
            if (getScore(key, word) <= UPGRADE_MIN_SCORE) continue;
            List<Vod> shown = mShownByName.get(key);
            if (shown == null || shown.isEmpty()) continue;
            for (int i = 0; i < shown.size(); i++) {
                Vod item = shown.get(i);
                if (VodMatcher.isConflict(item, vod)) continue;
                if (SiteHealthStore.compareVods(item, vod) <= 0) break;
                int index = -1;
                for (int j = 0; j < mDisplayed.size(); j++) {
                    if (mDisplayed.get(j) == item) {
                        index = j;
                        break;
                    }
                }
                if (index < 0) break;
                fillFields(vod, item);
                mDisplayed.set(index, vod);
                shown.set(i, vod);
                changed = true;
                break;
            }
        }
        if (changed) submitPage();
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

    // 卡片轮转：搜索入口进详情页「立马返回」（详情为空）后，把失败卡片原地换成
    // 原始结果池中同名同内容（片名全等 + 五维指纹不冲突）里健康度最优的候选站点；
    // 每失败一次消耗一个候选，无候选可用时 toast 提示
    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onEventMainThread(CollectFailEvent event) {
        if (!isAdded() || mSearchAdapter == null) return;
        int index = -1;
        Vod failed = null;
        for (int i = 0; i < mDisplayed.size(); i++) {
            Vod vod = mDisplayed.get(i);
            if (vod.getSiteKey().equals(event.siteKey()) && vod.getId().equals(event.vodId())) {
                index = i;
                failed = vod;
                break;
            }
        }
        // 卡片已被列表刷新移除/替换：过期事件直接忽略
        if (failed == null) return;
        String key = normalize(failed.getName());
        Vod best = null;
        for (Vod vod : mAllResults) {
            if (vod.isFolder()) continue;
            if (vod.getSiteKey().equals(event.siteKey()) && vod.getId().equals(event.vodId())) continue;
            if (!normalize(vod.getName()).equals(key)) continue;
            if (VodMatcher.isConflict(failed, vod)) continue;
            if (isTaken(vod)) continue;
            if (best == null || SiteHealthStore.compareVods(best, vod) > 0) best = vod;
        }
        // 消费失败条目：从原始池移除，避免轮转时再次选中或「加载更多」重清洗后又生成卡片
        mAllResults.removeIf(vod -> vod.getSiteKey().equals(event.siteKey()) && vod.getId().equals(event.vodId()));
        if (best == null) {
            Notify.show(getString(R.string.video_error_no_site));
            return;
        }
        fillFields(best, failed);
        mDisplayed.set(index, best);
        List<Vod> shown = mShownByName.get(key);
        if (shown != null) {
            int j = -1;
            for (int i = 0; i < shown.size(); i++) {
                if (shown.get(i) == failed) {
                    j = i;
                    break;
                }
            }
            if (j >= 0) shown.set(j, best);
            else shown.add(best);
        }
        submitPage();
    }

    // 候选是否已被已展示卡片或剩余候选占用（按 siteKey+id 判断，Vod.equals 只比 id 不可靠）
    private boolean isTaken(Vod vod) {
        for (Vod item : mDisplayed) {
            if (item.getSiteKey().equals(vod.getSiteKey()) && item.getId().equals(vod.getId())) return true;
        }
        for (Vod item : mRest) {
            if (item.getSiteKey().equals(vod.getSiteKey()) && item.getId().equals(vod.getId())) return true;
        }
        return false;
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
    public void onDestroy() {
        EventBus.getDefault().unregister(this);
        super.onDestroy();
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