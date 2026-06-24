package com.fongmi.android.tv.ui.custom;

import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fongmi.android.tv.setting.Setting;

public class BuiltInWallDrawable extends Drawable {

    private final Paint paint;
    private final Paint bitmapPaint;
    private final int wall;
    private Bitmap bitmap;
    private int bitmapWidth;
    private int bitmapHeight;
    private int alpha = 255;
    private ColorFilter colorFilter;

    public BuiltInWallDrawable(int wall) {
        this.wall = wall;
        this.paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        this.paint.setDither(true);
        this.bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        Rect bounds = getBounds();
        if (bounds.width() <= 0 || bounds.height() <= 0) return;
        ensureBitmap(bounds.width(), bounds.height());
        bitmapPaint.setAlpha(alpha);
        bitmapPaint.setColorFilter(colorFilter);
        canvas.drawBitmap(bitmap, bounds.left, bounds.top, bitmapPaint);
    }

    @Override
    public void setAlpha(int alpha) {
        this.alpha = alpha;
        invalidateSelf();
    }

    @Override
    public void setColorFilter(@Nullable ColorFilter colorFilter) {
        this.colorFilter = colorFilter;
        invalidateSelf();
    }

    @Override
    public int getOpacity() {
        return PixelFormat.OPAQUE;
    }

    private void ensureBitmap(int width, int height) {
        if (bitmap != null && bitmapWidth == width && bitmapHeight == height) return;
        if (bitmap != null) bitmap.recycle();
        bitmapWidth = width;
        bitmapHeight = height;
        bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        render(new Canvas(bitmap), width, height);
    }

    private void render(Canvas canvas, int width, int height) {
        switch (wall) {
            case Setting.WALL_AURORA_GLASS -> auroraFlow(canvas, width, height);
            case Setting.WALL_SUNSET_PRISM -> coralDusk(canvas, width, height);
            case Setting.WALL_MINT_GLACIER -> mintNebula(canvas, width, height);
            case Setting.WALL_LIQUID_CHROME -> silverCurrent(canvas, width, height);
            case Setting.WALL_NEON_BERRY -> berryNebula(canvas, width, height);
            case Setting.WALL_CHAMPAGNE_MIST -> champagneDawn(canvas, width, height);
            default -> solid(canvas, width, height);
        }
        vignette(canvas, width, height);
        grain(canvas, width, height);
    }

    private void auroraFlow(Canvas canvas, int width, int height) {
        background(canvas, width, height, 0xFF0B2737, 0xFF2B8ECB, 0xFF8B6FE8);
        glow(canvas, width * 0.12f, height * 0.20f, width * 0.80f, 0xAA46D6FF);
        glow(canvas, width * 0.86f, height * 0.18f, width * 0.76f, 0x8A5B79FF);
        glow(canvas, width * 0.54f, height * 0.86f, width * 0.82f, 0x884ED9B7);
        flow(canvas, width, height, 0x66CFFBFF, 0.16f, 0.40f, 0.54f, 0.08f);
        flow(canvas, width, height, 0x4D9E7DFF, 0.46f, 0.68f, 0.42f, -0.16f);
        haze(canvas, width, height, 0x33FFFFFF, 0.18f, 0.58f, 1.06f);
    }

    private void coralDusk(Canvas canvas, int width, int height) {
        background(canvas, width, height, 0xFF24152F, 0xFFFF6C8E, 0xFF5B67D6);
        glow(canvas, width * 0.18f, height * 0.22f, width * 0.78f, 0xB5FF8E72);
        glow(canvas, width * 0.82f, height * 0.52f, width * 0.76f, 0x96FFD466);
        glow(canvas, width * 0.60f, height * 0.12f, width * 0.58f, 0x805C7CFA);
        flow(canvas, width, height, 0x70FFE7B1, 0.30f, 0.56f, 0.50f, -0.12f);
        flow(canvas, width, height, 0x4DFF72B9, 0.58f, 0.82f, 0.42f, 0.20f);
        haze(canvas, width, height, 0x28FFFFFF, 0.08f, 0.40f, 0.96f);
    }

    private void mintNebula(Canvas canvas, int width, int height) {
        background(canvas, width, height, 0xFF12323D, 0xFF55D8BE, 0xFF6C82F1);
        glow(canvas, width * 0.76f, height * 0.18f, width * 0.76f, 0xAA90FFE5);
        glow(canvas, width * 0.18f, height * 0.72f, width * 0.84f, 0x7747B8FF);
        glow(canvas, width * 0.46f, height * 0.48f, width * 0.54f, 0x66E7FFF6);
        flow(canvas, width, height, 0x6695FFF1, 0.16f, 0.32f, 0.34f, 0.18f);
        flow(canvas, width, height, 0x446BA8FF, 0.54f, 0.82f, 0.48f, -0.08f);
        haze(canvas, width, height, 0x2CFFFFFF, 0.24f, 0.58f, 0.88f);
    }

    private void silverCurrent(Canvas canvas, int width, int height) {
        background(canvas, width, height, 0xFF121826, 0xFF53657F, 0xFF27304C);
        glow(canvas, width * 0.28f, height * 0.18f, width * 0.74f, 0x7CA8D4FF);
        glow(canvas, width * 0.86f, height * 0.68f, width * 0.90f, 0x88DDB3FF);
        glow(canvas, width * 0.34f, height * 0.82f, width * 0.76f, 0x55FFF7EC);
        current(canvas, width, height, 0x68FFFFFF, 0.22f, -0.18f);
        current(canvas, width, height, 0x4EA7D7FF, 0.50f, 0.08f);
        current(canvas, width, height, 0x3DF2E9FF, 0.72f, -0.04f);
        haze(canvas, width, height, 0x22FFFFFF, 0.02f, 0.36f, 1.00f);
    }

    private void berryNebula(Canvas canvas, int width, int height) {
        background(canvas, width, height, 0xFF201337, 0xFF7B42CF, 0xFF0F4660);
        glow(canvas, width * 0.18f, height * 0.20f, width * 0.76f, 0xB0FF4FCB);
        glow(canvas, width * 0.82f, height * 0.28f, width * 0.68f, 0x8A6BDBFF);
        glow(canvas, width * 0.50f, height * 0.84f, width * 0.86f, 0x88B76BFF);
        flow(canvas, width, height, 0x66FF7BD7, 0.24f, 0.54f, 0.46f, 0.16f);
        flow(canvas, width, height, 0x5269D8FF, 0.48f, 0.76f, 0.48f, -0.18f);
        haze(canvas, width, height, 0x26FFFFFF, 0.22f, 0.50f, 1.10f);
    }

    private void champagneDawn(Canvas canvas, int width, int height) {
        background(canvas, width, height, 0xFF3B2B36, 0xFFC07A9F, 0xFF7E6548);
        glow(canvas, width * 0.18f, height * 0.18f, width * 0.86f, 0x8EFFD8A8);
        glow(canvas, width * 0.84f, height * 0.52f, width * 0.76f, 0x7AF5A1C7);
        glow(canvas, width * 0.46f, height * 0.86f, width * 0.80f, 0x68FFE9D1);
        flow(canvas, width, height, 0x5CFFE8C8, 0.22f, 0.44f, 0.38f, -0.12f);
        flow(canvas, width, height, 0x42FFC7E1, 0.50f, 0.78f, 0.42f, 0.18f);
        haze(canvas, width, height, 0x30FFFFFF, 0.16f, 0.46f, 0.94f);
    }

    private void solid(Canvas canvas, int width, int height) {
        paint.setShader(null);
        paint.setColor(Setting.getBuiltInWallColor(wall));
        canvas.drawRect(0, 0, width, height, paint);
    }

    private void background(Canvas canvas, int width, int height, int top, int center, int bottom) {
        paint.setShader(new LinearGradient(0, 0, width, height, new int[]{top, center, bottom}, new float[]{0f, 0.48f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, width, height, paint);
        paint.setShader(new LinearGradient(width, 0, 0, height, 0x44000000, 0x00000000, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, width, height, paint);
    }

    private void glow(Canvas canvas, float cx, float cy, float radius, int color) {
        paint.setShader(new RadialGradient(cx, cy, radius, color, Color.TRANSPARENT, Shader.TileMode.CLAMP));
        canvas.drawCircle(cx, cy, radius, paint);
    }

    private void flow(Canvas canvas, int width, int height, int color, float start, float end, float thickness, float phase) {
        Path path = new Path();
        float p = height * phase;
        path.moveTo(-width * 0.18f, height * start + p);
        path.cubicTo(width * 0.22f, height * (start - 0.18f) - p, width * 0.54f, height * (end - 0.28f), width * 1.18f, height * (end - 0.12f) + p);
        path.lineTo(width * 1.18f, height * (end + thickness * 0.36f) + p);
        path.cubicTo(width * 0.58f, height * (end + thickness * 0.12f), width * 0.28f, height * (start + thickness * 0.58f) + p, -width * 0.18f, height * (start + thickness * 0.42f) - p);
        path.close();
        paint.setShader(new LinearGradient(0, height * start, width, height * end, new int[]{0x00FFFFFF, color, 0x00FFFFFF}, new float[]{0f, 0.52f, 1f}, Shader.TileMode.CLAMP));
        paint.setMaskFilter(new BlurMaskFilter(Math.max(18f, width * 0.030f), BlurMaskFilter.Blur.NORMAL));
        canvas.drawPath(path, paint);
        paint.setMaskFilter(null);
    }

    private void current(Canvas canvas, int width, int height, int color, float y, float phase) {
        Path path = new Path();
        float p = height * phase;
        path.moveTo(-width * 0.12f, height * y + p);
        path.cubicTo(width * 0.16f, height * (y - 0.16f), width * 0.38f, height * (y + 0.20f), width * 0.64f, height * (y + 0.02f));
        path.cubicTo(width * 0.86f, height * (y - 0.12f), width * 1.04f, height * (y + 0.02f), width * 1.14f, height * (y - 0.04f));
        path.lineTo(width * 1.14f, height * (y + 0.16f));
        path.cubicTo(width * 0.78f, height * (y + 0.30f), width * 0.44f, height * (y + 0.04f), -width * 0.12f, height * (y + 0.22f));
        path.close();
        paint.setShader(new LinearGradient(0, height * y, width, height * (y + 0.18f), new int[]{0x00FFFFFF, color, 0x12FFFFFF}, null, Shader.TileMode.CLAMP));
        paint.setMaskFilter(new BlurMaskFilter(Math.max(16f, width * 0.026f), BlurMaskFilter.Blur.NORMAL));
        canvas.drawPath(path, paint);
        paint.setMaskFilter(null);
    }

    private void haze(Canvas canvas, int width, int height, int color, float cx, float cy, float scale) {
        paint.setShader(new RadialGradient(width * cx, height * cy, Math.max(width, height) * scale, color, Color.TRANSPARENT, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, width, height, paint);
    }

    private void vignette(Canvas canvas, int width, int height) {
        float radius = Math.max(width, height) * 0.78f;
        paint.setShader(new RadialGradient(width / 2f, height / 2f, radius, 0x00000000, 0x66000000, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, width, height, paint);
        paint.setShader(new LinearGradient(0, 0, 0, height, 0x14000000, 0x52000000, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, width, height, paint);
    }

    private void grain(Canvas canvas, int width, int height) {
        paint.setShader(null);
        int count = Math.max(180, Math.min(620, width * height / 4200));
        for (int i = 0; i < count; i++) {
            float x = width * noise(i * 37 + wall * 19);
            float y = height * noise(i * 53 + wall * 23);
            int alpha = 5 + (int) (noise(i * 89 + wall * 29) * 9f);
            paint.setColor(Color.argb(alpha, 255, 255, 255));
            canvas.drawPoint(x, y, paint);
        }
    }

    private float noise(int value) {
        int x = value;
        x ^= x << 13;
        x ^= x >>> 17;
        x ^= x << 5;
        return (x & 0x7FFFFFFF) / (float) 0x7FFFFFFF;
    }
}
