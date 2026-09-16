package com.fongmi.android.tv.api;

import com.fongmi.android.tv.utils.DiagLog;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.zip.Adler32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * DEX 杀进程中和器（防御性，仅作用于第三方 spider jar 的缓存副本）。
 * <p>
 * 背景：部分订阅源 jar 内置「宿主授权开关」——运行时检测包名/应用名白名单，
 * 不在名单则延时调用 Process.killProcess / System.exit / Runtime.exit|halt 杀死宿主。
 * 本类在 jar 进入 DexClassLoader 之前，把这些危险 API 的全部 invoke 指令原地替换为
 * NOP（等长替换，寄存器分配与 try/catch 偏移不受影响），并重算 DEX 的 SHA-1 签名与
 * Adler-32 校验和，使 ART 校验通过。中和后源功能完整可用，仅杀进程开关失效。
 * <p>
 * 定位方式（与 {@link SourceScanner#dangerousMethodIds} 同一套精确解析）：
 * 遍历 class_defs → class_data → 每个 code_item 的指令流，按 Dalvik 权威指令宽度表
 * 精确走码（含 packed/sparse-switch、fill-array-data 等 payload 伪指令变长处理），
 * 凡 invoke 系列指令（0x6E-0x77，k35c/k3rc 均 3 码元，meth@+2）的方法索引命中
 * 危险集合，即整条指令写 0x0000（NOP 填充，等长替换）。
 * <p>
 * 自验证：走码过程中遇未分配操作码或结束时位置不等于指令末尾，判定该方法走码失准
 * （加固壳重映射操作码等），整方法放弃 patch，绝不盲改——宁可漏中和也不误伤。
 * <p>
 * fail-safe：任何解析异常返回「未修改」，调用方回退原有拦截逻辑，绝不因中和器
 * 自身故障放行危险代码或破坏正常 jar。
 */
public final class DexPatch {

    // 中和结果码（patchJarFile 返回值）
    public static final int OK = 0;             // 已中和并落盘
    public static final int ERR_WALK = 1;       // 危险代码块走码失准（加固混淆），无法安全定位 NOP
    public static final int ERR_NOT_FOUND = 2;  // 精确层无直引：仅明文/反射引用或藏在加密内嵌载荷
    public static final int ERR_IO = 3;         // 文件读写 / 校验和重算异常

    // 失败提示暂存键（Prefers）：值 = 每行 "jar地址\u0001原因码"，最多保留 5 条。
    // 后台线程（JarLoader.load）中和失败时写入，前台 onResume 消费弹窗。
    private static final String NOTICE = "dex_patch_notice";
    private static final String ROW_SEP = "\n";
    private static final String COL_SEP = "\u0001";
    private static final int NOTICE_MAX = 5;

    private DexPatch() {
    }

    /**
     * 中和单个 DEX 字节。返回 null 表示本 dex 未产生修改（无危险直引，或走码失准放弃）。
     * 通过 holder[0] 回传本 dex 的失准信号：1=存在危险直引但走码失准未能 NOP 任何一处。
     */
    static byte[] patchDex(byte[] dex, int[] holder) {
        try {
            Set<Integer> dangerous = SourceScanner.dangerousMethodIds(dex);
            if (dangerous.isEmpty()) return null;
            byte[] out = dex.clone();
            int count = nopInvokes(out, dangerous);
            if (count == 0) {
                if (holder != null) holder[0] = 1;
                return null;
            }
            recalcChecksum(out);
            DiagLog.log("dex-patch", "neutralized dex invocations=%s dangerousMethods=%s", count, dangerous);
            return out;
        } catch (Throwable e) {
            if (holder != null) holder[0] = 1;
            return null;
        }
    }

    // ===== 中和失败提示（后台线程写入，前台 onResume 消费弹窗） =====

    /**
     * 登记一条中和失败提示（同一 jar 去重，超上限丢弃最旧）。
     */
    public static void enqueueNotice(String jar, int code) {
        try {
            List<String> rows = readRows();
            for (String row : rows) {
                if (row.startsWith(jar + COL_SEP)) return;
            }
            rows.add(jar + COL_SEP + code);
            while (rows.size() > NOTICE_MAX) rows.remove(0);
            com.github.catvod.utils.Prefers.put(NOTICE, String.join(ROW_SEP, rows));
            DiagLog.log("dex-patch", "notice queued jar=%s code=%d", jar, code);
        } catch (Throwable ignored) {
        }
    }

    /**
     * 取出并清空全部待提示：返回 [jar, 原因码字符串] 列表，null 表示无。
     */
    public static List<String[]> takeNotice() {
        try {
            List<String> rows = readRows();
            if (rows.isEmpty()) return null;
            com.github.catvod.utils.Prefers.put(NOTICE, "");
            List<String[]> result = new ArrayList<>();
            for (String row : rows) {
                int i = row.indexOf(COL_SEP);
                if (i > 0) result.add(new String[]{row.substring(0, i), row.substring(i + COL_SEP.length())});
            }
            return result.isEmpty() ? null : result;
        } catch (Throwable e) {
            return null;
        }
    }

    private static List<String> readRows() {
        List<String> rows = new ArrayList<>();
        String raw = com.github.catvod.utils.Prefers.getString(NOTICE, "");
        if (raw.isEmpty()) return rows;
        for (String row : raw.split(ROW_SEP)) {
            if (!row.isEmpty()) rows.add(row);
        }
        return rows;
    }

    /**
     * 中和整个 jar 文件（原地重写）。返回 {@link #OK} 表示已中和并落盘；
     * 其余为失败原因码，调用方据此回退拦截并向用户提示。
     * 嵌套 .jar/.bin 条目递归处理；其余条目原样保留。
     */
    public static int patchJarFile(File file) {
        int[] holder = new int[1];
        byte[] data;
        try {
            data = readAll(file);
        } catch (Throwable e) {
            return ERR_IO;
        }
        byte[] patched;
        try {
            patched = patchJarBytes(data, holder);
        } catch (Throwable e) {
            return ERR_IO;
        }
        // 任一 dex 存在危险直引却走码失准 → 该 killProcess 未被 NOP，部分中和比不中和更危险，整体判失败不落盘
        if (holder[0] == 1) return ERR_WALK;
        if (patched == null) return ERR_NOT_FOUND;
        try {
            File tmp = new File(file.getAbsolutePath() + ".patching");
            try (FileOutputStream fos = new FileOutputStream(tmp)) {
                fos.write(patched);
            }
            // Linux 文件系统 renameTo 可直接覆盖目标；覆盖失败再退化为 delete+rename
            if (!tmp.renameTo(file) && (!file.delete() || !tmp.renameTo(file))) {
                tmp.delete();
                return ERR_IO;
            }
            DiagLog.log("dex-patch", "jar neutralized file=%s size=%s", file.getAbsolutePath(), file.length());
            return OK;
        } catch (Throwable e) {
            return ERR_IO;
        }
    }

    /**
     * 中和 jar 字节（ZIP 容器）。返回 null 表示未产生修改。
     * holder[0]==1 仅在「某 dex 存在危险直引但走码失准未能 NOP」时置位；
     * ZIP/IO 异常抛给调用方按 ERR_IO 处理，避免误报「加固混淆」。
     */
    static byte[] patchJarBytes(byte[] data, int[] holder) throws Exception {
        List<String> names = new ArrayList<>();
        List<byte[]> blobs = new ArrayList<>();
        boolean changed = false;
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(data))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                byte[] bytes = readStream(zis);
                String name = entry.getName();
                if (name.endsWith(".dex")) {
                    byte[] patched = patchDex(bytes, holder);
                    if (patched != null) {
                        bytes = patched;
                        changed = true;
                    }
                } else if (name.endsWith(".jar") || name.endsWith(".bin")) {
                    byte[] patched = patchJarBytes(bytes, holder);
                    if (patched != null) {
                        bytes = patched;
                        changed = true;
                    }
                }
                names.add(name);
                blobs.add(bytes);
            }
        }
        if (!changed) return null;
        ByteArrayOutputStream bos = new ByteArrayOutputStream(data.length);
        try (ZipOutputStream zos = new ZipOutputStream(bos)) {
            for (int i = 0; i < names.size(); i++) {
                zos.putNextEntry(new ZipEntry(names.get(i)));
                zos.write(blobs.get(i));
                zos.closeEntry();
            }
        }
        return bos.toByteArray();
    }

    // Dalvik 指令宽度表（单位：16-bit 码元；0=未观测/未分配，走码遇 0 即判失准放弃整方法）。
    // 由本仓库 dexdump 对真实 spider.dex 全量直方图统计 + 规范补齐生成，并经自验证走码确认。
    private static final int[] LEN = {
            1, 1, 2, 0, 1, 2, 0, 1, 2, 0, 1, 1, 1, 1, 1, 1,
            1, 1, 1, 2, 3, 2, 2, 3, 5, 2, 2, 3, 2, 1, 1, 2,
            2, 1, 2, 2, 3, 3, 3, 1, 1, 2, 3, 3, 3, 2, 2, 2,
            2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 0, 0,
            0, 0, 0, 0, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2,
            2, 0, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2,
            2, 2, 2, 2, 0, 0, 0, 2, 2, 2, 2, 0, 0, 0, 3, 3,
            3, 3, 3, 0, 3, 0, 3, 3, 3, 0, 0, 1, 1, 1, 1, 0,
            1, 1, 1, 1, 1, 0, 1, 1, 0, 1, 1, 1, 1, 1, 1, 1,
            2, 2, 2, 2, 2, 2, 2, 0, 2, 2, 0, 2, 2, 2, 2, 0,
            2, 2, 2, 2, 2, 2, 0, 0, 2, 0, 0, 0, 2, 2, 2, 0,
            1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 0,
            1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 0, 1, 1, 1, 1, 1,
            2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2,
            2, 2, 2, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 4, 4, 4, 4, 2, 2,
    };
    // jumbo 伪操作码（首码元高字节）：invoke-super/jumbo 等，宽度各异；未列者走码失准放弃
    private static final int[] JUMBO = {0, 0, 0, 0, 0, 0, 0, 0, 3, 3, 0, 3, 2, 2, 2, 2};

    // 遍历 class_defs -> class_data -> code_item，按权威宽度表精确走码，
    // 把危险方法的 invoke 指令（0x6E-0x77，k35c/k3rc 均 3 码元，meth@+2）整条 NOP。
    // 走码失准（未分配操作码 / 结束位置不等于指令末尾）即整方法放弃，绝不盲改。
    // 返回替换的指令条数。
    private static int nopInvokes(byte[] dex, Set<Integer> dangerous) {
        int classDefsSize = SourceScanner.readLe32(dex, 0x60);
        int classDefsOff = SourceScanner.readLe32(dex, 0x64);
        int typeIdsSize = SourceScanner.readLe32(dex, 0x40);
        if (classDefsSize <= 0 || classDefsOff <= 0 || (long) classDefsOff + (long) classDefsSize * 32 > dex.length) return 0;
        int patched = 0;
        for (int i = 0; i < classDefsSize; i++) {
            int cb = classDefsOff + i * 32;
            int cidx = readLeU16(dex, cb);
            if (cidx < 0 || cidx >= typeIdsSize) continue;
            int cdo = readLe32(dex, cb + 24);
            if (cdo <= 0 || cdo >= dex.length) continue;
            int[] p = {cdo};
            int staticFields = uleb(dex, p);
            int instanceFields = uleb(dex, p);
            int directMethods = uleb(dex, p);
            int virtualMethods = uleb(dex, p);
            for (int j = 0; j < staticFields + instanceFields; j++) {
                uleb(dex, p);
                uleb(dex, p);
            }
            for (int j = 0; j < directMethods + virtualMethods; j++) {
                uleb(dex, p);
                uleb(dex, p);
                int codeOff = uleb(dex, p);
                if (codeOff <= 0 || codeOff + 16 > dex.length) continue;
                int insSize = readLeU16(dex, codeOff + 12);
                int q = codeOff + 16;
                int end = q + insSize * 2;
                if (end > dex.length) continue;
                // 先完整走码收集命中，走码失准则整方法放弃（宁可漏中和不误伤）
                int[] hits = null;
                int hitsCount = 0;
                boolean ok = true;
                while (q < end) {
                    int head = readLeU16(dex, q);
                    int op = head & 0xFF;
                    if (op == 0x00) {
                        int hi = head >> 8;
                        if (hi == 0x00) {
                            q += 2; // nop
                        } else if (hi == 0x01) { // packed-switch-payload: 4 + 2*size
                            int size = readLeU16(dex, q + 2);
                            q += (4 + 2 * size) * 2;
                        } else if (hi == 0x02) { // sparse-switch-payload: 2 + 4*size
                            int size = readLeU16(dex, q + 2);
                            q += (2 + 4 * size) * 2;
                        } else if (hi == 0x03) { // fill-array-data-payload: 4 + ceil(width*count/2)，偶数补齐
                            int width = readLeU16(dex, q + 2);
                            int count = readLe32(dex, q + 4);
                            int units = 4 + (width * count + 1) / 2;
                            q += ((units & 1) == 0 ? units : units + 1) * 2;
                        } else if (hi < JUMBO.length && JUMBO[hi] > 0) {
                            q += JUMBO[hi] * 2;
                        } else {
                            ok = false;
                            break;
                        }
                        if (q > end) {
                            ok = false;
                            break;
                        }
                        continue;
                    }
                    int n = LEN[op];
                    if (n == 0) {
                        ok = false;
                        break;
                    }
                    if (op >= 0x6E && op <= 0x77 && dangerous.contains(readLeU16(dex, q + 2))) {
                        if (hits == null) hits = new int[8];
                        else if (hitsCount == hits.length) {
                            int[] bigger = new int[hits.length * 2];
                            System.arraycopy(hits, 0, bigger, 0, hitsCount);
                            hits = bigger;
                        }
                        hits[hitsCount++] = q;
                    }
                    q += n * 2;
                }
                if (!ok || q != end) continue; // 走码失准：整方法放弃
                for (int h = 0; h < hitsCount; h++) {
                    int a = hits[h];
                    if (a + 6 <= end) {
                        writeLe16(dex, a, 0);
                        writeLe16(dex, a + 2, 0);
                        writeLe16(dex, a + 4, 0);
                        patched++;
                    }
                }
            }
        }
        return patched;
    }

    // 重算 header 的 SHA-1 签名与 Adler-32 校验和（顺序：先签名后校验和）
    private static void recalcChecksum(byte[] dex) throws Exception {
        byte[] zero = new byte[20];
        System.arraycopy(zero, 0, dex, 12, 20);
        MessageDigest md = MessageDigest.getInstance("SHA-1");
        md.update(dex, 32, dex.length - 32);
        System.arraycopy(md.digest(), 0, dex, 12, 20);
        dex[8] = dex[9] = dex[10] = dex[11] = 0;
        Adler32 adler = new Adler32();
        adler.update(dex, 12, dex.length - 12);
        int value = (int) adler.getValue();
        dex[8] = (byte) value;
        dex[9] = (byte) (value >>> 8);
        dex[10] = (byte) (value >>> 16);
        dex[11] = (byte) (value >>> 24);
    }

    private static int uleb(byte[] b, int[] p) {
        int result = 0;
        int shift = 0;
        while (p[0] < b.length) {
            int c = b[p[0]++] & 0xFF;
            result |= (c & 0x7F) << shift;
            if ((c & 0x80) == 0) return result;
            shift += 7;
            if (shift > 28) throw new IllegalArgumentException("uleb too long");
        }
        throw new IllegalArgumentException("uleb truncated");
    }

    private static int readLe32(byte[] b, int off) {
        return (b[off] & 0xFF) | (b[off + 1] & 0xFF) << 8 | (b[off + 2] & 0xFF) << 16 | (b[off + 3] & 0xFF) << 24;
    }

    private static int readLeU16(byte[] b, int off) {
        return (b[off] & 0xFF) | (b[off + 1] & 0xFF) << 8;
    }

    private static void writeLe16(byte[] b, int off, int value) {
        b[off] = (byte) value;
        b[off + 1] = (byte) (value >>> 8);
    }

    private static byte[] readAll(File file) throws Exception {
        try (InputStream is = new FileInputStream(file)) {
            return readStream(is);
        }
    }

    private static byte[] readStream(InputStream is) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(4096);
        byte[] buffer = new byte[16384];
        int read;
        while ((read = is.read(buffer)) != -1) bos.write(buffer, 0, read);
        return bos.toByteArray();
    }
}
