package com.fongmi.android.tv.ui.dialog;

import android.app.Dialog;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.FragmentActivity;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.CommunityExtractor;
import com.fongmi.android.tv.databinding.DialogProbeScanBinding;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.Locale;

/**
 * 社区订阅源提取进度弹窗（样式同添加源安全检测进度）：圆环转圈 + 阶段文字 + 当前文件 + 进度条。
 * 提取在工作线程进行，通过 {@link CommunityExtractor.Progress} 回调切主线程刷新；不可取消。
 */
public class CommunityExtractDialog extends BaseAlertDialog implements CommunityExtractor.Progress {

    private static final String TAG = "CommunityExtract";
    private static final float LIST_END = 10f;
    private static final float README_END = 15f;

    private DialogProbeScanBinding binding;
    private float shown;
    private android.animation.ValueAnimator anim;

    private CommunityExtractDialog() {
        // Fragment 级不可取消：仅 Builder 层 setCancelable(false) 挡不住返回键触发的 DialogFragment.dismiss
        setCancelable(false);
    }

    public static CommunityExtractDialog create() {
        return new CommunityExtractDialog();
    }

    public void show(FragmentActivity activity) {
        show(activity.getSupportFragmentManager(), TAG);
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        setStyle(STYLE_NORMAL, R.style.ThemeOverlay_WebHTV_LightDialog);
        Dialog dialog = super.onCreateDialog(savedInstanceState);
        // 吞掉返回键：提取期间任何操作都不能关闭进度窗，只能等提取完成自动关闭
        dialog.setOnKeyListener((d, keyCode, event) -> keyCode == android.view.KeyEvent.KEYCODE_BACK);
        return dialog;
    }

    @Override
    protected ViewBinding getBinding() {
        return binding = DialogProbeScanBinding.inflate(getLayoutInflater());
    }

    @Override
    protected MaterialAlertDialogBuilder getBuilder() {
        return new MaterialAlertDialogBuilder(requireActivity(), R.style.ThemeOverlay_WebHTV_LightDialog)
                .setView(getBinding().getRoot())
                .setCancelable(false);
    }

    @Override
    protected void initView() {
        binding.title.setText(R.string.community_extract_title);
        setProgress(0f);
    }

    // ===== 进度回调（工作线程 → 主线程） =====

    @Override
    public void onPhase(int phase) {
        App.post(() -> {
            if (!isAdded()) return;
            if (phase == 1) {
                binding.progressText.setText(R.string.community_extract_phase_list);
                binding.progressDetail.setText("");
                setProgress(LIST_END * 0.5f);
            } else if (phase == 2) {
                binding.progressText.setText(R.string.community_extract_phase_readme);
                binding.progressDetail.setText("");
                setProgress(README_END * 0.6f);
            } else {
                binding.progressText.setText(R.string.community_extract_phase_scan);
                setProgress(README_END);
            }
        });
    }

    @Override
    public void onFile(String name, int done, int total) {
        App.post(() -> {
            if (!isAdded()) return;
            binding.progressText.setText(getString(R.string.community_extract_progress, done, total));
            binding.progressDetail.setText(name);
            setProgress(README_END + (100f - README_END) * done / (float) Math.max(total, 1));
        });
    }

    // 目标进度 300ms 补间，小步也平滑滑动；百分比保留 1 位小数
    private void setProgress(float target) {
        target = Math.max(0f, Math.min(100f, target));
        if (anim != null) anim.cancel();
        anim = android.animation.ValueAnimator.ofFloat(shown, target);
        anim.setDuration(300);
        anim.addUpdateListener(a -> {
            shown = (float) a.getAnimatedValue();
            if (binding == null) return;
            binding.progressBar.setProgress(Math.round(shown));
            binding.progressPercent.setText(String.format(Locale.US, "%.1f%%", shown));
        });
        anim.start();
    }

    @Override
    public void onDestroyView() {
        if (anim != null) anim.cancel();
        binding = null;
        super.onDestroyView();
    }
}
