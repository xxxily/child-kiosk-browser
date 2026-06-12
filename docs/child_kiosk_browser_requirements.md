# 儿童防误触全屏 Web 应用管理器 (Child Kiosk Browser) 需求规格说明书

## 1. 项目概述
* **项目名称**：儿童防误触全屏 Web 应用管理器 (Child Kiosk Browser)
* **目标群体**：2-8 岁儿童及其家长/管理员
* **技术栈**：
  * **开发语言**：Kotlin (1.9.x+)
  * **UI 框架**：Jetpack Compose (声明式 UI，现代且轻量)
  * **本地存储**：Room Database (用于管理 Web 应用列表)
  * **核心组件**：Android System Kiosk API (DevicePolicyManager + Lock Task Mode) + Android WebView (独立子进程运行)
* **项目宗旨**：打造一个绝对安全、防误触、防逃逸的儿童专用 Web 应用（小游戏/教育网站）容器。应用本身高度轻量，不包含具体的游戏内容，仅负责容器沙箱与系统锁定逻辑。

---

## 2. 核心场景与用户角色
* **儿童 (使用者)**：
  * **场景**：在平板上启动应用后，自动进入全屏网格主页。点击家长添加好的 Web 应用图标，进入全屏网页游戏或学习网站。
  * **安全保障**：无法通过物理/虚拟按键、滑动边缘手势、长按电源键、重启等任何常规操作离开本应用；无法点击网页内的广告链接跳转到外部浏览器或下载任何安装包。
* **家长/管理员 (配置者)**：
  * **场景**：通过隐藏的按键手势（如右上角 2 秒内连续点击 5 次）调起家长验证弹窗，输入正确的口算题或固定密码，进入管理后台。
  * **后台功能**：添加新的 Web 链接（输入名称与 URL，支持自动校验和预设）、删除或编辑现有链接；通过“退出”按钮安全解除系统锁定并返回正常的 Android 系统桌面；设置单次限时或每日可用时长。

---

## 3. 详细功能需求与技术实现标准

为了确保即使是初级开发人员也能准确实现，每个需求条目都配有具体的技术实现规范。

### 3.1 系统锁定与防逃逸模块 (Kiosk Core)

#### 【REQ-101】企业级锁定模式 (Lock Task Mode)
* **需求描述**：应用进入前台时，必须自动激活并进入 Lock Task Mode（锁定任务模式），实现系统级锁死。
* **开发实现规范**：
  1. 必须使用 **Device Owner** 模式，不允许使用需要用户手动授权的普通 Screen Pinning 模式。
  2. 实现自定义的 [MyDeviceAdminReceiver](file:///Users/blaze/work/github/notes/serverinfo/local_notes/notes/child_kiosk_browser_requirements.md)（继承自 `DeviceAdminReceiver`），并在 `AndroidManifest.xml` 中进行声明：
     ```xml
     <receiver
         android:name=".MyDeviceAdminReceiver"
         android:permission="android.permission.BIND_DEVICE_ADMIN"
         android:exported="true">
         <meta-data
             android:name="android.app.device_admin"
             android:resource="@xml/device_admin_policies" />
         <intent-filter>
             <action android:name="android.app.action.DEVICE_ADMIN_ENABLED" />
             <action android:name="android.app.action.PROFILE_PROVISIONING_COMPLETE" />
         </intent-filter>
     </receiver>
     ```
  3. 配置 `device_admin_policies.xml`，至少包含 `<force-lock />` 等策略。
  4. 在 Activity 的 `onResume` 中，加入以下锁死控制代码：
     ```kotlin
     val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
     val adminComponent = ComponentName(this, MyDeviceAdminReceiver::class.java)
     
     if (dpm.isDeviceOwnerApp(packageName)) {
         // 1. 设置 Lock Task 白名单包名（包括本应用自身）
         dpm.setLockTaskPackages(adminComponent, arrayOf(packageName))
         
         // 2. 精细化配置 Lock Task 属性 (仅保留状态栏系统信息显示，屏蔽其余所有逃逸口)
         if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
             dpm.setLockTaskFeatures(
                 adminComponent,
                 DevicePolicyManager.LOCK_TASK_FEATURE_SYSTEM_INFO // 仅允许查看电量、时间、Wi-Fi
             )
         }
         
         // 3. 开启锁死
         startLockTask()
     } else {
         // 提示家长该应用尚未被设为 Device Owner，引导通过 ADB 激活
     }
     ```

#### 【REQ-102】自定义 Launcher (防重启逃逸)
* **需求描述**：应用必须在 `AndroidManifest.xml` 中声明为系统的 `category.HOME` 桌面应用。
* **引导机制**：首次启动或检测到当前应用非默认桌面时，弹出全屏引导卡片，指导家长点击按钮跳转至系统设置中将本应用设为“默认主屏幕”。
* **开发实现规范**：
  1. `AndroidManifest.xml` 中 MainActivity 的 Intent 过滤器声明：
     ```xml
     <activity 
         android:name=".MainActivity"
         android:launchMode="singleTask"
         android:exported="true">
         <intent-filter>
             <action android:name="android.intent.action.MAIN" />
             <category android:name="android.intent.category.HOME" />
             <category android:name="android.intent.category.DEFAULT" />
             <category android:name="android.intent.category.LAUNCHER" />
         </intent-filter>
     </activity>
     ```
  2. 引导跳转至默认桌面设置的代码：
     ```kotlin
     val intent = Intent(Settings.ACTION_HOME_SETTINGS)
     startActivity(intent)
     ```

#### 【REQ-103】全方位逃逸防御加固 (Bypass Prevention)
* **需求描述**：必须主动屏蔽并防御通过语音助手、开发者调试、系统截屏分享、下拉通知栏、物理音量键等手段产生的系统逃逸。
* **开发实现规范**：
  1. **禁用语音助手**：在 `DeviceOwner` 激活后，调用 `dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_VOICE_ASSISTANTS)`。
  2. **禁用开发者选项与 USB 调试**：调用 `dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_DEBUGGING_FEATURES)`。
  3. **禁用截屏与截屏分享**：
     * 在 Activity 的 `onCreate` 中加入 `window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)`。
     * 调用 `dpm.setScreenCaptureDisabled(adminComponent, true)`。
  4. **拦截物理音量按键**：在 Activity 中重写 `onKeyDown`，消费音量键事件，防止系统音量 UI 弹出：
     ```kotlin
     override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
         return if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
             // 拦截并在此处自定义音量控制逻辑（如：限制最高音量不超过 70%）
             true
         } else {
             super.onKeyDown(keyCode, event)
         }
     }
     ```

---

### 3.2 退出机制与验证模块 (Security Verification)

#### 【REQ-201】隐藏入口与触发手势
* **需求描述**：在主界面和全屏 WebView 加载界面的右上角，设计一个隐藏的透明区域（大小限制在 **80dp x 80dp** 触控范围内，确保高容错率）。
* **触发手势**：
  * 家长在 **2 秒内连续点击该区域 5 次**，激活家长验证弹窗。
  * 必须使用防抖和计时逻辑（例如利用 Kotlin Flow 的 debounce 或在点击事件中记录时间戳数组，判断第 `i` 次与第 `i-4` 次的时间差是否小于 2000 毫秒）。

#### 【REQ-202】动态退出码与家长锁 (Parental Verification)
* **需求描述**：验证弹窗提供“动态口算题”与“固定密码”两种模式（可在设置中切换，默认为动态口算题，以防止孩子通过记住家长按键顺序来解锁）。
* **开发实现规范**：
  1. **动态口算题**：随机生成一个加减法或乘法算式，例如 `28 + 15 = ?` 或 `7 * 8 = ?`。数字范围：加减法和为 100 以内，乘法为九九乘法表。
  2. **固定密码**：支持数字 PIN 码设置，数据需通过加密字段保存在 Room 中。
  3. 验证通过后，才能弹出管理后台选项或执行退出操作。

#### 【REQ-203】安全退出 Lock Task 流程
* **需求描述**：在验证成功并点击“退出应用”后，必须按标准程序解除锁定并让家长能够切回原生的系统桌面。
* **开发实现规范**：
  1. 执行退出锁死：
     ```kotlin
     stopLockTask()
     ```
  2. 解锁后，必须自动拉起系统桌面选择器，让家长可以选择原生桌面：
     ```kotlin
     val intent = Intent(Intent.ACTION_MAIN).apply {
         addCategory(Intent.CATEGORY_HOME)
         flags = Intent.FLAG_ACTIVITY_NEW_TASK
     }
     startActivity(intent)
     ```

#### 【REQ-204】儿童健康使用限时控制 (Time Limiter)
* **需求描述**：家长可以配置“单次使用时长限制”（如 30 分钟）或“每日最大可用累计时间”。
* **开发实现规范**：
  1. 使用 Android `AlarmManager` 或 `WorkManager` 进行后台计时。
  2. 时间耗尽时，界面自动切入全屏锁死屏（非 Lock Task 解锁，而是覆盖一层全屏的 Compose 锁屏界面），并播放“休息时间到了，小眼睛该休息啦！”的儿童友好提示动画。此时必须通过 [REQ-202] 的家长验证才能获得额外使用时间。

---

### 3.3 Web 应用管理器 (Admin Console)

#### 【REQ-301】Room 数据库 schema 设计
* **需求描述**：使用 Room 数据库维护 Web 应用列表。
* **开发实现规范**：
  * **Entity 定义**：
    ```kotlin
    @Entity(tableName = "web_apps")
    data class WebAppEntity(
        @PrimaryKey(autoGenerate = true) val id: Int = 0,
        @ColumnInfo(name = "title") val title: String,
        @ColumnInfo(name = "url") val url: String,
        @ColumnInfo(name = "icon_path") val iconPath: String?, // 本地图标名称或自定义图片路径
        @ColumnInfo(name = "is_preset") val isPreset: Boolean = false, // 是否是开箱预设应用
        @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis()
    )
    ```

#### 【REQ-302】数据初始化与预设 (Preset Data)
* **需求描述**：应用首次安装并完成 Room 数据库建表时，默认静默预置 3 个安全无广告的儿童 Web 应用，保证“开箱即用”。
* **预设网址名单**：
  1. **Scratch Mit**：`https://scratch.mit.edu/` (少儿编程，域名白名单匹配 `scratch.mit.edu`)
  2. **PBS Kids**：`https://pbskids.org/` (儿童早教与小游戏，域名白名单匹配 `pbskids.org`)
  3. **NASA Kids' Club**：`https://www.nasa.gov/learning-resources/kids-club/` (科普，域名白名单匹配 `www.nasa.gov`)

#### 【REQ-303】后台管理 UI (Jetpack Compose)
* **功能包含**：
  1. 极简卡片式列表，显示应用名称、URL 缩略图（或预设 Icon），并提供“编辑”和“删除”按钮。
  2. “添加应用”悬浮按钮，弹出表单输入：应用标题 (Title) 和 URL（表单必须使用正则表达式校验 URL 格式，且强制以 `https://` 开头。若是 `http://`，自动转为 `https://` 并进行网络探测）。

---

### 3.4 全屏沙箱 WebView 容器 (Sandbox Web Container)

为了实现绝对的安全和最佳性能，WebView 部分是技术细节最密集的模块。

#### 【REQ-401】独立进程运行 WebView
* **需求描述**：由于 WebView 加载的大型 H5 游戏极易发生内存泄漏和 OOM 崩溃，WebView Activity 必须运行在独立的子进程中。
* **开发实现规范**：
  在 `AndroidManifest.xml` 中将 WebViewActivity 声明为独立进程：
  ```xml
  <activity
      android:name=".WebViewActivity"
      android:process=":webview"
      android:hardwareAccelerated="true"
      android:screenOrientation="sensorLandscape"
      android:configChanges="orientation|screenSize|keyboardHidden" />
  ```
  * **优势**：当 WebView 发生 OOM 崩溃时，只有 `:webview` 子进程会被杀掉，主进程依然维持在 Lock Task 状态，不会退出桌面，主进程检测到子进程异常退出后可友好重拉 WebView 界面。

#### 【REQ-402】WebSettings 核心安全加固
* **开发实现规范**：
  必须对 WebView 实例设置以下硬性安全参数，绝不允许遗漏：
  ```kotlin
  webView.settings.apply {
      javaScriptEnabled = true           // 支持 H5 游戏所必需的 JS
      domStorageEnabled = true           // 支持小游戏存档与数据缓存所必需的 DOM Storage
      databaseEnabled = true
      
      // 绝对禁止访问本地文件系统（防范越权读取敏感数据）
      allowFileAccess = false
      allowContentAccess = false
      allowFileAccessFromFileURLs = false
      allowUniversalAccessFromFileURLs = false
      
      // 屏蔽新窗口弹出（防止 window.open 逃逸）
      setSupportMultipleWindows(false)
      javaScriptCanOpenWindowsAutomatically = false
      
      // 隐私与输入安全
      saveFormData = false
      savePassword = false
      setGeolocationEnabled(false)       // 禁止地理位置请求
      
      // 音视频手势设置
      mediaPlaybackRequiresUserGesture = false // 允许游戏音效自动播放，无需用户手势触发
      
      // 混合内容限制：必须强制全 HTTPS
      mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
  }
  ```

#### 【REQ-403】多维度跳转过滤与拦截 (Link & Scheme Filters)
* **开发实现规范**：
  1. **外部 Scheme 拦截**：重写 `WebViewClient.shouldOverrideUrlLoading`。只允许 `http://` 和 `https://`。其余所有类似 `market://`、`tbopen://` 等 Scheme 一律拦截，防止其拉起外部应用。
  2. **域名白名单/Host 匹配拦截**：解析即将跳转的 URL。如果该 URL 的 Host 与家长配置的该应用原始 URL Host 不匹配（即非本域名下的跳转），则进行拦截，显示“非安全外部链接，已被家长助手拦截”的弹窗，阻止页面加载。
  3. **禁止 WebView 内部权限申请**：重写 `WebChromeClient.onPermissionRequest`，默认调用 `request.deny()` 拒绝一切摄像头、麦克风等权限获取。
  4. **阻断文件下载**：重写 `DownloadListener`，直接拦截所有下载事件，禁止网页静默下载任何 APK：
     ```kotlin
     webView.setDownloadListener { url, _, _, _, _ ->
         // 提示：“下载功能已受阻，若要下载应用请联系家长。”
     }
     ```

#### 【REQ-404】物理返回按键适配 (Back Navigation)
* **需求描述**：在全屏 WebView 页面内，点击设备物理返回键（或滑动手势）时，应优先执行网页的 `goBack()`。
* **开发实现规范**：
  * 使用 Compose 提供的 `BackHandler` 拦截返回键：
    ```kotlin
    BackHandler(enabled = true) {
        if (webView.canGoBack()) {
            webView.goBack() // 网页逐级返回
        } else {
            // 已无历史记录，退出 WebView 返回到本应用的主网格页面（而不是关闭应用或退出沙箱）
            finish() 
        }
    }
    ```

#### 【REQ-405】严格的 SSL 证书安全控制
* **开发实现规范**：
  * **绝不允许**在 `onReceivedSslError` 中直接调用 `handler.proceed()`。这会导致中间人攻击，且无法通过 Google Play store 的安全机审。
  * **必须**执行 `handler.cancel()`，并向用户展示一个精美的、应用内置的“网络安全异常”提示页面。

---

## 4. 非功能性需求与 UI/UX 规范

### 4.1 UI/UX 儿童专用视觉规范 (Compose)
* **按钮尺寸**：主页及 WebView 页面的“返回”等交互按钮，触控目标区域大小必须设置为 **72dp x 72dp** 以上（视觉大小可为 56dp，但必须使用 padding 或 `Modifier.sizeIn(minWidth = 72.dp, minHeight = 72.dp)` 扩展无形点击区）。
* **圆角规范**：主界面网格卡片必须使用不小于 `20.dp` 的圆角大小（例如 `RoundedCornerShape(20.dp)`），配合明亮饱满的色彩系统。
* **点击动效**：按钮点击必须实现仿“Q弹”的弹性缩放（Scale）动画，过渡时长为 200ms。配合设备微小震动反馈（通过 `LocalHapticFeedback.current` 调用 `HapticFeedbackType.LongPress`）。

### 4.2 性能与内存指标
* **包体积控制**：优化 R8/ProGuard 混淆规则，确保最终打包生成的 Release 渠道 APK 体积控制在 **5MB** 以内。
* **内存强制回收**：当离开子进程 WebView 页面返回主网格页面时，Activity 的 `onDestroy` 必须显式执行清理：
  ```kotlin
  webView.loadUrl("about:blank")
  webView.clearHistory()
  webView.clearCache(true)
  webView.destroy()
  ```

---

## 5. 验收标准与测试用例 (Acceptance Criteria)

开发人员在交付前必须自行跑通以下用例：

### 5.1 Kiosk 锁定与防逃逸测试 (TC-100 系列)
* **TC-101**：应用在 Device Owner 状态下启动并运行。**预期结果**：静默进入 Lock Task 锁定，系统返回键、Home 键、最近任务键全部失效。
* **TC-102**：尝试在屏幕边缘下拉通知栏。**预期结果**：状态栏仅显示系统时钟与电量，下拉操作无效，无法进入快速设置与通知中心。
* **TC-103**：说出 "Hey Google" 唤醒词或长按电源键。**预期结果**：无法呼出语音助手，不弹出系统关机/重启设置菜单。
* **TC-104**：强制断电重启设备。**预期结果**：设备重启进入系统后，第一秒直接拉起本应用，并立即进入 Lock Task 状态，无法看到原生系统桌面。

### 5.2 家长验证与退出测试 (TC-200 系列)
* **TC-201**：点击右上角透明区。**预期结果**：点击 1-4 次无反应；2 秒内连续点击 5 次顺利弹出家长验证框。
* **TC-202**：在验证框中输入错误的动态口算答案或错误的 PIN 码。**预期结果**：提示验证错误，不可进入后台，保持锁定。
* **TC-203**：输入正确验证码并点击“退出”。**预期结果**：顺利解除锁定状态，自动跳转到系统桌面选择器，让家长可以选择原生桌面返回。

### 5.3 WebView 沙箱安全测试 (TC-300 系列)
* **TC-301**：点击包含第三方广告跳转域名链接（非原 Host 跳转）。**预期结果**：拦截跳转，弹出域名限制警告。
* **TC-302**：点击网页中的下载 APK 文件按钮。**预期结果**：下载被安全拦截，无任何文件被下载到手机，并显示友好提示。
* **TC-303**：网页执行 `window.open`。**预期结果**：无任何新窗口弹出，页面在当前窗口保持原样。
* **TC-304**：网页出现 SSL 证书错误（如使用自签名证书访问）。**预期结果**：禁止加载，并显示“网络环境不安全”内置警示页，严禁放行。
