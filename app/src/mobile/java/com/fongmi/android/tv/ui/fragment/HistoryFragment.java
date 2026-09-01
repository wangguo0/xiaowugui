package com.fongmi.android.tv.ui.fragment;

import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.Product;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.History;
import com.fongmi.android.tv.databinding.FragmentHistoryBinding;
import com.fongmi.android.tv.event.RefreshEvent;
import com.fongmi.android.tv.ui.activity.HomeActivity;
import com.fongmi.android.tv.ui.activity.VideoActivity;
import com.fongmi.android.tv.ui.adapter.HistoryAdapter;
import com.fongmi.android.tv.ui.base.BaseFragment;
import com.fongmi.android.tv.ui.dialog.SyncDialog;
import com.fongmi.android.tv.utils.MobileWindow;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

public class HistoryFragment extends BaseFragment implements HistoryAdapter.OnClickListener {

    private FragmentHistoryBinding mBinding;
    private HistoryAdapter mAdapter;

    public static HistoryFragment newInstance() {
        return new HistoryFragment();
    }

    @Override
    protected ViewBinding getBinding(@NonNull LayoutInflater inflater, @Nullable ViewGroup container) {
        return mBinding = FragmentHistoryBinding.inflate(inflater, container, false);
    }

    @Override
    protected void initView() {
        setRecyclerView();
        mBinding.toolbar.setOnMenuItemClickListener(this::onMenuItemClick);
        setNavigationIcon();
        getHistory();
    }

    // 独立页面打开时显示返回箭头；内嵌首页时由底部导航切换，无需返回
    private void setNavigationIcon() {
        if (requireActivity() instanceof HomeActivity) return;
        mBinding.toolbar.setNavigationIcon(R.drawable.ic_control_back);
        mBinding.toolbar.setNavigationOnClickListener(v -> requireActivity().finish());
    }

    @Override
    protected void initEvent() {
        EventBus.getDefault().register(this);
    }

    private void setRecyclerView() {
        int column = MobileWindow.isWide(requireActivity()) ? Product.getColumn(requireActivity()) : 3;
        mBinding.recycler.setHasFixedSize(true);
        mBinding.recycler.setLayoutManager(new GridLayoutManager(requireContext(), column));
        mBinding.recycler.setAdapter(mAdapter = new HistoryAdapter(this));
        mAdapter.setSize(Product.getSpec(requireActivity(), column));
    }

    private void getHistory() {
        mAdapter.setItems(History.get(), (hasChange) -> {
            mBinding.progressLayout.showContent(true, mAdapter.getItemCount());
            if (hasChange) mBinding.recycler.scrollToPosition(0);
        });
    }

    private boolean onMenuItemClick(android.view.MenuItem item) {
        if (item.getItemId() == R.id.delete) onDelete();
        else if (item.getItemId() == R.id.sync) SyncDialog.create().history().show(requireActivity());
        else return false;
        return true;
    }

    private void onDelete() {
        if (mAdapter.isDelete()) {
            new MaterialAlertDialogBuilder(requireActivity()).setTitle(R.string.dialog_delete_record).setMessage(R.string.dialog_delete_history).setNegativeButton(R.string.dialog_negative, null).setPositiveButton(R.string.dialog_positive, (dialog, which) -> mAdapter.clear()).show();
        } else if (mAdapter.getItemCount() > 0) {
            mAdapter.setDelete(true);
        }
    }

    @Override
    public boolean canBack() {
        if (!mAdapter.isDelete()) return true;
        mAdapter.setDelete(false);
        return false;
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onRefreshEvent(RefreshEvent event) {
        if (event.getType().equals(RefreshEvent.Type.HISTORY)) getHistory();
    }

    @Override
    public void onItemClick(History item) {
        VideoActivity.start(requireActivity(), item.getSiteKey(), item.getVodId(), item.getVodName(), item.getVodPic(), null, item.getWallPic());
    }

    @Override
    public void onItemDelete(History item) {
        mAdapter.remove(item.deleteAndSync(), () -> {
            if (mAdapter.getItemCount() == 0) mAdapter.setDelete(false);
        });
    }

    @Override
    public boolean onLongClick() {
        mAdapter.setDelete(!mAdapter.isDelete());
        return true;
    }

    @Override
    public void onDestroyView() {
        EventBus.getDefault().unregister(this);
        super.onDestroyView();
    }
}
