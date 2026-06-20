package com.fongmi.android.tv.ui.custom;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;

import androidx.appcompat.widget.AppCompatImageView;

public class ContextWallImageView extends AppCompatImageView {

    private final Matrix matrix;
    private final Paint featherPaint;
    private final PorterDuffXfermode featherMode;

    public ContextWallImageView(Context context) {
        this(context, null);
    }

    public ContextWallImageView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public ContextWallImageView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        matrix = new Matrix();
        featherPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        featherMode = new PorterDuffXfermode(PorterDuff.Mode.DST_OUT);
        setScaleType(ScaleType.MATRIX);
    }

    @Override
    public void setImageDrawable(Drawable drawable) {
        super.setImageDrawable(drawable);
        updateMatrix();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        updateMatrix();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int save = canvas.saveLayer(0, 0, getWidth(), getHeight(), null);
        super.onDraw(canvas);
        drawFeather(canvas);
        canvas.restoreToCount(save);
    }

    private void updateMatrix() {
        Drawable drawable = getDrawable();
        int viewWidth = getWidth() - getPaddingLeft() - getPaddingRight();
        int viewHeight = getHeight() - getPaddingTop() - getPaddingBottom();
        if (drawable == null || viewWidth <= 0 || viewHeight <= 0) return;

        int drawableWidth = drawable.getIntrinsicWidth();
        int drawableHeight = drawable.getIntrinsicHeight();
        if (drawableWidth <= 0 || drawableHeight <= 0) return;

        float viewRatio = viewWidth / (float) viewHeight;
        float drawableRatio = drawableWidth / (float) drawableHeight;
        boolean narrow = drawableRatio < Math.min(1.2f, viewRatio * 0.75f);
        float scale;
        float dx;
        float dy;

        if (narrow) {
            scale = viewWidth / (float) drawableWidth;
            dx = 0f;
            dy = 0f;
        } else {
            scale = Math.max(viewWidth / (float) drawableWidth, viewHeight / (float) drawableHeight);
            dx = (viewWidth - drawableWidth * scale) * 0.5f;
            dy = (viewHeight - drawableHeight * scale) * 0.5f;
        }

        matrix.reset();
        matrix.setScale(scale, scale);
        matrix.postTranslate(Math.round(dx) + getPaddingLeft(), Math.round(dy) + getPaddingTop());
        setImageMatrix(matrix);
    }

    private void drawFeather(Canvas canvas) {
        int width = getWidth();
        int height = getHeight();
        if (getDrawable() == null || width <= 0 || height <= 0) return;
        float horizontal = width * 0.22f;
        float top = height * 0.30f;
        float bottom = height * 0.42f;
        featherPaint.setXfermode(featherMode);

        featherPaint.setShader(new LinearGradient(0, 0, horizontal, 0, 0xEE000000, 0x00000000, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, horizontal, height, featherPaint);
        featherPaint.setShader(new LinearGradient(width, 0, width - horizontal, 0, 0xEE000000, 0x00000000, Shader.TileMode.CLAMP));
        canvas.drawRect(width - horizontal, 0, width, height, featherPaint);
        featherPaint.setShader(new LinearGradient(0, 0, 0, top, 0xCC000000, 0x00000000, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, width, top, featherPaint);
        featherPaint.setShader(new LinearGradient(0, height, 0, height - bottom, 0xFF000000, 0x00000000, Shader.TileMode.CLAMP));
        canvas.drawRect(0, height - bottom, width, height, featherPaint);

        featherPaint.setShader(null);
        featherPaint.setXfermode(null);
    }
}
