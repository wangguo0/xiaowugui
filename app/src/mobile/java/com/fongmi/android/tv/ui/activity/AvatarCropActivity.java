package com.fongmi.android.tv.ui.activity;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;

import androidx.annotation.Nullable;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.databinding.ActivityAvatarCropBinding;
import com.fongmi.android.tv.ui.base.BaseActivity;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/**
 * 圆形头像裁剪页：选好本地图片后进入，拖动/缩放定位裁剪区域，确定后保存圆形头像。
 */
public class AvatarCropActivity extends BaseActivity {

    public static final String EXTRA_PATH = "avatar_path";
    public static final String EXTRA_URI = "avatar_uri";
    private static final int MAX_DECODE = 2048;

    private ActivityAvatarCropBinding mBinding;

    public static Intent buildIntent(Activity activity, Uri uri) {
        Intent intent = new Intent(activity, AvatarCropActivity.class);
        intent.putExtra(AvatarCropActivity.EXTRA_URI, uri.toString());
        return intent;
    }

    @Override
    protected ViewBinding getBinding() {
        return mBinding = ActivityAvatarCropBinding.inflate(getLayoutInflater());
    }

    @Override
    protected boolean customWall() {
        return false;
    }

    @Override
    protected void initView(@Nullable Bundle savedInstanceState) {
        String uri = getIntent().getStringExtra(EXTRA_URI);
        if (uri == null) {
            finish();
            return;
        }
        Bitmap bitmap = decodeSampled(Uri.parse(uri));
        if (bitmap == null) {
            setResult(RESULT_CANCELED);
            finish();
            return;
        }
        mBinding.cropView.setBitmap(bitmap);
    }

    @Override
    protected void initEvent() {
        mBinding.confirm.setOnClickListener(view -> confirmCrop());
        mBinding.cancel.setOnClickListener(view -> cancel());
    }

    private Bitmap decodeSampled(Uri uri) {
        try {
            BitmapFactory.Options opt = new BitmapFactory.Options();
            opt.inJustDecodeBounds = true;
            try (InputStream is = getContentResolver().openInputStream(uri)) {
                BitmapFactory.decodeStream(is, null, opt);
            }
            int w = opt.outWidth;
            int h = opt.outHeight;
            if (w <= 0 || h <= 0) return null;
            int sample = 1;
            while (Math.max(w / sample, h / sample) > MAX_DECODE) sample *= 2;
            try (InputStream is2 = getContentResolver().openInputStream(uri)) {
                BitmapFactory.Options opt2 = new BitmapFactory.Options();
                opt2.inSampleSize = sample;
                return BitmapFactory.decodeStream(is2, null, opt2);
            }
        } catch (Throwable e) {
            e.printStackTrace();
            return null;
        }
    }

    private void confirmCrop() {
        try {
            Bitmap bm = mBinding.cropView.crop();
            if (bm == null) {
                cancel();
                return;
            }
            File dst = new File(getFilesDir(), "avatar_" + System.currentTimeMillis() + ".png");
            try (FileOutputStream fos = new FileOutputStream(dst)) {
                bm.compress(Bitmap.CompressFormat.PNG, 100, fos);
            }
            setResult(RESULT_OK, new Intent().putExtra(EXTRA_PATH, dst.getAbsolutePath()));
            finish();
        } catch (Throwable e) {
            e.printStackTrace();
            cancel();
        }
    }

    private void cancel() {
        setResult(RESULT_CANCELED);
        finish();
    }
}