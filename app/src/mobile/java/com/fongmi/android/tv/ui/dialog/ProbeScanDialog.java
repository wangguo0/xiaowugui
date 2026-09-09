package com.fongmi.android.tv.ui.dialog;

import android.app.Dialog;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.SourceProbe;
import com.fongmi.android.tv.databinding.DialogProbeScanBinding;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.Locale;

/**
 * 添加源前置静态扫描进度弹窗（样式同合并源进度）：圆环转圈 + 阶段文字 + 当前线路 + 进度条。
 * 扫描在工作线程进行，通过 {@link SourceProbe.ScanProgress} 回调切主线程刷新；不可取消。
 */
public class ProbeScanDialog extends BaseAlertDialog implements SourceProbe.ScanProgress {

    private static final String TAG = "ProbeScan";
    private static final float FETCH_END = 10f;
    private static final float PARSE_END = 25f;

    private DialogProbeScanBinding binding;
    private float shown;
    private android.animation.ValueAnimator anim;

    private ProbeScanDialog() {
        // Fragment 级不可取消：仅 Builder 层 setCancelable(false) 挡不住返回键触发的 DialogFragment.dismiss
        setCancelable(false);
    }

    public static ProbeScanDialog create() {
        return new ProbeScanDialog();
    }

    public void show(FragmentActivity activity) {
        show(activity.getSupportFragmentManager(), TAG);
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        setStyle(STYLE_NORMAL, R.style.ThemeOverlay_WebHTV_LightDialog);
        Dialog dialog = super.onCreateDialog(savedInstanceState);
        // 吞掉返回键：扫描期间任何操作都不能关闭进度窗，只能等扫描完成自动关闭
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
        setProgress(0f);
    }

    // ===== 进度回调（工作线程 → 主线程） =====

    @Override
    public void onPhase(int phase, int total) {
        App.post(() -> {
            if (!isAdded()) return;
            if (phase == 1) {
                binding.progressText.setText(R.string.source_probe_phase_fetch);
                binding.progressDetail.setText("");
                setProgress(FETCH_END * 0.5f);
            } else if (phase == 2) {
                binding.progressText.setText(R.string.source_probe_phase_parse);
                setProgress(PARSE_END * 0.6f);
            } else {
                binding.progressText.setText(total == 0 ? getString(R.string.source_probe_phase_parse) : getString(R.string.source_probe_phase_scan, 0, total));
                setProgress(PARSE_END);
            }
        });
    }

    @Override
    public void onJar(int index, int total, String jar) {
        App.post(() -> {
            if (!isAdded()) return;
            binding.progressText.setText(getString(R.string.source_probe_phase_scan, index, total));
            binding.progressDetail.setText(jar);
            setProgress(PARSE_END + (100f - PARSE_END) * index / (float) Math.max(total, 1));
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
