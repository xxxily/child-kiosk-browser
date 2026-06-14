# WebView 调试取证运行手册

> 文档版本：1.0  
> 创建日期：2026-06-14  
> 适用场景：App WebView 中页面渲染异常、交互异常、Loading 卡住、Eruda/vConsole 注入不稳定、热备/预加载导致的历史栈或状态异常。

---

## 1. 目标

这份手册用于把 WebView 问题从“看起来不正常”转成可复查的证据：

- logcat 记录 App/WebView 生命周期、加载进度、主 frame 错误、拦截、SSL、控制台日志。
- Chrome Inspect 记录页面 DOM、CSS、viewport、Network、Console 和 JavaScript 运行环境。
- 同一 URL 对比 App WebView 与手机自带浏览器，确认差异来自宿主配置、资源加载、页面脚本还是 WebView 状态残留。

遇到复杂页面时，优先按本手册采集证据，再改代码。

---

## 2. 准备条件

### 2.1 手机侧

1. 打开 Android 开发者选项。
2. 开启 USB 调试。
3. 用 USB 连接电脑，并在手机上允许当前电脑调试。
4. 在 App 家长后台打开：
   - “网页调试与开发配置”
   - “启用 USB 远程调试 (Chrome Inspect)”
5. 如果系统安全防逃逸里启用了“禁用 USB 调试 (ADB)”，需要先关闭它；App 内部已经做了互斥提示，但实际设备仍以系统状态为准。

### 2.2 电脑侧

确认 ADB 能看到设备：

```bash
adb devices
```

正常输出应包含一台 `device` 状态的设备。若显示 `unauthorized`，看手机弹窗并点击允许。

确认 App 已安装：

```bash
adb shell pm path com.example.childkiosk
```

---

## 3. logcat 标准采集

### 3.1 清空旧日志

```bash
adb logcat -c
```

### 3.2 启动专用日志窗口

推荐优先使用项目自定义 tag：

```bash
adb logcat -v time ChildKioskWebView:D ChildKioskApp:D MainActivity:D chromium:I cr_WebView:I AndroidRuntime:E '*:S'
```

如果怀疑系统 WebView 内核或 Chromium 网络层有额外信息，可以临时放宽过滤：

```bash
adb logcat -v time | grep -E 'ChildKioskWebView|ChildKioskApp|MainActivity|chromium|cr_WebView|WebView|AndroidRuntime'
```

需要保存到文件时：

```bash
adb logcat -v time ChildKioskWebView:D ChildKioskApp:D MainActivity:D chromium:I cr_WebView:I AndroidRuntime:E '*:S' > webview-debug.log
```

### 3.3 复现时必须记录的关键日志

打开目标网页后，重点看这些行：

- `Initial load after layout: 宽x高, url=...`
- `Page started: ...`
- `Page finished: progress=..., canGoBack=..., url=...`
- `Cleared initial blank history for warm WebView: ...`
- `Render mode applied: requested=..., actual=..., screen=..., density=...`
- `Main frame error: ...`
- `Main frame HTTP error: HTTP ..., url=...`
- `Blocked ad request: ...`
- `SSL error blocked: ...`
- `tile memory limits exceeded, some content may not draw`
- `CONSOLE/ERROR/WARNING...`

判断方法：

- 没有 `Initial load after layout`：说明真实页面可能在 WebView attach/layout 前后时序有问题。
- `Page started` 后没有 `Page finished`：看 Network、SSL、主 frame 错误或页面跳转循环。
- `Page finished` 后 Loading 仍卡住：重点排查 App 的 meaningful content 判断、页面是否 SPA 空壳、遮罩状态。
- `Page finished` 后出现 `tile memory limits exceeded`：优先排查 WebView tile 内存压力。先确认 `Render mode applied` 实际是否为 `SOFTWARE`；如果仍是 `HARDWARE`，在“网页性能优化”里把 WebView 渲染模式切到“软件兼容”或“自动兼容”。同时关闭“网页离屏预栅格化”、空白 WebView 热备和 URL 后台预加载，再对比是否恢复；这类问题通常不是 JS 报错或网络失败。
- 手势返回先到空白页：看 `canGoBack=true` 且是否出现 `Cleared initial blank history...`。
- 出现 `Blocked ad request`、`SSL error blocked`：先确认当前限制开关是否符合预期。

---

## 4. Chrome Inspect 标准流程

### 4.1 打开 Inspect

1. 电脑打开 Chrome。
2. 地址栏输入：

```text
chrome://inspect/#devices
```

3. 勾选 `Discover USB devices`。
4. 打开 App 中的目标网页。
5. 在 `Remote Target` 下找到 `com.example.childkiosk` 或目标 URL。
6. 点击 `inspect`。

如果看不到 WebView：

- 确认 App 后台已开启 “启用 USB 远程调试 (Chrome Inspect)”。
- 确认 ADB 设备状态是 `device`。
- 关闭再打开目标网页，让新 WebView 重新应用 `WebView.setWebContentsDebuggingEnabled(true)`。
- 重启 App 后再试。
- 若设备处于强管控模式，确认没有重新禁用 USB 调试。

### 4.2 Console 面板

先看是否有红色错误。没有错误不代表页面正常，很多渲染异常是 CSS 状态或生命周期触发失败。

执行基础环境诊断：

```js
({
  href: location.href,
  readyState: document.readyState,
  title: document.title,
  userAgent: navigator.userAgent,
  platform: navigator.platform,
  language: navigator.language,
  cookieEnabled: navigator.cookieEnabled,
  online: navigator.onLine,
  innerWidth,
  innerHeight,
  outerWidth,
  outerHeight,
  dpr: devicePixelRatio,
  scrollX,
  scrollY,
  docClientWidth: document.documentElement.clientWidth,
  docClientHeight: document.documentElement.clientHeight,
  docScrollWidth: document.documentElement.scrollWidth,
  docScrollHeight: document.documentElement.scrollHeight,
  bodyChildren: document.body ? document.body.children.length : null,
  bodyTextLength: document.body ? document.body.innerText.trim().length : null,
  visualViewport: window.visualViewport && {
    width: visualViewport.width,
    height: visualViewport.height,
    scale: visualViewport.scale,
    offsetTop: visualViewport.offsetTop,
    offsetLeft: visualViewport.offsetLeft,
    pageTop: visualViewport.pageTop,
    pageLeft: visualViewport.pageLeft
  },
  features: {
    IntersectionObserver: 'IntersectionObserver' in window,
    ResizeObserver: 'ResizeObserver' in window,
    visualViewport: 'visualViewport' in window,
    serviceWorker: 'serviceWorker' in navigator,
    WebGL: !!document.createElement('canvas').getContext('webgl')
  },
  media: {
    dark: matchMedia('(prefers-color-scheme: dark)').matches,
    reducedMotion: matchMedia('(prefers-reduced-motion: reduce)').matches,
    coarsePointer: matchMedia('(pointer: coarse)').matches,
    hoverNone: matchMedia('(hover: none)').matches
  }
})
```

把输出结果和手机自带浏览器里的同一段输出对比，尤其看：

- UA 是否进入了不同站点分支。
- `innerWidth/innerHeight`、`visualViewport` 是否明显不同。
- `docScrollHeight` 是否接近 0 或明显短于正常浏览器。
- `bodyTextLength` 是否正常，判断内容是否实际进了 DOM。
- `prefers-color-scheme` 是否不同。

### 4.3 Network 面板

操作步骤：

1. 打开 Network。
2. 勾选 `Preserve log`。
3. 勾选 `Disable cache`，然后刷新页面。
4. 过滤 `Doc`、`JS`、`CSS`、`Img`、`Font`、`Fetch/XHR` 分别检查。

重点看：

- 主文档是否 200。
- CSS/JS 是否 404、403、500、blocked、canceled。
- 字体、图片、JSON、wasm、module script 是否失败。
- 是否有 mixed content、CORS、CSP、MIME type 错误。
- 请求 UA、Cookie、Referer 是否和手机浏览器不同。

如果 App 开了广告过滤或外链限制，同时 logcat 出现 `Blocked ad request`，要把 Network 中失败资源和 logcat 的拦截 URL 对上。

### 4.4 Elements 与 Computed Style

页面“少了一块”时，先确认 DOM 是否存在。

在 Elements 里搜索疑似节点，或在 Console 执行：

```js
Array.from(document.querySelectorAll('body *'))
  .filter(el => (el.innerText || '').includes('目录') || (el.className || '').toString().includes('card'))
  .slice(0, 20)
  .map(el => ({
    tag: el.tagName,
    id: el.id,
    cls: el.className,
    text: (el.innerText || '').trim().slice(0, 60),
    rect: (() => {
      const r = el.getBoundingClientRect();
      return { x: r.x, y: r.y, width: r.width, height: r.height };
    })(),
    style: (() => {
      const s = getComputedStyle(el);
      return {
        display: s.display,
        visibility: s.visibility,
        opacity: s.opacity,
        transform: s.transform,
        position: s.position,
        overflow: s.overflow,
        contentVisibility: s.contentVisibility
      };
    })()
  }))
```

判断方法：

- 节点不存在：优先查 JS 数据加载、接口、路由、资源失败。
- 节点存在但 `display:none` / `visibility:hidden` / `opacity:0`：优先查 CSS 状态、动画、懒显示逻辑。
- 节点存在且 `height:0`：优先查布局容器、图片未加载、CSS 计算、父元素 overflow。
- 节点在屏幕外：查 `transform`、滚动定位、safe area、视口高度。

---

## 5. 针对 books 页面样本的固定诊断

目标样本：

- `https://pages.anzz.site/books`
- `https://pages.anzz.site/books/人生哲理/老祖宗留下的10句话，说尽人生百态.html`

文章页内容卡片曾出现“DOM 存在但没显示”的情况，可执行：

```js
Array.from(document.querySelectorAll('.wisdom-card')).slice(0, 10).map((el, index) => {
  const r = el.getBoundingClientRect();
  const s = getComputedStyle(el);
  return {
    index,
    className: el.className,
    text: el.innerText.trim().slice(0, 80),
    rect: { x: r.x, y: r.y, width: r.width, height: r.height },
    display: s.display,
    visibility: s.visibility,
    opacity: s.opacity,
    transform: s.transform
  };
})
```

如果 `opacity` 是 `0` 且 class 里没有 `visible`，再执行：

```js
window.dispatchEvent(new Event('resize'));
window.dispatchEvent(new Event('scroll'));
document.dispatchEvent(new Event('visibilitychange'));
if (window.visualViewport) {
  visualViewport.dispatchEvent(new Event('resize'));
  visualViewport.dispatchEvent(new Event('scroll'));
}
```

如果执行后内容出现，说明页面依赖 viewport、scroll、visibility 或 IntersectionObserver 的触发，后续应重点查：

- WebView 初次 `loadUrl()` 是否早于 attach/layout。
- `innerHeight` / `visualViewport.height` 是否异常。
- 页面是否在初始隐藏、透明、离屏状态下执行了懒显示判断。
- App 是否在页面完成后正确补偿 resize/scroll/visibility 事件。

---

## 6. Eruda / vConsole 注入检查

如果页面上看不到 Eruda 图标，不要只判断为“注入失败”，先在 Chrome Inspect Console 执行：

```js
({
  erudaGlobal: !!window.eruda,
  erudaDom: !!document.querySelector('.eruda-container, .eruda-entry-btn'),
  vConsoleGlobal: !!window.VConsole || !!window.vConsole,
  vConsoleDom: !!document.querySelector('#__vconsole, .vc-switch'),
  scripts: Array.from(document.scripts).map(s => s.src).filter(Boolean)
})
```

再看 Network 里调试工具 CDN 是否加载成功。

常见原因：

- 页面发生 SPA 路由或重定向，早期注入被新文档替换。
- CDN 脚本被站点 CSP、网络、广告过滤或证书策略阻止。
- 页面自身样式层级覆盖了调试入口按钮。
- WebView 被复用或销毁重建后，注入状态标记和真实 DOM 不一致。

---

## 7. Loading 卡住排查

如果 App Loading 显示 100% 但进不去页面，按顺序检查：

1. logcat 是否有 `Page finished`。
2. Chrome Inspect 里 `document.readyState` 是否是 `complete`。
3. Console 执行：

```js
({
  readyState: document.readyState,
  progressLike: performance.getEntriesByType('navigation')[0],
  bodyChildren: document.body ? document.body.children.length : 0,
  bodyTextLength: document.body ? document.body.innerText.trim().length : 0,
  visibleElements: Array.from(document.body ? document.body.querySelectorAll('*') : [])
    .filter(el => {
      const r = el.getBoundingClientRect();
      const s = getComputedStyle(el);
      return r.width > 0 && r.height > 0 && s.display !== 'none' && s.visibility !== 'hidden' && Number(s.opacity) > 0;
    }).length
})
```

判断方法：

- `readyState=complete` 且可见元素很多：App 遮罩状态判断有问题。
- `bodyChildren` 很少：SPA 可能还没 hydrate，查 JS bundle 和接口。
- 可见元素为 0 但 DOM 很多：CSS/动画/懒显示状态有问题。

---

## 8. 对比手机浏览器

同一个 URL，在手机自带浏览器和 App WebView 分别采集：

- 截图。
- Console 基础环境诊断输出。
- Network 失败请求列表。
- “缺失节点”的 computed style。
- UA 字符串。

对比时优先看这些差异：

| 差异项 | 可能原因 |
|---|---|
| UA 不同 | 站点分流、WebView 降级分支 |
| viewport 不同 | WebSettings、系统栏、刘海屏、横竖屏、加载时机 |
| Network 失败不同 | App 拦截、混合内容、Cookie、CORS、CSP |
| DOM 缺失 | JS 没执行、接口失败、路由异常 |
| DOM 存在但不可见 | CSS 状态、懒加载、IntersectionObserver、动画 |
| App 有 history 空白页 | 热备/预加载 WebView 残留 `about:blank` |

---

## 9. 建议提交给开发的最小证据包

每次反馈一个 WebView 渲染问题，尽量提供：

- 问题 URL。
- 设备型号、Android 版本、WebView 版本。
- App 中相关开关截图，尤其是 UA、限制项、预加载、热备、Chrome Inspect。
- logcat 文件或关键日志片段。
- Chrome Inspect Console 诊断输出。
- Network 失败请求截图或导出的 HAR。
- Elements/Computed Style 中异常节点截图。
- 手机自带浏览器同 URL 的对比截图。

这些信息足够判断问题更可能在 App 宿主、WebView 配置、页面脚本、资源加载还是目标网站兼容性。
