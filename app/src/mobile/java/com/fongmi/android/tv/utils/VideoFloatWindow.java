package com.fongmi.android.tv.utils;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;

import androidx.media3.ui.PlayerView;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.setting.PlayerSetting;

/**
 * 手机端自建小窗：以 TYPE_APPLICATION_OVERLAY 承载播放画面，视频全幅铺满。
 * 点按画面切换控制层显隐（右上关闭 / 中间返回全屏 / 底部上一集·暂停·下一集，纯图标），3 秒无操作自动隐藏；
 * 按住画面任意处可拖移，四边四角 8 个透明手柄可缩放，几何信息本地持久化；
 * 进入小窗时用当前画面快照占位，小窗首帧真正渲染后淡出，消除黑屏/闪帧。
 */
public class VideoFloatWindow {

    public interface Listener {

        void onFloatPlayPause();

        void onFloatPrev();

        void onFloatNext();

        void onFloatBack();

        void onFloatClose();
    }

    private static final int MIN_WIDTH_DP = 140;
    private static final int MIN_HEIGHT_DP = 79;
    private static final int DEFAULT_WIDTH_DP = 240;
    private static final int DEFAULT_HEIGHT_DP = 135;
    private static final int DEFAULT_MARGIN_DP = 12;
    private static final int DEFAULT_TOP_DP = 48;
    private static final int TOUCH_SLOP_DP = 8;
    private static final long CONTROLS_AUTO_HIDE_MS = 3000L;
    private static final long PLACEHOLDER_TIMEOUT_MS = 1200L;
    // SurfaceView 的 surfaceChanged 早于 native 播放器真正出画，需再等画面稳定
    private static final long SURFACE_SETTLE_MS = 300L;

    private static final int EDGE_LEFT = 1;
    private static final int EDGE_TOP = 2;
    private static final int EDGE_RIGHT = 4;
    private static final int EDGE_BOTTOM = 8;

    private final Context context;
    private final Listener listener;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private WindowManager windowManager;
    private WindowManager.LayoutParams params;
    private View root;
    private View controlLayer;
    private View[] segViews;
    private ImageView placeholder;
    private PlayerView exoView;
    private ImageView playView;
    private final Point scratch = new Point();
    private DisplayManager displayManager;
    // 挂在系统显示服务上的旋转监听：Activity 退到后台也能即时回调，不受其配置延迟刷新影响
    private final DisplayManager.DisplayListener displayListener = new DisplayManager.DisplayListener() {
        @Override
        public void onDisplayAdded(int displayId) {
        }

        @Override
        public void onDisplayRemoved(int displayId) {
        }

        @Override
        public void onDisplayChanged(int displayId) {
            if (displayId != Display.DEFAULT_DISPLAY || !attached) return;
            handler.post(VideoFloatWindow.this::reclamp);
        }
    };

    private boolean attached;
    private String error;
    private float downRawX;
    private float downRawY;
    private int downX;
    private int downY;
    private int downWidth;
    private int downHeight;
    private boolean moving;
    private boolean dragged;
    private boolean placeholderWatched;

    private final Runnable hideControls = this::doHideControls;
    private final Runnable hidePlaceholderTask = this::hidePlaceholder;

    public VideoFloatWindow(Context context, Listener listener) {
        this.context = context;
        this.listener = listener;
    }

    public boolean isShowing() {
        return attached;
    }

    public String getError() {
        return error;
    }

    public PlayerView getExoView() {
        return exoView;
    }

    public void setPlaying(boolean playing) {
        if (playView == null) return;
        playView.setImageResource(playing
                ? androidx.media3.ui.R.drawable.exo_icon_pause
                : androidx.media3.ui.R.drawable.exo_icon_play);
        playView.setContentDescription(context.getString(playing ? R.string.video_float_pause : R.string.video_float_play));
        keepScreenOn(playing);
    }

    // 播放中保持亮屏，暂停后允许息屏
    private void keepScreenOn(boolean on) {
        if (params == null) return;
        int flags = on
                ? params.flags | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                : params.flags & ~WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON;
        if (flags == params.flags) return;
        params.flags = flags;
        applyLayout();
    }

    /**
     * 贴当前画面快照作为占位，等待小窗首帧渲染后淡出；bitmap 为 null 时保持黑底兜底。
     */
    public void setPlaceholder(Bitmap bitmap) {
        if (placeholder == null) return;
        placeholder.setAlpha(1f);
        placeholder.setImageBitmap(bitmap);
        placeholder.setVisibility(View.VISIBLE);
        watchFirstFrame(0);
        handler.postDelayed(hidePlaceholderTask, PLACEHOLDER_TIMEOUT_MS);
    }

    public boolean show() {
        if (attached) return true;
        if (!canDrawOverlays()) {
            error = context.getString(R.string.video_float_no_permission);
            return false;
        }
        try {
            windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
            if (windowManager == null) {
                error = context.getString(R.string.video_float_failed_unknown);
                return false;
            }
            root = LayoutInflater.from(context).inflate(R.layout.view_video_float, null);
            root.setClipToOutline(true);
            exoView = root.findViewById(R.id.exo);
            placeholder = root.findViewById(R.id.placeholder);
            controlLayer = root.findViewById(R.id.controlLayer);
            segViews = new View[]{
                    root.findViewById(R.id.segTop), root.findViewById(R.id.segBottom),
                    root.findViewById(R.id.segLeft), root.findViewById(R.id.segRight),
                    root.findViewById(R.id.segTopLeft), root.findViewById(R.id.segTopRight),
                    root.findViewById(R.id.segBottomLeft), root.findViewById(R.id.segBottomRight)};
            playView = root.findViewById(R.id.play);
            bindActions();
            bindGesture();
            params = buildParams();
            windowManager.addView(root, params);
            attached = true;
            displayManager = (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
            if (displayManager != null) displayManager.registerDisplayListener(displayListener, handler);
            return true;
        } catch (Throwable e) {
            error = e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ":" + e.getMessage());
            attached = false;
            root = null;
            placeholder = null;
            controlLayer = null;
            segViews = null;
            exoView = null;
            playView = null;
            return false;
        }
    }

    public void release() {
        if (displayManager != null) {
            displayManager.unregisterDisplayListener(displayListener);
            displayManager = null;
        }
        handler.removeCallbacks(hideControls);
        handler.removeCallbacks(hidePlaceholderTask);
        if (placeholder != null) placeholder.removeCallbacks(hidePlaceholderTask);
        hidePlaceholder();
        if (attached && windowManager != null && root != null) {
            try {
                windowManager.removeView(root);
            } catch (Throwable ignored) {
            }
        }
        attached = false;
        root = null;
        placeholder = null;
        controlLayer = null;
        segViews = null;
        exoView = null;
        playView = null;
        params = null;
    }

    private void bindActions() {
        root.findViewById(R.id.play).setOnClickListener(v -> {
            if (listener != null) listener.onFloatPlayPause();
            showControls();
        });
        root.findViewById(R.id.prev).setOnClickListener(v -> {
            if (listener != null) listener.onFloatPrev();
            showControls();
        });
        root.findViewById(R.id.next).setOnClickListener(v -> {
            if (listener != null) listener.onFloatNext();
            showControls();
        });
        root.findViewById(R.id.full).setOnClickListener(v -> {
            if (listener != null) listener.onFloatBack();
        });
        root.findViewById(R.id.close).setOnClickListener(v -> {
            if (listener != null) listener.onFloatClose();
        });
        bindResize(root.findViewById(R.id.handleLeft), EDGE_LEFT);
        bindResize(root.findViewById(R.id.handleRight), EDGE_RIGHT);
        bindResize(root.findViewById(R.id.handleTop), EDGE_TOP);
        bindResize(root.findViewById(R.id.handleBottom), EDGE_BOTTOM);
        bindResize(root.findViewById(R.id.handleTopLeft), EDGE_LEFT | EDGE_TOP);
        bindResize(root.findViewById(R.id.handleTopRight), EDGE_RIGHT | EDGE_TOP);
        bindResize(root.findViewById(R.id.handleBottomLeft), EDGE_LEFT | EDGE_BOTTOM);
        bindResize(root.findViewById(R.id.handleBottomRight), EDGE_RIGHT | EDGE_BOTTOM);
    }

    // 按住画面任意处：超过阈值视为拖移，否则视为点按切换控制层
    private void bindGesture() {
        root.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN -> {
                    if (params == null) return false;
                    downRawX = event.getRawX();
                    downRawY = event.getRawY();
                    downX = params.x;
                    downY = params.y;
                    moving = true;
                    dragged = false;
                    return true;
                }
                case MotionEvent.ACTION_MOVE -> {
                    if (!moving) return false;
                    float dx = event.getRawX() - downRawX;
                    float dy = event.getRawY() - downRawY;
                    if (!dragged && Math.hypot(dx, dy) < dp(TOUCH_SLOP_DP)) return true;
                    dragged = true;
                    params.x = downX + Math.round(dx);
                    params.y = downY + Math.round(dy);
                    clamp();
                    applyLayout();
                    setSegVisible(true);
                    return true;
                }
                case MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (moving && !dragged) toggleControls();
                    if (dragged) {
                        saveRect();
                        if (controlsVisible()) showControls();
                        else setSegVisible(false);
                    }
                    moving = false;
                    return true;
                }
                default -> {
                    return false;
                }
            }
        });
    }

    // 四边四角自由缩放：按边组合改宽高，同时按抓取方向平移原点
    private void bindResize(View view, int edges) {
        if (view == null) return;
        view.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN -> {
                    if (params == null) return false;
                    downRawX = event.getRawX();
                    downRawY = event.getRawY();
                    downX = params.x;
                    downY = params.y;
                    downWidth = params.width;
                    downHeight = params.height;
                    moving = true;
                    return true;
                }
                case MotionEvent.ACTION_MOVE -> {
                    if (!moving) return false;
                    int dx = Math.round(event.getRawX() - downRawX);
                    int dy = Math.round(event.getRawY() - downRawY);
                    int width = downWidth + ((edges & EDGE_LEFT) != 0 ? -dx : (edges & EDGE_RIGHT) != 0 ? dx : 0);
                    int height = downHeight + ((edges & EDGE_TOP) != 0 ? -dy : (edges & EDGE_BOTTOM) != 0 ? dy : 0);
                    int x = downX + ((edges & EDGE_LEFT) != 0 ? dx : 0);
                    int y = downY + ((edges & EDGE_TOP) != 0 ? dy : 0);
                    int minWidth = dp(MIN_WIDTH_DP);
                    int minHeight = dp(MIN_HEIGHT_DP);
                    if (width < minWidth) {
                        if ((edges & EDGE_LEFT) != 0) x -= minWidth - width;
                        width = minWidth;
                    }
                    if (height < minHeight) {
                        if ((edges & EDGE_TOP) != 0) y -= minHeight - height;
                        height = minHeight;
                    }
                    params.width = width;
                    params.height = height;
                    params.x = x;
                    params.y = y;
                    clamp();
                    applyLayout();
                    setSegVisible(true);
                    return true;
                }
                case MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (moving) {
                        saveRect();
                        if (controlsVisible()) showControls();
                        else setSegVisible(false);
                    }
                    moving = false;
                    return true;
                }
                default -> {
                    return false;
                }
            }
        });
    }

    private void toggleControls() {
        if (controlLayer != null && controlLayer.getVisibility() == View.VISIBLE) hideControlsNow();
        else showControls();
    }

    private void showControls() {
        if (controlLayer == null) return;
        controlLayer.setVisibility(View.VISIBLE);
        setSegVisible(true);
        handler.removeCallbacks(hideControls);
        handler.postDelayed(hideControls, CONTROLS_AUTO_HIDE_MS);
    }

    private void hideControlsNow() {
        handler.removeCallbacks(hideControls);
        doHideControls();
    }

    private void doHideControls() {
        if (controlLayer != null) controlLayer.setVisibility(View.GONE);
        setSegVisible(false);
    }

    private void setSegVisible(boolean visible) {
        if (segViews == null) return;
        int visibility = visible ? View.VISIBLE : View.GONE;
        for (View seg : segViews) {
            if (seg != null) seg.setVisibility(visibility);
        }
    }

    private boolean controlsVisible() {
        return controlLayer != null && controlLayer.getVisibility() == View.VISIBLE;
    }

    // 等待小窗内画面表面真正渲染首帧后再撤掉占位图：TextureView 看帧更新，SurfaceView 看 surfaceChanged
    private void watchFirstFrame(int attempt) {
        if (placeholderWatched || placeholder == null || exoView == null) return;
        View surface = exoView.getVideoSurfaceView();
        if (surface == null) {
            if (attempt < 60) placeholder.postDelayed(() -> watchFirstFrame(attempt + 1), 20);
            return;
        }
        placeholderWatched = true;
        if (surface instanceof TextureView textureView) {
            // 链式委托：不能顶掉 media3 自己注册的监听，否则播放器拿不到 Surface
            TextureView.SurfaceTextureListener origin = textureView.getSurfaceTextureListener();
            textureView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
                @Override
                public void onSurfaceTextureUpdated(android.graphics.SurfaceTexture texture) {
                    if (origin != null) origin.onSurfaceTextureUpdated(texture);
                    hidePlaceholder();
                }

                @Override
                public void onSurfaceTextureAvailable(android.graphics.SurfaceTexture texture, int width, int height) {
                    if (origin != null) origin.onSurfaceTextureAvailable(texture, width, height);
                }

                @Override
                public void onSurfaceTextureSizeChanged(android.graphics.SurfaceTexture texture, int width, int height) {
                    if (origin != null) origin.onSurfaceTextureSizeChanged(texture, width, height);
                }

                @Override
                public boolean onSurfaceTextureDestroyed(android.graphics.SurfaceTexture texture) {
                    return origin != null && origin.onSurfaceTextureDestroyed(texture);
                }
            });
        } else if (surface instanceof SurfaceView surfaceView) {
            surfaceView.getHolder().addCallback(new android.view.SurfaceHolder.Callback() {
                @Override
                public void surfaceCreated(android.view.SurfaceHolder holder) {
                    scheduleHide();
                }

                @Override
                public void surfaceChanged(android.view.SurfaceHolder holder, int format, int width, int height) {
                    scheduleHide();
                }

                @Override
                public void surfaceDestroyed(android.view.SurfaceHolder holder) {
                }

                private void scheduleHide() {
                    if (placeholder == null) return;
                    placeholder.removeCallbacks(hidePlaceholderTask);
                    placeholder.postDelayed(hidePlaceholderTask, SURFACE_SETTLE_MS);
                }
            });
        }
    }

    private void hidePlaceholder() {
        handler.removeCallbacks(hidePlaceholderTask);
        ImageView view = placeholder;
        if (view == null || view.getVisibility() != View.VISIBLE) return;
        placeholder = null;
        view.animate().alpha(0f).setDuration(60L).withEndAction(() -> {
            view.setVisibility(View.GONE);
            view.setImageDrawable(null);
        }).start();
    }

    private WindowManager.LayoutParams buildParams() {
        int defaultWidth = dp(DEFAULT_WIDTH_DP);
        int defaultHeight = dp(DEFAULT_HEIGHT_DP);
        int width = Math.max(dp(MIN_WIDTH_DP), PlayerSetting.getFloatWindowWidth(defaultWidth));
        int height = Math.max(dp(MIN_HEIGHT_DP), PlayerSetting.getFloatWindowHeight(defaultHeight));
        int defaultX = Math.max(0, screenWidth() - width - dp(DEFAULT_MARGIN_DP));
        int defaultY = dp(DEFAULT_TOP_DP);
        WindowManager.LayoutParams params = new WindowManager.LayoutParams();
        params.width = width;
        params.height = height;
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = PlayerSetting.getFloatWindowX(defaultX);
        params.y = PlayerSetting.getFloatWindowY(defaultY);
        params.format = PixelFormat.TRANSLUCENT;
        params.type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        params.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
        clamp(params);
        return params;
    }

    private void clamp() {
        if (params != null) clamp(params);
    }

    private void clamp(WindowManager.LayoutParams target) {
        target.x = Math.min(Math.max(target.x, 0), Math.max(0, screenWidth() - target.width));
        target.y = Math.min(Math.max(target.y, 0), Math.max(0, screenHeight() - target.height));
    }

    // 屏幕旋转等配置变化后，把小窗拉回新屏幕边界内
    public void reclamp() {
        if (params == null) return;
        clamp(params);
        applyLayout();
        saveRect();
    }

    private void applyLayout() {
        try {
            if (attached && windowManager != null && root != null) windowManager.updateViewLayout(root, params);
        } catch (Throwable ignored) {
        }
    }

    private void saveRect() {
        if (params == null) return;
        PlayerSetting.putFloatWindowRect(params.x, params.y, params.width, params.height);
    }

    private boolean canDrawOverlays() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context);
    }

    // 用屏幕真实物理尺寸钳制边界，避免横屏时两侧系统栏区域拖不动
    private int screenWidth() {
        return realSize().x;
    }

    private int screenHeight() {
        return realSize().y;
    }

    private Point realSize() {
        // 主源：进程级全局配置，旋转即时更新，不受 Activity 前后台状态影响；
        // Activity 上下文的 Display/Metrics 在后台会延迟刷新，横屏时可能仍是竖屏旧值
        DisplayMetrics global = Resources.getSystem().getDisplayMetrics();
        if (global.widthPixels > 0 && global.heightPixels > 0) {
            scratch.set(global.widthPixels, global.heightPixels);
        } else if (windowManager != null) {
            windowManager.getDefaultDisplay().getRealSize(scratch);
        } else {
            scratch.set(metrics().widthPixels, metrics().heightPixels);
        }
        Log.d("VideoFloatWindow", "realSize=" + scratch.x + "x" + scratch.y);
        return scratch;
    }

    private DisplayMetrics metrics() {
        return context.getResources().getDisplayMetrics();
    }

    private int dp(float value) {
        return (int) (value * metrics().density + 0.5f);
    }
}
