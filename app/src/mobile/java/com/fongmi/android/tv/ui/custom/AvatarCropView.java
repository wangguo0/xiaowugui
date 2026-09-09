package com.fongmi.android.tv.ui.custom;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Region;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

/**
 * 圆形头像裁剪视图：图片可拖动、双指缩放；圆形取景框外压暗，
 * 顶部实时圆形预览，所见即所得。
 */
public class AvatarCropView extends View {

    private Bitmap bitmap;
    private final Matrix matrix = new Matrix();
    private final Paint imagePaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final ScaleGestureDetector scaleDetector;
    private boolean scaling;
    private float lastFocusX;
    private float lastFocusY;

    // 取景圆（view 坐标）
    private float cropCx;
    private float cropCy;
    private float cropR;

    private static final float MIN_SCALE = 0.4f;
    private static final float MAX_SCALE = 5f;

    public AvatarCropView(Context context) {
        this(context, null);
    }

    public AvatarCropView(Context context, AttributeSet attrs) {
        super(context, attrs);
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(2f);
        borderPaint.setColor(Color.WHITE);
        gridPaint.setStyle(Paint.Style.STROKE);
        gridPaint.setStrokeWidth(1f);
        gridPaint.setColor(0x22FFFFFF);
        scaleDetector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector detector) {
                scaleAbout(detector.getFocusX(), detector.getFocusY(), detector.getScaleFactor());
                return true;
            }

            @Override
            public boolean onScaleBegin(ScaleGestureDetector detector) {
                scaling = true;
                return true;
            }

            @Override
            public void onScaleEnd(ScaleGestureDetector detector) {
                scaling = false;
            }
        });
    }

    public void setBitmap(Bitmap b) {
        bitmap = b;
        reset();
    }

    public Bitmap getBitmap() {
        return bitmap;
    }

    public void reset() {
        if (bitmap == null) return;
        matrix.reset();
        fitMatrix();
        clampPan();
        invalidate();
    }

    /**
     * 初始：把图片中心放到取景框中心，至少上下左右均铺满圆形取景框，
     * 再放大一点以方便裁剪调整。
     */
    private void fitMatrix() {
        float bw = bitmap.getWidth();
        float bh = bitmap.getHeight();
        if (bw <= 0 || bh <= 0) return;
        float dia = cropR * 2f;
        float scale = Math.max(dia / bw, dia / bh);
        matrix.reset();
        matrix.postScale(scale, scale, 0, 0);
        matrix.postTranslate(cropCx - bw * scale / 2f, cropCy - bh * scale / 2f);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        cropR = Math.min(w, h) * 0.34f;
        cropCx = w / 2f;
        cropCy = h / 2f;
        if (bitmap != null) reset();
    }

    private void scaleAbout(float fx, float fy, float factor) {
        float[] v = new float[9];
        matrix.getValues(v);
        float cur = (v[Matrix.MSCALE_X] + v[Matrix.MSCALE_Y]) / 2f;
        float next = clamp(cur * factor, MIN_SCALE, MAX_SCALE);
        float f = next / cur;
        matrix.postScale(f, f, fx, fy);
        clampPan();
        invalidate();
    }

    private static float clamp(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }

    /** 限制平移，保证图片始终覆盖圆形取景框 */
    private void clampPan() {
        if (bitmap == null) return;
        float[] v = new float[9];
        matrix.getValues(v);
        float scale = v[Matrix.MSCALE_X];
        float bmpW = bitmap.getWidth() * scale;
        float bmpH = bitmap.getHeight() * scale;
        float minTx = cropCx - bmpW + cropR;
        float maxTx = cropCx + bmpW - cropR;
        float minTy = cropCy - bmpH + cropR;
        float maxTy = cropCy + bmpH - cropR;
        float tx;
        float ty;
        if (minTx > maxTx) {
            tx = cropCx - bmpW / 2f;
        } else {
            tx = clamp(v[Matrix.MTRANS_X], minTx, maxTx);
        }
        if (minTy > maxTy) {
            ty = cropCy - bmpH / 2f;
        } else {
            ty = clamp(v[Matrix.MTRANS_Y], minTy, maxTy);
        }
        v[Matrix.MTRANS_X] = tx;
        v[Matrix.MTRANS_Y] = ty;
        matrix.setValues(v);
    }

    /** 按当前取景对原图进行中心方形+圆形裁剪，返回正方形 Bitmap（含透明圆角）。 */
    public Bitmap crop() {
        if (bitmap == null) return null;
        Matrix inverse = new Matrix();
        if (!matrix.invert(inverse)) return null;
        // 取景方框四个角（边长=直径）
        float half = cropR;
        float[] corners = {
                cropCx - half, cropCy - half,
                cropCx + half, cropCy - half,
                cropCx + half, cropCy + half,
                cropCx - half, cropCy + half
        };
        inverse.mapPoints(corners);
        float left = Float.MAX_VALUE, top = Float.MAX_VALUE, right = -Float.MAX_VALUE, bottom = -Float.MAX_VALUE;
        for (int i = 0; i < corners.length / 2; i++) {
            left = Math.min(left, corners[i * 2]);
            top = Math.min(top, corners[i * 2 + 1]);
            right = Math.max(right, corners[i * 2]);
            bottom = Math.max(bottom, corners[i * 2 + 1]);
        }
        left = Math.max(0, left);
        top = Math.max(0, top);
        right = Math.min(bitmap.getWidth(), right);
        bottom = Math.min(bitmap.getHeight(), bottom);
        int cw = (int) (right - left);
        int ch = (int) (bottom - top);
        if (cw <= 0 || ch <= 0) return null;
        int side = Math.max(cw, ch);
        int bx = (int) Math.max(0, Math.min(left, bitmap.getWidth() - side));
        int by = (int) Math.max(0, Math.min(top, bitmap.getHeight() - side));
        Bitmap square = Bitmap.createBitmap(bitmap, bx, by, side, side);
        int out = 512;
        Bitmap squared = Bitmap.createScaledBitmap(square, out, out, true);
        return applyCircle(squared);
    }

    private static Bitmap applyCircle(Bitmap src) {
        int size = src.getWidth();
        Bitmap out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        c.drawCircle(size / 2f, size / 2f, size / 2f, p);
        // 用 XFERMODE 合成图片到圆内
        android.graphics.BitmapShader shader = new android.graphics.BitmapShader(src,
                android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP);
        p.setXfermode(new android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC_IN));
        p.setShader(shader);
        c.drawRect(0, 0, size, size, p);
        return out;
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        scaleDetector.onTouchEvent(event);
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN: {
                lastFocusX = pointerX(event);
                lastFocusY = pointerY(event);
                break;
            }
            case MotionEvent.ACTION_MOVE: {
                if (scaling || event.getPointerCount() > 1) break;
                float dx = event.getX() - lastFocusX;
                float dy = event.getY() - lastFocusY;
                matrix.postTranslate(dx, dy);
                clampPan();
                lastFocusX = event.getX();
                lastFocusY = event.getY();
                invalidate();
                break;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                scaling = false;
                break;
            }
        }
        return true;
    }

    private static float pointerX(MotionEvent e) {
        return e.getPointerCount() > 1 ? (e.getX(0) + e.getX(1)) / 2f : e.getX();
    }

    private static float pointerY(MotionEvent e) {
        return e.getPointerCount() > 1 ? (e.getY(0) + e.getY(1)) / 2f : e.getY();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawColor(0xCC000000);
        if (bitmap == null) return;
        // 原图（含拖动缩放）
        canvas.drawBitmap(bitmap, matrix, imagePaint);
        // 取景框外压暗
        Path cropPath = new Path();
        cropPath.addCircle(cropCx, cropCy, cropR, Path.Direction.CW);
        canvas.save();
        canvas.clipPath(cropPath, Region.Op.DIFFERENCE);
        canvas.drawColor(0x99000000);
        canvas.restore();
        // 圆形边框与九宫格
        canvas.drawCircle(cropCx, cropCy, cropR, borderPaint);
        drawGrid(canvas);
    }

    private void drawGrid(Canvas canvas) {
        float l = cropCx - cropR, t = cropCy - cropR, r = cropCx + cropR, b = cropCy + cropR;
        float th = (r - l) / 3f;
        for (int i = 1; i < 3; i++) {
            canvas.drawLine(l + th * i, t, l + th * i, b, gridPaint);
            canvas.drawLine(l, t + th * i, r, t + th * i, gridPaint);
        }
    }
}