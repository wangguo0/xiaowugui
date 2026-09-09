package com.fongmi.android.tv.ui.dialog;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.view.View;

import androidx.fragment.app.Fragment;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.BuildConfig;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.DialogShareAppBinding;
import com.fongmi.android.tv.utils.Github;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.QRCode;
import com.fongmi.android.tv.utils.Task;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class ShareAppDialog extends BaseAlertDialog {

    private static final String TAG = "ShareAppProxy";
    private static final String UA = "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36";
    private static final String RAW_LIST = "https://raw.githubusercontent.com/AClon314/mirror-cn/refs/heads/main/src/mirror_cn/mirror_cn.py";
    private static final String[] LIST_SOURCES = {
            "https://gh-proxy.com/" + RAW_LIST,
            "https://ghfast.top/" + RAW_LIST,
            "https://ghproxy.net/" + RAW_LIST,
            "https://gh.llkk.cc/" + RAW_LIST,
            "https://mirror.ghproxy.com/" + RAW_LIST,
            "https://github.akams.cn/" + RAW_LIST,
            "https://ghproxy.1888866.xyz/" + RAW_LIST,
            RAW_LIST,
            "https://cdn.jsdelivr.net/gh/AClon314/mirror-cn@main/src/mirror_cn/mirror_cn.py"
    };
    private static final Pattern PATTERN = Pattern.compile("\"(https?://[^\"]+?)/https://github\\.com\"");
    private static final int MAX_LINES = 24;
    private static final int TEST_TIMEOUT = 5000;
    private static final int DETECT_BUDGET = 8000;

    // 独立裸客户端：不经过 App 的代理选择器/自定义 DNS/拦截器，反映设备真实直连能力
    private static final OkHttpClient PROBE = new OkHttpClient.Builder()
            .connectTimeout(TEST_TIMEOUT, TimeUnit.MILLISECONDS)
            .readTimeout(TEST_TIMEOUT, TimeUnit.MILLISECONDS)
            .writeTimeout(TEST_TIMEOUT, TimeUnit.MILLISECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build();
    private static final OkHttpClient FETCH = new OkHttpClient.Builder()
            .connectTimeout(6000, TimeUnit.MILLISECONDS)
            .readTimeout(6000, TimeUnit.MILLISECONDS)
            .writeTimeout(6000, TimeUnit.MILLISECONDS)
            .build();

    private DialogShareAppBinding binding;
    private volatile boolean destroyed = false;
    private String shareUrl = getLatestApkUrl();

    private static String getLatestApkUrl() {
        return Github.getGithubLatestAsset(BuildConfig.FLAVOR_mode + "-" + BuildConfig.FLAVOR_abi + ".apk");
    }

    public static ShareAppDialog create() {
        return new ShareAppDialog();
    }

    public void show(Fragment fragment) {
        show(fragment.getChildFragmentManager(), null);
    }

    @Override
    protected ViewBinding getBinding() {
        return binding = DialogShareAppBinding.inflate(getLayoutInflater());
    }

    @Override
    protected MaterialAlertDialogBuilder getBuilder() {
        return new MaterialAlertDialogBuilder(requireActivity(), R.style.ThemeOverlay_WebHTV_LightDialog).setView(getBinding().getRoot());
    }

    @Override
    protected void initView() {
        binding.name.setText(getString(R.string.app_name));
        detect();
    }

    @Override
    protected void initEvent() {
        binding.url.setOnClickListener(this::copy);
    }

    private void detect() {
        updateDetecting(R.string.share_app_detecting_list);
        Task.execute(() -> {
            List<String> lines = fetchLines();
            if (destroyed || !isAdded()) return;
            if (lines.isEmpty()) {
                binding.getRoot().post(() -> showResult(false));
                return;
            }
            binding.getRoot().post(() -> updateDetecting(R.string.share_app_detecting_probe, lines.size()));
            String proxy = probe(lines);
            shareUrl = proxy != null ? proxy + getLatestApkUrl() : getLatestApkUrl();
            if (destroyed || !isAdded()) return;
            binding.getRoot().post(() -> showResult(proxy != null));
        });
    }

    private String probe(List<String> lines) {
        Map<String, Long> result = new ConcurrentHashMap<>();
        CountDownLatch latch = new CountDownLatch(lines.size());
        String apk = getLatestApkUrl();
        for (String line : lines) {
            Task.largeExecutor().execute(() -> {
                try {
                    long start = System.currentTimeMillis();
                    if (testUrl(line + apk)) result.put(line, System.currentTimeMillis() - start);
                } finally {
                    latch.countDown();
                }
            });
        }
        try {
            latch.await(DETECT_BUDGET, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        cancelProbe();
        return result.entrySet().stream().min(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
    }

    private List<String> fetchLines() {
        AtomicReference<List<String>> holder = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(LIST_SOURCES.length);
        AtomicBoolean found = new AtomicBoolean(false);
        for (String source : LIST_SOURCES) {
            Task.largeExecutor().execute(() -> {
                try {
                    if (found.get()) return;
                    List<String> parsed = parseLines(fetch(source));
                    if (!parsed.isEmpty() && holder.compareAndSet(null, parsed)) found.set(true);
                } catch (Exception ignored) {
                } finally {
                    latch.countDown();
                }
            });
        }
        try {
            latch.await(6000, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        List<String> lines = holder.get();
        return lines == null ? Collections.emptyList() : lines;
    }

    private String fetch(String url) {
        try (Response res = FETCH.newCall(new Request.Builder().url(url).addHeader("User-Agent", UA).build()).execute()) {
            return res.body() == null ? "" : res.body().string();
        } catch (Exception e) {
            return "";
        }
    }

    private List<String> parseLines(String content) {
        List<String> lines = new ArrayList<>();
        if (content == null || content.isEmpty()) return lines;
        Matcher matcher = PATTERN.matcher(content);
        while (matcher.find() && lines.size() < MAX_LINES) {
            String line = matcher.group(1);
            if (!line.endsWith("/")) line = line + "/";
            if (!line.contains("github.com") && !lines.contains(line)) lines.add(line);
        }
        return lines;
    }

    private void updateDetecting(int resId, Object... args) {
        if (binding == null || binding.detectingText == null) return;
        String text = getString(resId);
        if (args.length > 0) text = String.format(text, args);
        binding.detectingText.setText(text);
    }

    private boolean testUrl(String url) {
        Call call = PROBE.newCall(new Request.Builder().url(url).addHeader("User-Agent", UA).tag(TAG).build());
        try (Response res = call.execute()) {
            int code = res.code();
            if (code == 200) return true;
            if (code >= 300 && code < 400) return !res.header("Location", "").isEmpty();
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    private static void cancelProbe() {
        for (Call call : PROBE.dispatcher().queuedCalls()) call.cancel();
        for (Call call : PROBE.dispatcher().runningCalls()) call.cancel();
    }

    private void showResult(boolean accelerated) {
        if (!isAdded() || binding == null) return;
        binding.detecting.setVisibility(View.GONE);
        binding.status.setVisibility(View.VISIBLE);
        binding.status.setText(accelerated ? R.string.share_app_accelerated : R.string.share_app_fallback);
        binding.url.setText(shareUrl);
        binding.url.setVisibility(View.VISIBLE);
        binding.hint.setVisibility(View.VISIBLE);
        Bitmap qr = QRCode.getStandardBitmap(shareUrl, 480, 2);
        binding.qrcode.setImageBitmap(qr);
        binding.qrcode.setVisibility(View.VISIBLE);
    }

    private void copy(View view) {
        ClipboardManager clipboard = (ClipboardManager) requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) clipboard.setPrimaryClip(ClipData.newPlainText("url", shareUrl));
        Notify.show(R.string.setting_subscription_share_copied);
    }

    @Override
    public void onDestroyView() {
        destroyed = true;
        cancelProbe();
        super.onDestroyView();
    }
}
