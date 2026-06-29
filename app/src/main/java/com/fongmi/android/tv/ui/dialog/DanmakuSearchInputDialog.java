package com.fongmi.android.tv.ui.dialog;

import static android.view.View.GONE;
import static android.view.View.VISIBLE;

import android.app.Dialog;
import android.content.DialogInterface;
import android.graphics.Color;
import android.text.InputType;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.DanmakuApi;
import com.fongmi.android.tv.bean.Danmaku;
import com.fongmi.android.tv.player.PlayerManager;
import com.fongmi.android.tv.ui.adapter.DanmakuAdapter;
import com.fongmi.android.tv.ui.custom.CustomRecyclerView;
import com.fongmi.android.tv.ui.custom.SpaceItemDecoration;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.utils.Util;
import com.github.catvod.crawler.SpiderDebug;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.progressindicator.CircularProgressIndicator;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Response;

public final class DanmakuSearchInputDialog extends DialogFragment implements DanmakuAdapter.OnClickListener, Callback {

    private final DanmakuAdapter adapter;
    private final Map<String, List<Danmaku>> groups;
    private TextInputEditText input;
    private MaterialButton search;
    private HorizontalScrollView sourceScroll;
    private LinearLayout sourceTabs;
    private FrameLayout resultFrame;
    private CustomRecyclerView recycler;
    private CircularProgressIndicator progress;
    private TextView empty;
    private PlayerManager player;
    private volatile Call activeCall;
    private boolean selected;
    private boolean restoreParent;
    private String selectedSource;

    public static DanmakuSearchInputDialog create() {
        return new DanmakuSearchInputDialog();
    }

    public DanmakuSearchInputDialog() {
        this.adapter = new DanmakuAdapter(this);
        this.groups = new LinkedHashMap<>();
    }

    public DanmakuSearchInputDialog player(PlayerManager player) {
        this.player = player;
        return this;
    }

    public DanmakuSearchInputDialog restoreParent(boolean restoreParent) {
        this.restoreParent = restoreParent;
        return this;
    }

    public void show(FragmentActivity activity) {
        for (Fragment f : activity.getSupportFragmentManager().getFragments()) if (f instanceof DanmakuSearchInputDialog) return;
        show(activity.getSupportFragmentManager(), null);
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable android.os.Bundle savedInstanceState) {
        input = createInput();
        AlertDialog dialog = new MaterialAlertDialogBuilder(requireActivity(), R.style.ThemeOverlay_WebHTV_LightDialog)
                .setTitle(R.string.play_search)
                .setView(createContentView())
                .setNegativeButton(R.string.dialog_negative, null)
                .create();
        dialog.setOnShowListener(d -> {
            input.setOnEditorActionListener((textView, actionId, event) -> {
                if (actionId != EditorInfo.IME_ACTION_SEARCH) return false;
                search();
                return true;
            });
            search.setOnClickListener(v -> search());
            Util.showKeyboard(input);
        });
        return dialog;
    }

    @Override
    public void onStart() {
        super.onStart();
        Dialog dialog = getDialog();
        Window window = dialog == null ? null : dialog.getWindow();
        if (window == null) return;
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        WindowManager.LayoutParams params = new WindowManager.LayoutParams();
        params.copyFrom(window.getAttributes());
        params.width = Math.max(dp(280), Math.min(metrics.widthPixels - dp(32), dp(560)));
        params.height = WindowManager.LayoutParams.WRAP_CONTENT;
        window.setAttributes(params);
    }

    @Override
    public void onDismiss(@NonNull DialogInterface dialog) {
        super.onDismiss(dialog);
        FragmentActivity activity = getActivity();
        if (selected || !restoreParent || activity == null || activity.isFinishing()) return;
        DanmakuDialog.create().player(player).show(activity);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        activeCall = null;
        DanmakuApi.cancel();
    }

    @Override
    public void onItemClick(Danmaku item) {
        selected = true;
        if (SpiderDebug.isEnabled()) SpiderDebug.log("danmaku", "search dialog item click selected=%s name=%s url=%s", item.isSelected(), item.getName(), item.getUrl());
        player.setDanmaku(item.isSelected() ? Danmaku.empty() : item);
        dismiss();
    }

    private TextInputEditText createInput() {
        TextInputEditText edit = new TextInputEditText(requireContext());
        edit.setSingleLine(true);
        edit.setMaxLines(1);
        edit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS | InputType.TYPE_TEXT_FLAG_AUTO_CORRECT | InputType.TYPE_TEXT_FLAG_AUTO_COMPLETE);
        edit.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        CharSequence title = player == null || player.getMetadata() == null ? "" : player.getMetadata().title;
        edit.setText(title == null ? "" : title);
        if (edit.getText() != null) edit.setSelection(edit.getText().length());
        return edit;
    }

    private LinearLayout createContentView() {
        LinearLayout root = new LinearLayout(requireContext());
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(8), dp(20), 0);
        root.addView(createSearchRow(), new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        sourceTabs = new LinearLayout(requireContext());
        sourceTabs.setOrientation(LinearLayout.HORIZONTAL);
        sourceTabs.setGravity(Gravity.CENTER_VERTICAL);

        sourceScroll = new HorizontalScrollView(requireContext());
        sourceScroll.setHorizontalScrollBarEnabled(false);
        sourceScroll.setOverScrollMode(HorizontalScrollView.OVER_SCROLL_NEVER);
        sourceScroll.setVisibility(GONE);
        sourceScroll.addView(sourceTabs, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.MATCH_PARENT));
        LinearLayout.LayoutParams tabParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(42));
        tabParams.setMargins(0, dp(12), 0, 0);
        root.addView(sourceScroll, tabParams);

        resultFrame = createResultFrame();
        resultFrame.setVisibility(GONE);
        LinearLayout.LayoutParams resultParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        resultParams.setMargins(0, dp(8), 0, 0);
        root.addView(resultFrame, resultParams);
        return root;
    }

    private LinearLayout createSearchRow() {
        TextInputLayout layout = new TextInputLayout(requireContext());
        layout.setHint(getString(R.string.search_keyword));
        layout.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_OUTLINE);
        layout.addView(input, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        search = actionButton(getString(R.string.play_search), true);
        search.setMinHeight(dp(56));
        search.setMinimumHeight(dp(56));

        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams inputParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        inputParams.setMargins(0, 0, dp(8), 0);
        row.addView(layout, inputParams);
        row.addView(search, new LinearLayout.LayoutParams(dp(82), dp(56)));
        return row;
    }

    private FrameLayout createResultFrame() {
        FrameLayout frame = new FrameLayout(requireContext());
        frame.setMinimumHeight(dp(72));

        recycler = new CustomRecyclerView(requireContext());
        recycler.setLayoutManager(new LinearLayoutManager(requireContext()));
        recycler.setItemAnimator(null);
        recycler.setHasFixedSize(false);
        recycler.setMaxHeight(dp(260));
        recycler.addItemDecoration(new SpaceItemDecoration(1, 10));
        recycler.setAdapter(adapter);
        recycler.setVisibility(GONE);
        frame.addView(recycler, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT));

        empty = new TextView(requireContext());
        empty.setGravity(Gravity.CENTER);
        empty.setTextColor(Color.parseColor("#5F6368"));
        empty.setTextSize(14);
        empty.setVisibility(GONE);
        empty.setMinHeight(dp(72));
        frame.addView(empty, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, dp(72), Gravity.CENTER));

        progress = new CircularProgressIndicator(requireContext());
        progress.setIndeterminate(true);
        progress.setIndicatorColor(Color.parseColor("#0B57D0"));
        progress.setIndicatorSize(dp(32));
        progress.setTrackThickness(dp(2));
        progress.setVisibility(GONE);
        frame.addView(progress, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        return frame;
    }

    private void search() {
        String keyword = input.getText() == null ? "" : input.getText().toString().trim();
        if (TextUtils.isEmpty(keyword)) {
            input.setError(getString(R.string.error_empty));
            return;
        }
        input.setError(null);
        showProgress();
        activeCall = null;
        Util.hideKeyboard(input);
        Call call = DanmakuApi.newCall(keyword, getEpisode());
        if (call == null) {
            showError(getString(R.string.danmaku_api_invalid));
            return;
        }
        activeCall = call;
        call.enqueue(this);
    }

    private String getEpisode() {
        CharSequence episode = player == null || player.getMetadata() == null ? "" : player.getMetadata().artist;
        return episode == null ? "" : episode.toString().trim();
    }

    private void showProgress() {
        selectedSource = null;
        groups.clear();
        adapter.clear();
        sourceTabs.removeAllViews();
        sourceScroll.setVisibility(GONE);
        resultFrame.setVisibility(VISIBLE);
        recycler.setVisibility(GONE);
        empty.setVisibility(GONE);
        progress.setVisibility(VISIBLE);
        search.setEnabled(false);
    }

    private void hideProgress() {
        progress.setVisibility(GONE);
        search.setEnabled(true);
    }

    private void showError(String message) {
        if (TextUtils.isEmpty(message)) message = getString(R.string.error_empty);
        if (SpiderDebug.isEnabled()) SpiderDebug.log("danmaku", "search dialog failed error=%s", message);
        hideProgress();
        sourceScroll.setVisibility(GONE);
        resultFrame.setVisibility(VISIBLE);
        recycler.setVisibility(GONE);
        empty.setText(message);
        empty.setVisibility(VISIBLE);
        Notify.show(message);
    }

    private void onSuccess(List<Danmaku> items) {
        if (SpiderDebug.isEnabled()) SpiderDebug.log("danmaku", "search dialog success count=%d", items.size());
        hideProgress();
        groups.clear();
        for (Danmaku item : items) {
            String source = item.getSourceName();
            if (!groups.containsKey(source)) groups.put(source, new ArrayList<>());
            groups.get(source).add(item);
        }
        if (groups.isEmpty()) {
            showError(getString(R.string.error_empty));
            return;
        }
        selectedSource = groups.keySet().iterator().next();
        renderSourceTabs();
        showSourceItems();
    }

    private void renderSourceTabs() {
        sourceTabs.removeAllViews();
        sourceScroll.setVisibility(groups.isEmpty() ? GONE : VISIBLE);
        for (String source : groups.keySet()) {
            List<Danmaku> items = groups.get(source);
            boolean active = TextUtils.equals(source, selectedSource);
            MaterialButton tab = actionButton(source + " " + (items == null ? 0 : items.size()), active);
            tab.setSingleLine(true);
            tab.setEllipsize(TextUtils.TruncateAt.END);
            tab.setOnClickListener(v -> {
                selectedSource = source;
                renderSourceTabs();
                showSourceItems();
            });
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(36));
            params.setMargins(0, 0, dp(8), 0);
            sourceTabs.addView(tab, params);
        }
    }

    private void showSourceItems() {
        hideProgress();
        adapter.clear();
        List<Danmaku> items = groups.get(selectedSource);
        if (items == null || items.isEmpty()) {
            recycler.setVisibility(GONE);
            empty.setText(R.string.error_empty);
            empty.setVisibility(VISIBLE);
            return;
        }
        empty.setVisibility(GONE);
        recycler.setVisibility(VISIBLE);
        adapter.addAll(items);
        recycler.requestFocus();
    }

    private MaterialButton actionButton(String text, boolean primary) {
        MaterialButton button = new MaterialButton(requireContext());
        button.setText(text);
        button.setTextSize(13);
        button.setAllCaps(false);
        button.setMinWidth(0);
        button.setMinHeight(dp(32));
        button.setMinimumHeight(dp(32));
        button.setPadding(dp(10), 0, dp(10), 0);
        button.setCornerRadius(dp(8));
        if (primary) {
            button.setBackgroundTintList(ContextCompat.getColorStateList(requireContext(), R.color.dialog_primary_button_bg));
            button.setTextColor(ContextCompat.getColorStateList(requireContext(), R.color.dialog_primary_button_text));
        } else {
            button.setBackgroundTintList(ContextCompat.getColorStateList(requireContext(), R.color.dialog_outlined_button_bg));
            button.setTextColor(ContextCompat.getColorStateList(requireContext(), R.color.dialog_outlined_button_text));
            button.setStrokeColor(ContextCompat.getColorStateList(requireContext(), R.color.dialog_outlined_button_stroke));
            button.setStrokeWidth(dp(1));
        }
        return button;
    }

    @Override
    public void onResponse(@NonNull Call call, @NonNull Response response) {
        if (call != activeCall) return;
        try {
            String body = response.body() == null ? "" : response.body().string();
            List<Danmaku> items = DanmakuApi.arrayFrom(body);
            if (items.isEmpty()) throw new Exception(ResUtil.getString(R.string.error_empty));
            App.post(() -> onSuccess(items));
        } catch (Exception e) {
            App.post(() -> showError(e.getMessage()));
        }
    }

    @Override
    public void onFailure(@NonNull Call call, @NonNull IOException e) {
        if (call != activeCall) return;
        App.post(() -> showError(e.getMessage()));
    }

    private int dp(int value) {
        return ResUtil.dp2px(value);
    }
}
