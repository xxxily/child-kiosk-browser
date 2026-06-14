# Tauri Android WebView 对比研究

> 日期：2026-06-14  
> 背景：当前 App 在高 DPR 设备上打开部分网页时出现局部不绘制、交互异常或 loading 遮罩卡住；同一设备的系统浏览器或 Tauri 生成 APK 表现更正常。本文用于判断 Tauri 是否换了浏览器内核，以及本项目能借鉴什么。

## 1. 结论摘要

1. **Tauri Android 不是内置另一套 Chromium，也不是绕开 Android WebView。**  
   Tauri 官方文档明确说明 Android 上使用系统 `Android WebView`，不随 APK 打包 WebView，运行时版本取决于设备当前 WebView provider。

2. **Tauri 表现更正常，主要不是内核差异，而是宿主差异。**  
   WRY/Tauri 的 Android WebView 是直接继承系统 `android.webkit.WebView` 的 `RustWebView`，然后在 Activity 中直接 `setContentView(webview)`。它没有 Compose `AndroidView`、Compose overlay、动画 loading、多 WebView 热备池这些额外层。

3. **Tauri/WRY 的可借鉴点是“轻量、直接、标准化”的 WebView 宿主。**  
   重点不是迁移到 Tauri，而是在本项目中实现一个“轻量原生 WebView 承载模式”：原生 `FrameLayout + WebView`，首屏直接挂真实 WebView，loading 尽快移除或改用 `postVisualStateCallback`，关闭热备/预加载，避免 Compose 合成层参与网页首绘。

4. **Tauri 不能解决 Chromium renderer tile cache 本身的上限。**  
   如果日志仍然是 `tile memory limits exceeded, some content may not draw`，Tauri 也同样受系统 WebView renderer 预算约束。它最多通过更轻的宿主层降低额外压力，不提供公开方式“扩展 WebView renderer tile 内存”。

5. **本项目还有一个和 Tauri/Android 官方基线不一致的点：WebViewPool 使用 application context 创建 WebView。**  
   Android 官方文档建议 WebView 应始终使用 Activity Context 创建，否则 JS dialog、autofill 等功能可能不完整。当前默认热备已关闭，所以它不是当前必然根因，但后续如果继续做复用，需要改成 Activity 级复用或 `MutableContextWrapper` 严格换绑 Activity。

## 2. 资料来源与已确认事实

### 2.1 Tauri Android 使用系统 WebView

Tauri 官方 Webview Versions 文档说明：

- Android 上使用系统 `Android WebView`，该 WebView 基于 Chromium。
- Tauri 不把 WebView 打包进 App，运行时版本取决于设备当前选择的 WebView provider。
- 调试时仍然通过 Chrome Web Inspector / `chrome://inspect` 观察运行中的 WebView。

来源：<https://v2.tauri.app/reference/webview-versions/>

这意味着：如果同一设备上 Tauri APK 和本项目 APK 使用的是同一个 WebView provider，它们的 JS/CSS/Canvas/WebGL 内核能力基线应当基本一致。差异主要来自宿主层、配置和页面加载方式。

### 2.2 WRY 的 Android WebView 是系统 WebView 子类

WRY 源码中 `RustWebView` 直接继承 `WebView`：

- `class RustWebView(...): WebView(context)`
- 默认开启 `javaScriptEnabled`、`domStorageEnabled`、`databaseEnabled`、地理位置、媒体自动播放、`javaScriptCanOpenWindowsAutomatically`。
- 支持 AndroidX WebKit 的 `DOCUMENT_START_SCRIPT` 时，用 `WebViewCompat.addDocumentStartJavaScript` 在 document start 注入初始化脚本。

来源：<https://github.com/tauri-apps/wry/blob/dev/src/android/kotlin/RustWebView.kt>

这说明 Tauri 的优势不是“新内核”，而是封装出来的默认配置和更早的初始化脚本注入点。

### 2.3 WRY 直接把 WebView 设为 Activity content view

WRY 的 `main_pipe.rs` 创建 WebView、设置 WebViewClient/WebChromeClient、添加 IPC interface 后，直接调用 Android Activity 的 `setContentView(webview)`。

来源：<https://github.com/tauri-apps/wry/blob/dev/src/android/main_pipe.rs>

这和本项目当前链路不同：

```text
本项目：ComponentActivity -> Compose setContent -> Surface -> Box -> AndroidView -> WebView
WRY：WryActivity/AppCompatActivity -> setContentView(WebView)
```

如果问题和首绘合成层、Compose `AndroidView` 测量/attach 时序、loading overlay 有关，WRY 的结构天然更接近“系统浏览器里一个独立内容视图”的形态。

### 2.4 WRY 使用 WebViewAssetLoader 处理本地资源

WRY 的 `RustWebViewClient` 内部使用 AndroidX `WebViewAssetLoader`，可以把本地 assets 映射为 http(s) URL。

来源：<https://github.com/tauri-apps/wry/blob/dev/src/android/kotlin/RustWebViewClient.kt>

Android 官方说明 `WebViewAssetLoader` 的目标是让 WebView 用 `http(s)://` URL 加载 App 静态资源，避免 `file://` 带来的同源策略问题。默认保留域名是 `appassets.androidplatform.net`。

来源：<https://developer.android.com/reference/androidx/webkit/WebViewAssetLoader>

注意：这对 Tauri 打包的本地前端很重要，但对当前几个远程 URL：

- `https://pages.anzz.site/app/piano/`
- `https://img-playground.anzz.site/`
- `https://pages.anzz.site/books`

帮助有限。远程页面本身已经是 HTTPS，不需要 asset loader 才能获得正常 origin。

### 2.5 Android WebView 的 renderer priority 不是 tile cache 扩容

Android 官方 `WebView.setRendererPriorityPolicy` 文档说明，该策略用于决定 out-of-process renderer 在内存紧张时是否更容易成为 OOM kill 目标。`RENDERER_PRIORITY_IMPORTANT` 只是让 renderer 更不容易被系统杀掉，不是提高 tile cache 上限。

来源：<https://developer.android.com/reference/android/webkit/WebView>

因此本项目 v0.0.23 中：

```text
setRendererPriorityPolicy(RENDERER_PRIORITY_IMPORTANT, true)
largeHeap=true
WebViewActivity 独立 :webview 进程
```

能改善宿主进程和 renderer 存活概率，但不能直接消除 `tile memory limits exceeded`。

### 2.6 offscreenPreRaster 的内存风险是官方确认的

Android 官方 `WebSettings.setOffscreenPreRaster` 文档说明：该模式会让 attached 但 offscreen 的 WebView 预先 raster tiles，可以避免某些动画进场伪影，但会使用更多内存；默认值是 `false`，建议限制 WebView 尺寸不要超过屏幕，并限制开启数量。

来源：<https://developer.android.com/reference/android/webkit/WebSettings>

本项目已经把默认值改回 `false`，这是正确基线。

## 3. Tauri/WRY 与本项目当前实现对比

| 维度 | Tauri/WRY Android | 本项目当前实现 | 风险判断 |
| --- | --- | --- | --- |
| 浏览器内核 | 系统 Android WebView | 系统 Android WebView | 内核不是关键差异 |
| WebView 类 | `RustWebView : WebView` | `android.webkit.WebView` | 本质一致 |
| Activity 承载 | `WryActivity/AppCompatActivity`，直接 `setContentView(webview)` | `ComponentActivity` + Compose + `AndroidView` | 本项目宿主层更重 |
| 首屏 overlay | 默认没有 App 自己的 Compose loading 遮罩 | Compose `LoadingOverlay` 覆盖在 WebView 上 | 可能放大首绘合成压力，也可能误判为页面没进 |
| WebView 创建 Context | Activity context | 主 WebView 是 Activity context；`WebViewPool` 是 application context | 热备/复用路径需要修正 |
| 预加载/复用 | Tauri 默认不是为任意 URL 做热备池 | 有空白热备池、URL 预加载配置，默认已关闭 | 默认关闭正确；后续复用要保守 |
| 初始化脚本 | 支持 `DOCUMENT_START_SCRIPT` 时走 document start | 多时机 `evaluateJavascript` 兜底 | 可借鉴 document start 注入 |
| 本地资源 | `WebViewAssetLoader` / custom protocol | 远程 URL 直连；本地资源暂无统一 asset loader | 本地打包页面可借鉴 |
| 多窗口 | Tauri mobile 多窗口可用独立 Activity / Activity Embedding | 当前同 Activity 内维护 WebView stack | 本项目多 WebView stack 更容易产生测量/内存复杂度 |
| Kiosk 能力 | 需要自写 Android 插件/原生壳 | 已有 Device Owner、锁定、限制项、白名单 | 迁移 Tauri 成本高 |

## 4. 为什么 Tauri APK 可能渲染正常

这里的判断分为“已确认”和“推测，需要 A/B 验证”。

### 已确认

1. **不是 Tauri 换了 Chromium。**  
   Tauri Android 仍是系统 Android WebView。

2. **Tauri 的宿主链路更短。**  
   WRY 直接把 WebView 作为 Activity content view，本项目则通过 Compose `AndroidView` 承载，并叠加多个 Compose overlay/动画状态。

3. **Tauri 的初始化脚本时机更早。**  
   WRY 在支持时使用 AndroidX `DOCUMENT_START_SCRIPT`。本项目目前主要通过 `evaluateJavascript` 在 `onPageStarted`、`onPageFinished` 和延迟 pass 注入，时机更晚，对早期 feature probing、首屏计算、调试面板注入稳定性不如 document start。

4. **Tauri 面向的是“一个 App 的前端壳”，不是“任意网页浏览器”。**  
   Tauri 的网页通常是自己打包的前端，资源、CSP、权限、页面复杂度都可控。本项目要加载任意白名单远程网页，还要做沙箱、拦截、下载、权限、返回栈、家长控制、限时等额外逻辑。

### 需要 A/B 验证

1. **Compose `AndroidView` 是否放大 tile 压力。**  
   目前日志显示 Chromium renderer tile memory 超限。Compose 本身不直接控制 renderer tile cache，但它可能通过额外 Surface/RenderNode/overlay、首屏 attach 时序、动态背景和动画，让 WebView 首帧附近的整体合成压力变高。

2. **Loading overlay 是否只是视觉遮挡。**  
   有些“卡在 100%”可能不是网页未渲染，而是 App overlay 状态没有及时消失。当前已有 12 秒兜底，但仍建议用 `postVisualStateCallback` 替代单纯依赖 `onPageFinished/progress=100`。

3. **多 WebView stack 是否增加峰值内存。**  
   `target="_blank"`、热备、预加载、旧 WebView 未完全销毁时，可能让 renderer 同时服务多个 WebView。Tauri 多窗口更多走独立 Activity，生命周期边界更清晰。

## 5. 不建议直接迁移 Tauri 的原因

1. **不能保证解决当前 tile memory warning。**  
   因为 Tauri 使用同一个系统 WebView provider。只要页面复杂度、DPR、屏幕像素和 Chromium renderer tile 预算相同，底层限制仍在。

2. **Kiosk/Device Owner 能力迁移成本很高。**  
   当前项目已有 Device Owner、Lock Task、系统限制、白名单、限时、家长验证、缓存管理、UA 配置、调试注入等 Android 原生能力。迁到 Tauri 后仍要在 Kotlin 插件里重新接入这些能力。

3. **Tauri 适合“自有前端 App”，不是最适合“儿童白名单浏览器”。**  
   本项目核心是受控浏览器和设备管控，不只是一个 HTML 前端壳。Tauri 的安全模型、capabilities、插件机制很有价值，但不是直接替代当前原生 Android 架构的低成本方案。

4. **包体、构建链和调试复杂度会上升。**  
   Tauri 引入 Rust、cargo、NDK、移动端桥接和插件体系。对当前问题而言，先做 Tauri-like 原生轻量宿主，收益/成本比更好。

## 6. 推荐借鉴方案：轻量原生 WebView 承载模式

建议新增一个实验配置：

```text
网页性能优化 -> WebView 承载模式
- 标准 Compose 承载：当前实现，兼容已有 UI/overlay/多窗口逻辑
- 轻量原生承载：实验模式，直接原生 View 承载 WebView
```

### 6.1 轻量原生承载目标

1. Activity 内不使用 Compose `setContent`。
2. 根布局使用 `FrameLayout`。
3. WebView 用 Activity context 创建，并作为根布局第一个全屏子 View。
4. Loading 使用普通 Android View 或尽量不显示全屏 overlay；如果显示，`postVisualStateCallback` 后立即移除。
5. 默认禁用热备和 URL 预加载；轻量模式下每次打开真实页面都创建新 WebView，退出直接销毁。
6. 沿用当前 `WebViewRuntime.applySettings()`，保证 WebSettings 基线一致。
7. 沿用当前安全/下载/权限/WebChromeClient/WebViewClient 能力，但先避免多 WebView stack，`target="_blank"` 可先同 WebView 顶层导航或单独 Activity 打开。

### 6.2 轻量原生承载的验证日志

新增日志字段：

```text
Host mode applied: LIGHTWEIGHT_NATIVE
WebView context: WebViewActivity
Root container: FrameLayout
Compose host: false
Overlay mode: NONE | NATIVE_MINIMAL
Warm pool: disabled
Url preload: disabled
```

继续保留：

```text
Render mode applied: requested=..., actual=HARDWARE, screen=..., density=..., process=...
High DPR render compat injected: result=...
Page started / Page finished
chromium tile_manager WARNING
```

### 6.3 A/B 判定方法

同一设备、同一 WebView provider、清缓存后按如下顺序测试：

1. `标准 Compose 承载 + 高分屏兼容自动`
2. `标准 Compose 承载 + 高分屏兼容关闭`
3. `轻量原生承载 + 高分屏兼容自动`
4. `轻量原生承载 + 高分屏兼容关闭`

每组打开：

- `https://pages.anzz.site/app/piano/`
- `https://pages.anzz.site/books`
- `https://img-playground.anzz.site/`

观察：

- 首屏是否完整。
- 是否还有 loading 100% 遮挡。
- 5 秒内 `tile memory limits exceeded` 次数。
- Chrome Inspect 的 `innerWidth/innerHeight/dpr/docScrollHeight` 是否一致。
- 手势返回是否出现空白页。

判定：

| 结果 | 解释 | 下一步 |
| --- | --- | --- |
| 轻量模式明显减少 warning / 页面恢复 | 宿主层和 overlay 确实放大了压力 | 将轻量模式作为高 DPR 默认或推荐 |
| 轻量模式 warning 数量接近，但兼容注入有效 | 主要是页面合成成本问题 | 继续做页面侧高 DPR 兼容策略 |
| 轻量模式和兼容注入都无效 | 系统 WebView renderer 预算/驱动限制占主导 | 评估 Chrome Custom Tabs、GeckoView 或站点侧降复杂度 |
| 只有 Tauri 正常、本项目轻量仍异常 | 继续对比 Tauri 具体 WebSettings/UA/系统栏/viewport/权限差异 | 抽样生成 Tauri demo 加载同 URL，抓同样日志 |

## 7. WebView 复用策略修正建议

当前默认已经关闭热备和 URL 预加载，这是正确方向。后续如果继续做复用，应按以下规则收敛：

1. **不要在全局 application context 下创建 WebView。**  
   Android 官方文档要求 WebView 使用 Activity Context。当前 `WebViewPool.init(context.applicationContext)` 后创建热备 WebView，与官方基线不一致。

2. **复用只做“同 Activity 生命周期内”的短周期复用。**  
   不跨 Activity、不卡全局静态池。Activity 销毁时 WebView 全部销毁。

3. **优先复用初始化配置，不复用真实页面状态。**  
   真实 URL WebView 不回池。避免 history、service worker、JS 全局状态、权限状态污染下一个页面。

4. **高 DPR 设备默认禁用复用。**  
   对 `density>=3.5` 且物理像素超过 400 万的设备，默认把内存预算全部留给当前可见页面。

5. **如果必须预热，使用 `MutableContextWrapper` 并在 attach 前切换到 Activity context。**  
   这条需要谨慎实现和充分测试；比起直接全局热备，风险更低，但仍不建议作为默认策略。

## 8. 其他可借鉴点

### 8.1 Document Start 脚本注入

WRY 使用 `WebViewCompat.addDocumentStartJavaScript`。本项目可把 Eruda/vConsole loader、自定义脚本和高 DPR CSS 的“最早注入”改为：

```kotlin
if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
    WebViewCompat.addDocumentStartJavaScript(webView, script, setOf("*"))
} else {
    // 保留 onPageStarted/onPageFinished/延迟 pass 兜底
}
```

收益：

- SPA 早期初始化阶段即可拿到调试/兼容脚本。
- 减少“前几个页面有 Eruda，后面页面没有”的时机问题。
- 高 DPR CSS 可以更早参与首屏样式计算。

风险：

- 注入脚本需要幂等。
- 不同 Android WebView provider 对 AndroidX WebKit feature 支持可能不同，必须保留兜底。

### 8.2 本地页面使用 WebViewAssetLoader

如果未来把部分儿童应用打包进 APK，建议使用 `WebViewAssetLoader`，不要用 `file://android_asset`。

收益：

- 更接近正常 HTTPS origin。
- 避免 file origin 下的同源、fetch、storage、service worker 等异常。
- 与 Tauri/WRY 的本地资源承载思路一致。

### 8.3 多窗口按 Activity 边界处理

Tauri mobile 的多窗口方案强调 Android Activity 边界。当前项目在一个 Compose screen 里维护 `WebView` stack，功能灵活，但内存和返回栈复杂。

建议轻量模式先做更保守策略：

- `target="_blank"` 默认同 WebView 顶层打开，减少 WebView 实例数。
- 如果确实需要新窗口，使用新的轻量 Activity 承载，而不是同一 root 里叠多个 WebView。

## 9. 替代方案对比

| 方案 | 是否换内核 | 对当前问题帮助 | 代价 | 建议 |
| --- | --- | --- | --- | --- |
| 当前原生 WebView + 轻量宿主 | 否 | 高，能验证宿主层是否是关键因素 | 低到中 | 优先做 |
| 完整迁移 Tauri | 否 | 不确定，宿主更轻但内核相同 | 高 | 不建议近期做 |
| Chrome Custom Tabs | 近似 Chrome 浏览器 | 可能高，接近用户说的“手机浏览器正常” | Kiosk 控制、注入、拦截能力弱 | 可作为受信任站点外部打开选项 |
| Trusted Web Activity | Chrome/TWA | 对自有 PWA 高 | 仅适合自有站点，不适合任意白名单浏览器 | 不适合作主浏览器 |
| GeckoView | 是，Firefox/Gecko | 可能绕开 Chromium tile 问题 | 包体大、兼容/调试/安全策略重做 | 作为最后备选 |
| CEF Android | 是/半成品生态 | 不确定 | 维护成本很高 | 不建议 |

## 10. 下一步推荐执行顺序

1. **实现轻量原生 WebView 承载模式。**  
   这是最接近 Tauri/WRY 的低成本验证，不改变核心业务架构。v0.0.25 起已按 AB 测试路径实现，可在“网页性能优化 -> WebView 承载模式（AB 测试）”中切换。

2. **修正 WebViewPool 的 Context 基线。**  
   禁止 application context 创建真实 WebView；高 DPR 下保持默认无热备、无 URL 预加载。

3. **接入 document start 注入。**  
   对调试面板、高 DPR CSS 和自定义脚本优先使用 AndroidX `DOCUMENT_START_SCRIPT`，保留现有多 pass 兜底。

4. **接入 `postVisualStateCallback` 控制 loading 消失。**  
   用视觉提交回调判断 WebView 已经提交一帧，减少 100% overlay 卡住。

5. **建立 Tauri demo 对照样本。**  
   只做最小 Tauri Android demo，加载同一远程 URL，抓相同 logcat 和 Inspect 数据。若 Tauri demo 仍无 warning，再继续逐项 diff WebSettings、Activity theme、system UI、UA、viewport 和页面缩放。

## 11. 当前判断

当前最合理的工程判断是：

```text
不要先迁移 Tauri。
先把本项目的 WebViewActivity 做出一个 Tauri-like 的轻量原生承载模式。
如果轻量模式能解决或显著缓解，再把它作为高 DPR 设备默认路径。
如果轻量模式无效，再证明问题主要在 Chromium renderer tile 预算或页面本身合成成本，届时再评估 GeckoView / Custom Tabs / 站点侧降复杂度。
```

这个路线能最快回答一个关键问题：**问题到底是宿主层放大了 WebView 压力，还是系统 WebView renderer 对这些页面在该设备上本身就扛不住。**

## 12. 参考链接

- Tauri Webview Versions：<https://v2.tauri.app/reference/webview-versions/>
- Tauri Architecture：<https://v2.tauri.app/concept/architecture/>
- Tauri Mobile Multi-Window：<https://v2.tauri.app/learn/mobile-multiwindow/>
- WRY README：<https://github.com/tauri-apps/wry/blob/dev/README.md>
- WRY `RustWebView.kt`：<https://github.com/tauri-apps/wry/blob/dev/src/android/kotlin/RustWebView.kt>
- WRY `RustWebViewClient.kt`：<https://github.com/tauri-apps/wry/blob/dev/src/android/kotlin/RustWebViewClient.kt>
- WRY `WryActivity.kt`：<https://github.com/tauri-apps/wry/blob/dev/src/android/kotlin/WryActivity.kt>
- WRY `main_pipe.rs`：<https://github.com/tauri-apps/wry/blob/dev/src/android/main_pipe.rs>
- Android `WebView`：<https://developer.android.com/reference/android/webkit/WebView>
- Android `WebSettings`：<https://developer.android.com/reference/android/webkit/WebSettings>
- AndroidX `WebViewAssetLoader`：<https://developer.android.com/reference/androidx/webkit/WebViewAssetLoader>
