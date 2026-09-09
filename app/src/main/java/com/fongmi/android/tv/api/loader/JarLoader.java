package com.fongmi.android.tv.api.loader;

import android.content.Context;
import android.text.TextUtils;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.utils.DiagLog;
import com.fongmi.android.tv.utils.Download;
import com.fongmi.android.tv.utils.UrlUtil;
import com.github.catvod.crawler.Spider;
import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.crawler.SpiderNull;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Path;
import com.github.catvod.utils.Util;

import org.json.JSONObject;

import java.io.File;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import dalvik.system.DexClassLoader;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class JarLoader {

    private final ConcurrentHashMap<String, DexClassLoader> loaders;
    private final ConcurrentHashMap<String, Method> methods;
    private final ConcurrentHashMap<String, Spider> spiders;
    private final ConcurrentHashMap<String, Object> locks;
    private volatile String recent;
    // 最近一次正在 init 的 jar 地址：spider.Init 子线程崩溃时用于定位「肇事播放路线」
    private volatile String lastInitJar;
    // DexClassLoader -> jar 地址（md5(key) 映射），宿主崩溃时用栈帧类名的 ClassLoader 反查肇事 jar
    private final ConcurrentHashMap<DexClassLoader, String> loaderJars;
    private static volatile JarLoader instance;

    public static JarLoader get() {
        return instance;
    }

    public String getLastInitJar() {
        return lastInitJar;
    }

    /**
     * 宿主崩溃精准归因：从 exit 调用线程栈中逐帧解析类名，用 {@code Class.forName} 取到
     * 其真实 ClassLoader，命中已登记的 jar 加载器即返回对应 jar 地址（肇事者铁证）。
     * 比「最近 init / 心跳 jar」精准得多：多源并发（换源/快搜）时只锁定真正执行
     * System.exit 的那一个 jar，绝不误伤当前正在播放但没有罪过的站源。
     * 反查不到时返回 null，由调用方回退到心跳/最近 init 兜底。
     */
    public String blameJarFromStack(String exitStack) {
        if (exitStack == null) return null;
        try {
            for (String line : exitStack.split("\n")) {
                String className = extractClassName(line);
                if (TextUtils.isEmpty(className)) continue;
                // 只反查「动态 jar 内」的类，避开系统/本 app 帧
                if (className.startsWith("com.") || className.startsWith("cn.")) {
                    Class<?> clz = Class.forName(className);
                    ClassLoader cl = clz.getClassLoader();
                    if (cl instanceof DexClassLoader) {
                        String jar = loaderJars.get(cl);
                        if (!TextUtils.isEmpty(jar)) {
                            DiagLog.log("jar-loader", "blame stack class=%s jar=%s", className, jar);
                            return jar;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    // 解析栈帧形如：at com.foo.Bar.method(File.java:10) -> com.foo.Bar
    private static String extractClassName(String line) {
        String l = line.trim();
        if (!l.startsWith("at ")) return null;
        String rest = l.substring(3).trim();
        int paren = rest.indexOf('(');
        String mn = paren < 0 ? rest : rest.substring(0, paren);
        int dot = mn.lastIndexOf('.');
        return dot < 0 ? null : mn.substring(0, dot);
    }

    public JarLoader() {
        instance = this;
        loaders = new ConcurrentHashMap<>();
        loaderJars = new ConcurrentHashMap<>();
        methods = new ConcurrentHashMap<>();
        spiders = new ConcurrentHashMap<>();
        locks = new ConcurrentHashMap<>();
    }

    public void clear() {
        SpiderDebug.log("jar-loader", "clear loaders=%s spiders=%s methods=%s", loaders.size(), spiders.size(), methods.size());
        spiders.values().forEach(Spider::destroy);
        loaders.clear();
        loaderJars.clear();
        methods.clear();
        spiders.clear();
        locks.clear();
        recent = null;
    }

    public void setRecent(String recent) {
        this.recent = recent;
        SpiderDebug.log("jar-loader", "recent=%s", recent);
    }

    // 崩溃回调里用于「换源/播放」场景反查肇事 jar（recent 是 md5(key)）
    public String getRecent() {
        return recent;
    }

    private void load(String key, File file) {
        long start = System.currentTimeMillis();
        if (Thread.interrupted()) {
            SpiderDebug.log("jar-loader", "load skip interrupted key=%s", key);
            return;
        }
        if (!Path.exists(file)) {
            SpiderDebug.log("jar-loader", "load skip missing key=%s file=%s", key, file);
            return;
        }
        if (!file.setReadOnly()) {
            SpiderDebug.log("jar-loader", "load skip readonly failed key=%s file=%s size=%s", key, file.getAbsolutePath(), file.length());
            return;
        }
        // 加载前静态闸口：解析 DEX 方法引用，确认 jar 直引/反射明文引用
        // System.exit / Runtime.exit|halt / Process.killProcess（宿主自杀）→ 永久拉黑并拒绝加载。
        // 该 jar 代码永不进内存：无 Init 崩溃、无 System.exit 杀宿主、无任何用户提示。
        // fail-open：检测自身异常一律放行，绝不误杀正常源。
        // 受「添加源安全检测」开关控制：关闭时不执行静态扫描，直接加载。
        if (com.fongmi.android.tv.setting.Setting.isProbeAdd() && com.fongmi.android.tv.api.SourceScanner.hasExitRef(file)) {
            com.fongmi.android.tv.setting.JarBlockSetting.add(lastInitJar);
            com.fongmi.android.tv.utils.DiagLog.log("jar-guard", "load reject suicidal jar key=%s file=%s jar=%s", key, file.getAbsolutePath(), lastInitJar);
            SpiderDebug.log("jar-loader", "load reject suicidal jar key=%s", key);
            return;
        }
        String cachePath = Path.jar().getAbsolutePath();
        SpiderDebug.log("jar-loader", "load start key=%s file=%s size=%s cache=%s", key, file.getAbsolutePath(), file.length(), cachePath);
        DexClassLoader loader = new CspDexClassLoader(file.getAbsolutePath(), cachePath, cachePath, App.get().getClassLoader());
        invokeInit(key, loader);
        invokeNetworkCompat(key, loader);
        invokeProxy(key, loader);
        loaders.put(key, loader);
        SpiderDebug.log("jar-loader", "load done key=%s cost=%sms", key, System.currentTimeMillis() - start);
    }

    private void invokeNetworkCompat(String key, DexClassLoader loader) {
        try {
            Class<?> clz = loader.loadClass("com.github.catvod.parser.merge.j0.b");
            Method method = clz.getMethod("b", okhttp3.OkHttpClient.class);
            OkHttpClient client = OkHttp.client().newBuilder().addInterceptor(chain -> {
                Request request = chain.request();
                boolean iframe = request.url().host().endsWith("youtube.com") && request.url().encodedPath().contains("iframe_api");
                long start = System.currentTimeMillis();
                try {
                    Response response = chain.proceed(request);
                    if (iframe) SpiderDebug.log("youtube-iframe", "response code=%s final=%s type=%s encoding=%s length=%s cost=%sms", response.code(), response.request().url(), response.header("Content-Type"), response.header("Content-Encoding"), response.header("Content-Length"), System.currentTimeMillis() - start);
                    return response;
                } catch (Throwable e) {
                    if (iframe) SpiderDebug.log("youtube-iframe", "failed url=%s cost=%sms error=%s", request.url(), System.currentTimeMillis() - start, error(e));
                    throw e;
                }
            }).build();
            Class<?> environment = loader.loadClass("com.github.catvod.parser.merge.j0.c");
            environment.getConstructor(okhttp3.OkHttpClient.class).newInstance(client);
            SpiderDebug.log("jar-loader", "network client injected key=%s", key);
            try {
                Class<?> provider = loader.loadClass("com.github.catvod.parser.merge.P1.P");
                Object fetcher = provider.getMethod("b").invoke(null);
                Object response = clz.getMethod("a", String.class).invoke(fetcher, "https://www.youtube.com/iframe_api");
                String body = (String) response.getClass().getMethod("a").invoke(response);
                SpiderDebug.log("youtube-iframe", "probe success chars=%s", body == null ? -1 : body.length());
            } catch (Throwable e) {
                Throwable cause = e;
                while (cause.getCause() != null) cause = cause.getCause();
                SpiderDebug.log("youtube-iframe", "probe failed error=%s", cause.getClass().getName() + ":" + cause.getMessage());
                SpiderDebug.log("youtube-iframe", cause);
            }
        } catch (ClassNotFoundException | NoSuchMethodException ignored) {
            // Optional compatibility hook used only by jars which expose it.
        } catch (Throwable e) {
            SpiderDebug.log("jar-loader", "network client inject failed key=%s error=%s", key, error(e));
        }
    }

    private void invokeInit(String key, DexClassLoader loader) {
        long start = System.currentTimeMillis();
        try {
            SpiderDebug.log("jar-loader", "jar init start key=%s", key);
            Class<?> clz = loader.loadClass("com.github.catvod.spider.Init");
            Method method = clz.getMethod("init", Context.class);
            method.invoke(clz, App.get());
            SpiderDebug.log("jar-loader", "jar init done key=%s cost=%sms", key, System.currentTimeMillis() - start);
        } catch (Throwable e) {
            SpiderDebug.log("jar-loader", "jar init error key=%s cost=%sms error=%s", key, System.currentTimeMillis() - start, error(e));
            SpiderDebug.log("jar-loader", e);
            e.printStackTrace();
        }
    }

    private void invokeProxy(String key, DexClassLoader loader) {
        long start = System.currentTimeMillis();
        try {
            Class<?> clz = loader.loadClass("com.github.catvod.spider.Proxy");
            Method method = clz.getMethod("proxy", Map.class);
            methods.put(key, method);
            SpiderDebug.log("jar-loader", "proxy method ready key=%s cost=%sms", key, System.currentTimeMillis() - start);
        } catch (Throwable e) {
            SpiderDebug.log("jar-loader", "proxy method missing key=%s cost=%sms error=%s", key, System.currentTimeMillis() - start, error(e));
            e.printStackTrace();
        }
    }

    public void parseJar(String key, String jar) {
        if (loaders.containsKey(key)) return;
        if (com.fongmi.android.tv.setting.JarBlockSetting.isBlocked(jar) || com.fongmi.android.tv.setting.JarBlockSetting.isBlocked(jar.split(";md5;")[0])) {
            SpiderDebug.log("jar-loader", "parse skip blocked jar=%s", jar);
            return;
        }
        if (jar.startsWith("assets")) jar = UrlUtil.convert(jar);
        lastInitJar = jar.split(";md5;")[0];
        String base = lastInitJar;
        Object lock = locks.computeIfAbsent(key, k -> new Object());
        synchronized (lock) {
            if (loaders.containsKey(key)) return;
            String[] texts = jar.split(";md5;");
            String md5 = texts.length > 1 ? texts[1].trim() : "";
            if (md5.startsWith("http")) md5 = OkHttp.string(md5).trim();
            jar = texts[0];
            SpiderDebug.log("jar-loader", "parse start key=%s source=%s md5=%s", key, source(jar), !md5.isEmpty());
            if (!md5.isEmpty() && Util.equals(jar, md5)) {
                load(key, Path.jar(jar));
            } else if (jar.startsWith("http")) {
                load(key, Download.create(jar, Path.jar(jar)).get());
            } else if (jar.startsWith("file")) {
                load(key, Path.local(jar));
            }
            // 登记 DexClassLoader -> jar 源地址，供宿主崩溃时栈帧类名精准反查
            DexClassLoader loaded = loaders.get(key);
            if (loaded != null) loaderJars.put(loaded, base);
        }
    }

    public DexClassLoader dex(String jar) {
        try {
            String jaKey = Util.md5(jar);
            parseJar(jaKey, jar);
            return loaders.get(jaKey);
        } catch (Throwable e) {
            e.printStackTrace();
            return null;
        }
    }

    public Spider getSpider(String key, String api, String ext, String jar) {
        // 双保险：entry 处直接拦截崩溃黑名单 jar，任何具备自杀逻辑的 jar（如被死亡归因/
        // 静态扫描拉黑的）一律不进入加载与 init 流程，返回空 spider，避免反复触发杀宿主代码
        if (com.fongmi.android.tv.setting.JarBlockSetting.isBlocked(jar) || com.fongmi.android.tv.setting.JarBlockSetting.isBlocked(jar.split(";md5;")[0])) {
            SpiderDebug.log("jar-loader", "spider skip blocked jar=%s", jar);
            return new SpiderNull();
        }
        String jaKey = Util.md5(jar);
        String spKey = jaKey + key;
        return spiders.computeIfAbsent(spKey, k -> {
            long start = System.currentTimeMillis();
            try {
                SpiderDebug.log("jar-loader", "spider init start site=%s api=%s jar=%s ext=%s", key, api, jaKey, ext == null ? 0 : ext.length());
                parseJar(jaKey, jar);
                DexClassLoader loader = loaders.get(jaKey);
                if (loader == null) {
                    SpiderDebug.log("jar-loader", "spider init skip loader missing site=%s api=%s jar=%s cost=%sms", key, api, jaKey, System.currentTimeMillis() - start);
                    return new SpiderNull();
                }
                Spider spider = (Spider) loader.loadClass("com.github.catvod.spider." + api.split("csp_")[1]).newInstance();
                spider.siteKey = key;
                spider.init(App.get(), ext);
                SpiderDebug.log("jar-loader", "spider init done site=%s api=%s jar=%s class=%s cost=%sms", key, api, jaKey, spider.getClass().getName(), System.currentTimeMillis() - start);
                return spider;
            } catch (Throwable e) {
                SpiderDebug.log("jar-loader", "spider init error site=%s api=%s jar=%s cost=%sms error=%s", key, api, jaKey, System.currentTimeMillis() - start, error(e));
                SpiderDebug.log("jar-loader", e);
                e.printStackTrace();
                return new SpiderNull();
            }
        });
    }

    private String source(String jar) {
        if (jar.startsWith("http")) return "http:" + Util.md5(jar);
        if (jar.startsWith("file")) return "file:" + jar.length();
        return jar;
    }

    private String error(Throwable e) {
        Throwable cause = e.getCause() == null ? e : e.getCause();
        return cause.getClass().getSimpleName() + ":" + cause.getMessage();
    }

    private DexClassLoader requireRecentLoader() {
        DexClassLoader loader = loaders.get(recent);
        if (loader == null) throw new IllegalStateException("No jar loaded for recent key: " + recent);
        return loader;
    }

    public JSONObject jsonExt(String key, LinkedHashMap<String, String> jxs, String url) throws Throwable {
        Class<?> clz = requireRecentLoader().loadClass("com.github.catvod.parser.Json" + key);
        Method method = clz.getMethod("parse", LinkedHashMap.class, String.class);
        return (JSONObject) method.invoke(null, jxs, url);
    }

    public JSONObject jsonExtMix(String flag, String key, String name, LinkedHashMap<String, HashMap<String, String>> jxs, String url) throws Throwable {
        Class<?> clz = requireRecentLoader().loadClass("com.github.catvod.parser.Mix" + key);
        Method method = clz.getMethod("parse", LinkedHashMap.class, String.class, String.class, String.class);
        return (JSONObject) method.invoke(null, jxs, name, flag, url);
    }

    public Object[] proxy(Map<String, String> params) throws Exception {
        Method method = recent != null ? methods.get(recent) : null;
        Object[] result = proxyInvoke(method, params);
        if (result != null) return result;
        return tryOthers(params);
    }

    private Object[] tryOthers(Map<String, String> p) {
        return methods.entrySet().stream().filter(e -> !e.getKey().equals(recent)).map(e -> proxyInvoke(e.getValue(), p)).filter(Objects::nonNull).findFirst().orElse(null);
    }

    private Object[] proxyInvoke(Method method, Map<String, String> params) {
        try {
            return method == null ? null : (Object[]) method.invoke(null, params);
        } catch (Throwable e) {
            e.printStackTrace();
            return null;
        }
    }
}
