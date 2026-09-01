package com.fongmi.android.tv.ui.fragment;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.Product;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.bangumi.BangumiRepository;
import com.fongmi.android.tv.bean.Bangumi;
import com.fongmi.android.tv.bean.BangumiBind;
import com.fongmi.android.tv.databinding.FragmentBangumiBinding;
import com.fongmi.android.tv.ui.activity.SearchActivity;
import com.fongmi.android.tv.ui.activity.VideoActivity;
import com.fongmi.android.tv.ui.adapter.BangumiAdapter;
import com.fongmi.android.tv.ui.base.BaseFragment;
import com.fongmi.android.tv.utils.MobileWindow;
import com.fongmi.android.tv.utils.Notify;
import com.google.android.material.textview.MaterialTextView;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

public class BangumiFragment extends BaseFragment implements BangumiAdapter.OnClickListener {

    private FragmentBangumiBinding mBinding;
    private BangumiAdapter mAdapter;
    private final List<Bangumi> mItems = new ArrayList<>();
    private final List<MaterialTextView> mTabs = new ArrayList<>();
    private int mToday;
    private int mDay;

    public static BangumiFragment newInstance() {
        return new BangumiFragment();
    }

    @Override
    protected ViewBinding getBinding(@NonNull LayoutInflater inflater, @Nullable ViewGroup container) {
        return mBinding = FragmentBangumiBinding.inflate(inflater, container, false);
    }

    @Override
    protected void initView() {
        mToday = mDay = today();
        setRecyclerView();
        setTabs();
        mBinding.refresh.setOnClickListener(v -> loadData(true));
    }

    @Override
    protected void initEvent() {
        loadData(false);
    }

    private void setRecyclerView() {
        int column = MobileWindow.isWide(requireActivity()) ? Product.getColumn(requireActivity()) : 3;
        mBinding.recycler.setHasFixedSize(true);
        mBinding.recycler.setLayoutManager(new GridLayoutManager(requireContext(), column));
        mBinding.recycler.setAdapter(mAdapter = new BangumiAdapter(this));
        mAdapter.setSize(Product.getSpec(requireActivity(), column));
    }

    private void setTabs() {
        mBinding.tabContainer.removeAllViews();
        mTabs.clear();
        String[] names = getResources().getStringArray(R.array.bangumi_week);
        for (int i = 0; i < names.length; i++) {
            int day = (mToday - 1 + i) % 7 + 1;
            MaterialTextView tab = new MaterialTextView(requireContext());
            tab.setText(day == mToday ? getString(R.string.bangumi_today) : names[day - 1]);
            tab.setTextSize(14);
            tab.setTextColor(getResources().getColorStateList(R.color.selector_bangumi_tab));
            int hPad = (int) (16 * getResources().getDisplayMetrics().density);
            int vPad = (int) (6 * getResources().getDisplayMetrics().density);
            tab.setPadding(hPad, vPad, hPad, vPad);
            tab.setBackgroundResource(R.drawable.selector_bangumi_tab);
            tab.setClickable(true);
            tab.setSelected(day == mDay);
            tab.setTag(day);
            tab.setOnClickListener(v -> selectDay((int) v.getTag()));
            ViewGroup.MarginLayoutParams lp = new ViewGroup.MarginLayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMarginEnd((int) (8 * getResources().getDisplayMetrics().density));
            mBinding.tabContainer.addView(tab, lp);
            mTabs.add(tab);
        }
    }

    private void selectDay(int day) {
        if (mDay == day) return;
        mDay = day;
        for (MaterialTextView tab : mTabs) tab.setSelected((int) tab.getTag() == mDay);
        applyFilter();
    }

    private void loadData(boolean refresh) {
        mBinding.progressLayout.showProgress();
        if (!refresh && BangumiRepository.get().loadCache(this::onResult)) return;
        BangumiRepository.get().load(this::onResult);
    }

    private void onResult(List<Bangumi> items, int source, long time) {
        if (mBinding == null) return;
        mItems.clear();
        mItems.addAll(items);
        if (source == BangumiRepository.SOURCE_STALE && !items.isEmpty()) Notify.show(R.string.bangumi_stale);
        applyFilter();
    }

    private void applyFilter() {
        List<Bangumi> filtered = new ArrayList<>();
        for (Bangumi item : mItems) if (item.contains(mDay)) filtered.add(item);
        mAdapter.setItems(filtered, hasChange -> {
            if (mBinding == null) return;
            mBinding.progressLayout.showContent(true, mAdapter.getItemCount());
            if (hasChange) mBinding.recycler.scrollToPosition(0);
        });
    }

    @Override
    public void onItemClick(Bangumi item) {
        BangumiBind bind = BangumiBind.find(item.getName());
        if (bind == null) SearchActivity.startBangumi(requireActivity(), item.getName(), item.getName());
        else VideoActivity.startBangumi(requireActivity(), item.getName(), bind.getSiteKey(), bind.getVodId(), bind.getVodName(), bind.getVodPic(), bind.getWallPic());
    }

    private int today() {
        int day = Calendar.getInstance().get(Calendar.DAY_OF_WEEK);
        return day == Calendar.SUNDAY ? Bangumi.SUNDAY : day - 1;
    }

    @Override
    public void onDestroyView() {
        mTabs.clear();
        mItems.clear();
        mBinding = null;
        super.onDestroyView();
    }
}
