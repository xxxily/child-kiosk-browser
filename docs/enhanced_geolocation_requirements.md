# 增强网页定位能力需求调研与方案文档

> 文档版本：1.0  
> 创建日期：2026-07-02  
> 状态：需求调研与方案细化，待产品确认后进入实现  
> 关联模块：`WebViewActivity`、`WebViewRuntime`、`WebViewPool`、`KioskPrefs`、后台管理 WebView 设置、GitHub Actions APK 构建产物  
> 核心结论：高德 Android 定位 SDK 可以作为增强版定位提供者，但“集成 SDK”本身不会自动提升所有普通网页的 `navigator.geolocation` 精度。要让普通网页稳定受益，需要新增原生定位提供者抽象，并通过受控 JS bridge 接管或补强网页 Geolocation 调用。

---

## 1. 背景与问题

当前浏览器已经支持 WebView 的标准网页定位权限链路：

- Manifest 已声明 `ACCESS_COARSE_LOCATION` 与 `ACCESS_FINE_LOCATION`。
- `WebViewRuntime.applySettings()` 会根据 `limitGeolocation` 调用 `setGeolocationEnabled(...)`。
- `WebViewActivity` 通过 `WebChromeClient.onGeolocationPermissionsShowPrompt` 弹出站点定位授权确认。
- 管理后台与 Site Info Panel 已有全局禁用定位和 Origin 维度定位黑名单。
- `WebViewActivity` 运行在独立 `:webview` 进程，启动时通过 Intent 快照获取 WebView 运行配置。

用户反馈的问题是：户外定位偏差很大，导致网页应用可靠性下降。初步设想是集成高德 Android SDK，并允许用户自己填写鉴权信息；GitHub Actions 同时输出不含高德 SDK 的普通版本和包含高德 SDK 的增强版本。

这个方向合理，但需要修正两个关键点：

1. 高德鉴权信息不是通用 token，而是高德开放平台 Android SDK Key。Key 需要绑定 APK 的包名和签名 SHA1。浏览器项目不能内置公共 Key，否则会带来配额、成本、滥用和责任风险。
2. Android WebView 的标准 Geolocation 只有授权回调，没有“把坐标传给 WebView 内核”的公开 API。仅把高德 SDK 加入 APK，不会让第三方网页的 `navigator.geolocation.getCurrentPosition()` 自动改用高德结果。

因此，本需求应该从“集成高德 SDK”细化为：

> 在保持默认浏览器行为不变的前提下，为增强构建版本提供可选的高精度原生定位提供者，并通过明确、可配置、可回退、可审计的方式把原生定位结果提供给网页应用。

---

## 2. 调研结论

### 2.1 高德 Android 定位 SDK

高德 Android 定位 SDK 当前可通过 `com.amap.api:location` 获取定位、逆地理编码和地理围栏能力。根据 Maven Central 元数据，`com.amap.api:location` 最新 release 为 `11.2.000`，更新时间为 2026-06-04。实现时应锁定明确版本号，不应使用 `latest.integration`，以保证 CI 和发布构建可复现。

高德开放平台 Key 要求：

- 用户需要在高德开放平台控制台创建应用并添加 Android 平台 Key。
- Key 绑定信息包括发布版 SHA1、调试版 SHA1 和 Package。
- 1 个 Key 只能用于 1 个应用；多包名、多签名场景需要单独配置。
- 本项目 release APK 如果保持 `applicationId = site.anzz.childkiosk` 且签名证书不变，用户只需为该包名和对应 SHA1 申请 Key。

高德 SDK 隐私合规要求：

- 定位 SDK 5.6.0 起要求在调用 SDK 任意接口前先调用隐私合规相关接口，例如 `AMapLocationClient.updatePrivacyShow(...)` 与 `AMapLocationClient.updatePrivacyAgree(...)`。
- 增强版不能在用户未填写 Key、未确认隐私合规说明前初始化高德定位客户端。
- 高德下载页明确列出定位 SDK 会采集经纬度、设备信息、GNSS 信息、Wi-Fi 状态及列表、基站信息、传感器信息、OAID、应用信息、设备参数、系统信息等。儿童 kiosk 场景必须把第三方 SDK 信息收集说明作为产品合规要求。

高德 SDK 对 WebView 的适用边界：

- “新版辅助 H5 页面定位”主要服务于使用高德 JS API 的 H5 页面，通过原生定位 SDK 辅助高德 JS API 获取位置。
- 该能力不等同于替换 WebView/Chromium 对标准 `navigator.geolocation` 的底层定位来源。
- 如果目标网页没有使用高德 JS API，或者我们无法修改网页前端，仅启用高德 H5 辅助定位不一定有收益。

### 2.2 普通 WebView Geolocation 的限制

Android WebView 提供的 `onGeolocationPermissionsShowPrompt(origin, callback)` 只允许宿主决定“允许或拒绝某个 Origin 使用定位”。这个回调不能传入经纬度，也不能选择定位提供者。

如果要让普通网页的 `navigator.geolocation` 使用高德或其他原生定位结果，需要额外实现：

- 在页面足够早的时机注入脚本，覆盖或代理 `navigator.geolocation.getCurrentPosition`、`watchPosition`、`clearWatch`。
- 通过 `@JavascriptInterface` 或等价 bridge 把网页请求传给原生定位管理器。
- 原生拿到坐标后用 `WebView.evaluateJavascript(...)` 回调网页。
- 这条路径必须严格复用现有定位全局开关、Origin 黑名单、权限弹窗和 Android 系统权限判断，不能绕过家长控制。

当前项目已依赖 `androidx.webkit:webkit:1.10.0`，后续可评估使用 `WebViewCompat.addDocumentStartJavaScript` 与 `WebViewFeature.DOCUMENT_START_SCRIPT` 在 document start 注入桥接脚本；不支持该特性的 WebView provider 再降级到现有 `onPageStarted/onPageFinished` 注入路径，但需要标记为兼容性较弱。

### 2.3 不集成商业 SDK 的可选方案

不集成高德、百度、腾讯、Google Play Services 等商业或闭源定位 SDK 时，仍有一些改进空间，但边界有限。

| 方案 | 是否新增外部 SDK | 对户外精度的帮助 | 主要问题 | 结论 |
|---|---:|---|---|---|
| 系统 `LocationManager` 高精度请求 | 否 | 户外 GNSS 精度可较高 | 冷启动慢；网络辅助能力取决于系统；无法直接喂给 WebView 内核 | 应作为标准版 P0 兜底优化 |
| 系统可用的 fused provider | 否 | 如果 ROM 提供融合定位，体验可能较好 | 设备差异大；不是所有设备都有可靠 provider | 可探测后使用，不应承诺 |
| Google Play Services FusedLocationProviderClient | 是 | 有 GMS 设备上通常好 | 不是开源；国内/无 GMS 设备不可依赖 | 不作为本需求默认方向 |
| microG UnifiedNlp | 不进 APK，但依赖系统集成 | 可改善无 GMS 网络定位 | 普通 App 无法把它作为内置库可靠接管系统定位；常需 ROM/系统级支持 | 只做设备环境建议，不做应用内集成 |
| OpenCellID / beaconDB / 自建 Wi-Fi/基站定位 | 通常需要 API/数据服务 | 可做粗定位 | 覆盖率、精度、隐私、配额、上传合规和服务维护成本高 | 不建议作为当前产品内置方案 |
| 传感器融合/步行航位推算 | 否或轻依赖 | 能改善连续轨迹平滑 | 不能解决绝对位置偏差；实现复杂，业务相关 | 非当前阶段目标 |

建议：

1. 标准版也应增加“系统原生高精度定位提供者”抽象，用 `LocationManager` 主动获取位置，并用统一逻辑判断新鲜度、精度和超时。
2. 增强版在同一抽象下新增高德实现。这样不用把“是否使用高德”写死在 WebView 权限链路里，也便于以后接入其他外部能力。
3. 如果用户的网页应用可以修改前端，优先推荐业务页使用明确的原生 bridge 或高德 JS API，而不是依赖 WebView 内核默认定位行为。

---

## 3. 产品目标

### 3.1 P0 目标

- 默认版行为保持不变：未安装增强版或未配置增强定位时，网页定位仍走现有 WebView/系统定位链路。
- 增强版新增高德定位 SDK 能力，但不内置高德 Key。
- 用户未填写有效高德 Key 时，高德相关能力不可用，并自动回退到系统默认定位。
- 后台提供清晰的“定位增强”配置入口，能看到当前 APK 是否包含外部定位能力。
- GitHub Actions 输出两条发行线：
  - `standard`：不包含高德 SDK，与当前依赖面基本一致。
  - `enhanced`：包含高德 SDK 与后续可选外部能力。
- 任何新增定位能力都必须继续尊重全局禁用定位、站点黑名单、系统权限和家长授权弹窗。

### 3.2 P1 目标

- 对普通网页提供可选的标准 Geolocation bridge，使 `navigator.geolocation` 可以使用原生高精度定位结果。
- 支持高德 H5 辅助定位，服务于使用高德 JS API 的网页。
- 支持按站点控制增强定位是否启用，避免把儿童设备精确位置暴露给未知网页。
- 提供诊断信息，显示当前网页定位路径是系统 WebView、系统原生 provider、高德 provider、还是回退状态。

### 3.3 P2 目标

- 支持多定位提供者策略，例如高德、系统 GNSS、系统 fused provider 的优先级、超时和回退组合。
- 支持 per-site 坐标系配置与兼容性策略。
- 支持定位质量统计，用于家长或开发者诊断“为什么此站点定位慢/偏/失败”。

---

## 4. 非目标

当前需求不做以下事项：

- 不内置任何公共高德 Key。
- 不绕过高德开放平台配额、鉴权、隐私政策或服务条款。
- 不新增后台持续定位或 `ACCESS_BACKGROUND_LOCATION`。
- 不做儿童轨迹记录、轨迹上传、围栏告警或家长监控平台。
- 不默认把精确位置暴露给所有网页。
- 不为了定位能力弱化 Device Owner、Lock Task、隐藏家长验证、安全退出、站点权限黑名单等 kiosk 安全机制。
- 不改变真实网页生产承载路径，仍保持 `WebViewActivity -> FrameLayout -> WebView`。
- 不引入地图显示、路线规划、POI 搜索等高德地图能力；本需求只关注定位。

---

## 5. 构建与分发需求

### 5.1 Gradle 变体

建议增加 product flavor，而不是用 buildType 区分是否包含高德：

```kotlin
flavorDimensions += "distribution"

productFlavors {
    create("standard") {
        dimension = "distribution"
    }
    create("enhanced") {
        dimension = "distribution"
    }
}
```

推荐 release 包保持同一个 `applicationId = site.anzz.childkiosk`，理由：

- 标准版和增强版是同一产品的不同能力包，不需要并行安装。
- 用户升级到增强版时可复用现有应用数据。
- 高德 Key 只需绑定同一包名和同一签名证书。

如未来确实需要标准版和增强版并行安装，再单独评估 `applicationIdSuffix`，但这会增加高德 Key、数据迁移、Device Owner 管理和用户理解成本。

### 5.2 代码组织

建议把共享接口放在 `src/main`，外部 SDK 实现只放在 `src/enhanced`：

- `src/main/java/.../location/GeolocationProvider.kt`
- `src/main/java/.../location/SystemGeolocationProvider.kt`
- `src/main/java/.../location/ExternalGeolocationProvider.kt`：接口或 no-op 工厂。
- `src/standard/java/.../location/AmapGeolocationProvider.kt`：no-op，占位实现，不引用高德类。
- `src/enhanced/java/.../location/AmapGeolocationProvider.kt`：真实高德实现。

`standard` 变体必须做到：

- APK 内不包含 `com.amap.*` 类。
- Gradle dependency graph 不出现 `com.amap.api:location`。
- UI 可以显示“当前版本不包含高德定位 SDK”，但不能展示可误操作的高德开关。

`enhanced` 变体必须做到：

- 只有增强版依赖高德 SDK。
- 高德 SDK 版本锁定，例如 `com.amap.api:location:11.2.000`，具体版本以实现时官方最新稳定版为准。
- 如果高德 SDK 需要额外 ProGuard/R8 keep 规则，必须只对增强版生效。

### 5.3 GitHub Actions 输出

现有 workflow 只构建 `:app:assembleRelease` 与 `:app:assembleDebug`。新增 flavor 后应输出：

- `child-kiosk-browser-${VERSION_NAME}-standard-release.apk`
- `child-kiosk-browser-${VERSION_NAME}-standard-debug.apk`
- `child-kiosk-browser-${VERSION_NAME}-enhanced-release.apk`
- `child-kiosk-browser-${VERSION_NAME}-enhanced-debug.apk`

建议命令：

```bash
./gradlew :app:assembleStandardRelease :app:assembleEnhancedRelease --stacktrace
./gradlew :app:assembleStandardDebug :app:assembleEnhancedDebug --stacktrace
```

Release 页面和 `docs/RELEASE_NOTES.md` 需要明确：

- standard 版本：不包含高德 SDK，定位行为与默认系统/WebView 路径一致。
- enhanced 版本：包含外部定位增强能力，但仍需用户自行配置高德 Key 并同意隐私合规说明。

---

## 6. 后台配置需求

### 6.1 配置入口

在后台 WebView/网页能力设置区域新增“定位增强”卡片。该卡片必须适配竖屏、横屏、窄屏和短屏，内容超高时可滚动，操作按钮不可被系统键盘遮挡。

标准版显示：

- 当前版本：标准版。
- 高德定位 SDK：未集成。
- 可用能力：系统默认网页定位、系统原生定位诊断。
- 引导文案：如需高德定位能力，请安装 enhanced APK，并自行配置高德开放平台 Key。

增强版显示：

- 当前版本：增强版。
- 高德定位 SDK：已集成，显示 SDK 版本。
- 高德 Key：输入框，默认空。
- 隐私合规确认：管理员确认已阅读并向使用者/监护人披露第三方 SDK 信息收集说明。
- 增强定位总开关：默认关闭；Key 空或未确认合规时置灰。
- 网页接入方式：
  - 仅使用 WebView 默认定位。
  - 启用高德 H5 辅助定位。
  - 对标准 `navigator.geolocation` 启用原生 bridge。
- 定位策略：
  - 兼容优先：较短超时，失败快速回退。
  - 高精度户外：更长超时，优先等待高精度结果。

### 6.2 Key 存储与展示

- Key 由用户自行填写，项目不提供默认值。
- Key 不写入源码、构建脚本、GitHub Actions secret 或 release notes。
- 日志、诊断文本、崩溃信息、复制调试信息中必须脱敏，只显示空/已设置/末尾 4 位。
- 配置导出如未来支持，默认不导出 Key；如用户明确选择导出，也必须有风险提示。
- Key 修改后，对新打开的网页生效；当前 WebViewActivity 若已经初始化高德客户端，不承诺即时替换，需要显示“新打开的网站生效”。

### 6.3 与现有定位权限设置的关系

现有“禁用网页定位 (Geolocation)”仍是最高优先级：

1. 如果全局禁用定位，所有系统、高德和 bridge 定位都必须拒绝。
2. 如果当前 Origin 在 `geolocationBlacklist`，直接拒绝，不初始化高德定位。
3. 如果 Android 系统定位权限未授予，先走现有系统权限请求流程。
4. 只有全局允许、Origin 未黑名单、系统权限已授予或即将请求时，才进入增强定位流程。

这保证增强定位不会绕过已有儿童隐私控制。

---

## 7. 技术方案

### 7.1 定位提供者抽象

新增统一定位接口：

```kotlin
interface NativeGeolocationProvider {
    val id: String
    fun isAvailable(context: Context, config: WebViewRuntimeConfig): Boolean
    suspend fun getCurrentLocation(request: NativeLocationRequest): NativeLocationResult
    fun startWatch(request: NativeLocationWatchRequest, callback: (NativeLocationResult) -> Unit): String
    fun stopWatch(watchId: String)
    fun destroy()
}
```

核心数据字段：

- 纬度、经度。
- 精度米数。
- 海拔、速度、方向，如果 provider 支持。
- 时间戳。
- provider 来源：`webview_default`、`system_gps`、`system_network`、`system_fused`、`amap`。
- 坐标系：`WGS84`、`GCJ02`、`UNKNOWN`。
- 错误码和可读诊断。

### 7.2 系统原生定位 provider

标准版和增强版都应有系统原生 provider：

- Android 11+ 优先使用 `LocationManager.getCurrentLocation(...)`。
- Android 9/10 使用 `requestLocationUpdates(...)` + 超时取消。
- 优先请求 `GPS_PROVIDER` 或系统可用 fused provider，按设备能力回退到 `NETWORK_PROVIDER`。
- 读取 last known location 只能作为快速候选；必须检查新鲜度和精度，不得把很旧的位置当成成功。
- 支持超时，例如兼容优先 8-10 秒，高精度户外 20-30 秒。
- 所有定位请求必须在 Activity 生命周期结束时取消。

该 provider 不会引入外部 SDK，但可以减少 WebView 内核默认定位不可控、超时不可诊断的问题。

### 7.3 高德 provider

增强版实现 `AmapGeolocationProvider`：

- 只有 Key 非空、隐私合规确认完成、全局定位未禁用、系统权限满足时才初始化。
- 调用高德隐私合规接口必须早于 `AMapLocationClient` 实例化。
- 使用高精度模式，例如 `AMapLocationMode.Hight_Accuracy`。
- 单次定位优先使用单次定位接口；持续定位只在网页调用 `watchPosition` 时启用。
- 停止网页 watch 或销毁 WebView 时必须 `stopLocation()` 并释放客户端。
- 定位失败时保留高德错误码、错误描述、provider 和耗时，用于诊断。

权限要求实现前需以官方当前文档为准。除现有 `INTERNET`、`ACCESS_COARSE_LOCATION`、`ACCESS_FINE_LOCATION` 外，如果高德 SDK 要求 `ACCESS_NETWORK_STATE`、`ACCESS_WIFI_STATE` 等权限，必须在需求实现 PR 中说明用途与隐私影响。不得为了兼容旧示例而无条件添加 `READ_PHONE_STATE` 等高敏权限。

### 7.4 高德 H5 辅助定位

增强版可对使用高德 JS API 的网页启用“高德 H5 辅助定位”：

- 在 WebView 创建后、页面加载前，为该 WebView 调用高德 SDK 的辅助 H5 定位入口。
- 该能力只在增强版、高德 Key 有效、增强定位开启时启用。
- 离开页面、销毁 WebView、回收 WebViewPool 时必须停止辅助定位。
- UI 文案必须明确：该能力主要面向使用高德 JS API 的网页，不保证普通 `navigator.geolocation` 页面受益。

### 7.5 标准 Geolocation JS bridge

为普通网页提升定位可靠性，需要新增受控 JS bridge。建议作为 P1，默认关闭或按站点开启。

功能要求：

- 覆盖 `navigator.geolocation.getCurrentPosition(success, error, options)`。
- 覆盖 `navigator.geolocation.watchPosition(success, error, options)`。
- 实现 `navigator.geolocation.clearWatch(id)`。
- 保持回调结构尽量符合 W3C Geolocation API：
  - `coords.latitude`
  - `coords.longitude`
  - `coords.accuracy`
  - `coords.altitude`
  - `coords.altitudeAccuracy`
  - `coords.heading`
  - `coords.speed`
  - `timestamp`
- 支持超时、最大年龄和高精度请求参数的近似映射。
- 网页回调必须回到同一个 WebView 和同一个页面请求上下文，避免页面跳转后把旧位置回调给新页面。

安全要求：

- 只对 `http` 和 `https` 页面启用。
- 必须复用 `requestGeolocationPermission(...)` 的 Origin 授权链路。
- 命中全局禁用或黑名单时，bridge 返回 Geolocation API 错误，不得静默成功。
- 禁止把高德 Key、SDK 错误详情、设备信息暴露给网页。
- 每个 WebView 限制并发请求数和 watch 数，防止恶意页面高频定位耗电。

坐标系要求：

- 标准 `navigator.geolocation` 语义应返回 WGS84。
- 高德在中国大陆定位结果通常面向 GCJ-02/高德坐标体系。实现必须明确识别或转换坐标系。
- 默认不得把 GCJ-02 坐标伪装成 WGS84 返回给标准 API。
- 如业务网页明确依赖中国地图坐标，可后续增加 per-site “GCJ-02 兼容模式”，但 UI 必须清晰说明它不是标准浏览器语义。

### 7.6 WebViewPool 与进程配置

增强定位相关配置会影响 WebView 创建、脚本注入、辅助 H5 定位和原生 provider 绑定。因此：

- `WebViewRuntimeConfig` 需要新增增强定位配置字段，并通过 `toJson/fromJson` 传入 `:webview` 进程。
- `WebViewPool` 的 `poolKey()` 必须包含这些字段，避免复用带旧定位 bridge 或旧辅助定位状态的 WebView。
- 修改增强定位开关、Key、网页接入方式或坐标系策略时，应清理或绕过旧 WebViewPool。
- 当前已打开 WebView 是否即时生效要按能力区分：
  - 黑名单修改：同一 Activity 内应立即生效。
  - 新增/关闭 JS bridge、H5 辅助定位、Key 修改：新打开网页生效。
  - 高德 SDK 初始化失败：当前请求回退，后台展示诊断。

---

## 8. 隐私、合规与儿童安全

增强定位比普通网页定位更敏感，尤其本项目面向儿童 kiosk 场景。需求实现必须满足：

- 增强版首次启用高德定位前，后台显示第三方 SDK 名称、服务商、使用目的、收集信息类型和隐私政策链接。
- 管理员必须主动确认，不能默认勾选。
- 不做后台定位，不记录连续轨迹。
- 不把定位历史写入 Room、SharedPreferences 或日志，除非后续另立需求并完成隐私评审。
- 诊断信息只记录定位来源、是否成功、耗时、精度范围、错误码；不记录完整经纬度。必要时可四舍五入或脱敏。
- 对非白名单/未知站点，建议默认只允许系统 WebView 定位授权，不默认启用高精度 bridge。
- 儿童模式下，如果网页请求定位，应继续走家长授权或已配置站点策略，不应因增强定位而减少提示。

---

## 9. 诊断与可观测性

后台和调试日志应能回答以下问题：

- 当前 APK 是 standard 还是 enhanced。
- 当前是否包含高德 SDK。
- 高德 Key 是否已设置，不显示完整 Key。
- 隐私合规确认是否完成。
- 当前网页定位路径：
  - WebView 默认定位。
  - 系统原生 provider。
  - 高德 provider。
  - 高德 H5 辅助定位。
  - JS bridge。
  - 回退到系统默认。
- 最近一次定位是否成功、耗时、精度、provider、错误码。
- 当前 Origin 是否被全局禁用或黑名单拦截。
- 当前 WebView provider 是否支持 document-start 注入。

日志要求：

- 使用 `ChildKioskWebView` 或独立 tag，例如 `ChildKioskLocation`。
- 默认 release 日志不输出经纬度。
- 复制诊断信息时脱敏 Key 和坐标。

---

## 10. 验收标准

### 10.1 构建验收

- `./gradlew :app:assembleStandardDebug :app:assembleEnhancedDebug` 成功。
- `./gradlew :app:assembleStandardRelease :app:assembleEnhancedRelease` 成功。
- GitHub Actions 上传 4 个 APK，文件名包含 `standard/enhanced` 和 `debug/release`。
- standard APK 依赖图与 APK 内容中不包含高德 SDK。
- enhanced APK 包含高德定位 SDK，且版本可在后台诊断中查看。

### 10.2 配置验收

- standard 版本后台显示“不包含高德定位 SDK”，不能误导用户输入 Key 后即可启用。
- enhanced 版本 Key 为空时，高德定位开关置灰或启用后自动提示“请先填写高德 Key”。
- Key 已设置但高德鉴权失败时，网页定位不崩溃，回退到系统定位或返回可解释错误。
- Key、隐私合规确认、增强定位开关通过 `WebViewRuntimeConfig` 传入 `:webview` 进程。
- 修改增强定位配置后，提示“新打开的网站生效”，并清理受影响的 WebViewPool。

### 10.3 权限与安全验收

- 全局“禁用网页定位”开启时，高德 provider、系统 provider、H5 辅助定位和 JS bridge 都不可用。
- Origin 在定位黑名单时，不触发高德定位，不弹高德相关请求，直接拒绝。
- Android 系统定位权限被拒绝时，不自动把站点加入黑名单。
- 关闭页面、返回主页、WebViewActivity 销毁时，所有定位 watch 停止。
- release 日志和复制诊断中不包含完整 Key 或精确经纬度。

### 10.4 功能验收

- 使用普通 `navigator.geolocation.getCurrentPosition` 的测试页：
  - 默认模式行为与当前版本一致。
  - 开启标准 Geolocation bridge 后，成功返回原生 provider 结果。
  - 超时、权限拒绝、黑名单拦截时返回合理错误。
- 使用 `watchPosition` 的测试页：
  - 能连续收到位置更新。
  - `clearWatch` 后停止原生定位。
  - 页面关闭后停止定位。
- 使用高德 JS API 的测试页：
  - enhanced 版本启用 H5 辅助定位后，高德 JS API 可获得原生高德定位增强。
  - standard 版本不崩溃，只走页面原有逻辑。
- 户外实测：
  - 至少对比系统默认 WebView、系统原生 provider、高德 provider 三条路径的首次定位耗时、精度和失败率。
  - 实测结果写入后续实现报告或 release note，不在需求文档中预设无法保证的精度承诺。

---

## 11. 推荐实施顺序

### 阶段 1：构建变体与配置骨架

- 增加 `standard/enhanced` product flavors。
- 调整 GitHub Actions 输出 4 个 APK。
- 增加后台“定位增强”卡片，只显示构建能力和占位配置。
- 增加 `WebViewRuntimeConfig` 字段和 WebViewPool key 变更。

### 阶段 2：系统原生定位 provider

- 实现不依赖外部 SDK 的 `SystemGeolocationProvider`。
- 增加定位诊断，不接管网页 Geolocation。
- 用测试页和真机验证系统 provider 与 WebView 默认定位差异。

### 阶段 3：增强版高德 provider

- 只在 `enhanced` source set 中接入高德 SDK。
- 实现 Key 输入、隐私合规确认、高德单次定位。
- 高德失败时回退系统 provider。
- 先不覆盖普通网页 `navigator.geolocation`，只提供诊断和内部 provider 能力。

### 阶段 4：WebView 网页接入

- 启用高德 H5 辅助定位。
- 评估并实现标准 Geolocation JS bridge。
- 以 per-site 或显式开关控制 bridge 覆盖范围。
- 完成安全、生命周期、坐标系和兼容性验证。

---

## 12. 资料来源

- 高德开放平台 Android 定位 SDK 概述：`https://lbs.amap.com/api/android-location-sdk/locationsummary/`
- 高德开放平台 Android 定位 SDK 获取 Key：`https://lbs.amap.com/api/android-location-sdk/guide/create-project/get-key`
- 高德开放平台 Android 定位 SDK 获取定位数据：`https://lbs.amap.com/api/android-location-sdk/guide/android-location/getlocation`
- 高德开放平台 Android 定位 SDK 新版辅助 H5 页面定位：`https://lbs.amap.com/api/android-location-sdk/guide/android-location/new-assistant_location`
- 高德开放平台 Android 定位 SDK 相关下载与隐私信息：`https://lbs.amap.com/api/android-location-sdk/download`
- Maven Central `com.amap.api:location` 元数据：`https://repo1.maven.org/maven2/com/amap/api/location/maven-metadata.xml`
- Android `WebChromeClient.onGeolocationPermissionsShowPrompt`：`https://developer.android.com/reference/android/webkit/WebChromeClient#onGeolocationPermissionsShowPrompt(java.lang.String,android.webkit.GeolocationPermissions.Callback)`
- Android `LocationManager`：`https://developer.android.com/reference/android/location/LocationManager`
- AndroidX WebKit `WebViewCompat.addDocumentStartJavaScript`：`https://developer.android.com/reference/androidx/webkit/WebViewCompat#addDocumentStartJavaScript(android.webkit.WebView,java.lang.String,java.util.Set%3Cjava.lang.String%3E)`
- microG UnifiedNlp：`https://github.com/microg/UnifiedNlp`
- OpenCellID：`https://www.opencellid.org/`
- beaconDB：`https://beacondb.net/`
- Mozilla Location Service 退役说明：`https://github.com/mozilla/ichnaea/issues/2065`
