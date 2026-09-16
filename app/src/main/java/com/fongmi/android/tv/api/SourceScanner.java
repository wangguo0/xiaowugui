package com.fongmi.android.tv.api;

import android.text.TextUtils;

import com.fongmi.android.tv.bean.Depot;
import com.fongmi.android.tv.utils.DiagLog;
import com.fongmi.android.tv.utils.Download;
import com.fongmi.android.tv.utils.UrlUtil;
import com.github.catvod.utils.Json;
import com.github.catvod.utils.Path;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.io.ByteArrayInputStream;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

/**
 * 源静态特征扫描器（不执行任何远程代码）。
 * <p>
 * 原理：旧版直接字节搜索 {@code Ljava/lang/System;->exit} 是错误写法——DEX 格式中
 * 类描述符与方法名分别存于 string_ids 区，该连续文本永远不会出现，特征形同虚设。
 * 现改为解析 DEX method_ids 表逐条核对「类描述符 + 方法名」（查表核对，非字符串碰运气），
 * 精确识别「自杀退出」（System.exit / Runtime.exit|halt / Process.killProcess）与
 * 「包名/环境检测」两类特征。正常源完全不受影响。
 * <p>
 * 命中「自杀」特征单独即判可疑（送沙箱复核）；「包名检测」需配合扫描不完整才送复核。
 * 复核超时 / 网络失败 / 不确定一律放行（fail-open）。
 */
public final class SourceScanner {

    // 「自杀退出」精确特征：解析 DEX method_ids 表，成对核对「类描述符 + 方法名」直引。
    // 正常 spider 可能合法引用 android/os/Process（myPid、setThreadPriority 等），
    // 故绝不可只按类描述符定罪，必须类+方法成对命中。
    private static final String DESC_SYSTEM = "Ljava/lang/System;";
    private static final String DESC_RUNTIME = "Ljava/lang/Runtime;";
    private static final String DESC_PROCESS = "Landroid/os/Process;";
    private static final String NAME_EXIT = "exit";
    private static final String NAME_HALT = "halt";
    private static final String NAME_KILL_PROCESS = "killProcess";
    // 反射调用的兜底明文特征：恶意 jar 经 getMethod("killProcess") 反射杀进程时，
    // 方法名字符串必然明文出现在 string_ids 区（实测判别式，正常 spider 绝不含此字面量）
    private static final byte[] SIG_KILL_PROCESS = "killProcess".getBytes(StandardCharsets.UTF_8);
    // 「包名/环境检测」特征（仅用于沙箱复核调度，不单独定罪）
    private static final String[] DETECT_NAMES = {
            "getPackageName", "getApplicationInfo", "getApplicationLabel", "getApplicationName",
            "getInstalledPackages", "getInstalledApplications", "getRunningAppProcesses", "getPackageInfo"};

    private static final int MAX_JAR_BYTES = 64 * 1024 * 1024;   // 单 jar 扫描上限
    private static final int MAX_DEX_BYTES = 48 * 1024 * 1024;   // 单条目解压上限
    private static final int MAX_JAR_COUNT = 16;                  // 单源 jar 扫描数量上限（多仓子源众多，放宽减少截断漏检）
    private static final int MAX_DEPOT = 3;                       // 多仓递归深度上限
    private static final long MEM_TTL = 10 * 60_000L;             // 内存缓存有效期

    private static final ConcurrentHashMap<String, long[]> MEM = new ConcurrentHashMap<>();

    private SourceScanner() {
    }

    public interface ScanListener {
        void onScan(int index, int total, String name);
    }

    /**
     * 扫描结果。
     * suspicious：是否需送沙箱复核；
     * complete：是否全部 jar 扫描成功（不完整的 PASS 不允许缓存，避免偶然结果锁死 7 天）；
     * killJars：命中「自杀」特征的 jar 地址，沙箱复核时优先探测引用它们的站点。
     */
    public static final class ScanResult {
        public final boolean suspicious;
        public final boolean complete;
        public final Set<String> killJars;

        ScanResult(boolean suspicious, boolean complete, Set<String> killJars) {
            this.suspicious = suspicious;
            this.complete = complete;
            this.killJars = killJars;
        }
    }

    public static ScanResult scan(String url, int type, String content) {
        return scan(url, type, content, null);
    }

    public static ScanResult scan(String url, int type, String content, ScanListener listener) {
        try {
            // LinkedHashSet 保证遍历顺序 = 集合出现顺序，扫描结果确定可复现（HashSet 顺序随机导致时拦时漏）
            SuspectBox box = new SuspectBox(false);
            Set<String> jars = collectJars(url, type, content, 0, new java.util.LinkedHashSet<>(), box);
            boolean kill = false;
            boolean detect = false;
            // 收集阶段已达上限：可能还有 jar 未被扫描，结果不完整
            boolean complete = jars.size() < MAX_JAR_COUNT;
            Set<String> killJars = new java.util.LinkedHashSet<>();
            int scanned = 0;
            int total = Math.min(jars.size(), MAX_JAR_COUNT);
            for (String jar : jars) {
                if (scanned >= MAX_JAR_COUNT) {
                    complete = false;
                    break;
                }
                if (listener != null) listener.onScan(scanned + 1, total, UrlUtil.getName(jar));
                long[] flags = scanJar(jar);
                if (flags == null) {
                    complete = false; // 下载失败：该 jar 未定性
                    continue;
                }
                scanned++;
                if (flags[0] == 1) {
                    kill = true;
                    killJars.add(jar);
                }
                detect |= flags[1] == 1;
            }
            // 「自杀退出」特征实锤单独即送沙箱复核（合并仓常无包名检测，只需一特征会漏）；
            // 「存在包名检测特征但扫描不完整」（可能漏扫 kill 特征）也送复核；
            // 多仓子源非 JSON（疑似加密载荷）也送复核
            boolean suspicious = kill || (!complete && detect) || box.suspect;
            DiagLog.log("scan", "url=%s jars=%d kill=%s detect=%s complete=%s structureSuspect=%s suspicious=%s", url, scanned, kill, detect, complete, box.suspect, suspicious);
            return new ScanResult(suspicious, complete, killJars);
        } catch (Throwable e) {
            // 扫描自身异常绝不拦截正常源；但结果不完整，PASS 不缓存
            return new ScanResult(false, false, Set.of());
        }
    }

    // 从配置 JSON 中收集全部 spider jar 路径（含多仓递归展开）
    public static Set<String> collectJars(String url, int type, String content) {
        return collectJars(url, type, content, 0, new java.util.LinkedHashSet<>(), new SuspectBox(false));
    }

    /**
     * 仅收集 + 标记「子仓结构异常」（含多仓递归展开），供入口拦截调用。
     * 若返回 true 表示该源存在「子仓返回非合法 JSON 的疑似加密/自解压载荷」，
     * 静态无法定性，需在添加/合并阶段拒绝或先行沙箱复核。
     */
    public static boolean structureSuspect(String url, int type, String content) {
        SuspectBox box = new SuspectBox(false);
        try {
            collectJars(url, type, content, 0, new java.util.LinkedHashSet<>(), box);
        } catch (Throwable ignored) {
        }
        return box.suspect;
    }

    // 收集 jar 的内存盒：将「子仓结构异常」标记传回调用方。
    // subSource 记录子仓地址，便于诊断日志定位具体哪一层出现异常
    private static final class SuspectBox {
        boolean suspect;

        SuspectBox(boolean suspect) {
            this.suspect = suspect;
        }
    }

    // 递归收集全部 http jar：
    //  type==0 为点播（sites），type 其它为直播（lives）。
    //  depth 为多仓递归深度，超深/超量即截断（与扫描上限一致）。
    //  多仓子源返回非合法 JSON（疑似加密/自解压载荷）时标记 box.suspect ——
    //  这是旧版 `!Json.isObj` 直接 return 漏掉南风之类的根因，现改为标可疑送沙箱复核。
    private static Set<String> collectJars(String url, int type, String content, int depth, Set<String> out, SuspectBox box) {
        if (depth > MAX_DEPOT || out.size() >= MAX_JAR_COUNT) return out;
        if (TextUtils.isEmpty(content)) return out;
        JsonObject object;
        try {
            JsonElement root = Json.parse(content);
            if (root == null || !root.isJsonObject()) return out;
            object = root.getAsJsonObject();
        } catch (Throwable e) {
            // 子仓内容非合法 JSON：疑似加密载荷，标可疑
            if (looksLikeSuspicious(content)) box.suspect = true;
            return out;
        }
        if (object.has("urls")) {
            // 多仓递归展开子源
            for (JsonElement element : Json.safeListElement(object, "urls")) {
                if (!element.isJsonObject()) continue;
                String sub = Json.safeString(element.getAsJsonObject(), "url");
                if (TextUtils.isEmpty(sub)) continue;
                String subContent = safeFetch(sub);
                // 子源非 JSON（疑似加密）即标可疑，交沙箱复核真伪
                if (subContent != null && !Json.isObj(subContent) && looksLikeSuspicious(subContent)) {
                    box.suspect = true;
                }
                collectJars(sub, type, subContent, depth + 1, out, box);
            }
            return out;
        }
        String spider = Json.safeString(object, "spider");
        addJar(out, spider);
        String sitesKey = (type == 0) ? "sites" : "lives";
        for (JsonElement element : Json.safeListElement(object, sitesKey)) {
            if (!element.isJsonObject()) continue;
            String jar = Json.safeString(element.getAsJsonObject(), "jar");
            if (jar.isEmpty()) jar = spider;
            addJar(out, jar);
        }
        return out;
    }

    private static String safeFetch(String url) {
        try {
            return fetchConfig(url);
        } catch (Throwable e) {
            return null;
        }
    }

    // 判断一串「非 JSON」文本是否为疑似加密/自解压载荷：
    //   - 极端十六进制 blob、极端 base64 blob 直接判可疑；
    //   - 具 HTTPS 链接的纯播放列表（m3u/m3u8）不算可疑；
    //   - 其余无法解析为合法子源结构的较长文本一律判可疑（fail-open，交沙箱复核真伪）。
    private static boolean looksLikeSuspicious(String text) {
        if (TextUtils.isEmpty(text)) return false;
        String t = text.trim();
        int len = t.length();
        if (len < 8) return false;
        if (t.startsWith("{") || t.startsWith("[")) return false; // JSON 开头，交给解析分支
        String lower = t.toLowerCase();
        if (lower.startsWith("#") || lower.startsWith("#3") || lower.startsWith("#4") || lower.startsWith("#5")) return false; // m3u/m3u8
        if (t.startsWith("http") && isPlaylistOr(t.toLowerCase())) return false;
        if (isHexBlob(t)) return true;
        if (isBase64Blob(t)) return true;
        return true; // 无法识别为合法播放列表/文本协议的较长内容
    }

    private static boolean isPlaylistOr(String t) {
        // 直链播放列表（http...m3u8 等）无数话：不标可疑
        return t.contains(".m3u") || t.contains(".m3u8") || t.contains("/playlist");
    }

    // 公文流极端十六进制字符明文判定（0-9a-f，长度>48）
    private static boolean isHexBlob(String t) {
        if (t.length() < 48) return false;
        int hex = 0;
        int len = t.length();
        for (int i = 0; i < len; i++) {
            char c = t.charAt(i);
            if ((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F')) hex++;
            else if (!Character.isWhitespace(c)) return false;
        }
        return (double) hex / len > 0.9;
    }

    // 极长 base64 字符流近似（无空格/无标点，洗掉 json 结构键）
    private static boolean isBase64Blob(String t) {
        if (t.length() < 64) return false;
        int alpha = 0;
        int len = t.length();
        for (int i = 0; i < len; i++) {
            char c = t.charAt(i);
            if ((c >= '0' && c <= '9') || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '+' || c == '/' || c == '=') alpha++;
            else return false;
        }
        return alpha >= len * 0.95;
    }

    private static String fetchConfig(String url) throws Exception {
        return Decoder.getJson(UrlUtil.convert(url), "Scan");
    }

    private static void addJar(Set<String> out, String jar) {
        if (TextUtils.isEmpty(jar)) return;
        if (!jar.startsWith("http")) return; // assets/file 为本地内容，不下载扫描
        String base = jar.split(";md5;")[0].trim();
        if (!base.isEmpty()) out.add(base);
    }

    // 返回 [kill, detect]；null 表示 jar 不可用（下载失败/过大），无法定性。
    // 注意：jar 为 ZIP 压缩容器，DEX 内字符串经 deflate 压缩后明文不可见，
    // 必须解包后在解压字节中搜索；直接扫原始 jar 字节会漏检。
    private static long[] scanJar(String jar) {
        long[] cached = MEM.get(jar);
        if (cached != null && System.currentTimeMillis() - cached[2] < MEM_TTL) return cached;
        // 与 JarLoader 相同的缓存键（Path.jar 内部已做 md5），扫描下载过的 jar 添加后直接复用
        File file = Path.jar(jar);
        try {
            if (!Path.exists(file) || file.length() == 0) {
                Download.create(jar, file).get();
            }
            if (!Path.exists(file) || file.length() == 0 || file.length() > MAX_JAR_BYTES) return null;
            long[] result = scanFile(file);
            if (result != null) MEM.put(jar, new long[]{result[0], result[1], System.currentTimeMillis()});
            return result;
        } catch (Throwable e) {
            return null;
        }
    }

    /**
     * 合并期入口：按 jar 地址检测（自动下载并复用 JarLoader 缓存键）。
     * 仅 http(s) 远程 jar 参与检测；assets/file 本地 jar 豁免。
     * fail-open：下载失败/检测异常一律放行。
     */
    public static boolean hasExitRef(String jar) {
        try {
            if (TextUtils.isEmpty(jar)) return false;
            String base = jar.split(";md5;")[0].trim();
            if (!base.startsWith("http")) return false;
            long[] flags = scanJar(base);
            return flags != null && flags[0] == 1;
        } catch (Throwable e) {
            return false;
        }
    }

    /**
     * 加载期/合并期入口：对已落地的 jar 文件做「自杀退出」精确检测。
     * true = 确认引用 System.exit / Runtime.exit|halt / Process.killProcess（直引或反射明文）。
     * 读不到文件、格式不认识等一律返回 false（fail-open，绝不因检测自身故障误杀正常源）。
     * 结果按 文件路径+大小+mtime 走内存缓存，重复加载零开销。
     */
    public static boolean hasExitRef(File file) {
        try {
            long[] flags = scanFile(file);
            return flags != null && flags[0] == 1;
        } catch (Throwable e) {
            return false;
        }
    }

    private static final ConcurrentHashMap<String, long[]> FILE_MEM = new ConcurrentHashMap<>();

    // 实际字节扫描：ZipFile 随机访问 + DEX method_ids 精确解析
    private static long[] scanFile(File file) {
        String ck = file.getAbsolutePath() + "|" + file.length() + "|" + file.lastModified();
        long[] cached = FILE_MEM.get(ck);
        if (cached != null && System.currentTimeMillis() - cached[2] < MEM_TTL) return cached;
        try (ZipFile zip = new ZipFile(file)) {
            boolean kill = false;
            boolean detect = false;
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (entry.getSize() > MAX_DEX_BYTES) continue;
                // 扫描 DEX 及内嵌 dex/jar 资源（部分恶意逻辑藏在 assets 二级包里）
                if (!name.endsWith(".dex") && !name.endsWith(".jar") && !name.endsWith(".bin")) continue;
                byte[] data = readEntry(zip, entry);
                if (data == null) continue;
                if (name.endsWith(".dex")) {
                    long[] flags = scanDex(data);
                    if (flags != null) {
                        kill |= flags[0] == 1;
                        detect |= flags[1] == 1;
                    }
                }
                // 内嵌 dex 被 deflate 压缩后明文不可见，故对非 .dex 条目（内层 jar/zip/bin）递归展开；
                // .dex 条目同时做反射明文兜底（killProcess 字面量）
                if (name.endsWith(".jar") || name.endsWith(".bin")) {
                    long[] inner = scanNested(data);
                    kill |= inner[0] == 1;
                    detect |= inner[1] == 1;
                } else if (containsBytes(data, SIG_KILL_PROCESS)) {
                    kill = true;
                }
                if (kill) break; // 已定罪，无需继续
            }
            long[] result = {kill ? 1 : 0, detect ? 1 : 0, System.currentTimeMillis()};
            FILE_MEM.put(ck, result);
            return result;
        } catch (Throwable e) {
            return null;
        }
    }

    // 展开内层 zip（assets 里的二级 jar），对其中每个 .dex 复用同一套精确检测
    private static long[] scanNested(byte[] data) {
        boolean kill = false;
        boolean detect = false;
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(data))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String name = entry.getName();
                if (!name.endsWith(".dex") && !name.endsWith(".jar") && !name.endsWith(".bin")) continue;
                byte[] inner = readStream(zis);
                if (inner == null) continue;
                if (name.endsWith(".dex")) {
                    long[] flags = scanDex(inner);
                    if (flags != null) {
                        kill |= flags[0] == 1;
                        detect |= flags[1] == 1;
                    }
                } else {
                    long[] deeper = scanNested(inner);
                    kill |= deeper[0] == 1;
                    detect |= deeper[1] == 1;
                }
                if (kill) break;
            }
        } catch (Throwable ignored) {
        }
        return new long[]{kill ? 1 : 0, detect ? 1 : 0};
    }

    /**
     * 解析单个 DEX 的 header/string_ids/type_ids/method_ids 表，
     * 逐条核对方法引用的「类描述符 + 方法名」是否命中危险 API。
     * 返回 [kill, detect]；null 表示非 DEX 或解析失败（不定罪）。
     */
    private static long[] scanDex(byte[] dex) {
        try {
            int[] tables = dexTables(dex);
            if (tables == null) return null;
            int stringIdsSize = tables[0], stringIdsOff = tables[1];
            int typeIdsSize = tables[2], typeIdsOff = tables[3];
            int methodIdsSize = tables[4], methodIdsOff = tables[5];
            boolean kill = !dangerousMethodIds(dex, stringIdsSize, stringIdsOff, typeIdsSize, typeIdsOff, methodIdsSize, methodIdsOff).isEmpty();
            boolean detect = false;
            if (!kill) {
                // 仅在未定罪时继续找「包名/环境检测」特征（定罪后 detect 标志无意义）
                for (int i = 0; i < methodIdsSize; i++) {
                    int base = methodIdsOff + i * 8;
                    int nameIdx = readLe32(dex, base + 4);
                    if (nameIdx < 0 || nameIdx >= stringIdsSize) continue;
                    String name = readDexString(dex, stringIdsOff, stringIdsSize, nameIdx);
                    if (name == null) continue;
                    for (String dn : DETECT_NAMES) {
                        if (dn.equals(name)) {
                            detect = true;
                            break;
                        }
                    }
                    if (detect) break;
                }
            }
            // 反射兜底：killProcess 字面量必然明文在 string_ids 区
            if (!kill) {
                for (int i = 0; i < stringIdsSize; i++) {
                    String s = readDexString(dex, stringIdsOff, stringIdsSize, i);
                    if (NAME_KILL_PROCESS.equals(s)) {
                        kill = true;
                        break;
                    }
                }
            }
            return new long[]{kill ? 1 : 0, detect ? 1 : 0};
        } catch (Throwable e) {
            return null;
        }
    }

    /**
     * 校验并读取 DEX 关键表头：[stringIdsSize, stringIdsOff, typeIdsSize, typeIdsOff, methodIdsSize, methodIdsOff]。
     * 偏移严格按 DEX 规范：string_ids@0x38、type_ids@0x40、method_ids@0x58。
     * 非法/非 DEX 返回 null。
     */
    static int[] dexTables(byte[] dex) {
        if (dex == null || dex.length < 0x70) return null;
        if (!(dex[0] == 'd' && dex[1] == 'e' && dex[2] == 'x' && dex[3] == '\n')) return null;
        int stringIdsSize = readLe32(dex, 0x38);
        int stringIdsOff = readLe32(dex, 0x3C);
        int typeIdsSize = readLe32(dex, 0x40);
        int typeIdsOff = readLe32(dex, 0x44);
        int methodIdsSize = readLe32(dex, 0x58);
        int methodIdsOff = readLe32(dex, 0x5C);
        if (stringIdsSize <= 0 || methodIdsSize <= 0 || typeIdsSize <= 0) return null;
        if ((long) stringIdsOff + (long) stringIdsSize * 4 > dex.length) return null;
        if ((long) typeIdsOff + (long) typeIdsSize * 4 > dex.length) return null;
        if ((long) methodIdsOff + (long) methodIdsSize * 8 > dex.length) return null;
        return new int[]{stringIdsSize, stringIdsOff, typeIdsSize, typeIdsOff, methodIdsSize, methodIdsOff};
    }

    /**
     * 解析 method_ids 表，返回「自杀退出」危险 API 的方法索引集合（供 DexPatch 中和定位）。
     * method_id_item 布局（DEX 规范）：class_idx@+0 u16、proto_idx@+2 u16、name_idx@+4 u32。
     */
    static Set<Integer> dangerousMethodIds(byte[] dex, int stringIdsSize, int stringIdsOff, int typeIdsSize, int typeIdsOff, int methodIdsSize, int methodIdsOff) {
        Set<Integer> ids = new HashSet<>();
        for (int i = 0; i < methodIdsSize; i++) {
            int base = methodIdsOff + i * 8;
            int classIdx = readLeU16(dex, base);
            int nameIdx = readLe32(dex, base + 4);
            if (classIdx < 0 || classIdx >= typeIdsSize || nameIdx < 0 || nameIdx >= stringIdsSize) continue;
            String cls = readDexString(dex, stringIdsOff, stringIdsSize, readLe32(dex, typeIdsOff + classIdx * 4));
            String name = readDexString(dex, stringIdsOff, stringIdsSize, nameIdx);
            if (cls == null || name == null || name.isEmpty()) continue;
            // 自杀退出：类描述符 + 方法名成对精确命中
            if (NAME_EXIT.equals(name) && (DESC_SYSTEM.equals(cls) || DESC_RUNTIME.equals(cls)) ||
                    NAME_HALT.equals(name) && DESC_RUNTIME.equals(cls) ||
                    NAME_KILL_PROCESS.equals(name) && DESC_PROCESS.equals(cls)) {
                ids.add(i);
            }
        }
        return ids;
    }

    /** 单 DEX 的危险方法索引集合；非 DEX/解析失败返回空集。 */
    static Set<Integer> dangerousMethodIds(byte[] dex) {
        int[] t = dexTables(dex);
        if (t == null) return new HashSet<>();
        return dangerousMethodIds(dex, t[0], t[1], t[2], t[3], t[4], t[5]);
    }

    // string_id（string_ids 表下标）-> utf-16 解码字符串（长度前缀为 ULEB128 的 utf16 字符数）
    private static String readDexString(byte[] dex, int stringIdsOff, int stringIdsSize, int stringId) {
        if (stringId < 0 || stringId >= stringIdsSize) return null;
        int entry = stringIdsOff + stringId * 4;
        if (entry + 4 > dex.length) return null;
        int off = readLe32(dex, entry);
        if (off <= 0 || off >= dex.length) return null;
        int p = off;
        // 跳 ULEB128 长度前缀
        while (p < dex.length && (dex[p] & 0x80) != 0) p++;
        p++;
        int start = p;
        while (p < dex.length && dex[p] != 0) p++;
        if (p - start > 2048) return null;
        // 短 ASCII 快速路径
        boolean ascii = true;
        for (int i = start; i < p; i++) {
            if ((dex[i] & 0x80) != 0) {
                ascii = false;
                break;
            }
        }
        if (ascii) return new String(dex, start, p - start, StandardCharsets.US_ASCII);
        // MUTF-8 解码
        try {
            return new String(dex, start, p - start, StandardCharsets.UTF_8);
        } catch (Throwable e) {
            return null;
        }
    }

    static int readLe32(byte[] b, int off) {
        return (b[off] & 0xFF) | (b[off + 1] & 0xFF) << 8 | (b[off + 2] & 0xFF) << 16 | (b[off + 3] & 0xFF) << 24;
    }

    private static int readLeU16(byte[] b, int off) {
        return (b[off] & 0xFF) | (b[off + 1] & 0xFF) << 8;
    }

    private static byte[] readStream(java.util.zip.ZipInputStream zis) {
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream(4096)) {
            byte[] buffer = new byte[16384];
            int read;
            long total = 0;
            while ((read = zis.read(buffer)) != -1) {
                total += read;
                if (total > MAX_DEX_BYTES) return null;
                bos.write(buffer, 0, read);
            }
            return bos.toByteArray();
        } catch (Throwable e) {
            return null;
        }
    }

    private static byte[] readEntry(ZipFile zip, ZipEntry entry) {
        try (InputStream is = zip.getInputStream(entry); ByteArrayOutputStream bos = new ByteArrayOutputStream(Math.max(4096, (int) Math.min(entry.getSize(), MAX_DEX_BYTES)))) {
            byte[] buffer = new byte[16384];
            int read;
            long total = 0;
            while ((read = is.read(buffer)) != -1) {
                total += read;
                if (total > MAX_DEX_BYTES) return null;
                bos.write(buffer, 0, read);
            }
            return bos.toByteArray();
        } catch (Throwable e) {
            return null;
        }
    }

    // 朴素子串搜索：jar 通常 < 20MB，模式串短，整体毫秒级
    private static boolean containsBytes(byte[] data, byte[] pattern) {
        outer:
        for (int i = 0; i <= data.length - pattern.length; i++) {
            if (data[i] != pattern[0]) continue;
            for (int j = 1; j < pattern.length; j++) {
                if (data[i + j] != pattern[j]) continue outer;
            }
            return true;
        }
        return false;
    }

    private static String str(JsonObject object, String key) {
        JsonElement element = object.get(key);
        if (element == null || element.isJsonNull()) return "";
        return element.isJsonPrimitive() ? element.getAsString() : element.toString();
    }
}
