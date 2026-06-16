# 广告、弹窗与污染过滤能力增强需求文档

## 背景

当前应用已经有“网页广告与弹窗过滤”开关，但实现仍偏基础：`AdBlocker` 主要依赖一组内置广告/统计域名关键字，在 `WebViewClient.shouldInterceptRequest` 中命中后返回空响应。这个能力能挡住部分常见广告请求，但无法承接主流过滤生态，面对中文站点、移动广告、弹窗遮罩、反广告拦截提示、追踪参数和快速变化的广告域名时维护成本会越来越高。

儿童应用的浏览场景更需要“抗干扰、去污染、可解释、可恢复”的过滤能力。优化方向不能自造一套孤立规则格式，而应兼容市面上已经长期维护的 Adblock 规则生态，让家长、维护者和社区现有规则都可以复用。

## 目标

- 支持导入、订阅和更新主流 Adblock 规则集，优先兼容 ABP/EasyList、uBlock Origin 静态过滤语法和 AdGuard 过滤语法。
- 支持家长自定义过滤规则、例外规则和站点级临时放行，规则格式仍使用主流 Adblock 语法。
- 内置一组适合儿童浏览器的默认订阅目录和默认启用组合，覆盖广告、追踪、中文网页、移动广告和常见弹窗干扰。
- 在保持过滤强度的同时控制误伤，尤其不能破坏儿童白名单站点、教育站点、小游戏、视频播放和登录/授权流程的基本可用性。
- 过滤配置必须明确跨进程应用方式。`WebViewActivity` 运行在 `:webview` 进程，不能依赖该进程长期读取到最新 `SharedPreferences`。
- 保持真实网页承载路径不变：生产网页仍由 `WebViewActivity -> FrameLayout -> WebView` 渲染，不引入 Compose `AndroidView` 承载真实网页。

## 非目标

- 不自创私有规则语法。可以扩展内部数据模型，但用户和订阅入口只接受主流 Adblock/hosts 格式。
- 不做系统级 VPN、代理、root hosts 或全设备 DNS 拦截。本阶段只管理应用内 WebView 流量和页面 DOM。
- 不默认执行订阅源提供的任意 JavaScript。脚本类过滤只能映射到本地审计过的安全 scriptlet 实现。
- 不默认加入 Acceptable Ads 或广告白名单计划。儿童模式下不应为了商业兼容主动放行广告。
- 不以破解付费墙、绕过 DRM、绕过站点安全策略为目标。

## 现有约束

- `shouldInterceptRequest` 可拦截多类 URL scheme，但 Android 文档说明该回调不在 UI 线程执行，因此只能做纯计算和返回响应，不能直接操作 View 或 WebView。
- `shouldInterceptRequest` 对重定向只会收到初始 URL，不适合依赖它完整处理跳转后的 URL 清洗或重定向规则。
- `shouldOverrideUrlLoading` 可处理页面、用户或重定向触发的导航，但不会覆盖所有 App 主动 `loadUrl()` 和 POST 场景。
- `WebResourceResponse` 可用于返回空响应。实现时应按资源类型返回合适 MIME 和状态，例如 `204 No Content` 或空的 JS/CSS/图片占位，避免让页面误判为文本资源。
- `WebViewPool` 中热备 WebView 可能携带旧过滤配置。规则或过滤开关变化后必须清理、绕过或刷新旧实例。
- 离线内置儿童应用和 `ChildAppSourceResolver` 的本地资源优先级必须高于广告过滤，避免本地资产被误拦截。

## 规则生态兼容范围

### 输入格式

必须支持以下订阅和自定义规则输入：

- ABP/EasyList 文本规则列表，包括 `[Adblock Plus 2.0]` 头、`!` 注释、元信息注释和普通规则行。
- uBlock Origin 静态过滤规则的高频子集。uBO 官方说明其支持大部分 EasyList/ABP 语法，并在其上扩展，因此需要按“ABP 核心优先，uBO 扩展渐进支持”的路线实现。
- AdGuard 过滤规则的高频子集，包括 AdGuard 官方过滤器和语言过滤器中常见的网络、CSS 隐藏和 scriptlet 规则。
- hosts/domain 类列表，例如 `0.0.0.0 example.com`、`127.0.0.1 example.com`、裸域名列表。此类规则按域名级拦截导入，不转换成私有格式暴露给用户。

### 网络过滤规则

P0 必须支持：

| 能力 | 示例 | 说明 |
| --- | --- | --- |
| 普通字符串、通配符、分隔符 | `*/ads/*`, `/banner/*/img^` | 按 ABP 语义解析 `*` 和 `^`。 |
| 域名锚定 | `||doubleclick.net^` | 匹配目标 host 及其子域。 |
| URL 起止锚定 | `|https://example.com/ad.js`, `ad.js|` | 支持 URL 开头/结尾匹配。 |
| 正则规则 | `/adserver\\d+\\.example/` | 需要编译缓存和数量限制，避免运行时灾难性回溯。 |
| 例外规则 | `@@||example.com/allowed.js` | 例外优先于普通阻断，除非命中 `$important`。 |
| 资源类型 | `$script`, `$image`, `$stylesheet`, `$font`, `$media`, `$xmlhttprequest`, `$subdocument`, `$document`, `$websocket`, `$ping`, `$other` | 由 `WebResourceRequest`、URL 后缀、请求头和页面上下文综合推断；无法精准识别时降级为 `other`。 |
| 一方/三方 | `$third-party`, `$first-party` | 基于顶层页面 host 与请求 host 判断，优先使用 registrable domain，缺省退回 host 后缀判断。 |
| 域名限制 | `$domain=example.com|~safe.example` | 支持包含和排除站点。 |
| 强制规则 | `$important` | 可覆盖普通例外规则，用于安全类和维护者明确强制规则。 |
| 禁用规则 | `$badfilter` | 用于订阅或自定义规则禁用误伤规则。 |

P1 支持：

- `$popup`：结合 `WebChromeClient.onCreateWindow`、`target=_blank` 和导航上下文处理弹窗规则。
- `$removeparam`：对主框架 GET 导航和子资源 URL 清理追踪参数。由于 WebView 不能在 `shouldInterceptRequest` 中安全做 3xx 重定向，主框架优先在 `shouldOverrideUrlLoading` 中处理，子资源仅在可安全重发时处理。
- `$redirect` / `$redirect-rule`：仅支持本地空资源或安全占位资源，例如空 JS、空 CSS、1x1 透明图片。不得从订阅执行远程替换资源。
- `$csp`：谨慎评估。WebView 内对响应头改写能力有限，P1 只允许对主框架注入本地固定 CSP 片段，且默认关闭。

P2 暂不承诺或仅记录为不支持：

- HTML response body filtering、`replace=`、任意响应体改写。
- CNAME uncloaking、DNS 层 IP 规则。
- 请求头/响应头通用改写、cookie 通用改写。
- 需要浏览器扩展 API 才能可靠实现的动态规则。

导入规则时不能静默丢弃不支持项。必须记录每个订阅的解析统计：总规则数、已启用规则数、已忽略规则数、不支持语法数量和最近一次错误摘要。

### CSS 元素隐藏规则

P0 支持标准 cosmetic filters：

- 通用隐藏：`##.ad-banner`
- 站点限定隐藏：`example.com##.ad-banner`
- 例外隐藏：`example.com#@#.ad-banner`
- 多域名选择：`a.com,b.com##.ad`

实现要求：

- 按当前页面 host 生成 CSS，注入到页面。注入必须在 UI 线程执行。
- 通用 CSS 规则要分批和限量，避免每个页面注入过大的样式字符串。
- 白名单儿童站点优先启用站点限定规则，通用规则误伤时可站点级关闭 cosmetic filtering。
- `#@#` 例外必须能撤销对应隐藏规则。

P1 支持一部分高级选择器：

- 浏览器原生支持的 CSS4 `:has()` 可按 WebView 内核能力动态启用。
- ABP/AdGuard/uBO 各自的扩展选择器，例如 `:-abp-contains`、`:contains()`、`:has-text()`，不得直接拼成 CSS。需要本地 JS DOM 扫描器实现，并默认限制数量、执行时机和单次耗时。

### Scriptlet 规则

主流生态中存在 uBO `##+js(...)` 和 AdGuard `#%#//scriptlet(...)` 规则。儿童应用可以兼容语法，但不能把订阅中的脚本文本当作任意代码执行。

要求：

- 只允许调用本地内置、审计过的 scriptlet allowlist。
- 所有参数必须做长度、字符集和类型校验。
- 未知 scriptlet 规则要记录为 unsupported，不执行、不报崩溃。
- 首批可评估的安全 scriptlet 包括：阻止 `window.open`、限制弹窗定时器、禁用常见广告占位检测变量、阻止特定属性读取/写入。每个 scriptlet 必须有单独测试页面。
- Scriptlet 默认只在“强力去干扰”或站点明确需要时启用；“标准儿童过滤”默认只启用低风险项。

## 默认订阅与规则集策略

### 默认启用策略

过滤能力应提供三个预设强度：

| 预设 | 默认场景 | 默认启用 |
| --- | --- | --- |
| 轻量兼容 | 正常模式、低性能设备、排障 | 仅启用本地高置信广告/追踪域名和 hosts/domain 轻量规则；不启用 cosmetic/scriptlet。 |
| 标准儿童过滤 | 儿童模式默认 | EasyList、EasyPrivacy、AdGuard Chinese filter、AdGuard Mobile Ads filter、本地儿童安全补充规则。 |
| 强力去干扰 | 家长手动选择 | 标准儿童过滤 + Fanboy's Annoyance 或 AdGuard Annoyances/Popups + URL Tracking + 安全类列表。 |

说明：

- 正常模式继续以兼容性为先，过滤开关可默认关闭或使用轻量兼容；儿童模式默认启用“标准儿童过滤”。
- EasyList 和 AdGuard Base 都覆盖通用广告，默认不同时启用，避免重复规则增加内存和误伤。标准儿童过滤优先使用 EasyList 作为通用广告基础。
- 中文站点默认优先启用 AdGuard Chinese filter。EasyList China 放入内置目录并完整兼容，作为中文规则备用或高级用户选择项。
- Fanboy's Annoyance、AdGuard Annoyances、Popups、Cookie Notices 等主要处理遮罩、社交组件、通知提示和反广告拦截，误伤 UI 的概率更高，因此不放入标准儿童过滤默认启用项。
- 不默认启用 Acceptable Ads。

### 内置订阅目录

应用内置订阅目录应至少包含：

| 类别 | 规则集 | 默认状态 |
| --- | --- | --- |
| 通用广告 | EasyList | 儿童标准默认启用 |
| 通用隐私 | EasyPrivacy | 儿童标准默认启用 |
| 中文广告 | AdGuard Chinese filter | 儿童标准默认启用 |
| 中文广告备用 | EasyList China | 内置可选 |
| 移动广告 | AdGuard Mobile Ads filter | 儿童标准默认启用 |
| 通用广告备用 | AdGuard Base filter | 内置可选，与 EasyList 二选一 |
| 追踪备用 | AdGuard Tracking Protection filter | 内置可选，与 EasyPrivacy 可二选一或强力模式叠加 |
| 弹窗/干扰 | Fanboy's Annoyance List | 强力去干扰可选 |
| 弹窗/干扰备用 | AdGuard Annoyances / Popups | 强力去干扰可选 |
| 追踪参数 | AdGuard URL Tracking filter | 强力去干扰可选，依赖 `$removeparam` 支持 |
| uBO 生态 | uBlock filters - Ads/Privacy/Badware risks | 内置可选，按兼容矩阵逐步开放 |
| 安全类 | URLHaus、Phishing Army、NoCoin 或 AdGuard 安全相关列表 | 强力或安全增强可选，命中主框架时必须显示可解释拦截页 |
| 轻量域名 | AdGuard DNS filter 或同类 domain-only 列表 | 低性能设备可选 |

内置目录是“可订阅清单”，不等于全部打包启用。每个条目必须展示名称、用途、维护方、主页、订阅 URL、最近更新时间、规则数、许可证和兼容状态。

### 本地儿童安全补充规则

保留一个本地补充规则集，但必须使用 ABP/hosts 兼容文本表达，例如：

```adblock
! Child Kiosk local supplemental rules
||doubleclick.net^
||googlesyndication.com^
||googleadservices.com^
||adservice.google^
||taboola.com^
||outbrain.com^
||amazon-adsystem.com^
||umeng.com^
||cnzz.com^
```

该规则集用于兜底高置信广告、统计和儿童不适合的强干扰源，不允许发展成独立私有规则生态。

## 订阅管理

### 下载与更新

- 订阅下载由主进程负责，使用 `WorkManager` 或等价后台任务在 `Dispatchers.IO` 中执行。
- 仅允许 HTTPS 订阅 URL，除非家长在高级设置中显式确认 HTTP 风险。
- 支持 ETag、Last-Modified、If-None-Match、If-Modified-Since，避免重复下载。
- 默认每日检查一次；失败后指数退避；允许家长手动更新。
- 更新失败时保留上一份可用编译版本，不让过滤引擎进入空规则状态。
- 单个订阅默认大小上限 15MB，全部启用订阅原始文本默认上限 60MB。超限需提示并拒绝或让家长确认。
- 解析前后记录 SHA-256、规则数、启用数、忽略数和编译耗时。

### 存储模型

建议新增或等价实现：

- `FilterSubscriptionEntity`：订阅配置、启用状态、URL、分类、预设、最近检查时间。
- `FilterListVersionEntity`：原始文件路径、哈希、下载时间、解析统计、兼容状态。
- `CompiledFilterSnapshot`：不可变编译快照文件或序列化缓存，供 `:webview` 进程加载。
- `CustomFilterRuleEntity`：家长自定义规则文本、启用状态、校验错误。
- `SiteFilterOverrideEntity`：站点级例外、临时放行、关闭 cosmetic/scriptlet 的设置。
- `FilterEventLog`：最近拦截和放行事件，限制数量并可清理。

### 自定义规则

- 提供家长自定义规则编辑器，格式为 ABP/uBO/AdGuard 兼容文本。
- 自定义规则优先级高于订阅规则，便于通过 `@@`、`#@#`、`$badfilter` 修复误伤。
- 编辑器必须有“校验”按钮，展示错误行、不支持语法和生效规则数。
- 支持从剪贴板粘贴订阅 URL 或规则文本，不能把自定义输入转换成私有格式后丢失原文。

## 运行时过滤架构

### 核心组件

- `FilterRuleParser`：解析 ABP/uBO/AdGuard/hosts 文本，输出统一 AST 和兼容统计。
- `FilterCompiler`：把 AST 编译为可快速匹配的不可变索引。
- `FilterEngine`：纯 Kotlin/JVM 匹配引擎，不依赖 Android View，可单元测试。
- `FilterRepository`：管理订阅、版本、快照和自定义规则。
- `FilterRuntimeConfig`：随 WebView 启动 Intent 或显式 IPC 传入 `:webview` 进程。
- `FilterDecisionLogger`：低开销记录最近事件，供调试和家长查看。

### 请求处理流程

1. `WebViewActivity` 打开页面时接收过滤快照版本、启用订阅、站点例外和强度预设。
2. 页面开始加载时记录顶层 URL，用于三方判断和站点限定规则。
3. `shouldInterceptRequest` 构造 `FilterRequestContext`：请求 URL、顶层 URL、资源类型、是否主框架、是否三方、HTTP method、是否用户手势。
4. `FilterEngine` 在内存快照中匹配例外、阻断、重定向和移除参数规则。
5. 命中阻断时返回资源类型对应的空响应，并记录规则来源、规则文本和订阅名称。
6. 命中例外时返回 `null` 继续正常加载，并记录例外来源。
7. 页面加载阶段按 host 获取 cosmetic 和 scriptlet 规则，在 UI 线程注入。
8. 弹窗创建和导航跳转阶段结合 `$popup`、多窗口设置和站点例外决策。

### 跨进程应用策略

- 过滤开关、订阅启用状态、自定义规则和站点例外变化后，主进程必须生成新的 `CompiledFilterSnapshot`。
- 新打开网页必须通过 Intent 携带快照版本和过滤配置，不依赖 `:webview` 进程自行读取最新偏好。
- 当前已打开的 `WebViewActivity` 优先通过显式广播、bound service、ContentProvider 版本检查或其他明确 IPC 接收新快照并替换 `FilterEngine` 的 `AtomicReference`。
- 如果当前页面不能立即完整应用 cosmetic/scriptlet 变化，设置界面必须提示“当前网页刷新后生效”或“新打开的网站生效”。
- 过滤配置变化后必须清理或标记失效旧 `WebViewPool` 实例，防止热备 WebView 沿用旧规则。

## 性能要求

- `shouldInterceptRequest` 中不得做网络、磁盘大文件读取、数据库查询或正则编译。
- 运行时只读取内存中的不可变编译索引，快照替换用原子引用。
- 规则索引按资源类型、host 后缀、域名限制、三方条件分桶，减少全量扫描。
- 正则规则单独索引并限制数量，编译时检测高风险表达式；匹配耗时超限时熔断该规则并记录。
- 对同一页面内重复请求做 LRU decision cache，key 至少包含 URL、资源类型、顶层 host 和三方状态。
- 默认儿童标准规则下，中端 Android 设备单次请求决策目标：P95 小于 2ms，P99 小于 5ms。
- 默认儿童标准规则下，过滤引擎常驻内存目标小于 50MB；低端设备可自动建议切换轻量兼容预设。
- CSS 注入按站点生成，不在每个页面注入全量通用规则。单页注入 CSS 默认不超过 256KB，超限时优先保留站点限定规则。
- 订阅编译在后台完成，期间 UI 显示进度。编译失败不影响上一版过滤继续工作。

## 误伤治理

### 放行优先级

从高到低：

1. 家长站点级临时放行。
2. 家长自定义例外规则。
3. 儿童白名单站点的主框架和核心一方资源保护策略。
4. 订阅内例外规则。
5. `$important` 阻断规则。
6. 普通阻断规则。
7. cosmetic/scriptlet 规则。

儿童白名单不等于完全不过滤。推荐策略是：白名单站点的主框架、一方脚本、一方样式和一方字体默认更保守，三方广告、追踪和弹窗仍继续过滤。

### 家长可见的解释能力

后台应提供“过滤日志”：

- 最近 200 条拦截/例外事件。
- URL、顶层站点、资源类型、动作、规则文本、订阅名称、时间。
- 一键为当前站点生成临时例外，例如 `@@||example.com^$document` 或关闭该站点 cosmetic filtering。
- 支持复制诊断报告，便于反馈误伤。

### 站点级控制

每个网站至少支持：

- 暂停全部过滤 15 分钟。
- 对当前站点关闭网络过滤。
- 对当前站点关闭元素隐藏。
- 对当前站点关闭 scriptlet。
- 仅放行某条被拦截 URL 或某个三方域名。

### 自动保护

- 默认不因广告规则拦截主框架文档，除非规则来自安全类列表、家长自定义规则或明确 `$document`。
- 页面主框架加载失败时，如果当前页面有大量拦截事件，错误界面应提示家长可查看过滤日志或临时关闭当前站点过滤。
- 对登录、支付、OAuth、验证码、视频 DRM、教育平台考试/作业类页面，不使用自动强清理参数和高风险 scriptlet。
- 强力去干扰预设开启前要提示：“可能隐藏登录、客服、通知、分享或视频控件，遇到异常可对站点放行”。

## 后台 UI 要求

新增“网页过滤”二级页面或卡片组：

- 总开关：网页广告、弹窗与追踪过滤。
- 过滤强度：轻量兼容、标准儿童过滤、强力去干扰、自定义。
- 订阅列表：名称、分类、状态、规则数、更新时间、更新按钮、错误提示。
- 自定义订阅：添加 URL、校验、启用/停用、删除。
- 自定义规则：多行编辑、校验、保存、恢复示例。
- 站点例外：按域名展示、编辑、删除、过期时间。
- 过滤日志：最近事件、按站点过滤、复制诊断。
- 高级设置：允许 hosts 列表、允许 scriptlet、允许 URL 参数清理、最大规则数、低性能模式。

布局要求：

- 所有页面、弹窗、编辑器和日志列表必须支持竖屏/横屏、手机/平板、窄宽和短高。
- 长列表和多行编辑器必须滚动，保存/取消/删除按钮在小屏上始终可达。
- 不把关键操作依赖系统软键盘；例如订阅启用、更新、临时放行、清空日志必须可直接触控完成。
- 危险操作如删除订阅、清空自定义规则、启用 HTTP 订阅，需要家长确认。

## 安全与隐私

- 订阅源只能看到应用定期下载规则文件，不能接收儿童浏览历史。
- 不上传过滤日志，除非家长主动复制或分享诊断报告。
- 不在订阅规则中执行任意远程 JS。
- 规则和日志中可能包含访问 URL，导出前要提示隐私风险。
- 安全类规则命中主框架时显示本地拦截页，说明命中的规则集和域名，提供家长验证后的临时继续访问。
- 不新增 Android 权限，除非某个后续能力有明确必要和安全评估。

## 实施阶段

### 阶段 1：核心网络过滤和订阅

- 新增 ABP 核心网络规则解析、编译和匹配引擎。
- 支持 EasyList、EasyPrivacy、AdGuard Chinese、AdGuard Mobile Ads 的订阅、更新、解析统计和启用。
- 支持自定义规则文本和例外规则。
- 接入 `WebViewActivity.shouldInterceptRequest`，替换当前关键词匹配。
- 过滤配置通过 Intent 快照进入 `:webview` 进程。
- 提供基础过滤日志和站点临时放行。

### 阶段 2：元素隐藏和弹窗规则

- 支持 `##`、`#@#` cosmetic filters。
- 支持 `$popup` 与多窗口策略联动。
- 提供站点级关闭元素隐藏能力。
- 对儿童白名单站点建立误伤回归样例。

### 阶段 3：强力去干扰

- 支持本地安全 scriptlet allowlist。
- 支持 URL Tracking / `$removeparam` 的安全子集。
- 引入 Fanboy's Annoyance 或 AdGuard Annoyances/Popups 作为强力预设可选项。
- 增加反广告拦截提示处理的可控能力。

### 阶段 4：性能和生态完善

- 引入 hosts/domain-only 轻量模式。
- 完善 uBO 扩展语法兼容矩阵。
- 增加订阅兼容测试集、规则基准测试和误伤测试集。
- 评估是否复用成熟引擎，例如 Brave `adblock-rust`。如果直接集成 Rust/NDK 成本过高，也应以其语法兼容和测试用例作为长期参考。

## 验收标准

- 能导入并启用 EasyList、EasyPrivacy、AdGuard Chinese filter、AdGuard Mobile Ads filter，展示规则数和不支持语法统计。
- 自定义规则支持 `||domain^`、`@@||domain^`、`$script`、`$image`、`$third-party`、`$domain=`、`$important`、`$badfilter`。
- 命中规则后，过滤日志能显示具体规则文本和来源订阅。
- 儿童模式新打开网页使用最新过滤快照，不依赖 `:webview` 进程读取旧偏好。
- 修改过滤配置后，旧 `WebViewPool` 实例不会继续使用旧配置。
- 标准儿童过滤下，常见儿童白名单站点、教育站点和视频站点可正常打开、播放和交互。
- 强力去干扰开启后，常见页面弹窗、通知遮罩和反广告拦截提示显著减少，并可通过站点级例外恢复。
- 无网络、订阅下载失败或订阅解析失败时，上一版可用规则继续生效。
- 低性能设备可切换轻量兼容预设，页面不会因过滤引擎造成明显卡顿。
- Kotlin/UI 相关实现完成后至少通过 `:app:compileDebugKotlin`；行为敏感版本通过 `:app:assembleDebug`。

## 参考资料

- Adblock Plus Help Center: How to write filters, <https://help.adblockplus.org/adblock-plus-help-center/how-to-write-filters>
- Adblock Plus filter cheatsheet, <https://adblockplus.org/filter-cheatsheet>
- uBlock Origin static filter syntax, <https://github.com/gorhill/uBlock/wiki/Static-filter-syntax>
- AdGuard custom filter rules, <https://adguard.com/kb/general/ad-filtering/create-own-filters/>
- AdGuard filters, <https://adguard.com/kb/general/ad-filtering/adguard-filters/>
- EasyList overview and supplementary lists, <https://easylist.to/>
- Fanboy Annoyances List, <https://fanboy.co.nz/>
- Brave adblock-rust, <https://github.com/brave/adblock-rust>
- Android `WebViewClient.shouldInterceptRequest`, <https://developer.android.com/reference/android/webkit/WebViewClient#shouldInterceptRequest(android.webkit.WebView,android.webkit.WebResourceRequest)>
- Android `WebResourceResponse`, <https://developer.android.com/reference/android/webkit/WebResourceResponse>
