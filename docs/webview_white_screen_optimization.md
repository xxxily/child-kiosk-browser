# WebView 白屏优化改造需求文档

> **文档版本**：2.0  
> **创建日期**：2026-06-13  
> **最近更新**：2026-06-13（审查细化）  
> **状态**：待开发  
> **优先级**：高  
> **关联版本**：v0.0.5（计划）

---

## 一、问题现象

每次从 Kiosk 主页点击网站卡片进入 WebView 浏览网站时，界面会先出现一段明显的**白屏空白期**（约 0.5 ~ 3 秒，视网络状况和目标网站复杂度而定），随后网页内容才逐渐渲染呈现。

该白屏体验在以下场景尤为突出：

- 应用整体配色为暖色/深色调时，白屏的刺眼对比尤为明显
- 目标网站为 SPA（单页应用），JS Bundle 较大时白屏持续更久
- 弱网环境（Wi-Fi 信号差、蜂窝网络）下白屏时间显著拉长
- 儿童频繁切换应用时，反复经历白屏造成体验割裂
- **冷启动场景**（应用首次打开或进程被杀后重新打开），白屏时间明显长于后续访问

---

## 二、根因分析

经过对 [`WebViewActivity.kt`](../app/src/main/java/com/example/childkiosk/WebViewActivity.kt) 源码的逐行审查，白屏由以下六个层面的因素叠加造成：

### 2.1 Activity 窗口背景默认白色

`WebViewActivity` 启动时，系统在 Compose 内容绘制之前，会先渲染 Activity 的 **窗口背景**（Window Background）。当前未在主题中自定义 `android:windowBackground`，使用的是系统默认白色。这意味着从 Activity 创建到 Compose 首帧绘制的数十到数百毫秒内，用户看到的始终是白色窗口。

**关键位置**：`AndroidManifest.xml` 中 `WebViewActivity` 使用的主题未设置自定义窗口背景。

### 2.2 WebView 渲染引擎冷启动开销

首次在应用进程中创建 WebView 实例时，Android 需要加载整个 Chromium 渲染引擎到内存。这个一次性的"冷启动"耗时约 200~800ms（取决于设备性能和 Android 版本），在此期间 WebView 控件不会渲染任何内容。

**影响**：这解释了为什么应用启动后"第一次"打开网站比后续打开慢得多。

### 2.3 WebView 默认白色底色

`WebView` 控件初始化后的默认背景色为 `#FFFFFF` 纯白色。在 `loadUrl()` 被调用后、网页首帧（First Paint）渲染出来之前，WebView 内部画布呈现的就是这层白底。

**关键代码位置**：[`createSecureWebView()`](../app/src/main/java/com/example/childkiosk/WebViewActivity.kt) 函数定义于第 500 行，第 508 行 `WebView(ctx).apply { ... }` 中未调用 `setBackgroundColor()`。

### 2.4 Compose `AndroidView` 的测量与布局时差 + `loadUrl()` 调用时机

WebView 通过 `AndroidView` 的 `factory` 回调同步创建并加载 URL（[第 256-274 行](../app/src/main/java/com/example/childkiosk/WebViewActivity.kt)）。从 `AndroidView` 首次加入 Compose 视图树、进行测量（measure）和布局（layout），到底层 WebView 接收到网络数据并绘制出网页内容，存在一个原生渲染管线的物理耗时。

此外，`loadUrl(targetUrl)` 在 `factory` 回调中被同步调用（[第 270 行](../app/src/main/java/com/example/childkiosk/WebViewActivity.kt)），此时 WebView 尚未完成 `attach` 到窗口，实际网络请求会被延迟到 WebView 完成 layout 之后，进一步增加了白屏持续时间。

### 2.5 渲染阻塞资源与网络延迟

当 WebView 访问网络地址时，必须先下载 HTML，再解析 `<head>` 中的同步外部 CSS 和 JavaScript 资源。这些资源下载并执行完毕之前，DOM 树的呈现与绘制被阻塞，用户看到的是一片白屏。

### 2.6 SPA "空壳" HTML 特性

现代 SPA 框架（React、Vue、Vite 等）构建的网站，其 HTML 文件通常只有一个空的挂载节点（如 `<div id="app"></div>`）。即便 WebView 极快地渲染了首屏 HTML，在体积庞大的 JS Bundle 下载并动态注入页面 DOM 之前，页面实质上没有任何可见内容。

---

## 三、当前代码现状概要

对优化涉及的核心代码做如下摘要，便于开发时快速定位：

| 模块 | 位置 | 现状 |
|------|------|------|
| `createSecureWebView()` | 第 500-613 行 | 无 `setBackgroundColor()`；`cacheMode = LOAD_DEFAULT`（第 534 行） |
| `WebViewClient` | 第 544-591 行 | 仅重写了 `shouldOverrideUrlLoading`、`shouldInterceptRequest`、`onReceivedSslError`；**无** `onPageStarted`、`onPageFinished`、`onReceivedError` |
| `WebChromeClient` | 第 593-607 行 | 仅重写了 `onPermissionRequest`、`onCreateWindow`；**无** `onProgressChanged` |
| `WebViewScreen()` | 第 157-433 行 | **无**加载状态变量（`isLoading`/`loadProgress`），无加载遮罩层 |
| `AndroidView` 块 | 第 256-274 行 | `factory` 中同步 `loadUrl()`，无延迟或预热逻辑 |

---

## 四、优化方案设计

### 方案 0：Activity 窗口背景主题优化（必做 ✅）

**优先级**：P0（成本最低、效果立竿见影）  
**预期效果**：消除 Activity 启动阶段（Compose 首帧绘制前）的白屏闪烁。

#### 4.0.1 需求描述

在所有 WebView 层面的优化之前，先从系统层面消除 Activity 切换时的白色窗口。为 `WebViewActivity` 定义专属主题，将 `android:windowBackground` 设置为与应用主色调一致的暖色。

#### 4.0.2 技术实现要点

1. 定义 WebView 专用主题：
   ```xml
   <!-- res/values/themes.xml -->
   <style name="Theme.ChildKiosk.WebView" parent="Theme.ChildKiosk">
       <item name="android:windowBackground">@color/warm_background</item>
   </style>
   ```

2. 定义颜色资源：
   ```xml
   <!-- res/values/colors.xml -->
   <color name="warm_background">#FFF8E1</color>
   ```

3. 在 `AndroidManifest.xml` 中为 `WebViewActivity` 指定此主题：
   ```xml
   <activity
       android:name=".WebViewActivity"
       android:theme="@style/Theme.ChildKiosk.WebView"
       ... />
   ```

#### 4.0.3 验收标准

- [ ] 从 Kiosk 主页跳转到 WebView 页面时，Activity 过渡阶段无白色闪烁
- [ ] 窗口背景色与应用整体视觉风格保持一致
- [ ] 不影响 WebView 内网页的正常渲染和显示

---

### 方案 1：Compose 加载遮罩与过渡动画（必做 ✅）

**优先级**：P0（核心体验优化）  
**预期效果**：白屏期间展示品牌化加载动画，消除视觉空白，并通过渐隐过渡自然切换到网页内容。

#### 4.1.1 需求描述

在网页完全绘制成功之前，用一个优雅的 Compose 加载遮罩层覆盖在 WebView 上方，避免暴露白屏阶段。当网页加载完成后，遮罩以渐隐动画平滑消失。

#### 4.1.2 技术实现要点

1. 在 `WebViewScreen` 中维护加载状态：
   ```kotlin
   var isPageLoading by remember { mutableStateOf(true) }
   var loadProgress by remember { mutableIntStateOf(0) }
   var loadError by remember { mutableStateOf<String?>(null) }
   // 最小展示时间控制，防止遮罩闪现闪退
   var overlayShownTime by remember { mutableLongStateOf(0L) }
   ```

2. 在 `createSecureWebView` 中新增 `onLoadingStateChanged`、`onProgressUpdate`、`onError` 回调参数，并在 `WebViewClient` 中添加回调：
   ```kotlin
   override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
       super.onPageStarted(view, url, favicon)
       onLoadingStateChanged(true)
   }

   override fun onPageFinished(view: WebView?, url: String?) {
       super.onPageFinished(view, url)
       // 对 SPA 页面，注入 JS 检查 DOM 是否有实际可见内容
       view?.evaluateJavascript(
           "(document.body && document.body.children.length > 0).toString()"
       ) { result ->
           if (result == "\"true\"") {
               onLoadingStateChanged(false)
           }
       }
   }

   override fun onReceivedError(
       view: WebView?,
       request: WebResourceRequest?,
       error: WebResourceError?
   ) {
       super.onReceivedError(view, request, error)
       // 仅处理主页面加载失败（忽略子资源）
       if (request?.isForMainFrame == true) {
           onError("页面加载失败：${error?.description}")
       }
   }

   override fun onReceivedHttpError(
       view: WebView?,
       request: WebResourceRequest?,
       errorResponse: WebResourceResponse?
   ) {
       super.onReceivedHttpError(view, request, errorResponse)
       if (request?.isForMainFrame == true && (errorResponse?.statusCode ?: 200) >= 400) {
           onError("页面返回错误：HTTP ${errorResponse?.statusCode}")
       }
   }
   ```

3. 在 `WebChromeClient` 中监听精细进度：
   ```kotlin
   override fun onProgressChanged(view: WebView?, newProgress: Int) {
       onProgressUpdate(newProgress)
       // 当进度 >= 85% 时即可认为首屏基本可用，提前移除遮罩
       if (newProgress >= 85) {
           onLoadingStateChanged(false)
       }
   }
   ```

4. 加载遮罩层 UI 设计：
   - 背景：与应用主题匹配的暖色渐变（如 `Color(0xFFFFF8E1)` → `Color(0xFFFFECB3)`）
   - 中央：显示目标网站的图标（复用 `webApp.iconPath`）+ 网站名称
   - 下方：圆形进度条或线性进度条，显示加载百分比
   - 底部：趣味性提示文字（如「正在为你准备精彩内容...」「马上就好啦 🎉」等随机展示）

5. 过渡动画（含最小展示时间保护）：
   ```kotlin
   // 记录遮罩展示时间
   LaunchedEffect(isPageLoading) {
       if (isPageLoading) {
           overlayShownTime = System.currentTimeMillis()
       }
   }

   // 计算是否满足最小展示时间（600ms）
   val shouldShowOverlay by remember {
       derivedStateOf {
           isPageLoading || loadError != null ||
           (System.currentTimeMillis() - overlayShownTime < 600)
       }
   }

   AnimatedVisibility(
       visible = shouldShowOverlay,
       exit = fadeOut(animationSpec = tween(400))
   ) {
       if (loadError != null) {
           LoadingErrorOverlay(
               error = loadError!!,
               onRetry = {
                   loadError = null
                   isPageLoading = true
                   webViewRef?.reload()
               },
               onClose = onClose
           )
       } else {
           LoadingOverlay(webApp = webApp, progress = loadProgress)
       }
   }
   ```

6. 错误提示页 `LoadingErrorOverlay` 设计：
   - 与应用风格一致的暖色/深色渐变背景
   - 中央显示友好的错误图标和提示文字（如「网络好像不太好呢...」）
   - 提供「重试」和「返回主页」两个按钮

#### 4.1.3 验收标准

- [ ] 点击网站卡片后，立即显示带品牌感的加载遮罩，**0 帧白屏**
- [ ] 加载遮罩展示网站图标、名称与加载进度
- [ ] 网页加载至 85% 或触发 `onPageFinished`（且 DOM 检测通过）时，遮罩以 400ms 渐隐动画消失
- [ ] 遮罩最少展示 600ms，避免极速加载时闪现闪退的不自然感
- [ ] 加载失败（超时、DNS 错误、HTTP 4xx/5xx 等）时，遮罩平滑切换为错误提示页
- [ ] 错误提示页提供「重试」和「返回主页」两个操作
- [ ] SPA 页面（JS Bundle 未加载完成前 DOM 为空）不会过早移除遮罩

---

### 方案 2：WebView 背景色匹配主题（必做 ✅）

**优先级**：P0（与方案 1 配合使用）  
**预期效果**：即使遮罩层有极短的渲染延迟，WebView 底色也不再是刺眼的白色。

#### 4.2.1 需求描述

将 WebView 的默认背景色设置为与应用主题一致的暖色（**非透明**），使其在未渲染网页内容时与 Compose 容器层背景融为一体。

> **注意**：此处采用**暖色实色**而非透明色。`setBackgroundColor(Color.TRANSPARENT)` 在部分 Android 版本和 WebView 内核上可能引起硬件加速渲染异常（黑屏或闪烁），采用实色可规避此兼容性风险。

#### 4.2.2 技术实现要点

在 `createSecureWebView()` 的 `WebView(ctx).apply { ... }` 块中添加：
```kotlin
// 设置与应用主题一致的暖色背景，避免白屏闪烁
setBackgroundColor(android.graphics.Color.parseColor("#FFF8E1"))
```

同时在 Compose 的 `Box` 容器上设置一致的暖色背景：
```kotlin
Box(
    modifier = Modifier
        .fillMaxSize()
        .background(Color(0xFFFFF8E1)) // 暖色底色兜底
) {
    AndroidView(...)
}
```

在 `onPageFinished` 回调中，将 WebView 背景恢复为白色，以避免少数未设置 `body` 背景色的网页出现文字与暖色底叠加的问题：
```kotlin
override fun onPageFinished(view: WebView?, url: String?) {
    super.onPageFinished(view, url)
    // 网页加载完成后恢复白色背景，防止无背景色网页的可读性问题
    view?.setBackgroundColor(android.graphics.Color.WHITE)
    // ... 其他逻辑
}
```

#### 4.2.3 验收标准

- [ ] WebView 在未绘制网页内容时，不出现任何白色闪烁
- [ ] WebView 底色与应用整体视觉风格保持一致
- [ ] 网页加载完成后，无背景色的网页内容可读性不受影响
- [ ] 在 Android 8.0~14 各版本上无渲染异常（黑屏、闪烁等）

---

### 方案 3：优化缓存策略（建议做 ⭐）

**优先级**：P1（体验增强）  
**预期效果**：二次访问同一网站时大幅减少白屏时间，接近秒开。

#### 4.3.1 需求描述

在保持内容时效性的前提下，优化 WebView 缓存策略，使二次访问相同网站时能更快渲染首屏。

#### 4.3.2 技术实现要点

1. **保留 `LOAD_DEFAULT` 缓存模式**（遵守 HTTP 缓存头），不采用过于激进的 `LOAD_CACHE_ELSE_NETWORK`：

   ```kotlin
   // 保持当前设置（第 534 行）
   cacheMode = WebSettings.LOAD_DEFAULT
   ```

   > **设计决策**：`LOAD_CACHE_ELSE_NETWORK` 会在有缓存时完全不发网络请求（即使缓存已过期），对于可能频繁更新的儿童教育类网站，用户可能看到过期内容。`LOAD_DEFAULT` 遵守 HTTP 缓存头（`Cache-Control`、`ETag`、`Last-Modified`），在性能和时效性之间取得平衡。

2. **确保 DOM Storage 和 Database 持久化**：当前已启用 ✅（`domStorageEnabled = true`, `databaseEnabled = true`），无需修改。

3. **提供管理后台的缓存管理功能**：

   在 `AdminConsoleScreen` 中增加「清除网页缓存」按钮：
   ```kotlin
   QButton(onClick = {
       // 清除所有 WebView 缓存
       WebView(context).apply {
           clearCache(true)
           destroy()
       }
       // 清除 Cookie
       CookieManager.getInstance().removeAllCookies(null)
       Toast.makeText(context, "网页缓存已清除", Toast.LENGTH_SHORT).show()
   }) {
       Text("清除网页缓存")
   }
   ```

4. **自动清理过期缓存**：在 `Application.onCreate()` 或 `KioskMainActivity.onCreate()` 中检查并清理超过 7 天的缓存：
   ```kotlin
   // 在 KioskPrefs 中记录上次清理时间
   val lastCacheClear = KioskPrefs.getLastCacheClearTime(context)
   if (System.currentTimeMillis() - lastCacheClear > 7 * 24 * 60 * 60 * 1000L) {
       WebView(context).apply {
           clearCache(true)
           destroy()
       }
       KioskPrefs.setLastCacheClearTime(context, System.currentTimeMillis())
   }
   ```

#### 4.3.3 验收标准

- [ ] 首次访问网站时，行为与当前一致（网络优先，遵守 HTTP 缓存头）
- [ ] 二次访问相同网站时，已缓存资源直接使用，白屏时间显著缩短
- [ ] 管理后台提供「清除网页缓存」功能入口
- [ ] 超过 7 天的缓存在应用启动时自动清理
- [ ] 在 `KioskPrefs` 中持久化缓存管理相关配置

---

### 方案 4：WebView 引擎预热（建议做 ⭐）

**优先级**：P1（低成本高收益）  
**预期效果**：消除首次创建 WebView 时的 Chromium 引擎冷启动耗时（200~800ms）。

#### 4.4.1 需求描述

在 `Application.onCreate()` 阶段，利用主线程空闲时预创建一个轻量 WebView 实例触发 Chromium 引擎初始化，使后续真正创建 WebView 时可跳过冷启动。

#### 4.4.2 技术实现要点

在应用的 `Application` 类中添加预热逻辑：
```kotlin
class ChildKioskApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // ... 其他初始化代码

        // 预热 WebView 引擎（利用主线程空闲时机）
        android.os.Handler(mainLooper).post {
            try {
                WebView(this).apply {
                    loadUrl("about:blank")
                    destroy()
                }
            } catch (e: Exception) {
                // 预热失败不影响正常功能
                android.util.Log.w("WebViewPrewarm", "WebView prewarm failed", e)
            }
        }
    }
}
```

> **注意**：使用 `Handler.post()` 将预热延迟到主线程空闲时执行，不阻塞应用启动。

#### 4.4.3 验收标准

- [ ] 应用首次打开网站的 WebView 创建耗时与后续打开基本一致
- [ ] 预热过程不延长应用启动时间（通过 Logcat 验证 `onCreate` 耗时无显著增加）
- [ ] 预热失败时静默降级，不影响正常功能

---

### 方案 5：WebView 实例预加载与复用池（可选做 💡）

**优先级**：P2（进阶优化）  
**预期效果**：实现点击即开、零白屏的极致体验。

#### 4.5.1 需求描述

在 Kiosk 主页空闲时段，预创建 WebView 实例并提前加载目标网站。用户点击网站卡片时，直接将已加载好首屏的 WebView 实例挂载到 Activity 中。

#### 4.5.2 技术实现要点

1. 创建 `WebViewPool` 单例管理器（**必须使用 Application Context 防止内存泄漏**）：
   ```kotlin
   object WebViewPool {
       private val pool = mutableMapOf<String, WebView>()
       private var appContext: Context? = null

       fun init(context: Context) {
           appContext = context.applicationContext
       }

       fun preload(url: String) {
           val ctx = appContext ?: return
           if (pool.containsKey(url)) return
           if (pool.size >= MAX_POOL_SIZE) return

           val webView = createSecureWebView(
               ctx = ctx,
               targetUrl = url,
               originalHost = runCatching { URL(url).host }.getOrNull()?.lowercase().orEmpty(),
               onSslError = {},
               onBlocked = {},
               onDownloadBlocked = {}
           )
           webView.loadUrl(url)
           pool[url] = webView
       }

       fun acquire(url: String): WebView? = pool.remove(url)

       fun release(url: String) {
           pool.remove(url)?.destroy()
       }

       fun clear() {
           pool.values.forEach { it.destroy() }
           pool.clear()
       }

       fun trimToSize(maxSize: Int) {
           while (pool.size > maxSize) {
               val entry = pool.entries.first()
               entry.value.destroy()
               pool.remove(entry.key)
           }
       }

       private const val MAX_POOL_SIZE = 3
   }
   ```

2. 集成系统内存回调，在内存紧张时主动释放：
   ```kotlin
   class ChildKioskApp : Application(), ComponentCallbacks2 {
       override fun onTrimMemory(level: Int) {
           super.onTrimMemory(level)
           when {
               level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> WebViewPool.clear()
               level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> WebViewPool.trimToSize(1)
               level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE -> WebViewPool.trimToSize(2)
           }
       }
   }
   ```

3. 在主页 `KioskMainScreen` 加载完白名单后，利用协程在后台按顺序预热前 N 个（如 3 个）最常用的网站。使用 `IdleHandler` 确保不影响 UI 流畅度：
   ```kotlin
   Looper.myQueue().addIdleHandler {
       scope.launch {
           webApps.take(3).forEach { app ->
               WebViewPool.preload(app.url)
               delay(1000) // 间隔预加载，减轻瞬时内存压力
           }
       }
       false // 只执行一次
   }
   ```

4. `WebViewActivity` 启动时，优先从 Pool 中获取已加载的 WebView 实例。若命中，直接挂载；若未命中，回退到当前的即时创建逻辑。

5. 预加载的 WebView **必须与 `createSecureWebView` 使用相同的安全策略**（URL 拦截、SSL 策略、广告过滤等），确保安全配置一致性。

#### 4.5.3 验收标准

- [ ] 预加载的网站点击后 0 白屏秒开
- [ ] 内存占用可控（最多同时预加载 3 个实例）
- [ ] 应用退到后台或内存紧张时，自动释放预加载的 WebView 实例（通过 `onTrimMemory` 验证）
- [ ] 管理后台可配置是否启用预加载（默认开启）
- [ ] 预加载使用 Application Context，无 Activity 内存泄漏
- [ ] 预加载的 WebView 安全策略与正常创建的完全一致

---

## 五、实施优先级与阶段规划

| 阶段 | 方案 | 优先级 | 预期工时 | 备注 |
|------|------|--------|----------|------|
| Phase 0 | 方案 0（Activity 窗口背景） | P0 必做 | 0.5h | 成本最低，立竿见影 |
| Phase 1 | 方案 1 + 方案 2（遮罩 + WebView 背景色） | P0 必做 | 4~8h | 联合实施，彻底消除白屏视觉 |
| Phase 2 | 方案 4（WebView 引擎预热） | P1 建议做 | 0.5h | 解决首次冷启动白屏 |
| Phase 3 | 方案 3（缓存策略优化） | P1 建议做 | 2~4h | 提升二次访问速度，需管理后台配合 |
| Phase 4 | 方案 5（WebView 预加载池） | P2 可选做 | 8~16h | 极致体验，需严格评估内存开销和安全一致性 |

---

## 六、风险与注意事项

1. **`onPageFinished` 的可靠性**：部分网站（尤其是 SPA）在 `onPageFinished` 触发时可能仍在执行异步渲染。因此建议结合 `onProgressChanged >= 85` + JS DOM 检测三重判定来决定移除遮罩的时机。

2. **SPA 页面的遮罩移除时机**：对于 SPA 应用，`onPageFinished` 后 DOM 可能仍为空。通过注入 `evaluateJavascript` 检查 `document.body.children.length > 0` 来判断页面是否有实际可见内容。

3. **遮罩最小展示时间**：极速加载（如命中缓存）时，遮罩可能只展示 100ms 就消失，造成"闪一下"的不自然感。强制最低展示 600ms 可规避此问题。

4. **WebView 背景色设置的兼容性**：`setBackgroundColor(Color.TRANSPARENT)` 在部分 Android 版本上可能引起硬件加速渲染异常。本方案采用**暖色实色**（`#FFF8E1`）替代透明色，规避兼容性风险。

5. **`onPageFinished` 后恢复白色背景**：少数网页本身没有设置 `body` 背景色，暖色底色可能导致文字对比度下降。在 `onPageFinished` 后将 WebView 背景恢复为白色可规避此问题。

6. **WebView 预加载的内存压力**：每个 WebView 实例大约消耗 50~150MB 内存。预加载池应设置上限（建议最多 3 个），并在系统 `onTrimMemory` 回调中按级别主动释放。

7. **WebView 预加载的 Context 泄漏**：`WebViewPool` 单例中的 WebView **必须使用 Application Context 创建**，否则持有 Activity 引用将导致严重内存泄漏。

8. **缓存策略的时效性**：`LOAD_DEFAULT` 模式遵守 HTTP 缓存头，在性能和时效性之间取得平衡。后台提供手动清缓存入口，并在应用启动时自动清除超过 7 天的缓存。

---

## 七、相关文件索引

| 文件 | 路径 | 说明 |
|------|------|------|
| WebViewActivity.kt | `app/src/main/java/com/example/childkiosk/WebViewActivity.kt` | WebView 创建、加载、安全策略核心文件 |
| KioskMainScreen.kt | `app/src/main/java/com/example/childkiosk/ui/KioskMainScreen.kt` | 主页网格，预加载触发入口 |
| KioskPrefs.kt | `app/src/main/java/com/example/childkiosk/util/KioskPrefs.kt` | 偏好配置存储，可扩展缓存/预加载开关 |
| AdminConsoleScreen.kt | `app/src/main/java/com/example/childkiosk/ui/AdminConsoleScreen.kt` | 管理后台，清缓存、预加载配置入口 |
| AndroidManifest.xml | `app/src/main/AndroidManifest.xml` | Activity 主题配置 |
| themes.xml | `app/src/main/res/values/themes.xml` | 主题定义，新增 WebView 专用主题 |
| colors.xml | `app/src/main/res/values/colors.xml` | 颜色资源定义 |
