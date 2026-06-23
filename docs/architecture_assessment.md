# 架构评估与优化建议

评估日期：2026-06-16  
评估视角：Android/Kotlin Kiosk 浏览器架构、WebView 运行时、安全边界、工程质量与可维护性

## 结论摘要

当前项目已经形成了正确的核心方向：主界面和后台使用 Compose，真实网页固定走 `WebViewActivity -> FrameLayout -> WebView`，并把 WebView 放入独立 `:webview` 进程；同时通过运行时配置快照缓解了跨进程 `SharedPreferences` 不一致问题。这些是儿童 Kiosk 浏览器里最关键的架构决策。

主要短板不在“能不能跑”，而在“复杂度开始集中、策略边界开始模糊、质量基线还不够系统化”。`AdminConsoleScreen.kt` 超过 4000 行、`WebViewActivity.kt` 接近 1800 行、`KioskPrefs.kt` 超过 700 行，核心行为、UI、数据访问、网络请求、安全策略和调试能力高度耦合。继续在现有形态上叠功能，会让设置生效、WebView 池复用、安全默认值、横竖屏可达性和发布回归越来越难保证。

建议把下一阶段目标定为“收敛架构复杂度 + 固化安全与验证基线”，而不是继续增加开关。优先处理 P0/P1 项，随后再扩展内容生态和高级能力。

## 已做对的关键决策

- WebView 使用独立 `:webview` 进程，降低大型 H5 页面 OOM 对主 Kiosk 壳的影响。
- 真实网页使用原生 `FrameLayout + WebView` 承载，避免 Compose `AndroidView` 宿主带来的渲染不确定性。
- WebView 相关设置通过启动 `Intent` 传递运行时配置快照，方向、沙箱、过滤、调试注入等不再完全依赖 WebView 进程读取偏好。
- 屏幕固定软锁、Device Owner 完全锁定、无锁定三档模型降低了普通家庭安装门槛。
- Adblock 过滤引擎开始从关键词黑名单演进到主流规则语法子集，并已有单元测试覆盖。
- AGENTS/README/docs 对 WebView 渲染、发布纪律、横竖屏 UI 可达性已有明确约束，说明项目开始有工程治理意识。

## P0 问题

### 1. 核心类职责过载，维护风险已经显性化

证据：

- `AdminConsoleScreen.kt` 约 4223 行，承担后台页面导航、所有设置 UI、网络检查、GitHub Release 查询、过滤订阅管理、添加网站、PIN 设置、诊断展示等职责。
- `WebViewActivity.kt` 约 1818 行，承担 Activity 生命周期、WebView 创建、WebViewClient/WebChromeClient、悬浮控件、时间限制、认证弹窗、下载、过滤注入、调试桥和 JS 下载缓存。
- `KioskPrefs.kt` 约 708 行，既是偏好存储，又是模式预设、WebView 运行时 DTO、JSON 序列化、跨进程配置快照生成器。

影响：

- 新增设置时很容易遗漏“当前 Activity 立即生效 / 新开网页生效 / WebView 池失效 / 跨进程快照”中的某一环。
- UI 改动和安全策略改动混在一起，代码审查难以聚焦。
- WebView 行为回归需要人工记忆大量隐含耦合。

建议：

- 拆出 `settings` 域：`KioskSettingsRepository`、`QuickModePolicy`、`WebViewRuntimeConfigMapper`，统一管理设置读取、写入、模式预设和运行时快照。
- 拆出 `webview` 域：`WebViewFactory`、`KioskWebViewClient`、`KioskWebChromeClient`、`WebViewNavigationPolicy`、`WebViewScriptInjector`、`DownloadPolicy`。
- 拆出 `admin` UI 子页面：快速模式、时间与认证、WebView 性能、沙箱限制、过滤、站点管理、系统诊断分别独立 Composable 文件。
- 拆分时保持行为不变，先做机械边界提取，再补测试，不要和新功能混做。

### 2. 配置存储分裂，缺少统一的设置生效协议

证据：

- Room 保存 `SystemConfigEntity` 和 `WebAppEntity`；大量运行时设置保存在 `KioskPrefs`；过滤设置又由 `FilterRepository` 管理。
- `MainActivity`、`KioskMainScreen`、`AdminConsoleScreen`、`WebViewActivity` 都直接读取配置。
- 部分 UI 使用 `remember { KioskPrefs.get... }`，依赖页面重建或本地状态更新，缺少统一的 observable settings state。

影响：

- 设置来源分散后，默认值、迁移和“快速模式是否覆盖该项”容易不一致。
- 多进程下无法简单判断某个设置是否已经对旧 WebView、热备 WebView 和当前 Activity 生效。
- 后续版本一旦引入更多配置项，回归成本会快速上升。

建议：

- 定义 `SettingsState` 聚合模型，明确字段来源、默认值、模式归属、是否跨进程、是否要求清理 WebViewPool。
- 所有设置写入走 repository，返回 `ApplyEffect`，例如 `ApplyImmediately`、`ApplyNextWebPage`、`RequiresPoolClear`、`RequiresActivityRecreate`、`RequiresRestart`。
- 主进程 UI 订阅 settings flow；WebView 启动只接收不可变 `WebViewRuntimeConfig`。
- 保留同步读取能力给启动早期和 `onResume`，但把直接 `KioskPrefs.get...` 调用收敛到 repository 内部。

### 3. Room 使用破坏性迁移，不适合进入真实家庭数据阶段

证据：

- `AppDatabase` 使用 `fallbackToDestructiveMigration()`，版本为 3，且 `exportSchema = false`。
- 数据库里保存白名单、分类、PIN hash、时间限制和每日累计用时。

影响：

- 后续 schema 升级可能清空家长配置的网站、分类和使用限制。
- 数据丢失对 Kiosk 应用很敏感，可能导致儿童可访问内容变化或管理策略失效。
- 没有 schema 导出，迁移审查和自动化验证都比较困难。

建议：

- 从下一次数据库变更开始启用 `exportSchema = true` 并提交 schema。
- 补齐 1->2、2->3 以及未来版本的显式 migration，避免继续依赖 destructive fallback。
- 新增 migration 单元/仪器测试，至少验证用户添加的网站、启用状态、分类、时间限制、PIN 配置在迁移后保留。
- 仅在明确“重置数据”功能中做破坏性清理，并给管理员确认。

### 4. PIN 保护强度不足

证据：

- `HashUtils.sha256(input)` 对 4 位 PIN 做裸 SHA-256，无盐、无迭代。
- PIN hash 存在本地 Room 配置中。

影响：

- 4 位 PIN 空间只有 10000 个，裸 hash 很容易离线枚举。
- 对儿童防护未必立即致命，但对“家长锁”语义不够严谨，尤其设备可被连接调试或备份提取时。

建议：

- 使用 Android Keystore 生成/保护本地密钥，或至少改为 per-install salt + PBKDF2/Argon2id 类 KDF。
- 增加验证失败节流：连续失败后短暂冷却，防止孩子机械试错。
- 对既有裸 SHA-256 做兼容迁移：首次成功验证后升级为新格式。

## P1 问题

### 5. WebView 安全与浏览器兼容默认值需要产品化分层

证据：

- 正常模式默认较开放，儿童模式默认收紧；但具体开关很多，包括下载、跳转、定位、媒体、文件访问、混合内容、第三方 Cookie、调试注入、自定义 JS。
- `usesCleartextTraffic="true"` 和 `http://` 网站允许保存，WebView 对 HTTP 目标会允许 mixed content。
- 自定义 JS 注入和调试桥能力对开发很有用，但属于高风险高级能力。

影响：

- 普通管理员容易误配：既希望“安全”，又可能打开下载、调试注入或 HTTP 站点。
- 安全能力过细会让“儿童模式到底安全到什么程度”难以解释。

建议：

- 把设置分成三层：普通设置、安全高级设置、开发者调试设置。开发者调试区需二次确认，并显示“仅用于排查，不适合儿童模式”。
- 对 HTTP 站点、下载、文件访问、自定义 JS 注入、关闭 SSL 检查给出强提醒，并记录配置风险状态。
- 儿童模式下默认禁止自定义 JS 注入和调试桥；如果用户强行开启，应自动进入自定义模式并展示风险标识。

### 6. WebViewPool 与运行时配置快照已改进，但仍需制度化失效规则

证据：

- `WebViewPool` 以 runtime config JSON 作为 pool key，能避免部分旧配置复用。
- `MainActivity.applySandboxLimits()` 会清理和重新 warmup。
- 但各设置 setter 并不统一声明是否需要清理池，部分设置只写入 SharedPreferences。

影响：

- 新增 WebView 创建相关设置时，仍可能忘记清理旧热备实例。
- 预加载 URL、过滤引擎缓存、调试注入、UA、Cookie、混合内容等都属于容易被旧实例携带的状态。

建议：

- 在 `SettingsRepository` 中为每个设置声明 `RuntimeImpact`，由统一写入路径触发 pool clear 或 toast。
- `WebViewRuntimeConfig` 增加稳定的版本字段，避免 JSON 字段顺序或新增字段导致不可控 key 变化。
- 增加单元测试：修改影响 WebView 创建的设置后，旧 pool entry 不被复用。

### 7. 自动化验证覆盖不足

证据：

- 当前可见单元测试主要集中在 `FilterEngineTest`。
- 缺少对 `TimeLimiter`、`WebViewRuntime.resolveUserAgent`、URL/host 策略、quick mode preset、PIN hash 迁移、Room migration 的测试。
- 没有 Compose UI 或仪器测试覆盖横屏短高度验证弹窗、后台设置页可达性、WebView 返回顺序。

影响：

- 项目高度依赖人工实机验证，发布时容易漏掉横竖屏、OEM、WebView provider 差异。
- 安全策略和兼容策略回归很难通过 CI 提前发现。

建议：

- P1 先补 JVM 单元测试：QuickModePolicy、TimeLimiter、WebViewRuntime、FilterRepository 快照、URL 策略。
- P1/P2 补仪器或截图测试：PIN/口算弹窗横屏短高度、后台关键页滚动可达、主网格分类和大图标模式。
- CI 增加 `:app:testDebugUnitTest`，Release 前再跑 `:app:assembleDebug :app:assembleRelease`。

### 8. 网络请求散落在 UI 与运行时中

证据：

- `AdminConsoleScreen` 内部直接执行添加网站 HEAD 探测和 GitHub Release API 请求。
- `WebViewActivity` 里调试工具 fallback 直接下载远程 JS。
- `FilterRepository` 管理订阅下载。

影响：

- 网络超时、错误处理、重试、User-Agent、证书策略和测试替身不统一。
- UI 文件继续膨胀。

建议：

- 新增轻量 `NetworkClient` 或 `HttpFetcher` 接口，统一超时、UA、HTTPS 策略和错误类型。
- UI 只调用 use case，例如 `CheckWebsiteReachability`、`FetchLatestRelease`、`UpdateFilterSubscription`。
- 调试 JS fallback 限定只在调试模式下启用，下载结果大小设上限，避免异常大脚本占用内存。

## P2 问题

### 9. Manifest 权限和发布声明需要收敛

证据：

- Manifest 声明 `QUERY_ALL_PACKAGES`、`WRITE_EXTERNAL_STORAGE`、`largeHeap`、`usesCleartextTraffic=true`。
- `WRITE_EXTERNAL_STORAGE` 仅 maxSdk 28；`QUERY_ALL_PACKAGES` 为查询 HOME 应用相关能力服务，但商店发布会被重点审核。

建议：

- 为每个敏感权限建立“使用点 -> 是否可替代 -> 发布说明”表。
- 如果只需查询 HOME intent，优先保持 `<queries>` 精准声明，评估是否可以移除 `QUERY_ALL_PACKAGES`。
- `usesCleartextTraffic=true` 若只是允许家长添加 HTTP 站点，建议改成更明确的 network security 策略和 UI 风险确认。

### 10. README 与真实代码/产品策略存在局部漂移

证据：

- README 中仍有“默认 Tier 2 屏幕固定软锁”等历史表述，而 `KioskPrefs.DEFAULT_MODE` 当前是 `MODE_NONE`，快速模式文档说明默认正常模式。
- README 项目结构仍像早期结构，真实代码已有 `filter`、`browser`、provider diagnostics 等模块。

影响：

- 新维护者会按过期文档形成错误预期。
- 用户也可能误解首次安装后的安全等级。

建议：

- 每次 release 前把 README 的“默认行为、项目结构、部署路径、安全说明”列入发布检查项。
- 将“默认正常模式”和“儿童模式如何开启强防护”写得更明确，降低误用。

## 建议的目标架构

```text
app
├── appcore
│   ├── KioskAppState
│   ├── KioskSettingsRepository
│   ├── QuickModePolicy
│   └── RuntimeImpact
├── data
│   ├── AppDatabase
│   ├── migrations
│   └── dao/entities
├── kiosk
│   ├── DeviceOwnerPolicy
│   ├── LockTaskController
│   └── SystemUiPolicy
├── webview
│   ├── WebViewRuntimeConfig
│   ├── WebViewFactory
│   ├── KioskWebViewClient
│   ├── KioskWebChromeClient
│   ├── NavigationPolicy
│   ├── DownloadPolicy
│   ├── ScriptInjectionPolicy
│   └── WebViewPool
├── filter
│   ├── FilterEngine
│   ├── FilterRepository
│   └── FilterEventLog
└── ui
    ├── main
    ├── admin
    ├── verification
    └── browser
```

迁移原则：

- 不改变生产 WebView 承载路径。
- 不一次性重写 UI，只按功能边界拆文件和 use case。
- 每拆一个边界，补一组低成本单元测试。
- 先让设置写入和运行时影响集中，再处理 UI 视觉和交互细节。

## 路线建议

### 近期：1-2 个 patch 版本

- 修正 README 与真实默认模式、真实目录结构的漂移。
- 为 QuickMode、TimeLimiter、WebViewRuntime、URL 策略补 JVM 单元测试。
- 改造 PIN 存储，至少引入 salt + KDF，并兼容旧 hash。
- 建立设置项影响矩阵文档，后续新增设置必须标注生效时机。

### 中期：1 个 minor 版本

- 引入 `KioskSettingsRepository` 和 `QuickModePolicy`，收敛直接 `KioskPrefs` 访问。
- 拆分 `AdminConsoleScreen` 为多个子页面文件。
- 拆分 `WebViewActivity` 中的 WebViewClient/WebChromeClient/注入/下载策略。
- 启用 Room schema 导出和显式 migration。

### 长期：2-3 个 minor 版本

- 建立仪器测试与截图回归基线，覆盖横屏短高度、平板、WebView 返回链路。
- 引入配置风险评估页：告诉管理员当前设备处于普通、儿童、自定义、调试中的哪种安全状态。
- 评估把过滤订阅、事件日志、站点例外从 SharedPreferences/文件逐步迁入结构化存储。

## 推荐优先级清单

| 优先级 | 事项 | 主要收益 |
| --- | --- | --- |
| P0 | 拆分设置写入与运行时影响协议 | 降低设置不生效和旧 WebView 复用风险 |
| P0 | 去掉未来迁移对 destructive fallback 的依赖 | 保护用户白名单和安全配置 |
| P0 | 强化 PIN 存储与失败节流 | 提升家长验证可信度 |
| P1 | 拆分 Admin/WebView 大文件 | 降低功能迭代和审查成本 |
| P1 | 增加核心策略单元测试 | 让发布回归可自动发现 |
| P1 | 网络请求下沉到 use case/client | 改善错误处理和可测试性 |
| P2 | 敏感权限与 README 治理 | 降低发布审核和用户误解风险 |

