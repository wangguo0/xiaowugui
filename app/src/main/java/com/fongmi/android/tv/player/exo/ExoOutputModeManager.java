package com.fongmi.android.tv.player.exo;

import android.os.Build;
import android.view.Display;
import android.view.Window;
import android.view.WindowManager;

import androidx.media3.common.Format;

import com.fongmi.android.tv.setting.ExoPerformanceSetting;
import com.github.catvod.crawler.SpiderDebug;

import java.util.ArrayList;
import java.util.List;

/** Applies and restores EXO display modes at the Activity/Window boundary. */
public final class ExoOutputModeManager {

    private static final int INVALID_MODE_ID = 0;

    private final Window window;
    private int originalModeId = INVALID_MODE_ID;
    private int requestedModeId = INVALID_MODE_ID;

    public ExoOutputModeManager(Window window) {
        this.window = window;
    }

    public Result apply(Format format) {
        int setting = ExoPerformanceSetting.getFrameRateMode();
        if (setting == ExoPerformanceSetting.FRAME_RATE_OFF) return Result.skipped("disabled");
        if (format == null || format.frameRate <= 0 || format.width <= 0 || format.height <= 0) return Result.skipped("unknown-format");
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return Result.skipped("unsupported-api");
        Display display = window == null ? null : window.getWindowManager().getDefaultDisplay();
        if (display == null) return Result.skipped("no-display");
        Display.Mode current = display.getMode();
        Display.Mode[] supported = display.getSupportedModes();
        if (current == null || supported == null || supported.length == 0) return Result.skipped("no-modes");
        if (originalModeId == INVALID_MODE_ID) originalModeId = current.getModeId();
        ExoOutputModePolicy.Policy policy = setting == ExoPerformanceSetting.FRAME_RATE_RESOLUTION_AND_RATE
                ? ExoOutputModePolicy.Policy.resolutionAndRate()
                : ExoOutputModePolicy.Policy.frameRateOnly();
        ExoOutputModePolicy.Decision decision = ExoOutputModePolicy.select(toModes(supported), current.getModeId(), ExoOutputModePolicy.Content.of(format.width, format.height, format.frameRate), policy);
        ExoOutputModePolicy.Mode selected = decision.mode();
        if (selected == null) return Result.skipped(decision.reason());
        if (setting == ExoPerformanceSetting.FRAME_RATE_SEAMLESS) return new Result(decision, false, "seamless-delegated");
        if (!decision.changeRequired() || selected.id() == requestedModeId) return new Result(decision, false, "already-selected");
        WindowManager.LayoutParams attributes = window.getAttributes();
        attributes.preferredDisplayModeId = selected.id();
        window.setAttributes(attributes);
        requestedModeId = selected.id();
        if (SpiderDebug.isEnabled()) SpiderDebug.log("exo-output", "requested mode=%dx%d@%.3fHz id=%d content=%dx%d@%.3fHz policy=%s", selected.width(), selected.height(), selected.refreshRateMilliHz() / 1000f, selected.id(), format.width, format.height, format.frameRate, ExoPerformanceSetting.getFrameRateText());
        return new Result(decision, true, "requested");
    }

    public void restore() {
        if (window == null || originalModeId == INVALID_MODE_ID || Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return;
        WindowManager.LayoutParams attributes = window.getAttributes();
        attributes.preferredDisplayModeId = originalModeId;
        window.setAttributes(attributes);
        if (SpiderDebug.isEnabled()) SpiderDebug.log("exo-output", "restored original mode id=%d", originalModeId);
        requestedModeId = INVALID_MODE_ID;
        originalModeId = INVALID_MODE_ID;
    }

    private static List<ExoOutputModePolicy.Mode> toModes(Display.Mode[] modes) {
        List<ExoOutputModePolicy.Mode> result = new ArrayList<>(modes.length);
        for (Display.Mode mode : modes) result.add(ExoOutputModePolicy.Mode.of(mode.getModeId(), mode.getPhysicalWidth(), mode.getPhysicalHeight(), mode.getRefreshRate()));
        return result;
    }

    public record Result(ExoOutputModePolicy.Decision decision, boolean applied, String reason) {
        private static Result skipped(String reason) {
            return new Result(new ExoOutputModePolicy.Decision(null, false, reason), false, reason);
        }
    }
}
