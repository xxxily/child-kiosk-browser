# Changelog

本项目所有显著变更都将记录在此文件中。

格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，版本遵循 [Semantic Versioning](https://semver.org/lang/zh-CN/)。

## [Unreleased]

### Added — 新增

- **WebView 承载模式 AB 测试**：
  - 家长后台“网页性能优化”新增“WebView 承载模式（AB 测试）”，支持“标准 Compose / 轻量原生”切换。
  - 轻量原生模式使用原生 `FrameLayout + WebView` 直接承载页面，跳过 Compose `AndroidView` 宿主、全屏 Loading、热备池和 URL 预加载，用于定位宿主层是否放大 Chromium tile 压力。
  - 新增 AB 诊断临时选项：视觉提交回调关闭 Loading、页面激活事件补发、延迟多轮脚本注入、轻量模式顶部原生进度条。
  - 新增 `AB diagnostics`、`Host mode applied`、`WebView surface`、`Visual state callback requested/delivered/timeout` 等日志，便于 logcat 对比标准模式与轻量模式。

## [0.0.24] - 2026-06-14

### Added — 新增

- **高分屏渲染兼容模式**：
  - 家长后台“网页性能优化”新增“高分屏渲染兼容模式”，支持“自动 / 开启 / 关闭”。
  - 自动模式会在 DPR>=3.5 且物理像素较高的设备上注入轻量渲染补丁，降低动画、`will-change`、filter、backdrop-filter 等高成本合成路径。
  - 针对 `pages.anzz.site/app/piano` 增加站点兼容补丁：降低运行时键宽、隐藏特效层、去除键盘大阴影并重排黑键，减少超宽 88 键键盘在高 DPR WebView renderer 中的 tile 成本。
  - 针对 `pages.anzz.site/books` 增加站点兼容补丁：停用首屏渐变动画和卡片 hover transform，减少全屏渐变/阴影层造成的 tile 压力。

## [0.0.23] - 2026-06-14

### Changed — 变更

- **WebView tile 内存策略回滚**：
  - 禁用 v0.0.22 引入的高 DPR 自动切 `LAYER_TYPE_SOFTWARE` 策略；实测该策略会让 `tile memory limits exceeded` 从少量变为连续刷屏。
  - “WebView 渲染模式”后台选项收敛为“自动默认 / 硬件默认”，旧版本保存过 `SOFTWARE` 时会自动退回 `AUTO`。
  - `Render mode applied` 日志增加 `memoryClass/largeMemoryClass/heapMax/heapTotal/heapFree`，便于确认设备给 App 的真实内存基线。
- **WebView 首绘内存余量优化**：
  - 应用声明 `android:largeHeap="true"`，为高 DPR WebView 页面提供更大的宿主进程内存余量。
  - `WebViewActivity` 切换到独立 `:webview` 进程，避免 WebView 与主页 Compose、Room、图片加载和后台管理页共享同一个 App 进程内存预算。
  - WebView 进程启动时设置独立 data directory suffix，主页进程不再创建 WebView 热备/预加载实例，避免多进程 WebView 数据目录冲突和无效内存占用。
  - 设置 WebView renderer priority 为 `RENDERER_PRIORITY_IMPORTANT`，降低活跃 WebView renderer 被系统回收的概率。
  - 页面加载完成后不再对全屏 loading 遮罩做退出淡出，并将最小遮罩时间从 600ms 降为 120ms，减少首绘阶段额外合成层压力。

### Fixed — 修复

- **WebView 软件渲染回退误判**：
  - 根据 `webview-debug.log` 中 `actual=SOFTWARE` 后大量 `tile memory limits exceeded` 的证据，修正文档与调试手册中“切软件兼容绕开 tile 压力”的错误结论。

## [0.0.22] - 2026-06-14

### Added — 新增

- **WebView 渲染模式配置**：
  - 家长后台“网页性能优化”新增 WebView 渲染模式，可在“自动兼容 / 硬件默认 / 软件兼容”之间切换。
  - 自动兼容模式会在高 DPR 大屏设备上切到软件兼容绘制，绕开 Chromium 硬件 tile/GPU 合成预算不足导致的局部不绘制问题。
  - logcat 新增 `Render mode applied` 诊断日志，显示请求模式、实际模式、屏幕像素和 density。

### Changed — 变更

- **WebView tile 内存问题说明修正**：
  - 文档明确 `tile memory limits exceeded` 不是简单等同设备 RAM 不足，而是 WebView/Chromium 合成器 tile/GPU 栅格化预算不足。
  - 调试手册补充软件兼容渲染模式的排查与切换步骤。

## [0.0.21] - 2026-06-14

### Changed — 变更

- **WebView 渲染内存基线收紧**：
  - 默认关闭 `offscreenPreRaster`，避免高 DPR 设备上离屏预栅格化放大 Chromium tile 内存压力。
  - 空白 WebView 热备默认改为关闭，保留后台开关供内存充足设备手动开启。
  - 多窗口 WebView 栈只 attach 当前顶层 WebView，底层实例不再作为隐藏原生 View 继续参与窗口测量和绘制。

### Fixed — 修复

- **WebView 局部内容不绘制排查修正**：
  - 根据 logcat 中 `tile memory limits exceeded, some content may not draw` 的证据，修正此前只按视口/懒加载方向排查的判断。
  - 更新 WebView 渲染一致性文档与调试手册，将 tile 内存超限列为进度 100%、无网络/JS 错误但局部不绘制时的优先排查项。

## [0.0.20] - 2026-06-14

### Added — 新增

- **WebView 调试取证运行手册**：
  - 新增 `docs/webview_debugging_runbook.md`，固化 logcat、Chrome Inspect、Network、Elements、Computed Style、Eruda/vConsole 注入检查和 Loading 卡住排查流程。
  - README 项目结构同步补充 WebView 渲染复盘与调试手册入口。

### Changed — 变更

- **WebView 初次加载时序优化**：
  - 主 WebView 不再在 Compose `remember` 创建阶段立即 `loadUrl()`，改为等 `AndroidView` attach 且完成有效尺寸布局后再加载真实 URL，降低页面以零尺寸/错误 viewport 启动导致懒加载、`visualViewport`、`IntersectionObserver` 判断异常的概率。
- **WebView 运行时日志增强**：
  - 增加 `ChildKioskWebView` 关键日志，覆盖初始布局后加载、页面开始/完成、主 frame 错误、HTTP 错误、广告拦截、SSL 拦截和热备历史清理，便于后续直接用 logcat 定位问题。

### Fixed — 修复

- **热备 WebView 返回空白页问题**：
  - 修复启用空白 WebView 热备后，进入网页再手势返回会先回到 `about:blank`、需要再次返回才回首页的问题。
  - 对热备和 URL 预加载接管场景清理初始空白历史栈，同时保留网页内部后续导航的正常返回能力。

## [0.0.19] - 2026-06-14

### Added — 新增

- **WebView 渲染一致性复盘文档**：
  - 新增 `docs/webview_rendering_consistency.md`，系统记录 WebView 与手机浏览器渲染不一致的根因、修复策略、基线配置和后续排查流程。
- **User-Agent 可视化与自定义配置**：
  - 家长后台“安全沙箱与限制”新增系统默认 UA、当前实际 UA 展示，并支持输入自定义 User-Agent。
- **网页摄像头与麦克风限制配置**：
  - 新增独立的“禁用网页摄像头与麦克风”开关，避免继续把媒体采集权限错误绑定到定位限制上。

### Changed — 变更

- **WebView 默认基线改为浏览器兼容优先**：
  - 关闭全局 `useWideViewPort/loadWithOverviewMode`，避免现代移动响应式页面被缩放成非预期视口。
  - 广告过滤、下载限制、长按限制、外链跳转限制、定位限制、多窗口限制、文件/content 访问限制默认改为关闭；相关限制仍保留在后台供家长按需加严。
  - 下载默认交给系统 DownloadManager 处理，禁用下载时才阻断。
- **浏览器沙箱说明文案调整**：
  - “网页浏览器沙箱限制”顶部新增兼容基线说明，并逐项标明开启限制可能影响的网页能力。

### Fixed — 修复

- **WebView 局部内容不显示/交互异常问题**：
  - 针对依赖 `IntersectionObserver`、滚动揭示、懒加载和可见性事件的页面，在页面完成后补发 `resize/scroll/pageshow/visibilitychange` 等通用事件，降低 DOM 已加载但内容保持透明或未激活的概率。
- **多次打开网页后卡 0% 问题**：
  - 真实页面 WebView 不再回收到全局热备池，退出页面时直接销毁，只保留空白 WebView 热备，避免旧页面状态污染新页面。
- **缓存清理误触问题**：
  - 清理网页缓存与 Cookie 前增加确认弹窗，并显示当前统计缓存大小。
- **刘海屏与横屏系统栏显示问题**：
  - 优化 Device Owner 场景下的状态栏策略，竖屏保留系统时间信息，横屏避免顶部半透明黑条。

## [0.0.18] - 2026-06-14

### Added — 新增

- **空白 WebView 热备池**：
  - 默认保留 1 个已初始化的空白 WebView，打开网页时优先复用，降低冷创建成本。
  - 保留 URL 级预加载开关，但将其默认关闭，避免无感占用网络、内存和网站会话。
- **缓存大小统计与清理反馈**：
  - 家长后台“网页性能优化”新增 WebView 数据、HTTP 缓存、代码缓存和合计大小统计。
  - 清理缓存与 Cookie 后展示本次大致释放空间。
- **首页 sticky 分类栏**：
  - 首页标题随应用列表滚动消失，分类栏吸顶固定；列表回到顶部继续下拉时标题自然恢复。

### Changed — 变更

- **WebView 运行时配置统一化**：
  - 统一抽取 WebView 设置，补充第三方 Cookie、混合内容兼容模式、浏览器 User-Agent 等选项，提升现代网页兼容性。
  - 调试工具和自定义脚本注入默认改为多时机兜底，提升 Eruda/vConsole 在多页面、SPA 与重定向场景下的成功率。
- **刘海屏系统信息显示策略**：
  - Device Owner + Lock Task 模式下保留状态栏系统信息（时间、电量、网络），减少顶部黑条浪费。
  - 非 Device Owner 场景仍保持沉浸式系统栏隐藏，避免无法可靠禁止通知栏下拉而带来逃逸风险。

### Fixed — 修复

- **Loading 100% 卡住问题**：
  - 优化 Loading 遮罩状态与完成回调兜底，减少网页已渲染但仍停留在 App 加载遮罩的问题。
- **白名单编辑横向选择不可达问题**：
  - 新增/编辑白名单应用时，分类与代表图标选择支持横向滚动，超出可视范围的选项可正常选择。

## [0.0.12] - 2026-06-13

### Added — 新增

- **网页内置调试面板与 USB 远程调试配置化**：
  - 在“安全沙箱与限制”下新增了 **“网页调试与开发配置”** 控制模块。
  - 支持 **网页内置调试面板一键配置**：管理员可按需选择注入 **「无」/「vConsole」/「Eruda」/「自定义脚本」**。
  - 采用 **CDN 动态脚本加载器** 技术，直接在 WebView 运行期动态创建 script 标签加载所配置 the CDN 地址并自动执行初始化，实现 **APK 安装包体积零增长（0 字节冗余）**。
  - 提供默认官方 CDN 地址，并支持 **CDN URL 自由填入和重置**，管理员可按需改用内部局域网 CDN、更快源镜像或更新的版本。
  - 支持 **注入时机自定义配置**，可选择 **「页面开始加载 (onPageStarted)」**（推荐/默认，能更早捕获渲染初始化错误）或 **「页面加载完成 (onPageFinished)」**。
  - 支持 **自定义 JavaScript 脚本片段输入**，可实现任意扩展脚本（如特定 Mock 模块、自定义环境探针等）并在设定时机自执行注入。
  - 支持 **启用 USB 远程调试 (Chrome Inspect) 开关**，开启后连接电脑在 Chrome 输入 `chrome://inspect` 即可利用电脑端 Chrome DevTools 抓包和断点调试，提供工业级排错能力。

## [0.0.11] - 2026-06-13

### Added — 新增

- **底层安全沙箱与限制能力统一配置化与管理**：
  - 在家长控制后台主目录新增 **“安全沙箱与限制”** 入口，提供全新的二级配置页，以 “🛡️ 系统安全防逃逸限制”、“📱 界面与物理按键限制”、“🌐 网页浏览器沙箱限制” 三大类精致卡片开关供家长精细管理 18 个底层限制属性，并配有详细的用户感知说明。
  - 所有 18 个底层安全和网页沙箱限制项（包括 ADB、安全模式、恢复出厂、多用户、USB传输、截图录屏、状态栏、锁屏、FLAG_SECURE、物理音量按键拦截、网页广告过滤、文件下载、长按复制、外链跳转、地理位置、SSL校验等）均与 `KioskPrefs` 配置绑定，默认均为开启状态（音量按键拦截除外）。
  - 如果当前设备未激活 Device Owner，管理后台会在系统限制卡片顶部显示醒目的黄色警告提示横幅，交互更加透明友好。
  - 编写了配置变更时的实时同步与回调接口 `onSandboxLimitsChanged`，只要在管理后台更改开关，Device Owner 和 FLAG_SECURE 相关的锁定加固策略就会立刻实时刷新。

## [0.0.10] - 2026-06-13

### Added — 新增

- **白名单应用列表分类管理功能**：
  - 数据模型与库结构升级：在 `WebAppEntity` 中引入了 `category` 字段，Room 数据库升级至版本 3。为内置的预置白名单应用自动匹配了正确的分类（游戏、视频、绘本等）。
  - 编辑与添加支持分类：在 `AddEditWebAppDialog` 中引入了横向分类选择组件，允许家长在创建和编辑白名单应用时直接划定分类。
  - 管理后台列表卡片显示分类标签：白名单管理列表中，每个应用的卡片现在都会直观展示其分类状态（如 `🎮 游戏`）。
- **主页（儿童首屏）分类 Tab 展示与过滤**：
  - 新增了一行精美的流式大圆角单选分类 Tab（🌟 全部、🎮 游戏、📺 视频、📚 绘本、✍️ 学习、⚙️ 其他），点击不同的 Tab 可以对首屏卡片进行实时的平滑过滤展示，带来绝佳的儿童上网交互体验。

### Changed — 变更

- **管理后台（AdminConsoleScreen）层级折叠化重构**：
  - 彻底抛弃了以前所有设置项目大卡片平铺的一眼望不到头的混乱设计，将管理后台重构为极具秩序感和设计感的层级菜单。
  - 主页首屏为 Preference Item 列表式大目录（包含：🛡️ 安全防护与锁定、⏳ 健康限时管理、🔑 家长身份验证、🎨 界面与显示配置、⚡ 网页性能优化、🖥️ 应用白名单管理）。点击大项可流畅进入配备独立 Back 按钮的二级设置页面。

### Fixed — 修复

- **优化屏幕固定软锁（Soft Lock）防重复提示逻辑**：
  - 解决了非 Device Owner 模式下软锁在被拒绝（点击系统弹窗的“不用了”）或被取消后，由于 `onResume` 生命周期导致每次切走再切回应用时重复弹出系统锁定框的死循环提示 bug。
  - 引入了内存标志位 `isSoftLockDeferred`，当检测到未成功锁定且失去/重新获得焦点时，自动挂起在此 Session 内的自动锁定提示。
  - 用户在后台重新启用软锁时，自动重置标志位并立即触发锁定请求，提供即时的交互响应。

## [0.0.9] - 2026-06-13

### Added — 新增

- **关于与系统诊断展示**：
  - 家长后台新增“关于与系统诊断”专属卡片，包含项目 GitHub 地址（支持一键复制与系统浏览器跳转）与开发者署名。
  - 动态读取并显示应用版本、系统 WebView 内核包名与版本信息、设备型号、Android 系统版本及防护等级（Device Owner 状态）。
- **检查更新与 OTA 下载**：
  - 支持“检查最新版本”功能。异步获取 GitHub Latest Releases API，智能比对本地与远程版本号。
  - 发现新版本时弹出更新日志 Dialog，提供跳转下载和复制下载链接。
- **应用名称升级为「儿童空间」**：
  - 更新了应用字符串，将原有的“儿童乐园”更名为“儿童空间”，包括桌面图标名称、设备管理器标签等，使界面更加专业统一。
- **非固定模式下后台正常存活与切回**：
  - 移除了 Manifest 中对 `MainActivity` 和 `WebViewActivity` 的 `excludeFromRecents="true"` 限制，使应用在非屏幕固定模式下按 Home 键切走后能从“最近任务”列表中正常显示并切回。

### Changed — 变更

- **物理音量控制释放**：
  - 移除了对音量物理键的强制屏蔽。当儿童网页中包含音视频内容时，可正常通过物理音量键调节系统声音，保障多媒体体验。

## [0.0.8] - 2026-06-13

### Fixed — 修复

- **修复数据库破坏性迁移导致白名单为空的 Bug**：
  - 修复了 Room 数据库从 `version = 1` 升级至 `version = 2` 触发 Destructive Migration（破坏性迁移）时，不调用 `onCreate` 回调导致内置白名单丢失的缺陷。重写并添加了 `onDestructiveMigration` 回调。
  - 移除了在协程中直接读取未赋值全局单例 `INSTANCE` 的竞争隐患，改用 `getInstance(context)` 线程安全机制。

## [0.0.7] - 2026-06-13

### Added — 新增

- **内置 9 个精选国内绿色儿童网站**：
  - 新置了国家中小学智慧教育平台、北京故宫博物院(青少版)、中华珍宝馆、科普中国、中国数字科技馆、汉字屋、编程猫、中国科普博览、宝宝巴士官网共 9 个国内优质儿童网站。所有内置网站默认状态均为禁用，由家长按需手动开启。
- **白名单启用/禁用控制开关**：
  - 为家长控制台的白名单列表站点卡片新增了 `Switch` 开关。可以随时控制决定该网站是否显示在儿童乐园主页中，而无需频繁删除和重新添加。
- **乐园主网格精细过滤**：
  - 儿童主页面（乐园主页）自动根据启用状态过滤展示，只有开启的白名单网站才会显示在网格图标中，并只针对启用的网站进行后台预加载。

### Changed — 变更

- **数据库版本升级**：
  - 数据库表结构升级至 `version = 2`，并在实体中引入 `is_enabled` 状态字段，升级时自动迁移重置内置应用。

## [0.0.6] - 2026-06-13

### Fixed — 修复

- **预缓存/预加载 100% 卡死问题**：
  - 修复了预加载的 WebView 挂载到 Window 时因重新 attach 导致底层重新触发 `onPageStarted` 却不再触发 `onPageFinished` 的重入卡死白屏 bug。我们在 `onPageStarted` 中拦截并忽略了网页 progress 为 100% 时的重入通知，同时使用 `remember(preloadEntry)` 进行 Compose 参数同步，彻底杜绝了 100% 页面的卡死。
  - 修复了已加载完成的预加载实例在重置时强行将背景色设为暖黄色 `#FFF8E1` 导致的网页渲染底色异常，现在根据 `existingWebView.progress == 100` 自适应选用初始底色。
- **网页右上角点击区域拦截与误退问题**：
  - 去除了 `WebViewActivity` 顶部的右上角盲点击进入设置区域，避免了该透明区域拦截网页对应坐标（右上角按钮）的点击事件。用户现可通过系统物理/虚拟返回键自然退回主屏幕。

## [0.0.5] - 2026-06-13

### Added — 新增

- **网页后台预加载与复用池**：
  - 新增 `WebViewPool` 网页预加载与实例复用机制。支持在主页完全闲置（通过 `IdleHandler` 监听）时在后台分步预加载最常用的前 3 个网页，并与 `onTrimMemory` 结合在系统内存紧张时自动进行分级释放以规避 OOM。
  - 新增网页后台预加载 Switch 开关与一键手动“清理网页缓存与Cookie”的操作卡片，全面优化系统存储和运行稳定性。
- **品牌化网页加载遮罩层与渐隐过渡**：
  - 新增全屏 `LoadingOverlay` 遮罩层，在网页完全绘制前优雅展示应用的暖黄色渐变背景、圆形加载进度百分比和儿童友好趣味提示语。
  - **三重就绪判定**：结合网页进度 >= 85%、`onPageFinished` 触发以及 `evaluateJavascript` 注入 JS 检查 DOM 实质子节点渲染状态，完美规避了 SPA 页面“空壳 HTML”引起的过渡提前关闭问题。
  - 支持 600ms 的最小展示时间保护，配合 400ms 渐隐动画，实现极其平滑高档的视觉效果。
- **网页加载异常/错误提示界面**：
  - 新增全屏 `LoadingErrorOverlay` 遮罩层，在网页遇到 SSL 异常、主框架加载失败、超时或服务端返回 HTTP 4xx/5xx 时，平滑切换为精美的报错提醒页，并提供「重试一下」与「返回乐园」选项。
- **WebView 引擎预热与离线自动清理**：
  - 在自定义 `ChildKioskApplication` 启动时进行空闲预创建预热，消除了 WebView 引擎（Chromium）首次拉起时的冷启动耗时。
  - 引入 7 天自动缓存垃圾回收，在启动检测超过时间后自动于后台调用缓存和 Cookie 的清空操作。

### Changed — 变更

- **进程架构简化与 View 挂载复用**：
  - 移除了 `WebViewActivity` 的独立 `:webview` 子进程属性。因为 Android 的原生 View 机制不支持跨进程直接 addView 挂载，合并入主进程后，预加载好的后台 WebView 实例方可被无缝挂载使用。
- **底色匹配与无背景网页适配**：
  - 将 `WebView` 初始画布的白色改为匹配应用色调的 `#FFF8E1`，网页完全载入后自动恢复 `WHITE` 白色，完美消除了白屏闪烁，同时保障了无背景色网页的文字对比度。

## [0.0.4] - 2026-06-13

### Added — 新增

- **网站退出验证按需配置**：
  - 新增 `verify_on_web_exit` 配置项，默认关闭退出验证。按返回键或顶部区域 5 次盲点击可以直接安全退出到主页。开启该配置则恢复之前的强制家长密码验证逻辑。
- **右上角可交互管理锁图标**：
  - 在主页右上角添加了精美的锁头卡片图标（当未开启隐藏时），引导家长点击进入管理后台。多次点击该锁头即可弹出家长验证框。
  - 新增 `hide_admin_icon` 配置开关，在控制台开启隐藏后，锁头图标不显示，但仍保留盲区 5 次点击的后台入口。
- **主页标题文字自定义与隐藏**：
  - 新增 `main_title_text` 配置项，允许家长在后台将“我的游戏乐园”修改为任意自定义文本。
  - 新增 `hide_main_title` 选项，支持一键隐藏主页顶部标题，让首屏布局更为纯净。
- **内置图标库扩增**：
  - 将内置图标数增加至 12 个，支持更多的日常分类（如星星、书本、手柄、视频、音乐、地球、相机、学校、灯泡、笑脸、心形、主页）。

### Changed — 变更

- **白名单应用删除限制解除**：
  - 解除了默认预设应用（isPreset）的无法删除限制。现在所有应用均可自由删除，避免造成应用列表的锁死。
- **自定义 Favicon 卡片排版优化与预览**：
  - 将原来的自定义 Favicon 单行布局改为了更宽敞舒适的纵向多行布局。
  - 引入了 56dp x 56dp 的 Coil `AsyncImage` 框进行实时图片预览。当输入有效的 HTTP/HTTPS 图片网址时，自动加载展示预览，加载失败或格式不合法时优雅显示警告，界面高档雅致。

## [0.0.3] - 2026-06-13

### Added — 新增

- **自定义网址图标与 Favicon 自动推导**：
  - 引入了 `Coil` 异步图片库加载 URL 自定义图标，支持网络缓存及降级回退显示内置 Star 图标。
  * `AddEditWebAppDialog` 监听 `urlInput`，自动解析并推导出其默认 favicon 地址。
  * 提供了自定义图标 URL 文本输入卡片与单选框，可随时与内置图标切换使用，默认自动勾选填充推导的 Favicon。
- **屏幕显示方向配置**：
  - 支持自适应（AUTO）、锁定横屏（LANDSCAPE）和锁定竖屏（PORTRAIT）三种方向。
  * `MainActivity.onCreate` 和 `WebViewActivity.onCreate` 早期同步应用该配置，杜绝 Activity 旋转和闪动。
  * 在管理后台提供了“屏幕显示方向”卡片，修改后即时旋转屏幕。
- **首屏图标网格自适应大小**：
  - 支持小（SMALL）、中（MEDIUM）和大（LARGE）三种图标及文字大小选项卡，自适应网格密度，适合横屏展示更多应用。
- **下拉状态栏 3 秒自动隐藏**：
  - 针对 `MainActivity` 和 `WebViewActivity` 增加了窗口 System UI 监听器，在下滑呼出半透明灰色块后，3秒内自动调用沉浸模式进行再次隐藏，防止其在部分 OEM 上卡死占位。

### Fixed — 修复

- 修复了添加/编辑应用 Dialog 在横屏模式下无法滚动的体验 Bug（在表单 Column 中引入 `verticalScroll`）。
- 重构 `isValidUrl` 校验逻辑，利用 Android 原生 `Patterns.WEB_URL`，放行并完美兼容局域网 IP（例如 `192.168.1.1`）以及 `localhost` 的测试网络。
- 允许添加明文 HTTP 网址（修改 `network_security_config.xml` 与 Manifest），且在输入以 `http://` 开头时提供橙色警告以提醒传输风险。

## [0.0.2] - 2026-06-13

引入**分级防护模型**：不再把 Device Owner 当作「能否使用本应用」的前提，而是当作「锁得多死」的等级。普通侧载安装即可开箱使用，按当前可用权限自动选择最高防护档。

### Added — 新增

- **分级防护模型（三档）**
  - **Tier 1 · Device Owner 完全锁定**：与 v0.0.1 一致，企业级全方位防逃逸（保持不变）
  - **Tier 2 · 屏幕固定软锁（默认）**：非 Device Owner 时调用系统「屏幕固定（Screen Pinning）」拦截 Home/最近任务键，无需恢复出厂、无需任何特殊权限即可使用
  - **Tier 3 · 无系统级锁定**：仅沉浸式全屏 + 自定义 Launcher + 家长验证退出，适合开发调试与临时体验
  - `MainActivity.onResume` 自动选档：是 Device Owner 走 Tier 1，否则读取家长配置（默认 Tier 2）
  - 新增 `util/KioskPrefs`：以 SharedPreferences 持久化非 Device Owner 场景下的防护等级
- **家长后台**
  - 新增「防护等级」卡片：非 Device Owner 时可在 Tier 2 / Tier 3 间切换，并内置 Device Owner 升级引导（复制 ADB 脚本 + 跳转默认桌面设置）；已是 Device Owner 时仅展示状态
  - 新增「退出并安全解锁」按钮

### Changed — 变更

- 儿童主网格**不再以 Device Owner 为使用前提**：原先未激活 Device Owner 时整屏被激活引导页占据，现在主网格始终可用，激活引导移入家长后台
- `stopLockTaskMode` 兼容非 Device Owner 场景：用户限制清理调用仅在 Device Owner 时执行，软锁场景安全退出
- README 重写部署指南为「路径 A 普通侧载 / 路径 B Device Owner 完全锁定」双路径，新增分级防护模型说明

> **推荐做法说明（本版本由 AI 协助实现并记录）**：默认档位选用 **Tier 2 屏幕固定软锁**（而非无锁定），在「免特殊权限」与「基础防逃逸」间取平衡；不在应用内提供 Device Owner 降级入口（降级属高风险操作，需 ADB `dpm remove-active-admin`）；防护等级用 SharedPreferences 而非 Room 存储，因选档决策发生在 Compose Room Flow 订阅之前、需同步读取。如需调整可在后续版本修改。

## [0.0.1] - 2026-06-13

首个可用预览版本。基于 `docs/child_kiosk_browser_requirements.md` 与 `docs/android_kiosk_research_report.md` 完整落地。

### Added — 新增

- **Kiosk 核心**
  - `MainActivity` 自动启用 Lock Task Mode，前台时强制锁定，无系统弹窗
  - `setLockTaskFeatures(LOCK_TASK_FEATURE_SYSTEM_INFO)`：仅保留状态栏系统信息
  - 多维度 User Restriction：`DISALLOW_VOICE_ASSISTANTS`、`DISALLOW_DEBUGGING_FEATURES`、`DISALLOW_INSTALL_UNKNOWN_SOURCES`、`DISALLOW_FACTORY_RESET`、`DISALLOW_SAFE_BOOT`、`DISALLOW_ADD_USER`、`DISALLOW_USB_FILE_TRANSFER`
  - `setScreenCaptureDisabled(true)` + `FLAG_SECURE` 双重防截屏
  - `setStatusBarDisabled(true)` + `setKeyguardDisabled(true)`
  - 自定义 Launcher（`category.HOME`）+ `BootReceiver` 实现重启自启动
  - 沉浸式全屏（隐藏状态栏与导航栏）
  - 物理音量键拦截，避免儿童误触刺耳音量条
- **沙箱 WebView**
  - `:webview` 独立子进程运行，OOM 不会拖垮主进程
  - 完整 WebSettings 加固：禁文件协议、禁 window.open、禁地理位置、禁表单/密码保存、强制 HTTPS、禁混合内容
  - `shouldOverrideUrlLoading` 仅放行 http/https，并按主域同源校验跨域跳转
  - `shouldInterceptRequest` 接入 `AdBlocker`，命中 50+ 广告/追踪域名直接返回空响应
  - `onReceivedSslError` 强制 `handler.cancel()` + 应用内安全警告页
  - `WebChromeClient.onPermissionRequest` 默认 deny；`onCreateWindow` 返回 false
  - `setDownloadListener` 拦截所有下载，禁止 APK 落盘
  - `BackHandler`：优先 `webView.goBack()`，无历史时触发家长验证后才允许退出
  - 退出时彻底销毁 WebView：loadUrl(blank) + clearHistory + clearCache + destroy
- **家长后台**
  - 右上角 80dp 隐藏区域 2 秒内连击 5 次触发家长验证
  - 动态口算题（加减法 100 内 / 九九乘法表）+ 4 位数字 PIN 码两种验证模式
  - PIN 码以 SHA-256 哈希存储，可在后台随时修改
  - 单次时长（0-120 分钟可调）+ 每日累计时长（0-240 分钟可调）双重限制
  - 时长耗尽后切入"小眼睛该休息啦"全屏提醒页，验证家长身份后可延长 30 分钟
  - Web 应用白名单：增/删/改/排序，URL 自动 https 转换 + 网络可达性预检（连通失败可二次确认强行保存）
  - 5 个内置图标可选（游戏机/火箭/拼图/书本/画笔）
  - 预设 3 个白名单：Scratch、PBS Kids、NASA Kids' Club（开箱即用，不可删除）
- **儿童 UI**
  - Material3 自定义主题：暖橙 / 天蓝 / 草绿 高饱和儿童友好色彩
  - 网格主页：72dp+ 大触控目标 + 24dp 圆角 + Q 弹缩放微动画 + 触觉反馈
  - 自适应列数 LazyVerticalGrid，平板大屏体验自然
- **构建/CI**
  - GitHub Actions 工作流：分支推送构建 Debug+Release APK 上传 Artifact
  - 推送 `v*` tag 时自动发布 GitHub Release 并上传 APK
  - 阿里云 Maven 镜像加速依赖下载
  - R8/ProGuard 启用，Room 实体/DAO/Receiver 关键反射点已加 keep 规则

### Security — 安全

- 默认禁用 backup（`allowBackup="false"`），同步配置 `data_extraction_rules` / `backup_rules`
- 配置 `network_security_config` 禁止明文流量（cleartext）

### Documentation — 文档

- 完整 README：功能、技术栈、本地构建、ADB 部署 Device Owner、家长操作手册、安全模型、发版流程
- 调研报告 `docs/android_kiosk_research_report.md`
- 需求规格 `docs/child_kiosk_browser_requirements.md`

[Unreleased]: https://github.com/xxxily/child-kiosk-browser/compare/v0.0.24...HEAD
[0.0.24]: https://github.com/xxxily/child-kiosk-browser/compare/v0.0.23...v0.0.24
[0.0.23]: https://github.com/xxxily/child-kiosk-browser/compare/v0.0.22...v0.0.23
[0.0.22]: https://github.com/xxxily/child-kiosk-browser/compare/v0.0.21...v0.0.22
[0.0.21]: https://github.com/xxxily/child-kiosk-browser/compare/v0.0.20...v0.0.21
[0.0.20]: https://github.com/xxxily/child-kiosk-browser/compare/v0.0.19...v0.0.20
[0.0.19]: https://github.com/xxxily/child-kiosk-browser/compare/v0.0.18...v0.0.19
[0.0.18]: https://github.com/xxxily/child-kiosk-browser/compare/v0.0.17...v0.0.18
[0.0.4]: https://github.com/xxxily/child-kiosk-browser/compare/v0.0.3...v0.0.4
[0.0.3]: https://github.com/xxxily/child-kiosk-browser/compare/v0.0.2...v0.0.3
[0.0.2]: https://github.com/xxxily/child-kiosk-browser/compare/v0.0.1...v0.0.2
[0.0.1]: https://github.com/xxxily/child-kiosk-browser/releases/tag/v0.0.1
