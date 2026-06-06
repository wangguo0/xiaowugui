# 待处理问题记录

## 2026-06-02 TV 样式兼容反馈

- 主页卡片间距过近：从截图看不是单纯错觉，搜索结果横向列表依赖 flex `gap`，旧 Android WebView 或个别电视 WebView 可能不支持 flex gap，导致卡片贴近。建议后续加 `.rail` 子项 `margin-right` fallback。性能影响很低，属于静态 CSS 布局兼容。
- 详情页按钮拥挤：截图中按钮间距几乎消失，也可能与 flex gap 兼容有关；另外部分 TV/WebView 的 CSS 视口或缩放异常时，`detail-large` 大屏样式会显得过大、偏挤。建议先做 flex gap fallback，如果仍有反馈，再考虑 TV compact 详情样式。后者是视觉取舍，需要单独确认。
- 当前暂不修改样式，先只修 TV 搜索交互问题。
