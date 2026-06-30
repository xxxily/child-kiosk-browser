# Changelog

本项目所有显著变更都将记录在此文件中。

格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，版本遵循 [Semantic Versioning](https://semver.org/lang/zh-CN/)。

## [Unreleased]

## [0.2.21] - 2026-06-30

### Added — 新增

- **网页过滤性能诊断页面**：
  - 在“网页过滤管理”中新增过滤性能诊断卡片，可查看规则编译耗时、启用规则数、判定次数、缓存命中率、候选规则评估、正则评估、元素隐藏/scriptlet 注入调用与生成资源体积。
  - 支持展示 `FilterPerfSnapshot` 的 P50/P95/P99/Max 分位数、索引结构摘要、最近过滤事件摘要，并预留自动采样刷新等后续诊断配置入口。
  - 新增“复制完整诊断信息”，方便真机实测后直接复制 build/runtime/sample/index/event 指标用于继续定位广告过滤性能瓶颈。

- **悬浮球快速收藏到白名单**：
  - 在网页悬浮球控制面板中新增“收藏”按钮，当前页面为 `http/https` 网页时可一键加入应用白名单。
  - 收藏时会标准化当前 URL 并按 URL 去重；已存在但被禁用的白名单条目会自动重新启用，避免重复添加和后台列表混乱。

## [0.2.20] - 2026-06-30

### Changed — 变更

- **广告过滤热路径二次性能优化 (Adblock Hot Path Optimization)**：
  - 将过滤规则索引升级为 domain suffix + URL gram 候选检索，修复单 token 精确匹配对 `adsbygoogle.js`、`banner123ad.js`、`/assets/ads/...` 等真实广告 URL 的候选覆盖不足问题，并保持候选按规则优先级稳定评估。
  - 新增 `removeparam` 专用索引，导航 URL 参数清理不再借用全量 blocking 候选扫描。
  - 为 cosmetic CSS 与 scriptlet 生成建立域名后缀索引和 host 级缓存，减少页面注入阶段对全部规则的重复扫描。
  - 优化 `shouldInterceptRequest` 外层开销：复用 WebResourceRequest host/lowercase 结果，减少重复 URI 解析；对广告密集页的拦截日志与过滤事件做限流，避免日志/广播拖慢加载。
  - 去除过滤引擎构建阶段为了 `badfilter` 产生的二次规则解析。

### Added — 新增

- **过滤性能可观测性与差分回归测试**：
  - 新增 `FilterPerfSnapshot`，可采样 build 耗时、cache 命中、候选数量、regex 求值、`decide()` p50/p95/p99、cosmetic/scriptlet 生成耗时等指标，方便真机验证下一步是否需要编译快照或 native 引擎。
  - 增加线性参考引擎差分测试、确定性 10,000 请求生成用例、嵌入式 literal 回归用例和 cosmetic/scriptlet 域名索引测试，保证索引优化不牺牲过滤正确性。

## [0.2.19] - 2026-06-29

### Changed — 变更

- **广告拦截匹配引擎性能优化 (Adblock Filter Engine Performance Optimization)**：
  - **Token 倒排索引查找 (TokenIndex)**：重构了 `FilterEngine` 底层规则匹配算法，使用关键字倒排索引替代了原本每笔请求执行 $O(N)$ 线性扫描的方法，将网络请求决策复杂度降低为 $O(K)$（规则候选集从二十几万条瞬间降至十几条），匹配速度提升数千倍。
  - **规则字段预计算与常量化**：为 `CompiledRule` 增加了 `patternLower`、`anchorHost`、`anchorPath` 与最佳 `bestToken` 提取等预计算机制，完全消除了高频匹配过程中的临时字符串构造与 lowercase 开销。
  - **并发加锁优化与缓存扩容**：将限制为 512 条的同步 `decisionCache` 移除，替换为线程安全的 `ConcurrentHashMap`，容量扩大至 4096 条。配合索引去除了 `decide()` 方法上的 `@Synchronized` 全局锁，极大地释放了并发网络拦截的吞吐性能。


### Added — 新增

- **多标签内存缓存机制 (TabMemoryCache)**：
  - 引入了 `:webview` 进程内长期存活的静态标签内存缓存 `TabMemoryCache`，存储多 Tab 结构及各个 WebView 的 `savedState` 二进制历史 Bundle 快照。
  - 回到主页时，直接 finish 销毁 `WebViewActivity`，将所有状态交由内存缓存进行托管，彻底规避了复杂的跨 Task 栈切换。

### Fixed — 修复

- **儿童模式 (Kiosk/Lock Task) 下浏览器显示不出来 Bug 修复**：
  - 彻底撤销了原本导致创建独立任务栈的 `singleInstance` 和 `taskAffinity` 设置，让 `WebViewActivity` 的 `launchMode` 回归最安全的默认 `standard`。
  - 移除了启动 Intent 上的 `FLAG_ACTIVITY_NEW_TASK`。这使得浏览器完美运行于主任务栈顶，彻底解决了此前由于独立栈违反 Lock Task 系统限制而在儿童模式下“直接显示不出来”的严重 Bug。

## [0.2.17] - 2026-06-28

### Added — 新增

- **多进程物理磁盘文件同步**：
  - 将多标签共享快照持久化媒介由 `SharedPreferences` 改写为直接读写 App 专属 `cache/tabs_snapshot.json` 磁盘物理文件，避开了多进程缓存刷新不同步问题，保证回到首屏后悬浮球列表展示绝对实时、无延迟。

### Changed — 变更

- **网页 Home 退回自动隐藏面板**：
  - 点击悬浮面板的小房子 Home 按钮返回主屏前，强制执行收起面板，这样下次白名单进入或切回网页时悬浮球默认为整洁的隐藏吸附状态，不挡页面。

### Fixed — 修复

- **返回手势销毁所有标签 Bug 修复 (手势仅关闭单 Tab)**：
  - 彻底改写手势返回及网页退出流程。返回手势在没有网页历史时，只执行 `closeTab` **仅关闭当前这一活跃 Tab** 并自动 switchToTab 另一个活跃标签；只有关闭完最后一个标签时，才会激活安全退出验证销毁 Activity，修复了手势返回直接全部闪退关闭的严重 Bug。

## [0.2.16] - 2026-06-28

### Fixed — 修复

- **Home 键切回首页清空标签 Bug 修复**：
  - 彻底将 `WebViewActivity` 从主任务栈隔离，为其配置专有的 `android:taskAffinity` 与 `singleInstance` 单实例启动模式。
  - 在网页切换回首页、以及首页唤起/切换网页标签时，均显式附加 `FLAG_ACTIVITY_NEW_TASK` 标志，实现纯粹的任务栈前后台置换，彻底解决了原本由于 `singleTask` Clear-Top 机制而导致 `WebViewActivity` 被强制 finish 并清空打开标签页的严重 Bug。

## [0.2.15] - 2026-06-28

### Added — 新增

- **白名单分类选择弹窗**：
  - 点击悬浮面板 `+` 添加按钮时，弹出沉浸式 Dialog，支持一键“新建空白标签页”，或从已启用的白名单应用（按游戏 🎮、视频 📺、绘本 📚、学习 ✍️、工具 🧰、其他 ⚙️ 分类排布的卡片网格）中快捷点选新开标签页。
- **跨进程多标签同步机制**：
  - 首屏（`MainActivity`）与网页（`WebViewActivity`）通过持久化快照共享标签状态，支持在首屏展开悬浮面板查看、新建、切换或关闭当前标签网页，实现首屏与网页间无缝切换。
  - 将 `WebViewActivity` 设置为 `singleTask` 重用模式，保证跨进程唤醒和新开标签的高效与单例性。
- **Home 房子按键**：
  - 悬浮面板动作条新增 Home 按键，点击后快速切回应用首屏，同时后台的网页标签页保持挂起状态不被销毁。

### Changed — 变更

- **标签列表垂直化排布**：
  - 面板标签列表由水平滑动改为垂直单行平铺，高度限制在 4.5 个标签，超出时自动剪裁并允许滚动，极大提升小屏/横屏下的操作性。
- **悬浮控制器交互微调**：
  - **首屏禁用态**：当在应用首屏展开悬浮控制器时，前进、后退、刷新、强刷等网页操作自动呈禁用状态；新建与打开白名单保持可用。
  - **吸附时间缩短**：悬浮球无操作隐藏吸附边缘的延迟由 2.8 秒缩短至 `2.0` 秒。
  - **强刷按钮低调化**：强刷按钮改为 `NORMAL` 低调样式，避免引起普通用户注意。

### Added — 新增

- **全新多标签页 (Multi-tab) 管理机制**：
  - 在悬浮控制器面板中新增了精致的“标签页管理” Section。支持新建标签 (`+`)、切换标签、以及一键关闭 (`x`) 各自独立的标签页。
  - **window.open 新标签页打开**：重写了 `onCreateWindow` 逻辑，使得网页端请求拉起的新页面，直接作为新 Tab 在浏览器中独立开启，与标准桌面端/移动端浏览器体验对齐。

### Changed — 变更

- **不活跃标签冻结与性能优化 (LRU Suspension)**：
  - 限制后台未冻结的 WebView 实例最多为 `1` 个（与当前活动 Tab 共计最多 `2` 个 WebView 实例），以此在大内存开销的 Web 页面下防止 OOM 奔溃。
  - 采用 LRU 缓存策略，对被压入后台且超出数量阈值的不活跃标签页，自动调用 `WebView.saveState(Bundle)` 并将其 Native 内存销毁挂起；当用户重新切回点击激活时，通过 `WebView.restoreState(Bundle)` 自动解冻复原，不遗失网页历史和滚动状态。

## [0.2.13] - 2026-06-28

### Added — 新增

- **支持 data: 协议文件下载**：
  - 对非 http/https 的 `data:` 协议下载（例如前端生成的 base64 文件流）提供了原生 Base64 解码并保存为本地文件的支持，保存位置为系统 `Downloads` 文件夹，以便与普通浏览器对齐。

### Changed — 变更

- **正常模式限制放开与体验对齐**：
  - **允许非 Web 链接拉起外部应用**：在正常模式下，当触发非 `http/https` 协议（如自定义 scheme、支付拉起、迅雷下载等）时，不予直接拦截，而是尝试隐式 Intent 唤起系统中的外部应用程序，该能用的都能用。
  - **取消网页退出家长验证**：在正常模式下，退出非白名单内的任何自定义网页（通过悬浮球打开的 URL）时不再弹出 PIN 码或计算题的家长验证，直接关闭并退出，消除不必要的限制。

## [0.2.12] - 2026-06-28

### Changed — 变更

- **管理锁默认透明度下调**：
  - 将桌面上“管理锁”图标的默认透明度从 `20%` 降低到 `10%`，进一步柔化其视觉效果，以最大限度减少它对孩子日常操作和网页内容的干扰。

### Fixed — 修复

- **刘海屏/挖孔屏全屏显示优化**：
  - 修复刘海屏/挖孔屏手机在全屏隐藏状态栏时，屏幕顶部出现黑色一横条（Notch 留白）的问题。通过将 `layoutInDisplayCutoutMode` 设为 `SHORT_EDGES`，使网页内容完美铺满刘海的左右两侧区，实现真正独占全屏。在返回首页时，仍能够安全恢复状态栏的正常展示。

## [0.2.11] - 2026-06-28

### Added — 新增

- **调试模式默认开启悬浮球**：
  - 在配置中心将应用模式切为“调试模式”（DEBUG）时，悬浮球菜单默认自动启用，以方便开发调试。

### Changed — 变更

- **强刷按钮防误触优化**：
  - 优化了悬浮球菜单的“强刷”按钮交互。普通点击“强刷”将弹出轻量级 Toast 提示 `"长按“强刷”以执行强制刷新"`；长按该按钮时才真正触发强制刷新，以防用户在日常浏览中误触引发的额外加载开销。

### Fixed — 修复

- **浏览器界面隐藏系统状态栏**：
  - 默认浏览器 WebView 展示页面（`WebViewActivity`）彻底隐藏顶部的系统状态栏，使页面头部内容不再与状态栏字迹/电量图标重叠挡住，实现网页完全独占屏幕空间。返回首页时自动恢复状态栏的正常显示。

## [0.2.10] - 2026-06-25

### Changed — 变更

- **儿童过滤默认规则精炼与网页加载性能优化**：
  - **默认规则精简**：修改了内置过滤订阅的默认启用状态。在“标准儿童过滤”（儿童模式）下，默认不再强制勾选 `EasyPrivacy`（通用隐私）、`AdGuard Chinese filter`（中文广告）和 `AdGuard Mobile Ads filter`（移动广告）这三个规则库极为庞大的外部订阅，仅保留主广告过滤规则 `EasyList` 和轻量低开销的 `Child Kiosk 本地补充规则` 默认勾选。
  - **性能跃升**：精简后直接裁剪了移动端数万条不必要的过滤匹配逻辑，大幅缩减了 WebView 首次打开网页时过滤引擎的编译和匹配匹配时间，有效减少了 GC 抖动和开屏加载延迟。其他高级过滤规则已交由家长根据实际需要由控制后台手动按需启用。


### Fixed — 修复

- **地址栏软键盘遮挡优化**：
  - 在悬浮面板中通过动态监听 WindowInsets IME 软键盘高度变化，重构了自适应高度压扁收缩与 Y 轴定位避让算法，同时给 Activity 增加了 `adjustResize` 配合，彻底解决了用户输入网址时输入法遮挡地址输入框的问题，确保在横屏和竖屏下字迹完全可见。
- **WebView 跨线程方法调用闪退修复**：
  - 修复了在 WebView 的后台网络资源加载线程（`shouldInterceptRequest`）中，违规直接物理访问 `view?.url` (即调用非线程安全的 `WebView.getUrl()`) 引发的致命线程检查异常崩溃。
  - 重构为：在 `WebViewActivity` 内部引入 `AtomicReference` 并在 UI 主线程的 `onPageStarted` 回调中将加载的顶级 URL 安全写入该并发缓存，在后台拦截时读取该缓存共享；在 `WebViewPool` 预加载拦截中，直接闭包引用创建时预先绑定的 `url` 常量，彻底根除了开启网页过滤和儿童模式时的闪退大 Bug。


### Fixed — 修复

- **网页过滤闪退彻底修复与进程安全加固**：
  - **网络拦截协议优化**：将资源拦截响应的 HTTP 状态码从 `204 No Content` 更改为兼容安全的 `200 OK`，并对图片（`IMAGE`）资源返回 1x1 像素透明 GIF 字节流，对其他拦截资源返回空字节流。此举彻底根治了 Chromium 底层因解析“204 状态码带非空实体”引发的 Native 致命断言崩溃。
  - **多进程日志写入解耦**：当独立进程 `:webview` 中触发资源拦截时，不再直接使用 `FilterRepository` 写磁盘（避免引发多进程并发下的 `AtomicFile` 写入异常或 SELinux 权限崩溃），改为发送以本包名为受众的本地跨进程广播，并由主进程唯一的 `FilterEventReceiver` 进行统一无锁的文件写入与内存缓冲，杜绝了多进程写冲突和任务堆积导致的 OOM。
  - **渲染器进程崩溃防护**：在 `WebViewClient` 中重写了 `onRenderProcessGone` 容错机制，在渲染进程被系统杀掉或异常崩溃时，安全卸载旧实例并友好提示，主动告知 Android 系统宿主应用已妥善处理，完美拦截了渲染器挂掉带崩主应用的闪退现象。


### Added — 新增

- **全局悬浮球及网页导航支持**：
  - 将悬浮球重构为全局功能，在主页面桌面（未打开网页时）也可以常驻显示悬浮球。
  - 没打开网页时悬浮面板的地址栏默认为空，用户可输入网址并按 Go 键直接通过 `WebViewActivity` 拉起网页并载入。

### Changed — 变更

- **悬浮球图标与边距精细化重塑**：
  - 将网页控制悬浮球图标更换为更契合探索/浏览器场景的 Explore (指南针) 探索风格 SVG 矢量图，弃用原本的汉堡菜单图标。
  - 将 `bubbleButton` 的缩放模式修改为 `CENTER_INSIDE`，并将内边距设为 `10dp`，彻底改善原本图标占满圆圈无边距的问题，视觉体验更加精致。
- **导航后自动折叠**：
  - 在悬浮球地址栏输入 URL 点击 Go 后，悬浮面板将自动折叠，避免对新网页产生遮挡。

### Fixed — 修复

- **网页过滤闪退修复与性能优化**：
  - 修复了开启网页过滤（轻量过滤与标准过滤）时，一旦打开网页就会立马闪退的致命 Bug。在 AdBlock 拦截层增加了协议与超长防御校验，仅拦截 `http` 与 `https` 资源，直接放行 `data:` 等内联资源。
  - 在 `FilterRequestContext` 构造中对 URL 进行一次性 lowercase 转换与缓存，并在匹配时共享，彻底根治在通配符规则循环匹配大字符串时产生的 OOM (内存溢出) 与 StackOverflowError (栈溢出) 崩溃。

## [0.2.6] - 2026-06-25

### Changed — 变更

- **屏幕显示方向自适应逻辑调整**：
  - 将默认的“自适应”显示方向（`ORIENTATION_AUTO`）底层对应的 Android Orientation Flag 由强力感知重力的 `SCREEN_ORIENTATION_SENSOR` 修改为跟随系统全局设置的 `SCREEN_ORIENTATION_UNSPECIFIED`。
  - 调整后自适应方向将完全跟随系统：若系统开启了“自动旋转”则自适应旋转，若系统锁死竖屏则也会锁死竖屏，防止在系统已禁用自动旋转时 App 仍强行翻转。
- **配置中心弹窗体验重塑**：
  - 配置中心 Dialog 样式深度美化：限制 Dialog 最大宽度、增加精致设置图标与副标题。
  - 按钮尺寸与排版重塑：弃用儿童级 72dp 巨大按钮，改为 56dp 亲和圆角 Button，并加入轻微缩放动画与 Haptic 触觉反馈。
  - 自适应方向支持：横屏状态下两个按钮自动呈 Row 并排展示以优化纵向空间，竖屏下保持 Column 竖排。
- **“返回系统桌面”按钮样式优化**：
  - 将配置中心弹窗与配置后台底部的“返回系统桌面”大红警示按钮改为 Material 3 的 `errorContainer`（淡粉背景）与 `onErrorContainer`（深红文字）柔和色调，并增设 Home 图标，减少刺眼晃眼感。

### Fixed — 修复

- **正常模式首屏状态栏避让优化**：
  - 修复正常模式且显示状态栏时，首屏大标题和控制按钮被状态栏遮挡一部分的问题，通过 WindowInsets 动态避让状态栏。
- **白名单应用状态栏背景透明化**：
  - 修复正常模式打开白名单应用网页时，状态栏区域强制填充白色背景的问题，移除了 WebView 容器在正常模式下的状态栏 Padding，并配合透明状态栏，使得状态栏底色与网页背景完美融合。

## [0.2.5] - 2026-06-24

### Added — 新增

- **默认白名单预置站点扩充**：
  - 新增 9 个游戏类默认白名单站点：Abeto Messenger、Astrocade、Y8 Games、Poki、在线小霸王、WGame80、Neal.fun、CrazyGames、Sandspiel。
  - 新增 6 个实用工具/趣味网站类默认白名单站点：ANZZ Map、Drawnix、Excalidraw、tldraw、Draw.Chat、HTwins。
  - 新增“实用工具”分类，主屏分类、后台分类筛选、订阅格式解析和订阅文档均已支持该分类。
  - 新增预置站点默认全部禁用，既能作为可选模板提供给家长，也不会在升级后自动放开访问。

### Changed — 变更

- **站点图标自动识别增强**：
  - 后台编辑应用时优先尝试读取站点根路径 `favicon.png`。
  - 若未命中，会继续解析页面声明的 `icon` / `shortcut icon` / `apple-touch-icon` 等候选图标，并按格式、尺寸和声明类型选择更合适的图标。
  - 最后再回退到 `apple-touch-icon.png` 与 `favicon.ico`，提升新建或编辑白名单站点时的图标准确性。
  - 仅在图标地址仍是自动填充结果时继续更新，避免覆盖家长手动填写的图标 URL。

### Fixed — 修复

- **正常模式首屏状态栏避让修复**：
  - 修复正常模式下网页首屏内容仍可能被状态栏覆盖的问题。
  - 横屏进入网页时恢复无状态栏显示，减少横屏高度被系统栏占用。

## [0.2.4] - 2026-06-23

### Fixed — 修复

- **正式包名与源码路径修正**：
  - 将 Android `namespace` 与 `applicationId` 修正为 `site.anzz.childkiosk`。
  - 将源码与测试目录迁移到 `src/*/java/site/anzz/childkiosk`。
  - 同步更新 README、后台 ADB 指令和 WebView 调试文档，避免构建产物、运行进程或使用说明继续出现示例包名。
- **正常模式系统栏透明与内容避让修复**：
  - 修复 WebView 主题残留全屏 flag 导致正常模式下网页内容被状态栏遮挡的问题。
  - 正常模式下状态栏和导航栏改为透明背景，并关闭 Android 10+ 系统栏对比度遮罩，避免白色状态栏突兀覆盖网页。
  - 进入正常模式和受控系统信息模式时会显式清理沉浸式/全屏旧状态，确保内容按系统栏 inset 正常布局。

## [0.2.3] - 2026-06-23

### Added — 新增

- **网页强制刷新能力**：
  - 悬浮球浏览控制面板新增“强刷”按钮，用于解决部分网页被 HTTP 缓存、Service Worker 或浏览器缓存强缓存后无法及时更新的问题。
  - 默认强制刷新只清理 WebView 页面缓存，并通过 `Cache-Control: no-cache, no-store, must-revalidate` 与 `Pragma: no-cache` 请求头重新加载当前页面。
  - 强制刷新弹窗新增可选项，可由用户决定是否同时清理当前网站 Cookie、WebStorage、`localStorage`、`sessionStorage`、CacheStorage、IndexedDB 和 Service Worker 注册等登录/本地数据。

### Fixed — 修复

- **正常模式系统状态栏显示修复**：
  - 修复正常模式进入网页后仍被 `WebViewActivity` 强制切回沉浸式全屏的问题。
  - 正常模式现在与普通 App 一样显示系统状态栏/导航栏，可正常看到时间、电量、网络图标等头部系统信息。
  - WebView 独立 `:webview` 进程通过启动 Intent 接收系统栏模式快照，不依赖跨进程 SharedPreferences 即时读取。
- **网页悬浮球默认遮挡优化**：
  - 悬浮球进入网页后默认直接吸附到屏幕边缘并保持半隐藏状态，减少对网页内容和儿童操作的干扰。
  - 修复屏幕旋转后原本处于半隐藏状态的悬浮球自动完整露出的问题；旋转或窗口边距变化后会按当前屏幕尺寸重新吸附半隐藏。
  - 恢复悬浮球贴边安全边距，避免按钮过度贴边导致触摸不稳定。
- **后台白名单与悬浮面板细节修复**：
  - 优化后台白名单开关尺寸和 URL 输入区域高度。
  - 调整悬浮面板输入选中行为，减少误选中和布局压缩问题。

## [0.2.2] - 2026-06-23

### Changed — 变更

- **网页浏览悬浮球细节深度优化**：
  - 物理点击区域由 `54dp` 降为更精致的 `44dp`，可视白圆盘直径进一步降为 `42dp`，背景缩进减小为 `1dp`，使得点击手感与精细视觉达到最佳平衡。
  - 修复“悬浮球移动无法靠紧屏幕最边缘”的缺陷。解除 X 轴方向的 12dp 贴边安全限制（降为 0dp），使得悬浮球可以紧紧贴合屏幕左/右边缘，极大提升可用视界。
  - 调整面板 URL 地址输入框比例。增加垂直 padding 8dp 且配合垂直居中，在 LayoutParams 中指定高度为 `40dp`，实现 2.0 倍于文字高度的完美黄金视觉比例。
- **应用白名单列表排版重塑**：
  - 针对原有“操作按钮与应用信息挤作一团”的问题，对 `WebAppCard` 重新设计为“左侧图标，右侧上下分栏”的桌面卡片级排版。
  - 将应用名称与网址置于上部独占全部宽度，将分类标签、订阅状态与操作按钮（使能开关、编辑、删除）收纳于下部，从根源上消除排版折行与重叠现象。
  - 收缩内边距至 `12dp`，去除累赘多余的左右边距，提高界面现代感。

## [0.2.1] - 2026-06-23

### Changed — 变更

- **主网格紧凑度优化**：
  - 调整竖屏下应用图标的列数分配，小图标为 4 列，中图标为 3 列，大图标为 2 列。
  - 全面压缩图标间距，减少多余留白以增强界面高档感。
  - 主屏大标题字号从 `32sp` 降为 `22sp`，使头部排版更具比例美感。
  - 分类导航标签（Tabs）字体缩小至 `13sp`，padding 调整为水平 `12dp` / 垂直 `6dp`，间距降至 `8dp`，消除老人机臃肿感。
- **悬浮球体积二次缩小与低调配色**：
  - 物理点击触控大小（悬浮球半透明遮挡区域）由 `72dp` 降为 `54dp`，可视白圆盘直径降为 `44dp`。
  - 悬浮球配色重新设计为高级低调的**纯白底色 + 灰色图标**，可完美融入任何网页环境。
- **网页控制面板精致化**：
  - 面板内 URL 地址栏高度从 `56dp` 降至更紧凑的 `40dp`，圆角降至 `12dp`。
  - “关闭”和“访问”按钮高度由 `56dp` 统一降至 `40dp`，圆角降至 `12dp`。
  - 底部功能键的间距和高宽从 `64x68` 调整至 `50x56`，图标尺寸由 `28dp` 降为 `20dp`，文字大小调至 `10f`。

## [0.2.0] - 2026-06-23

### Added — 新增

- **首屏壁纸与配色配置**：
  - 新增“首屏壁纸与配色配置”功能，提供 10 种精美的高级纯色和渐变配置选项（如温暖明黄、莫兰迪蓝、森林绿意、薰衣草紫、极简纯黑等）。
  - 主屏标题、标签页及应用名前景色支持根据壁纸明暗属性（isDark）自动完成无缝自适应切换。
  - 在“界面与显示配置”二级子页面中新增交互精美的滑动色块配置 Card。

### Changed — 变更

- **网站白名单手机桌面化重构**：
  - 剥离主屏白名单应用卡片的白色背景底色与阴影卡片框架，重构为透明的手机桌面 App 风格（上面图标，下面名称）。
  - 针对默认预置 App，根据应用 ID 自动应用 5 种精美莫兰迪色系渐变底色，搭配白色图标和描边边框。
  - 应用图标框的尺寸在小、中、大三种模式下进行了更合理的比例计算。
- **悬浮球视觉缩减与灵动动效**：
  - 悬浮球的视觉直径由 `72dp` 降至更精致小巧的 `52dp`，同时在物理层利用 InsetDrawable 维持 `72dp` 宽大的物理触控热区。
  - 悬浮球采用全新的青绿微渐变背景与半透明发光白色边框，提升视觉立体感。
  - 新增边缘半隐藏 Alpha 淡出动效（空闲 2.8 秒后自动淡出至 `0.4f`，拖拽及交互时立刻恢复 `1.0f` 不透明）。
- **快速模式选择配置二级菜单化**：
  - 从配置首页中移除直接铺开的 `QuickModeCard`。
  - 在配置中心列表顶部新增“快速切换模式”列表项，其 summary 副标题在未点击进入前即可直观显示当前的模式名称。
  - 点击“快速切换模式”进入全新的 `"QUICK_MODE"` 二级配置菜单以执行模式的一键切换。

## [0.1.2] - 2026-06-16

### Added — 新增

- **WebView 内核环境诊断**：
  - 配置后台新增“WebView 内核环境”入口，显示当前 WebView provider 包名、版本、versionCode、Chromium 版本、Android 版本、设备型号、进程和渲染路径。
  - 新增 WebView 运行环境状态分级，区分正常、偏旧、高风险和无法识别，帮助管理员判断老旧系统 WebView 的兼容性风险。
  - 新增关键 WebView 配置快照展示，包括 Chrome Inspect、手机浏览器 UA、自定义 UA、第三方 Cookie、混合内容、热备、预加载和顶部进度条。
- **升级与排查入口**：
  - WebView 内核环境页支持重新检测、复制完整诊断信息、打开 Android System WebView 更新页、打开 Chrome 更新页和打开系统 WebView 设置。
  - 新增 `ChildKioskWebView` provider diagnostics 日志，便于结合 logcat 排查 WebView provider、Chromium 版本和进程状态。

### Documentation — 文档

- **WebView provider 管理需求**：
  - 新增 `docs/webview_provider_management_requirements.md`，明确普通 App 不能把私有目录中的 WebView APK/so 强制替换为 `android.webkit.WebView` provider。
  - 细化轻量 P0 诊断与升级引导、P1 下载/安装辅助和 P2 替代渲染器预研的边界。

## [0.1.1] - 2026-06-16

### Added — 新增

- **Adblock 规则生态兼容过滤**：
  - 新增主流 Adblock 文本规则解析与过滤引擎，支持 ABP/EasyList、uBlock Origin 静态规则和 AdGuard 常用语法子集。
  - 支持域名锚定、通配符、分隔符、正则、例外规则、资源类型、三方/一方、`domain=`、`badfilter`、`removeparam` 等高频网络过滤能力。
  - 新增 EasyList、EasyPrivacy、AdGuard Chinese、AdGuard Mobile Ads、EasyList China、AdGuard Annoyances 等内置订阅目录和儿童过滤预设。
- **自定义规则、订阅和站点例外**：
  - 配置后台新增网页过滤总控、过滤强度、订阅列表、自定义 HTTPS 订阅、自定义规则输入和规则解析统计。
  - 支持站点级关闭网络过滤、元素隐藏、scriptlet 或临时放行，用于处理误伤站点。
  - 新增最近过滤事件列表，展示被拦截 URL、资源类型、命中规则和来源，方便管理员排查误伤。
- **页面去干扰能力**：
  - 支持标准元素隐藏规则、站点限定隐藏和隐藏例外，将匹配 CSS 注入到当前页面。
  - 支持安全 allowlist scriptlet，包括阻止部分弹窗和常见反拦截干扰逻辑。
  - 支持弹窗过滤和主框架导航追踪参数清理，减少广告跳转、污染参数和干扰窗口。

### Changed — 变更

- **过滤运行时一致性增强**：
  - 打开 WebView 时通过运行时配置快照传递过滤设置和订阅元数据，避免独立 `:webview` 进程读取过期 SharedPreferences。
  - WebView 预加载池按运行时配置 key 区分实例，过滤配置变化后不会复用旧配置的预加载 WebView。
- **性能与可用性保护**：
  - WebView 请求拦截、跳转清洗、元素隐藏注入、scriptlet 注入和弹窗判断只读取已预热的过滤引擎缓存。
  - 过滤引擎在打开网页前于 IO 线程预热，避免 `shouldInterceptRequest` 热路径编译规则、读取订阅文件或访问数据库。
  - 过滤事件改为后台串行写入原子文件，减少拦截线程上的同步磁盘读写。
  - 新增过滤引擎缓存语义测试，确保未预热时不会在请求路径隐式构建引擎。

### Documentation — 文档

- **过滤能力需求文档**：
  - 新增 `docs/adblock_filtering_requirements.md`，记录规则生态兼容范围、默认订阅策略、性能边界、误伤处理、跨进程配置快照和验收标准。

## [0.1.0] - 2026-06-16

### Added — 新增

- **快速模式预设**：
  - 配置后台新增“正常模式 / 儿童模式 / 调试模式 / 自定义模式”，点击模式即可批量应用对应配置，减少逐项配置成本。
  - 正常模式成为默认模式，默认不启用认证校验、软锁和网页/系统限制，右上角管理入口可单击打开。
  - 儿童模式默认启用认证、软锁、隐藏管理入口和系统/WebView 限制。
  - 调试模式默认放开限制，并开启 Chrome Inspect 与网页内置调试面板。
  - 自定义模式保留当前细项配置；手动调整锁定、认证、沙箱、调试、悬浮球等模式控制项时会自动进入自定义模式。
- **网页悬浮球操作入口**：
  - 新增原生 `FrameLayout` 内悬浮球组件，支持拖拽、左右边缘吸附、空闲半隐藏和展开操作面板。
  - 悬浮球面板提供 URL 输入/访问、当前 URL 展示、后退、前进、刷新和停止加载能力。
  - 悬浮球动作模型支持后续扩展更多分组和操作项。
- **正常模式浏览控制接入**：
  - 正常模式默认开启网页悬浮球，进入网站后可通过悬浮球完成基础浏览器导航操作。
  - 配置后台新增“网页悬浮球操作入口”开关；管理员可在自定义模式下决定其它模式是否显示悬浮球。
  - WebView 独立进程通过启动 Intent 接收悬浮球配置快照，不依赖跨进程 SharedPreferences 即时读取。

### Changed — 变更

- **模式预设边界收敛**：
  - 屏幕方向、图标大小、主页标题、白名单、缓存/预加载等常用非限制选项不会被模式切换重置。
  - 语音助手入口和未知来源安装限制改为显式配置项，不再作为隐性硬编码规则。

### Documentation — 文档

- **快速模式需求文档**：
  - 新增 `docs/quick_mode_requirements.md`，记录模式目标、预设矩阵、非目标选项和验收标准。
- **悬浮球需求文档**：
  - 新增/完善 `docs/floating_browser_controls_requirements.md`，记录悬浮球组件边界、交互要求、状态回调和正常模式接入计划。

## [0.0.28] - 2026-06-14

### Fixed — 修复

- **首屏认证弹窗横屏适配**：
  - 首屏右上角管理锁触发的 PIN/口算认证弹窗增加高度约束和纵向滚动，固定横屏或短高度设备下按钮不再被裁切。
  - 首屏 PIN 和口算认证均改为内置数字键盘，避免系统软键盘挤占横屏空间导致无法完整操作。

### Changed — 变更

- **运行时文案中性化**：
  - 应用内可见文案弱化“家长”表述，统一改为“认证”“配置后台”“管理员”等更中性的说法。
  - 菜单文案调整：“返回乐园”改为“返回空间”，“进入系统白名单及时间配置后台”改为“进入配置后台”，“退出并安全解锁（返回系统桌面）”改为“返回系统桌面”。

### Documentation — 文档

- **新增 Agent 公共开发规则**：
  - 新增仓库级 `AGENTS.md`，记录横竖屏 UI 可达性、配置项生效反馈、WebView 独立进程配置快照、Android/Kiosk/WebView 生命周期与发布纪律等公共开发规范。
  - 明确不应在项目规则中写入个人机器路径、本地工具别名、凭据或私有环境信息。

## [0.0.27] - 2026-06-14

### Fixed — 修复

- **横屏家长验证显示不完整**：
  - WebView 退出/延时验证中的 PIN 和动态口算验证均改为 App 内置数字键盘，不再依赖系统软键盘。
  - 验证内容放入可滚动容器，固定横屏、小高度设备下也能完整访问数字键、确认和取消按钮。
- **WebView 设置变更后不立即生效**：
  - 打开网站时从主页进程生成 WebView 运行时配置快照，并通过 Intent 传给独立 `:webview` 进程。
  - “退出网站时需要验证”、下载限制、跳转限制、广告拦截、SSL 检查、媒体权限、地理位置、User-Agent、调试注入等 WebView 相关选项不再依赖 WebView 进程里的旧 SharedPreferences 缓存。
  - WebView 启动时显式传递当前横/竖屏设置，固定方向下新打开的网站会按最新设置显示。

### Changed — 变更

- **管理锁图标默认透明度调整**：
  - 新安装默认透明度从 40% 降为 20%，降低主页右上角锁图标对儿童使用界面的干扰。

## [0.0.26] - 2026-06-14

### Changed — 变更

- **WebView 原生承载固化**：
  - 根据 v0.0.25 实机 AB 结果，正式采用 `FrameLayout + WebView` 承载真实网页，不再在配置界面暴露“标准 Compose / 轻量原生”切换。
  - 移除 WebView 页面中的 Compose `AndroidView` 宿主和全屏 Loading overlay，保留可选顶部细进度条。
  - 清理 AB 诊断临时开关：视觉提交回调关闭 Loading、页面激活事件补发、延迟多轮脚本注入。
  - 移除高分屏渲染兼容 CSS/JS 注入和配置项，避免禁用网页动画、改写第三方页面样式。
  - 固定 `offscreenPreRaster=false`，避免旧保存项继续放大高 DPR WebView tile 压力。
  - 新增 `docs/webview_development_guidelines.md`，记录 WebView 开发基线、禁止项和排查日志。
- **首页分类导航样式调整**：
  - 首页分类 sticky 导航去掉独立渐变背景，改为透明承接页面背景，避免和底部背景产生割裂。
- **网页沙箱默认基线调整**：
  - “网页广告与弹窗过滤”默认关闭，降低误拦截脚本、样式、字体和统计资源的概率。
  - “仅允许白名单域名跳转”默认关闭，默认允许正常跨域导航、CDN 和 OAuth 跳转。
  - 增加一次性基线迁移，旧版本升级后会把这两个开关重置为关闭。

## [0.0.25] - 2026-06-14

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

[Unreleased]: https://github.com/xxxily/child-kiosk-browser/compare/v0.0.28...HEAD
[0.0.28]: https://github.com/xxxily/child-kiosk-browser/compare/v0.0.27...v0.0.28
[0.0.27]: https://github.com/xxxily/child-kiosk-browser/compare/v0.0.26...v0.0.27
[0.0.26]: https://github.com/xxxily/child-kiosk-browser/compare/v0.0.25...v0.0.26
[0.0.25]: https://github.com/xxxily/child-kiosk-browser/compare/v0.0.24...v0.0.25
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
