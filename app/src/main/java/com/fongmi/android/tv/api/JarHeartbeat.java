package com.fongmi.android.tv.api;

import android.text.TextUtils;

import com.fongmi.android.tv.App;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * 「当前正在使用的站源/jar」心跳文件：站源切换 / spider 初始化时低频同步写入
 * （key|jar|wallMillis）。第三方 jar 若以 native killProcess 硬杀宿主（shutdown hook
 * 也不会响），下次启动由 JarGuard 验尸：无解释的自杀退出 + 心跳落在死亡窗口内
 * → 把心跳里的 jar 拉黑、站源记事故。同步 FileOutputStream 写，进程死亡不丢。
 */
public final class JarHeartbeat {

    private static final String FILE_NAME = "jar-heartbeat.txt";
    // 节流：同一 key+jar 3 秒内不重复落盘（recent() 在快搜遍历站点时高频调用）
    private static volatile String lastTag = "";
    private static volatile long lastWrite;

    private JarHeartbeat() {
    }

    public static void write(String key, String jar) {
        if (TextUtils.isEmpty(jar)) return;
        try {
            String tag = key + "|" + jar;
            long now = System.currentTimeMillis();
            if (tag.equals(lastTag) && now - lastWrite < 3000L) return;
            lastTag = tag;
            lastWrite = now;
            String content = (key == null ? "" : key) + "|" + jar + "|" + now;
            try (FileOutputStream stream = new FileOutputStream(file(), false)) {
                stream.write(content.getBytes(StandardCharsets.UTF_8));
                stream.flush();
            }
        } catch (Throwable ignored) {
        }
    }

    // 返回 [key, jar, wallMillis]；文件缺失/损坏返回 null
    public static String[] read() {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file()), StandardCharsets.UTF_8))) {
            String line = reader.readLine();
            if (TextUtils.isEmpty(line)) return null;
            String[] p = line.split("\\|", 3);
            if (p.length < 3) return null;
            Long.parseLong(p[2].trim());
            return p;
        } catch (Throwable e) {
            return null;
        }
    }

    public static void clear() {
        try {
            file().delete();
        } catch (Throwable ignored) {
        }
    }

    private static File file() {
        return new File(App.get().getFilesDir(), FILE_NAME);
    }
}
