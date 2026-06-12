# Changelog

本项目所有显著变更都将记录在此文件中。

格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，版本遵循 [Semantic Versioning](https://semver.org/lang/zh-CN/)。

## [Unreleased]

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

[Unreleased]: https://github.com/xxxily/child-kiosk-browser/compare/v0.0.1...HEAD
[0.0.1]: https://github.com/xxxily/child-kiosk-browser/releases/tag/v0.0.1
