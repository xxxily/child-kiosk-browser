# LocationManager 网页定位优化需求文档

> 文档版本：1.0  
> 创建日期：2026-07-03  
> 状态：需求调研与方案细化，待确认后进入实现  
> 关联模块：`WebViewActivity`、`WebViewRuntime`、`WebViewPool`、`KioskPrefs`、后台管理 WebView 设置、Site Info Panel  
> 核心结论：先不接入高德 SDK，不拆分构建版本。基于 Android 系统 `LocationManager` 增加可配置的“定位预热、原生定位诊断、可选 Geolocation bridge”能力，让用户决定是否启用；默认继续保持当前 WebView 定位行为。

---

## 1. 背景与调整结论

上一版增强定位调研已经确认：直接集成高德 Android 定位 SDK 的工程与合规成本较高，而且普通网页的 `navigator.geolocation` 不会因为 APK 中多了高德 SDK 就自动改用高德定位。高德 H5 辅助定位主要面向使用高德 JS API 的页面，很多第三方网页或既有网页应用无法直接受益。

因此当前阶段调整为：

- 不引入高德、百度、腾讯、Google Play Services 等外部定位 SDK。
- 不增加 `standard/enhanced` 构建版本，不修改当前 GitHub Actions 的多版本输出策略。
- 优先基于 Android 官方 `LocationManager` 做底层优化。
- 功能默认关闭，通过后台配置交给管理员选择启用。
- 保持现有真实网页承载路径：`WebViewActivity -> FrameLayout -> WebView`。

需要先明确一个边界：

> `LocationManager` 可以让宿主 App 主动取系统位置、预热系统定位缓存、诊断定位状态；但 Android WebView 的标准 Geolocation 授权回调仍然不能直接传入坐标。若要稳定让普通网页拿到宿主通过 `LocationManager` 取得的位置，仍需要可选的 JS bridge/polyfill 接管 `navigator.geolocation`。

所以本方案分两层推进：

1. **低侵入层（P0）：定位预热与诊断。** 不覆盖网页 API，只在宿主层短时请求系统位置，刷新系统定位缓存，提升 WebView 后续定位可能命中缓存或热启动的概率。
2. **托管层（P1）：标准 Geolocation bridge。** 用户显式开启后，由宿主通过 `LocationManager` 获取位置，并以标准 `navigator.geolocation` 回调结构返回给网页，获得更强可控性。

---

## 2. 当前项目定位现状

当前代码已具备标准 WebView 定位权限链路：

- `AndroidManifest.xml` 已声明 `ACCESS_COARSE_LOCATION` 与 `ACCESS_FINE_LOCATION`。
- `WebViewRuntime.applySettings()` 根据 `limitGeolocation` 调用 `setGeolocationEnabled(...)`。
- `WebViewActivity` 使用 `WebChromeClient.onGeolocationPermissionsShowPrompt` 处理网页定位授权。
- 管理后台已有“禁用网页定位 (Geolocation)”全局开关。
- Site Info Panel 和后台已有 Origin 维度 `geolocationBlacklist`。
- `WebViewActivity` 运行在 `:webview` 独立进程，WebView 运行配置通过 `Intent` 快照传入，不能依赖 WebView 进程实时读取主进程 SharedPreferences。

现有问题：

- WebView 默认定位是黑盒，失败原因难诊断。
- 户外首次定位可能因为冷启动慢导致网页超时。
- 部分设备没有可靠网络定位 provider，WebView 默认路径可能只依赖 GPS 或返回偏差较大的缓存。
- Android 12+ 用户可能只授权近似位置，网页和宿主都必须正确降级。
- 现有权限弹窗只决定“是否允许网页请求定位”，不能控制定位 provider、超时、缓存策略和诊断。

---

## 3. Android LocationManager 能力边界

### 3.1 能做什么

`LocationManager` 是 Android 平台定位服务入口，不依赖外部 SDK。当前项目 minSdk 为 28，可以覆盖 Android 9 及以上设备。

可用能力：

- 检查系统定位总开关，例如 `isLocationEnabled()`。
- 查询可用 provider，例如 GPS、network、passive，以及部分系统上的 fused provider。
- 读取 last known location，快速判断系统是否已有近期定位缓存。
- Android 11+ 使用 `getCurrentLocation(...)` 发起单次定位。
- Android 9/10 使用 `requestLocationUpdates(...)` 配合超时取消实现单次定位。
- 使用 `requestLocationUpdates(...)` 支持网页 `watchPosition` 这类持续定位场景。
- 捕获 provider 不可用、权限不足、超时、位置过旧、精度不足等状态，形成诊断信息。

### 3.2 不能保证什么

`LocationManager` 不是新的定位数据源，本质仍依赖设备系统、GNSS 芯片、基站/Wi-Fi 网络定位实现和厂商 ROM。

不能保证：

- 不能直接替换 WebView 内核自己的定位 provider。
- 不能在没有 GPS 信号、网络定位 provider 不可靠的情况下凭空提升精度。
- 不能绕过 Android 系统定位权限和用户的“近似位置”选择。
- 不能在系统定位总开关关闭时继续获取真实位置。
- 不能承诺所有设备上 network provider 都可用或准确。

预期收益应保守表达：

- **定位预热**：可能缩短首次定位等待时间，尤其户外 GPS 冷启动转热启动后。
- **原生托管 bridge**：可以提升可控性、诊断能力和超时一致性；户外高精度模式可优先等待 GPS 结果。
- **精度提升**：取决于设备 provider 和环境，必须通过真机实测验证，不在 UI 或 release notes 中承诺固定米级精度。

---

## 4. 产品目标

### 4.1 P0 目标：定位预热与诊断

- 新增“LocationManager 定位优化”后台配置，默认关闭。
- 不改变构建产物，不增加 product flavor。
- 不新增外部 SDK 和 Android 权限。
- 在用户已授予定位权限时，可短时启动系统定位预热。
- 不为了预热主动弹 Android 系统权限申请；只有网页真正请求定位或管理员手动测试时才请求系统权限。
- 显示当前系统定位状态、权限状态、可用 provider、最近一次定位耗时、精度范围和失败原因。
- 保持网页默认 `navigator.geolocation` 行为不变，减少兼容风险。

### 4.2 P1 目标：可选标准 Geolocation bridge

- 管理员可选择启用“用系统原生定位托管网页 Geolocation”。
- 开启后，普通网页调用 `navigator.geolocation.getCurrentPosition` 和 `watchPosition` 时，宿主通过 `LocationManager` 获取位置并回调网页。
- 继续复用现有全局禁用定位、Origin 黑名单、站点授权弹窗和 Android 系统权限流程。
- 默认只对已配置允许的站点启用 bridge，避免把儿童设备精确位置暴露给未知网页。
- 如果当前 WebView provider 不支持安全的 document-start 注入或受控消息通道，UI 需要提示兼容性限制。

### 4.3 P2 目标：策略优化

- 支持按站点配置定位策略，例如“兼容优先”“户外高精度”“低功耗”。
- 支持 watchPosition 的节流、最大持续时间和最大并发 watch 数。
- 支持诊断导出，用于对比开启/关闭预热、bridge 后的真机效果。

---

## 5. 非目标

当前需求不做以下事项：

- 不接入高德、百度、腾讯、Google Play Services 或其他外部定位 SDK。
- 不新增 `ACCESS_BACKGROUND_LOCATION`。
- 不做后台持续定位。
- 不采集、上传、持久化儿童轨迹。
- 不修改现有 GitHub Actions 为多版本构建。
- 不改变应用包名、签名策略或 release APK 命名规则。
- 不默认覆盖所有网页的 `navigator.geolocation`。
- 不绕过全局定位禁用、站点黑名单、系统权限和家长确认。
- 不把真实网页迁回 Compose `AndroidView` 承载。

---

## 6. 构建与发布要求

保持当前单版本构建模式：

- 继续使用当前 `:app:assembleDebug`、`:app:assembleRelease`。
- GitHub Actions 继续输出当前 debug/release APK。
- 不增加 `standard/enhanced` flavor。
- 不新增第三方 SDK 依赖。
- 不修改 `applicationId`。

发布说明应写明：

- 本版本增加的是系统 `LocationManager` 定位优化能力。
- 功能默认关闭，需管理员在后台启用。
- 不接入任何第三方定位 SDK。
- 实际效果取决于设备、系统定位 provider、户外/室内环境和用户授权精度。

---

## 7. 后台配置需求

### 7.1 配置入口

在后台 WebView/网页能力设置区域新增“网页定位优化”卡片。该卡片必须符合项目 UI 约束：

- 竖屏和横屏都可完整操作。
- 窄屏下文字和开关不裁切。
- 内容超高时可滚动。
- 输入、测试、确认按钮不能被系统软键盘遮挡。
- 触控目标保持适合 kiosk 操作。

### 7.2 配置项

建议配置如下：

| 配置项 | 默认值 | 生效范围 | 说明 |
|---|---|---|---|
| 启用系统定位优化 | 关闭 | 新打开网页 | 总开关。关闭时保持当前行为 |
| 短时定位预热 | 关闭 | 新打开网页 | 页面加载时短时请求系统位置，刷新缓存 |
| 预热时长 | 5 秒 | 新打开网页 | 建议范围 3-15 秒 |
| 原生托管网页 Geolocation | 关闭 | 新打开网页 | P1 功能，开启后通过 bridge 返回原生位置 |
| 仅对白名单/已允许站点启用托管 | 开启 | 立即影响决策 | 避免未知站点获得高精度位置 |
| 定位策略 | 兼容优先 | 新请求 | 兼容优先、户外高精度、低功耗 |
| 最大缓存年龄 | 30 秒 | 新请求 | 命中近期位置时直接返回 |
| 单次定位超时 | 10 秒 | 新请求 | 高精度模式可提高到 20-30 秒 |
| watchPosition 最大持续时间 | 10 分钟 | 新 watch | 到期自动停止，降低耗电 |
| 复制定位诊断 | 手动触发 | 当前状态 | 脱敏导出 provider/耗时/精度/错误 |

### 7.3 配置生效文案

- 开启/关闭“短时定位预热”：提示“新打开的网站生效”。
- 开启/关闭“原生托管网页 Geolocation”：提示“新打开的网站生效”，并清理或绕过旧 WebViewPool。
- 修改站点级启用/禁用托管：应尽量在当前 Activity 内即时影响后续定位请求。
- 修改全局“禁用网页定位”：仍按现有逻辑作为最高优先级。

---

## 8. 权限与安全规则

### 8.1 权限优先级

定位请求决策顺序必须固定：

1. 全局 `limitGeolocation` 开启：直接拒绝，不启动 `LocationManager`。
2. 当前 Origin 命中 `geolocationBlacklist`：直接拒绝，不启动 `LocationManager`。
3. 系统定位总开关关闭：返回错误并提示管理员开启系统定位。
4. Android 定位权限未授予：沿用现有系统权限请求流程。
5. Android 12+ 只有近似位置权限：按粗定位返回，不能假装是精确定位。
6. 管理员未启用定位优化：保持 WebView 默认定位授权路径。
7. 管理员启用预热或 bridge：进入对应 LocationManager 流程。

### 8.2 精确/近似定位

Android 12+ 用户可以选择“精确位置”或“近似位置”。实现要求：

- 继续同时请求 `ACCESS_FINE_LOCATION` 与 `ACCESS_COARSE_LOCATION`。
- 如果只获得 `ACCESS_COARSE_LOCATION`，UI 显示“当前仅有近似位置权限”。
- 只有 coarse 权限时，不强行请求 GPS 高精度 provider；实现必须避免 `SecurityException`。
- bridge 返回给网页的 `coords.accuracy` 必须反映粗定位精度，不能固定写成高精度。
- 如果网页请求 `enableHighAccuracy: true`，但系统只授权近似位置，应返回可用粗略位置或明确错误，不能卡死。

### 8.3 不申请后台定位

本项目是前台 kiosk 浏览器，不需要 `ACCESS_BACKGROUND_LOCATION`：

- WebViewActivity 可见时才允许原生定位。
- `onStop()`、`onDestroy()`、页面关闭、标签销毁时必须停止 watch 和预热。
- 不在 BootReceiver、MainActivity 后台或主进程 Application 中启动定位。

### 8.4 隐私与日志

- 不持久化经纬度。
- 不上传定位数据。
- release 日志不输出完整经纬度。
- 复制诊断信息时只输出：
  - provider 名称。
  - 是否成功。
  - 耗时。
  - 精度米数或精度区间。
  - 缓存年龄。
  - 错误类型。
- 如必须显示经纬度用于管理员测试，只在本机测试弹窗中显示，并提供遮蔽/复制脱敏选项。

---

## 9. 技术方案

### 9.1 配置模型

`WebViewRuntimeConfig` 新增字段建议：

```kotlin
val nativeLocationOptimizationEnabled: Boolean
val nativeLocationWarmupEnabled: Boolean
val nativeLocationBridgeEnabled: Boolean
val nativeLocationAllowedOrigins: Set<String>
val nativeLocationWarmupTimeoutMs: Long
val nativeLocationRequestTimeoutMs: Long
val nativeLocationMaxCacheAgeMs: Long
val nativeLocationMode: String
val nativeLocationWatchMaxDurationMs: Long
```

要求：

- 通过 `toJson/fromJson` 传入 `:webview` 进程。
- 参与 `WebViewPool.poolKey()`，避免复用旧 bridge 注入状态。
- 修改 bridge 相关配置时清理 WebViewPool。
- 修改仅影响请求策略的配置时，对新请求生效。

### 9.2 原生定位管理器

新增 `SystemLocationManager` 或 `NativeLocationManager`，运行在 `:webview` 进程内，由 `WebViewActivity` 生命周期持有。

职责：

- 统一检查权限、系统定位开关和 provider 可用性。
- 读取 last known location，并按缓存年龄和精度过滤。
- 发起单次定位请求。
- 管理 watchPosition 持续监听。
- 统一取消请求、释放 listener。
- 维护最近一次脱敏诊断快照。

位置结果模型：

```kotlin
data class NativeLocationResult(
    val success: Boolean,
    val latitude: Double?,
    val longitude: Double?,
    val accuracyMeters: Float?,
    val altitude: Double?,
    val bearing: Float?,
    val speed: Float?,
    val elapsedRealtimeNanos: Long?,
    val wallTimeMillis: Long?,
    val provider: String?,
    val cached: Boolean,
    val cacheAgeMillis: Long?,
    val precisePermission: Boolean,
    val error: NativeLocationError?
)
```

注意：经纬度只应在内存中短暂存在，不进入持久化诊断。

### 9.3 Provider 选择

建议按权限和策略选择 provider：

1. 如果只有 coarse 权限：
   - 优先 `NETWORK_PROVIDER`。
   - 可使用 `PASSIVE_PROVIDER` 的近期缓存。
   - 不主动请求 GPS。
2. 如果有 fine 权限：
   - 兼容优先：先尝试近期缓存，再短时请求 network/fused，可按设备情况回退 GPS。
   - 户外高精度：优先 GPS 或系统 fused provider，允许更长超时。
   - 低功耗：优先缓存、passive、network，避免 GPS 长时间工作。
3. Provider 不存在或不可用：
   - 不崩溃，记录诊断并尝试下一个 provider。

实现细节：

- Android 11+ 优先使用 `LocationManager.getCurrentLocation(...)`。
- Android 9/10 使用 `requestLocationUpdates(...)` 实现单次定位，收到首个满足条件的位置或超时后立即 `removeUpdates(...)`。
- 所有 listener 注册和移除必须在主线程或明确的 Handler/Executor 中管理，避免生命周期竞态。
- `SecurityException`、provider disabled、timeout 都要转成可诊断错误。

### 9.4 定位预热

P0 预热不改变网页 API，只刷新系统定位状态。

触发时机：

- WebViewActivity 创建后，如果当前 URL 是 http/https 且配置启用预热。
- 页面开始加载时，如果 Origin 未被定位黑名单拦截。
- 管理员点击“测试系统定位”时。

约束：

- 仅在系统定位权限已经授予时自动预热。
- 不为了预热主动弹系统权限。
- 预热时长默认 5 秒，到时必须取消。
- 如果页面加载完成前已经拿到满足精度/新鲜度的位置，可提前结束。
- WebViewActivity `onStop()`、`onDestroy()` 必须取消。

预热收益的表达：

- 文案使用“可能缩短网页首次定位等待时间”。
- 不写“必然提升精度”。
- 诊断中展示“最近预热是否成功”和“缓存年龄”。

### 9.5 标准 Geolocation bridge

P1 bridge 用于稳定让普通网页使用宿主定位结果。

#### 9.5.1 注入方式

优先方案：

- 使用 AndroidX WebKit 的 document-start 注入能力，在网页脚本执行前安装 `navigator.geolocation` polyfill。
- 使用 AndroidX WebKit 的受控 WebMessage 能力与原生通信，并按 Origin 限制消息来源。

降级方案：

- 如果当前 WebView provider 不支持安全 document-start 注入或受控消息通道，默认不启用 bridge。
- 如必须使用 `addJavascriptInterface` 降级，必须仅对管理员明确允许的站点启用，并在 UI 提示兼容性和 iframe 风险。

原因：

- `addJavascriptInterface` 注入对象可能被页面内 iframe 调用，宿主难以可靠识别子 frame 来源。
- 定位是高敏能力，不能把通用原生定位接口暴露给未知 frame。

#### 9.5.2 API 覆盖范围

需要覆盖：

- `navigator.geolocation.getCurrentPosition(success, error, options)`
- `navigator.geolocation.watchPosition(success, error, options)`
- `navigator.geolocation.clearWatch(id)`

返回结构尽量匹配标准 Geolocation API：

- `coords.latitude`
- `coords.longitude`
- `coords.accuracy`
- `coords.altitude`
- `coords.altitudeAccuracy`
- `coords.heading`
- `coords.speed`
- `timestamp`

错误映射：

- 全局禁用、站点黑名单、用户拒绝：`PERMISSION_DENIED`
- 系统定位关闭、provider 不可用、没有可用位置：`POSITION_UNAVAILABLE`
- 超时：`TIMEOUT`

#### 9.5.3 请求生命周期

- 每个 JS 请求分配 requestId。
- 页面跳转后旧 requestId 全部作废。
- WebView 销毁时取消所有原生请求。
- `watchPosition` 必须限制最大并发数，建议每个 WebView 最多 3 个。
- `watchPosition` 必须限制最短更新间隔，避免恶意页面高频耗电。
- `clearWatch(id)` 必须立即停止对应 listener。
- 如果同一 Origin 高频调用单次定位，应优先复用近期缓存。

### 9.6 与 WebView 默认定位的关系

三种模式：

1. **关闭优化**：完全保持现状。
2. **预热模式**：WebView 仍使用默认 `navigator.geolocation`，宿主只提前刷新系统定位缓存。
3. **托管模式**：注入 bridge，网页 Geolocation 请求由宿主托管；WebView 默认 geolocation 作为兼容回退。

托管模式下是否继续调用 `onGeolocationPermissionsShowPrompt` 需要实现时仔细设计：

- 如果 bridge 完全接管网页调用，则需要在 bridge 内主动复用现有 `requestGeolocationPermission(...)` 弹窗与黑名单逻辑。
- 不能因为绕过 WebView 默认 API 而跳过家长确认。
- 对不支持 bridge 的页面或注入失败的页面，应回退到当前 WebView 默认路径。

### 9.7 坐标系

`LocationManager` 返回 Android 系统 `Location` 语义下的经纬度，按标准网页 Geolocation 预期处理即可。

要求：

- 不做 GCJ-02/WGS84 转换。
- 不新增地图厂商坐标系配置。
- 如果用户发现某个中国地图网页偏移，原因可能是网页地图自身坐标系要求；这不是本阶段解决目标。

---

## 10. UI 与交互细化

### 10.1 状态展示

后台卡片显示：

- 系统定位：已开启/已关闭。
- 应用权限：精确位置、近似位置、未授权。
- 网页定位全局开关：允许/已禁用。
- 当前优化模式：关闭、预热、托管。
- 可用 provider：GPS、network、passive、fused，如系统提供。
- 最近一次定位：
  - 成功/失败。
  - provider。
  - 耗时。
  - 精度。
  - 缓存年龄。
  - 错误类型。

### 10.2 测试定位

新增“测试系统定位”按钮：

- 仅管理员后台可见。
- 点击后请求一次 `LocationManager` 定位。
- 如果应用定位权限未授予，可触发系统权限申请。
- 展示结果时默认不显示完整经纬度，只显示精度、provider、耗时。
- 提供“显示详细坐标”二次确认，仅用于本机排查。

### 10.3 用户提示

典型文案：

- “定位预热可能缩短网页首次定位等待时间，但不保证所有网站精度提升。”
- “原生托管会让已允许的网站通过系统 LocationManager 获取位置，建议仅对可信网站开启。”
- “当前系统只授予近似位置，网页无法获得精确定位。”
- “系统定位服务已关闭，请在系统设置中开启定位。”
- “此设置新打开的网站生效。”

---

## 11. 生命周期与耗电要求

定位能力必须以省电和可回收为底线：

- 预热请求默认最长 5 秒。
- 单次定位请求有硬超时。
- watchPosition 有最大持续时间。
- Activity `onStop()` 停止所有活跃定位。
- Activity `onDestroy()` 销毁定位管理器。
- WebView 被移出或销毁时取消对应请求。
- WebViewPool 回收 WebView 前清除 bridge 状态和 watch 状态。
- 不持有 WakeLock。
- 不在后台进程或不可见 Activity 中继续定位。

耗电诊断：

- 记录活跃 watch 数。
- 记录最近一次 listener 注册/释放时间。
- 诊断页提示是否存在未释放请求。
- 实机验收应使用 `adb shell dumpsys location` 或等价方式检查 listener 是否清零。

---

## 12. 验收标准

### 12.1 构建验收

- 不新增 product flavor。
- 不新增外部 SDK。
- 不新增 `ACCESS_BACKGROUND_LOCATION`。
- `:app:compileDebugKotlin` 通过。
- 行为敏感实现完成后，`:app:assembleDebug` 通过。
- GitHub Actions 仍输出当前 debug/release 两个 APK。

### 12.2 权限验收

- 全局禁用定位时，预热和 bridge 都不启动。
- Origin 在定位黑名单时，预热和 bridge 都不返回位置。
- 系统定位关闭时，网页收到错误或用户看到明确提示，不出现无限等待。
- Android 12+ 只授予近似位置时不崩溃，返回粗略位置或明确错误。
- Android 系统权限拒绝时，不自动把站点加入黑名单。

### 12.3 预热验收

- 预热关闭时，行为与当前版本一致。
- 预热开启但应用未获定位权限时，不主动弹系统权限。
- 预热开启且已有权限时，新页面加载触发短时定位，超时后释放。
- 页面关闭、Activity 停止、进程销毁时 listener 被释放。
- 诊断中能看到最近预热结果、耗时、provider 和错误。

### 12.4 Bridge 验收

- bridge 关闭时，普通网页仍走 WebView 默认 Geolocation。
- bridge 开启且站点允许时，测试页 `getCurrentPosition` 能收到标准结构位置。
- `watchPosition` 能持续收到更新，`clearWatch` 后停止。
- 页面跳转后旧请求不会回调到新页面。
- bridge 不支持或注入失败时，回退 WebView 默认路径并记录诊断。
- 未知 iframe 不能绕过站点授权直接获取位置。

### 12.5 实测验收

至少准备一个本地/内网页面测试：

```javascript
navigator.geolocation.getCurrentPosition(
  pos => console.log('ok', pos.coords.latitude, pos.coords.longitude, pos.coords.accuracy),
  err => console.log('err', err.code, err.message),
  { enableHighAccuracy: true, timeout: 10000, maximumAge: 30000 }
)
```

真机对比：

- 关闭优化。
- 仅开启预热。
- 开启 bridge 托管。

记录：

- 首次定位耗时。
- 是否成功。
- 精度米数。
- provider。
- 是否近似位置权限。
- 是否室内/户外。

验收结论不得只看单次结果，至少覆盖 3 次冷启动和 3 次页面重载。

---

## 13. 推荐实施顺序

### 阶段 1：配置与诊断骨架

- 新增后台“网页定位优化”卡片。
- 新增 `WebViewRuntimeConfig` 字段。
- 新增定位诊断模型。
- 不启动任何定位请求。

### 阶段 2：LocationManager 单次定位与测试按钮

- 实现 `NativeLocationManager`。
- 支持权限、系统定位开关、provider 状态检查。
- 后台“测试系统定位”可用。
- 诊断脱敏展示。

### 阶段 3：短时预热

- 页面加载时按配置短时预热。
- 生命周期取消。
- WebViewPool 与配置生效处理。
- 真机对比预热效果。

### 阶段 4：标准 Geolocation bridge

- 优先用 AndroidX WebKit document-start 注入与受控消息通道。
- 实现 `getCurrentPosition`。
- 实现 `watchPosition/clearWatch`。
- 完成 iframe、页面跳转、权限、黑名单和耗电验收。

---

## 14. 资料来源

- Android `LocationManager` API：`https://developer.android.com/reference/android/location/LocationManager`
- Android 位置权限说明：`https://developer.android.com/develop/sensors-and-location/location/permissions`
- Android 请求定位权限：`https://developer.android.com/develop/sensors-and-location/location/permissions#request-location-access-runtime`
- Android `WebChromeClient.onGeolocationPermissionsShowPrompt`：`https://developer.android.com/reference/android/webkit/WebChromeClient#onGeolocationPermissionsShowPrompt(java.lang.String,android.webkit.GeolocationPermissions.Callback)`
- AndroidX WebKit `WebViewCompat.addDocumentStartJavaScript`：`https://developer.android.com/reference/androidx/webkit/WebViewCompat#addDocumentStartJavaScript(android.webkit.WebView,java.lang.String,java.util.Set%3Cjava.lang.String%3E)`
- AndroidX WebKit `WebViewCompat.addWebMessageListener`：`https://developer.android.com/reference/androidx/webkit/WebViewCompat#addWebMessageListener(android.webkit.WebView,java.lang.String,java.util.Set%3Cjava.lang.String%3E,androidx.webkit.WebViewCompat.WebMessageListener)`
