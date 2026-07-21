# EXO 高码率无感动态网络保护与保调播放最佳实践方案

日期：2026-07-22

代码基线：main-2 / playback-optimization-item-61

最终实现目标 tag：playback-optimization-item-62

Media3：1.11.0-alpha01-fongmi

适用场景：4K/5K、HEVC/H.265、大体积 MKV/REMUX、网盘 Range、Go 二进制代理、实际持续吞吐略低于媒体消费码率。

## 一、最终结论

该能力应定义为“允许自动判断的无感动态保护”，而不是“开启后整场固定降速”。

最终状态模型：

    休眠（不改变任何现有功能）
      → 观察缓冲与网络证据
      → 确认存在可在无感范围内补偿的轻微吞吐缺口
      → 连续、小幅、保调地降低播放速度
      → 缓冲能力恢复
      → 平滑回到 1.00x
      → 自动退出并重新休眠

核心原则：

1. 设置开启只代表允许控制器观察和判断。
2. 未介入时不得改变播放器行为。
3. 不得为了启用而关闭 tunneling、音频直通、电视刷新率匹配或分辨率直出。
4. 用户已启用 tunneling 或音频直通时，控制器保持休眠。
5. 自动速度严格限制在 0.97～1.00x。
6. 如果稳定播放所需速度低于 0.97x，控制器不得继续偷偷降速。
7. Go 二进制代理和 127.0.0.1 播放链路必须保留，App 不直连网盘 SDK/CDN。
8. 对 Go/本地代理链路，不得把 Exo 从 127.0.0.1 读取到的速度当成真实上游吞吐。

## 二、为什么必须重做 item-60/61 原型

item-60/61 验证了 Media3 PlaybackParameters 与 Sonic 保调变速可以工作，但实现存在三类结构性问题：

| 问题 | 原型行为 | 最终修正 |
|---|---|---|
| 输出功能被提前改变 | 设置开启即禁用 tunneling | 保留 tunneling；检测到用户启用时控制器休眠 |
| 音频能力被提前改变 | 设置开启即强制 PCM，关闭直通 | 保留音频直通；检测到用户启用时控制器休眠 |
| 电视直出被提前改变 | 设置开启即恢复/跳过 EXO Display.Mode | 完全移除该关联，帧率与分辨率直出照常工作 |
| 调速目标离散 | 每次固定变化 0.02x | 根据可支撑速度连续计算，保留小步限幅 |
| 模式范围过宽 | 自动下探 0.95/0.90/0.85x | 无感自动统一限制为 0.97～1.00x |
| 上游带宽判断不严谨 | 容易误用本地代理吞吐 | loopback 只用缓冲斜率；仅直连远端才使用 Media3 带宽估计 |

因此 item-60/61 应视为实验原型，最终实现由 item-62 替代其副作用和固定档位设计。

## 三、公开资料与开源实现调研

### 3.1 Media3 默认速度控制：0.97～1.03 的工程边界

来源：

- https://github.com/androidx/media/blob/main/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/DefaultLivePlaybackSpeedControl.java

关键事实：

- 默认最小播放速度为 0.97x。
- 默认最大播放速度为 1.03x。
- 使用最小更新间隔避免频繁 rate change。
- 使用比例控制器，而不是固定几个目标档位。
- 对最小可达延迟使用指数平滑。
- 使用平滑偏差的 3 倍作为安全边界。

虽然该实现服务于直播延迟控制，不能直接套到高码率点播，但它证明 0.97x、平滑、最小更新间隔、安全边界是 Media3 自身采用的成熟工程模式。

### 3.2 Media3 Sonic 与 DefaultAudioSink：保调能力和兼容边界

来源：

- https://github.com/androidx/media/blob/main/libraries/common/src/main/java/androidx/media3/common/audio/SonicAudioProcessor.java
- https://github.com/androidx/media/blob/main/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/audio/DefaultAudioSink.java

关键事实：

- SonicAudioProcessor 独立设置 speed 与 pitch。
- PlaybackParameters.withSpeed() 保留 pitch，当前项目可用 pitch=1.0 做保调变速。
- DefaultAudioSink 默认音频处理链包含 Sonic。
- AudioProcessor 只支持 PCM，不支持 passthrough/offload。
- Media3 源码明确说明 tunneling 下不能使用会改变音频时长的处理器，因此无法依靠 Sonic 调速。
- DefaultAudioSink 默认关闭 offload；当前项目也没有主动开启 offload。

结论：不能为了网络保护强制重建输出链。正确做法是仅在当前天然为 PCM、非 tunneling 的会话中介入。

### 3.3 Media3 issues：offload 调速不可靠

来源：

- https://github.com/androidx/media/issues/133
- https://github.com/androidx/media/issues/1110
- https://github.com/androidx/media/issues/2038

共同结论：

- offload 可能失去 playback speed 等能力。
- 部分 Samsung、Pixel 和 Android 版本即使声明需要支持速度变化，实际仍可能不生效。
- PlaybackParameters 可能显示已设置，但真实播放仍为 1.00x。

因此最终资格门控必须保守，不应在压缩音频直通/offload 路径上假设保调调速可靠。

### 3.4 dash.js CatchupController：状态机、死区和 LoL+ 非线性控制

来源：

- https://github.com/Dash-Industry-Forum/dash.js/blob/development/src/streaming/controllers/CatchupController.js

可借鉴点：

- 播放暂停、Seek、stalled 时不盲目调速。
- 缓冲低于安全阈值时才进入 buffer-based 控制。
- LoL+ 使用连续非线性函数计算新速度。
- 速度差小于阈值时不写入，避免频繁 ratechange。
- 恢复到安全区后回到 1.00x。

本项目采用相同思想，但把直播延迟误差替换为点播缓冲斜率和耗尽时间。

### 3.5 hls.js 与 Shaka：快慢双 EWMA 的保守融合

来源：

- https://github.com/video-dev/hls.js/blob/master/src/utils/ewma-bandwidth-estimator.ts
- https://github.com/shaka-project/shaka-player/blob/main/lib/abr/ewma_bandwidth_estimator.js

两者都维护快、慢两个 EWMA，并取两者较小值：

- 网络变差时快速下调。
- 网络恢复时缓慢上调。
- 避免短时峰值造成过度乐观。

最终实现把这一原则用于缓冲斜率：

    保守缓冲斜率 = min(快速 EWMA, 慢速 EWMA)

### 3.6 主观感知论文

来源：

- Smooth Control of Adaptive Media Playout for Video Streaming
  - DOI: 10.1109/TMM.2009.2030543
- Online Buffer Fullness Estimation Aided Adaptive Media Playout for Video Streaming
  - DOI: 10.1109/TMM.2011.2160158
- Subjective Assessment of Adaptive Media Playout for Video Streaming
  - DOI: 10.1109/QOMEX.2019.8743320
- Human Perception of Altered Video Speed
  - DOI: 10.1163/22134468-bja10116

关键结论：

- 自适应播放速度可用于控制缓冲和播放延迟。
- 缓冲 fullness 估计可以辅助动态速度控制。
- 2019 年主观研究指出，既往工作低估了速度变化对感知质量的影响，一般不应偏离原速超过 10%。
- 2024 年研究进一步确认，用户对速度变化存在系统性感知偏差，且不同内容、动作与用户差异明显。

项目最终选择 0.97x，而不是把 0.90x 或 0.85x 宣称为无感。

### 3.7 Android 电视刷新率官方建议

来源：

- https://developer.android.com/media/optimize/performance/frame-rate

关键事实：

- Surface.setFrameRate() 只是向系统表达内容帧率偏好，系统不保证切换。
- 非无缝刷新率切换可能黑屏。
- 视频 App 应按内容和用户选择决定是否允许非无缝切换。
- App 必须在系统没有切换刷新率时仍能正常工作。

结论：动态网络保护不能接管或关闭电视帧率匹配。当前 EXO 输出模式继续按原始媒体帧率工作，保护控制器不频繁切换 Display.Mode。

## 四、物理边界

稳定播放的近似条件：

    播放速度 <= 实际持续吞吐 / 实际媒体消费码率

缓冲斜率提供了不依赖上游鉴权和代理内部实现的等价估计：

    缓冲可支撑速度 = 当前播放速度 + 缓冲秒数变化率

例：

- 当前速度 1.00x。
- 20 秒内缓冲减少 0.4 秒。
- 缓冲斜率约为 -0.02 秒/秒。
- 可支撑速度约为 0.98x。

如果估算结果为 0.92x，说明缺口超过无感范围。此时 0.97x 只能延缓重缓冲，不能消除长期缺口，控制器不应伪装成已经解决。

## 五、最终资格矩阵

只有全部满足才进入观察：

| 条件 | 要求 | 不满足时 |
|---|---|---|
| 播放核心 | EXO | 休眠 |
| 内容类型 | 点播 | 休眠 |
| 用户速度 | 1.00x | 用户设置优先 |
| 速度命令 | COMMAND_SET_SPEED_AND_PITCH 可用 | 休眠 |
| tunneling | 用户未启用 | 保留 tunneling，休眠 |
| 音频直通 | 用户未启用 | 保留直通，休眠 |
| 电视输出 | 不作为阻断条件 | 保持原帧率/分辨率直出 |
| Go 代理 | 保持原链路 | 不绕过、不直连 |

注意：“设置开启”不是资格条件全部成立的同义词。它只是允许控制器执行上述判断。

## 六、输入信号与可信度

### 6.1 主信号：缓冲媒体时间斜率

采样条件：

- Player.STATE_READY
- player.isPlaying()
- player.isLoading()
- 非暂停
- 非 Seek
- 非切轨
- 非重缓冲过程

实现：

- 5 秒调度一次。
- 最少 10 秒趋势窗口后才决策。
- 快 EWMA 半衰期 6 秒。
- 慢 EWMA 半衰期 20 秒。
- 取二者较小值，下降快、恢复慢。
- 单次异常斜率钳制在 -2.0～+5.0 秒/秒。

### 6.2 风险信号：缓冲耗尽时间

当缓冲下降时：

    timeToEmpty = 当前缓冲秒数 / 缓冲下降速度

风险成立需满足：

- 缓冲斜率 <= -0.015 秒/秒；并且
- 缓冲 <= 45 秒，或预计 240 秒内耗尽。

这样可避免在已有数分钟安全缓冲时过早干预。

### 6.3 辅助信号：真实远端带宽 / 媒体消费码率

仅 DIRECT_REMOTE_HTTP 使用：

    网络可支撑速度 = 0.90 × Media3 带宽估计 / 保守媒体码率

媒体码率优先级：

1. Format 标称码率。
2. 文件总长度 / 总时长。
3. content-length 与 byte-slope 混合估计。
4. P90 / 1.25×P50 保守观测。

仅使用 medium/high confidence。

对于 APP_LOCAL_SERVICE 和 EXTERNAL_LOOPBACK_PROXY：

- 不使用 Media3 bandwidthEstimate 参与调速。
- 原因是该值描述 Exo 到 127.0.0.1 的本地腿，不能证明 Go/CDN 上游能力。
- 继续依赖缓冲斜率；未来若 Go 二进制提供上游 telemetry，可作为额外保守上限接入。

## 七、连续动态控制算法

### 7.1 支撑能力融合

    bufferSupported = currentSpeed + conservativeBufferSlope

直连远端且网络估计可信时：

    supported = min(bufferSupported, networkSupported)

Go/loopback 时：

    supported = bufferSupported

### 7.2 进入条件

必须同时满足：

1. 资格矩阵通过。
2. 趋势窗口不少于 10 秒。
3. 风险持续确认 10 秒。
4. supported 位于 0.97～1.00x。
5. 目标速度变化超过 0.002x 死区。

### 7.3 目标速度

缓冲下降时：

    desired = clamp(supported - 0.002, 0.97, 1.00)

不是固定跳到 0.98/0.95，而是可产生 0.992、0.984、0.979 等连续目标。

限幅：

- 每 10 秒最多下降 0.008x。
- 每 10 秒最多恢复 0.004x。
- 写入精度 0.001x。
- 下降比恢复快，避免刚恢复就反复卡顿。

### 7.4 自动退出

已介入后，满足以下条件持续 25 秒才恢复：

- 可支撑速度 >= 0.998x。
- 缓冲 >= 30 秒。

或者：

- 加载器因满缓冲停止。
- 缓冲 >= 50 秒并持续 30 秒。

速度平滑回到 1.00x 后，状态回到观察，不再改变播放。

### 7.5 超出无感范围

当 supported < 0.968x：

- 未介入时保持 1.00x，不偷偷降到 0.95/0.90/0.85。
- 已介入时平滑退出。
- 状态标记为“超出无感范围”，用于诊断。
- 不无限降低速度。

## 八、暂停、Seek、重缓冲与切换行为

| 事件 | 行为 |
|---|---|
| 用户暂停 | 立即恢复用户 1.00x，清除趋势 |
| Seek/跳集/切轨 | 恢复 1.00x，清除旧样本，重新观察 |
| 重缓冲 | 保留当前无感保护速度，但清除旧趋势；恢复 READY 后重新采样 |
| 停止/结束/新媒体 | 恢复用户速度，完整重置 |
| 用户手动改倍速 | 自动控制退出，用户速度绝对优先 |
| 中途启用 tunneling/直通 | 自动恢复 1.00x 并休眠 |

## 九、当前代码实施映射

### ✅ 已修正的副作用

- ✅ ExoTunnelingPolicy 不再因网络保护设置而禁用 tunneling。
- ✅ ExoUtil.buildAudioSink() 不再因网络保护设置而强制 PCM。
- ✅ PlaybackActivity 不再因网络保护设置而跳过电视 Display.Mode。
- ✅ 用户启用 tunneling 或音频直通时由资格门控休眠。

### ✅ 已实现的动态控制

- ✅ ExoNetworkGuardEligibility：非破坏性资格矩阵。
- ✅ ForwardBufferTrend：快/慢双 EWMA 保守缓冲斜率。
- ✅ ExoNetworkGuardController：进入确认、耗尽时间、死区、滞后、连续目标、下降/恢复非对称限幅。
- ✅ PlayerManager：自动调度、暂停/Seek/重缓冲/停止生命周期处理。
- ✅ DIRECT_REMOTE_HTTP 可融合可信带宽与媒体码率。
- ✅ loopback 不使用本地带宽误判 Go/CDN 上游。
- ✅ 播放性能“自动”默认允许无感动态判断。
- ✅ OSD 诊断可显示观察、休眠原因、保护、恢复与超出无感范围。

### ✅ 不变的播放链

- ✅ Go 二进制代理继续存在。
- ✅ 127.0.0.1 HTTP 播放链继续存在。
- ✅ App 不增加网盘 SDK 鉴权。
- ✅ App 不直连网盘 CDN Range。
- ✅ 原有硬解、SurfaceView、帧率匹配、电视分辨率直出逻辑不被该功能重写。

## 十、收益、代价与适用边界

### 收益

- 对约 0～3% 的持续吞吐小缺口，可直接消除缓冲持续下降。
- 对轻微短时波动，可延长缓冲安全时间并减少重缓冲。
- 不需要更换清晰度、重建播放器或绕过 Go 代理。
- 未介入时没有 Sonic 处理开销，也不改变输出能力。
- 动态退出后恢复原始 1.00x。

### 代价

- 介入时 PCM 音频经过 Sonic，会增加少量 CPU。
- 0.97x 会使两小时内容增加约 3 分 43 秒实际观看时间。
- 某些节拍强、熟悉人声或高运动内容仍可能被极敏感用户察觉。
- 不能解决 5%、10% 或更大的长期带宽缺口。
- 不能替代更大缓冲、稳定网络、低码率版本或真正的上游吞吐优化。

## 十一、测试与验收

### 已有专项单元测试

- ExoNetworkProtectionPolicyTest
- ExoNetworkGuardEligibilityTest
- ExoTunnelingPolicyTest
- ForwardBufferTrendTest
- ExoNetworkGuardControllerTest
- AutoPreloadPolicyTest

覆盖：

- tunneling/直通不被关闭。
- 用户倍速和不支持调速的设备保持休眠。
- 小缺口持续确认后连续降速。
- 超出 0.97 无感范围时不介入。
- 调整冷却避免频繁 ratechange。
- 缓冲恢复后慢速回到 1.00x。
- 满缓冲时恢复。
- direct 网络估计只能让结果更保守。
- 重缓冲后没有新趋势时不盲目降速。

### 真机验收指标

1. 隧道模式开启：网络保护显示休眠，隧道仍按原逻辑工作。
2. 音频直通开启：网络保护显示休眠，直通仍按原逻辑工作。
3. 电视帧率/分辨率直出：开启网络保护后仍正常切换。
4. 普通 PCM EXO 点播：健康网络全程保持 1.00x。
5. 轻微受限网络：确认后出现 0.99x 左右连续值，缓冲下降停止。
6. 网络恢复：至少确认 25 秒后缓慢回到 1.00x。
7. 严重缺口：保持 1.00x并报告超出无感范围，不下探 0.95/0.90。
8. 暂停、Seek、切集：立即恢复 1.00x并重新观察。
9. 对比 CPU、音频 underrun、视频 droppedFrames、重缓冲次数与总时长。

## 十二、后续可选增强

1. Go 二进制增加只读上游 telemetry：
   - 上游实际读取字节。
   - 上游请求耗时。
   - Range 命中与重试。
   - 快/慢 EWMA。
   - 仅提供观测，不改变 App 鉴权和播放路径。
2. 加入 Sonic CPU、AudioTrack underrun 和热控资格门控。
3. 建立设备/ROM 黑名单。
4. 统计不同内容类型下 0.97x 的主观感知反馈。

这些增强不能绕过 Go，也不能扩大自动速度范围。
