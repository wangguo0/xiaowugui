# WebHTV 二开差异移植清单

本文档记录 WebHTV 5.4.1 发布版相对 FongMi/TV 官方 5.4.1 基线的二次开发差异，用于后续把同一套能力移植到官方 5.4.9、5.5.2 或更新版本。

核心原则：以后合并官方新版本时，不直接沿用旧的 WebHTV 分支整体合并；先以官方目标版本为底座，再按本文档移植 WebHTV 二开能力。这样可以减少上游依赖、播放器、Go 二进制、Spider Jar 等能力被非预期改坏的风险。

## 差异来源

官方 5.4.1 基线：

```text
仓库：https://github.com/FongMi/TV
提交：c057cb142
版本：versionCode 541 / versionName 5.4.1
说明：release 分支 2026-04-20 附近的 5.4.1 代码
```

WebHTV 5.4.1 发布版：

```text
仓库：https://github.com/fish2018/webhtv
tag：v5.4.1-202605240217
提交：f55580a91
版本：versionCode 541 / versionName 5.4.1
```

官方 5.4.9 目标底座：

```text
仓库：https://github.com/FongMi/TV
提交：a00ebd257
版本：versionCode 549 / versionName 5.4.9
```

差异查看命令：

```bash
git diff --name-status c057cb142 v5.4.1-202605240217
git diff --stat c057cb142 v5.4.1-202605240217
```

如果在独立官方仓库中操作，可以先拉取 WebHTV tag：

```bash
git remote add webhtv https://github.com/fish2018/webhtv.git
git fetch webhtv tag v5.4.1-202605240217
```

## 必须移植的能力

### 1. WebHome 自定义主页

功能目标：允许 CSP 站点通过 `homePage` 配置自定义首页网页。App 切换到该 CSP 首页时，如果有 `homePage`，优先显示 WebView 首页；没有配置时回退官方原生首页。

配置字段：

```json
{
  "key": "site-key",
  "name": "站点名称",
  "api": "...",
  "homePage": "https://example.com/nostr.html"
}
```

支持别名：

```text
homePage
home_page
webHome
web_home
```

必须移植文件：

```text
app/src/main/java/com/fongmi/android/tv/bean/Site.java
app/src/main/java/com/fongmi/android/tv/web/HomeWebController.java
app/src/main/java/com/fongmi/android/tv/web/HomeWebBridge.java
app/src/main/java/com/fongmi/android/tv/web/WebCall.java
app/src/main/java/com/fongmi/android/tv/web/CookieBridge.java
app/src/main/java/com/fongmi/android/tv/web/HeaderPolicy.java
```

必须改造点：

```text
1. Site 增加 homePage 字段、getter、setter、hasHomePage。
2. Site.objectFrom 里对 homePage 做 UrlUtil.convert。
3. Site Parcelable 读写必须同步增加 homePage。
4. VodFragment 或新版对应首页 Fragment 中增加 WebView 容器。
5. 首页加载逻辑改为：有 homePage 加载 WebView；否则走官方 homeContent。
6. 首页刷新按钮在 WebHome 可见时执行 WebView reload。
7. 返回键优先交给 WebView goBack。
8. Fragment onResume/onPause/onDestroyView 转发到 HomeWebController。
```

WebView 特性：

```text
1. JavaScript、DOM Storage、Database 开启。
2. MixedContent 允许。
3. 第三方 Cookie 允许。
4. 背景透明，用于适配 App 壁纸背景。
5. 注入 fm SDK。
6. 注入 CSS 变量 --fm-web-width、--fm-web-height、--fm-safe-bottom。
7. 支持锁屏/后台恢复后恢复渲染，不直接刷新回主页。
8. WebView render process gone 时重建 WebView 并尝试恢复当前 URL。
```

注意事项：

```text
1. 不要把 WebHome 写成只适配某个 demo HTML。
2. 底部高度应由 WebView 容器正确约束，不能要求每个 HTML 自己垫高。
3. WebView 恢复时不能直接 load 主页，否则用户在详情页、搜索结果页、盘搜结果页会丢状态。
4. 合并新版官方代码时，优先适配新版首页 Fragment 的结构，不要强行覆盖整个 Fragment 文件。
```

### 2. WebHome JavaScript SDK

功能目标：WebHome 页面通过统一 SDK 调用 App 能力，不直接依赖浏览器跨域能力。

核心桥接对象：

```text
Android 注入对象：fongmiBridge
JS 全局对象：fm / fongmi
```

主要能力：

```text
net.request       原生 OkHttp 请求，支持 headers、method、body、cookies、gzip/deflate 解码
net.resourceUrl   生成 App 内置资源转发 URL，用于图片、视频、跨域资源
player.playUrl    播放普通 URL
player.playVod    按 siteKey + vodId 进入 App 原生详情/播放
player.control    控制播放、暂停、停止、上一集、下一集、循环、重播
player.status     获取当前播放状态
app.search        打开 App 搜索，支持 direct
app.openLive      打开直播
app.openKeep      打开收藏
app.history       获取最近观看历史
pan.check         调用网盘有效性检测
pan.play          播放网盘链接，内部走 App push 机制
cache.get         获取本地缓存
cache.set         写入本地缓存
cache.del         删除本地缓存
device.info       获取设备信息
site.info         获取当前站点信息
config.info       获取当前配置源信息
navigation.back   WebHome 请求返回
navigation.reload WebHome 请求刷新
```

必须移植文件：

```text
app/src/main/java/com/fongmi/android/tv/web/HomeWebBridge.java
app/src/main/java/com/fongmi/android/tv/web/WebCall.java
app/src/main/java/com/fongmi/android/tv/web/CookieBridge.java
app/src/main/java/com/fongmi/android/tv/web/HeaderPolicy.java
app/src/main/java/com/fongmi/android/tv/server/process/WebResourceGateway.java
```

注意事项：

```text
1. pan.play 只保留统一 pan 命名，不保留 drive.check 这类旧草案兼容名。
2. pan.play 当前核心机制是复用 App push 播放链路，不在 SDK 内自行解析夸克、百度、UC 等网盘。
3. 磁力、电驴、普通网盘分享链接都可以走 pan.play，由 App 后续播放链路处理。
4. net.request 必须自己处理 gzip/deflate，避免 OkHttp 自动 gzip 严格校验导致异常。
5. resourceUrl 必须支持 CORS 响应头、Range、headers、credentials。
```

### 3. 内置 WebResource 网关

功能目标：为 WebHome 提供 App 内部 HTTP 资源转发，解决浏览器 CORS、Cookie、Header、图片加载、媒体 Range 等问题。

接口路径：

```text
/webResource
```

关键能力：

```text
1. 支持 GET、POST、PUT、PATCH、DELETE、HEAD、OPTIONS。
2. 支持 headers 参数。
3. 支持 credentials=include 时从 WebView CookieManager 读取 Cookie。
4. 支持 Range 请求，避免视频/大文件资源无法拖动。
5. 响应添加 Access-Control-Allow-Origin、Allow-Credentials、Allow-Methods、Allow-Headers、Expose-Headers。
6. 不透传 Connection、Transfer-Encoding、Keep-Alive、Content-Length。
```

必须移植文件：

```text
app/src/main/java/com/fongmi/android/tv/server/process/WebResourceGateway.java
app/src/main/java/com/fongmi/android/tv/server/Nano.java
app/src/main/java/com/fongmi/android/tv/web/CookieBridge.java
app/src/main/java/com/fongmi/android/tv/web/HeaderPolicy.java
```

### 4. 网盘有效性检测 pan.check

功能目标：App 内置网盘有效性检测服务，供 WebHome 和自定义爬虫调用。检测只判断链接有效性，不在 App 原生搜索结果中自动检测排序。

HTTP 接口：

```text
POST http://127.0.0.1:{serverPort}/pan/check
```

WebHome SDK：

```js
await fm.pan.check({ items: [{ url, type, name }] })
```

必须移植文件：

```text
app/src/main/java/com/fongmi/android/tv/bean/drive/DriveCheckItem.java
app/src/main/java/com/fongmi/android/tv/bean/drive/DriveCheckRequest.java
app/src/main/java/com/fongmi/android/tv/bean/drive/DriveCheckResponse.java
app/src/main/java/com/fongmi/android/tv/bean/drive/DriveCheckResult.java
app/src/main/java/com/fongmi/android/tv/server/process/DriveCheck.java
app/src/main/java/com/fongmi/android/tv/service/DriveCheckService.java
app/src/main/java/com/fongmi/android/tv/service/DriveMobileCrypto.java
app/src/main/java/com/fongmi/android/tv/setting/Setting.java
app/src/main/java/com/fongmi/android/tv/server/Nano.java
```

行为要求：

```text
1. 设置开关默认开启。
2. 开关关闭时 HTTP 接口返回 403，WebHome 不应继续检测排序。
3. 一批请求内部最多 10 个并发，超过 10 个自动分批。
4. 只检测支持的网盘类型，不支持的类型直接返回 unsupported/idle。
5. 成功结果缓存不超过 1 小时，避免链接失效后长期误判有效。
6. 参数字段保持 type，不要改成 disk_type、driveType 等其他名字。
```

注意：Java 内部包名中仍使用 `drive` 目录名，这是历史实现命名；对外 API 统一使用 `pan`，参数字段保持 `type`。

### 5. pan.play 网盘播放入口

功能目标：WebHome 或页面盘搜结果点击后，通过统一 `pan.play` 调用 App 播放网盘、磁力、电驴等链接。

SDK 示例：

```js
await fm.pan.play({ url, title, type })
```

实现要求：

```text
1. pan.play 不自行实现各网盘直链解析。
2. pan.play 内部使用 App 现有 push 播放机制。
3. 如果传入 push:// 前缀，内部去掉前缀后再交给 push 链路。
4. title 为空时使用 url 作为标题。
5. 日志记录 route、type、title、url，便于调试。
```

涉及文件：

```text
app/src/main/java/com/fongmi/android/tv/web/HomeWebBridge.java
app/src/main/java/com/fongmi/android/tv/player/extractor/Push.java
app/src/main/java/com/fongmi/android/tv/player/PlayerManager.java
```

注意：不要再恢复早期草案中的 `drive.play`、`drive.check`、`detect/list/resolve/auth/login` 等接口。

### 6. 通用 debug 日志

功能目标：提供一个 App 内置调试日志开关。开启后记录 WebHome、SDK、App 服务接口、OkHttp、播放链路、push 等关键行为；关闭时清空日志。

HTTP 接口：

```text
GET /debug/logs
```

必须移植文件：

```text
catvod/src/main/java/com/github/catvod/crawler/DebugLogStore.java
catvod/src/main/java/com/github/catvod/crawler/SpiderDebug.java
catvod/src/main/java/com/github/catvod/net/CookieStore.java
catvod/src/main/java/com/github/catvod/net/interceptor/ResponseInterceptor.java
app/src/main/java/com/fongmi/android/tv/server/process/DebugLogs.java
app/src/main/java/com/fongmi/android/tv/server/Nano.java
app/src/main/java/com/fongmi/android/tv/setting/Setting.java
```

记录范围：

```text
1. WebHome SDK invoke。
2. WebHome net.request。
3. 内置 HTTP server 请求。
4. pan.check 调用和异常。
5. WebResource 请求和异常。
6. OkHttp 请求、响应、失败。
7. 播放 start、state、error、timeout。
8. push extractor 调用。
```

行为要求：

```text
1. 默认关闭。
2. 关闭时不弹 Toast。
3. 关闭时清空内存日志。
4. 不限制 2000 条这类固定上限；关闭清理即可。
5. 打开时启动内置 server，并用浏览器打开 /debug/logs。
6. /debug/logs 可以下载或复制日志。
```

### 7. 增强功能设置入口

功能目标：设置中新增统一“增强功能”入口，用于集中管理网盘检测、debug 日志，以及未来脚本扩展等能力，避免设置页变乱。

必须移植文件：

```text
app/src/main/java/com/fongmi/android/tv/setting/Setting.java
app/src/mobile/java/com/fongmi/android/tv/ui/fragment/SettingFragment.java
app/src/mobile/java/com/fongmi/android/tv/ui/fragment/SettingEnhanceFragment.java
app/src/mobile/res/layout/fragment_setting.xml
app/src/mobile/res/layout/fragment_setting_enhance.xml
app/src/leanback/java/com/fongmi/android/tv/ui/activity/SettingActivity.java
app/src/leanback/java/com/fongmi/android/tv/ui/activity/SettingEnhanceActivity.java
app/src/leanback/res/layout/activity_setting.xml
app/src/leanback/res/layout/activity_setting_enhance.xml
app/src/main/res/values/strings.xml
app/src/main/res/values-zh-rCN/strings.xml
app/src/main/res/values-zh-rTW/strings.xml
```

UI 要求：

```text
1. 参考播放设置的入口形式。
2. 功能项是开关，不使用勾选框。
3. 手机端和电视端都要有入口。
4. 电视端焦点默认落到第一个开关。
5. 网盘检测默认打开。
6. debug 日志默认关闭。
```

### 8. 移除启动自动更新弹窗

功能目标：App 打开后不自动弹出更新版本弹窗。

涉及文件：

```text
app/src/mobile/java/com/fongmi/android/tv/ui/activity/HomeActivity.java
app/src/leanback/java/com/fongmi/android/tv/ui/activity/HomeActivity.java
```

行为要求：

```text
1. 移除或禁用 HomeActivity 初始化阶段的 Updater.create().start(this)。
2. 不删除 Updater 类，避免其他入口或未来手动更新能力受影响。
```

### 9. 最近观看入口和刷新能力

功能目标：WebHome 页面可以通过 SDK 获取 App 最近观看；原生首页在 WebHome 模式下有刷新能力。

涉及文件：

```text
app/src/main/java/com/fongmi/android/tv/web/HomeWebBridge.java
app/src/mobile/java/com/fongmi/android/tv/ui/fragment/VodFragment.java
app/src/mobile/res/menu/menu_vod.xml
app/src/mobile/res/drawable/ic_action_refresh.xml
app/src/leanback/java/com/fongmi/android/tv/ui/activity/HomeActivity.java
app/src/leanback/res/layout/activity_home.xml
```

行为要求：

```text
1. fm.app.history 返回 History.get()。
2. WebHome 可见时，刷新按钮执行 WebView reload。
3. 原生首页可见时，刷新按钮执行 homeContent。
```

## 可选移植内容

### demo 页面

WebHTV 5.4.1 发布版新增：

```text
demo/nostr.html
demo/check.html
```

这些页面用于演示 WebHome、Nostr 推荐、盘搜、pan.check、pan.play 等能力。它们不是 App 编译必需文件，但建议保留在开源仓库，方便开发者参考。

### 开发文档

WebHTV 5.4.1 发布版新增：

```text
docs/应用完整开发文档.md
```

同时删除了官方分散文档：

```text
docs/CONFIG.md
docs/LIVE.md
docs/LOCAL.md
docs/SPIDER.md
```

后续建议只维护：

```text
docs/应用完整开发文档.md
docs/开发分支与官方同步流程.md
docs/WebHTV二开差异移植清单.md
```

### 构建脚本

WebHTV 5.4.1 发布版新增：

```text
build_test_min.sh
```

它不是功能必需文件，可按目标分支实际需要保留。

## 不应盲目移植的内容

WebHTV 5.4.1 发布版相对官方基线删除了大量官方内置模块源码和 AAR：

```text
forcetech/
hook/
jianpian/
thunder/
tvbus/
zlive/
app/libs/*.aar
```

这些属于开源仓库整理和闭源依赖处理，不是 WebHome 功能本身。移植到 5.4.9、5.5.2 时不要无脑删除，处理规则如下：

```text
1. 如果目标分支需要 clone 后直接编译，必须保留或补齐构建所需 AAR。
2. 如果官方目标版本已经调整依赖结构，优先遵循官方目标版本。
3. 不要因为 5.4.1 WebHTV tag 删除过这些目录，就在新版本里直接删除。
4. Go 二进制、Spider Jar、P2P、播放器相关依赖优先保持官方目标版本原样。
```

特别注意：如果目标是修复 5.4.9 Go 二进制执行能力，必须避免把后续版本的播放器、Media3、nextlib、依赖管理变更混入官方 5.4.9 底座。5.4.9 官方代码本身已经引用 Media3/nextlib 时，只接入该版本需要的本地 Maven 依赖，不替换官方播放链路。

## 5.4.9 移植建议

推荐做法：

```text
1. 从官方 a00ebd257 创建干净分支。
2. 不使用当前已经混入后续代码的旧 webhtv-5.4.9 分支作为底座。
3. 按本文档逐组移植 WebHTV 二开能力。
4. 优先保持官方 5.4.9 的 catvod、shell、Path、JarLoader、Spider、player 依赖结构。
5. 移植完成后再补 clone-buildable 所需 AAR 和 third_party/maven，但不要引入非 5.4.9 必需的新播放器依赖。
```

推荐迁移顺序：

```text
1. Site.homePage 数据模型。
2. WebHome Controller 和 Bridge。
3. 手机端 VodFragment WebHome 接入。
4. 电视端 HomeActivity WebHome 接入。
5. Nano 增加 WebResource、pan.check、debug logs process。
6. pan.check 服务和 bean。
7. debug 日志能力。
8. 增强功能设置页。
9. 移除自动更新弹窗。
10. demo 和文档。
11. clone-buildable 依赖补齐。
12. 构建和真机回归。
```

必须验证：

```text
1. 官方 5.4.9 原本可执行的 Go 二进制仍可执行。
2. Spider Jar 的 goProxy、shell、chmod、Path 行为没有被 WebHTV 移植破坏。
3. 手机端 WebHome 可加载透明背景主页。
4. 电视端原生首页不异常，遥控器返回正常。
5. pan.check 开关默认开启，关闭后接口返回 403。
6. pan.play 能走 push 播放链路。
7. debug 日志默认关闭，打开后 /debug/logs 可访问。
8. mobile arm64、leanback arm64、leanback armeabi-v7a 都能 release 构建。
```

## 5.4.9 实施记录

本次干净迁移已经按以下方式完成：

```text
底座：官方 FongMi/TV a00ebd257，versionName 5.4.9
迁移来源：WebHTV tag v5.4.1-202605240217
工作分支：webhtv-5.4.9
目标：保留官方 5.4.9 Go/Shell/Spider Jar 行为，只叠加 WebHTV 开放能力
```

实际适配点：

```text
1. 5.4.9 的 Setting 类路径是 app/src/main/java/com/fongmi/android/tv/setting/Setting.java。
2. 手机端 HomeActivity 保留官方 SettingDanmakuFragment，并新增 SettingEnhanceFragment。
3. 电视端 SettingActivity 保留官方弹幕设置入口，并新增增强功能入口。
4. WebHome 的 player.control loop 在 5.4.9 中映射到 PlaybackService.dispatchRepeat()。
5. 官方 5.4.9 播放器使用 Media3/nextlib，本分支只补齐对应本地 Maven 依赖和 PreferRenderersFactory 适配，不替换官方 Go/Shell/JarLoader 相关代码。
6. 本地构建需要 third_party/maven，settings.gradle 增加该仓库；去掉重复的旧 rtmp-client 依赖，避免与 media3-datasource-rtmp 重复类冲突。
7. local.properties 不再是 release 构建硬依赖；没有正式签名配置时回退 debug signing，方便 clone 后直接构建验证。
```

Go 二进制保护核对：

```text
catvod/src/main/java/com/github/catvod/utils/Shell.java：相对官方 5.4.9 未改动
catvod/src/main/java/com/github/catvod/utils/Path.java：相对官方 5.4.9 未改动
app/src/main/java/com/fongmi/android/tv/api/loader/JarLoader.java：相对官方 5.4.9 未改动
app/src/main/java/com/fongmi/android/tv/utils/Util.java：相对官方 5.4.9 未改动
```

已验证构建：

```bash
bash gradlew :app:assembleMobileArm64_v8aRelease
bash gradlew :app:assembleLeanbackArm64_v8aRelease
bash gradlew :app:assembleLeanbackArmeabi_v7aRelease
```

三个 release 任务均已通过，产物路径：

```text
app/build/outputs/apk/mobileArm64_v8a/release/mobile-arm64_v8a.apk
app/build/outputs/apk/leanbackArm64_v8a/release/leanback-arm64_v8a.apk
app/build/outputs/apk/leanbackArmeabi_v7a/release/leanback-armeabi_v7a.apk
```

## 5.5.2 移植建议

5.5.2 或更高版本移植时，不要使用 5.4.9 移植分支直接反向合并。推荐以官方目标版本为底座，重新按本文档移植。

需要重点检查：

```text
1. 首页 UI 类名、Fragment 结构是否变化。
2. 播放器是否从官方版本引入了 Media3/nextlib 新结构。
3. Setting 页手机端和电视端入口是否变化。
4. NanoHTTPD server process 注册方式是否变化。
5. OkHttp 拦截器是否变化。
6. Site Parcelable 字段顺序是否变化。
7. History 数据结构是否变化。
8. Push 播放链路是否变化。
9. targetSdk 是否变化。
```

原则：

```text
1. 目标版本官方能正常工作的 Go 二进制能力必须优先保持。
2. WebHTV 只移植开放能力，不替换目标版本的底层播放器和 Spider 机制。
3. 如果官方目标版本已经修复或重构某处代码，应把 WebHTV 逻辑适配进去，而不是整文件覆盖。
4. 每完成一组能力都应该能单独编译，避免最后集中爆冲突。
```

## 快速核对清单

必须新增或修改的核心文件：

```text
app/src/main/java/com/fongmi/android/tv/bean/Site.java
app/src/main/java/com/fongmi/android/tv/setting/Setting.java
app/src/main/java/com/fongmi/android/tv/web/CookieBridge.java
app/src/main/java/com/fongmi/android/tv/web/HeaderPolicy.java
app/src/main/java/com/fongmi/android/tv/web/HomeWebBridge.java
app/src/main/java/com/fongmi/android/tv/web/HomeWebController.java
app/src/main/java/com/fongmi/android/tv/web/WebCall.java
app/src/main/java/com/fongmi/android/tv/server/Nano.java
app/src/main/java/com/fongmi/android/tv/server/process/Action.java
app/src/main/java/com/fongmi/android/tv/server/process/DebugLogs.java
app/src/main/java/com/fongmi/android/tv/server/process/DriveCheck.java
app/src/main/java/com/fongmi/android/tv/server/process/WebResourceGateway.java
app/src/main/java/com/fongmi/android/tv/service/DriveCheckService.java
app/src/main/java/com/fongmi/android/tv/service/DriveMobileCrypto.java
app/src/main/java/com/fongmi/android/tv/bean/drive/DriveCheckItem.java
app/src/main/java/com/fongmi/android/tv/bean/drive/DriveCheckRequest.java
app/src/main/java/com/fongmi/android/tv/bean/drive/DriveCheckResponse.java
app/src/main/java/com/fongmi/android/tv/bean/drive/DriveCheckResult.java
catvod/src/main/java/com/github/catvod/crawler/DebugLogStore.java
catvod/src/main/java/com/github/catvod/crawler/SpiderDebug.java
catvod/src/main/java/com/github/catvod/net/CookieStore.java
catvod/src/main/java/com/github/catvod/net/interceptor/ResponseInterceptor.java
```

手机端 UI 文件：

```text
app/src/mobile/java/com/fongmi/android/tv/ui/activity/HomeActivity.java
app/src/mobile/java/com/fongmi/android/tv/ui/activity/SearchActivity.java
app/src/mobile/java/com/fongmi/android/tv/ui/activity/VideoActivity.java
app/src/mobile/java/com/fongmi/android/tv/ui/fragment/VodFragment.java
app/src/mobile/java/com/fongmi/android/tv/ui/fragment/SettingFragment.java
app/src/mobile/java/com/fongmi/android/tv/ui/fragment/SettingEnhanceFragment.java
app/src/mobile/res/layout/activity_home.xml
app/src/mobile/res/layout/fragment_vod.xml
app/src/mobile/res/layout/fragment_setting.xml
app/src/mobile/res/layout/fragment_setting_enhance.xml
app/src/mobile/res/menu/menu_vod.xml
app/src/mobile/res/drawable/ic_action_refresh.xml
```

电视端 UI 文件：

```text
app/src/leanback/AndroidManifest.xml
app/src/leanback/java/com/fongmi/android/tv/ui/activity/HomeActivity.java
app/src/leanback/java/com/fongmi/android/tv/ui/activity/SearchActivity.java
app/src/leanback/java/com/fongmi/android/tv/ui/activity/SettingActivity.java
app/src/leanback/java/com/fongmi/android/tv/ui/activity/SettingEnhanceActivity.java
app/src/leanback/java/com/fongmi/android/tv/ui/activity/VideoActivity.java
app/src/leanback/res/layout/activity_home.xml
app/src/leanback/res/layout/activity_setting.xml
app/src/leanback/res/layout/activity_setting_enhance.xml
```

资源和文档：

```text
app/src/main/res/values/strings.xml
app/src/main/res/values-zh-rCN/strings.xml
app/src/main/res/values-zh-rTW/strings.xml
demo/nostr.html
demo/check.html
docs/应用完整开发文档.md
README.md
```

## Go 二进制相关保护点

如果移植后 Go 二进制不能执行，而官方目标版本可以执行，优先检查以下内容：

```text
1. 是否引入了目标版本之外的 Media3/nextlib/播放器依赖变更。
2. 是否改动了 catvod/src/main/java/com/github/catvod/utils/Shell.java。
3. 是否改动了 catvod/src/main/java/com/github/catvod/utils/Path.java 的 chmod、setExecutable、文件目录逻辑。
4. 是否改动了 JarLoader、BaseLoader、Spider 初始化流程。
5. 是否改动了 app 的 targetSdk。
6. 是否把官方 5.5.x 的依赖结构混进 5.4.9。
7. 是否误删了某些官方内置 AAR 或 so。
8. 是否混淆规则改变导致 Spider Jar 反射调用异常。
```

典型执行权限失败一般表现为：

```text
Permission denied
No such file or directory
Exec format error
Cannot run program
```

如果错误是 JSON 类型不匹配，例如：

```text
JSONArray cannot be converted to JSONObject
```

这通常不是二进制执行权限问题，而是 Spider Jar、Go proxy 入参或返回值格式问题。
