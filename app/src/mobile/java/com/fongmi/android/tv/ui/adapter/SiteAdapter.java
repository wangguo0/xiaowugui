package com.fongmi.android.tv.ui.adapter;

import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.databinding.AdapterSiteBinding;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.setting.SiteHealthStore;
import com.fongmi.android.tv.setting.SiteBlockSetting;
import com.fongmi.android.tv.setting.SiteOrderStore;
import com.fongmi.android.tv.utils.ResUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class SiteAdapter extends RecyclerView.Adapter<SiteAdapter.ViewHolder> {

    private final OnClickListener listener;
    private final List<Site> mAllItems;
    private final List<Site> mItems;
    private String group;
    private String keyword = "";
    private boolean search;
    private boolean change;
    private boolean block;
    private int column = 1;

    // 列筛选条件：null / 空 表示全部
    private SiteHealthStore.Status healthFilter; // 连接状态
    private String nameFilter;                   // 站源名称首字母（A-Z / "#"，null 全部）
    private Boolean searchFilter;                // 是否参与搜索
    private Integer blockFilter;                 // 屏蔽状态（0 参与换源 1 关闭换源 2 彻底屏蔽 3 已屏蔽·系统强制）

    public SiteAdapter(OnClickListener listener) {
        this.listener = listener;
        this.mAllItems = new ArrayList<>();
        this.mItems = new ArrayList<>();
        this.addAll();
    }

    public interface OnClickListener {

        void onTextClick(Site item);

        void onSearchClick(int position, Site item, View anchor);

        void onChangeClick(int position, Site item, View anchor);

        boolean onTextLongClick(ViewHolder holder);

        boolean onSearchLongClick(Site item);

        boolean onChangeLongClick(Site item);
    }

    public SiteAdapter search(boolean search) {
        this.search = search;
        return this;
    }

    public SiteAdapter change(boolean change) {
        this.change = change;
        return this;
    }

    public SiteAdapter block(boolean block) {
        if (this.block == block) return this;
        this.block = block;
        reload();
        return this;
    }

    public void column(int column) {
        int value = Math.max(1, column);
        if (this.column == value) return;
        this.column = value;
        notifyDataSetChanged();
    }

    public void reload() {
        mAllItems.clear();
        addAll();
    }

    public int indexOf(Site site) {
        return mItems.indexOf(site);
    }

    private void addAll() {
        mAllItems.addAll(SiteBlockSetting.filter(VodConfig.get().getSites(), block));
        if (Setting.isSiteHealthDialogSort()) SiteHealthStore.sortSites(mAllItems);
        SiteOrderStore.sortSites(mAllItems);
        filter(group, keyword);
    }

    public List<Site> getItems() {
        return mItems;
    }

    public int getSelectedPosition() {
        for (int i = 0; i < mItems.size(); i++) if (mItems.get(i).isSelected()) return i;
        return 0;
    }

    public void filter(String keyword) {
        filter(group, keyword);
    }

    public void filter(String group, String keyword) {
        this.group = group;
        this.keyword = keyword;
        String text = keyword == null ? "" : keyword.trim().toLowerCase(Locale.ROOT);
        boolean searching = !TextUtils.isEmpty(text);
        mItems.clear();
        for (Site site : mAllItems) {
            String name = site.getName();
            String key = site.getKey();
            boolean matchGroup = searching || site.inGroup(group);
            boolean matchName = !TextUtils.isEmpty(name) && name.toLowerCase(Locale.ROOT).contains(text);
            boolean matchKey = !TextUtils.isEmpty(key) && key.toLowerCase(Locale.ROOT).contains(text);
            boolean matchKeyword = !searching || matchName || matchKey;
            if (matchGroup && matchKeyword && matchFilters(site)) mItems.add(site);
        }
        notifyDataSetChanged();
    }

    /** 应用四列表头筛选条件（连接状态 / 站源名称首字母 / 参与搜索 / 屏蔽状态），条件为空表示该列筛选全部 */
    public boolean matchFilters(Site site) {
        if (healthFilter != null && SiteHealthStore.getStatus(site) != healthFilter) return false;
        if (nameFilter != null) {
            char first = !TextUtils.isEmpty(site.getName()) ? Character.toUpperCase(site.getName().charAt(0)) : '#';
            boolean letter = first >= 'A' && first <= 'Z';
            String bucket = letter ? String.valueOf(first) : "#";
            if (!bucket.equals(nameFilter)) return false;
        }
        if (searchFilter != null && site.isSearchable() != searchFilter) return false;
        if (blockFilter != null) {
            boolean blocked = SiteBlockSetting.isBlocked(site);
            boolean locked = SiteBlockSetting.isLocked(site);
            int state;
            if (blocked && locked) state = 3;
            else if (blocked) state = 2;
            else state = site.isChangeable() ? 0 : 1;
            if (state != blockFilter) return false;
        }
        return true;
    }

    public void setHealthFilter(SiteHealthStore.Status status) {
        this.healthFilter = status;
        filter(group, keyword);
    }

    public void setNameFilter(String letter) {
        this.nameFilter = letter;
        filter(group, keyword);
    }

    public void setSearchFilter(Boolean searchable) {
        this.searchFilter = searchable;
        filter(group, keyword);
    }

    public void setBlockFilter(Integer state) {
        this.blockFilter = state;
        filter(group, keyword);
    }

    public void clearFilters() {
        healthFilter = null;
        nameFilter = null;
        searchFilter = null;
        blockFilter = null;
        filter(group, keyword);
    }

    public boolean drag(int from, int to) {
        if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) return false;
        if (from < 0 || to < 0 || from >= mItems.size() || to >= mItems.size() || from == to) return false;
        Site moving = mItems.get(from);
        Site target = mItems.get(to);
        moveAllItem(moving, target, from < to);
        mItems.remove(from);
        mItems.add(to, moving);
        notifyItemMoved(from, to);
        SiteOrderStore.save(mAllItems);
        return true;
    }

    private void moveAllItem(Site moving, Site target, boolean afterTarget) {
        int oldIndex = mAllItems.indexOf(moving);
        int targetIndex = mAllItems.indexOf(target);
        if (oldIndex < 0 || targetIndex < 0 || oldIndex == targetIndex) return;
        mAllItems.remove(oldIndex);
        if (oldIndex < targetIndex) targetIndex--;
        int insertIndex = afterTarget ? targetIndex + 1 : targetIndex;
        mAllItems.add(Math.max(0, Math.min(insertIndex, mAllItems.size())), moving);
    }

    @Override
    public int getItemCount() {
        return mItems.size();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new ViewHolder(AdapterSiteBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        Site item = mItems.get(position);
        boolean blocked = SiteBlockSetting.isBlocked(item);
        boolean on = block || !search || change;
        boolean singleColumn = column == 1;
        holder.binding.text.setText(item.getName());
        holder.binding.health.setBackgroundTintList(ColorStateList.valueOf(SiteHealthStore.getColor(item)));
        holder.binding.text.setEnabled(on);
        holder.binding.text.setFocusable(on);
        holder.binding.text.setSelected(block ? blocked : on && item.isSelected());
        holder.binding.text.setAlpha(block && blocked ? 0.55f : 1.0f);
        holder.binding.health.setAlpha(block && blocked ? 0.55f : 1.0f);
        holder.binding.search.setText(getSearchLabel(item));
        holder.binding.change.setText(getChangeLabel(item));
        setSearchChip(holder.binding.search, item);
        setChangeChip(holder.binding.change, item);
        holder.binding.search.setVisibility(block && search && singleColumn ? View.VISIBLE : View.GONE);
        holder.binding.change.setVisibility(block && change && singleColumn ? View.VISIBLE : View.GONE);
        holder.binding.text.setOnClickListener(v -> listener.onTextClick(item));
        holder.binding.search.setOnClickListener(v -> listener.onSearchClick(position, item, v));
        holder.binding.change.setOnClickListener(v -> listener.onChangeClick(position, item, v));
        holder.binding.text.setOnLongClickListener(v -> listener.onTextLongClick(holder));
        holder.binding.search.setOnLongClickListener(v -> listener.onSearchLongClick(item));
        holder.binding.change.setOnLongClickListener(v -> listener.onChangeLongClick(item));
    }

    private int getSearchLabel(Site item) {
        return item.isSearchable() ? R.string.site_state_search_on : R.string.site_state_search_off;
    }

    // 三态：彻底屏蔽 > 关闭换源 > 参与换源（系统强制屏蔽显示专用标签）
    private int getChangeLabel(Site item) {
        if (SiteBlockSetting.isBlocked(item)) return SiteBlockSetting.isLocked(item) ? R.string.site_state_blocked_locked : R.string.site_state_blocked;
        return item.isChangeable() ? R.string.site_state_change_on : R.string.site_state_change_off;
    }

    // 胶囊底色（圆角）应用
    private void setSearchChip(android.view.View view, Site item) {
        if (item.isSearchable()) setChip(view, 0xFF0B8043, Color.WHITE);
        else setChip(view, 0xFFFF5252, Color.WHITE);
    }

    private void setChangeChip(android.view.View view, Site item) {
        if (SiteBlockSetting.isBlocked(item)) setChip(view, 0xFFFF5252, Color.WHITE);
        else if (item.isChangeable()) setChip(view, 0xFF0B8043, Color.WHITE);
        else setChip(view, 0xFFFFD54F, 0xFF202124);
    }

    // 生成圆角胶囊并按状态填充底色
    private static void setChip(android.view.View view, int bg, int fg) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(bg);
        drawable.setCornerRadius(ResUtil.dp2px(20));
        view.setBackground(drawable);
        ((androidx.appcompat.widget.AppCompatTextView) view).setTextColor(fg);
        view.setPadding(ResUtil.dp2px(8), ResUtil.dp2px(4), ResUtil.dp2px(8), ResUtil.dp2px(4));
    }

    public class ViewHolder extends RecyclerView.ViewHolder {

        private final AdapterSiteBinding binding;

        ViewHolder(@NonNull AdapterSiteBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
