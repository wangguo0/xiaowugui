package com.fongmi.android.tv.ui.guide;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.graphics.Color;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.utils.ResUtil;

import java.util.ArrayList;
import java.util.List;

// 新手引导全屏遮罩：半透明底 + PorterDuff 挖洞高亮目标控件，洞外拦截触摸，
// 洞内触摸透传给下层真实控件，用户必须点击目标控件才能推进引导。
// 可挂到任意窗口根（Activity decor 或 Dialog decor），坐标均用 getLocationInWindow 对齐。
public class GuideOverlayView extends FrameLayout {

    public interface Callback {
        void onSkip();

        void onContinue();
    }

    private static final float HOLE_PAD = ResUtil.dp2px(6);
    private static final float HOLE_RADIUS = ResUtil.dp2px(12);

    private final SpotView spot;
    private final View bubble;
    private final TextView counter;
    private final TextView message;
    private final TextView continueBtn;
    private final TextView skip;
    private final List<View> targets = new ArrayList<>();
    private final List<RectF> holes = new ArrayList<>();
    private final View.OnLayoutChangeListener relayout = (v, l, t, r, b, ol, ot, or, ob) -> update();
    private final Runnable relayoutTask = this::update;
    // 页面滚动时目标控件不会重新布局，必须靠窗口级滚动事件驱动洞与气泡同步重算
    private final ViewTreeObserver.OnScrollChangedListener onScroll = this::update;
    private boolean scrollListening;
    private Callback callback;

    public GuideOverlayView(Context context) {
        super(context);
        // 注意：不可 setClickable(true)，否则洞内 DOWN 会被本层 onTouchEvent 吞掉，目标控件收不到点击
        spot = new SpotView(context);
        addView(spot, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        bubble = View.inflate(context, R.layout.view_guide_bubble, null);
        counter = bubble.findViewById(R.id.counter);
        message = bubble.findViewById(R.id.message);
        continueBtn = bubble.findViewById(R.id.continueBtn);
        addView(bubble, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
        skip = new TextView(context);
        skip.setText(R.string.guide_skip);
        skip.setTextSize(13);
        skip.setTextColor(0xCCFFFFFF);
        skip.setBackgroundResource(android.R.color.transparent);
        skip.setPadding(ResUtil.dp2px(10), ResUtil.dp2px(6), ResUtil.dp2px(10), ResUtil.dp2px(6));
        LayoutParams skipParams = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        skipParams.gravity = Gravity.TOP | Gravity.END;
        skipParams.topMargin = ResUtil.dp2px(28);
        skipParams.rightMargin = ResUtil.dp2px(12);
        addView(skip, skipParams);
        skip.setOnClickListener(v -> {
            if (callback != null) callback.onSkip();
        });
        continueBtn.setOnClickListener(v -> {
            if (callback != null) callback.onContinue();
        });
    }

    // 展示当前步骤：高亮 targets 列表内全部控件，气泡文案 textRes，步数角标 index/total（index<=0 隐藏）
    public void show(List<View> views, int textRes, int index, int total, boolean showContinue, Callback cb) {
        callback = cb;
        for (View old : targets) old.removeOnLayoutChangeListener(relayout);
        targets.clear();
        holes.clear();
        for (View view : views) {
            targets.add(view);
            view.addOnLayoutChangeListener(relayout);
        }
        message.setText(textRes);
        if (index > 0 && total > 0) {
            counter.setVisibility(VISIBLE);
            counter.setText(getResources().getString(R.string.guide_step, index, total));
        } else {
            counter.setVisibility(GONE);
        }
        continueBtn.setVisibility(showContinue ? VISIBLE : GONE);
        setVisibility(VISIBLE);
        startScrollListening();
        update();
        // 目标控件可能仍在布局中（菜单项/弹窗动画），稍后再校准一次
        App.post(relayoutTask, 120);
        App.post(relayoutTask, 400);
    }

    private void startScrollListening() {
        if (scrollListening) return;
        scrollListening = true;
        getViewTreeObserver().addOnScrollChangedListener(onScroll);
    }

    private void stopScrollListening() {
        if (!scrollListening) return;
        scrollListening = false;
        try {
            getViewTreeObserver().removeOnScrollChangedListener(onScroll);
        } catch (IllegalStateException ignored) {
            // VTO 已失效（窗口销毁），无需处理
        }
    }

    public void detach() {
        stopScrollListening();
        for (View view : targets) view.removeOnLayoutChangeListener(relayout);
        targets.clear();
        if (getParent() != null) ((ViewGroup) getParent()).removeView(this);
    }

    // 重算洞位置并摆放气泡
    private void update() {
        holes.clear();
        int[] loc = new int[2];
        for (View view : targets) {
            if (view.getWindowToken() == null || view.getWidth() == 0 || view.getHeight() == 0) continue;
            view.getLocationInWindow(loc);
            RectF rect = new RectF(loc[0] - HOLE_PAD, loc[1] - HOLE_PAD, loc[0] + view.getWidth() + HOLE_PAD, loc[1] + view.getHeight() + HOLE_PAD);
            holes.add(rect);
        }
        spot.setHoles(holes);
        positionBubble();
    }

    private void positionBubble() {
        if (holes.isEmpty()) {
            bubble.setVisibility(INVISIBLE);
            return;
        }
        // 遮罩自身尚未完成首次布局（宽度=0）：先隐藏气泡，等 onSizeChanged 再精确摆放，避免"先顶部后跳下"的跳动
        if (getWidth() == 0 || getHeight() == 0) {
            bubble.setVisibility(INVISIBLE);
            return;
        }
        bubble.setVisibility(VISIBLE);
        // 用副本做并集，避免 union 污染 holes.get(0) 导致洞矩形被撑大
        RectF primary = new RectF(holes.get(0));
        for (RectF rect : holes) primary.union(rect);
        int width = Math.min(getWidth() - ResUtil.dp2px(32), ResUtil.dp2px(340));
        bubble.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
        int bh = bubble.getMeasuredHeight();
        int gap = ResUtil.dp2px(10);
        int top = (int) (primary.bottom + gap);
        if (top + bh > getHeight() - ResUtil.dp2px(16)) top = (int) (primary.top - gap - bh);
        if (top < ResUtil.dp2px(8)) top = (getHeight() - bh) / 2;
        int left = (int) (primary.centerX() - width / 2f);
        left = Math.max(ResUtil.dp2px(16), Math.min(left, getWidth() - width - ResUtil.dp2px(16)));
        // 用 LayoutParams 边距定位：手工 layout() 会被父容器后续布局重置回左上角
        LayoutParams lp = (LayoutParams) bubble.getLayoutParams();
        lp.width = width;
        lp.height = LayoutParams.WRAP_CONTENT;
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.leftMargin = left;
        lp.topMargin = top;
        bubble.setLayoutParams(lp);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        if (ev.getAction() == MotionEvent.ACTION_DOWN) {
            float x = ev.getX();
            float y = ev.getY();
            float slop = ResUtil.dp2px(4);
            for (RectF rect : holes) {
                if (x >= rect.left - slop && x <= rect.right + slop && y >= rect.top - slop && y <= rect.bottom + slop) return false;
            }
            if (bubble.getVisibility() == VISIBLE && x >= bubble.getLeft() && x <= bubble.getRight() && y >= bubble.getTop() && y <= bubble.getBottom()) return false;
            if (x >= skip.getLeft() && x <= skip.getRight() && y >= skip.getTop() && y <= skip.getBottom()) return false;
            return true;
        }
        return super.onInterceptTouchEvent(ev);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            float x = event.getX();
            float y = event.getY();
            float slop = ResUtil.dp2px(4);
            // 洞内：返回 false 让事件透传给下层真实控件（拦截发生在洞外）
            for (RectF rect : holes) {
                if (x >= rect.left - slop && x <= rect.right + slop && y >= rect.top - slop && y <= rect.bottom + slop) return false;
            }
            if (bubble.getVisibility() == VISIBLE && x >= bubble.getLeft() && x <= bubble.getRight() && y >= bubble.getTop() && y <= bubble.getBottom()) return false;
            if (x >= skip.getLeft() && x <= skip.getRight() && y >= skip.getTop() && y <= skip.getBottom()) return false;
            return true;
        }
        // 已消费过洞外 DOWN 的后续事件继续吞掉
        return true;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        // 首次拿到真实尺寸、键盘弹出、旋转等窗口重排场景：洞与气泡一并校准
        if (w > 0 && h > 0 && !targets.isEmpty()) update();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        stopScrollListening();
        App.removeCallbacks(relayoutTask);
        for (View view : targets) view.removeOnLayoutChangeListener(relayout);
    }

    // 半透明底 + 挖洞绘制层
    private static class SpotView extends View {

        private final List<RectF> holes = new ArrayList<>();
        private final Paint dim = new Paint();
        private final Paint clear = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);

        SpotView(Context context) {
            super(context);
            dim.setColor(0xB3000000);
            clear.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));
            ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeWidth(ResUtil.dp2px(2));
            ring.setColor(0x66FFFFFF);
        }

        void setHoles(List<RectF> rects) {
            holes.clear();
            holes.addAll(rects);
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            // 离屏层内先铺暗底再用 CLEAR 挖洞，保证洞透出下层界面而非透出窗口背景
            int layer = canvas.saveLayer(0, 0, getWidth(), getHeight(), null);
            canvas.drawRect(0, 0, getWidth(), getHeight(), dim);
            for (RectF rect : holes) canvas.drawRoundRect(rect, HOLE_RADIUS, HOLE_RADIUS, clear);
            canvas.restoreToCount(layer);
            for (RectF rect : holes) canvas.drawRoundRect(rect, HOLE_RADIUS, HOLE_RADIUS, ring);
        }
    }
}
