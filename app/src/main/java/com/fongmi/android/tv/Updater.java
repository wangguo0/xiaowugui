package com.fongmi.android.tv;

import android.os.SystemClock;
import android.text.TextUtils;
import android.view.View;

import androidx.fragment.app.FragmentActivity;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleEventObserver;

import com.fongmi.android.tv.bean.Update;
import com.fongmi.android.tv.impl.UpdateListener;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.ui.dialog.DownloadLineDialog;
import com.fongmi.android.tv.ui.dialog.UpdateDialog;
import com.fongmi.android.tv.utils.Download;
import com.fongmi.android.tv.utils.FileUtil;
import com.fongmi.android.tv.utils.ForcePolicy;
import com.fongmi.android.tv.utils.Github;
import com.fongmi.android.tv.utils.GithubProxy;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.utils.Task;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Path;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.lang.ref.WeakReference;
import java.security.MessageDigest;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Updater implements Download.Callback, UpdateListener {

    private static final String DEFAULT_RELEASE_NOTES = "手动触发 GitHub Actions 构建发布。";
    private static final String SOURCE_GITHUB = "github";
    private static final long UPDATE_CHECK_TIMEOUT_MS = TimeUnit.SECONDS.toMillis(10);
    private static final long GITHUB_REQUEST_TIMEOUT_MS = TimeUnit.SECONDS.toMillis(4);
    // 直连 API 失败后的降级预算：动态镜像（最多 24 条）+ 直连共 25 源并发拉清单取最新
    private static final long FALLBACK_COLLECT_MS = TimeUnit.SECONDS.toMillis(6);
    // tag 形如「小乌龟1.2-202609201200」：版本号 + 可选构建时间戳
    private static final Pattern TAG_VERSION = Pattern.compile("(\\d+(?:\\.\\d+)+)");
    private static final Pattern TAG_TIME = Pattern.compile("(\\d{12})");
    private static final Map<String, String> GITHUB_API_HEADERS = Map.of("Accept", "application/vnd.github+json", "X-GitHub-Api-Version", "2022-11-28");
    private static final Map<String, String> GITHUB_ASSET_HEADERS = Map.of("Accept", "application/octet-stream", "X-GitHub-Api-Version", "2022-11-28");
    private static final Updater INSTANCE = new Updater();

    private final LifecycleEventObserver lifecycleObserver = (source, event) -> {
        if (!(source instanceof FragmentActivity)) return;
        FragmentActivity activity = (FragmentActivity) source;
        if (event == Lifecycle.Event.ON_DESTROY) unbind(activity);
    };

    private WeakReference<FragmentActivity> activityRef;
    private UpdateDialog dialog;
    private Download download;
    private Update stable;
    private Update beta;
    private Update selected;
    private boolean force;
    private boolean downloading;
    private boolean canceled;
    private volatile boolean launchChecked;
    private volatile boolean launchChecking;
    private volatile long lastLaunchCheckAt;
    private boolean forceMode;
    private String forceMsg = "";
    private GithubProxy.Line currentLine;
    private boolean retriedDirect;
    private int lastProgress = -1;
    private long lastBytes;
    private long lastTotal;
    private long lastSpeed;
    private long lastElapsed;

    private Updater() {
    }

    public static Updater create() {
        return INSTANCE;
    }

    private File getFile() {
        return Path.cache("update.apk");
    }

    // 发布产物文件名模式段用拼音（mobile→shouji、leanback→dianshi），与 CI 上传的 Release 附件名保持一致
    private String getName() {
        String mode = BuildConfig.FLAVOR_mode;
        String name = "mobile".equals(mode) ? "shouji" : "leanback".equals(mode) ? "dianshi" : mode;
        return name + "-" + BuildConfig.FLAVOR_abi;
    }

    public Updater force() {
        force = true;
        Notify.show(R.string.update_check);
        Setting.putUpdate(true);
        return this;
    }

    public void start(FragmentActivity activity) {
        bind(activity);
        boolean forceCheck = force;
        force = false;
        if (downloading) {
            restoreDialog(activity);
            return;
        }
        if (!Setting.getUpdate()) return;
        Task.execute(() -> doInBackground(activity, forceCheck, false));
    }

    // 冷启动静默检查：仅命中强制更新（发布清单 force 或远端最低版本策略）才弹窗，否则完全不打扰
    // 检查失败（镜像+直连全挂/超时）不锁定状态，回前台经 resume() 自动重试（节流 30 秒）
    public void checkOnLaunch(FragmentActivity activity) {
        if (launchChecked || launchChecking) return;
        if (downloading || dialog != null) return;
        long now = SystemClock.elapsedRealtime();
        if (lastLaunchCheckAt > 0 && now - lastLaunchCheckAt < TimeUnit.SECONDS.toMillis(30)) return;
        lastLaunchCheckAt = now;
        launchChecking = true;
        Task.execute(() -> {
            try {
                doInBackground(activity, false, true);
            } finally {
                launchChecking = false;
            }
        });
    }

    public void resume(FragmentActivity activity) {
        bind(activity);
        if (downloading) {
            restoreDialog(activity);
            return;
        }
        if (forceMode && selected != null && selected.hasUpdate()) {
            if (dialog == null || !dialog.isAdded()) show(activity);
            return;
        }
        // 启动检查曾失败：回前台自动重试（内部含状态判断与 30 秒节流）
        if (!launchChecked) checkOnLaunch(activity);
    }

    private void doInBackground(FragmentActivity activity, boolean forceCheck, boolean launch) {
        long deadline = SystemClock.elapsedRealtime() + UPDATE_CHECK_TIMEOUT_MS;
        Future<Update> stableFuture = Task.executor().submit(() -> getUpdate(Update.CHANNEL_STABLE));
        Future<Update> betaFuture = Task.executor().submit(() -> getUpdate(Update.CHANNEL_BETA));
        Future<ForcePolicy> policyFuture = Task.executor().submit(ForcePolicy::fetch);
        stable = awaitUpdate(stableFuture, Update.CHANNEL_STABLE, deadline);
        beta = awaitUpdate(betaFuture, Update.CHANNEL_BETA, deadline);
        applyForce(awaitPolicy(policyFuture, deadline));
        if (launch) {
            // 拿到任一渠道清单即视为检查成功并锁定；全失败则留待回前台重试
            if (stable.hasManifest() || beta.hasManifest()) launchChecked = true;
            if (!forceMode || selected == null || !selected.hasUpdate()) return;
            // 弹窗被权限框/页面保存状态挡住时，回前台由 resume() 的强制态补弹
            App.post(() -> show(activity));
            return;
        }
        if (!stable.hasUpdate() && !beta.hasUpdate()) {
            if (forceCheck && (stable.hasManifest() || beta.hasManifest())) {
                selected = stable;
                App.post(() -> show(activity));
                return;
            }
            if (forceCheck) App.post(() -> Notify.show(hasErrorOnly() ? R.string.update_failed : R.string.update_latest));
            return;
        }
        if (!forceMode) selected = stable;
        App.post(() -> show(activity));
    }

    // 合并 A（发布清单 force 字段）+ B（远端 force.json 最低版本策略）判定强制更新态
    private void applyForce(ForcePolicy policy) {
        forceMode = false;
        forceMsg = "";
        Update target = null;
        if (stable.isForceUpdate()) target = stable;
        else if (beta.isForceUpdate()) target = beta;
        if (target != null) {
            forceMode = true;
            forceMsg = TextUtils.isEmpty(target.forceMsg) ? policy.message : target.forceMsg;
            selected = target;
            return;
        }
        if (!policy.force) return;
        target = stable.hasUpdate() ? stable : (beta.hasUpdate() ? beta : null);
        // 策略要求强制但无可安装目标（清单缺失/网络异常）→ 无法执行，不弹窗卡死用户
        if (target == null) return;
        forceMode = true;
        forceMsg = policy.message;
        selected = target;
    }

    private ForcePolicy awaitPolicy(Future<ForcePolicy> future, long deadline) {
        try {
            long remaining = deadline - SystemClock.elapsedRealtime();
            if (remaining <= 0) return ForcePolicy.none();
            return future.get(remaining, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            future.cancel(true);
            return ForcePolicy.none();
        }
    }

    private Update awaitUpdate(Future<Update> future, String channel, long deadline) {
        try {
            long remaining = deadline - SystemClock.elapsedRealtime();
            if (remaining <= 0) throw new TimeoutException("Update check timed out");
            return future.get(remaining, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            future.cancel(true);
            e.printStackTrace();
            Update update = Update.empty(channel);
            update.error = e.getMessage();
            return update;
        }
    }

    private Update getUpdate(String channel) {
        return Update.CHANNEL_BETA.equals(channel) ? getGithubBetaUpdate(channel) : getGithubStableUpdate(channel);
    }

    private Update getGithubStableUpdate(String channel) {
        try {
            JSONObject release = new JSONObject(fetchGithub(Github.getLatestReleaseApi(), Update.CHANNEL_STABLE));
            return readGithubReleaseUpdate(channel, release);
        } catch (Exception e) {
            e.printStackTrace();
            return Update.empty(channel);
        }
    }

    private Update getGithubBetaUpdate(String channel) {
        String manifestName = getManifestName(channel);
        try {
            JSONArray releases = new JSONArray(fetchGithub(Github.getReleasesApi(), channel));
            for (int i = 0; i < releases.length(); i++) {
                JSONObject release = releases.optJSONObject(i);
                if (release == null || !isBetaRelease(release)) continue;
                if (findAsset(release.optJSONArray("assets"), manifestName) == null) continue;
                return readGithubReleaseUpdate(channel, release);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return Update.empty(channel);
    }

    private boolean isBetaRelease(JSONObject release) {
        String tag = release.optString("tag_name");
        return release.optBoolean("prerelease") || tag.contains("-beta-");
    }

    private JSONObject findAsset(JSONArray assets, String name) {
        if (assets == null) return null;
        for (int i = 0; i < assets.length(); i++) {
            JSONObject asset = assets.optJSONObject(i);
            if (asset == null || !name.equals(asset.optString("name"))) continue;
            return asset;
        }
        return null;
    }

    private Update readGithubReleaseUpdate(String channel, JSONObject release) {
        JSONObject asset = findAsset(release.optJSONArray("assets"), getManifestName(channel));
        if (asset == null) return Update.empty(channel);
        // 优先用资源直链（github.com 文件下载，镜像可代理），缺失时回退 API 地址
        String url = asset.optString("browser_download_url");
        boolean api = TextUtils.isEmpty(url);
        if (api) url = Github.getReleaseAssetApi(asset.optLong("id"));
        return readUpdate(channel, url, SOURCE_GITHUB, api ? GITHUB_ASSET_HEADERS : null, release.optString("body"));
    }

    // 版本信息 API（latest/releases 移动指针）：直连优先（GitHub API 永不缓存，响应仅 2KB），
    // 直连失败才降级为多源并发拉清单取最新——陈旧镜像缓存不可能在比较中胜出
    private String fetchGithub(String url, String channel) throws Exception {
        try {
            String body = OkHttp.string(url, GITHUB_API_HEADERS, GITHUB_REQUEST_TIMEOUT_MS);
            if (isReleaseJson(body)) return body;
        } catch (Exception ignored) {
        }
        GithubProxy.clearJsonCache();
        String best = pickLatest(GithubProxy.fetchJsonAll(url, FALLBACK_COLLECT_MS), channel);
        if (best == null) throw new IllegalStateException("Update check failed: " + url);
        return best;
    }

    // tag 钉死的 API 地址（内容不可变，镜像缓存无害）：镜像优先加速，失败回退直连
    private String fetchGithubPinned(String url) throws Exception {
        String body = GithubProxy.fetchJson(url);
        if (isReleaseJson(body)) return body;
        GithubProxy.clearJsonCache();
        return OkHttp.string(url, GITHUB_API_HEADERS, GITHUB_REQUEST_TIMEOUT_MS);
    }

    private boolean isReleaseJson(String body) {
        return body != null && body.contains("\"assets\"");
    }

    // 多源取最新：比较 tag 版本号 → 平局优先直连份 → 再比 tag 内构建时间戳；返回胜出源的完整响应体
    private String pickLatest(List<GithubProxy.Source> sources, String channel) {
        String bestBody = null;
        String bestTag = null;
        boolean bestDirect = false;
        for (GithubProxy.Source source : sources) {
            if (!isReleaseJson(source.body)) continue;
            String tag = candidateTag(source.body, channel);
            if (TextUtils.isEmpty(tag)) continue;
            if (bestTag == null || compareTag(tag, bestTag, source.direct, bestDirect) > 0) {
                bestBody = source.body;
                bestTag = tag;
                bestDirect = source.direct;
            }
        }
        return bestBody;
    }

    // 从 API 响应提取候选发布 tag：stable 为单对象；beta 从数组取首个带清单的预发布
    private String candidateTag(String body, String channel) {
        try {
            String text = body.trim();
            if (text.startsWith("[")) {
                JSONArray releases = new JSONArray(text);
                String manifestName = getManifestName(channel);
                for (int i = 0; i < releases.length(); i++) {
                    JSONObject release = releases.optJSONObject(i);
                    if (release == null || !isBetaRelease(release)) continue;
                    if (findAsset(release.optJSONArray("assets"), manifestName) == null) continue;
                    return release.optString("tag_name");
                }
                return null;
            }
            return new JSONObject(text).optString("tag_name");
        } catch (Exception e) {
            return null;
        }
    }

    private int compareTag(String a, String b, boolean aDirect, boolean bDirect) {
        int c = compareVersion(tagVersion(a), tagVersion(b));
        if (c != 0) return c;
        if (aDirect != bDirect) return aDirect ? 1 : -1;
        return tagTime(a).compareTo(tagTime(b));
    }

    private String tagVersion(String tag) {
        Matcher matcher = TAG_VERSION.matcher(tag);
        return matcher.find() ? matcher.group(1) : "";
    }

    private String tagTime(String tag) {
        Matcher matcher = TAG_TIME.matcher(tag);
        return matcher.find() ? matcher.group(1) : "";
    }

    private int compareVersion(String a, String b) {
        if (a.isEmpty() || b.isEmpty()) return 0;
        String[] pa = a.split("\\.");
        String[] pb = b.split("\\.");
        for (int i = 0; i < Math.max(pa.length, pb.length); i++) {
            long x = i < pa.length ? parseSegment(pa[i]) : 0;
            long y = i < pb.length ? parseSegment(pb[i]) : 0;
            if (x != y) return x > y ? 1 : -1;
        }
        return 0;
    }

    private long parseSegment(String value) {
        try {
            return Long.parseLong(value);
        } catch (Exception e) {
            return 0;
        }
    }

    private Update readUpdate(String channel, String manifestUrl, String source, Map<String, String> headers, String fallbackNotes) {
        Update update = Update.empty(channel);
        try {
            String text;
            if (headers == null) {
                // 文件直链清单：先试镜像加速，失败回退直连
                text = GithubProxy.fetchJson(manifestUrl);
                if (TextUtils.isEmpty(text)) text = OkHttp.string(manifestUrl, GITHUB_REQUEST_TIMEOUT_MS);
            } else {
                text = OkHttp.string(manifestUrl, headers, GITHUB_REQUEST_TIMEOUT_MS);
            }
            if (TextUtils.isEmpty(text)) throw new IllegalStateException("Empty update manifest: " + manifestUrl);
            JSONObject object = new JSONObject(text);
            update.name = object.optString("name");
            update.desc = normalizeText(object.optString("desc"));
            update.notes = normalizeText(object.optString("notes"));
            update.channel = object.optString("channel", channel);
            update.code = object.optInt("code");
            update.apk = object.optString("apk");
            update.size = object.optLong("size");
            update.sha256 = object.optString("sha256");
            update.force = object.optBoolean("force");
            update.forceMsg = normalizeText(object.optString("forceMsg"));
            update.apkUrl = getApkUrl(update, source);
            // 清单严格校验：apkUrl/size/sha256/code 缺任一即视为无效数据整体废弃，
            // 绝不允许带空字段进入下载流程（否则下载后的大小/SHA 校验会被空值整体跳过，旧包可蒙混过关）
            if (TextUtils.isEmpty(update.apkUrl) || update.size <= 0 || update.sha256.length() != 64 || update.code <= 0)
                throw new IllegalStateException("Invalid update manifest: " + manifestUrl);
            if (isDefaultReleaseNotes(update.notes)) update.notes = "";
            if (TextUtils.isEmpty(update.notes) && TextUtils.isEmpty(update.desc)) {
                String notes = TextUtils.isEmpty(fallbackNotes) ? getReleaseNotes(update.name) : fallbackNotes;
                if (!TextUtils.isEmpty(notes)) update.notes = normalizeText(notes);
            }
        } catch (Exception e) {
            e.printStackTrace();
            Update failed = Update.empty(channel);
            failed.error = e.getMessage();
            return failed;
        }
        return update;
    }

    private String normalizeText(String text) {
        if (TextUtils.isEmpty(text)) return "";
        return text
                .replace("\\r\\n", "\n")
                .replace("\\n", "\n")
                .replace("\\r", "\n")
                .replace("\\t", "\t")
                .replace("\\\"", "\"")
                .replace("\\'", "'");
    }

    private String getManifestName(String channel) {
        return getAssetName(channel, "json");
    }

    private String getDefaultApkName(String channel) {
        return getAssetName(channel, "apk");
    }

    private String getAssetName(String channel, String ext) {
        String suffix = Update.CHANNEL_BETA.equals(channel) ? "-beta" : "";
        return getName() + suffix + "." + ext;
    }

    private String getApkUrl(Update update, String source) {
        String apk = TextUtils.isEmpty(update.apk) ? getDefaultApkName(update.channel) : update.apk;
        if (apk.startsWith("http://") || apk.startsWith("https://")) return apk;
        if (SOURCE_GITHUB.equals(source) && !TextUtils.isEmpty(update.name)) return Github.getGithubReleaseAsset(update.name, getFileName(apk, update.channel));
        return Github.getGithubLatestAsset(getFileName(apk, update.channel));
    }

    private String getFileName(String value, String channel) {
        int query = value.indexOf('?');
        if (query >= 0) value = value.substring(0, query);
        int slash = value.lastIndexOf('/');
        String name = slash >= 0 ? value.substring(slash + 1) : value;
        return TextUtils.isEmpty(name) ? getDefaultApkName(channel) : name;
    }

    private boolean isDefaultReleaseNotes(String notes) {
        return !TextUtils.isEmpty(notes) && DEFAULT_RELEASE_NOTES.equals(notes.trim());
    }

    private String getReleaseNotes(String tag) {
        if (TextUtils.isEmpty(tag)) return "";
        String notes = readReleaseNotes(tag);
        if (!TextUtils.isEmpty(notes) || tag.startsWith("v")) return notes;
        return readReleaseNotes("v" + tag);
    }

    private String readReleaseNotes(String tag) {
        try {
            // release notes 按 tag 钉死取，内容不可变：优先镜像加速线路，全部失败才回退直连
            return new JSONObject(fetchGithubPinned(Github.getReleaseApi(tag))).optString("body");
        } catch (Exception ignored) {
            return "";
        }
    }

    private boolean hasErrorOnly() {
        return !stable.hasManifest() && !beta.hasManifest() && (!TextUtils.isEmpty(stable.error) || !TextUtils.isEmpty(beta.error));
    }

    private boolean show(FragmentActivity activity) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return false;
        if (activity.getSupportFragmentManager().isStateSaved()) return false;
        bind(activity);
        dismiss();
        // 旋转后 FragmentManager 会自动恢复一个无状态的旧实例，统一移除后以完整状态重建
        for (androidx.fragment.app.Fragment fragment : activity.getSupportFragmentManager().getFragments()) {
            if (fragment instanceof UpdateDialog) ((UpdateDialog) fragment).dismissAllowingStateLoss();
        }
        Notify.dismissToast();
        String channel = selected == null ? Update.CHANNEL_STABLE : selected.channel;
        dialog = UpdateDialog.create().stable(stable).beta(beta).selected(channel).force(forceMode, forceMsg).listener(this).show(activity);
        return true;
    }

    @Override
    public void onConfirm(View view) {
        if (selected == null || !selected.hasUpdate()) {
            Notify.show(R.string.update_latest);
            return;
        }
        view.setEnabled(false);
        downloading = true;
        canceled = false;
        retriedDirect = false;
        resetProgress();
        Path.clear(getFile());
        setDialogProgress(0, 0, selected.size, 0, 0);
        String url = selected.apkUrl;
        long size = selected.size;
        FragmentActivity act = activityRef == null ? null : activityRef.get();
        // 下载前核对：24 镜像+直连并发 Range 探测（每线仅 2KB），总大小与 PK 文件头都对上的线路才有资格进列表；
        // 用户在弹窗手点线路或选「自动选择」（最快），从源头杜绝下载到陈旧/残缺内容
        Task.execute(() -> {
            if (canceled || !downloading) return;
            List<GithubProxy.Line> lines = GithubProxy.probeVerified(url, size);
            if (canceled || !downloading) return;
            if (lines.isEmpty()) {
                App.post(() -> {
                    Notify.show(R.string.update_line_empty);
                    startDownload(url, null);
                });
                return;
            }
            App.post(() -> {
                if (act == null || act.isFinishing() || act.isDestroyed()) {
                    startDownload(GithubProxy.build(lines.get(0), url), lines.get(0));
                    return;
                }
                DownloadLineDialog.show(act, lines, line -> startDownload(GithubProxy.build(line, url), line), () -> {
                    // 用户取消选线路：中止本次更新下载
                    canceled = true;
                    downloading = false;
                    resetProgress();
                    Path.clear(getFile());
                    dismiss();
                });
            });
        });
    }

    private void startDownload(String url, GithubProxy.Line line) {
        currentLine = line;
        // 裸 OkHttpClient 下载：绕开爬虫网络栈的全局缓存/代理，杜绝镜像 302 重定向命中旧缓存
        download = Download.create(url, getFile()).tag(url).client(GithubProxy.downloadClient());
        download.start(this);
    }

    @Override
    public void onCancel(View view) {
        // 强制更新态：拒绝取消（下载中不可中断、弹窗不可关闭）
        if (forceMode) return;
        if (downloading) {
            canceled = true;
            downloading = false;
            if (download != null) download.cancel();
            download = null;
            resetProgress();
            Notify.show(R.string.update_canceled);
            dismiss();
            return;
        }
        Setting.putUpdate(false);
        if (download != null) download.cancel();
        dismiss();
    }

    @Override
    public void onClose() {
        dialog = null;
    }

    @Override
    public void onChannel(String channel) {
        Setting.putUpdateChannel(channel);
        selected = Update.CHANNEL_BETA.equals(channel) ? beta : stable;
    }

    private void dismiss() {
        try {
            if (dialog != null) dialog.dismissAllowingStateLoss();
        } catch (Exception ignored) {
        } finally {
            dialog = null;
        }
    }

    @Override
    public void progress(int progress) {
        setDialogProgress(progress, 0, 0, 0, 0);
    }

    @Override
    public void progress(int progress, long bytes, long total, long speed, long elapsed) {
        setDialogProgress(progress, bytes, total, speed, elapsed);
    }

    private void setDialogProgress(int progress, long bytes, long total, long speed, long elapsed) {
        if (canceled || !downloading) return;
        long manifestSize = selected == null ? 0 : selected.size;
        if (total <= 0 && manifestSize > 0) total = manifestSize;
        if (progress < 0 && total > 0 && bytes > 0) progress = (int) (bytes * 100.0 / total);
        lastProgress = progress;
        lastBytes = bytes;
        lastTotal = total;
        lastSpeed = speed;
        lastElapsed = elapsed;
        if (dialog == null) return;
        if (!dialog.setProgress(progress, bytes, total, speed, elapsed)) dialog = null;
    }

    @Override
    public void error(String msg) {
        if (canceled) return;
        download = null;
        downloading = false;
        resetProgress();
        Notify.show(msg);
        // 强制更新态下载失败：保留弹窗供重试，不关闭
        if (forceMode && dialog != null && dialog.reset()) return;
        dismiss();
    }

    @Override
    public void success(File file) {
        if (canceled) return;
        download = null;
        Update target = selected;
        Task.execute(() -> {
            String error = validate(file, target);
            App.post(() -> {
                if (canceled) return;
                if (!TextUtils.isEmpty(error)) {
                    Path.clear(file);
                    // 校验不过且当前走的是镜像线路：自动改用官方直连重试一次（罕见兜底，防传输中途坏数据）
                    if (!retriedDirect && currentLine != null && target != null && !TextUtils.isEmpty(target.apkUrl)) {
                        retriedDirect = true;
                        Notify.show(R.string.update_retry_direct);
                        startDownload(target.apkUrl, null);
                        return;
                    }
                    downloading = false;
                    resetProgress();
                    Notify.show(error);
                    dismiss();
                    return;
                }
                downloading = false;
                resetProgress();
                FileUtil.openFile(file);
                dismiss();
            });
        });
    }

    private void restoreDialog(FragmentActivity activity) {
        if (!downloading || selected == null) return;
        show(activity);
        setDialogProgress(lastProgress, lastBytes, lastTotal, lastSpeed, lastElapsed);
    }

    private String validate(File file, Update update) {
        if (file == null || !file.exists() || file.length() <= 0) return ResUtil.getString(R.string.update_download_invalid);
        if (update != null && update.size > 0 && file.length() != update.size) return ResUtil.getString(R.string.update_download_incomplete);
        if (update != null && !TextUtils.isEmpty(update.sha256) && !update.sha256.equalsIgnoreCase(sha256(file))) return ResUtil.getString(R.string.update_download_checksum);
        android.content.pm.PackageInfo info = App.get().getPackageManager().getPackageArchiveInfo(file.getAbsolutePath(), 0);
        if (info == null) return ResUtil.getString(R.string.update_download_invalid);
        // 版本核对（零流量，只读本地文件头）：APK 实际 versionCode 必须与清单一致，杜绝任何环节把旧包冒充新包递给安装器
        if (update != null && update.code > 0 && androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(info) != update.code)
            return ResUtil.getString(R.string.update_download_version);
        return "";
    }

    private String sha256(File file) {
        try (FileInputStream input = new FileInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[16384];
            int read;
            while ((read = input.read(buffer)) != -1) digest.update(buffer, 0, read);
            StringBuilder builder = new StringBuilder();
            for (byte value : digest.digest()) builder.append(String.format(Locale.ROOT, "%02x", value));
            return builder.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private void bind(FragmentActivity activity) {
        if (activity == null) return;
        FragmentActivity old = activityRef == null ? null : activityRef.get();
        if (old == activity) return;
        if (old != null) old.getLifecycle().removeObserver(lifecycleObserver);
        activityRef = new WeakReference<>(activity);
        activity.getLifecycle().addObserver(lifecycleObserver);
    }

    private void unbind(FragmentActivity activity) {
        FragmentActivity current = activityRef == null ? null : activityRef.get();
        if (current != activity) return;
        activity.getLifecycle().removeObserver(lifecycleObserver);
        activityRef = null;
        if (!downloading) dialog = null;
    }

    private void resetProgress() {
        lastProgress = -1;
        lastBytes = 0;
        lastTotal = 0;
        lastSpeed = 0;
        lastElapsed = 0;
    }
}
