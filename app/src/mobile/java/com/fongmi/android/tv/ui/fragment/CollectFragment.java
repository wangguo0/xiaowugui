package com.fongmi.android.tv.ui.fragment;

import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class CollectFragment extends BaseFragment implements MenuProvider, SearchAdapter.OnClickListener {

    private static final int GRID_ITEM_MARGIN_DP = 4;
    private static final int GRID_TOP_PADDING_DP = 8;

    private FragmentCollectBinding mBinding;
    private SearchAdapter mSearchAdapter;
    private SiteViewModel mViewModel;
    private List<Site> mSites;
    private final List<Vod> mAllResults = new ArrayList<>();

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
        mBinding.recycler.setAdapter(mSearchAdapter = new SearchAdapter(this));
        setResultLayout(false);
        mBinding.recycler.post(() -> setResultLayout(false));
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
        mSearchAdapter.setItems(sortByRelevance(dedupe(mAllResults)));
    }

    private List<Vod> dedupe(List<Vod> items) {
        Map<String, Vod> map = new LinkedHashMap<>();
        for (Vod vod : items) {
            String key = vod.getName().trim().toLowerCase(Locale.ROOT);
            Vod existing = map.get(key);
            if (existing == null || SiteHealthStore.compareVods(existing, vod) > 0) map.put(key, vod);
        }
        return new ArrayList<>(map.values());
    }

    // 按「关键词长度 / 影片名长度」计算匹配度，降序排列；完全不包含关键词的结果隐藏。
    private List<Vod> sortByRelevance(List<Vod> items) {
        String keyword = normalize(getKeyword());
        if (keyword.isEmpty()) return items;
        List<Vod> result = new ArrayList<>();
        for (Vod vod : items) {
            if (normalize(vod.getName()).contains(keyword)) result.add(vod);
        }
        result.sort(Comparator.comparingDouble((Vod vod) -> getScore(normalize(vod.getName()), keyword)).reversed());
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
        mViewModel.stopSearch();
        SiteHealthStore.flush();
        requireActivity().removeMenuProvider(this);
    }
}