# 高德 Android SDK 托管网页定位需求文档

> 文档版本：1.0  
> 创建日期：2026-07-04  
> 状态：方案评估与需求细化，待产品确认后进入实现  
> 关联模块：`WebViewActivity`、`NativeLocationManager`、`WebViewRuntimeConfig`、`WebViewPool`、后台网页定位设置、高德增强构建  
> 核心结论：方案可行，但不建议“实例化一个高德地图只为拿定位”。推荐以高德 Android 定位 SDK 的 `AMapLocationClient` 作为原生定位 provider，再通过现有受控 Geolocation bridge 把定位结果返回给可信网页。高德 JS SDK 只在网页本身使用高德 JS API 或需要高德官方 H5 辅助定位链路时才是必要条件；如果我们自建 `navigator.geolocation` hook，普通网页不需要接入高德 JS SDK。

---

## 1. 问题结论

### 1.1 为什么接入高德 Android SDK 后，还会提到高德 JS SDK

这里有两条不同链路，容易混在一起：

1. **高德官方 H5 辅助定位链路**
   - App 集成高德 Android 定位 SDK。
   - 宿主对 `WebView` 调用 `AMapLocationClient.startAssistantLocation(webView)`。
   - H5 页面加载高德 JS API，并使用 `AMap.Geolocation({ useNative: true })`。
   - 这条链路服务的是“使用高德 JavaScript API 实现的 H5 页面”，高德 JS SDK 负责在页面侧发起和接收高德定义的定位调用。

2. **浏览器自建标准 Geolocation bridge 链路**
   - App 集成高德 Android 定位 SDK，或复用系统 `LocationManager`。
   - WebView 在 document-start 注入脚本，覆盖或代理 `navigator.geolocation.getCurrentPosition`、`watchPosition`、`clearWatch`。
   - 页面调用标准 Web API 时，由注入脚本把请求发给原生，原生拿到位置后回调页面。
   - 这条链路不依赖高德 JS SDK，适合普通第三方网页或我们无法改造前端代码的网页。

因此，“接入高德 Android SDK 后还需要接入高德 JS SDK”并不是普遍成立的结论。准确说法是：

- 如果目标是使用高德官方 H5 辅助定位，页面侧必须使用高德 JS API，并设置 `AMap.Geolocation({ useNative: true })`。
- 如果目标是让普通网页的标准 `navigator.geolocation` 拿到高德 Android 定位结果，可以不接高德 JS SDK，但必须实现并启用我们自己的 JS hook / bridge。
- 只把高德 Android SDK 加入 APK，不会自动改变 Android WebView/Chromium 的标准 Geolocation 数据源。

### 1.2 通过高德 Android 地图实例拿准确定位是否可行

技术上可行，但不是推荐实现。

高德 Android 地图 SDK 的定位蓝点能力可以通过 `AMap.OnMyLocationChangeListener` 收到 `android.location.Location`，地图 SDK 文档也说明定位蓝点可以获取经纬度信息。也就是说，如果实例化一个 `MapView`/`AMap` 并打开定位蓝点，确实可以拿到定位回调。

但本项目不建议把“地图实例”作为网页定位的数据源：

- 地图 SDK 是 UI 和地图渲染能力，定位只是地图蓝点的一部分；为了定位创建地图实例，依赖更重、生命周期更复杂。
- 本项目真实网页必须继续走 `WebViewActivity -> FrameLayout -> WebView`，不应额外引入不可见地图视图作为定位服务。
- 地图实例通常绑定 Activity/View 生命周期，和网页 `getCurrentPosition`、`watchPosition` 的请求模型不完全一致。
- 高德 Android 定位 SDK 已经提供独立定位客户端 `AMapLocationClient`，支持单次定位、连续定位、高精度/低功耗/GPS 等模式，更适合做 provider。
- 地图蓝点拿到的坐标同样需要处理权限、隐私合规、停止定位、坐标系、错误码和耗电控制，不能减少 bridge 的复杂度。

推荐判断：

> 不要通过“实例化一个高德地图”来托管网页定位。应通过高德 Android 定位 SDK 实现 `AmapNativeLocationProvider`；地图 SDK 只在未来真的需要展示地图 UI 时接入。

### 1.3 通过 JS hook 触发高德 Android SDK 定位是否可行

可行，而且和当前项目已有的系统 `LocationManager` bridge 架构匹配。

当前项目已经有系统原生定位托管能力：

- `NativeLocationManager` 支持单次定位、预热、watch 和诊断。
- `WebViewActivity` 已有 `ChildKioskNativeLocation` WebMessage bridge。
- `nativeLocationBridgeScript()` 已覆盖标准 `navigator.geolocation` API。
- `WebViewRuntimeConfig` 已通过 Intent 快照传入 `:webview` 进程。
- `WebViewPool` 已把 runtime config 纳入 pool key，避免旧 WebView 复用旧定位注入状态。

高德接入可以作为新的 provider 插入这条链路：

```text
网页 navigator.geolocation
  -> document-start JS hook
  -> WebMessage bridge
  -> WebViewActivity 权限/Origin 决策
  -> NativeLocationProviderRouter
  -> AmapNativeLocationProvider / SystemLocationProvider
  -> evaluateJavascript 回调网页 success/error
```

这条链路的关键不是“拿得到高德定位”，而是能否安全、及时、符合标准 API 语义地返回给网页。实现时必须特别处理权限、Origin 白名单、页面跳转后的旧请求、watch 停止、坐标系和隐私日志。

---

## 2. 推荐产品形态

### 2.1 两种网页接入模式

后台“定位增强”应明确区分两种模式：

| 模式 | 页面是否需要高德 JS SDK | 适用页面 | 原生侧能力 | 推荐级别 |
|---|---:|---|---|---|
| 高德 H5 辅助定位 | 需要 | 已经使用高德 JS API 的业务 H5 | `startAssistantLocation(webView)` | 可选兼容能力 |
| 标准 Geolocation 托管 | 不需要 | 普通网页、无法改造的第三方页面、只调用 `navigator.geolocation` 的页面 | 自建 JS hook + 原生 provider | 主推荐能力 |

后台文案建议：

- “高德 H5 辅助定位”：仅对使用高德 JS API 的网页有效，页面需使用 `AMap.Geolocation({ useNative: true })`。
- “托管标准网页定位”：对允许列表站点接管 `navigator.geolocation`，由原生定位 provider 返回结果。

### 2.2 Provider 策略

新增 provider 路由，而不是把高德写死在 bridge 里：

```kotlin
interface NativeLocationProvider {
    val id: String
    fun isAvailable(config: WebViewRuntimeConfig): Boolean
    fun requestCurrent(request: NativeLocationRequest, callback: (NativeLocationResult) -> Unit): String
    fun startWatch(request: NativeLocationWatchRequest, callback: (NativeLocationResult) -> Unit): String
    fun cancel(id: String)
    fun stopAll()
    fun destroy()
}
```

Provider 建议：

- `SystemLocationProvider`：当前 `NativeLocationManager` 的能力，作为所有版本默认 provider。
- `AmapLocationProvider`：只在增强版存在，使用 `AMapLocationClient`。
- `NativeLocationProviderRouter`：根据后台策略、Key 状态、隐私确认、Origin 策略和错误状态选择 provider。

策略建议：

| 策略 | 行为 |
|---|---|
| 系统默认 | 不启用 bridge，网页仍走 WebView 默认定位 |
| 系统原生托管 | bridge 使用 `LocationManager` |
| 高德优先，系统回退 | 优先 `AmapLocationProvider`，失败或超时后回退系统 provider |
| 高德 only | 只对测试/特定可信站点开放，高德失败即返回错误 |

默认建议为“高德优先，系统回退”，但只对允许列表 Origin 生效。

---

## 3. P0 需求：高德 provider 接入

### 3.1 构建与依赖

延续旧文档中的增强版思路：

- `standard` flavor 不包含高德 SDK。
- `enhanced` flavor 包含高德 Android 定位 SDK。
- 不为了定位引入 Android 地图 SDK，除非后续明确需要显示地图 UI。
- 高德 SDK 版本必须锁定，不使用动态版本。
- 高德 Key 不写入源码、Gradle、GitHub Actions secret 或 release notes。

推荐 source set：

```text
src/main/.../location/NativeLocationProvider.kt
src/main/.../location/SystemLocationProvider.kt
src/main/.../location/NativeLocationProviderRouter.kt
src/standard/.../location/AmapLocationProvider.kt   // no-op
src/enhanced/.../location/AmapLocationProvider.kt   // real implementation
```

### 3.2 高德 Key 与隐私合规

后台增强版配置：

- 高德 Android SDK Key 输入框，默认空。
- Key 状态只显示“未设置/已设置/尾号 4 位”，日志和诊断必须脱敏。
- 隐私合规确认开关，未确认前不能初始化高德 SDK。
- 第三方 SDK 信息说明：服务商、使用目的、采集类型、隐私政策链接。
- Key 或隐私确认变更后提示“新打开的网站生效”。

初始化规则：

- 必须在调用高德 SDK 任意接口前完成隐私合规接口调用。
- Key 为空、隐私未确认、全局禁用定位、Origin 命中黑名单时，不初始化高德定位客户端。
- 高德鉴权失败不能导致网页崩溃，应回退系统 provider 或返回可解释错误。

### 3.3 `AmapLocationProvider`

单次定位：

- 使用 `AMapLocationClient`。
- 使用 `AMapLocationClientOption`。
- 默认 `Hight_Accuracy`。
- 支持 `setOnceLocation(true)` 或 `setOnceLocationLatest(true)`。
- 支持 timeout，超时必须停止定位并释放请求。
- 返回字段映射到 `NativeLocationResult`。

连续定位：

- 只在网页 `watchPosition` 时开启。
- 设置合理 interval，最小间隔按高德 SDK 限制和产品耗电策略执行。
- `clearWatch`、页面关闭、WebViewActivity `onStop/onDestroy` 必须停止定位。

错误与诊断：

- 记录高德错误码、错误信息、耗时、精度、定位类型。
- release 日志不输出完整经纬度。
- 诊断面板显示 provider=`amap`、是否回退、失败原因。

---

## 4. P1 需求：标准 Geolocation bridge 使用高德结果

### 4.1 复用现有 bridge

当前系统 `LocationManager` bridge 已覆盖：

- `navigator.geolocation.getCurrentPosition`
- `navigator.geolocation.watchPosition`
- `navigator.geolocation.clearWatch`
- document-start 注入能力检测
- WebMessage listener
- per-origin 允许列表
- 页面关闭时取消请求

高德接入不应重写这套脚本，应该只替换 provider 层：

```text
nativeLocationBridgeGetCurrentPosition()
  -> NativeLocationProviderRouter.requestCurrent()
  -> AmapLocationProvider 或 SystemLocationProvider
```

### 4.2 权限决策顺序

任何 provider 都必须遵守同一决策顺序：

1. 全局“禁用网页定位”开启：拒绝。
2. Origin 命中 `geolocationBlacklist`：拒绝。
3. bridge 未启用或当前 Origin 不在允许列表：不接管或拒绝。
4. Android 系统定位开关关闭：返回 `POSITION_UNAVAILABLE`。
5. Android 定位权限未授予：走现有权限申请/家长授权流程。
6. 高德 Key/隐私/SDK 初始化不可用：按策略回退系统 provider 或返回错误。
7. provider 成功返回后，按标准 Geolocation 回调给页面。

### 4.3 坐标系要求

这是高德托管标准 Web API 的最大风险点之一。

高德定位 SDK 文档说明：国内返回高德类型坐标，海外返回 GPS 坐标。标准 `navigator.geolocation` 语义通常应返回 WGS84 坐标。直接把 GCJ-02/高德坐标伪装成 WGS84，可能导致非高德地图或通用业务系统出现偏移。

需求要求：

- `NativeLocationResult` 增加 `coordinateSystem` 字段：`WGS84`、`GCJ02`、`UNKNOWN`。
- 高德 provider 在中国大陆结果标记为 `GCJ02`，海外结果标记为 `WGS84` 或 `UNKNOWN`。
- 标准 `navigator.geolocation` 默认返回 WGS84；国内高德结果必须转换后再返回，或在后台明确开启“GCJ-02 兼容模式”。
- 若开启“GCJ-02 兼容模式”，只允许 per-site 配置，并显示“适用于高德/国内地图坐标系，非标准浏览器语义”。
- 诊断面板必须显示当前返回坐标系和是否发生转换。

### 4.4 页面生命周期与并发

- 每个 WebView 限制并发单次定位数量。
- 每个 WebView 限制 watch 数量，例如最多 3 个。
- 页面跳转、刷新、返回主页、WebView 回收入池时，取消旧请求。
- WebViewPool 复用前必须清理 bridge 请求和高德辅助定位状态。
- bridge payload 带 request id，旧页面请求不得回调到新页面。

---

## 5. P2 需求：高德 H5 辅助定位

高德 H5 辅助定位作为兼容模式，不作为普通网页定位的主方案。

启用条件：

- enhanced 版本。
- 高德 Key 已配置。
- 隐私合规已确认。
- 全局定位未禁用。
- 当前 Origin 未命中定位黑名单。
- 当前站点在“高德 H5 辅助定位允许列表”中。

实现要求：

- WebView 创建后、页面加载前调用 `locationClient.startAssistantLocation(webView)`。
- 官方建议尽量在设置 WebView 属性之前启动辅助定位；本项目需要评估和当前 `WebViewActivity -> FrameLayout -> WebView` 创建顺序的兼容性。
- 页面结束、WebView 销毁、WebViewPool 回收前调用 `stopAssistantLocation()`。
- UI 文案必须说明：该能力只对使用高德 JS API 且使用 `AMap.Geolocation({ useNative: true })` 的页面有效。

不承诺：

- 不承诺普通 `navigator.geolocation` 页面会受益。
- 不承诺未加载高德 JS SDK 的页面会受益。
- 不承诺可以替代我们自建的标准 Geolocation bridge。

---

## 6. 不推荐方案：不可见地图实例取定位

不推荐实现“创建不可见高德地图实例 -> 监听蓝点位置 -> 回传网页”。

理由：

- 地图 SDK 定位蓝点用于地图 UI，不是通用定位服务抽象。
- 创建地图实例会引入额外渲染、生命周期和资源管理成本。
- 与本项目真实网页承载路径无关，增加排查复杂度。
- 无法绕过 JS bridge、权限、Origin、坐标系、watch 管理等核心问题。
- 高德定位 SDK 已经提供更直接的定位接口。

唯一可接受场景：

- 未来新增“原生地图展示”功能，地图实例本身就是用户可见功能。
- 在该可见地图功能中，顺带把 `OnMyLocationChangeListener` 结果用于页面诊断或特定业务。
- 即便如此，也不能作为普通网页 Geolocation bridge 的主 provider。

---

## 7. 后台配置需求

增强版“定位增强”卡片建议配置：

| 配置项 | 默认值 | 生效范围 | 说明 |
|---|---|---|---|
| 高德定位 SDK | 只读 | 当前 APK | 显示未集成/已集成/版本 |
| 高德 Key | 空 | 新打开网页 | 用户自行填写，脱敏展示 |
| 隐私合规确认 | 未确认 | 新打开网页 | 未确认不得初始化 SDK |
| 增强定位总开关 | 关闭 | 新打开网页 | 控制高德 provider 和高德 H5 辅助定位 |
| provider 策略 | 高德优先，系统回退 | 新请求 | 可选系统原生/高德优先/高德 only |
| 托管标准 Geolocation | 关闭 | 新打开网页 | 使用 JS hook 接管 `navigator.geolocation` |
| 标准托管允许 Origin | 空 | 尽量即时 | 只对可信站点启用 |
| 坐标系模式 | 标准 WGS84 | 新请求 | 可 per-site 开启 GCJ-02 兼容 |
| 高德 H5 辅助定位 | 关闭 | 新打开网页 | 只面向高德 JS API 页面 |
| H5 辅助定位允许 Origin | 空 | 新打开网页 | 不对未知站点默认启用 |
| 定位诊断 | 手动刷新 | 当前状态 | 脱敏显示 provider/耗时/精度/错误/坐标系 |

UI 约束：

- 所有开关和输入在竖屏、横屏、窄屏、短屏可操作。
- 内容超高必须可滚动。
- Key 输入不能遮挡保存按钮。
- 中文说明不能裁切，必要时换行。

---

## 8. 验收标准

### 8.1 构建验收

- standard APK 不包含高德 SDK 类和依赖。
- enhanced APK 包含高德 Android 定位 SDK，不包含 Android 地图 SDK，除非另有地图 UI 需求。
- `:app:assembleStandardDebug`、`:app:assembleEnhancedDebug` 成功。
- release 构建产物命名清晰区分 standard/enhanced。

### 8.2 高德 provider 验收

- Key 空时不初始化高德 SDK。
- 隐私未确认时不初始化高德 SDK。
- 高德鉴权失败时网页不崩溃。
- 单次定位可返回 provider=`amap` 的结果。
- watchPosition 可连续返回，clearWatch 后停止。
- Activity 销毁后无活跃定位请求。
- 诊断信息不包含完整 Key 和完整经纬度。

### 8.3 标准 Geolocation 托管验收

- 未启用 bridge 时，普通网页仍走 WebView 默认定位。
- 启用 bridge 且 Origin 在允许列表时，`navigator.geolocation.getCurrentPosition` 返回高德或系统 provider 结果。
- Origin 未允许、黑名单、全局禁用定位时，返回标准 Geolocation 错误。
- 页面跳转后旧请求不会回调到新页面。
- 高德 provider 超时后按策略回退系统 provider。
- 返回 payload 包含精度、时间戳、坐标系诊断。

### 8.4 高德 H5 辅助定位验收

- 使用高德 JS API 且 `useNative: true` 的测试页可以触发辅助定位。
- 不加载高德 JS SDK 的普通测试页不依赖该模式。
- WebView 销毁或回收入池时调用 `stopAssistantLocation()`。
- UI 清楚提示该模式只服务高德 JS API 页面。

### 8.5 坐标系验收

- 国内高德结果不得默认伪装为标准 WGS84。
- 标准模式下返回 WGS84 或执行明确转换。
- GCJ-02 兼容模式只能 per-site 开启。
- 诊断显示原始 provider 坐标系和网页返回坐标系。

---

## 9. 推荐实施顺序

1. 抽象 `NativeLocationProvider` 和 provider router，把现有 `NativeLocationManager` 包装为系统 provider。
2. 增加 enhanced flavor 和高德配置 UI，但默认关闭，不初始化 SDK。
3. 实现 `AmapLocationProvider` 单次定位、诊断和系统回退。
4. 把现有标准 Geolocation bridge 切到 provider router。
5. 增加坐标系字段、转换/兼容模式和 per-site 设置。
6. 实现高德 watchPosition。
7. 可选实现高德 H5 辅助定位。
8. 真机户外对比 WebView 默认、系统 provider、高德 provider 的耗时、精度和失败率。

---

## 10. 资料来源

- 高德 Android 定位 SDK 获取定位数据：`https://lbs.amap.com/api/android-location-sdk/guide/android-location/getlocation`
- 高德 Android 定位 SDK 新版辅助 H5 页面定位：`https://lbs.amap.com/api/android-location-sdk/guide/android-location/new-assistant_location`
- 高德 Android 地图 SDK 显示定位蓝点：`https://lbs.amap.com/api/android-sdk/guide/create-map/mylocation`
- Android `WebChromeClient.onGeolocationPermissionsShowPrompt`：`https://developer.android.com/reference/android/webkit/WebChromeClient#onGeolocationPermissionsShowPrompt(java.lang.String,android.webkit.GeolocationPermissions.Callback)`
- AndroidX WebKit `WebViewCompat.addDocumentStartJavaScript`：`https://developer.android.com/reference/androidx/webkit/WebViewCompat#addDocumentStartJavaScript(android.webkit.WebView,java.lang.String,java.util.Set%3Cjava.lang.String%3E)`
