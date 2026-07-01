# 需求规划：地址栏左侧网站信息与权限配置面板 (Site Info Panel)

为提升用户体验、降低心智负担，本项目计划在悬浮球功能面板的地址栏左侧新增一个“网站信息与快捷权限配置”入口。该功能对标 Chrome 浏览器的地址栏左侧按钮能力，提供当前域名的安全状态展示、定位权限管理以及自定义 Scheme 协议跳转管理，使用户无需深入管理员后台即可快速诊断和配置当前网站。

---

## 1. 对标 Chrome 地址栏左侧按钮分析

Chrome 地址栏左侧的按钮（通常为“锁”图标、警告图标或 ℹ️ 图标）主要提供以下能力：
1. **连接安全性 (Connection Security)：** 告知用户与当前网站的连接是否安全（HTTPS 证书有效性、HTTP 未加密警告）。
2. **网站权限控制 (Permissions)：** 列出当前网站已经请求或被配置的权限（定位、摄像头、麦克风、通知等），并允许用户现场更改（允许/禁止）或一键重置。
3. **Cookie 和网站数据 (Cookies & Site Data)：** 展示当前网站存储的本地数据量，并允许一键清理。
4. **关于此页面 (About this Page)：** 供用户查看证书详情及站点发布者信息。

---

## 2. 本项目 Site Info Panel 设计规格

基于儿童 kiosk 浏览器的特殊场景，我们对 Chrome 的能力进行剪裁和定制，重点解决**“站点权限可解释、可即时拒绝、可持久拒绝”**的问题。面板既要覆盖定位和 Scheme 协议跳转，也要覆盖摄像头、麦克风、文件上传等会泄露隐私或跳出浏览上下文的能力。

### 2.1 视觉呈现与入口 (Icon UI)
在悬浮球面板中的 `urlInput`（输入框）左侧，新增一个独立的图标按钮 `infoButton`：
* **安全性图标自动切换：**
  * 若当前加载的网页 URL 为 `https://`：显示绿色/灰色的安全“锁 🔒”图标。
  * 若当前加载的网页 URL 为 `http://`：显示警告“⚠️”图标，以警示未加密风险。
  * 其他情况（如空地址、`about:blank` 等）：显示信息“ℹ️”图标。

### 2.2 权限控制与信息面板 (Site Info Panel Pop-up)
点击 `infoButton` 后，将弹出一个精致的自定义 Compose 对话框（SiteInfoDialog），以卡片形式优雅呈现：
1. **域名与安全状态展示：**
   * 突出展示当前站点的 Host（如 `m.baidu.com`）。
   * 显示当前 SSL 安全连接状态（如：“此连接安全” 或 “您与该网站的连接未加密，存在安全隐患”）。
2. **网页定位权限 (Geolocation) 快捷配置：**
   * 显示当前网站（Origin，例如 `https://example.com`）的定位权限状态：
     * **允许/询问：** 域名未在黑名单中。
     * **彻底禁止：** Origin 存在于 Geolocation 黑名单中。
   * 如果全局设置里禁用了定位，在此处会显示“已全局禁用”，并置灰操作。
   * 用户可以直接在该面板中，一键切换该 Origin 的黑名单状态（移入或移出），立即可见并生效。
3. **外部 Scheme 跳转 (Custom Scheme) 快捷配置：**
   * 列出当前网页关联的 Scheme 协议控制，或提供常用/已拉黑的 Scheme 快捷移除。
   * 当用户在此处配置时，可直接开启、关闭当前 Scheme（如 `alipays` 等）的跳转黑名单权限。
4. **媒体与文件权限快捷配置：**
   * 摄像头、麦克风、文件选择/上传均按当前 Origin 维护独立黑名单。
   * 如果全局设置禁用了对应能力，在此处显示“已全局禁用”，并置灰站点级切换。
   * 当用户在权限弹窗中选择“拒绝且不再提示”时，必须写入对应黑名单，并同步更新当前 WebViewActivity 的运行时配置，使同一页面后续请求也不再弹窗。
5. **网站数据清理 (Data Purge)：**
   * 提供“清除此网站的缓存与 Cookie”的一键快捷清理按钮。

---

## 3. 权限体系规划

### 3.1 权限矩阵
| 能力 | WebView 入口 | 全局限制 | 站点/协议黑名单 | Android 系统权限 | 默认策略 |
|---|---|---|---|---|---|
| 定位 | `onGeolocationPermissionsShowPrompt` | `limitGeolocation` | `geolocationBlacklist`，按 Origin | `ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION` | 全局默认允许询问，儿童模式禁用 |
| 外部应用跳转 | `shouldOverrideUrlLoading` 非 Web Scheme | `limitCustomScheme` | `schemeBlacklist`，按 Scheme | 无 | 正常模式询问，非正常模式或儿童模式拦截 |
| 摄像头/视频采集 | `onPermissionRequest` 的 `RESOURCE_VIDEO_CAPTURE` | `limitCameraCapture` | `cameraBlacklist`，按 Origin | `CAMERA` | 全局默认允许询问，儿童模式禁用 |
| 麦克风/录音 | `onPermissionRequest` 的 `RESOURCE_AUDIO_CAPTURE` | `limitMicrophoneCapture` | `microphoneBlacklist`，按 Origin | `RECORD_AUDIO` | 全局默认允许询问，儿童模式禁用 |
| 文件选择/上传 | `onShowFileChooser` | `limitFileChooser` | `fileChooserBlacklist`，按 Origin | 无，交给系统文件选择器 | 默认允许询问，儿童模式禁用 |
| 网页全屏视频 | `onShowCustomView` | `limitFullscreenVideo` | 暂不做站点黑名单 | 无 | 默认允许，儿童模式禁用 |
| 下载 | `setDownloadListener` | `limitDownload` | 暂不做站点黑名单 | API 28 及以下需要存储权限 | 默认允许，儿童模式禁用 |

### 3.2 持久化与即时生效规则
1. 黑名单必须使用稳定 key：
   * Origin 权限使用规范化 Origin：`scheme://host[:port]`，host 小写，不包含 path/query。
   * Scheme 权限使用小写 Scheme：`weixin`、`alipays`，不包含 `://`。
2. “拒绝”只拒绝当前请求；“拒绝且不再提示”必须拒绝当前请求并写入对应黑名单。
3. `WebViewActivity` 运行在 `:webview` 进程，权限弹窗写入 `SharedPreferences` 后，还必须同步更新 Activity 内存中的 `runtimeConfig`。
4. `WebViewClient` / `WebChromeClient` 不能永久捕获创建时的旧 `runtimeConfig` 用于权限判断；黑名单和权限开关要通过当前 Activity 的最新运行时配置读取。
5. 已存在的 WebView 实例如果携带影响创建期行为的设置（如全局定位开关、文件访问开关），后台切换后对当前页面不承诺立即生效；新打开网页生效。站点黑名单和弹窗决策类设置应立即生效。

### 3.3 弹窗行为
1. 定位、摄像头、麦克风、文件选择、外部 Scheme 都必须有三种用户路径：
   * 允许：继续当前请求；需要 Android 系统权限时再触发系统授权。
   * 拒绝：拒绝当前请求，但下次仍可询问。
   * 拒绝且不再提示：写入对应黑名单，同一页面后续请求直接拒绝，不再弹窗。
2. 全局禁用或命中黑名单时，不展示用户确认弹窗，直接拒绝并给出简短 Toast。
3. 系统权限被拒绝时，不自动写入站点黑名单；这是系统权限层面的拒绝，不等同于家长对该站点的长期策略。
4. 对未知 `PermissionRequest` 资源默认拒绝，不做宽泛放行。

### 3.4 下拉刷新
1. 增加“页面顶部大幅下拉刷新”设置，默认关闭；只有用户主动开启后才启用。
2. 实现必须保持真实网页仍走 `WebViewActivity -> FrameLayout -> WebView` 原生路径；可以在外层使用原生 ViewGroup 承载手势，但不能改成 Compose `AndroidView` 生产宿主。
3. 仅当当前 WebView 已在页面顶部且没有全屏视频/权限弹窗等覆盖层时触发刷新。
4. 设置通过 WebView 启动 Intent 快照传入 `:webview` 进程；切换后对新打开的网站生效。
5. 触发模型应接近浏览器级 pull-to-refresh：触摸开始时页面已在顶部，向下拖动超过阈值，并在松手时触发刷新；不要要求手指从屏幕顶部小区域开始。
6. 应尊重网页通过 `overscroll-behavior-y: contain/none` 表达的禁用浏览器下拉刷新意图，降低全屏 Web App 或自定义手势页面误触。

---

## 4. 技术实现路径

### 4.1 悬浮球布局改造
* 在 [FloatingBrowserControlsOverlay.kt](file:///Users/blaze/work/github/child-kiosk-browser/app/src/main/java/site/anzz/childkiosk/ui/browser/FloatingBrowserControlsOverlay.kt) 的地址栏水平 `LinearLayout` 中，在 `urlInput` 左侧通过 `addView` 塞入 `infoButton`。
* 监听当前 WebView 的 URL 状态变化，实时更新 `infoButton` 的 Icon。
* 为 `infoButton` 绑定 `setOnClickListener`，点击后派发回调 `onShowSiteInfoPanel(currentUrl)` 至外部的主 Activity。

### 4.2 权限快捷控制面板 (SiteInfoDialog) 渲染
* 在 [WebViewActivity.kt](file:///Users/blaze/work/github/child-kiosk-browser/app/src/main/java/site/anzz/childkiosk/WebViewActivity.kt) 中接收回调，利用 `showCustomComposeDialog` 方法渲染一个定制的 `SiteInfoDialog`（Compose 视图）。
* 在 `SiteInfoDialog` 中：
  * 读取最新的 `runtimeConfig`，取得当前的定位、摄像头、麦克风、文件选择黑名单和 Scheme 黑名单。
  * 根据当前的 `currentUrl` 抽取 Origin 和 Host，根据最近一次拦截记录抽取 Scheme。
  * 提供 Switch 用于快速更改当前 Origin 的站点权限、当前 Scheme 的跳转权限。
  * 更改后，直接调用 `KioskPrefs` 将其移入/移出黑名单，并实时更新 Activity 的 `runtimeConfig`，做到当场修改、当场生效。
