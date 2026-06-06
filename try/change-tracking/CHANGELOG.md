# try/test.html 变更记录

记录 `try/test.html` 每次逐步优化的功能点、具体变更和对应备份文件。备份文件统一放在 `try/change-tracking/backups/`。

## 2026-06-05 12:27

- 功能/问题：电视/大屏剧情概要展开后，标题仍受 `max-width: min(760px, 54vw)` 限制，右侧还有空间时就提前显示省略号。
- 变更内容：仅在 `episode-preview-expanded` 状态下，将 `.detail-info h2` 的 `max-width` 放宽到 `calc(100vw - var(--detail-side-pad) - var(--detail-side-pad))`。标题仍保持单行，只有用满屏幕右侧可用宽度后才显示 `...`。
- 影响文件：`try/test.html`
- 备份文件：修改前 `try/change-tracking/backups/test-20260605-tv-preview-expanded-title-width-before.html`；修改后 `try/change-tracking/backups/test-20260605-tv-preview-expanded-title-width.html`

## 2026-06-05 12:14

- 功能/问题：电视/大屏剧情概要预览态标题后面仍显示金黄色日期/片名小字；折叠态简介没有充分利用到剧情概要区域上方的可用空间；展开态简介可能遮挡“剧情概要”和季按钮。
- 变更内容：剧情概要预览态隐藏 `.detail-title-meta`，去掉标题后的金色日期/片名。折叠态简介高度计算去掉 220-300px 的人为 `previewHeight` 上限，直接以 `#seasonBlock` 顶部为下边界，并保留 22px 间距，让折叠态显示更多文字。展开态隐藏 `#seasonBlock > h3` 和 `#seasonTabs`，保留分集卡片位置和焦点逻辑，避免展开文字遮挡“剧情概要/第1季”。
- 影响文件：`try/test.html`
- 备份文件：修改前 `try/change-tracking/backups/test-20260605-tv-preview-meta-clamp-expanded-before.html`；修改后 `try/change-tracking/backups/test-20260605-tv-preview-meta-clamp-expanded.html`

## 2026-06-05 12:02

- 功能/问题：电视/大屏剧情概要点击 OK 展开后，简介仍被 `max-height` 和 `overflow:hidden` 截断，显示不完整。
- 变更内容：展开态 `#detailText` 改为 `max-height: none; overflow: visible;`；`updateInlineEpisodePreviewClamp()` 在展开状态下清除 `--episode-preview-text-max` 和内联 `maxHeight`，只保留折叠态截断。标题对齐、分集焦点和循环逻辑不变。
- 影响文件：`try/test.html`
- 备份文件：修改前 `try/change-tracking/backups/test-20260605-tv-preview-expanded-no-clamp-before.html`；修改后 `try/change-tracking/backups/test-20260605-tv-preview-expanded-no-clamp.html`

## 2026-06-05 11:56

- 功能/问题：电视/大屏聚焦剧情概要卡片时，剧情概要标题仍可能被压到屏幕顶部；用户明确要求“默认标题距离屏幕顶部多少，剧情概要标题也保持同样距离”。同时剧情概要简介文字出现白色聚焦框。
- 变更内容：`updateEpisodePreviewPosition()` 改为硬对齐默认详情标题顶部距离：使用详情页当前真实 `padding-top` 作为唯一目标 top，将剧情概要 `.detail-info` 视觉位置拉回该高度，不再根据分集卡片位置反推标题高度。`applyInlineEpisodePreview()` 在保留分集卡片锚点后调用 `syncEpisodePreviewLayout()`，并在连续两帧内重新对齐和重新计算简介折叠，抵消聚焦后的自动滚动。给 `#detailText` 设置 `tabindex="-1"`，并在剧情概要预览态禁用简介文本焦点框、选择和 pointer 事件，避免简介文字被显示为聚焦白框。
- 影响文件：`try/test.html`
- 备份文件：修改前 `try/change-tracking/backups/test-20260605-tv-preview-default-title-and-text-focus-before.html`；修改后 `try/change-tracking/backups/test-20260605-tv-preview-default-title-and-text-focus.html`

## 2026-06-05 11:47

- 功能/问题：电视/大屏剧情概要预览态标题高度/位置反复偏差，用户指定参考 `try/test_副本2.html`，该文件对应历史备份 `test-20260604-tv-episode-preview-inline-more.html`，其剧情概要标题高度正确。
- 变更内容：只对齐参考文件里与预览标题高度相关的规则：删除预览态单独覆盖 `--detail-top-pad`，恢复预览标题行 `height: 64px; height: clamp(50px, 5.8vw, 74px);`，取消预览标题字号压小，让标题继承大屏详情默认标题字号/行高。`updateEpisodePreviewPosition()` 改为以详情页真实 `padding-top` 作为默认标题基准，只在滚动/短屏情况下把标题简介限制在默认顶部和分集卡片上方之间，避免继续靠猜数值调高度。保留已有的“更多”透明、分集循环、上/下焦点返回、单季不可聚焦等后续功能。
- 影响文件：`try/test.html`
- 备份文件：修改前 `try/change-tracking/backups/test-20260605-copy2-tv-preview-title-reference-before.html`；修改后 `try/change-tracking/backups/test-20260605-copy2-tv-preview-title-reference.html`

## 2026-06-05 11:34

- 功能/问题：电视/大屏剧情概要预览态标题高度仍无明显变化，说明只改 `updateEpisodePreviewPosition()` 的正向下移公式没有命中实际顶部锚点。
- 变更内容：直接给 `#detailSheet.detail-large.episode-preview-active` 单独降低 `--detail-top-pad` 到 `clamp(22px, 4.2vh, 44px)`，让预览态标题实际从更高位置开始；`updateEpisodePreviewPosition()` 改回只在信息区压到分集卡片时向上避让，不再正向下移，避免抵消顶部 padding。目标是让剧情概要标题高度明显靠上，同时仍避免正文压到卡片。
- 影响文件：`try/test.html`
- 备份文件：修改前 `try/change-tracking/backups/test-20260605-tv-preview-direct-top-anchor-before.html`；修改后 `try/change-tracking/backups/test-20260605-tv-preview-direct-top-anchor.html`

## 2026-06-05 11:29

- 功能/问题：电视/大屏剧情概要预览态标题高度仍不稳定、偏下，之前反复调数值仍受不同分集标题/简介内容影响。
- 变更内容：重新梳理预览定位链路后，改掉用 `infoRect.height` 参与定位的做法。`updateEpisodePreviewPosition()` 现在用固定预览区域高度 `max(220, min(300, 屏幕高 * 0.34))` 作为锚点，根据“剧情概要”分集区域顶部反推标题/简介目标位置；简介折叠/展开高度也使用同一固定预览区域下边界，避免内容长短影响标题高度和卡片重叠。保留跟随剧情概要区域的行为，不回到屏幕顶部。
- 影响文件：`try/test.html`
- 备份文件：修改前 `try/change-tracking/backups/test-20260605-tv-preview-anchor-rewrite-before.html`；修改后 `try/change-tracking/backups/test-20260605-tv-preview-anchor-rewrite.html`

## 2026-06-05 11:23

- 功能/问题：电视/大屏剧情概要预览态标题仍明显低于正常详情高度。
- 变更内容：保留“标题/简介跟随剧情概要卡片区域”的定位逻辑，但增大预留信息区高度，从 130-205px 调整为 190-285px，并将与分集区间距从 18px 调整为 22px，让标题/简介整体上移，接近默认详情的视觉高度。
- 影响文件：`try/test.html`
- 备份文件：修改前 `try/change-tracking/backups/test-20260605-tv-preview-title-higher-before.html`；修改后 `try/change-tracking/backups/test-20260605-tv-preview-title-higher.html`

## 2026-06-05 10:28

- 功能/问题：上一版误把电视/大屏剧情概要预览定位改成不跟随剧情概要区域，导致切换剧情概要时标题和简介又跑到屏幕上方；真实需求是保留跟随下移，但不要下移过多。
- 变更内容：恢复 `updateEpisodePreviewPosition()` 的“根据剧情概要卡片区域位置下移标题/简介”逻辑，同时收紧顶部安全距和信息区预留高度：顶部安全距 10-24px，预留高度 130-205px，分集区上方间距 18px。这样短屏电视仍能让标题/简介跟随剧情概要区域，但位置比之前更靠上。
- 影响文件：`try/test.html`
- 备份文件：修改前 `try/change-tracking/backups/test-20260605-tv-preview-follow-card-tight-before.html`；修改后 `try/change-tracking/backups/test-20260605-tv-preview-follow-card-tight.html`

## 2026-06-05 10:24

- 功能/问题：电视/大屏剧情概要预览态标题仍偏低，希望和默认详情标题高度/位置一致；剧情概要标题也有类似背景/阴影观感。
- 变更内容：预览态不再按固定顶部安全距主动下推 `.detail-info`，改为默认保留原详情标题位置，只有当信息区底部压到“剧情概要”卡片区域时才向上让位。清理预览态标题行、标题文字和标题 meta 的 `text-shadow/box-shadow/filter/backdrop-filter`，避免标题出现灰底/阴影观感。
- 影响文件：`try/test.html`
- 备份文件：修改前 `try/change-tracking/backups/test-20260605-tv-preview-title-default-position-before.html`；修改后 `try/change-tracking/backups/test-20260605-tv-preview-title-default-position.html`

## 2026-06-05 10:20

- 功能/问题：电视/大屏剧情概要预览“更多”虽然背景透明，但仍能看到类似背景/阴影的灰块感。
- 变更内容：继续清理“更多”伪元素和隐藏按钮本体的视觉效果：移除 `min-width`，强制 `box-shadow/text-shadow/filter/backdrop-filter` 全部为 none，避免文字阴影或滤镜造成灰底观感。
- 影响文件：`try/test.html`
- 备份文件：修改前 `try/change-tracking/backups/test-20260605-tv-preview-more-no-shadow-before.html`；修改后 `try/change-tracking/backups/test-20260605-tv-preview-more-no-shadow.html`

## 2026-06-05 10:16

- 功能/问题：电视/大屏剧情概要预览里的“更多”仍看起来有背景色。
- 变更内容：对分集预览态 `#detailText.clamped[data-more-label]::after` 强制设置 `background/background-color/background-image` 为全透明/none，并加 `!important`；同时对预览态 `#detailMoreBtn` 本体也补同样透明覆盖，避免旧样式或浏览器覆盖残留。
- 影响文件：`try/test.html`
- 备份文件：修改前 `try/change-tracking/backups/test-20260605-tv-preview-more-transparent-before.html`；修改后 `try/change-tracking/backups/test-20260605-tv-preview-more-transparent.html`

## 2026-06-05 10:09

- 功能/问题：电视/大屏剧情概要预览态标题顶部留白仍很大，标题高度明显高于正常详情；“更多”提示带背景色。
- 变更内容：将分集预览态标题行高度从 `clamp(50px, 5.8vw, 74px)` 压到 `clamp(42px, 4.2vw, 56px)`，同步降低标题字号到 `clamp(32px, 4vw, 48px)`，更接近正常详情标题高度。分集预览定位顶部安全距进一步压缩到 8-22px，减少顶部空白；去掉 `#detailText.clamped[data-more-label]::after` 的渐变背景，只保留文字提示。
- 影响文件：`try/test.html`
- 备份文件：修改前 `try/change-tracking/backups/test-20260605-tv-preview-title-height-more-bg-before.html`；修改后 `try/change-tracking/backups/test-20260605-tv-preview-title-height-more-bg.html`

## 2026-06-05 10:05

- 功能/问题：电视/大屏详情页聚焦剧情概要卡片后，标题区域顶部留白偏多，展开剧情概要时正文可能下压重叠到分集卡片。
- 变更内容：TV 大屏分集预览定位从固定顶部 48px 改为按屏幕高度计算的 18-34px 紧凑顶部安全距，减少标题上方留白；分集简介折叠/展开高度统一以“剧情概要”分集区域顶部为下边界计算，展开态也使用 `--episode-preview-text-max` 限制最大高度，避免正文压到卡片。只改 `detail-large.episode-preview-*` 的定位和正文高度计算。
- 影响文件：`try/test.html`
- 备份文件：修改前 `try/change-tracking/backups/test-20260605-tv-episode-preview-top-space-before.html`；修改后 `try/change-tracking/backups/test-20260605-tv-episode-preview-top-space.html`

## 2026-06-05 09:58

- 功能/问题：手机详情剧情概要卡片剧照仍没有贴齐卡片顶部。
- 变更内容：确认新加的 `.episode-still-wrap` 原来使用 `span`，会被 `.episode-card span` 的通用 margin/display 样式影响。将分集剧照容器改为 `div.episode-still-wrap`，手机详情分集卡片强制 `display:flex; flex-direction:column`，媒体区固定 `order:0`、无 padding/border、固定高度，正文区固定 `order:1`；半透明模式同步强制纵向布局。只改手机详情分集卡片图片布局。
- 影响文件：`try/test.html`
- 备份文件：修改前 `try/change-tracking/backups/test-20260605-episode-still-top-align-before.html`；修改后 `try/change-tracking/backups/test-20260605-episode-still-top-align.html`

## 2026-06-05 09:51

- 功能/问题：手机详情点击“更多/收起”仍闪烁；手机剧情概要卡片剧照仍未贴齐卡片顶部。
- 变更内容：撤掉上一次手机简介“截断正文 + 内联 span”的重建方案，改为保持 `#detailText` 主文本不变，只通过 `max-height` 折叠/展开，并让真实 `detailMoreBtn` 在文字末行右侧显示“更多/收起”，点击时只切换 class 和按钮文案，避免重建整段简介。分集卡片剧照改为 `<span class="episode-still-wrap"><img ...></span>` 固定裁切容器，手机端和半透明模式分别给容器固定高度，图片块级铺满容器顶部，避免顶部露出卡片背景。TV/电脑分集概要和主页背景不改。
- 影响文件：`try/test.html`
- 备份文件：修改前 `try/change-tracking/backups/test-20260605-fix-mobile-more-and-episode-still-before.html`；修改后 `try/change-tracking/backups/test-20260605-fix-mobile-more-and-episode-still.html`

## 2026-06-05 09:41

- 功能/问题：手机详情点击“更多/收起”仍有闪烁；状态面板里的“手机详情”需要从 checkbox 改成“沉浸式/半透明”分段控制器；手机详情剧情概要卡片部分剧照顶部露出半透明卡片背景。
- 变更内容：参考移动 Web 避免 forced synchronous layout/layout thrashing 的做法，手机详情简介折叠结果按文本长度、宽度和目标高度缓存，后续点击“更多/收起”只切换缓存文本，减少点击时反复 DOM 写入和高度读取。状态面板“手机详情”改为两个可聚焦按钮组成的分段控制器，保留遥控器左右键切换和持久化。手机详情分集剧照增加固定高度裁切规则，强制图片铺满顶部，半透明模式使用更高剧照高度，避免顶部漏出卡片底色。主页背景、TV/电脑分集概要不改。
- 影响文件：`try/test.html`
- 备份文件：修改前 `try/change-tracking/backups/test-20260605-mobile-detail-segmented-and-no-flicker-before.html`；修改后 `try/change-tracking/backups/test-20260605-mobile-detail-segmented-and-no-flicker.html`

## 2026-06-05 09:31

- 功能/问题：手机详情简介折叠态“更多”和最后几个字重叠；展开后点击“收起”无法稳定折叠；点击“更多/收起”时简介区域会闪一下。
- 变更内容：废弃手机详情里通过 CSS 伪元素/绝对定位覆盖“更多/收起”的实现，改为把简介文本真实渲染成“正文尾部 + 内联 `detail-text-toggle`”。普通详情写入统一走 `setDetailTextContent()` 保存完整简介，手机详情折叠按 4 行实际高度测量并二分截断，展开时渲染完整正文加“收起”，点击简介文本在两个状态间切换。TV/电脑分集概要仍保留原 `data-more-label` 逻辑，主页背景不改。
- 影响文件：`try/test.html`
- 备份文件：修改前 `try/change-tracking/backups/test-20260605-mobile-inline-more-render-before.html`；修改后 `try/change-tracking/backups/test-20260605-mobile-inline-more-render.html`

## 2026-06-05 09:15

- 功能/问题：半透明手机详情模式下按钮和简介文字间距太小；沉浸式简介右侧换行过早；“更多/收起”需要和正文最后一行同行且不能有背景色；页面上滑后点击“收起”无法稳定重新折叠。
- 变更内容：手机详情按钮恢复到简介和“剧情概要”之间，半透明模式增加按钮上方间距，让操作区居中落在简介与分集区域之间。手机详情正文的“更多/收起”统一改为 `#detailText` 自身的伪元素提示，去掉渐变背景和右侧预留宽度，展开后也隐藏真实 `detailMoreBtn`，点击简介文本即可在“更多/收起”之间切换，避免滚动后独立按钮失效。TV/电脑详情和主页背景不改。
- 影响文件：`try/test.html`
- 备份文件：修改前 `try/change-tracking/backups/test-20260605-mobile-inline-more-spacing-before.html`；修改后 `try/change-tracking/backups/test-20260605-mobile-inline-more-spacing.html`

## 2026-06-05 09:04

- 功能/问题：上次手机详情按钮前置后，部分设备上标题、信息标签和简介看起来跑到壁纸/剧照层下面，不再和按钮一样稳定显示。
- 变更内容：保留“三按钮在简介上方”的布局，只给手机详情中由 `.detail-info` 重排出来的标题行、标签、简介和“更多”补回 `position: relative; z-index: 2`，修复 `display: contents` 导致子内容不再继承详情页直接子元素层级的问题。不改主页背景、不改详情壁纸、不改 TV/电脑详情逻辑。
- 影响文件：`try/test.html`
- 备份文件：修改前 `try/change-tracking/backups/test-20260605-mobile-detail-info-zindex-before.html`；修改后 `try/change-tracking/backups/test-20260605-mobile-detail-info-zindex.html`

## 2026-06-05 09:01

- 功能/问题：手机端详情页三按钮高度偏高；希望“继续观看 / 搜索 / 盘搜”显示在简介上方；简介折叠时“更多”需要和最后一行同排，且最后一行不能被裁掉；按钮文字观感不够清晰。
- 变更内容：仅在 `html.native-mobile-app #detailSheet:not(.detail-large)` 范围内调整手机详情布局，把详情操作按钮视觉顺序移到简介正文前面，标题和标签仍在按钮上方。压缩三按钮高度、字号、圆角和图标尺寸，并去掉三按钮文字阴影/模糊叠加，提升文字对比。手机详情折叠简介改为在正文右下角显示行内“更多”，隐藏独立“更多”按钮并支持点击简介展开；折叠高度按完整行计算并增加少量余量，避免最后一行下半截被截断。TV/电脑详情、主页背景和现有导航逻辑不改。
- 影响文件：`try/test.html`
- 备份文件：修改前 `try/change-tracking/backups/test-20260605-mobile-actions-over-summary-before.html`；修改后 `try/change-tracking/backups/test-20260605-mobile-actions-over-summary.html`

## 2026-06-05 08:44

- 功能/问题：手机详情开关聚焦视觉需要和其他控件完全一致；手机详情出现“继续观看”时三个按钮需要按参考图实现，保留图标并区分主按钮/次按钮；电脑打开剧情概要希望和电视端一样，不再弹窗。
- 变更内容：撤掉“手机详情”开关单独 TV outline，把 `detail-style-toggle:focus-within` 并入 `.chip/.btn/.connection-toggle` 同一套聚焦规则。手机详情三按钮行重做为参考图风格：继续观看为蓝色渐变主按钮且保留图标，搜索/盘搜为浅色次按钮，三按钮使用带最小宽度的三列 grid，沉浸式和半透明两种手机详情模式都适配。电脑/浏览器大屏详情放开 `useInlineEpisodePreview()` 的 TV 限制，和电视端一样聚焦分集卡片时直接在详情页内替换标题、简介和剧照；手机端仍保留原剧情概要弹层。
- 影响文件：`try/test.html`
- 备份文件：修改前 `try/change-tracking/backups/test-20260605-mobile-buttons-and-desktop-episodes-before.html`；修改后 `try/change-tracking/backups/test-20260605-mobile-buttons-and-desktop-episodes.html`

## 2026-06-05 08:33

- 功能/问题：电视端状态面板里的“手机详情”切换开关虽然可点击，但没有明显聚焦状态；手机详情页出现“继续观看”后，希望“继续观看 / 搜索 / 盘搜”三个按钮一行完整显示。
- 变更内容：为“手机详情”开关的 `focus-within` 增加背景、边框和 TV outline，遥控器聚焦时可见。详情操作区在命中继续观看历史时增加 `has-continue` class；手机端沉浸式和半透明两种详情模式下都改为三列紧凑布局，缩小字号、间距和图标，并隐藏“继续观看”图标以保证三按钮一行显示。无继续观看时仍保持原来的两按钮布局。
- 影响文件：`try/test.html`
- 备份文件：修改前 `try/change-tracking/backups/test-20260605-focus-and-mobile-actions-before.html`；修改后 `try/change-tracking/backups/test-20260605-focus-and-mobile-actions.html`

## 2026-06-05 08:18

- 功能/问题：状态面板需要增加一个选项，让用户自行决定手机详情页使用当前全屏沉浸式，还是参考 `demo/nostr-半透明.html` 的半透明背景风格。
- 变更内容：在“状态”面板新增“手机详情”开关，默认保持“全屏沉浸式”；开启后给 `<html>` 添加 `mobile-detail-translucent`，只覆盖 `html.native-mobile-app #detailSheet:not(.detail-large)` 的手机详情样式，恢复半透明背景、内容流里的圆角剧照、普通标题/标签/按钮/分集/演员卡片布局。用户选择写入 App cache 的 `fish2018_home_v1_ui_prefs`，SDK 就绪后会自动读取；TV 大屏详情、主页透明背景、盘搜/TMDB 配置保存逻辑不变。
- 影响文件：`try/test.html`
- 备份文件：修改前 `try/change-tracking/backups/test-20260605-mobile-detail-style-toggle-before.html`；修改后 `try/change-tracking/backups/test-20260605-mobile-detail-style-toggle.html`

## 2026-06-05 01:33

- 功能/问题：详情页如果最近观看里已有该片可恢复的正常播放历史，需要在“搜索播放”前增加“继续观看”，并把“搜索播放”改成“搜索”。
- 变更内容：详情操作区新增默认隐藏的“继续观看”按钮；打开详情页时根据 App 最近观看记录匹配当前片名/TMDB 标识，只在命中 `siteKey + vodId` 的可恢复历史时显示按钮，并动态把搜索按钮文案改为“搜索”。点击“继续观看”会直接调用 `sdk().vod(siteKey, vodId, title, pic)` 恢复播放，同时用当前 TMDB 详情项记录观看意图；无历史时保持原单按钮“搜索播放”。补齐 TV 详情页操作区焦点识别，让“继续观看 / 搜索 / 盘搜”仍按同一组按钮进行左右和上下导航。
- 影响文件：`try/test.html`
- 备份文件：修改前 `try/change-tracking/backups/test-20260605-detail-continue-watch-before.html`；修改后 `try/change-tracking/backups/test-20260605-detail-continue-watch.html`

## 2026-06-05 00:55

- 功能/问题：进入/退出详情页时短暂露出 App 原生背景的尝试修复不符合要求。
- 变更内容：已回退该次错误改动，`html.fm-native`、`.app`、`.sheet` 继续保持透明背景，不再使用不透明主页兜底；详情关闭顺序也恢复到修改前状态。
- 影响文件：`try/test.html`
- 备份文件：已回退到 `try/change-tracking/backups/test-20260605-detail-transition-background-before.html`

## 2026-06-05 00:37

- 功能/问题：电视端详情页一直按上键可能回到主页。
- 变更内容：排查为隐藏的详情返回按钮仍可能进入焦点顺序。TV 大屏详情下同步将 `closeDetailBtn.tabIndex` 设为 `-1`；详情方向键顺序不再把返回按钮加入 `detailBlockOrder()`，若焦点已异常落到该按钮，方向键会重定向到“搜索播放/盘搜”而不是继续停留在隐藏返回按钮。
- 影响文件：`try/test.html`
- 备份文件：修改前 `try/change-tracking/backups/test-20260605-tv-detail-up-no-back-before.html`；修改后 `try/change-tracking/backups/test-20260605-tv-detail-up-no-back.html`

## 2026-06-05 00:31

- 功能/问题：从剧情概要分集卡片按上恢复默认详情时，分集卡片仍完整停在屏幕底部，默认详情标题可能超出屏幕看不到。
- 变更内容：在分集卡片按上退出分集预览并恢复默认详情后，调用 `ensureDefaultDetailHeaderVisible()`：强制默认简介折叠到约 3 行，显示“更多”，并调整 `detailSheet.scrollTop` 让默认详情标题区域回到屏幕内。多季回季按钮、单季回搜索播放按钮前都会先执行该可见化处理。
- 影响文件：`try/test.html`
- 备份文件：修改前 `try/change-tracking/backups/test-20260605-tv-default-detail-visible-before.html`；修改后 `try/change-tracking/backups/test-20260605-tv-default-detail-visible.html`

## 2026-06-05 00:22

- 功能/问题：从演员表等下方区域按上键回到剧情概要分集卡片时，分集卡片没有先移动到屏幕底部，导致上方标题/简介/剧照显示区域不足并发生重叠。
- 变更内容：详情方向键命中分集卡片且来源不是详情操作区/季标签/简介区时，先调整 `detailSheet.scrollTop`，把目标分集卡片定位到屏幕底部安全区域，再用 `preventScroll` 聚焦，并只补横向滚动可见性，避免通用 `focusRemoteTarget()` 把卡片重新滚回中间。
- 影响文件：`try/test.html`
- 备份文件：修改前 `try/change-tracking/backups/test-20260605-tv-episode-return-scroll-before.html`；修改后 `try/change-tracking/backups/test-20260605-tv-episode-return-scroll.html`

## 2026-06-05 00:17

- 功能/问题：切换分集剧情概要卡片时，标题和简介位置一上一下跳动，一集显示正常、一集跑上去。
- 变更内容：修正分集预览定位计算。`updateEpisodePreviewPosition()` 在读取 `.detail-info.getBoundingClientRect()` 前先清空旧 `transform`，避免基于已偏移的位置重复计算；同时改为标题/简介文本更新后再计算位置，保证不同集标题和简介内容变化后使用最终高度定位。
- 影响文件：`try/test.html`
- 备份文件：修改前 `try/change-tracking/backups/test-20260605-tv-preview-stable-position-before.html`；修改后 `try/change-tracking/backups/test-20260605-tv-preview-stable-position.html`

## 2026-06-05 00:00

- 功能/问题：短屏电视需要向下滚动才能看到剧情概要卡片，聚焦分集后标题/简介仍停在原详情顶部，看起来像跑到屏幕上方；同时 TV 分集内联预览的左右键不再支持第 1 集向左循环到有效最后一集。
- 变更内容：分集预览态根据 `seasonBlock` 当前屏幕位置计算 `.detail-info` 的视觉下移量，直接写入 `transform`，让标题/简介跟随当前可见的剧情概要区域，同时不参与文档流，保持分集卡片位置固定；退出预览时清理该 transform。新增 TV 分集 rail 左右键循环目标，复用 `episodeEffectiveIndexes`，左键上一集、右键下一集，到边界时在有效分集中取模循环。
- 影响文件：`try/test.html`
- 备份文件：修改前 `try/change-tracking/backups/test-20260604-tv-inline-episode-cycle-and-scroll-before.html`；修改后 `try/change-tracking/backups/test-20260604-tv-inline-episode-cycle-and-scroll.html`

## 2026-06-04 23:46

- 功能/问题：旧电视 WebView 中分集概要预览改造兼容性不足，正文可能跑到屏幕顶部，覆盖大面积剧照。
- 变更内容：为 `no-css-functions` 和 `legacy-detail-layout` 增加分集概要预览专用兜底：正文宽度固定为 560px，预览信息区固定高度 220px，标题行固定 64px，折叠正文固定最大高度，避免依赖 `min()/clamp()/var()` 计算。JS 在旧内核兜底模式下改为直接写入 `height/maxHeight` 和正文 `maxHeight`，退出预览时清理这些内联样式，避免恢复默认详情后残留。
- 影响文件：`try/test.html`
- 备份文件：修改前 `try/change-tracking/backups/test-20260604-tv-episode-preview-legacy-webview-before.html`；修改后 `try/change-tracking/backups/test-20260604-tv-episode-preview-legacy-webview.html`

## 2026-06-04 23:30

- 功能/问题：用户从分集剧情概要卡片按上下键跳出后，再返回剧情概要区域时，总是回到第 1 集，而不是刚才聚焦的分集。
- 变更内容：为分集概要预览状态增加 `lastEpisodeNumber`，聚焦分集卡片时记录上次集号；从季标签或上方详情区域重新进入分集 rail 时，优先聚焦该集号对应的卡片，找不到时才回退到第一个分集。分集列表重新渲染时也优先用上次集号恢复 `episodeViewer` 当前集。
- 影响文件：`try/test.html`
- 备份文件：修改前 `try/change-tracking/backups/test-20260604-tv-episode-return-focus-before.html`；修改后 `try/change-tracking/backups/test-20260604-tv-episode-return-focus.html`

## 2026-06-04 23:25

- 功能/问题：电视端聚焦到分集剧情概要卡片后，按上键没有反应，无法回到默认详情界面。
- 变更内容：修正详情页分集 rail 的上键路径。分集卡片按上时，如果当前处于分集概要预览态，先恢复默认详情内容和搜索/盘搜按钮；多季剧集优先回到可聚焦季按钮，单季剧集因季标签不可聚焦则回到“搜索播放”按钮，避免卡在分集 rail。
- 影响文件：`try/test.html`
- 备份文件：修改前 `try/change-tracking/backups/test-20260604-tv-episode-up-restore-before.html`；修改后 `try/change-tracking/backups/test-20260604-tv-episode-up-restore.html`

## 2026-06-04 23:20

- 功能/问题：部分较窄电视上详情/分集概要折叠态简介过宽，容易压到右侧剧照主体；很多剧集只有一个“第1季”，遥控器不需要停在这个单季按钮上。
- 变更内容：TV 大屏详情和分集概要折叠态正文宽度收窄为 `min(620px, 42vw)`，尽量控制在左侧安全区，减少遮挡剧照；展开态仍保留横向到屏幕右侧换行。`renderSeasons()` 在只有一个季时保留季标签视觉，但移除 `focusable`、设置 `tabIndex=-1`，不再绑定切季点击，遥控器上下导航会跳过单季标签直接到分集卡片。
- 影响文件：`try/test.html`
- 备份文件：修改前 `try/change-tracking/backups/test-20260604-tv-preview-safe-width-single-season-before.html`；修改后 `try/change-tracking/backups/test-20260604-tv-preview-safe-width-single-season.html`

## 2026-06-04 23:10

- 功能/问题：分集概要折叠时没有充分利用“剧情概要”标题上方空间，文字被裁到半行；“更多”按钮另起一行，和简介正文割裂。
- 变更内容：折叠高度改为以 `#seasonBlock` 顶部为下边界，并按完整行数取整，避免半行文字被截断；将“更多”改为 `#detailText` 的行内伪元素提示，显示在折叠正文最后一行右侧，原独立 `detailMoreBtn` 在分集概要态隐藏但仍保留 OK/点击逻辑入口。
- 影响文件：`try/test.html`
- 备份文件：修改后 `try/change-tracking/backups/test-20260604-tv-episode-preview-inline-more.html`

## 2026-06-04 23:03

- 功能/问题：分集概要预览态隐藏“搜索播放/盘搜”后，分集卡片不能随按钮隐藏而上移；简介需要视觉上利用隐藏按钮区域显示更多文字；展开简介时只应改变正文横向换行宽度，标题、日期/来源标签、季标签等信息不能消失，标题位置也不能忽高忽低。
- 变更内容：将概要态的详情操作按钮改为 `visibility:hidden` 保留原布局占位，避免分集卡片上移；进入分集预览时记录并锁定 `.detail-info` 原高度，正文允许溢出到隐藏按钮区域，折叠高度按隐藏按钮区域真实底部计算，给“更多/收起”留位置。撤销分集标题动态缩小字号和标题 meta 隐藏，改为固定标题行高度、标题单行省略，展开态只扩大正文宽度到屏幕右侧换行，不再隐藏标题、标签或季信息。
- 影响文件：`try/test.html`
- 备份文件：修改前 `try/change-tracking/backups/test-20260604-tv-episode-preview-fixed-rail-before.html`；修改后 `try/change-tracking/backups/test-20260604-tv-episode-preview-fixed-rail.html`

## 2026-06-04 22:40

- 功能/问题：电视端详情页剧情概要不再希望点击分集后弹窗查看，而是遥控器聚焦到分集卡片时直接把详情页标题、简介和剧照切换为该集内容；概要过长时折叠，按 OK 展开/收起，并保持分集卡片位置固定。补充要求：分集概要标题单行显示，概要态隐藏“搜索播放”和“盘搜”按钮。
- 变更内容：在 TV 大屏详情页新增分集内联预览状态，分集卡片 `focus` 时只更新详情区内容和剧照，不重建分集列表，不触发滚动聚焦；OK/点击在 TV 大屏下切换概要展开/收起，手机端和非 TV 仍保留原 `imageViewer` 弹窗。概要态隐藏详情操作按钮，分集简介按长度和空间折叠，Back 键在展开态优先收起，不退出详情页。
- 影响文件：`try/test.html`
- 备份文件：修改前 `try/change-tracking/backups/test-20260604-tv-inline-episode-preview-before.html`；修改后 `try/change-tracking/backups/test-20260604-tv-inline-episode-preview.html`

## 2026-06-04 19:42

- 功能/问题：老电视 WebView 中主页卡片高度不一致，部分卡片顶部或底部露出半透明灰色背景，圆角看起来不统一，疑似 `aspect-ratio` 兼容异常导致海报区域没有固定比例裁切。
- 变更内容：增加 `aspect-ratio` 能力检测和实测校验，不支持或误报支持时给 `<html>` 添加 `no-aspect-ratio`。为旧内核补充卡片比例兜底：竖版海报使用 `.poster-wrap` 的 `padding-top:150%` 固定 2:3 比例，横版卡片使用 `56.25%` 固定 16:9，图片绝对定位填满并统一裁切；最近观看卡片、详情分集卡片、详情相关推荐横图卡片也增加固定高度兜底。正常支持 `aspect-ratio` 的设备不启用该兜底。
- 影响文件：`try/test.html`
- 备份文件：修改前 `try/change-tracking/backups/test-20260604-card-aspect-fallback-before.html`；修改后 `try/change-tracking/backups/test-20260604-card-aspect-fallback.html`

## 2026-06-04 19:28

- 功能/问题：老电视 WebView 中详情页大屏模式仍然贴近左上，标题、标签、按钮和剧情概要区域比正常设备更小，疑似 `clamp()/min()/max()` 等 CSS 函数兼容异常导致关键 padding/字号声明失效。
- 变更内容：增加 CSS 函数能力检测，不支持 `min()`/`clamp()` 时给 `<html>` 添加 `no-css-functions`；详情页大屏模式增加固定 px 兜底，覆盖左右/顶部 padding、背景图尺寸、内容宽度、标题/评分/简介/按钮/分集卡片/演员卡片/相关推荐卡片尺寸。另在 `syncDetailLayout()` 后加入实际 padding 测量，若大屏详情计算出的左/上 padding 小于 32px，则添加 `legacy-detail-layout` 强制启用同一套详情兜底。该改动只影响检测异常的老 WebView，不修改焦点导航和数据逻辑。
- 影响文件：`try/test.html`
- 备份文件：修改前 `try/change-tracking/backups/test-20260604-detail-large-css-fallback-before.html`；修改后 `try/change-tracking/backups/test-20260604-detail-large-css-fallback.html`

## 2026-06-04

- 功能/问题：部分老电视 WebView 中主页卡片、详情页按钮、信息气泡等元素挤在一起，疑似 `flex/grid gap` 兼容异常导致间距失效。
- 变更内容：为 `try/test.html` 增加运行时布局间距检测，实际测量 flex/grid `gap` 是否生效；仅在检测失败时给 `<html>` 添加 `no-layout-gap`。新增对应 CSS 兜底，用 margin 为搜索按钮、chips、横向 rail、主页网格卡片、详情页按钮、信息气泡、状态面板、盘搜配置等关键区域补间距。正常支持 gap 的新设备不会启用兜底，不改变原有布局；未修改焦点导航、数据加载、详情交互逻辑。
- 影响文件：`try/test.html`
- 备份文件：修改前 `try/change-tracking/backups/test-20260604-old-webview-gap-fallback-before.html`；修改后 `try/change-tracking/backups/test-20260604-old-webview-gap-fallback.html`

## 2026-06-03 17:15

- 功能/问题：`try/nostr-沉浸式.html` 剧情概要循环切换需要识别最新有效集，例如 TMDB 返回 200 集但实际只更新到 142 集时，第 1 集右滑应显示第 142 集而不是第 200 集。
- 变更内容：新增有效分集索引计算。若分集存在 `air_date`，以不晚于今天的最大播出集数作为有效硬上限，未来占位集不参与循环；若整季没有可靠播出日期，再用剧照或简介作为兜底判断。剧情概要循环切换改为只在有效分集索引中取模，因此首尾循环会落到最新有效集。
- 影响文件：`try/nostr-沉浸式.html`
- 备份文件：修改前 `try/change-tracking/backups/nostr-immersive-20260603-episode-effective-loop-before.html`；修改后 `try/change-tracking/backups/nostr-immersive-20260603-episode-effective-loop.html`

## 2026-06-03 17:09

- 功能/问题：`try/nostr-沉浸式.html` 剧情概要切集希望支持循环切换，例如第 1 集右滑显示最后一集。
- 变更内容：将剧情概要 `switchEpisodeViewer()` 的边界处理改为取模循环，上一集/下一集越界时自动跳到另一端；触屏滑动和遥控器左右键共用该逻辑，因此两端输入都支持循环切换。
- 影响文件：`try/nostr-沉浸式.html`
- 备份文件：修改前 `try/change-tracking/backups/nostr-immersive-20260603-episode-loop-before.html`；修改后 `try/change-tracking/backups/nostr-immersive-20260603-episode-loop.html`

## 2026-06-03 17:06

- 功能/问题：`try/nostr-沉浸式.html` 剧情概要触屏左右滑动方向反了，第 10 集左滑显示第 11 集、右滑显示第 9 集。
- 变更内容：只调整剧情概要触屏 `touchend` 的切集方向映射，恢复为左滑下一集、右滑上一集；不改遥控器方向键逻辑，遥控器仍为左键上一集、右键下一集。
- 影响文件：`try/nostr-沉浸式.html`
- 备份文件：修改前 `try/change-tracking/backups/nostr-immersive-20260603-episode-swipe-direction-before.html`；修改后 `try/change-tracking/backups/nostr-immersive-20260603-episode-swipe-direction.html`

## 2026-06-03 17:00

- 功能/问题：`try/nostr-沉浸式.html` 从第 10 集等非首集进入剧情概要后，左右切换可能从第一集开始计算，而不是按当前集切到上一集/下一集。
- 变更内容：剧情概要查看器新增当前 `episode_number` 作为稳定定位依据；打开概要时保存当前集号，切换时优先按集号在当前分集数组中反查索引，避免 `index` 被异步重绘重置后兜底到 0。分集列表在概要打开期间重新渲染时会保留当前集号并重新定位索引。触屏方向调整为左滑上一集、右滑下一集；遥控器仍为左键上一集、右键下一集。
- 影响文件：`try/nostr-沉浸式.html`
- 备份文件：修改前 `try/change-tracking/backups/nostr-immersive-20260603-episode-swipe-index-before.html`；修改后 `try/change-tracking/backups/nostr-immersive-20260603-episode-swipe-index.html`

## 2026-06-03 16:44

- 功能/问题：`try/nostr-沉浸式.html` 打开分集剧情概要后，需要不用退出即可切换上/下一集，并同时支持手机触屏左右滑动和电视遥控器左右键。
- 变更内容：为剧情概要查看器新增当前季分集状态，点击分集时记录当前索引；将概要渲染拆成可复用函数，切集时直接重绘概要内容并回到顶部。新增 `imageContent` 横向触屏滑动识别，仅在 `imageViewer.episode-mode` 激活且横向位移明显时拦截，避免影响概要正文上下滚动。新增剧情概要模式下的遥控器 `ArrowLeft`/`ArrowRight` 处理，优先于普通焦点导航执行，左右键分别切换上一集/下一集；到边界时提示“已经是第一集/最后一集”。切集后同步返回焦点到对应分集卡片，关闭概要时回到当前集。
- 影响文件：`try/nostr-沉浸式.html`
- 备份文件：修改前 `try/change-tracking/backups/nostr-immersive-20260603-episode-swipe-before.html`；修改后 `try/change-tracking/backups/nostr-immersive-20260603-episode-swipe.html`

## 2026-06-03 11:51

- 功能/问题：需要把已有 Nostr 榜单原始事件镜像到新的 relay，让后续只配置新 relay 的用户也能读取尽量完整的榜单数据。
- 变更内容：未覆盖主线 `try/test.html`，新增独立版本 `try/test-relay-mirror.html`。该版本在状态面板增加“镜像源 Relays”“镜像目标 Relays”“镜像榜单”按钮和进度区；镜像时从源 relay 拉取 90 天内 `kind:30078`、`#t:fish2018-home-v1`、`#d:heat:user:90d:v2` 的原始已签名事件，过滤过期/无签名事件，按可替换事件地址保留每个用户最新事件，再原样发布到目标 relay。进度显示拉取页数、有效事件、按用户去重数量、发布 OK/拒绝/失败数量。该功能不伪造别人的数据，不自动启动，需要手动填写目标 relay 并点击执行。
- 影响文件：`try/test-relay-mirror.html`
- 备份文件：`try/change-tracking/backups/test-relay-mirror-20260603-115159.html`

## 2026-06-03 11:29

- 功能/问题：点击“刷新榜单”后仍看不出是成功、失败还是正在更新，榜单数量没变化时尤其容易误判。
- 变更内容：把“刷新”状态改为多行进度说明，明确显示当前阶段、连接数、订阅完成数、订阅事件数、近 7 天回填页数/事件数、历史回填页数/事件数、回填队列、本地榜单列表待更新和当前榜单条数；即使数据为 0 也显示 `0页/0条`。修正回填定时器触发后未清除等待标记的问题，避免完成状态被残留 timer 卡住；完成判断会等待榜单列表刷新定时器结束后再显示“已完成”，并在完成时提示当前榜单条数。刷新按钮文案跟随真实刷新状态，不再固定 1.2 秒后提前恢复。
- 影响文件：`try/test.html`
- 备份文件：修改前 `try/change-tracking/backups/test-20260603-112945-before-refresh-progress-clarity.html`；修改后 `try/change-tracking/backups/test-20260603-112945-refresh-progress-clarity.html`

## 2026-06-03 11:20

- 功能/问题：点击“刷新榜单”后只能看到 `无推荐数据`，无法判断是正在刷新、已完成、没有收到事件，还是本地索引不可用。
- 变更内容：状态面板新增“刷新”状态行，显示刷新阶段和进度：清理索引、连接 relay、订阅近 7 天、订阅事件数、近 7 天回填页数/事件数、历史回填页数/事件数、当前榜单数量、完成或本地索引不可用。将订阅、回填、写入索引和完成判断都接入进度更新，让用户能看到刷新是否仍在进行以及卡在哪个阶段。
- 影响文件：`try/test.html`
- 备份文件：修改前 `try/change-tracking/backups/test-20260603-112022-before-nostr-refresh-progress.html`；修改后 `try/change-tracking/backups/test-20260603-112022-nostr-refresh-progress.html`

## 2026-06-03 10:54

- 功能/问题：部分设备清理缓存或本地索引异常后，状态显示 relay 已连接但 `榜单 0`，需要用户可手动触发恢复 Nostr 榜单数据。
- 变更内容：在状态面板增加“刷新榜单”按钮；点击后使旧订阅/旧回填失效，清空本地 Nostr 榜单索引和 relay 回填游标并保留当前身份的本机向量，然后重新订阅 relay 并触发近 7 天订阅与 90 天历史回填。新增订阅 token 保护，避免手动刷新时旧 WebSocket/旧回填请求继续写入新索引或污染 relay 计数。该功能不删除 nsec 身份，不发布删除事件，不清 TMDB 缓存。
- 影响文件：`try/test.html`
- 备份文件：修改前 `try/change-tracking/backups/test-20260603-105419-before-force-nostr-refresh.html`；修改后 `try/change-tracking/backups/test-20260603-105419-force-nostr-refresh.html`

## 2026-06-02 22:49

- 功能/问题：TV 端主页搜索按钮长按仍不能打开 App 原生搜索，需要重新排查根因并移除上次无效修复。
- 变更内容：确认根因是 TV 遥控器 Enter/OK 在全局 `installRemoteKeys()` 的 `keydown` 阶段会立即走 `activateFocusedElement()` 点击搜索按钮，上次绑在按钮自身 `keydown`/`keyup` 的长按逻辑无法可靠抢在全局点击前生效；删除按钮自身的 `keydown`/`keyup` 监听和对应函数，改为复用“长按屏蔽”的全局按键模型：全局 `keydown` 对搜索按钮启动长按计时并阻止立即点击，全局 `keyup` 根据是否长按触发来执行原生搜索收尾或短按网页搜索。保留上一轮已修复的“搜索框输入后右键可直接到搜索按钮”逻辑。
- 影响文件：`try/test.html`
- 备份文件：修改前 `try/change-tracking/backups/test-20260602-224906-before-search-hold-global-rework.html`；修改后 `try/change-tracking/backups/test-20260602-224906-search-hold-global-rework.html`

## 2026-06-02 22:38

- 功能/问题：TV 端遥控器长按主页搜索按钮没有打开 App 原生搜索；搜索建议打开时，输入框按右键不能直接移动到搜索按钮。
- 变更内容：为 `searchSubmitBtn` 增加 Enter/OK 的 `keydown`/`keyup` 长按计时，长按打开原生搜索，短按仍执行网页内搜索，并加入短按 click 防重入；调整搜索建议打开时输入框右键分发，让 `ArrowRight` 回到搜索表单方向键逻辑，从输入框直接聚焦搜索按钮。未修改样式和性能优化逻辑。
- 影响文件：`try/test.html`
- 备份文件：修改前 `try/change-tracking/backups/test-20260602-223824-before-tv-search-hold.html`；修改后 `try/change-tracking/backups/test-20260602-223824-tv-search-hold-fixed.html`

## 2026-06-02 21:59

- 功能/问题：将已验证可用的性能优化合并回主线，同时将焦点视觉增强版从性能版本序列中拆出。
- 变更内容：把 `v4-aggressive.html` 的性能改动合并进 `try/test.html`：TV 渲染提示和轻量布局隔离、焦点滚动合帧、收窄后的主页同容器快速导航、TV 方向键轻量节流；未合并 `tv-focus-visual-strong.html` 的粗轮廓/放大视觉增强样式。将原 `v5-tv-focus-strong.html` 改名为 `tv-focus-visual-strong.html`，对应备份改名为 `visual-tv-focus-strong-20260602-215432.html`。
- 影响文件：`try/test.html`、`try/optimization-variants/tv-focus-visual-strong.html`、`try/optimization-variants/README.md`
- 备份文件：合并前主线 `try/change-tracking/backups/test-20260602-215950-before-v4-performance-merge.html`；视觉版备份 `try/change-tracking/backups/visual-tv-focus-strong-20260602-215432.html`

## 2026-06-02 21:54

- 功能/问题：参考 TV 焦点清晰度建议，生成一个粗轮廓和轻微放大版本，用于验证远距离遥控操作时的焦点可见性。
- 变更内容：未修改 `try/test.html`；基于可用的 `v4-aggressive.html` 新增视觉试验版，后续已改名为 `tv-focus-visual-strong.html`；只增强 TV 模式焦点视觉：卡片使用更粗 `outline`、轻微更大的 `transform: scale()`、内描边；按钮、状态按钮、搜索输入、建议项、盘搜结果增加明显轮廓；不通过加粗 `border-width` 改变布局尺寸，不修改方向键目标选择逻辑。
- 影响文件：`try/optimization-variants/tv-focus-visual-strong.html`、`try/optimization-variants/README.md`
- 备份文件：`try/change-tracking/backups/visual-tv-focus-strong-20260602-215432.html`

## 2026-06-02 21:36

- 功能/问题：`v3-fast-home-grid-nav.html` 开始出现搜索结果和状态按钮无法正常聚焦。
- 变更内容：未修改 `try/test.html`；收窄 `v3`、`v4` 的主页快速导航范围，删除 chips 向下直跳内容、网格首行向上直跳 chips、直播入口向上直跳 chips 等跨区域快速路径；保留同一容器内部快速移动，跨区域移动回退原 `nearestFocusable()`，避免抢走搜索结果/状态区焦点。
- 影响文件：`try/optimization-variants/v3-fast-home-grid-nav.html`、`try/optimization-variants/v4-aggressive.html`、`try/optimization-variants/README.md`
- 备份文件：问题版 `try/change-tracking/backups/variant-v3-broken-20260602-213645.html`、`try/change-tracking/backups/variant-v4-broken-20260602-213645.html`；修正版 `try/change-tracking/backups/variant-v3-fixed-20260602-213645.html`、`try/change-tracking/backups/variant-v4-fixed-20260602-213645.html`

## 2026-06-02 21:14

- 功能/问题：需要按低风险到完整优化生成多个可测试版本，便于在真实设备上分别验证性能和聚焦稳定性。
- 变更内容：未覆盖 `try/test.html`，在 `try/optimization-variants/` 下生成 `v0-current.html`、`v1-low-risk.html`、`v2-focus-scroll-batched.html`、`v3-fast-home-grid-nav.html`、`v4-aggressive.html` 和测试说明 `README.md`；`v1` 仅做渲染提示，`v2` 增加焦点滚动合帧，`v3` 增加 TV 主页快速导航，`v4` 增加完整优化和方向键节流。
- 影响文件：`try/optimization-variants/`
- 备份文件：`try/change-tracking/backups/test-20260602-211450-optimization-variants-baseline.html`

## 2026-06-02 20:36

- 功能/问题：继续做低风险性能优化，减少 TV/WebView 遥控器焦点移动时的重复布局计算和局部渲染影响。
- 变更内容：新增 `gridColumnCache` 缓存 `gridColumns()` 计算结果，并在 `fmviewport`、`resize` 时清空，减少重复读取 `gridTemplateColumns`；为分集卡片、人物卡片、盘搜结果项增加 `contain: layout style`，隔离局部布局/样式影响，不使用 `paint` 或 `content-visibility`，避免裁剪焦点高亮或影响可聚焦性。
- 影响文件：`try/test.html`
- 备份文件：`try/change-tracking/backups/test-20260602-203607-grid-column-cache-containment.html`

## 2026-06-02 20:07

- 功能/问题：参考卡顿优化建议做低风险性能优化，同时避免复现 `nostr_副本43-改造卡片聚焦算法.html` 中可能导致聚焦失效的全局焦点改造问题。
- 变更内容：TV 模式下进一步关闭 chip、按钮、输入框、连接状态按钮、盘搜结果项、人物信息等控件的 `backdrop-filter`，降低透明模糊绘制成本；`maybeAppendGridForFocus()` 优先读取卡片 `data-card-index`，失败时保留原扫描兜底；`isFirstVisiblePanResult()` 改为从第一个子节点向后找首个可见盘搜结果，避免每次上键构造完整数组。未改全局方向键导航、`focusRemoteTarget()`、搜索/连接/详情焦点分发。
- 影响文件：`try/test.html`
- 备份文件：`try/change-tracking/backups/test-20260602-200731-low-risk-performance-optimizations.html`

## 2026-06-02 19:45

- 功能/问题：手机端详情页返回按钮、剧情概要查看页返回按钮需要隐藏。
- 变更内容：在移动端样式中隐藏 `#closeDetailBtn` 和剧情概要模式下的 `#imageViewer.episode-mode #closeImageBtn`，仅隐藏视觉按钮，不移除 DOM 和现有返回键/点击逻辑，避免影响桌面/TV。
- 影响文件：`try/test.html`
- 备份文件：`try/change-tracking/backups/test-20260602-194556-mobile-hide-back-buttons.html`

## 2026-06-02 19:38

- 功能/问题：用户在主页向下翻多页后进入详情页，返回主页会回到顶部，丢失原位置。
- 变更内容：增加 `homeReturn` 返回点机制，进入详情前记录主页 `scrollY`、当前分类、媒体 key、所在 rail/grid 和卡片 index；关闭详情时分阶段恢复焦点和滚动位置，焦点使用 `preventScroll`，避免搜索结果卡片、状态按钮等已有焦点路径被滚动副作用打断；UI 快照同步保存 `homeReturn`，用于 App/WebView 恢复场景。
- 影响文件：`try/test.html`
- 备份文件：`try/change-tracking/backups/test-20260602-193852-home-return-scroll-restore.html`

## 2026-06-02 19:26

- 功能/问题：手机端详情页顶部剧照需要圆角；确认详情页图片/数据缓存机制。
- 变更内容：为非 TV、非大屏详情下的 `.detail-cover` 增加 14px 圆角、裁切和隔离层，并让内部图片/加载伪元素继承圆角。缓存机制仅做代码确认，未修改缓存逻辑。
- 影响文件：`try/test.html`
- 备份文件：`try/change-tracking/backups/test-20260602-192600-mobile-detail-cover-radius.html`

## 2026-06-02 16:58

- 功能/问题：手机端剧情概要卡片顶部仍显示“第几集”，灰色半透明条覆盖剧照。
- 变更内容：撤销“手机端分集剧情弹层标题去掉集数前缀”的 JS 改动，恢复弹层标题原格式；增加更高优先级的 `#detailSheet .episode-card .episode-badge` 隐藏规则，避免被 `.episode-card span` 覆盖，TV 大屏详情仍可显示角标。
- 影响文件：`try/test.html`
- 备份文件：`try/change-tracking/backups/test-20260602-165828-episode-badge-css-fix.html`

## 2026-06-02 16:50

- 功能/问题：手机端剧情概要顶部显示集数会漏出半透明灰色背景；详情简介过长时需要折叠；主页搜索按钮需要长按跳转 App 原生搜索。
- 变更内容：将详情大布局分集角标限制为 TV 模式显示；详情简介增加按文字长度触发的折叠阈值，并保留首屏空间不足时的折叠逻辑；主页搜索按钮增加长按检测，长按打开 App 原生搜索页，短按仍执行网页内 TMDB 搜索。
- 影响文件：`try/test.html`
- 备份文件：`try/change-tracking/backups/test-20260602-165045-mobile-episode-summary-search-hold.html`

## 2026-06-02 16:15

- 功能/问题：详情页剧照拼接边界线明显。
- 变更内容：参考 `try/history/test.html` 的近期实现，为 `detail-large` 的横向剧照恢复左侧半透明羽化遮罩、模糊过渡层和覆盖渐变，减少剧照与页面背景之间的硬边。
- 影响文件：`try/test.html`
- 备份文件：`try/change-tracking/backups/test-20260602-161523-detail-cover-feather.html`
