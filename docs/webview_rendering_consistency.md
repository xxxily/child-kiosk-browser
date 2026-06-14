# WebView 渲染异常复盘与一致性优化记录

> 文档版本：1.2
> 创建日期：2026-06-14  
> 状态：已按 7.1 默认基线完成代码对齐；浏览器沙箱限制默认兼容优先；WebView tile 内存高压选项默认关闭
> 关联代码：[`WebViewRuntime.kt`](../app/src/main/java/com/example/childkiosk/util/WebViewRuntime.kt)、[`WebViewActivity.kt`](../app/src/main/java/com/example/childkiosk/WebViewActivity.kt)、[`AdminConsoleScreen.kt`](../app/src/main/java/com/example/childkiosk/ui/AdminConsoleScreen.kt)  
> 相关文档：[`webview_white_screen_optimization.md`](./webview_white_screen_optimization.md)、[`webview_debugging_runbook.md`](./webview_debugging_runbook.md)

---

## 1. 问题摘要

用户反馈：同一批网页在手机自带浏览器中可以正常打开和渲染，但在本应用 WebView 中只渲染一部分界面，或者部分交互功能异常。典型样本：

- `https://pages.anzz.site/app/piano/`
- `https://img-playground.anzz.site/`
- `https://pages.anzz.site/books`
- `https://pages.anzz.site/books/人生哲理/老祖宗留下的10句话，说尽人生百态.html`

其中 `pages.anzz.site/books` 系列页面的表现最典型：首屏布局异常，进入文章页后内容卡片、目录等模块看起来“没有渲染出来”。`pages.anzz.site/app/piano/` 的最新取证则显示：页面已经 `readyState=complete`，Network 与 Console 均无异常，但 logcat 出现 Chromium tile 内存超限。

这类问题和传统“白屏”不同：

- 白屏：网页还没绘制出有效内容，通常与冷启动、网络、首帧、遮罩时机有关。
- 本次异常：DOM 和 CSS 大概率已经加载，但某些元素处于“默认隐藏/等待可见性触发”的状态，页面逻辑没有按预期把它们切换到可见态。

---

## 2. 直接根因

已确认的问题来源不止一类。早期修复处理的是视口/懒显示触发问题；最新 logcat 证据进一步确认，部分页面还会被 WebView 合成器 tile 内存预算影响。

### 2.1 WebView 被配置成了非手机浏览器式视口

修复前 [`WebViewRuntime.applySettings()`](../app/src/main/java/com/example/childkiosk/util/WebViewRuntime.kt) 中启用了：

```kotlin
useWideViewPort = true
loadWithOverviewMode = true
```

这两个设置在很多“把桌面网页缩放到手机屏幕”的老式 WebView 场景下有用，但它们不适合现代移动响应式页面作为默认值。

根据 Android WebSettings 文档：

- `setUseWideViewPort(true)` 会让 WebView 支持 viewport meta tag；当页面未提供有效 width 时会使用 wide viewport。`false` 时布局宽度始终等于 WebView 控件的 CSS 像素宽度。
- `setLoadWithOverviewMode(true)` 会在内容宽度超过 WebView 控件宽度时按宽度缩放页面。

问题在于现代移动页通常已经声明：

```html
<meta name="viewport" content="width=device-width, initial-scale=1.0, viewport-fit=cover">
```

这类页面期待浏览器提供“真实手机视口”。如果宿主 WebView 再叠加 wide viewport/overview 缩放，会改变页面启动时的 CSS viewport、缩放比例、首屏高度和元素可见性判断。依赖 `100vh/100dvh`、`visualViewport`、滚动监听、懒加载、入场动画的页面都可能受影响。

### 2.2 目标页面内容依赖 IntersectionObserver 才变为可见

对文章页源码进行检查后，发现核心内容卡片默认是隐藏状态：

```css
.wisdom-card {
  opacity: 0;
  transform: translateY(24px);
}

.wisdom-card.visible {
  opacity: 1;
  transform: translateY(0);
}
```

页面底部脚本通过 `IntersectionObserver` 判断卡片是否进入视口，进入后才添加 `.visible`：

```js
const cards = document.querySelectorAll('.wisdom-card');
const observer = new IntersectionObserver((entries) => {
  entries.forEach(entry => {
    if (entry.isIntersecting) {
      entry.target.classList.add('visible');
      observer.unobserve(entry.target);
    }
  });
}, { threshold: 0.1, rootMargin: '0px 0px -40px 0px' });

cards.forEach(card => observer.observe(card));
```

所以页面“看起来没渲染”的真实机制是：元素存在，但 CSS 初始状态是透明的；如果 IntersectionObserver 没有收到符合预期的视口交叉回调，元素就会一直保持 `opacity: 0`。

MDN 对 Intersection Observer 的定义也说明了这一点：它是异步观察目标元素与祖先元素或顶层文档 viewport 交叉变化的 API。换句话说，它非常依赖 viewport、滚动状态、布局完成时机和浏览器生命周期事件。

### 2.3 Chromium tile 内存预算被打爆，导致内容跳过绘制

用户针对 `https://pages.anzz.site/app/piano/` 提供的 logcat 中，关键证据是：

```text
Page finished: progress=100, canGoBack=false, url=https://pages.anzz.site/app/piano/
WARNING: tile memory limits exceeded, some content may not draw
```

同一次 Chrome Inspect 采集结果显示：

- `readyState=complete`
- `innerWidth=360`、`innerHeight=764`、`devicePixelRatio=4`
- `visualViewport.scale=1`
- `bodyChildren=2`、`bodyTextLength=645`
- `IntersectionObserver/ResizeObserver/visualViewport/WebGL` 均存在
- Network 无异常，Console 无脚本报错

这说明该页面不是没加载、不是 UA 分流错误、不是 viewport 为 0，也不是脚本崩溃。页面已经完成主 frame 加载，DOM 与运行环境正常，但 Chromium 的 compositor/tile manager 在栅格化阶段因为内存预算不足，选择跳过部分 tile 绘制，所以用户看到的是“加载完成但一部分界面没画出来”。

本项目此前把 `WebSettings.offscreenPreRaster` 全局设为 `true`。该选项会让 WebView 对屏幕外内容提前栅格化，适合少数即将滑入可见区域的 WebView，但会增加 tile 内存占用。对 `DPR=4`、物理尺寸 `1440x3056` 这类高分屏设备来说，每个可见区域和离屏区域的 tile 都更贵；再叠加空白 WebView 热备、页面自身 Canvas/CSS 合成层，就可能触发 `tile memory limits exceeded`。

---

## 3. 为什么控制台没有报错

这不是典型 JavaScript 异常。

页面脚本可以正常执行，DOM 也可以正常创建，但页面作者把一部分内容设计为“进入视口后才显示”。当触发条件没发生时，浏览器不会报错，因为从页面逻辑看，这只是“还没进入视口”。

这类问题常见表现：

- 控制台无红色报错。
- Elements 面板能看到节点。
- 节点尺寸可能正常，但 `opacity: 0`、`visibility: hidden`、`transform` 还停留在入场前状态。
- 手动触发 resize/scroll、轻微滚动一下、打开 DevTools 或切前后台后，内容有时突然恢复。

---

## 4. 为什么手机浏览器正常，App WebView 异常

Android System WebView 和手机浏览器可能使用相近的 Chromium 内核，但两者不是同一个运行环境。差异主要来自宿主。

### 4.1 视口与缩放策略不同

手机浏览器默认按移动浏览器模式解释 viewport。WebView 则由 App 显式配置 `WebSettings`。一旦 App 启用 wide viewport 和 overview mode，就可能让页面启动在不同的 CSS viewport/scale 下。

### 4.2 生命周期事件不同

手机浏览器有完整的 tab 生命周期、地址栏折叠/展开、前后台切换、可见性状态管理。嵌入式 WebView 只是一个原生 View，挂载在 Activity/Compose `AndroidView` 中。WebView attach、measure、layout、首帧绘制、页面脚本执行之间存在时序差。

Android 官方文档明确提醒：收到 `onPageFinished()` 并不保证下一帧 WebView 绘制已经反映当前 DOM 状态；如需确认视觉状态，需要使用 `WebView.postVisualStateCallback()` 这类机制。

### 4.3 UA 与站点识别不同

WebView 默认 UA 通常带有 WebView 标识，例如 `; wv`、`Version/4.0`。一些站点会根据 UA 判断是浏览器还是内嵌容器，然后切换降级代码、禁用功能或走不同样式分支。

此外 Android WebView 的 UA 还在持续演进。Google 在 WebView User-Agent Reduction 说明中提到，自定义 UA 仍可通过 `setUserAgentString()` 使用，但自定义 UA 也会影响 User-Agent Client Hints 和 `navigator.userAgentData`。

### 4.4 宿主安全策略可能拦截资源

本项目为了儿童安全加入了 URL 白名单、广告过滤、混合内容限制、SSL 校验、文件访问限制、第三方 Cookie 配置、下载限制等。即使这些开关都关闭，也要考虑：

- 是否仍有某个资源被 `shouldInterceptRequest()` 拦截。
- 是否被混合内容策略阻止。
- 是否因第三方 Cookie、Storage、权限或跨域鉴权导致某些子资源不可用。
- 是否页面使用 `window.open()`、多窗口、下载、地理位置、媒体权限等浏览器能力。

### 4.5 WebView 复用带来的状态残留

WebView 创建成本较高，复用可以改善性能，但复用真实加载过页面的 WebView 风险很高：

- UA、history、JS bridge、缓存、scroll、页面进程状态可能残留。
- WebView parent/visibility/destroy 时序不正确时，会出现第二个页面卡 0%、加载回调丢失、旧页面状态污染新页面等问题。

当前项目已经调整为：真实页面 WebView 不回收到全局热备池；空白 WebView 热备默认关闭，仅在用户手动开启时保留。

---

## 5. 本次修复方案

### 5.1 修正默认视口策略

文件：[`WebViewRuntime.kt`](../app/src/main/java/com/example/childkiosk/util/WebViewRuntime.kt)

修复后：

```kotlin
cacheMode = WebSettings.LOAD_DEFAULT
useWideViewPort = false
loadWithOverviewMode = false
textZoom = 100
```

原则：

- 默认以手机浏览器式视口为基线。
- 不再把所有网页默认按 overview 缩放到屏幕宽度。
- 避免破坏现代移动页的 viewport、`100dvh`、IntersectionObserver、懒加载和滚动动画判断。

如果未来确实遇到只适配桌面宽布局的老网页，可以做“单站点兼容开关”，而不是全局开启。

### 5.2 UA 解析集中化，防止复用残留

文件：[`WebViewRuntime.kt`](../app/src/main/java/com/example/childkiosk/util/WebViewRuntime.kt)

修复后新增统一入口：

```kotlin
fun resolveUserAgent(context: Context, defaultUserAgent: String): String {
    val customUserAgent = KioskPrefs.getCustomUserAgent(context).trim()
    if (customUserAgent.isNotEmpty()) {
        return customUserAgent
    }
    return if (KioskPrefs.isUseBrowserUserAgentEnabled(context)) {
        browserLikeUserAgent(defaultUserAgent)
    } else {
        defaultUserAgent
    }
}
```

同时在 `applySettings()` 中使用 `WebSettings.getDefaultUserAgent(context)` 作为基线，而不是复用 WebView 当前的 `userAgentString`。这样可以避免热备 WebView 重复 `applySettings()` 后把已经处理过的 UA 当成新默认值。

### 5.3 页面完成后补偿视口/可见性事件

文件：[`WebViewActivity.kt`](../app/src/main/java/com/example/childkiosk/WebViewActivity.kt)

在 `onPageFinished()` 和 `onProgressChanged >= 100` 后调度：

```kotlin
schedulePageActivation(view)
```

实际注入逻辑会分几次触发：

- `focus`
- `pageshow`
- `resize`
- `scroll`
- `visibilitychange`
- `visualViewport.resize`
- `visualViewport.scroll`
- 极小幅度 `scrollTop + 1` 后回滚

目的：

- 让页面重新计算 viewport/visualViewport。
- 触发依赖 scroll/resize/visibility 的组件初始化。
- 促使 IntersectionObserver、懒加载图片、滚动进度条、目录定位等组件重新评估当前首屏。

这是通用补偿，不硬编码 `pages.anzz.site` 或 `.wisdom-card`。如果未来其他页面也依赖滚动揭示、懒加载、可见性观察，仍可受益。

### 5.4 UA 可见、可编辑

文件：[`AdminConsoleScreen.kt`](../app/src/main/java/com/example/childkiosk/ui/AdminConsoleScreen.kt)

管理后台“安全沙箱与限制”现在显示：

- 系统默认 WebView UA。
- 当前实际使用 UA。
- 是否启用“手机浏览器 UA”。
- 自定义 User-Agent 输入框。

这样后续遇到站点按 UA 分流时，可以快速确认真实请求头/JS 环境，而不是只能猜。

### 5.5 降低 WebView tile 内存压力

文件：[`WebViewRuntime.kt`](../app/src/main/java/com/example/childkiosk/util/WebViewRuntime.kt)、[`KioskPrefs.kt`](../app/src/main/java/com/example/childkiosk/util/KioskPrefs.kt)、[`WebViewPool.kt`](../app/src/main/java/com/example/childkiosk/util/WebViewPool.kt)、[`AdminConsoleScreen.kt`](../app/src/main/java/com/example/childkiosk/ui/AdminConsoleScreen.kt)

最新修复后：

```kotlin
offscreenPreRaster = KioskPrefs.isWebViewOffscreenPreRasterEnabled(context)
```

默认值为 `false`，并在“网页性能优化”里作为高级开关提供。原因：

- 它不是浏览器兼容必需项。
- 它会增加 WebView tile 预栅格化内存。
- 高分屏设备上 `DPR=4` 会显著放大 tile 成本。
- logcat 已经明确出现 `tile memory limits exceeded, some content may not draw`。

同时，空白 WebView 热备池默认值从开启调整为关闭。热备只优化冷创建耗时，但会提前初始化 Chromium/WebView 资源；当系统已经触发 `Trim memory critical` 时，默认热备会挤占真实页面的绘制预算。现在策略是：

- 默认不保留热备 WebView。
- 用户设备内存充足、追求打开速度时可手动开启。
- 真实页面 WebView 仍然关闭即销毁，不回收到全局池。

多窗口栈也改为只把当前顶层 WebView attach 到 Compose/Window。底层 WebView 实例保留在栈里，但不继续作为隐藏原生 View 参与窗口测量和绘制，降低多个 WebView surface 同时存在时的 tile 压力。

---

## 6. 当前仍可能导致渲染异常的风险点

即使完成以上修复，WebView 仍可能与手机浏览器出现差异。后续排查时重点看以下方向。

### 6.1 被拦截或失败的子资源

症状：

- 页面结构有，但样式丢失。
- 图片、字体、wasm、worker、module script 不加载。
- 控制台可能只有 warning，甚至没有明显错误。

排查：

- 打开 Chrome Inspect 的 Network 面板。
- 记录 `shouldInterceptRequest()` 中被拦截的 URL、host、mime。
- 临时关闭广告过滤、外链限制、混合内容严格模式、SSL 强校验做 A/B。

### 6.2 混合内容和跨域鉴权

HTTPS 页面加载 HTTP 子资源时，WebView 的 `mixedContentMode` 会直接影响结果。第三方登录、嵌入组件、跨域资源鉴权则可能依赖第三方 Cookie 和 Storage。

当前基线：

- HTTP 主页面：`MIXED_CONTENT_ALWAYS_ALLOW`
- HTTPS 主页面：默认兼容模式；如果用户显式开启严格阻止，则用 `MIXED_CONTENT_NEVER_ALLOW`
- 第三方 Cookie 默认允许

### 6.3 App 主题影响网页颜色

Android WebView 会根据 App 主题影响 `prefers-color-scheme`；Android 官方文档还说明，WebView 的算法暗色化能力与 `targetSdkVersion` 和主题有关。若页面支持暗色主题，App 主题可能让网页选择不同 CSS 分支。

排查：

- 在页面执行 `matchMedia('(prefers-color-scheme: dark)').matches`
- 对比手机浏览器结果。
- 检查是否启用了 algorithmic darkening 或 force dark 相关兼容设置。

### 6.4 Safe area、状态栏、刘海屏、横竖屏

页面如果使用 `viewport-fit=cover`、`env(safe-area-inset-*)`、`100vh/100dvh`，会受系统栏、刘海屏、横竖屏、沉浸式策略影响。

排查：

- 输出 `innerWidth/innerHeight`
- 输出 `visualViewport.width/height/scale`
- 输出 CSS env safe area 的实际表现
- 横屏和竖屏分别截图对比

### 6.5 WebView 生命周期与 Compose AndroidView 时序

`AndroidView` attach、measure、layout 与 WebView 页面加载不是一个同步过程。`onPageFinished()` 只是主 frame 加载回调，不等于视觉上已经稳定。复杂 SPA 还会在 `onPageFinished()` 后继续拉数据、hydrate、懒加载。

后续可考虑使用：

- `WebView.postVisualStateCallback()` 或 `WebViewCompat.postVisualStateCallback()` 等视觉状态回调，作为“页面可见/遮罩消失”的更强依据。
- 页面诊断 JS：持续采样 DOM 节点数、body 文本长度、可见元素数、首屏非透明元素数。

### 6.6 真实页面 WebView 复用

不建议把加载过真实 URL 的 WebView 回收到全局池。可以热备空白 WebView，但真实页面实例应随页面关闭销毁。

允许复用的前提：

- 已彻底 `stopLoading()`
- 已清空 client/chrome client/JS bridge
- 已从 parent 移除
- 已 `loadUrl("about:blank")`
- 已 `clearHistory()`
- 不保留真实页面的 history、scroll、storage、JS 全局状态

当前项目采取保守策略：真实页面销毁；空白热备默认关闭，仅作为用户手动开启的启动速度优化项。

### 6.7 Chromium tile 内存压力

症状：

- `onPageFinished()` 已到达，进度 100%。
- Inspect 中 DOM、CSS、Network、Console 基本正常。
- logcat 出现 `tile memory limits exceeded, some content may not draw`。
- 页面不是整体白屏，而是部分模块、Canvas、背景、按钮或某些区域不绘制。

优先排查：

- 是否开启 `offscreenPreRaster`。
- 是否开启空白 WebView 热备或 URL 预加载。
- 是否同时 attach 多个 WebView。
- 是否页面本身使用大量 Canvas/WebGL、CSS filter、固定背景、大图、复杂 transform 或超大阴影。
- 设备是否为高 DPR、高分辨率且内存紧张。

---

## 7. 如何尽量让 WebView 与正常浏览器保持一致

不能做到 100% 一致。WebView 是嵌入式浏览器控件，Chrome/手机浏览器是完整浏览器产品。两者的宿主生命周期、权限、UI、cookie 策略、feature flags、地址栏/viewport 行为、站点隔离、调试能力都不同。

但可以做到“尽量接近移动浏览器基线”。

### 7.1 推荐默认基线

| 配置 | 推荐值 | 当前状态 | 原因 |
|---|---:|---|---|
| `javaScriptEnabled` | `true` | 已满足 | 现代网页基本必需 |
| `domStorageEnabled` | `true` | 已满足 | localStorage/sessionStorage 必需 |
| `databaseEnabled` | `true` | 已满足 | IndexedDB/Web SQL 兼容旧站点与部分 WebView 存储路径 |
| `loadsImagesAutomatically` | `true` | 已满足 | 避免图片资源不可见 |
| `blockNetworkImage` | `false` | 已满足 | 避免图片缺失 |
| `blockNetworkLoads` | `false` | 已满足 | 避免资源加载被全局禁用 |
| `useWideViewPort` | `false` | 已满足 | 默认贴近手机 WebView 控件 CSS 宽度 |
| `loadWithOverviewMode` | `false` | 已满足 | 避免把移动响应式页面缩放成非预期视口 |
| `textZoom` | `100` | 已满足 | 避免系统字体缩放改变网页布局 |
| `cacheMode` | `LOAD_DEFAULT` | 已满足 | 使用 WebView/HTTP 正常缓存策略 |
| `thirdPartyCookies` | 默认允许 | 已满足，可配置关闭 | 提升登录、嵌入组件、跨域鉴权兼容性 |
| `mixedContentMode` | 兼容模式 | 已满足，可配置严格阻止 | 默认兼容旧网页；安全需要时再严格 |
| `hardwareAccelerated` | Activity 开启 | 已满足 | Canvas、视频、CSS 动画、合成层依赖 |
| `offscreenPreRaster` | `false` | 已满足，可配置开启 | 默认关闭以降低 tile 内存压力；只在确认需要预栅格化时手动开启 |
| 空白 WebView 热备 | 默认关闭 | 已满足，可配置开启 | 热备提升冷启动速度，但会占用 WebView/Chromium 资源，高分屏或低内存设备优先保障真实页面绘制 |
| URL 后台预加载 | 默认关闭 | 已满足，可配置开启 | 避免无感占用网络、内存和网站会话 |
| UA | 默认显示并可切换/自定义 | 已满足 | 便于站点分流问题排查 |
| 广告/弹窗过滤 | 默认关闭 | 已调整，可配置开启 | 避免误拦截脚本、CSS、字体、统计脚本依赖等子资源 |
| 下载能力 | 默认允许 | 已调整，可配置禁用 | 正常浏览器应能处理下载；禁用时需要用户明确选择 |
| 长按选择/复制 | 默认允许 | 已调整，可配置禁用 | 保持阅读、复制、图片保存等浏览器交互 |
| 主页面跨域跳转 | 默认允许 | 已调整，可配置限制 | OAuth、CDN、外部文档、支付/登录跳转可能跨域 |
| 地理位置 | 默认允许网页申请 | 已调整，可配置禁用 | 和浏览器能力一致；隐私限制需用户主动开启 |
| 摄像头/麦克风 | 默认允许网页申请 | 已新增配置项，可配置禁用 | WebRTC、拍照、录音、扫码等交互需要媒体采集能力 |
| 多窗口/`window.open` | 默认允许 | 已调整，可配置禁用 | `target=_blank`、OAuth、文档预览等常依赖新窗口 |
| `file/content` 访问 | 默认允许 | 已调整，可配置限制 | 文件上传、预览、本地内容交互需要标准访问能力 |

本轮基线复核结论：原有核心 WebSettings 渲染项已满足，但 `offscreenPreRaster=true` 和默认空白 WebView 热备会在高分屏设备上放大 Chromium tile 内存压力，已经按“渲染稳定优先”调整为默认关闭。多个“网页浏览器沙箱限制”的默认值也已改为“默认按正常浏览器兼容基线放开，后台保留配置项，用户需要更严格儿童安全策略时再主动开启”。

### 7.2 调试基线

遇到渲染异常时，先收集以下信息：

```js
({
  userAgent: navigator.userAgent,
  width: window.innerWidth,
  height: window.innerHeight,
  dpr: window.devicePixelRatio,
  scrollY: window.scrollY,
  docHeight: document.documentElement.scrollHeight,
  bodyTextLength: (document.body && document.body.innerText || '').trim().length,
  visualViewport: window.visualViewport && {
    width: window.visualViewport.width,
    height: window.visualViewport.height,
    scale: window.visualViewport.scale,
    offsetTop: window.visualViewport.offsetTop,
    offsetLeft: window.visualViewport.offsetLeft
  },
  colorSchemeDark: matchMedia('(prefers-color-scheme: dark)').matches
})
```

再检查：

- Network 是否有 4xx/5xx/blocked/canceled。
- Elements 中“没显示”的节点是否存在。
- Computed Style 是否为 `opacity: 0`、`visibility: hidden`、`display: none`、`transform` 位移、`content-visibility`、`contain` 等。
- 手动执行 `window.dispatchEvent(new Event('resize'))`、`window.dispatchEvent(new Event('scroll'))` 是否恢复。
- 关闭 App 的广告过滤、外链限制、混合内容严格模式、SSL 强校验后是否恢复。

### 7.3 需要新增的长期能力

建议后续继续补：

- **资源拦截日志面板**：记录每个被 App 拦截的请求 URL、原因、mime、是否主 frame。
- **WebView 环境诊断页**：一键展示 UA、viewport、visualViewport、DPR、Cookie、Storage、prefers-color-scheme。
- **视觉状态回调接入**：用 `postVisualStateCallback` 改进 Loading 遮罩消失时机。
- **站点兼容配置**：按域名覆盖 viewport、UA、mixed content、第三方 Cookie、多窗口等策略。
- **截图对比回归**：同一 URL 用设备浏览器和 App WebView 截图比对，避免发布后才发现渲染退化。
- **调试脚本健康检查**：Eruda/vConsole 注入后记录是否成功创建全局对象和面板 DOM。

---

## 8. 后续遇到类似问题的排查流程

详细操作命令和 Chrome Inspect 固定诊断脚本见 [`webview_debugging_runbook.md`](./webview_debugging_runbook.md)。本节只保留判断顺序。

1. 先确认是否是 Loading 遮罩未消失，而不是网页没渲染。
2. 打开 Chrome Inspect，看 Console 与 Network。
3. 在 Elements 中定位“缺失”的模块是否存在。
4. 如果节点存在但不可见，检查 Computed Style。
5. 执行 viewport 诊断 JS，和手机浏览器结果对比。
6. 手动派发 `resize/scroll/visibilitychange`，观察是否恢复。
7. 临时关闭 App 安全/过滤/混合内容/外链限制做 A/B。
8. 修改 UA 为手机浏览器 UA 或系统默认 WebView UA 做 A/B。
9. 如仍异常，抓取目标页面 HTML/CSS/JS，查是否依赖：
   - IntersectionObserver
   - ResizeObserver
   - visualViewport
   - Service Worker
   - WebGL/Canvas
   - SharedArrayBuffer/Cross-Origin Isolation
   - module script/import map
   - lazy loading
   - `content-visibility`
   - safe-area/env
10. 必要时为该域名加单站点兼容策略，不要轻易改全局默认。

---

## 9. 本次结论

这次不是 WebView 版本过旧，也不是页面完全没加载。已经确认存在两类不同问题：

1. 视口/生命周期触发问题：wide viewport、overview mode、attach/layout 时机可能让依赖 IntersectionObserver、懒加载和滚动揭示的页面停在隐藏态。
2. tile 内存问题：页面加载完成、Inspect 无异常时，如果 logcat 出现 `tile memory limits exceeded, some content may not draw`，就是 Chromium 合成器在栅格化阶段因为内存预算不足跳过部分绘制。

对 `pages.anzz.site/app/piano/`，最新证据更符合第二类：Inspect 显示 UA、viewport、DOM、功能 API、Network、Console 都正常；logcat 在 `Page finished` 后立刻提示 tile memory exceeded。

当前修复的原则是：

- 默认贴近手机浏览器视口。
- 默认关闭高内存的离屏预栅格化和 WebView 热备。
- UA 真实可见、可控制。
- 页面完成后补偿通用生命周期和 viewport 事件。
- 不对目标站点写特例。
- 保持安全策略可配置，并为后续排查保留明确路径。

---

## 10. 参考资料

- Android Developers：[`WebSettings`](https://developer.android.com/reference/android/webkit/WebSettings)
- Android Developers：[`WebViewClient.onPageFinished`](https://developer.android.com/reference/android/webkit/WebViewClient#onPageFinished(android.webkit.WebView,%20java.lang.String))
- AndroidX WebKit：[`WebViewCompat.VisualStateCallback`](https://developer.android.com/reference/androidx/webkit/WebViewCompat.VisualStateCallback)
- MDN：[`Intersection Observer API`](https://developer.mozilla.org/en-US/docs/Web/API/Intersection_Observer_API)
- Android Developers Blog：[`User-Agent Reduction on Android WebView`](https://android-developers.googleblog.com/2024/12/user-agent-reduction-on-android-webview.html)
