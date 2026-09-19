package com.fongmi.android.tv.api;

import android.text.TextUtils;

import com.fongmi.android.tv.bean.CommunitySource;
import com.fongmi.android.tv.utils.GithubProxy;
import com.github.catvod.net.OkHttp;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 订阅源全网资源社区「一键提取」引擎（工作线程调用）。
 * <p>
 * 针对 GitHub 仓库：读取 README，只提取「TVBox配置」章节推荐的仓库自制配置——
 * README 中提到的仓库内文件（与 git/trees 文件列表求交集），保存镜像地址。「各路大佬配置」
 * 章节完全不提取（站外源国内普遍失效）。README 无命中时降级为全仓库 .json/.txt/.m3u 文件扫描。
 * 所有候选均经拉取验证：死源（DNS 失败/超时/404/HTML 错误页）与空源（无站点/频道）不提取。
 * api 与 raw 请求优先经 {@link GithubProxy} 国内镜像加速。
 * 分类判定与 {@link SourceScanner} 保持一致：含 sites=点播、含 lives=直播、
 * 含 urls（多仓订阅）=点播；均不含则判为直播（txt/m3u 频道列表）。
 */
public final class CommunityExtractor {

    private static final int CONCURRENCY = 12;
    private static final long FETCH_TIMEOUT = 8_000L;
    private static final String RAW_PREFIX = "https://raw.githubusercontent.com/";
    private static final Pattern URL_IN_TEXT = Pattern.compile("https?://[^\\s\"'`()<>\\[\\]{}|\\\\]+");
    // README 行内的订阅文件名（如 jsm.json、Bili1.txt）
    private static final Pattern FILE_NAME = Pattern.compile("[\\w.\\-\\u4e00-\\u9fa5]+\\.(json|txt|m3u)");
    // README 行内的订阅文件路径（可含目录，如 xiaosa/api.json）
    private static final Pattern FILE_PATH = Pattern.compile("[\\w.\\-/\\u4e00-\\u9fa5]+\\.(json|txt|m3u)");

    private CommunityExtractor() {
    }

    /**
     * 从 GitHub 仓库页地址解析 owner/repo，例如 https://github.com/qist/tvbox → qist/tvbox。
     */
    public static String[] parseRepo(String pageUrl) {
        try {
            String path = pageUrl.replaceAll("^https?://(www\\.)?github\\.com/", "").replaceAll("[/\\s]+$", "");
            String[] parts = path.split("/");
            if (parts.length < 2) return null;
            return new String[]{parts[0], parts[1]};
        } catch (Throwable e) {
            return null;
        }
    }

    /**
     * 提取进度回调（工作线程触发，实现方自行切主线程）。
     */
    public interface Progress {
        void onPhase(int phase);          // 1=列文件 2=读README 3=开始扫描文件

        void onFile(String name, int done, int total);
    }

    /**
     * 提取仓库推荐订阅源（工作线程调用）。
     * 拉取失败（连接失败/内容不合法）的条目直接剔除；README 中匹配到的文字说明随条目返回。
     */
    public static List<CommunitySource> extractFromRepo(String owner, String repo, Progress progress) {
        if (progress != null) progress.onPhase(1);
        List<String[]> files = listRepoFiles(owner, repo);
        if (files.isEmpty()) return Collections.emptyList();
        if (progress != null) progress.onPhase(2);
        String readme = fetchReadme(owner, repo);
        // 仓库镜像根 base（README 反推，如 https://qist.wyfc.qzz.io/）：自制配置保存镜像地址
        List<String> bases = collectProxies(readme, files);
        java.util.Map<String, String> descs = collectDescs(readme);
        // 候选：[url, 名称(可空), raw兜底地址(可空)]。README 命中自制配置 → 只取推荐；否则全量扫描
        List<String[]> candidates = pickRecommended(readme, files, bases);
        if (candidates == null) candidates = toCandidates(files);
        if (progress != null) progress.onPhase(3);
        List<CommunitySource> sources = classify(candidates, progress);
        for (CommunitySource source : sources) {
            String desc = descs.get(fileName(source.getUrl()).toLowerCase());
            if (desc != null) source.setDesc(desc);
        }
        markRecommended(sources);
        // 排序：带「推荐」标识的永远第一位，其余按站点/频道数量降序
        sources.sort((a, b) -> {
            if (a.isRecommended() != b.isRecommended()) return a.isRecommended() ? -1 : 1;
            return Integer.compare(b.getCount(), a.getCount());
        });
        return sources;
    }

    // 仓库主推标记：站点数最多的点播源（如 qist 仓库的 jsm.json 大合集）显示「推荐」角标
    private static void markRecommended(List<CommunitySource> sources) {
        CommunitySource best = null;
        for (CommunitySource source : sources) {
            if (source.getType() != 0 || source.getCount() <= 0) continue;
            if (best == null || source.getCount() > best.getCount()) best = source;
        }
        if (best != null) best.setRecommended(true);
    }

    // 返回 [path, rawUrl]
    private static List<String[]> listRepoFiles(String owner, String repo) {
        List<String[]> result = new ArrayList<>();
        try {
            String api = "https://api.github.com/repos/" + owner + "/" + repo + "/git/trees/HEAD?recursive=1";
            String json = GithubProxy.fetchJson(api);
            if (TextUtils.isEmpty(json)) json = OkHttp.string(api, FETCH_TIMEOUT);
            if (TextUtils.isEmpty(json)) return result;
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            if (!root.has("tree")) return result;
            root.getAsJsonArray("tree").forEach(el -> {
                if (!el.isJsonObject()) return;
                JsonObject node = el.getAsJsonObject();
                if (!"blob".equals(str(node, "type"))) return;
                String path = str(node, "path");
                if (TextUtils.isEmpty(path) || !isSourceFile(path)) return;
                String raw = "https://raw.githubusercontent.com/" + owner + "/" + repo + "/HEAD/" + path;
                result.add(new String[]{path, raw});
            });
        } catch (Throwable ignored) {
        }
        return result;
    }

    // 拉取仓库根 README.md 原文（失败返回 ""）。README.md 不在候选文件列表里，需直拼地址。
    private static String fetchReadme(String owner, String repo) {
        try {
            String raw = "https://raw.githubusercontent.com/" + owner + "/" + repo + "/HEAD/README.md";
            String readme = OkHttp.string(GithubProxy.accelerate(raw), FETCH_TIMEOUT);
            return readme == null ? "" : readme;
        } catch (Throwable ignored) {
        }
        return "";
    }

    // ===== README 推荐解析 =====

    /**
     * 只提取 README「TVBox配置」章节推荐的仓库自制配置：README 提到的文件名/路径与
     * 仓库文件列表求交集，保存镜像地址（base+相对路径，如 https://qist.wyfc.qzz.io/0707.json），
     * 无镜像时回退 raw。「各路大佬配置」章节完全不提取（站外源国内普遍失效）。
     * README 无命中返回 null（由调用方降级为全仓库扫描）。
     */
    private static List<String[]> pickRecommended(String readme, List<String[]> files, List<String> bases) {
        if (TextUtils.isEmpty(readme)) return null;
        List<String[]> result = new ArrayList<>();
        // 仓库自制配置：README 提到的文件名/路径与仓库文件列表求交集，保存镜像地址
        try {
            Matcher m = FILE_PATH.matcher(readme);
            while (m.find()) {
                String want = m.group().toLowerCase();
                for (String[] f : files) {
                    String path = f[0].toLowerCase();
                    if (path.equals(want) || path.endsWith("/" + want)) {
                        if (containsUrl(result, f[1])) break;
                        String mirror = bases.isEmpty() ? null : bases.get(0) + f[0];
                        result.add(new String[]{mirror != null ? mirror : f[1], null, f[1]});
                        break;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return result.isEmpty() ? null : result;
    }

    private static boolean containsUrl(List<String[]> candidates, String url) {
        for (String[] c : candidates) if (c[0].equals(url)) return true;
        return false;
    }

    private static List<String[]> toCandidates(List<String[]> files) {
        List<String[]> result = new ArrayList<>();
        for (String[] f : files) result.add(new String[]{f[1], null});
        return result;
    }

    // 从 README 中反推仓库镜像根 base（如 https://qist.wyfc.qzz.io/）：
    // 取 README 里以订阅文件名结尾、且命中仓库文件列表的站外 URL，去掉尾部相对路径得到 base。
    // fetch 时拼 base + 相对路径（如 qist.wyfc.qzz.io/jsm.json），一发命中，避免拼出 404 垃圾地址。
    private static List<String> collectProxies(String readme, List<String[]> files) {
        List<String> bases = new ArrayList<>();
        try {
            if (TextUtils.isEmpty(readme)) return bases;
            Matcher m = URL_IN_TEXT.matcher(readme);
            while (m.find() && bases.size() < 4) {
                String u = m.group();
                if (u.contains("github.com") || u.contains("raw.githubusercontent")) continue;
                for (String[] f : files) {
                    String suffix = "/" + f[0];
                    if (u.regionMatches(true, u.length() - suffix.length(), suffix, 0, suffix.length())) {
                        String base = u.substring(0, u.length() - suffix.length() + 1);
                        if (!bases.contains(base)) bases.add(base);
                        break;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return bases;
    }

    // README 逐行按文件名匹配说明文字：返回 文件名小写(jsm.json) → 该行清洗后的描述（多行命中取最长）
    private static java.util.Map<String, String> collectDescs(String readme) {
        java.util.Map<String, String> descs = new java.util.HashMap<>();
        try {
            if (TextUtils.isEmpty(readme)) return descs;
            for (String line : readme.split("\n")) {
                String clean = cleanLine(line);
                if (TextUtils.isEmpty(clean)) continue;
                Matcher m = FILE_NAME.matcher(clean);
                while (m.find()) {
                    String file = m.group().toLowerCase();
                    String desc = clean.replace(m.group(), "").trim();
                    desc = desc.replaceAll("^\\s*[（(]\\s*\\d+\\s*[）)]", "");
                    desc = desc.replaceAll("^[:：\\-—|·\\s]+", "").replaceAll("[:：\\-—|·\\s]+$", "").trim();
                    if (desc.length() < 4) continue;
                    if (desc.length() > 120) desc = desc.substring(0, 120);
                    String old = descs.get(file);
                    if (old == null || desc.length() > old.length()) descs.put(file, desc);
                }
            }
        } catch (Throwable ignored) {
        }
        return descs;
    }

    // 去掉 markdown 噪音：图片/链接语法、加粗、反引号、表格竖线、行内 URL
    private static String cleanLine(String line) {
        String text = line.replaceAll("!\\[[^]]*]\\([^)]*\\)", "")
                .replaceAll("\\[([^]]*)]\\([^)]*\\)", "$1")
                .replaceAll("[*`]", "")
                .replace("|", " ")
                .replaceAll("<https?://[^>]*>", "")
                .replaceAll("https?://[^\\s\"'<>()\\[\\]{}|\\\\]+", "")
                .replaceAll("\\s{2,}", " ")
                .trim();
        return text;
    }

    // 从 URL 取文件名：…/FTY/Bili1.json?x → Bili1.json
    private static String fileName(String url) {
        String path = url.replaceAll("[?#].*$", "");
        return path.substring(path.lastIndexOf('/') + 1);
    }

    private static boolean isSourceFile(String path) {
        String lower = path.toLowerCase();
        if (!lower.endsWith(".json") && !lower.endsWith(".txt") && !lower.endsWith(".m3u")) return false;
        String name = lower.substring(lower.lastIndexOf('/') + 1);
        // package.json / tsconfig 等非 TVBox 结构文件直接排除
        return !name.equals("package.json") && !name.equals("package-lock.json") && !name.equals("tsconfig.json");
    }

    public static List<CommunitySource> classify(List<String[]> candidates, Progress progress) {
        List<CommunitySource> result = Collections.synchronizedList(new ArrayList<>());
        if (candidates.isEmpty()) return result;
        AtomicInteger done = new AtomicInteger();
        // 预热 gh-proxy 镜像前缀（一次探测，raw 兜底时直接拼接，不再每文件重复 test）
        String ghPrefix = null;
        for (String[] c : candidates) {
            if (c.length > 2 && c[2] != null && c[2].startsWith(RAW_PREFIX)) {
                ghPrefix = GithubProxy.warmFile(c[2]);
                break;
            }
        }
        ExecutorService pool = Executors.newFixedThreadPool(CONCURRENCY);
        CountDownLatch latch = new CountDownLatch(candidates.size());
        for (String[] candidate : candidates) {
            String url = candidate[0];
            String name = candidate[1];
            String fallback = candidate.length > 2 ? candidate[2] : null;
            String prefix = ghPrefix;
            pool.execute(() -> {
                try {
                    // 拉取验证：死源（DNS 失败/超时/404/HTML 错误页）与空源（拉到内容但无站点/频道）均不提取
                    CommunitySource source = probe(url, name, fallback, prefix);
                    if (source != null) result.add(source);
                } catch (Throwable ignored) {
                } finally {
                    if (progress != null) progress.onFile(fileName(url), done.incrementAndGet(), candidates.size());
                    latch.countDown();
                }
            });
        }
        try {
            latch.await(CONCURRENCY * FETCH_TIMEOUT / 1000L + 60L, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
        }
        pool.shutdownNow();
        return result;
    }

    // 拉取顺序：① 保存地址本身（镜像 base 或大佬原链）→ ② raw 兜底（gh-proxy 镜像直链 → 直连）。
    // 无论走哪条，保存/添加的 URL 始终是 candidate[0]。均失败返回 null
    private static CommunitySource probe(String url, String name, String fallback, String ghPrefix) {
        String content = get(url);
        if (content == null && fallback != null) {
            content = ghPrefix != null ? get(ghPrefix + fallback) : null;
            if (content == null) content = get(fallback);
        }
        if (content == null) return null;
        CommunitySource source = parse(url, content);
        // 空源判定：拉到合法结构但站点/频道数为 0（如 "sites":[]），启用后必为空，不提取
        if (source.getCount() <= 0) return null;
        if (!TextUtils.isEmpty(name)) source.setName(name);
        return source;
    }

    private static String get(String url) {
        try {
            String content = OkHttp.string(url, FETCH_TIMEOUT);
            return looksLikeSource(content) ? content : null;
        } catch (Throwable ignored) {
        }
        return null;
    }

    // 内容合法性：JSON 需含 sites/lives/urls 之一；txt/m3u 需含 #EXTM3U 或 逗号分隔频道行
    private static boolean looksLikeSource(String content) {
        if (TextUtils.isEmpty(content)) return false;
        String head = content.length() > 4096 ? content.substring(0, 4096) : content;
        String trimmed = head.trim();
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            return head.contains("\"sites\"") || head.contains("\"lives\"") || head.contains("\"urls\"");
        }
        return head.contains("#EXTM3U") || head.contains("#EXTINF") || head.contains(",");
    }

    // 按内容字段分类：sites→点播，urls→点播（多仓），lives→直播，其余（txt/m3u）→直播
    private static CommunitySource parse(String url, String content) {
        int type = 1;
        int count = 0;
        String name = null;
        String trimmed = content.trim();
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            type = 0;
            try {
                JsonObject obj = JsonParser.parseString(content).getAsJsonObject();
                if (obj.has("sites") && obj.getAsJsonArray("sites").size() > 0) {
                    count = obj.getAsJsonArray("sites").size();
                } else if (obj.has("urls") && obj.getAsJsonArray("urls").size() > 0) {
                    count = obj.getAsJsonArray("urls").size();
                } else if (obj.has("lives") && obj.getAsJsonArray("lives").size() > 0) {
                    type = 1;
                    count = obj.getAsJsonArray("lives").size();
                }
                name = obj.has("name") ? str(obj, "name") : null;
            } catch (Throwable e) {
                // 解析失败但内容像 JSON 订阅：保守按点播处理
                type = content.contains("\"lives\"") && !content.contains("\"sites\"") ? 1 : 0;
            }
        } else {
            type = 1;
            String[] lines = content.split("\n");
            for (String line : lines) if (line.contains(",") && !line.startsWith("#")) count++;
            if (count == 0) count = lines.length;
        }
        if (TextUtils.isEmpty(name)) name = guessName(url);
        return new CommunitySource(name, url, type, count, true);
    }

    // 从路径猜名称：目录/文件名，如 FTY/Bili1.json → FTY/Bili1
    private static String guessName(String url) {
        try {
            String path = url.replaceAll("^https?://[^/]+/", "").replaceAll("[?#].*$", "");
            int q = path.indexOf('/');
            String name = q == -1 ? path : path.substring(0, q) + "/" + path.substring(q + 1);
            name = name.replaceAll("(?i)\\.(json|txt|m3u)$", "");
            return TextUtils.isEmpty(name) ? url : name;
        } catch (Throwable e) {
            return url;
        }
    }

    private static String str(JsonObject obj, String key) {
        return obj.has(key) && obj.get(key).isJsonPrimitive() ? obj.get(key).getAsString() : null;
    }
}
