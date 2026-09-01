package com.fongmi.android.tv.ui.fragment;

import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.Product;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Config;
import com.fongmi.android.tv.bean.Keep;
import com.fongmi.android.tv.databinding.FragmentKeepBinding;
import com.fongmi.android.tv.event.RefreshEvent;
import com.fongmi.android.tv.impl.Callback;
import com.fongmi.android.tv.ui.activity.HomeActivity;
import com.fongmi.android.tv.ui.activity.SearchActivity;
import com.fongmi.android.tv.ui.activity.VideoActivity;
import com.fongmi.android.tv.ui.adapter.KeepAdapter;
import com.fongmi.android.tv.ui.base.BaseFragment;
import com.fongmi.android.tv.ui.dialog.SyncDialog;
import com.fongmi.android.tv.utils.Notify;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

public class KeepFragment extends BaseFragment implements KeepAdapter.OnClickListener {

    private FragmentKeepBinding mBinding;
    private KeepAdapter mAdapter;

    public static KeepFragment newInstance() {
        return new KeepFragment();
    }

    @Override
    protected ViewBinding getBinding(@NonNull LayoutInflater inflater, @Nullable ViewGroup container) {
        return mBinding = FragmentKeepBinding.inflate(inflater, container, false);
    }

    @Override
    protected void initView() {
        setRecyclerView();
        mBinding.toolbar.setOnMenuItemClickListener(this::onMenuItemClick);
        setNavigationIcon();
        getKeep();
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
        mBinding.recycler.setHasFixedSize(true);
        mBinding.recycler.setLayoutManager(new GridLayoutManager(requireContext(), Product.getColumn(requireActivity())));
        mBinding.recycler.setAdapter(mAdapter = new KeepAdapter(this));
        mAdapter.setSize(Product.getSpec(requireActivity()));
    }

    private void getKeep() {
        mAdapter.setItems(Keep.getVod(), () -> mBinding.progressLayout.showContent(true, mAdapter.getItemCount()));
    }

    private boolean onMenuItemClick(MenuItem item) {
        if (item.getItemId() == R.id.delete) onDelete();
        else if (item.getItemId() == R.id.sync) SyncDialog.create().keep().show(requireActivity());
        else return false;
        return true;
    }

    private void onDelete() {
        if (mAdapter.isDelete()) {
            new MaterialAlertDialogBuilder(requireActivity()).setTitle(R.string.dialog_delete_record).setMessage(R.string.dialog_delete_keep).setNegativeButton(R.string.dialog_negative, null).setPositiveButton(R.string.dialog_positive, (dialog, which) -> mAdapter.clear()).show();
        } else if (mAdapter.getItemCount() > 0) {
            mAdapter.setDelete(true);
        }
    }

    private void loadConfig(Config config, Keep item) {
        VodConfig.load(config, new Callback() {
            @Override
            public void success() {
                VideoActivity.start(requireActivity(), item.getSiteKey(), item.getVodId(), item.getVodName(), item.getVodPic());
            }

            @Override
            public void error(String msg) {
                Notify.show(msg);
            }
        });
    }

    @Override
    public boolean canBack() {
        if (!mAdapter.isDelete()) return true;
        mAdapter.setDelete(false);
        return false;
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onRefreshEvent(RefreshEvent event) {
        if (event.getType().equals(RefreshEvent.Type.KEEP)) getKeep();
    }

    @Override
    public void onItemClick(Keep item) {
        Config config = Config.find(item.getCid());
        if (config == null) SearchActivity.start(requireActivity(), item.getVodName());
        else if (item.getCid() != VodConfig.getCid()) loadConfig(config, item);
        else VideoActivity.start(requireActivity(), item.getSiteKey(), item.getVodId(), item.getVodName(), item.getVodPic());
    }

    @Override
    public void onItemDelete(Keep item) {
        mAdapter.remove(item.delete(), () -> {
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
