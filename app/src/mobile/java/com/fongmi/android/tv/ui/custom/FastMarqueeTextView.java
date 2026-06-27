package com.fongmi.android.tv.ui.custom;

import android.animation.ValueAnimator;
import android.content.Context;
import android.text.TextUtils;
import android.util.AttributeSet;

import com.google.android.material.textview.MaterialTextView;

public class FastMarqueeTextView extends MaterialTextView {

    private static final float SPEED_DP_PER_SECOND = 96f;
    private static final long START_DELAY_MS = 150;
    private static final long END_HOLD_MS = 450;

    private ValueAnimator animator;
    private boolean marquee;

    public FastMarqueeTextView(Context context) {
        super(context);
    }

    public FastMarqueeTextView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public FastMarqueeTextView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public void setFastMarquee(boolean marquee) {
        if (this.marquee == marquee) {
            if (marquee) post(this::startFastMarquee);
            return;
        }
        this.marquee = marquee;
        if (marquee) post(this::startFastMarquee);
        else stopFastMarquee();
    }

    @Override
    protected void onTextChanged(CharSequence text, int start, int before, int count) {
        super.onTextChanged(text, start, before, count);
        if (marquee) post(this::startFastMarquee);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (marquee) post(this::startFastMarquee);
    }

    @Override
    protected void onDetachedFromWindow() {
        stopFastMarquee();
        super.onDetachedFromWindow();
    }

    private void startFastMarquee() {
        cancelAnimator();
        if (!marquee || getWidth() <= 0 || TextUtils.isEmpty(getText())) {
            scrollTo(0, 0);
            return;
        }
        int available = getWidth() - getCompoundPaddingLeft() - getCompoundPaddingRight();
        int overflow = Math.max(0, Math.round(getPaint().measureText(getText().toString()) - available));
        if (overflow <= 0) {
            scrollTo(0, 0);
            return;
        }
        int holdDistance = Math.max(1, Math.round(getResources().getDisplayMetrics().density * SPEED_DP_PER_SECOND * END_HOLD_MS / 1000f));
        long duration = Math.max(700, Math.round((overflow + holdDistance) * 1000f / (getResources().getDisplayMetrics().density * SPEED_DP_PER_SECOND)));
        animator = ValueAnimator.ofInt(0, overflow + holdDistance);
        animator.setStartDelay(START_DELAY_MS);
        animator.setDuration(duration);
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.setRepeatMode(ValueAnimator.RESTART);
        animator.addUpdateListener(animation -> scrollTo(Math.min((int) animation.getAnimatedValue(), overflow), 0));
        animator.start();
    }

    private void stopFastMarquee() {
        cancelAnimator();
        scrollTo(0, 0);
    }

    private void cancelAnimator() {
        if (animator == null) return;
        animator.cancel();
        animator = null;
    }
}
