# WebView 开发注意事项

> 日期：2026-06-14  
> 背景：v0.0.25 实机 AB 测试确认，原生 `FrameLayout + WebView` 承载可让问题页面恢复正常；旧的 Compose `AndroidView` 宿主、全屏 Loading overlay 和高 DPR 样式注入会放大渲染风险。

## 1. 当前基线

- WebView 页面必须走 `WebViewActivity -> FrameLayout -> WebView`。
- 不再提供“标准 Compose / 轻量原生”切换；原生承载就是正式浏览器路径。
- WebView 使用系统硬件合成路径，`offscreenPreRaster=false`。
- 不再注入“高分屏渲染兼容” CSS/JS。实测该补丁会禁用动画、改写页面样式，导致交互表现和正常浏览器不一致。
- 加载提示只允许使用可选的顶部细进度条，避免全屏遮罩、淡出动画或额外 Compose 层参与网页首绘。

## 2. 不要再引入的模式

- 不要把真实网页 WebView 放进 Compose `AndroidView` 再叠全屏 Loading。
- 不要用通用 CSS/JS 去修改第三方页面的动画、阴影、`filter`、`will-change` 或布局，除非这是明确的站点级兼容补丁且有用户开关。
- 不要默认开启 URL 后台预加载或空白热备。保留开关，但默认关闭。
- 不要为了“兼容”切到 `LAYER_TYPE_SOFTWARE`。此前验证会让 `tile memory limits exceeded` 更严重。
- 不要重新开启 `offscreenPreRaster` 作为默认项。高 DPR 设备上它会提前栅格化离屏内容，增加 tile 压力。
- 不要把 `onPageFinished` 等同于“页面已经视觉可见”。如果后续重新做 Loading overlay，必须单独验证视觉提交和遮罩移除时机。

## 3. 保留配置项

家长后台“网页缓存与性能优化”只保留以下 WebView 相关控制：

- 网页顶部进度条：默认关闭；只显示顶部细进度条。
- 保留 1 个空白 WebView 热备：默认关闭。
- WebView 渲染模式：默认系统硬件合成路径。
- 网页后台预加载：默认关闭。
- 网页缓存与本地数据统计/清理。

这些选项改变后应清理 WebViewPool，避免旧实例携带旧设置。

## 4. 日志基线

排查 WebView 问题时，优先采集：

```bash
adb logcat -v time ChildKioskWebView:D ChildKioskApp:D MainActivity:D chromium:I cr_WebView:I AndroidRuntime:E '*:S'
```

关键日志：

- `Host mode applied: NATIVE_FRAME_LAYOUT`：确认走正式原生承载。
- `WebView diagnostics`：记录渲染模式、顶部进度条、热备/预加载、屏幕、density、进程和 heap。
- `WebView surface`：确认 WebView attach、尺寸、layer、parent 和 context。
- `Initial load after layout`：确认真实 URL 在 WebView 有有效尺寸后加载。
- `Page started` / `Page finished`：确认主 frame 生命周期。
- `tile memory limits exceeded`：Chromium renderer tile 预算不足，通常不是 App Java heap 单独能解决的问题。

## 5. 变更检查清单

任何 WebView 改动合入前至少确认：

- 同一 URL 在 App WebView 和手机浏览器 viewport/UA/Console 基本一致。
- 没有新增全屏遮罩、动画层或 Compose wrapper 参与网页首绘。
- 没有新增默认开启的预加载、热备、离屏预栅格化。
- 没有新增会修改第三方页面视觉/动画的通用注入脚本。
- `pages.anzz.site/app/piano/`、`pages.anzz.site/books` 能正常首屏渲染和交互。
- 返回键不会先回到 `about:blank`。

## 6. 后续可优化方向

- 如果继续优化热备复用，优先评估 Activity 级 WebView 复用或 `MutableContextWrapper` 换绑，避免 application context 创建真实 WebView 带来的 JS dialog、autofill 和生命周期问题。
- 如果需要更早的调试脚本注入，优先研究 AndroidX WebKit `DOCUMENT_START_SCRIPT`，不要恢复多轮延迟注入作为默认负担。
- 如果原生承载下仍出现 renderer tile warning，应优先定位页面自身合成成本或系统 WebView/驱动限制，而不是继续给宿主层增加补丁。
