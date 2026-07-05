# 高德网页定位增强排查记录

## 背景

增强版 APK 通过高德 Android 定位 SDK 托管可信网页的 `navigator.geolocation`。本项目的真实网页运行在 `WebViewActivity`，且该 Activity 位于独立的 `:webview` 进程。定位增强因此不能只看 UI 配置是否正确，还必须确认高德 SDK 初始化、运行时配置同步、主进程代理和网页 bridge 请求是否在同一条链路上。

## 已踩过的坑

### 1. WebView 进程直接初始化高德 SDK 容易鉴权失败

现象：

- 后台测试定位正常。
- 网页定位返回 `高德定位失败: 7 KEY错误 INVALID_USER_KEY`。
- 调试信息里能看到请求发生在 `site.anzz.childkiosk:webview`。

原因：

- `WebViewActivity` 运行在 `:webview` 进程。
- 长生命周期 WebView 进程不能假设 `SharedPreferences` 中的 Key、隐私同意和 provider 策略是最新值。
- 高德 SDK 的 `setApiKey()` 必须在 SDK 业务初始化前执行，跨进程旧配置会造成实际请求 Key 与后台看到的 Key 不一致。

处理原则：

- 网页 bridge 请求通过 `NativeLocationMainProcessClient -> NativeLocationMainProcessService -> NativeLocationManager` 走主进程定位代理。
- 主进程定位代理使用运行时配置快照和 `amap_location_runtime_config.json` 合并后的最新高德配置。
- 不要在 `:webview` 进程为标准网页定位再单独创建高德 `AMapLocationClient`。

### 2. 预热 provider 与网页 provider 不一致会让网页冷启动

现象：

- 日志中“页面预热”很快，但 provider 是 `network`。
- 后续网页单次定位 provider 是 `amap`，耗时仍约 3 秒。

原因：

- 预热被强制切到系统 provider，只刷新系统 `LocationManager` 缓存。
- 网页实际走高德 provider 时无法复用这条系统缓存，仍要新启动一次高德定位。

处理原则：

- 预热应走与当前策略一致的原生 provider。
- 在增强版且策略为“高德优先/仅高德”时，预热应通过主进程高德 provider 刷新高德近期结果。
- 同一 origin 的预热需要短时间节流，避免页面开始/提交阶段重复预热把请求队列挤慢。

### 3. `isOnceLocationLatest=true` 可能带来稳定 3 秒等待

现象：

- 后台测试定位几十到几百毫秒。
- 网页定位稳定约 3.1 秒。
- 高德返回成功，但 `耗时` 明显偏高。

原因：

- 高德单次定位的 `onceLocationLatest` 会等待近期更优结果，并不等价于“立即返回缓存”。
- 如果每次网页请求都依赖该模式，用户感知就是网页定位慢。

处理原则：

- 项目自己维护最近一次成功的高德定位结果。
- 网页请求允许缓存时，先按 `nativeLocationMaxCacheAgeMs` 命中项目内缓存。
- 缓存命中后可按阈值后台刷新，但不能阻塞网页回调。
- 新的高德单次请求默认关闭 `onceLocationLatest`，避免无谓等待。

### 4. 调试日志必须能排查，但默认展示要降噪

现象：

- 定位诊断能定位问题，但“最近定位记录”充满 SDK 调试、Key 指纹、进程信息，管理员难以阅读。

处理原则：

- 最近定位记录默认只展示来源、类型、状态、provider、精度、耗时、缓存状态和简短说明。
- 原始诊断默认折叠，复制诊断仍复制完整内容。
- 需要排查鉴权或跨进程问题时，再展开原始诊断查看 `process`、`webviewProcess`、`effectiveKey`、`requestKey`、`runtimeFile` 等字段。

## 推荐排查顺序

1. 确认当前安装版本是 enhanced，且诊断显示已集成高德定位 SDK。
2. 确认高德配置调试中 `effectiveKey`、`prefsKey`、`runtimeFile` 的 Key 指纹一致。
3. 确认网页定位记录中的高德调试 `process` 是主进程 `site.anzz.childkiosk`，不是 `site.anzz.childkiosk:webview`。
4. 如果出现 `INVALID_USER_KEY`，先核对诊断里高德校验的 `SHA1AndPackage`，再核对高德后台 Key 类型、包名、发布签名 SHA1。
5. 如果后台测试快但网页慢，检查预热是否走同一 provider，并检查网页请求是否命中高德近期缓存。
6. 如果“页面预热”短时间连续出现多条，检查预热节流是否生效。
7. 如果网页仍慢，保留原始诊断，重点看 `provider`、`cached`、`cacheAgeMillis`、`elapsedMs` 和是否存在排队/超时。

## 验收基线

- 网页 bridge 定位成功时，允许返回 provider=`amap`。
- 近期高德缓存命中时，网页定位应接近毫秒级返回，并标记 `缓存=是`。
- 冷启动首次高德定位可以慢于缓存命中，但不应因为 `onceLocationLatest` 固定等待约 3 秒。
- 预热不应在同一 origin 上短时间重复刷屏。
- 复制诊断仍能提供完整调试信息，默认 UI 不应展示大段调试文本。
