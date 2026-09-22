package com.fongmi.android.tv.ui.dialog;

import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.DialogCommunityRepoAddBinding;
import com.fongmi.android.tv.databinding.DialogCommunityRepoBinding;
import com.fongmi.android.tv.databinding.ItemCommunityRepoBinding;
import com.fongmi.android.tv.ui.activity.CommunityWebActivity;
import com.github.catvod.utils.Prefers;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 订阅源全网资源社区：内置 github 资源库 + 用户自定义仓库入口。
 * 自定义仓库持久化在 Prefers（键 community_repos，JSON 数组 name/url），
 * 点击跳转应用内网页浏览，条目右侧删除图标弹确认框删除。内置 github 资源库同样支持删除（持久隐藏）。
 */
public class CommunityRepoDialog extends BaseAlertDialog {

    private static final String KEY = "community_repos";
    private static final String KEY_GITHUB_HIDDEN = "community_github_hidden";

    private DialogCommunityRepoBinding binding;
    private final List<String[]> custom = new ArrayList<>();

    public static CommunityRepoDialog create() {
        return new CommunityRepoDialog();
    }

    public void show(androidx.fragment.app.Fragment fragment) {
        show(fragment.getChildFragmentManager(), null);
    }

    @Override
    protected ViewBinding getBinding() {
        return binding = DialogCommunityRepoBinding.inflate(LayoutInflater.from(requireActivity()), null, false);
    }

    @NonNull
    @Override
    protected MaterialAlertDialogBuilder getBuilder() {
        return new MaterialAlertDialogBuilder(requireActivity(), R.style.ThemeOverlay_WebHTV_LightDialog).setView(getBinding().getRoot());
    }

    @Override
    protected void initView() {
        load();
        binding.githubRepo.setVisibility(Prefers.getBoolean(KEY_GITHUB_HIDDEN, false) ? android.view.View.GONE : android.view.View.VISIBLE);
        render();
    }

    @Override
    protected void initEvent() {
        binding.githubRepo.setOnClickListener(v -> open("https://github.com/qist/tvbox", getString(R.string.community_repo_github), true));
        // 内置 github 资源库支持右侧图标删除（持久隐藏；可通过「添加仓库」重新添加该地址恢复）
        binding.githubDelete.setOnClickListener(v -> confirmDeleteGithub());
        binding.addRepo.setOnClickListener(v -> showAddDialog());
    }

    // ===== 自定义仓库持久化 =====

    private void load() {
        custom.clear();
        try {
            JSONArray array = new JSONArray(Prefers.getString(KEY, "[]"));
            for (int i = 0; i < array.length(); i++) {
                JSONObject obj = array.optJSONObject(i);
                if (obj == null) continue;
                String name = obj.optString("name");
                String url = obj.optString("url");
                if (TextUtils.isEmpty(name) || TextUtils.isEmpty(url)) continue;
                custom.add(new String[]{name, url});
            }
        } catch (Throwable ignored) {
        }
    }

    private void save() {
        try {
            JSONArray array = new JSONArray();
            for (String[] item : custom) array.put(new JSONObject().put("name", item[0]).put("url", item[1]));
            Prefers.put(KEY, array.toString());
        } catch (Throwable ignored) {
        }
    }

    private void render() {
        binding.customList.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(requireActivity());
        for (String[] item : custom) {
            ItemCommunityRepoBinding row = ItemCommunityRepoBinding.inflate(inflater, binding.customList, false);
            row.name.setText(item[0]);
            row.url.setText(item[1]);
            // 自定义仓库仅浏览，不提供提取；右侧图标删除
            row.getRoot().setOnClickListener(v -> open(item[1], item[0], false));
            row.delete.setOnClickListener(v -> confirmDelete(item));
            binding.customList.addView(row.getRoot());
        }
    }

    // ===== 添加 =====

    private void showAddDialog() {
        DialogCommunityRepoAddBinding add = DialogCommunityRepoAddBinding.inflate(LayoutInflater.from(requireActivity()), null, false);
        androidx.appcompat.app.AlertDialog dialog = new MaterialAlertDialogBuilder(requireActivity(), R.style.ThemeOverlay_WebHTV_LightDialog)
                .setTitle(R.string.community_repo_add)
                .setView(add.getRoot())
                .setNegativeButton(R.string.dialog_negative, null)
                .setPositiveButton(R.string.dialog_positive, null)
                .show();
        // 校验不通过时保持弹窗打开，仅在输入框下方提示
        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String name = getText(add.nameText.getText());
            String url = getText(add.urlText.getText());
            add.nameInput.setError(TextUtils.isEmpty(name) ? getString(R.string.community_repo_invalid) : null);
            if (TextUtils.isEmpty(name)) return;
            if (!(url.startsWith("http://") || url.startsWith("https://"))) {
                add.urlInput.setErrorEnabled(true);
                add.urlInput.setError(getString(R.string.community_repo_invalid));
                return;
            }
            if (exists(url)) {
                add.urlInput.setErrorEnabled(true);
                add.urlInput.setError(getString(R.string.community_repo_exists));
                return;
            }
            add.urlInput.setError(null);
            custom.add(new String[]{name, url});
            save();
            render();
            dialog.dismiss();
        });
    }

    private String getText(CharSequence text) {
        return text == null ? "" : text.toString().trim();
    }

    private boolean exists(String url) {
        for (String[] item : custom) if (item[1].equals(url)) return true;
        return false;
    }

    // ===== 删除 =====

    private void confirmDelete(String[] item) {
        new MaterialAlertDialogBuilder(requireActivity(), R.style.ThemeOverlay_WebHTV_LightDialog)
                .setTitle(R.string.community_repo_delete_confirm)
                .setMessage(item[0])
                .setNegativeButton(R.string.dialog_negative, null)
                .setPositiveButton(R.string.setting_subscription_delete, (dialog, which) -> {
                    custom.remove(item);
                    save();
                    render();
                })
                .show();
    }

    private void confirmDeleteGithub() {
        new MaterialAlertDialogBuilder(requireActivity(), R.style.ThemeOverlay_WebHTV_LightDialog)
                .setTitle(R.string.community_repo_delete_confirm)
                .setMessage(R.string.community_repo_github)
                .setNegativeButton(R.string.dialog_negative, null)
                .setPositiveButton(R.string.setting_subscription_delete, (dialog, which) -> {
                    Prefers.put(KEY_GITHUB_HIDDEN, true);
                    binding.githubRepo.setVisibility(android.view.View.GONE);
                })
                .show();
    }

    private void open(String url, String name, boolean extractable) {
        dismissAllowingStateLoss();
        CommunityWebActivity.start(requireActivity(), url, name, extractable);
    }

    @Override
    public void onStart() {
        super.onStart();
        if (getDialog() == null || getDialog().getWindow() == null) return;
        ViewGroup.LayoutParams params = getDialog().getWindow().getDecorView().getLayoutParams();
        params.width = Math.min(getDialog().getWindow().getDecorView().getResources().getDisplayMetrics().widthPixels, (int) (480 * getDialog().getWindow().getDecorView().getResources().getDisplayMetrics().density));
        getDialog().getWindow().getDecorView().setLayoutParams(params);
    }
}
