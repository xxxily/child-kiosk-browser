# Android 儿童防误触全屏 Kiosk 浏览器技术调研报告

## 1. 背景与概述
在开发面向2-8岁儿童的 Web 应用管理器（如小游戏、教育平台容器）时，核心挑战在于构建一个**绝对安全、防误触、防逃逸**的沙箱环境。儿童由于运动控制能力尚在发育中，容易产生误触物理/虚拟按键、边缘滑动手势等行为；同时，网络上可能存在的恶意广告、弹窗及外部 App 拉起等，也是必须隔离的隐患。

本报告对 Android 系统底层的 Kiosk 机制（Lock Task Mode）、系统安全逃逸漏洞、WebView 安全沙箱配置以及儿童专用 UI/UX 设计规范进行了深度调研，为后续开发提供坚实的技术支撑与实现标准。

---

## 2. 核心技术选型与 API 分析

### 2.1 Device Owner (设备所有者) 与 Device Admin (设备管理器) 的对比
在 Android 权限体系中，有两种实现设备级管理的模式：

| 维度 | Device Admin (遗留模式) | Device Owner (企业模式) |
| :--- | :--- | :--- |
| **引入版本** | Android 2.2 (API 8) | Android 5.0 (API 21) |
| **当前状态** | **已废弃**。Android 10+ 限制了其大部分关键 API。 | **推荐标准**。目前处于积极维护与更新状态。 |
| **Lock Task Mode** | ❌ 无法静默启用，需要用户手动点击系统授权弹窗，且易被用户轻易退出。 | ✅ **完全静默启用**。应用被系统强锁，无弹窗，且用户无法绕过或强制退出。 |
| **系统控制权限** | 仅限于密码策略、远程擦除、禁用相机等基础限制。 | 拥有“万能钥匙”权限：可静默安装/卸载、管理 USB 调试、禁用出厂设置等。 |
| **配置条件** | 应用启动后可随时申请，用户在系统设置中手动激活。 | **极其严格**。必须在设备**首次开机初始化（或恢复出厂设置）**、且未绑定任何 Google 账号（或系统账号）前，通过 ADB 或 QR 码/NFC 进行配置。 |

> [!IMPORTANT]
> **结论**：本项目必须采用 **Device Owner** 模式。普通的 `Device Admin` 或无需权限的 `Screen Pinning`（屏幕固定）会产生系统弹窗提示，且可通过按键组合直接退出，无法满足“防儿童逃逸”的刚性需求。

### 2.2 Lock Task Mode 核心 API 实现

要实现静默且不可逆的锁定，需要在 Activity 中执行以下步骤：

```kotlin
// 1. 获取系统服务
val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
val adminComponent = ComponentName(context, MyDeviceAdminReceiver::class.java)

// 2. 将当前包名加入 Lock Task 白名单（必须是 Device Owner 才能执行）
val allowedPackages = arrayOf(context.packageName)
dpm.setLockTaskPackages(adminComponent, allowedPackages)

// 3. 在 Activity 中开启锁定（此操作会静默锁定系统，不再弹出用户确认提示）
startLockTask()

// 4. 解锁（在家长通过验证后调用）
stopLockTask()
```

### 2.3 Lock Task Features 标志位控制 (Android 9.0+)
Android 9.0 (API 28) 引入了 `setLockTaskFeatures` API，允许开发者微调 Kiosk 模式下的系统 UI 表现。推荐策略配置如下：

| 标志位 | 控制效果 | 推荐配置与原因 |
| :--- | :--- | :--- |
| `LOCK_TASK_FEATURE_NONE` | 禁用所有系统 UI，包括状态栏、导航栏、锁屏等。 | **基准配置**。以此为基础做叠加。 |
| `LOCK_TASK_FEATURE_SYSTEM_INFO` | 允许在状态栏显示时间、电池电量、Wi-Fi 信号等。 | **开启**。家长和儿童需要知晓设备当前电量和网络状态。 |
| `LOCK_TASK_FEATURE_HOME` | 启用系统 Home 键。 | **关闭**。开启后会给逃逸留下漏洞。 |
| `LOCK_TASK_FEATURE_OVERVIEW` | 启用最近任务键（多任务键）。 | **关闭**。防止切换到其他后台应用。 |
| `LOCK_TASK_FEATURE_NOTIFICATIONS`| 启用下拉通知栏和悬浮通知。 | **关闭**。防止儿童点击通知跳转到其他应用。 |
| `LOCK_TASK_FEATURE_KEYGUARD` | 启用系统锁屏。 | **关闭**。避免锁屏后可能触发的逃逸或被锁定在外。 |
| `LOCK_TASK_FEATURE_GLOBAL_ACTIONS` | 启用电源键长按菜单（关机/重启）。 | **关闭**（或视设备而定）。关闭后，长按电源键将不再弹出关机菜单，减少儿童误触导致设备关闭。 |

---

## 3. 防逃逸与系统级安全漏洞加固

即使进入了 Lock Task Mode，Android 设备的复杂交互仍可能留下逃逸路径。开发时必须实现以下针对性的防御：

### 3.1 语音助手逃逸 (Voice Assistant Bypass)
* **漏洞描述**：儿童可以通过说出 "Hey Google" 或长按物理助理键呼出语音助手，然后说出“打开系统设置”来逃离沙箱。
* **防护方案**：在 Device Owner 激活后，调用：
  ```kotlin
  dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_VOICE_ASSISTANTS)
  ```

### 3.2 截屏/分享通道逃逸
* **漏洞描述**：长按电源键+音量下键触发系统截屏，弹出的“分享”面板可以让儿童跳转到社交软件或系统相册。
* **防护方案**：
  1. 在 Activity 的 `onCreate` 中，设置 `FLAG_SECURE` 阻断截屏：
     ```kotlin
     window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
     ```
  2. 使用 Device Owner 策略全局禁止截屏：
     ```kotlin
     dpm.setScreenCaptureDisabled(adminComponent, true)
     ```

### 3.3 开发者选项与 USB 调试逃逸
* **漏洞描述**：如果设备开启了 USB 调试，懂电脑的儿童（或外部人员）可以通过 ADB 执行 `adb shell am start` 直接拉起其他应用，或者通过 `pm disable` 关停本应用。
* **防护方案**：通过 Device Policy Manager 禁用开发者选项及调试功能：
  ```kotlin
  dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_DEBUGGING_FEATURES)
  ```

### 3.4 物理音量键控制
* **漏洞描述**：虽然音量键无法使应用逃逸，但儿童反复狂按音量键会发出刺耳的声音，或者直接将声音降到最低导致无法使用。
* **防护方案**：在 Activity 中重写键盘监听事件，拦截并由应用内部代为处理音量调节，或者彻底屏蔽：
  ```kotlin
  override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
      return if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
          // 消费事件，阻止系统默认的音量 UI 弹出，并根据需要实现受限音量控制
          true
      } else {
          super.onKeyDown(keyCode, event)
      }
  }
  ```

---

## 4. WebView 安全沙箱与性能优化

WebView 作为加载 Web 游戏或教学内容的容器，是发生网络风险的最高频场所。必须从安全和内存两个维度进行深度定制。

### 4.1 WebView 安全加固策略
1. **禁用本地文件与内容访问**：阻止网页读取设备本地的 `/sdcard` 目录文件。
   ```kotlin
   settings.allowFileAccess = false
   settings.allowContentAccess = false
   settings.allowFileAccessFromFileURLs = false
   settings.allowUniversalAccessFromFileURLs = false
   ```
2. **阻断新窗口弹出**：禁用 `window.open`。
   ```kotlin
   settings.setSupportMultipleWindows(false)
   settings.javaScriptCanOpenWindowsAutomatically = false
   ```
3. **强制 HTTPS 协议与 SSL 校验**：
   * **绝对禁止**在 `onReceivedSslError` 中调用 `handler.proceed()`。任何 SSL 错误必须执行 `handler.cancel()`，并显示友好的应用内报错页面，防止中间人攻击（MITM）。
   * 限制非 `https` 内容加载，设置 `mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW`。
4. **禁止 WebView 内权限请求**：重写 `WebChromeClient.onPermissionRequest`，默认拒绝所有麦克风、摄像头、地理位置等权限申请，防范隐私泄露。
   ```kotlin
   override fun onPermissionRequest(request: PermissionRequest) {
       // 默认直接拒绝所有 H5 权限申请
       request.deny()
   }
   ```
5. **重写下载监听器 (DownloadListener)**：拦截任何试图下载 APK 或其他文件的请求。
   ```kotlin
   webView.setDownloadListener { url, userAgent, contentDisposition, mimetype, contentLength ->
       // 拦截下载行为，禁止任何文件落盘，并向用户展示“下载功能已受限”提示
   }
   ```

### 4.2 网页广告与弹窗过滤拦截
除了禁用新窗口，还需要在网络层和 DOM 层实现广告屏蔽：
* **网络层拦截**：重写 `WebViewClient.shouldInterceptRequest`。解析网页加载的每一个资源请求，若目标 Host 在预置的广告域名黑名单中（如 doubleclick.net 等），直接返回空的 `WebResourceResponse`，阻止其网络包下发。
* **外部 Scheme 阻断**：重写 `shouldOverrideUrlLoading`。只允许 `http://` 和 `https://` 协议，其余如 `market://` (应用商店)、`weixin://`、`tbopen://` 等非 H5 协议全部拦截丢弃，杜绝网页唤起外部 App 的可能性。

### 4.3 内存泄漏与崩溃防御
由于部分 H5 游戏对 Canvas 或 WebGL 的开销极大，WebView 运行时间长了极易发生 OOM。
* **独立进程运行 WebView**：在 `AndroidManifest.xml` 中将 WebView 所在的 Activity 声明在独立的进程中（例如 `android:process=":webview"`）。这样即使 WebView 发生 OOM 崩溃，也仅仅是子进程崩溃，主进程的 Kiosk 状态不会丢失，应用会自动重启子进程，避免崩溃后直接暴露出原生桌面。
* **显式销毁机制**：当退出 WebView 返回网格主页时，必须执行精细的销毁：
  ```kotlin
  webView.loadUrl("about:blank")
  webView.clearHistory()
  webView.clearCache(true)
  webView.removeAllViews()
  (webView.parent as? ViewGroup)?.removeView(webView)
  webView.destroy()
  ```

---

## 5. 儿童专用 UI/UX 设计规范 (2-8岁)

儿童在生理与认知上与成人有显著差异，应用界面必须遵循以下规范：

### 5.1 触控目标 (Touch Targets) 极大化
* **设计标准**：Android 系统的基础触控范围是 48dp，而面向儿童的交互按钮必须扩展至 **72dp - 80dp**，且按钮之间的物理间距应不小于 **16dp**。
* **原因**：2-4岁幼儿的精细动作发育尚不成熟，通常使用手指甚至整个手掌去拍击屏幕。如果按钮太小或太密集，极易产生挫败感。

### 5.2 视觉与文案的非依赖设计
* **设计标准**：界面中**不要依赖复杂的文字描述**来引导操作，应采用高辨识度的拟物图标或插画（如“小房子”代表主页，“小齿轮”代表设置）。
* **原因**：该年龄段的部分儿童还处于“前阅读期”（识字不多），图形引导能保证他们自主操作。

### 5.3 色彩与微动画反馈
* **色彩方案**：使用高饱和度、明亮的色彩系统（如明黄、天蓝、草绿、暖橙），卡片应使用圆角（如 `16dp` 以上），避免冰冷锋利的直角。
* **交互反馈**：所有的点击必须有即时的、弹跳感（Bounce）强的微动画效果（过渡时间控制在 150-300ms），辅以轻微的触觉震动，给予儿童明确的“我已经点到这个按钮了”的认知确认。

---

## 6. 行业成熟方案参考 (竞品分析)

在开发本款应用前，建议参考市场上主流的商业级 Kiosk 方案的功能边界：

1. **Fully Kiosk Browser**：
   * **特点**：目前最强大的商用 Web 锁定容器。
   * **核心功能**：细致到极致的 WebView 配置（支持白名单过滤）、设备传感器唤醒屏幕、本地 PIN 码/隐藏手势退出、云端远程配置。
2. **SureLock (by 42Gears)**：
   * **特点**：企业级多应用锁定 Launcher。
   * **核心功能**：完全接管系统桌面，仅允许用户访问白名单内的几个应用，禁用 USB 调试和系统下拉通知栏，内置详细的屏幕使用时间统计。

---

## 7. 部署与分发建议

### 7.1 ADB 部署 Device Owner 的标准操作手册
由于本应用包含强锁系统行为，目标用户为个人家庭（家长部署给孩子）。在开发测试以及家长安装时，可提供如下步骤：

1. **备份并出厂重置设备**（如果设备已有 Google 账号或系统账号，必须重置）。
2. **开机向导中跳过** WiFi 连接和所有账户绑定，直接进入桌面。
3. 进入系统设置，连续点击版本号开启开发者模式，**启用 USB 调试**。
4. 将平板连接电脑，打开终端运行：
   ```bash
   adb shell dpm set-device-owner site.anzz.childkiosk/.MyDeviceAdminReceiver
   ```
5. 成功后，界面会显示 `Active Admin set`。此时可以安全地连网并添加其他账号。

### 7.2 Google Play 合规性（面向未来发布）
如果后续考虑上架 Google Play Store，必须严守以下政策：
* **儿童与家庭政策 (Families Policy)**：必须在声明中指定受众年龄（如 2-8 岁），严禁集成任何会收集持久标识符（如 AAID、IMEI）的第三方广告 SDK 或数据统计 SDK。
* **隐私声明**：必须提供符合 COPPA（儿童在线隐私保护法）的隐私政策说明，表明应用本身不收集、不存储、不传输任何儿童的个人数据。
