package com.fongmi.android.tv.ui.activity;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.KeyEvent;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewbinding.ViewBinding;

import com.bumptech.glide.Glide;
import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Collect;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.databinding.ActivityCollectBinding;
import com.fongmi.android.tv.event.CollectFailEvent;
import com.fongmi.android.tv.model.SiteViewModel;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.setting.SiteBlockSetting;
import com.fongmi.android.tv.setting.SiteHealthStore;
import com.fongmi.android.tv.ui.adapter.SearchAdapter;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.KeyUtil;
import com.fongmi.android.tv.utils.QuickPoolHandoff;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.utils.Task;
import com.fongmi.android.tv.utils.VodMatcher;
import com.github.catvod.crawler.SpiderDebug;

import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class CollectActivity extends BaseActivity implements SearchAdapter.OnClickListener {

    private static final long REFRESH_THROTTLE_MS = 400;
    private static final int MAX_RAW_RESULTS = 5000;
    private static final int PAGE_SIZE = 15;
    private static final int LOAD_MORE_SIZE = 15;
    private static final double UPGRADE_MIN_SCORE = 0.9;
    // 进入/重新搜索后屏蔽确认键的时间窗（毫秒），防止残留按键误触首卡播放
    private static final long ENTER_GUARD_MS = 600;

    private ActivityCollectBinding mBinding;
    private SearchAdapter mSearchAdapter;
    private SiteViewModel mViewModel;
    private RecyclerView.OnScrollListener mImageScrollListener;
    private List<Site> mSites;
    // 后备池：所有站点返回的原始结果，冻结后只静默累积
    private final List<Vod> mAllResults = new ArrayList<>();
    // 已展示卡片：首屏提交后钉死，「加载更多」只在其后追加
    private final List<Vod> mDisplayed = new ArrayList<>();
    // 剩余候选：最近一次全量清洗（去重+排序）后、尚未展示的条目
    private final List<Vod> mRest = new ArrayList<>();
    private int mCleanedCount;
    private final Map<String, List<Vod>> mShownByName = new HashMap<>();
    private final Map<Vod, List<Vod>> mShownMembers = new IdentityHashMap<>();
    private Map<Vod, List<Vod>> mRestMembers = new IdentityHashMap<>();
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private boolean mRefreshPending;
    private boolean mRefreshing;
    private boolean mRefreshAgain;
    private boolean mFrozen;
    private boolean mLoadingMore;
    private boolean mAllReturned;
    private boolean mScrolling;
    private boolean mLeavingForPlayback;
    // 页面刚进入时短暂屏蔽确认键，避免来自搜索页残留的 ENTER/中间键误触首卡进入播放
    private long mEnterGuardUntil;

    public static void start(Activity activity, String keyword) {
        start(activity, keyword, null);
    }

    public static void start(Activity activity, String keyword, String siteKey) {
        start(activity, keyword, siteKey, null, null);
    }

    public static void start(Activity activity, String keyword, String siteKey, String pic, String wallPic) {
        Intent intent = new Intent(activity, CollectActivity.class);
        intent.putExtra("keyword", keyword);
        intent.putExtra("siteKey", siteKey);
        intent.putExtra("pic", pic);
        intent.putExtra("wallPic", wallPic);
        activity.startActivity(intent);
    }

    private String getKeyword() {
        return getIntent().getStringExtra("keyword");
    }

    private String getSiteKey() {
        return getIntent().getStringExtra("siteKey");
    }

    private String getPic() {
        return getIntent().getStringExtra("pic");
    }

    private String getWallPic() {
        return getIntent().getStringExtra("wallPic");
    }

    @Override
    protected ViewBinding getBinding() {
        return mBinding = ActivityCollectBinding.inflate(getLayoutInflater());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        getIntent().putExtras(intent);
        if (mViewModel != null) mViewModel.stopSearch();
        mEnterGuardUntil = SystemClock.uptimeMillis() + ENTER_GUARD_MS;
        saveKeyword();
        search();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        // 进入/重新搜索刚完成时，屏蔽可能残留的确认键，避免误触焦点卡片进入播放
        if (SystemClock.uptimeMillis() < mEnterGuardUntil && KeyUtil.isEnterKey(event)) return true;
        return super.dispatchKeyEvent(event);
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        mEnterGuardUntil = SystemClock.uptimeMillis() + ENTER_GUARD_MS;
        setRecyclerView();
        setViewModel();
        saveKeyword();
        setSites();
        search();
    }

    private void setRecyclerView() {
        int count = getCount();
        mBinding.recycler.setHasFixedSize(true);
        mBinding.recycler.setItemAnimator(null);
        mBinding.recycler.setItemViewCacheSize(count * 3);
        GridLayoutManager layoutManager = new GridLayoutManager(this, count);
        layoutManager.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
            @Override
            public int getSpanSize(int position) {
                return mSearchAdapter != null && mSearchAdapter.isFooter(position) ? count : 1;
            }
        });
        mBinding.recycler.setLayoutManager(layoutManager);
        mBinding.recycler.addOnScrollListener(mImageScrollListener = new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                if (!canLoadImage()) return;
            }

            @Override
            public void onScrollStateChanged(@NonNull RecyclerView recyclerView, int newState) {
                if (!canLoadImage()) return;
                if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                    Glide.with(CollectActivity.this).resumeRequests();
                    flushPendingItems();
                } else if (mScrolling == (newState != RecyclerView.SCROLL_STATE_IDLE)) {
                    // 状态未变化
                }
                mScrolling = newState != RecyclerView.SCROLL_STATE_IDLE;
                if (mScrolling) Glide.with(CollectActivity.this).pauseRequests();
            }
        });
        mBinding.recycler.setAdapter(mSearchAdapter = new SearchAdapter(this, getItemWidth(count), getItemHeight(count)));
        mSearchAdapter.setLoadMore(this::loadMore);
    }

    private boolean canLoadImage() {
        return !isFinishing() && !isDestroyed();
    }

    private void setViewModel() {
        mViewModel = new ViewModelProvider(this).get(SiteViewModel.class).init();
        mViewModel.getSearch().observe(this, this::setCollect);
        mViewModel.getSearchProgress().observe(this, progress -> {
            if (progress == null || !progress.finished()) return;
            mAllReturned = true;
            if (!mFrozen) scheduleRefresh();
            else submitPage();
        });
    }

    private void saveKeyword() {
        String keyword = getKeyword();
        if (keyword == null || keyword.isEmpty()) return;
        List<String> items = Setting.getKeyword().isEmpty() ? new ArrayList<>() : App.gson().fromJson(Setting.getKeyword(), com.google.gson.reflect.TypeToken.getParameterized(List.class, String.class).getType());
        items.remove(keyword);
        items.add(0, keyword);
        if (items.size() > 9) items.remove(9);
        Setting.putKeyword(App.gson().toJson(items));
    }

    private void setSites() {
        String siteKey = getSiteKey();
        mSites = SiteBlockSetting.filter(VodConfig.get().getSites(), false);
        mSites.removeIf(site -> !site.isSearchable());
        if (!TextUtils.isEmpty(siteKey)) mSites.removeIf(site -> !site.getKey().equals(siteKey));
        SiteHealthStore.sortSites(mSites);
    }

    private void search() {
        mAllResults.clear();
        mDisplayed.clear();
        mRest.clear();
        mShownByName.clear();
        mShownMembers.clear();
        mRestMembers.clear();
        mCleanedCount = 0;
        mRefreshPending = false;
        mRefreshing = false;
        mRefreshAgain = false;
        mFrozen = false;
        mLoadingMore = false;
        mAllReturned = false;
        mSearchAdapter.clear();
        // clear() 会把 loadMore 置空，这里重新绑定，保证底部「加载更多」点击回调一直有效
        mSearchAdapter.setLoadMore(this::loadMore);
        mBinding.result.setText(getString(R.string.collect_result, getKeyword()));
        if (mSites.isEmpty()) return;
        mViewModel.searchContent(mSites, getKeyword(), false);
    }

    private int getCount() {
        return 5;
    }

    private int getItemWidth(int count) {
        int width = ResUtil.getScreenWidth() - ResUtil.dp2px(48);
        int spacing = ResUtil.dp2px(8) * (count - 1);
        return (width - spacing) / count;
    }

    private int getItemHeight(int count) {
        return (int) (getItemWidth(count) / 0.72f);
    }

    private void setCollect(Result result) {
        if (mLeavingForPlayback) return;
        if (result == null || result.getList().isEmpty()) return;
        mAllResults.addAll(result.getList());
        if (mAllResults.size() > MAX_RAW_RESULTS) {
            mAllResults.subList(0, mAllResults.size() - MAX_RAW_RESULTS).clear();
        }
        if (mFrozen) {
            upgradeShownForFrozen(result.getList());
            return;
        }
        scheduleRefresh();
    }

    private void scheduleRefresh() {
        if (mFrozen || mRefreshPending) return;
        mRefreshPending = true;
        mHandler.postDelayed(this::doRefresh, REFRESH_THROTTLE_MS);
    }

    private void doRefresh() {
        mRefreshPending = false;
        if (isFinishing() || mFrozen) return;
        if (mRefreshing) {
            mRefreshAgain = true;
            return;
        }
        List<Vod> snapshot = new ArrayList<>(mAllResults);
        String keyword = getKeyword();
        mRefreshing = true;
        Task.execute(() -> {
            List<List<Vod>> clusters = dedupe(snapshot);
            List<Vod> sorted = sortByRelevance(repsOf(clusters), keyword);
            Map<Vod, List<Vod>> members = membersOf(clusters);
            App.post(() -> {
                mRefreshing = false;
                if (isFinishing()) return;
                commitFirstPage(sorted, snapshot.size(), members);
                if (mRefreshAgain) {
                    mRefreshAgain = false;
                    scheduleRefresh();
                }
            });
        });
    }

    private void commitFirstPage(List<Vod> sorted, int cleanedCount, Map<Vod, List<Vod>> members) {
        if (sorted.isEmpty()) return;
        int end = Math.min(PAGE_SIZE, sorted.size());
        mDisplayed.clear();
        mShownByName.clear();
        mShownMembers.clear();
        mRest.clear();
        for (int i = 0; i < end; i++) {
            Vod vod = sorted.get(i);
            mDisplayed.add(vod);
            trackShown(vod);
            List<Vod> list = members.get(vod);
            if (list != null) mShownMembers.put(vod, list);
        }
        mRest.addAll(sorted.subList(end, sorted.size()));
        mRestMembers = members;
        mCleanedCount = cleanedCount;
        if (mDisplayed.size() >= PAGE_SIZE) mFrozen = true;
        submitPage();
    }

    private boolean hasMore() {
        return !mRest.isEmpty() || mAllResults.size() != mCleanedCount || !mAllReturned;
    }

    private void submitPage() {
        List<Vod> page = new ArrayList<>(mDisplayed);
        if (hasMore()) page.add(SearchAdapter.FOOTER);
        else if (!mDisplayed.isEmpty()) page.add(SearchAdapter.END);
        boolean atTop = !mBinding.recycler.canScrollVertically(-1);
        mSearchAdapter.setItems(page, () -> {
            if (isFinishing() || !atTop) return;
            mBinding.recycler.scrollToPosition(0);
        });
    }

    private void loadMore() {
        if (mLoadingMore) return;
        if (mAllResults.size() != mCleanedCount) reclean();
        else appendFromRest();
    }

    private void reclean() {
        mLoadingMore = true;
        List<Vod> snapshot = new ArrayList<>(mAllResults);
        String keyword = getKeyword();
        Task.execute(() -> {
            List<List<Vod>> clusters = dedupe(snapshot);
            List<Vod> sorted = sortByRelevance(repsOf(clusters), keyword);
            Map<Vod, List<Vod>> members = membersOf(clusters);
            App.post(() -> {
                mLoadingMore = false;
                if (isFinishing()) return;
                mRest.clear();
                for (Vod vod : sorted) {
                    if (!mergeIntoShown(vod, members.get(vod))) mRest.add(vod);
                }
                mRestMembers = members;
                mCleanedCount = snapshot.size();
                appendFromRest();
            });
        });
    }

    private void appendFromRest() {
        int end = Math.min(LOAD_MORE_SIZE, mRest.size());
        if (end == 0) {
            submitPage();
            return;
        }
        for (Vod vod : mRest.subList(0, end)) {
            mDisplayed.add(vod);
            trackShown(vod);
            List<Vod> list = mRestMembers.get(vod);
            if (list != null) mShownMembers.put(vod, list);
        }
        mRest.subList(0, end).clear();
        submitPage();
    }

    private void trackShown(Vod vod) {
        mShownByName.computeIfAbsent(normalize(vod.getName()), k -> new ArrayList<>()).add(vod);
    }

    private boolean mergeIntoShown(Vod vod, List<Vod> members) {
        List<Vod> shown = mShownByName.get(normalize(vod.getName()));
        if (shown == null) return false;
        for (Vod item : shown) {
            if (item == vod) return true;
            if (!clusterMatchesShown(item, vod, members)) continue;
            List<Vod> target = mShownMembers.computeIfAbsent(item, k -> new ArrayList<>());
            target.add(vod);
            if (members != null) for (Vod member : members) if (member != item) target.add(member);
            return true;
        }
        return false;
    }

    private boolean clusterMatchesShown(Vod shown, Vod rep, List<Vod> members) {
        if (!sameCluster(shown, rep)) return false;
        if (members == null) return true;
        for (Vod member : members) if (!sameCluster(shown, member)) return false;
        return true;
    }

    private boolean sameCluster(Vod shown, Vod vod) {
        if (VodMatcher.isConflict(shown, vod)) return false;
        List<Vod> members = mShownMembers.get(shown);
        if (members == null) return true;
        for (Vod member : members) if (VodMatcher.isConflict(member, vod)) return false;
        return true;
    }

    private void migrateMembers(Vod from, Vod to) {
        List<Vod> members = mShownMembers.remove(from);
        if (members == null) members = new ArrayList<>();
        members.add(from);
        mShownMembers.computeIfAbsent(to, k -> new ArrayList<>()).addAll(members);
    }

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
                if (!sameCluster(item, vod)) continue;
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
                migrateMembers(item, vod);
                changed = true;
                break;
            }
        }
        if (changed) submitPage();
    }

    private List<List<Vod>> dedupe(List<Vod> items) {
        Map<String, List<Vod>> groups = new LinkedHashMap<>();
        for (Vod vod : items) {
            String name = vod.getName();
            String key = name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
            List<Vod> group = groups.get(key);
            if (group == null) {
                group = new ArrayList<>();
                groups.put(key, group);
            }
            group.add(vod);
        }
        List<List<Vod>> result = new ArrayList<>();
        for (List<Vod> group : groups.values()) {
            List<List<Vod>> clusters = new ArrayList<>();
            for (Vod vod : group) {
                List<Vod> target = null;
                for (List<Vod> cluster : clusters) {
                    if (!conflictsWithAny(cluster, vod)) {
                        target = cluster;
                        break;
                    }
                }
                if (target == null) {
                    target = new ArrayList<>();
                    target.add(vod);
                    clusters.add(target);
                } else {
                    target.add(vod);
                }
            }
            for (List<Vod> cluster : clusters) {
                int best = 0;
                for (int i = 1; i < cluster.size(); i++) {
                    if (SiteHealthStore.compareVods(cluster.get(best), cluster.get(i)) > 0) best = i;
                }
                if (best > 0) cluster.add(0, cluster.remove(best));
                Vod rep = cluster.get(0);
                for (int i = 1; i < cluster.size(); i++) fillFields(rep, cluster.get(i));
                result.add(cluster);
            }
        }
        return result;
    }

    private static boolean conflictsWithAny(List<Vod> cluster, Vod vod) {
        for (Vod member : cluster) if (VodMatcher.isConflict(member, vod)) return true;
        return false;
    }

    private static List<Vod> repsOf(List<List<Vod>> clusters) {
        List<Vod> result = new ArrayList<>();
        for (List<Vod> cluster : clusters) result.add(cluster.get(0));
        return result;
    }

    private static Map<Vod, List<Vod>> membersOf(List<List<Vod>> clusters) {
        Map<Vod, List<Vod>> result = new IdentityHashMap<>();
        for (List<Vod> cluster : clusters) {
            if (cluster.size() > 1) result.put(cluster.get(0), new ArrayList<>(cluster.subList(1, cluster.size())));
        }
        return result;
    }

    private void fillFields(Vod target, Vod other) {
        if (target.getDirector().isEmpty()) target.setDirector(other.getDirector());
        if (target.getPic().isEmpty()) target.setPic(other.getPic());
        if (target.getContent().isEmpty()) target.setContent(other.getContent());
    }

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

    private void flushPendingItems() {
    }

    @Override
    public void onItemClick(Vod item) {
        long start = System.currentTimeMillis();
        setResult(Activity.RESULT_OK);
        mLeavingForPlayback = true;
        removeCallbacks();
        SpiderDebug.log("collect-flow", "item click site=%s id=%s name=%s folder=%s", item.getSiteKey(), item.getId(), item.getName(), item.isFolder());
        if (item.isFolder()) {
            VodActivity.start(this, item.getSiteKey(), Result.folder(item));
        } else {
            List<Vod> cluster = new ArrayList<>();
            List<Vod> members = mShownMembers.get(item);
            if (members != null) cluster.addAll(members);
            cluster.add(item);
            QuickPoolHandoff.put(cluster);
            String pic = item.getPic().isEmpty() ? getPic() : item.getPic();
            VideoActivity.collect(this, item.getSiteKey(), item.getId(), item.getName(), pic, getWallPic());
        }
        SpiderDebug.log("collect-flow", "activity launch requested cost=%dms", System.currentTimeMillis() - start);
        App.post(() -> {
            if (canLoadImage()) Glide.with(this).pauseRequests();
        }, 200);
    }

    @Override
    public boolean onItemKey(int position, int keyCode, KeyEvent event) {
        if (event.getAction() != KeyEvent.ACTION_DOWN || position < 0) return false;
        int count = getCount();
        if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
            return position >= mSearchAdapter.getItemCount() - count;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP) return position < count;
        if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) return position % count == count - 1;
        return false;
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onEventMainThread(CollectFailEvent event) {
        if (mSearchAdapter == null) return;
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
        if (failed == null) return;
        String key = normalize(failed.getName());
        Vod best = null;
        for (Vod vod : mAllResults) {
            if (vod.isFolder()) continue;
            if (vod.getSiteKey().equals(event.siteKey()) && vod.getId().equals(event.vodId())) continue;
            if (!normalize(vod.getName()).equals(key)) continue;
            if (!sameCluster(failed, vod)) continue;
            if (isTaken(vod)) continue;
            if (best == null || SiteHealthStore.compareVods(best, vod) > 0) best = vod;
        }
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
        migrateMembers(failed, best);
        submitPage();
    }

    private boolean isTaken(Vod vod) {
        for (Vod item : mDisplayed) {
            if (item.getSiteKey().equals(vod.getSiteKey()) && item.getId().equals(vod.getId())) return true;
        }
        for (Vod item : mRest) {
            if (item.getSiteKey().equals(vod.getSiteKey()) && item.getId().equals(vod.getId())) return true;
        }
        return false;
    }

    private void removeCallbacks() {
        mHandler.removeCallbacksAndMessages(null);
    }

    @Override
    protected void onBackInvoked() {
        removeCallbacks();
        if (mViewModel != null) mViewModel.stopSearch();
        super.onBackInvoked();
    }

    @Override
    protected void onResume() {
        super.onResume();
        mLeavingForPlayback = false;
        if (canLoadImage()) Glide.with(this).resumeRequests();
    }

    @Override
    protected void onDestroy() {
        if (mBinding != null) {
            if (mImageScrollListener != null) mBinding.recycler.removeOnScrollListener(mImageScrollListener);
        }
        if (mViewModel != null) mViewModel.stopSearch();
        removeCallbacks();
        SiteHealthStore.flush();
        super.onDestroy();
    }
}