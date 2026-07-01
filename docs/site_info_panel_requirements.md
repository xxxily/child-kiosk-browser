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

基于儿童 kiosk 浏览器的特殊场景，我们对 Chrome 的能力进行剪裁和定制，重点解决**“定位黑名单”**和**“Scheme 协议跳转”**的现场配置问题。

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
   * 显示当前网站（Host）的定位权限状态：
     * **允许/询问：** 域名未在黑名单中。
     * **彻底禁止：** 域名存在于 Geolocation 黑名单中。
   * 如果全局设置里禁用了定位，在此处会显示“已全局禁用”，并置灰操作。
   * 用户可以直接在该面板中，一键切换该域名的黑名单状态（移入或移出），立即可见并生效。
3. **外部 Scheme 跳转 (Custom Scheme) 快捷配置：**
   * 列出当前网页关联的 Scheme 协议控制，或提供常用/已拉黑的 Scheme 快捷移除。
   * 当用户在此处配置时，可直接开启、关闭当前 Scheme（如 `alipays` 等）的跳转黑名单权限。
4. **网站数据清理 (Data Purge)：**
   * 提供“清除此网站的缓存与 Cookie”的一键快捷清理按钮。

---

## 3. 技术实现路径

### 3.1 悬浮球布局改造
* 在 [FloatingBrowserControlsOverlay.kt](file:///Users/blaze/work/github/child-kiosk-browser/app/src/main/java/site/anzz/childkiosk/ui/browser/FloatingBrowserControlsOverlay.kt) 的地址栏水平 `LinearLayout` 中，在 `urlInput` 左侧通过 `addView` 塞入 `infoButton`。
* 监听当前 WebView 的 URL 状态变化，实时更新 `infoButton` 的 Icon。
* 为 `infoButton` 绑定 `setOnClickListener`，点击后派发回调 `onShowSiteInfoPanel(currentUrl)` 至外部的主 Activity。

### 3.2 权限快捷控制面板 (SiteInfoDialog) 渲染
* 在 [WebViewActivity.kt](file:///Users/blaze/work/github/child-kiosk-browser/app/src/main/java/site/anzz/childkiosk/WebViewActivity.kt) 中接收回调，利用 `showCustomComposeDialog` 方法渲染一个定制的 `SiteInfoDialog`（Compose 视图）。
* 在 `SiteInfoDialog` 中：
  * 读取最新的 `runtimeConfig`，取得当前的定位黑名单和 Scheme 黑名单。
  * 根据当前的 `currentUrl` 抽取 Host 和 Scheme。
  * 提供 RadioButton 或 Switch 用于快速更改当前 Host 的定位权限、当前 Scheme 的跳转权限。
  * 更改后，直接调用 `KioskPrefs` 将其移入/移出黑名单，并实时更新 Activity 的 `runtimeConfig`，做到当场修改、当场生效。
