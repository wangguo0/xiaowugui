# WebHomeTV 项目

这是一个基于 CatVod 生态的 Android 影音应用，支持手机端和 Android TV 端，主要能力包括点播、直播、爬虫扩展、WebHome 自定义首页、本地 HTTP 服务、投屏和多设备同步。

完整开发、配置、WebHome、开放能力、隐藏功能和打包说明都已整合到：

```text
docs/应用完整开发文档.md
```

WebHome 页面示例放在：

```text
demo/nostr.html
demo/check.html
```

仓库只保留源码、资源、文档和必要 Gradle Wrapper 文件；不包含 APK/AAB、AAR、JNI `.so`、本地签名配置、Gradle 构建缓存和其它编译产物。

常用手机端 arm64 release 打包命令：

```bash
bash gradlew assembleMobileArm64_v8aRelease
```

当前启动 App 不会自动弹出版本更新窗口，用户可在设置页手动检查版本。
