# Changelog

本项目所有显著变更都将记录在此文件中。

格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，版本遵循 [Semantic Versioning](https://semver.org/lang/zh-CN/)。

## [Unreleased]

## [0.0.3] - 2026-06-13

### Added — 新增

- **自定义网址图标与 Favicon 自动推导**：
  - 引入了 `Coil` 异步图片库加载 URL 自定义图标，支持网络缓存及降级回退显示内置 Star 图标。
  * `AddEditWebAppDialog` 监听 `urlInput`，自动解析并推导出其默认 favicon 地址。
  * 提供了自定义图标 URL 文本输入卡片与单选框，可随时与内置图标切换使用，默认自动勾选填充推导的 Favicon。
- **屏幕显示方向配置**：
  - 支持自适应（AUTO）、锁定横屏（LANDSCAPE）和锁定竖屏（PORTRAIT）三种方向。
  * `MainActivity.onCreate` 和 `WebViewActivity.onCreate` 早期同步应用该配置，杜绝 Activity 旋转和闪动。
  * 在管理后台提供了“屏幕显示方向”卡片，修改后即时旋转屏幕。
- **首屏图标网格自适应大小**：
  - 支持小（SMALL）、中（MEDIUM）和大（LARGE）三种图标及文字大小选项卡，自适应网格密度，适合横屏展示更多应用。
- **下拉状态栏 3 秒自动隐藏**：
  - 针对 `MainActivity` 和 `WebViewActivity` 增加了窗口 System UI 监听器，在下滑呼出半透明灰色块后，3秒内自动调用沉浸模式进行再次隐藏，防止其在部分 OEM 上卡死占位。

### Fixed — 修复

- 修复了添加/编辑应用 Dialog 在横屏模式下无法滚动的体验 Bug（在表单 Column 中引入 `verticalScroll`）。
- 重构 `isValidUrl` 校验逻辑，利用 Android 原生 `Patterns.WEB_URL`，放行并完美兼容局域网 IP（例如 `192.168.1.1`）以及 `localhost` 的测试网络。
- 允许添加明文 HTTP 网址（修改 `network_security_config.xml` 与 Manifest），且在输入以 `http://` 开头时提供橙色警告以提醒传输风险。

## [0.0.2] - 2026-06-13

引入**分级防护模型**：不再把 Device Owner 当作「能否使用本应用」的前提，而是当作「锁得多死」的等级。普通侧载安装即可开箱使用，按当前可用权限自动选择最高防护档。

### Added — 新增

- **分级防护模型（三档）**
  - **Tier 1 · Device Owner 完全锁定**：与 v0.0.1 一致，企业级全方位防逃逸（保持不变）
  - **Tier 2 · 屏幕固定软锁（默认）**：非 Device Owner 时调用系统「屏幕固定（Screen Pinning）」拦截 Home/最近任务键，无需恢复出厂、无需任何特殊权限即可使用
  - **Tier 3 · 无系统级锁定**：仅沉浸式全屏 + 自定义 Launcher + 家长验证退出，适合开发调试与临时体验
  - `MainActivity.onResume` 自动选档：是 Device Owner 走 Tier 1，否则读取家长配置（默认 Tier 2）
  - 新增 `util/KioskPrefs`：以 SharedPreferences 持久化非 Device Owner 场景下的防护等级
- **家长后台**
  - 新增「防护等级」卡片：非 Device Owner 时可在 Tier 2 / Tier 3 间切换，并内置 Device Owner 升级引导（复制 ADB 脚本 + 跳转默认桌面设置）；已是 Device Owner 时仅展示状态
  - 新增「退出并安全解锁」按钮

### Changed — 变更

- 儿童主网格**不再以 Device Owner 为使用前提**：原先未激活 Device Owner 时整屏被激活引导页占据，现在主网格始终可用，激活引导移入家长后台
- `stopLockTaskMode` 兼容非 Device Owner 场景：用户限制清理调用仅在 Device Owner 时执行，软锁场景安全退出
- README 重写部署指南为「路径 A 普通侧载 / 路径 B Device Owner 完全锁定」双路径，新增分级防护模型说明

> **推荐做法说明（本版本由 AI 协助实现并记录）**：默认档位选用 **Tier 2 屏幕固定软锁**（而非无锁定），在「免特殊权限」与「基础防逃逸」间取平衡；不在应用内提供 Device Owner 降级入口（降级属高风险操作，需 ADB `dpm remove-active-admin`）；防护等级用 SharedPreferences 而非 Room 存储，因选档决策发生在 Compose Room Flow 订阅之前、需同步读取。如需调整可在后续版本修改。

## [0.0.1] - 2026-06-13

首个可用预览版本。基于 `docs/child_kiosk_browser_requirements.md` 与 `docs/android_kiosk_research_report.md` 完整落地。

### Added — 新增

- **Kiosk 核心**
  - `MainActivity` 自动启用 Lock Task Mode，前台时强制锁定，无系统弹窗
  - `setLockTaskFeatures(LOCK_TASK_FEATURE_SYSTEM_INFO)`：仅保留状态栏系统信息
  - 多维度 User Restriction：`DISALLOW_VOICE_ASSISTANTS`、`DISALLOW_DEBUGGING_FEATURES`、`DISALLOW_INSTALL_UNKNOWN_SOURCES`、`DISALLOW_FACTORY_RESET`、`DISALLOW_SAFE_BOOT`、`DISALLOW_ADD_USER`、`DISALLOW_USB_FILE_TRANSFER`
  - `setScreenCaptureDisabled(true)` + `FLAG_SECURE` 双重防截屏
  - `setStatusBarDisabled(true)` + `setKeyguardDisabled(true)`
  - 自定义 Launcher（`category.HOME`）+ `BootReceiver` 实现重启自启动
  - 沉浸式全屏（隐藏状态栏与导航栏）
  - 物理音量键拦截，避免儿童误触刺耳音量条
- **沙箱 WebView**
  - `:webview` 独立子进程运行，OOM 不会拖垮主进程
  - 完整 WebSettings 加固：禁文件协议、禁 window.open、禁地理位置、禁表单/密码保存、强制 HTTPS、禁混合内容
  - `shouldOverrideUrlLoading` 仅放行 http/https，并按主域同源校验跨域跳转
  - `shouldInterceptRequest` 接入 `AdBlocker`，命中 50+ 广告/追踪域名直接返回空响应
  - `onReceivedSslError` 强制 `handler.cancel()` + 应用内安全警告页
  - `WebChromeClient.onPermissionRequest` 默认 deny；`onCreateWindow` 返回 false
  - `setDownloadListener` 拦截所有下载，禁止 APK 落盘
  - `BackHandler`：优先 `webView.goBack()`，无历史时触发家长验证后才允许退出
  - 退出时彻底销毁 WebView：loadUrl(blank) + clearHistory + clearCache + destroy
- **家长后台**
  - 右上角 80dp 隐藏区域 2 秒内连击 5 次触发家长验证
  - 动态口算题（加减法 100 内 / 九九乘法表）+ 4 位数字 PIN 码两种验证模式
  - PIN 码以 SHA-256 哈希存储，可在后台随时修改
  - 单次时长（0-120 分钟可调）+ 每日累计时长（0-240 分钟可调）双重限制
  - 时长耗尽后切入"小眼睛该休息啦"全屏提醒页，验证家长身份后可延长 30 分钟
  - Web 应用白名单：增/删/改/排序，URL 自动 https 转换 + 网络可达性预检（连通失败可二次确认强行保存）
  - 5 个内置图标可选（游戏机/火箭/拼图/书本/画笔）
  - 预设 3 个白名单：Scratch、PBS Kids、NASA Kids' Club（开箱即用，不可删除）
- **儿童 UI**
  - Material3 自定义主题：暖橙 / 天蓝 / 草绿 高饱和儿童友好色彩
  - 网格主页：72dp+ 大触控目标 + 24dp 圆角 + Q 弹缩放微动画 + 触觉反馈
  - 自适应列数 LazyVerticalGrid，平板大屏体验自然
- **构建/CI**
  - GitHub Actions 工作流：分支推送构建 Debug+Release APK 上传 Artifact
  - 推送 `v*` tag 时自动发布 GitHub Release 并上传 APK
  - 阿里云 Maven 镜像加速依赖下载
  - R8/ProGuard 启用，Room 实体/DAO/Receiver 关键反射点已加 keep 规则

### Security — 安全

- 默认禁用 backup（`allowBackup="false"`），同步配置 `data_extraction_rules` / `backup_rules`
- 配置 `network_security_config` 禁止明文流量（cleartext）

### Documentation — 文档

- 完整 README：功能、技术栈、本地构建、ADB 部署 Device Owner、家长操作手册、安全模型、发版流程
- 调研报告 `docs/android_kiosk_research_report.md`
- 需求规格 `docs/child_kiosk_browser_requirements.md`

[Unreleased]: https://github.com/xxxily/child-kiosk-browser/compare/v0.0.3...HEAD
[0.0.3]: https://github.com/xxxily/child-kiosk-browser/compare/v0.0.2...v0.0.3
[0.0.2]: https://github.com/xxxily/child-kiosk-browser/compare/v0.0.1...v0.0.2
[0.0.1]: https://github.com/xxxily/child-kiosk-browser/releases/tag/v0.0.1
