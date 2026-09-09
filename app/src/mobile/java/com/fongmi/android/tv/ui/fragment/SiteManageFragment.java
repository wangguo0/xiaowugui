package com.fongmi.android.tv.ui.fragment;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.LinearLayoutCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.databinding.FragmentSiteManageBinding;
import com.fongmi.android.tv.impl.Callback;
import com.fongmi.android.tv.setting.SiteBlockSetting;
import com.fongmi.android.tv.setting.SiteHealthStore;
import com.fongmi.android.tv.ui.adapter.SiteAdapter;
import com.fongmi.android.tv.ui.base.BaseFragment;
import com.fongmi.android.tv.ui.custom.CustomTextListener;
import com.fongmi.android.tv.ui.custom.SiteTester;
import com.fongmi.android.tv.ui.custom.SpaceItemDecoration;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.utils.Util;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textview.MaterialTextView;

import java.util.ArrayList;
import java.util.List;

/**
 * 屏蔽管理（子页面）：复用与线路弹窗「屏蔽管理」完全一致的站点屏蔽/换源/搜索管理体系，
 * 数据存储于 SiteBlockSetting，二者共用。
 */
public class SiteManageFragment extends BaseFragment implements SiteAdapter.OnClickListener {

    private FragmentSiteManageBinding mBinding;
    private SiteAdapter adapter;
    private SiteTester tester;

    private AlertDialog testDialog;
    private ProgressBar progressBar;
    private MaterialTextView progressText;
    private List<Site> testSlows = new ArrayList<>();
    private List<Site> testFails = new ArrayList<>();

    // 四列表头筛选当前值（用于箭头高亮与“清除筛选”判断）
    private static final int COLOR_FILTER_ON = Color.parseColor("#1A73E8");
    private static final int COLOR_ARROW_IDLE = Color.parseColor("#202124");
    private SiteHealthStore.Status filterHealth;
    private String filterName;
    private Boolean filterSearch;
    private Integer filterBlock;

    public static SiteManageFragment newInstance() {
        return new SiteManageFragment();
    }

    @Override
    protected ViewBinding getBinding(@NonNull LayoutInflater inflater, @Nullable ViewGroup container) {
        return mBinding = FragmentSiteManageBinding.inflate(inflater, container, false);
    }

    @Override
    protected void initView() {
        adapter = new SiteAdapter(this).block(true).search(true).change(true);
        adapter.column(1);
        mBinding.recycler.setAdapter(adapter);
        mBinding.recycler.setItemAnimator(null);
        mBinding.recycler.setHasFixedSize(true);
        mBinding.recycler.addItemDecoration(new SpaceItemDecoration(1, 8));
        mBinding.recycler.post(() -> mBinding.recycler.scrollToPosition(0));
        // 表头文字超出列宽时横向循环滚动，复刻首页左上角 logo 右侧的跑马灯效果
        setHeaderMarquee(mBinding.headerHealthText);
        setHeaderMarquee(mBinding.headerNameText);
        setHeaderMarquee(mBinding.headerSearchText);
        setHeaderMarquee(mBinding.headerBlockText);
    }

    private void setHeaderMarquee(TextView textView) {
        textView.setSingleLine(true);
        textView.setHorizontallyScrolling(true);
        textView.setMarqueeRepeatLimit(-1);
        textView.setEllipsize(TextUtils.TruncateAt.MARQUEE);
        textView.setSelected(true);
    }

    @Override
    protected void initEvent() {
        mBinding.test.setOnClickListener(v -> onTestClick());
        mBinding.reset.setOnClickListener(v -> onResetClick());
        mBinding.clearFilter.setOnClickListener(v -> onClearFilterClick());
        mBinding.headerHealth.setOnClickListener(v -> onHealthFilterClick());
        mBinding.headerName.setOnClickListener(v -> onNameFilterClick());
        mBinding.headerSearch.setOnClickListener(v -> onSearchFilterClick());
        mBinding.headerBlock.setOnClickListener(v -> onBlockFilterClick());
        mBinding.keyword.setOnEditorActionListener((textView, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) Util.hideKeyboard(mBinding.keyword);
            return false;
        });
        mBinding.keyword.addTextChangedListener(new CustomTextListener() {
            @Override
            public void afterTextChanged(Editable s) {
                adapter.filter(s.toString());
                mBinding.recycler.scrollToPosition(0);
            }
        });
        mBinding.toolbar.setNavigationOnClickListener(v -> {
            if (getActivity() != null) getActivity().finish();
        });
        mBinding.helpBox.setOnClickListener(v -> showHelpDialog());
    }

    // ==================== 四列表头筛选 ====================

    private void onHealthFilterClick() {
        String[] labels = {getString(R.string.site_filter_all),
                getString(R.string.site_status_good),
                getString(R.string.site_status_warn),
                getString(R.string.site_status_bad),
                getString(R.string.site_status_unknown)};
        int checked = filterHealth == null ? 0
                : (filterHealth == SiteHealthStore.Status.GOOD ? 1
                : filterHealth == SiteHealthStore.Status.WARN ? 2
                : filterHealth == SiteHealthStore.Status.BAD ? 3 : 4);
        showFilterMenu(mBinding.headerHealth, labels, checked, index -> {
            switch (index) {
                case 1: filterHealth = SiteHealthStore.Status.GOOD; break;
                case 2: filterHealth = SiteHealthStore.Status.WARN; break;
                case 3: filterHealth = SiteHealthStore.Status.BAD; break;
                case 4: filterHealth = SiteHealthStore.Status.UNKNOWN; break;
                default: filterHealth = null;
            }
            adapter.setHealthFilter(filterHealth);
            mBinding.recycler.scrollToPosition(0);
            refreshHeaderArrows();
        });
    }

    private void onNameFilterClick() {
        List<String> options = new ArrayList<>();
        options.add(getString(R.string.site_filter_all));
        for (char c = 'A'; c <= 'Z'; c++) options.add(String.valueOf(c));
        options.add("#");
        int checked = filterName == null ? 0 : (filterName.equals("#") ? 26 : filterName.charAt(0) - 'A' + 1);
        showFilterMenu(mBinding.headerName, options.toArray(new String[0]), checked, index -> {
            if (index == 0) filterName = null;
            else filterName = options.get(index);
            adapter.setNameFilter(filterName);
            mBinding.recycler.scrollToPosition(0);
            refreshHeaderArrows();
        });
    }

    private void onSearchFilterClick() {
        String[] labels = {getString(R.string.site_filter_all),
                getString(R.string.site_state_search_on),
                getString(R.string.site_state_search_off)};
        int checked = filterSearch == null ? 0 : (filterSearch ? 1 : 2);
        showFilterMenu(mBinding.headerSearch, labels, checked, index -> {
            if (index == 1) filterSearch = true;
            else if (index == 2) filterSearch = false;
            else filterSearch = null;
            adapter.setSearchFilter(filterSearch);
            mBinding.recycler.scrollToPosition(0);
            refreshHeaderArrows();
        });
    }

    private void onBlockFilterClick() {
        String[] labels = {getString(R.string.site_filter_all),
                getString(R.string.site_state_change_on),
                getString(R.string.site_state_change_off),
                getString(R.string.site_state_blocked),
                getString(R.string.site_state_blocked_locked)};
        int checked = filterBlock == null ? 0 : filterBlock + 1;
        showFilterMenu(mBinding.headerBlock, labels, checked, index -> {
            filterBlock = index == 0 ? null : (index - 1);
            adapter.setBlockFilter(filterBlock);
            mBinding.recycler.scrollToPosition(0);
            refreshHeaderArrows();
        });
    }

    private void onClearFilterClick() {
        boolean hasAny = filterHealth != null || filterName != null || filterSearch != null || filterBlock != null;
        if (!hasAny) {
            Notify.show(R.string.site_filter_none);
            return;
        }
        adapter.clearFilters();
        filterHealth = null;
        filterName = null;
        filterSearch = null;
        filterBlock = null;
        refreshHeaderArrows();
        mBinding.recycler.scrollToPosition(0);
    }

    // 统一下拉窗：内容包裹在 ScrollView 中并限定最大高度，避免选项过多时超出屏幕
    private void showFilterMenu(View anchor, String[] labels, int checked, java.util.function.IntConsumer onSelect) {
        anchor.post(() -> {
            android.widget.PopupWindow menu = new android.widget.PopupWindow();
            LinearLayoutCompat cnt = new LinearLayoutCompat(requireContext());
            cnt.setOrientation(LinearLayoutCompat.VERTICAL);
            int paddingV = ResUtil.dp2px(6);
            int paddingH = ResUtil.dp2px(16);
            for (int i = 0; i < labels.length; i++) {
                cnt.addView(getMenuItemView(i, i == checked, labels[i], null, paddingV, paddingH, view -> {
                    menu.dismiss();
                    onSelect.accept((Integer) view.getTag());
                }));
            }
            int specW = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
            int specH = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
            cnt.measure(specW, specH);
            ScrollView scroll = new ScrollView(requireContext());
            scroll.addView(cnt);
            int maxH = (int) (ResUtil.dp2px(360));
            scroll.setLayoutParams(new ScrollView.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, Math.min(cnt.getMeasuredHeight(), maxH)));
            menu.setContentView(scroll);
            menu.setWidth(Math.max(cnt.getMeasuredWidth(), anchor.getWidth()));
            menu.setHeight(ViewGroup.LayoutParams.WRAP_CONTENT);
            menu.setBackgroundDrawable(ContextCompat.getDrawable(requireContext(), R.drawable.shape_site_menu_bg));
            menu.setOutsideTouchable(true);
            menu.setFocusable(false);
            menu.showAsDropDown(anchor);
        });
    }

    // 根据筛选开关状态高亮箭头：激活时上色，未激活时淡化
    private void refreshHeaderArrows() {
        updateHeaderArrow(mBinding.headerHealthArrow, filterHealth != null);
        updateHeaderArrow(mBinding.headerNameArrow, filterName != null);
        updateHeaderArrow(mBinding.headerSearchArrow, filterSearch != null);
        updateHeaderArrow(mBinding.headerBlockArrow, filterBlock != null);
    }

    private void updateHeaderArrow(android.widget.ImageView arrow, boolean active) {
        arrow.setColorFilter(active ? COLOR_FILTER_ON : COLOR_ARROW_IDLE, android.graphics.PorterDuff.Mode.SRC_IN);
        arrow.setAlpha(active ? 1.0f : 0.45f);
    }

    private void showHelpDialog() {
        LinearLayoutCompat container = new LinearLayoutCompat(requireContext());
        container.setOrientation(LinearLayoutCompat.VERTICAL);
        int pad = ResUtil.dp2px(20);
        container.setPadding(pad, 0, pad, 0);

        MaterialTextView msg = new MaterialTextView(requireContext());
        msg.setText(R.string.site_help_msg);
        msg.setTextSize(14f);
        msg.setTextColor(android.graphics.Color.parseColor("#202124"));
        msg.setLineSpacing(0f, 1.2f);
        container.addView(msg);

        View line = new View(requireContext());
        LinearLayoutCompat.LayoutParams lineLp = new LinearLayoutCompat.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ResUtil.dp2px(1));
        lineLp.topMargin = ResUtil.dp2px(16);
        lineLp.bottomMargin = ResUtil.dp2px(12);
        line.setBackgroundColor(0x14000000);
        line.setLayoutParams(lineLp);
        container.addView(line);

        MaterialTextView statusTitle = new MaterialTextView(requireContext());
        statusTitle.setText(R.string.site_help_status_title);
        statusTitle.setTextSize(15f);
        statusTitle.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
        statusTitle.setTextColor(android.graphics.Color.parseColor("#202124"));
        container.addView(statusTitle);

        addStatusRow(container, "#0B8043", R.string.site_status_good);
        addStatusRow(container, "#FFD54F", R.string.site_status_warn);
        addStatusRow(container, "#FF5252", R.string.site_status_bad);
        addStatusRowUnknown(container);

        applyLightCard(new MaterialAlertDialogBuilder(requireContext(), R.style.ThemeOverlay_WebHTV_LightDialog)
                .setTitle(R.string.site_help_title)
                .setView(container)
                .setPositiveButton(R.string.dialog_confirm, null)
                .show());
    }

    // 状态图例行：圆点 + 文字说明（与站点列表健康圆点颜色一致）
    private void addStatusRow(LinearLayoutCompat parent, String color, int labelRes) {
        addStatusRow(parent, color, null, labelRes);
    }

    private void addStatusRowUnknown(LinearLayoutCompat parent) {
        addStatusRow(parent, null, "#FFFFFF", R.string.site_status_unknown);
    }

    private void addStatusRow(LinearLayoutCompat parent, String fillColor, String strokeColor, int labelRes) {
        LinearLayoutCompat row = new LinearLayoutCompat(requireContext());
        row.setOrientation(LinearLayoutCompat.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        LinearLayoutCompat.LayoutParams rowLp = new LinearLayoutCompat.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowLp.topMargin = ResUtil.dp2px(6);
        row.setLayoutParams(rowLp);

        View dot = new View(requireContext());
        LinearLayoutCompat.LayoutParams dotLp = new LinearLayoutCompat.LayoutParams(ResUtil.dp2px(10), ResUtil.dp2px(10));
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        // unknown 以白色圆点 + 灰色描边呈现（与列表一致，白底上仍可见）
        bg.setColor(strokeColor != null ? Color.parseColor(strokeColor) : Color.parseColor(fillColor));
        bg.setStroke(dp(1), Color.parseColor("#B0B4BB"));
        dot.setBackground(bg);
        dot.setLayoutParams(dotLp);
        row.addView(dot);

        MaterialTextView label = new MaterialTextView(requireContext());
        label.setText(labelRes);
        label.setTextSize(14f);
        label.setTextColor(Color.parseColor("#202124"));
        LinearLayoutCompat.LayoutParams labelLp = new LinearLayoutCompat.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        labelLp.leftMargin = dp(10);
        label.setLayoutParams(labelLp);
        row.addView(label);

        parent.addView(row);
    }

    private static int dp(int v) {
        return ResUtil.dp2px(v);
    }

    private void onTestClick() {
        if (tester != null) return;
        List<Site> sites = new ArrayList<>(adapter.getItems());
        if (sites.isEmpty()) return;
        testSlows.clear();
        testFails.clear();
        mBinding.test.setEnabled(false);
        showTestProgress(sites.size());
        tester = SiteTester.start(sites, new SiteTester.Callback() {
            @Override
            public void onResult(Site site, SiteHealthStore.Status status, int index, int total) {
                App.post(() -> {
                    if (tester == null) return;
                    if (status == SiteHealthStore.Status.GOOD) {
                        // 正常，不计入异常列表
                    } else if (status == SiteHealthStore.Status.WARN) {
                        testSlows.add(site);
                    } else {
                        testFails.add(site);
                    }
                    updateTestProgress(index, total);
                    int position = adapter.indexOf(site);
                    if (position >= 0) adapter.notifyItemChanged(position);
                });
            }

            @Override
            public void onFinish(int good, int warn, int bad) {
                App.post(() -> {
                    tester = null;
                    if (mBinding == null) return;
                    mBinding.test.setEnabled(true);
                    if (testDialog != null) testDialog.dismiss();
                    showTestResult(good, warn, bad);
                });
            }
        });
    }

    private void showTestProgress(int total) {
        LinearLayoutCompat layout = new LinearLayoutCompat(requireContext());
        layout.setOrientation(LinearLayoutCompat.VERTICAL);
        int pad = ResUtil.dp2px(20);
        layout.setPadding(pad, pad, pad, pad);
        MaterialTextView title = new MaterialTextView(requireContext());
        title.setText(getString(R.string.site_testing_title));
        title.setTextSize(16f);
        title.setGravity(android.view.Gravity.CENTER);
        title.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
        progressBar = new ProgressBar(requireContext(), null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(total);
        progressBar.setProgress(0);
        LinearLayoutCompat.LayoutParams barLp = new LinearLayoutCompat.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        barLp.topMargin = ResUtil.dp2px(16);
        progressBar.setLayoutParams(barLp);
        progressText = new MaterialTextView(requireContext());
        progressText.setText(getString(R.string.site_testing_progress, 0, total));
        progressText.setTextSize(13f);
        progressText.setGravity(android.view.Gravity.CENTER);
        layout.addView(title);
        layout.addView(progressBar);
        layout.addView(progressText);
        testDialog = new MaterialAlertDialogBuilder(requireContext(), R.style.ThemeOverlay_WebHTV_LightDialog)
                .setView(layout)
                .setCancelable(false)
                .setNegativeButton(R.string.dialog_cancel, (d, w) -> {
                    // 需求2：取消需真正终止后台测试任务，并恢复按钮可点
                    if (tester != null) {
                        tester.cancel();
                        tester = null;
                    }
                    mBinding.test.setEnabled(true);
                    d.dismiss();
                })
                .show();
        testDialog.setOnDismissListener(d -> { progressText = null; progressBar = null; });
        applyLightCard(testDialog);
    }

    private void updateTestProgress(int index, int total) {
        if (progressBar == null || progressText == null) return;
        progressBar.setProgress(index);
        progressText.setText(getString(R.string.site_testing_progress, index, total));
    }

    private void showTestResult(int good, int warn, int bad) {
        LinearLayoutCompat layout = new LinearLayoutCompat(requireContext());
        layout.setOrientation(LinearLayoutCompat.VERTICAL);
        int pad = ResUtil.dp2px(20);
        layout.setPadding(pad, pad, pad, pad);

        MaterialTextView title = new MaterialTextView(requireContext());
        title.setText(getString(R.string.site_test_finished));
        title.setTextSize(16f);
        title.setGravity(android.view.Gravity.CENTER);
        title.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
        title.setTextColor(Color.parseColor("#202124"));
        layout.addView(title);

        layout.addView(getResultRow(SiteHealthStore.Status.GOOD, getString(R.string.site_test_normal_item, good)));
        layout.addView(getResultRow(SiteHealthStore.Status.WARN, getString(R.string.site_test_warn_item, warn)));
        layout.addView(getResultRow(SiteHealthStore.Status.BAD, getString(R.string.site_test_bad_item, bad)));

        // 底部三个功能按钮横排：彻底屏蔽缓慢站源 / 彻底屏蔽失败站源 / 取消
        LinearLayoutCompat buttons = new LinearLayoutCompat(requireContext());
        buttons.setOrientation(LinearLayoutCompat.HORIZONTAL);
        LinearLayoutCompat.LayoutParams btnRowLp = new LinearLayoutCompat.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        btnRowLp.topMargin = ResUtil.dp2px(16);
        buttons.setLayoutParams(btnRowLp);

        // 功能按钮文字分两行（每行四字），使用自定义竖直布局避免窄按钮内再次换行
        LinearLayoutCompat slowBox = new PillBox(requireContext(), getString(R.string.site_block_slow_l1), getString(R.string.site_block_slow_l2), Color.parseColor("#FF5252"));
        LinearLayoutCompat failBox = new PillBox(requireContext(), getString(R.string.site_block_fail_l1), getString(R.string.site_block_fail_l2), Color.parseColor("#FF5252"));
        LinearLayoutCompat closeBox = new PillBox(requireContext(), getString(R.string.dialog_cancel), null, Color.parseColor("#8A8F98"));
        buttons.addView(slowBox, getActionLp(false));
        buttons.addView(failBox, getActionLp(true));
        buttons.addView(closeBox, getActionLp(true));
        layout.addView(buttons);

        AlertDialog dialog = new MaterialAlertDialogBuilder(requireContext(), R.style.ThemeOverlay_WebHTV_LightDialog)
                .setView(layout)
                .show();
        slowBox.setOnClickListener(v -> { dialog.dismiss(); blockSlowSites(); });
        failBox.setOnClickListener(v -> { dialog.dismiss(); blockFailSites(); });
        closeBox.setOnClickListener(v -> dialog.dismiss());
    }

    private View getResultRow(SiteHealthStore.Status status, String text) {
        LinearLayoutCompat row = new LinearLayoutCompat(requireContext());
        row.setOrientation(LinearLayoutCompat.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        LinearLayoutCompat.LayoutParams rowLp = new LinearLayoutCompat.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowLp.topMargin = ResUtil.dp2px(10);
        row.setLayoutParams(rowLp);

        View dot = new View(requireContext());
        int d = ResUtil.dp2px(10);
        GradientDrawable gd = new GradientDrawable();
        gd.setShape(GradientDrawable.OVAL);
        gd.setColor(getStatusColor(status));
        dot.setBackground(gd);
        LinearLayoutCompat.LayoutParams dotLp = new LinearLayoutCompat.LayoutParams(d, d);
        dot.setLayoutParams(dotLp);

        MaterialTextView tv = new MaterialTextView(requireContext());
        tv.setText(text);
        tv.setTextSize(14f);
        tv.setTextColor(Color.parseColor("#202124"));
        LinearLayoutCompat.LayoutParams tvLp = new LinearLayoutCompat.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tvLp.leftMargin = ResUtil.dp2px(8);
        tv.setLayoutParams(tvLp);

        row.addView(dot);
        row.addView(tv);
        return row;
    }

    private LinearLayoutCompat.LayoutParams getActionLp(boolean hasLeftMargin) {
        LinearLayoutCompat.LayoutParams lp = new LinearLayoutCompat.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.weight = 1;
        if (hasLeftMargin) lp.leftMargin = ResUtil.dp2px(6);
        return lp;
    }

    /** 自定义胶囊按钮：文字可指定为一行或两行（line2 为 null 时仅一行） */
    private static class PillBox extends LinearLayoutCompat {
        PillBox(Context context, String line1, String line2, int bg) {
            super(context);
            setOrientation(LinearLayoutCompat.VERTICAL);
            setGravity(android.view.Gravity.CENTER);
            int pad = ResUtil.dp2px(4);
            setPadding(pad, pad, pad, pad);

            GradientDrawable gd = new GradientDrawable();
            gd.setColor(bg);
            gd.setCornerRadius(ResUtil.dp2px(18));
            setBackground(gd);

            setClickable(true);
            setFocusable(true);

            addLine(context, line1);
            if (line2 != null) addLine(context, line2);
        }

        private void addLine(Context context, String text) {
            MaterialTextView tv = new MaterialTextView(context);
            tv.setText(text);
            tv.setTextSize(13f);
            tv.setTextColor(Color.WHITE);
            tv.setGravity(android.view.Gravity.CENTER);
            tv.setSingleLine(true);
            addView(tv, new LinearLayoutCompat.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
    }

    private int getStatusColor(SiteHealthStore.Status status) {
        if (status == SiteHealthStore.Status.GOOD) return Color.parseColor("#0B8043");
        if (status == SiteHealthStore.Status.WARN) return Color.parseColor("#FFD54F");
        return Color.parseColor("#FF5252");
    }

    private void blockSlowSites() {
        confirmBlockAll(testSlows, R.string.site_block_slow);
    }

    private void blockFailSites() {
        confirmBlockAll(testFails, R.string.site_block_fail);
    }

    private void confirmBlockAll(List<Site> sites, int labelRes) {
        if (sites.isEmpty()) {
            Notify.show(R.string.site_block_empty);
            return;
        }
        new MaterialAlertDialogBuilder(requireContext(), R.style.ThemeOverlay_WebHTV_LightDialog)
                .setTitle(labelRes)
                .setMessage(R.string.site_block_confirm_msg)
                .setNegativeButton(R.string.dialog_cancel, null)
                .setPositiveButton(R.string.dialog_confirm, (d, w) -> {
                    for (Site s : sites) SiteBlockSetting.setBlocked(s, true);
                    adapter.reload();
                })
                .show();
    }

    private void onResetClick() {
        applyLightCard(new MaterialAlertDialogBuilder(requireContext(), R.style.ThemeOverlay_WebHTV_LightDialog)
                .setTitle(R.string.site_reset_title)
                .setMessage(R.string.site_reset_msg)
                .setNegativeButton(R.string.dialog_cancel, null)
                .setPositiveButton(R.string.dialog_confirm, (d, w) -> doReset())
                .show());
    }

    private void doReset() {
        SiteBlockSetting.clear();
        Site.deleteAll();
        VodConfig.load(VodConfig.get().getConfig(), new Callback() {
            @Override
            public void success() {
                if (mBinding == null) return;
                adapter.reload();
                adapter.filter(mBinding.keyword.getText().toString());
                mBinding.recycler.scrollToPosition(0);
                Notify.show(R.string.site_reset_done);
            }

            @Override
            public void error(String msg) {
                if (mBinding == null) return;
                adapter.reload();
                adapter.filter(mBinding.keyword.getText().toString());
                mBinding.recycler.scrollToPosition(0);
            }
        });
    }

    // 点击站源名称：在屏蔽管理中切换屏蔽状态
    @Override
    public void onTextClick(Site item) {
        if (SiteBlockSetting.isLocked(item)) {
            Notify.show(R.string.site_block_locked_toast);
            return;
        }
        SiteBlockSetting.toggle(item);
        adapter.filter(mBinding.keyword.getText().toString());
        mBinding.recycler.scrollToPosition(0);
    }

    @Override
    public void onSearchClick(int position, Site item, View anchor) {
        String[] labels = {getString(R.string.site_state_search_on), getString(R.string.site_state_search_off)};
        String[] descs = {getString(R.string.site_search_desc_on), getString(R.string.site_search_desc_off)};
        int current = item.isSearchable() ? 0 : 1;
        showMenu(anchor, labels, descs, current, index -> {
            boolean next = index == 0;
            runWithConfirm(item, item.getSearchable() == 0, position, () -> item.setSearchable(next).save());
        });
    }

    @Override
    public void onChangeClick(int position, Site item, View anchor) {
        String[] labels = {getString(R.string.site_state_change_on), getString(R.string.site_state_change_off), getString(R.string.site_state_blocked)};
        String[] descs = {getString(R.string.site_desc_change_on), getString(R.string.site_desc_change_off), getString(R.string.site_desc_blocked)};
        int current = SiteBlockSetting.isBlocked(item) ? 2 : (item.isChangeable() ? 0 : 1);
        showMenu(anchor, labels, descs, current, index -> runWithConfirm(item, item.getChangeable() == 0, position, () -> {
            item.setChangeable(index != 1).save();
            SiteBlockSetting.setBlocked(item, index == 2);
        }));
    }

    @Override
    public boolean onTextLongClick(SiteAdapter.ViewHolder holder) {
        return false;
    }

    @Override
    public boolean onSearchLongClick(Site item) {
        boolean result = !item.isSearchable();
        adapter.getItems().forEach(site -> site.setSearchable(result).save());
        adapter.notifyItemRangeChanged(0, adapter.getItemCount());
        return true;
    }

    @Override
    public boolean onChangeLongClick(Site item) {
        boolean result = !item.isChangeable();
        adapter.getItems().forEach(site -> site.setChangeable(result).save());
        adapter.notifyItemRangeChanged(0, adapter.getItemCount());
        return true;
    }

    /** 将弹窗背景应用为「下拉菜单」同款白底圆角卡片（需求1：浅色易读） */
    private void applyLightCard(androidx.appcompat.app.AlertDialog dialog) {
        if (dialog == null || dialog.getWindow() == null) return;
        dialog.getWindow().setBackgroundDrawable(ContextCompat.getDrawable(dialog.getContext(), R.drawable.shape_site_menu_bg));
    }

    // 点击状态文字弹出下拉菜单，由用户选择目标状态；每项下方附小号字功能说明
    private void showMenu(View anchor, String[] labels, String[] descs, int checked, java.util.function.IntConsumer onSelect) {
        anchor.post(() -> {
            android.widget.PopupWindow menu = new android.widget.PopupWindow();
            LinearLayoutCompat container = new LinearLayoutCompat(requireContext());
            container.setOrientation(LinearLayoutCompat.VERTICAL);
            int paddingV = ResUtil.dp2px(6);
            int paddingH = ResUtil.dp2px(16);
            for (int i = 0; i < labels.length; i++) {
                container.addView(getMenuItemView(i, i == checked, labels[i], descs == null ? null : descs[i], paddingV, paddingH, view -> {
                    menu.dismiss();
                    onSelect.accept((Integer) view.getTag());
                }));
            }
            container.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
            menu.setContentView(container);
            menu.setWidth(Math.max(container.getMeasuredWidth(), anchor.getWidth()));
            menu.setHeight(ViewGroup.LayoutParams.WRAP_CONTENT);
            // 必须使用不透明背景：透明 window background 会导致弹窗立即自动关闭
            menu.setBackgroundDrawable(ContextCompat.getDrawable(requireContext(), R.drawable.shape_site_menu_bg));
            menu.setOutsideTouchable(true);
            menu.setFocusable(false);
            menu.showAsDropDown(anchor);
        });
    }

    private View getMenuItemView(int index, boolean checked, String label, String desc, int paddingV, int paddingH, View.OnClickListener listener) {
        LinearLayoutCompat item = new LinearLayoutCompat(requireContext());
        item.setOrientation(LinearLayoutCompat.VERTICAL);
        item.setTag(index);
        item.setPadding(paddingH, paddingV, paddingH, paddingV);
        item.setBackgroundResource(getBackgroundRes());
        item.setOnClickListener(listener);
        item.addView(getMenuLabelView((checked ? "✓  " : "    ") + label, 14f, true));
        if (!TextUtils.isEmpty(desc)) item.addView(getMenuLabelView(desc, 11f, false));
        return item;
    }

    private MaterialTextView getMenuLabelView(String text, float sizeSp, boolean isTitle) {
        MaterialTextView view = new MaterialTextView(requireContext());
        LinearLayoutCompat.LayoutParams params = new LinearLayoutCompat.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        if (!isTitle) params.topMargin = ResUtil.dp2px(2);
        view.setLayoutParams(params);
        view.setText(text);
        view.setTextSize(sizeSp);
        view.setMaxWidth(ResUtil.dp2px(260));
        view.setTextColor(isTitle ? Color.parseColor("#202124") : Color.parseColor("#8A8F98"));
        return view;
    }

    private int getBackgroundRes() {
        android.util.TypedValue value = new android.util.TypedValue();
        requireContext().getTheme().resolveAttribute(android.R.attr.selectableItemBackground, value, true);
        return value.resourceId;
    }

    private void runWithConfirm(Site item, boolean locked, int position, Runnable action) {
        if (!locked) {
            action.run();
            adapter.notifyItemChanged(position);
            return;
        }
        new MaterialAlertDialogBuilder(requireContext(), R.style.ThemeOverlay_WebHTV_LightDialog)
                .setTitle(R.string.site_lock_warning_title)
                .setMessage(getString(R.string.site_lock_warning_msg, item.getName()))
                .setNegativeButton(R.string.dialog_cancel, null)
                .setPositiveButton(R.string.dialog_confirm, (d, w) -> {
                    action.run();
                    adapter.notifyItemChanged(position);
                })
                .show();
    }

    @Override
    public void onDestroyView() {
        if (tester != null) {
            tester.cancel();
            tester = null;
        }
        if (testDialog != null) {
            testDialog.dismiss();
            testDialog = null;
        }
        mBinding = null;
        super.onDestroyView();
    }
}