# EXO 高码率网络保护自适应降速与保调播放方案

日期：2026-07-21

代码基线：`main-2` / `playback-optimization-item-55`

Media3：`1.11.0-alpha01-fongmi`

适用场景：4K/5K、HEVC/H.265、大体积 MKV/REMUX、网盘 Range、Go 本地代理、实际吞吐略低于视频消费码率

## 一、结论先行

针对“视频消费码率略高于实际网络吞吐，缓冲持续下降并反复重缓冲”的场景，可以增加一个可选的“网络保护播放”模式：

```text
正常播放：1.00x
网络保护：根据缓冲趋势在 0.95～1.00x 或 0.90～1.00x 之间渐进调整
激进保护：用户主动允许最低 0.85x
```

这不是凭空创造带宽。它只能降低单位墙钟时间内消耗的媒体数据量，适合解决 5%～15% 左右的持续缺口或短时吞吐波动；如果网络长期只有视频码率的 60%～70%，即使 0.90x 也无法稳定。

推荐的源码组合是：

1. 使用 Media3 已内置的 `PlaybackParameters` + `SonicAudioProcessor` 做保调变速。
2. 参考 dash.js `CatchupController` 的缓冲安全区、滞后、步进和恢复逻辑。
3. 以“缓冲媒体时间斜率”为主要控制信号，不把 `127.0.0.1` 瞬时吞吐误认为 CDN/Go 代理真实吞吐。
4. 对 tunneling、音频直通/offload、显示刷新率匹配设置明确边界。

本方案不绕过 Go 二进制代理，不增加 App 直连网盘 SDK/CDN 的路径，也不引入 App 侧光流插帧。

## 二、物理边界与收益估算

稳定播放的理论条件近似为：

```text
播放速度 ≤ 实际持续吞吐 ÷ 视频实际消费码率
```

示例：

| 视频实际消费码率 | 网络持续吞吐 | 理论速度上限 | 适用判断 |
|---:|---:|---:|---|
| 65Mbps | 60Mbps | 0.923x | 0.90～0.92x 有机会稳定 |
| 75Mbps | 70Mbps | 0.933x | 0.92～0.95x 有机会稳定 |
| 70Mbps | 60Mbps | 0.857x | 需要接近 0.85x，感知明显 |
| 85Mbps | 60Mbps | 0.706x | 0.85～0.95x 不能长期稳定 |

对于 85Mbps 视频、60Mbps 网络：

- 1.00x 时缺口约 25Mbps。
- 0.90x 时消费约 76.5Mbps，仍缺口约 16.5Mbps。
- 0.90x 只能延长初始缓冲的可用时间，不能消除长期重缓冲。
- 真正稳定需要约 0.70x，不能作为正常观影速度。

两小时内容的时长代价：

| 播放速度 | 增加的实际观看时间 |
|---:|---:|
| 0.95x | 约 6 分 19 秒 |
| 0.90x | 约 13 分 20 秒 |
| 0.85x | 约 21 分 11 秒 |

因此“0.90～0.95x 完全无感”不能作为绝对保证。更稳妥的产品定义是：0.95x 为低感知保护档，0.90x 为增强保护档，0.85x 仅作为用户主动选择的激进档。

## 三、公开开源实现调研

### 3.1 Media3：直接可用的保调链路

- [PlaybackParameters.java](https://github.com/androidx/media/blob/main/libraries/common/src/main/java/androidx/media3/common/PlaybackParameters.java)
  - `speed` 控制播放速度。
  - `pitch=1.0` 表示改变速度但不主动改变音调。
  - 当前项目的 `withSpeed()` 会保留现有 pitch。

- [SonicAudioProcessor.java](https://github.com/androidx/media/blob/main/libraries/common/src/main/java/androidx/media3/common/audio/SonicAudioProcessor.java)
  - Media3 内置 Sonic 时间伸缩处理器。
  - 支持独立 speed/pitch。
  - 使用 WSOLA/SOLA 类时间伸缩思路，避免简单重采样产生的低沉人声。
  - 运行在 PCM 音频处理链，不需要引入新的 native 依赖。

- [DefaultAudioSink.java](https://github.com/androidx/media/blob/main/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/audio/DefaultAudioSink.java)
  - 默认音频处理链包含 Sonic。
  - 普通 PCM 播放时可以由 ExoPlayer 处理速度和音调。
  - 音频直通/offload 和 tunneling 不使用这条处理链。

Media3 的现成能力已经覆盖第一阶段原型，不建议先复制 mpv 或 FFmpeg 的音频算法。

### 3.2 dash.js：缓冲驱动的动态播放速度

- [CatchupController.js](https://github.com/Dash-Industry-Forum/dash.js/blob/development/src/streaming/controllers/CatchupController.js)
  - 提供 Default、Step、LoL+ 等控制模式。
  - LoL+ 在 `bufferLevel < playbackBufferMin` 时降低 playback rate。
  - 使用最小变更步长，避免频繁触发 ratechange。
  - 播放已经 stalled 时，不继续提高或剧烈改变速度。
  - 缓冲恢复后逐步回到 1.0。

它主要用于低延迟直播追赶 live edge，但其中的缓冲安全区、滞后和分步恢复逻辑适合改造成点播网络保护控制器。

### 3.3 Media3：比例控制和冷却机制

- [DefaultLivePlaybackSpeedControl.java](https://github.com/androidx/media/blob/main/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/DefaultLivePlaybackSpeedControl.java)
  - 通过目标延迟误差计算速度。
  - 速度受最小值、最大值限制。
  - 有最小更新间隔，避免频繁调速。
  - 重缓冲后扩大目标延迟，网络恢复后再平滑收敛。

该类针对直播延迟，不应直接用于当前单轨高码率点播，但其控制结构可复用。

### 3.4 mpv、FFmpeg、VLC 和其他音频实现

- mpv：[af_scaletempo2.c](https://github.com/mpv-player/mpv/blob/master/audio/filter/af_scaletempo2.c)
  - `scaletempo2` 在改变速度时保持音调。
  - mpv 文档中的 `audio-pitch-correction` 会在 speed 不为 1 时自动插入该滤镜。
  - 源码实现较完整，但不能直接接入 EXO 的 `AudioSink`。

- FFmpeg：[af_atempo.c](https://github.com/FFmpeg/FFmpeg/blob/master/libavfilter/af_atempo.c)
  - 使用 WSOLA 思路做音频 tempo scaling。
  - 适合作为 FFmpeg 音频链路参考。
  - 当前项目若只为降速而引入 FFmpeg filter，会增加 JNI/native 维护成本。

- VLC：[scaletempo.c](https://github.com/videolan/vlc/blob/master/modules/audio_filter/scaletempo.c)
  - 通过 stride、overlap 和相关搜索保持音调。
  - 适合参考算法参数，不建议直接移植到 Android 播放主链。

- WebRTC NetEq：[PreemptiveExpand](https://github.com/webrtc-mirror/webrtc/blob/main/modules/audio_coding/neteq/preemptive_expand.cc)
  - 针对语音 jitter buffer 做短时音频拉伸。
  - 说明“缓冲状态 → 小幅时间伸缩 → 恢复”的思路有成熟工程先例。
  - 这是音频抖动缓冲，不是电影视频播放器的直接实现。

- Rubber Band：[Rubber Band Library](https://github.com/breakfastquay/rubberband)
  - 音质和功能较高，但实时处理更重。
  - GPL 或商业授权，不适合作为当前 App 的首选依赖。

## 四、当前工程源码审阅

### 4.1 已具备的能力

- [PlayerManager.setSpeed()](../app/src/main/java/com/fongmi/android/tv/player/PlayerManager.java) 已能调用 `player.setPlaybackParameters(...withSpeed(speed))`。
- [ExoUtil.buildAudioSink()](../app/src/main/java/com/fongmi/android/tv/player/exo/ExoUtil.java) 已统一构建 `DefaultAudioSink`。
- FFmpeg 音频 renderer 最终也输出 PCM 到同一 AudioSink，理论上可以复用 Sonic。
- EXO 自动模式已有缓冲、重缓冲和带宽诊断，可作为控制器输入来源。
- Go 二进制代理和 `127.0.0.1` HTTP 链路必须继续保留。

### 4.2 当前缺口

- `SPEED_PRESETS` 目前主要是 `0.5x、0.75x、1x、1.2x` 以上，没有 0.95/0.92/0.90/0.85 保护档。
- 没有“缓冲下降趋势 → 自动降速 → 稳定后恢复”的独立控制器。
- [ExoTunnelingPolicy](../app/src/main/java/com/fongmi/android/tv/player/exo/ExoTunnelingPolicy.java) 没有根据网络保护模式或非 1.0x 速度禁用 tunneling。
- 当前音频直通/offload 与 Sonic 保调存在冲突。
- [ExoOutputModeManager](../app/src/main/java/com/fongmi/android/tv/player/exo/ExoOutputModeManager.java) 按原始帧率选显示模式，没有为动态播放速度提供匹配刷新率。
- 当前“播放性能自动”只调整加载、缓冲、解码等参数，不会自动改变播放速度。

## 五、推荐的控制器设计

### 5.1 主要输入

优先级从高到低：

1. 播放缓冲媒体时间的滑动斜率。
2. 连续重缓冲次数和重缓冲间隔。
3. 真实字节读取斜率和已知媒体码率。
4. Go 代理提供的可选上游 telemetry；没有 telemetry 时不能假设本地吞吐等于 CDN 吞吐。

缓冲斜率的近似关系：

```text
可支撑媒体速度 ≈ 当前播放速度 + 缓冲秒数变化率
```

例如当前 1.0x 播放，20 秒内缓冲从 30 秒降到 28 秒，则网络大约只能支撑 0.90x 的媒体消费速度，应在安全余量下逐步调到 0.90～0.92x。

采样必须避开：暂停、Seek、切轨、重缓冲过程、缓冲已经达到上限且加载器主动停止的阶段。

### 5.2 状态机

```text
NORMAL
  speed=1.00

BUFFER_WARNING
  缓冲持续下降，连续确认后进入保护

PROTECT
  1.00 → 0.97 → 0.95 → 0.92 → 0.90
  每次调整 0.01～0.02，至少间隔 10～20 秒

RECOVERY
  缓冲恢复并稳定后逐步回升
  0.90 → 0.92 → 0.95 → 0.97 → 1.00

UNSUSTAINABLE
  已到最低速度但缓冲仍持续下降
  停止继续降速，报告网络无法支撑当前媒体
```

自动控制不得无限降低速度，也不应在重缓冲时反复震荡。

### 5.3 模式边界

建议提供三个档位：

| 模式 | 速度范围 | 目标 |
|---|---|---|
| 标准保护 | 0.95～1.00x | 尽量低感知，处理轻微缺口 |
| 增强保护 | 0.90～1.00x | 处理约 5%～10% 持续缺口 |
| 激进保护 | 0.85～1.00x | 用户主动选择，尽量延迟重缓冲 |

## 六、必须处理的兼容性问题

### 6.1 音频直通/offload

Sonic 只能处理 PCM，不能修改已经编码的 AC3/EAC3/TrueHD/DTS-HD bitstream。因此网络保护模式可能需要关闭 passthrough/offload，改为：

```text
压缩音频 → 解码 PCM → Sonic 保调 → AudioTrack
```

代价是失去原始码流直通、Atmos/DTS:X 对象音频能力，以及部分功耗优势。

### 6.2 tunneling

Media3 的音频 sink 在 tunneling 下不会应用音频处理器变速，因为音频时长变化与视频帧时间戳无法自动保持同步。因此网络保护模式不能继续无条件启用 tunneling。

优先策略是：

- 在会话创建前已知需要网络保护时，直接关闭 tunneling。
- 不在播放中途为了降速强行重建播放器。
- 如果用户选择音频直通或 tunneling，则自动网络降速应显示为不可用，而不是静默破坏输出能力。

### 6.3 显示刷新率和画面节奏

0.95x/0.90x 会改变有效视频节奏，但电视通常没有 22.8Hz/21.6Hz 这类刷新率。网络保护模式不应随着每次调速频繁切换 Display.Mode；应保持当前显示模式，接受轻微 cadence 变化，或由电视自身 MEMC 处理。

不建议在 App 内实现光流插帧：4K/5K 光流会显著增加 GPU、内存、功耗和延迟，可能把网络问题变成渲染/热控问题。

### 6.4 用户手动速度

自动网络保护必须与用户手动倍速区分：

- 用户主动设置 0.75x/1.25x 时，不应被自动控制器覆盖。
- 自动控制只在用户速度为 1.0x 且模式已开启时生效。
- OSD 应显示“网络保护 0.95x”与“用户速度 1.00x”两个概念，避免误解。

## 七、实施顺序建议

### ✅ 阶段 0：只做观测，不改速度

- ✅ 已有 `ForwardBufferTrend` 记录 5～30 秒缓冲斜率。
- ✅ 已记录重缓冲次数、总时长、当前缓冲和加载状态。
- ✅ 观测链路不改变用户播放行为，并有专项单测。

### ✅ 阶段 1：固定保护档

- ✅ 在播放性能设置中增加关闭、0.95x、0.90x、0.85x 固定保护档。
- ✅ 仅 EXO 点播、用户倍速为 1.0x 时生效；用户手动倍速优先。
- ✅ 保护会话禁用 tunneling、音频直通输出路径和强制刷新率匹配，使用 PCM + Sonic 保调。
- ✅ OSD 分别显示用户倍速、网络保护档和实际播放速度。
- ✅ 不改变 Go 二进制代理及 `127.0.0.1` 播放链路。

### 阶段 2：自动标准保护

- 新增独立 `ExoNetworkGuardController`。
- 默认只允许 0.95～1.00x。
- 使用缓冲斜率、冷却、滞后和下限。
- 低于下限仍耗尽时报告不可持续，不继续降速。

### 阶段 3：增强/激进保护

- 用户主动开启最低 0.90x 或 0.85x。
- 增加音频听感、画面 cadence、字幕同步、Seek 和切后台测试。
- 建立设备/音频格式黑名单。

## 八、验收指标

每次代码修改都必须单独单测、构建 APK、commit、打独立 tag，并在 vivo V2453A 真机验证。

### 功能

- 速度能在 1.00/0.95/0.92/0.90/0.85 之间渐进切换。
- Sonic 保持人声音调，不出现明显低沉或断裂。
- Seek、暂停、重缓冲、切轨后速度状态正确恢复。
- 用户手动倍速不会被自动模式覆盖。

### 稳定性

- 轻微带宽缺口下重缓冲次数和总时长下降。
- 达到最低速度仍无法支撑时不无限降速。
- 不破坏 Go 二进制代理、鉴权和 `127.0.0.1` 链路。
- tunneling、直通和 PCM 模式的可用性显示准确。

### 性能

- 比较 Sonic CPU、音频 underrun、视频掉帧、首帧时间和 RSS。
- 确认关闭 tunneling 后的 CPU 增量是否抵消网络保护收益。
- 记录 24/30/60fps 内容在 0.95/0.90x 下的 cadence/judder。

## 九、明确不建议的方案

1. 直接按“本地 127.0.0.1 网速 / 视频码率”每秒调速。
2. 不设置速度下限，持续降到 0.5x 或更低。
3. 为了降速强制关闭所有硬件输出能力且不提示用户。
4. 在播放中途频繁重建播放器来切换 PCM、直通或 tunneling。
5. 为网络卡顿引入 App 侧实时光流插帧。
6. 仅引入 mpv/FFmpeg 音频滤镜，却不处理视频时钟、tunneling 和显示 cadence。
7. 把“0.90～0.95x 低感知”宣传成所有内容和用户都完全无感。

## 十、最终建议

该方案可以作为主方案的独立候选项实施，但不应默认覆盖所有高规格播放。

优先建议：

1. 先做阶段 0 观测，确认当前资源的缓冲斜率确实能预测重缓冲。
2. 再做阶段 1 手动 0.95/0.90x 实验。
3. 只有确认 Sonic、PCM 输出和画面 cadence 可接受后，才实现自动控制器。
4. 默认保护下限采用 0.95x；增强模式下限 0.90x；0.85x 仅用户主动开启。
5. 对长期严重带宽不足的资源，最终仍应提示切换低码率版本、等待更大缓冲或更换网络，不能把降速当成带宽替代品。
