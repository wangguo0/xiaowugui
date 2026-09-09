package com.fongmi.android.tv.setting;

import android.text.TextUtils;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.utils.DiagLog;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * 站源「事故计数表」：第三方 jar 每次导致崩溃被吞、或在进程死亡瞬间被归因
 * （shutdown hook 死亡归因 / 启动验尸归因），对应站源记一次事故。
 * 同一站源累计满 2 次 → 永久屏蔽且不可解禁（SiteBlockSetting.blockForever），
 * 其 jar 同步进黑名单。
 * 计数落独立文件并同步写：进程被 jar 自杀的瞬间 SharedPreferences apply() 可能来不及落盘，
 * 普通文件 FileOutputStream 写完即持久。
 */
public final class SiteIncidentSetting {

    private static final String FILE_NAME = "site-incident.txt";
    private static final int LIMIT = 2;
    private static final Object LOCK = new Object();
    private static Map<String, Integer> counts;

    private SiteIncidentSetting() {
    }

    // 永久屏蔽阈值（对外暴露供提示文案组装）
    public static int limit() {
        return LIMIT;
    }

    // 记录一次事故，返回累计次数（0 表示 key 为空未记录）
    public static int add(String key, String jar) {
        if (TextUtils.isEmpty(key)) return 0;
        try {
            int count;
            synchronized (LOCK) {
                if (counts == null) counts = load();
                count = counts.getOrDefault(key, 0) + 1;
                counts.put(key, count);
                save();
            }
            DiagLog.log("jar-guard", "incident key=%s count=%d jar=%s", key, count, jar);
            if (count >= LIMIT) {
                SiteBlockSetting.blockForever(key);
                JarBlockSetting.add(jar);
                DiagLog.log("jar-guard", "site locked forever key=%s incidents=%d", key, count);
            }
            return count;
        } catch (Throwable e) {
            return 0;
        }
    }

    private static File file() {
        return new File(App.get().getFilesDir(), FILE_NAME);
    }

    private static Map<String, Integer> load() {
        Map<String, Integer> map = new HashMap<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file()), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                int i = line.lastIndexOf('=');
                if (i <= 0) continue;
                try {
                    map.put(line.substring(0, i), Integer.parseInt(line.substring(i + 1).trim()));
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
        return map;
    }

    private static void save() {
        try (FileOutputStream stream = new FileOutputStream(file(), false)) {
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, Integer> entry : counts.entrySet()) sb.append(entry.getKey()).append('=').append(entry.getValue()).append('\n');
            stream.write(sb.toString().getBytes(StandardCharsets.UTF_8));
            stream.flush();
        } catch (Throwable ignored) {
        }
    }
}
