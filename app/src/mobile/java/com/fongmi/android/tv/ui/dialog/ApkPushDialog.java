package com.fongmi.android.tv.ui.dialog;

import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.Constant;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.Device;
import com.fongmi.android.tv.databinding.DialogDeviceBinding;
import com.fongmi.android.tv.server.Server;
import com.fongmi.android.tv.ui.adapter.SyncDeviceAdapter;
import com.fongmi.android.tv.ui.custom.SpaceItemDecoration;
import com.fongmi.android.tv.utils.FileUtil;
import com.fongmi.android.tv.utils.NsdDeviceDiscovery;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.ProgressRequestBody;
import com.fongmi.android.tv.utils.ScanTask;
import com.fongmi.android.tv.utils.Task;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Path;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.RequestBody;
import okhttp3.Response;

public class ApkPushDialog extends BaseBottomSheetDialog implements SyncDeviceAdapter.OnClickListener, ScanTask.Listener, NsdDeviceDiscovery.Listener {

    private static final String TAG = "apk_push";
    private static final String PART_NAME = "apk";
    private static final int MAX_RETRY = 2;
    private static final MediaType APK = MediaType.parse("application/vnd.android.package-archive");

    private final OkHttpClient client = OkHttp.client(Constant.TIMEOUT_SYNC_TRANSFER);
    private final NsdDeviceDiscovery discovery = new NsdDeviceDiscovery(this);
    private final Uri uri;

    private DialogDeviceBinding binding;
    private SyncDeviceAdapter adapter;
    private ScanTask scanTask = new ScanTask(this);
    private Call uploadCall;
    private File apk;
    private String fileName;
    private String sha256;
    private volatile boolean pushing;
    private long lastProgressUpdate;

    private ApkPushDialog(Uri uri) {
        this.uri = uri;
    }

    public static ApkPushDialog create(Uri uri) {
        return new ApkPushDialog(uri);
    }

    public void show(FragmentActivity activity) {
        if (activity.getSupportFragmentManager().isStateSaved()) return;
        for (Fragment fragment : activity.getSupportFragmentManager().getFragments()) if (fragment instanceof ApkPushDialog) return;
        showNow(activity.getSupportFragmentManager(), TAG);
    }

    @Override
    protected ViewBinding getBinding(@NonNull LayoutInflater inflater, @Nullable ViewGroup container) {
        return binding = DialogDeviceBinding.inflate(inflater, container, false);
    }

    @Override
    protected void initView() {
        Server.get().start();
        binding.title.setText(R.string.apk_push_preparing);
        binding.scan.setVisibility(View.GONE);
        binding.recycler.setHasFixedSize(false);
        binding.recycler.addItemDecoration(new SpaceItemDecoration(1, 8));
        binding.recycler.setAdapter(adapter = new SyncDeviceAdapter(this));
        prepare();
    }

    @Override
    protected void initEvent() {
        binding.refresh.setOnClickListener(view -> refresh());
    }

    private void prepare() {
        Task.execute(() -> {
            try {
                fileName = getDisplayName();
                if (fileName == null || !fileName.toLowerCase(Locale.ROOT).endsWith(".apk")) throw new IllegalArgumentException(getString(R.string.apk_push_invalid));
                fileName = sanitize(fileName);
                apk = Path.cache("apk-push-" + System.currentTimeMillis() + ".apk");
                copyUri(apk);
                if (requireContext().getPackageManager().getPackageArchiveInfo(apk.getAbsolutePath(), 0) == null) throw new IllegalArgumentException(getString(R.string.apk_push_invalid));
                sha256 = sha256(apk);
                App.post(() -> {
                    if (binding == null) return;
                    binding.title.setText(getString(R.string.apk_push_ready, fileName, FileUtil.byteCountToDisplaySize(apk.length())));
                    refresh();
                });
            } catch (Exception e) {
                if (apk != null) Path.clear(apk);
                App.post(() -> {
                    Notify.show(e.getMessage());
                    dismissAllowingStateLoss();
                });
            }
        });
    }

    private String getDisplayName() {
        try (Cursor cursor = requireContext().getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) return cursor.getString(0);
        } catch (Exception ignored) {
        }
        return uri.getLastPathSegment();
    }

    private String sanitize(String name) {
        String result = name.replace('\\', '_').replace('/', '_').replace('\u0000', '_').trim();
        return result.isEmpty() ? "app.apk" : result;
    }

    private void copyUri(File target) throws Exception {
        long available = FileUtil.getAvailableStorageSpace(Path.cache());
        try (InputStream raw = requireContext().getContentResolver().openInputStream(uri)) {
            if (raw == null) throw new IllegalStateException(getString(R.string.apk_push_read_failed));
            try (BufferedInputStream input = new BufferedInputStream(raw); BufferedOutputStream output = new BufferedOutputStream(new FileOutputStream(Path.create(target)))) {
                byte[] buffer = new byte[64 * 1024];
                long written = 0;
                int count;
                while ((count = input.read(buffer)) != -1) {
                    written += count;
                    if (available > 0 && written + 16L * 1024 * 1024 > available) throw new IllegalStateException(getString(R.string.apk_push_no_space));
                    output.write(buffer, 0, count);
                }
            }
        }
        if (target.length() <= 0) throw new IllegalStateException(getString(R.string.apk_push_read_failed));
    }

    private void refresh() {
        if (pushing || apk == null) return;
        discovery.stop();
        scanTask.stop();
        scanTask = new ScanTask(this);
        adapter.clear(() -> {
            binding.recycler.setVisibility(View.GONE);
            discovery.start();
            scanTask.start();
        });
    }

    @Override
    public void onServiceFound(String url) {
        scanTask.start(url);
    }

    @Override
    public void onFind(Device device) {
        if (device == null || !device.isApp() || Device.get().equals(device)) return;
        adapter.sort(device, () -> binding.recycler.setVisibility(View.VISIBLE));
    }

    @Override
    public void onLost(Device device) {
        adapter.remove(device, () -> binding.recycler.setVisibility(adapter.getItemCount() == 0 ? View.GONE : View.VISIBLE));
    }

    @Override
    public void onFinish() {
    }

    @Override
    public void onItemClick(Device device) {
        if (pushing || apk == null) return;
        pushing = true;
        stopScan();
        upload(device, 0);
    }

    private void upload(Device device, int retry) {
        RequestBody fileBody = new ProgressRequestBody(apk, APK, this::onProgress);
        MultipartBody body = new MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("device", Device.get().toString())
                .addFormDataPart("name", fileName)
                .addFormDataPart("size", String.valueOf(apk.length()))
                .addFormDataPart("sha256", sha256)
                .addFormDataPart(PART_NAME, fileName, fileBody)
                .build();
        String url = device.getIp() + "/action?do=apk";
        uploadCall = OkHttp.newCall(client, url, body);
        uploadCall.enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull java.io.IOException e) {
                if (call.isCanceled()) return;
                if (retry < MAX_RETRY) {
                    Task.schedule(() -> upload(device, retry + 1), 600, TimeUnit.MILLISECONDS);
                    return;
                }
                App.post(() -> failed(e.getMessage()));
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) {
                try (Response res = response) {
                    String ack = res.body() == null ? "" : res.body().string().trim();
                    if (res.isSuccessful() && "APK received".equals(ack)) App.post(() -> {
                        pushing = false;
                        Notify.show(R.string.apk_push_success);
                        dismissAllowingStateLoss();
                    });
                    else if (retry < MAX_RETRY) Task.schedule(() -> upload(device, retry + 1), 600, TimeUnit.MILLISECONDS);
                    else App.post(() -> failed(res.isSuccessful() ? getString(R.string.apk_push_receiver_unsupported) : "HTTP " + res.code()));
                }
            }
        });
    }

    private void onProgress(long written, long total) {
        long now = System.currentTimeMillis();
        if (now - lastProgressUpdate < 250 && written < total) return;
        lastProgressUpdate = now;
        int percent = total <= 0 ? 0 : (int) Math.min(100, written * 100 / total);
        App.post(() -> {
            if (binding != null) binding.title.setText(getString(R.string.apk_push_progress, percent, FileUtil.byteCountToDisplaySize(written), FileUtil.byteCountToDisplaySize(total)));
        });
    }

    private void failed(String reason) {
        pushing = false;
        binding.title.setText(getString(R.string.apk_push_ready, fileName, FileUtil.byteCountToDisplaySize(apk.length())));
        Notify.show(getString(R.string.apk_push_failed, reason));
        refresh();
    }

    private void stopScan() {
        discovery.stop();
        scanTask.stop();
    }

    private String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        StringBuilder result = new StringBuilder();
        for (byte value : digest.digest()) result.append(String.format(Locale.ROOT, "%02x", value));
        return result.toString();
    }

    @Override
    public void onDestroyView() {
        if (uploadCall != null) uploadCall.cancel();
        stopScan();
        if (apk != null) Path.clear(apk);
        binding = null;
        super.onDestroyView();
    }
}
