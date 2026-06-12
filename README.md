# Child Kiosk Browser · 儿童防误触全屏 Web 应用管理器

[![Android CI](https://github.com/xxxily/child-kiosk-browser/actions/workflows/android.yml/badge.svg)](https://github.com/xxxily/child-kiosk-browser/actions/workflows/android.yml)
[![Latest Release](https://img.shields.io/github/v/release/xxxily/child-kiosk-browser?include_prereleases&label=release)](https://github.com/xxxily/child-kiosk-browser/releases/latest)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

一个面向 **2-8 岁儿童** 的 Android 全屏 Kiosk 浏览器/桌面应用，专为家庭场景下的安全沙箱使用而设计。
基于 **Device Owner + Lock Task Mode + WebView 独立进程** 实现 **绝对防误触、防逃逸、防广告、防下载** 的儿童 Web 容器。

> 本应用本身不包含任何具体的游戏或学习内容，仅作为容器：家长在后台白名单中添加可信网址（如 [Scratch](https://scratch.mit.edu/)、[PBS Kids](https://pbskids.org/)、[NASA Kids' Club](https://www.nasa.gov/learning-resources/kids-club/) 等），孩子在锁定的安全壳内访问。

---

## 功能亮点

| 维度 | 实现要点 |
| :--- | :--- |
| 系统级锁定 | Device Owner + Lock Task Mode，静默锁屏，无系统弹窗，无法通过 Home/Recents/Back 退出 |
| 防逃逸 | 屏蔽语音助手、USB 调试、状态栏、通知栏、电源菜单、安全模式、出厂重置、未知来源安装 |
| 自启动 | 设置为系统默认 HOME，开机即进入 Kiosk，断电重启不会暴露原生桌面 |
| 防截屏 | FLAG_SECURE + setScreenCaptureDisabled 双重防御 |
| 沙箱 WebView | 独立 `:webview` 子进程运行，OOM 不会拖垮主进程；强制 HTTPS、拒绝文件协议、禁止 window.open、拦截下载、拒绝麦克风/摄像头/位置权限 |
| 网络层广告拦截 | 在 `shouldInterceptRequest` 中按 50+ 广告/追踪域名黑名单丢弃请求，主域同源校验拦截外链跳转 |
| 严格 SSL | `onReceivedSslError` 强制 cancel，绝不 proceed，遇到证书错误展示应用内安全提示页 |
| 家长验证 | 右上角 80dp 隐藏区域 2 秒内连击 5 次 → 动态口算题 / 数字 PIN 二选一 |
| 时长管理 | 单次时长 + 每日累计时长双限制，超时切入"小眼睛该休息啦"全屏提醒 |
| 儿童 UI | 高饱和明亮色彩、72dp+ 大触控目标、20dp 以上圆角、Q 弹缩放微动画 + 触觉反馈 |
| 独立桌面 | Compose 网格列表，预设 Scratch / PBS Kids / NASA Kids' Club，支持新增/编辑/删除（预设不可删） |

---

## 技术栈

- **语言**：Kotlin 1.9.22
- **UI**：Jetpack Compose（Material3）+ Compose BOM 2023.10
- **数据库**：Room 2.6.1
- **协程**：kotlinx-coroutines 1.7.3
- **WebView**：AndroidX WebKit 1.10.0
- **构建**：Android Gradle Plugin 8.2.2 / Gradle 8.5 / JDK 17
- **目标版本**：minSdk 28 (Android 9.0+) · targetSdk 34 (Android 14)

---

## 项目结构

```
app/
├── src/main/java/com/example/childkiosk/
│   ├── MainActivity.kt               # 主桌面 Activity，承载 Lock Task 启停
│   ├── WebViewActivity.kt            # 独立进程 WebView 容器
│   ├── MyDeviceAdminReceiver.kt      # Device Admin 广播接收
│   ├── BootReceiver.kt               # 开机自启动到 Kiosk
│   ├── data/
│   │   ├── AppDatabase.kt
│   │   ├── WebAppEntity.kt / WebAppDao.kt
│   │   └── SystemConfigEntity.kt / SystemConfigDao.kt
│   ├── ui/
│   │   ├── KioskMainScreen.kt        # 儿童网格主页
│   │   ├── AdminConsoleScreen.kt     # 家长后台
│   │   ├── ParentVerificationDialog.kt  # 口算 / PIN 验证
│   │   └── theme/Theme.kt            # 儿童友好的色彩/形状/字体
│   └── util/
│       ├── AdBlocker.kt              # 广告/追踪域名黑名单
│       ├── HashUtils.kt              # SHA-256 PIN 哈希
│       ├── SystemUiHelper.kt         # 沉浸式全屏
│       └── TimeLimiter.kt            # 单次/每日时长限制
└── src/main/res/
    ├── xml/
    │   ├── device_admin_policies.xml
    │   ├── network_security_config.xml
    │   ├── backup_rules.xml
    │   └── data_extraction_rules.xml
    ├── drawable/ic_launcher_*.xml
    └── values/{colors,strings,themes}.xml
docs/
├── child_kiosk_browser_requirements.md   # 产品需求规格
├── android_kiosk_research_report.md      # 技术调研报告
├── CHANGELOG.md                          # 版本变更日志
└── RELEASE_NOTES.md                      # 当前发布说明（用于 CI 推送 Release）
```

---

## 本地开发与构建

### 1. 环境准备

- macOS / Linux / Windows
- JDK 17（建议 Temurin）
- Android Studio Hedgehog (2023.1.1) 或更高
- 一台 Android 9.0+ 的真机或平板（推荐平板，注意 sensorLandscape）

```bash
git clone https://github.com/xxxily/child-kiosk-browser.git
cd child-kiosk-browser
./gradlew :app:assembleDebug          # 调试包
./gradlew :app:assembleRelease        # 发布包（默认仍使用 debug 签名以便侧载）
```

构建产物：

- `app/build/outputs/apk/debug/app-debug.apk`
- `app/build/outputs/apk/release/app-release.apk`

> Release 包默认使用 debug keystore 签名，便于直接侧载。如需上架商店或自定义签名，请在 `app/build.gradle.kts` 中配置 `signingConfigs` 并替换 release 块。

### 2. ADB 安装到设备

```bash
adb install -r -t app/build/outputs/apk/release/app-release.apk
```

---

## 部署指南：家长如何在儿童平板上完整启用

> 由于本应用必须以 **Device Owner（设备所有者）** 模式运行才能完全锁屏，配置过程比普通 App 复杂。请家长按以下步骤操作。

### 步骤 1：准备一台"干净"的 Android 平板

Device Owner **必须在设备未绑定任何账号**（无 Google 账号、无系统主账号）的状态下才能激活。已使用过的平板必须先恢复出厂设置。

1. 进入 **设置 → 系统 → 重置选项 → 恢复出厂设置**
2. 重启完成后进入开机向导
3. **跳过 Wi-Fi 连接、跳过 Google 账号登录、跳过指纹/人脸**，直接进入桌面（如果系统强制要求账号，可改用一台无 GMS 的国产/教育平板）

### 步骤 2：开启 USB 调试

1. **设置 → 关于本机** → 连续点击"版本号"7 次
2. 返回 **设置 → 系统 → 开发者选项**
3. 打开 **USB 调试**

### 步骤 3：安装 APK

```bash
# 方式一：从 GitHub Releases 下载
# https://github.com/xxxily/child-kiosk-browser/releases/latest

# 方式二：通过电脑 ADB 安装
adb install -r -t child-kiosk-browser-x.y.z-release.apk
```

### 步骤 4：激活 Device Owner

将平板通过 USB 连接电脑，确保 ADB 已识别（`adb devices` 能看到设备）：

```bash
adb shell dpm set-device-owner com.example.childkiosk/.MyDeviceAdminReceiver
```

成功提示：

```
Success: Device owner set to package ComponentInfo{com.example.childkiosk/com.example.childkiosk.MyDeviceAdminReceiver}
Active admin: ComponentInfo{...}
```

> ❗ 如果提示 `Not allowed to set the device owner because there are already several users on the device`，说明设备未恢复出厂或已绑定账号，回到步骤 1。

### 步骤 5：把本应用设为默认主屏幕

打开本应用 → 在引导页点击「设置本应用为默认主屏幕」 → 系统弹出 Launcher 选择 → 选 **儿童防误触主屏** → 始终。

完成以上 5 步后，平板将进入完全锁定状态：

- 重启后第一时间进入本应用
- Home/Back/Recents 键无效
- 状态栏只剩时间/电量/Wi-Fi（无下拉）
- 长按电源不弹出关机菜单
- 语音助手、USB 调试、未知来源安装、安全模式、出厂重置等被禁用

### 步骤 6（可选）：连接 Wi-Fi 并配置家长口令

激活完成后即可放心连接 Wi-Fi。建议立刻在家长后台中：

1. 设置 4 位数字 PIN 码作为退出口令（默认是动态口算题）
2. 配置每日累计 / 单次最长游戏时长
3. 添加家长选定的网站白名单

---

## 家长操作手册

### 进入家长后台

在主页或网页内，**用一根手指在右上角 80dp 区域内 2 秒内连续点击 5 次** → 弹出验证窗 → 通过验证后进入控制中心。

### 退出 Kiosk 模式

控制中心 → "退出并安全解锁（返回系统桌面）" → 自动跳转到系统桌面选择器，选择原生 Launcher 即可回到普通系统。

### 解除 Device Owner（彻底卸载本应用）

```bash
# 在电脑上通过 ADB 执行
adb shell dpm remove-active-admin com.example.childkiosk/.MyDeviceAdminReceiver
adb uninstall com.example.childkiosk
```

> 一旦移除 Device Owner，本应用将变回普通应用。如需重新激活，必须再次恢复出厂设置。

---

## 安全模型说明

| 风险 | 防御方式 |
| :--- | :--- |
| 重启逃逸 | 自定义 Launcher + BootReceiver 自动拉起 |
| Home/Back/Recents | Lock Task Mode + setLockTaskFeatures 屏蔽 |
| 状态栏/通知栏 | `setStatusBarDisabled(true)` + `LOCK_TASK_FEATURE_NOTIFICATIONS` 关闭 |
| 语音助手呼出 | `DISALLOW_VOICE_ASSISTANTS` 用户限制 |
| ADB 远程注入 | `DISALLOW_DEBUGGING_FEATURES` 限制 |
| 截屏分享逃逸 | `FLAG_SECURE` + `setScreenCaptureDisabled(true)` |
| 网页 window.open | `setSupportMultipleWindows(false)` + `onCreateWindow` 返回 false |
| 外部 Scheme | `shouldOverrideUrlLoading` 仅放行 http/https |
| 跨域跳转到广告站 | 主域同源校验，非同源直接拦截显示警告页 |
| 网页内广告/追踪 | `shouldInterceptRequest` 命中 AdBlocker 黑名单返回空响应 |
| 静默下载 APK | `setDownloadListener` 拦截，不写入磁盘 |
| 麦克风/摄像头/位置 | `WebChromeClient.onPermissionRequest` 默认 deny |
| SSL 证书错误 | `handler.cancel()` + 应用内安全警告页 |
| WebView OOM | 独立 `:webview` 子进程，崩溃不影响主进程 Kiosk 状态 |

---

## 发布与版本管理

### CI/CD

- 推送到 `main`/`master` 分支：自动跑 `assembleDebug` / `assembleRelease`，APK 作为 Artifact 上传
- 推送 `v*` 标签：在上述基础上自动构建并发布 GitHub Release，附带 APK 与 [`docs/RELEASE_NOTES.md`](docs/RELEASE_NOTES.md) 中的发布说明

### 发版流程

```bash
# 1. 修改 app/build.gradle.kts 中的 versionName / versionCode
# 2. 更新 docs/CHANGELOG.md 与 docs/RELEASE_NOTES.md
# 3. 提交并打 tag
git add .
git commit -m "chore(release): v0.1.0"
git tag v0.1.0
git push origin main --tags
# 4. GitHub Actions 自动完成构建 + Release 发布
```

详见 [`docs/CHANGELOG.md`](docs/CHANGELOG.md)。

---

## 已知限制

- 必须 **恢复出厂** 才能首次激活 Device Owner，无法在已使用的平板上"无痕"安装
- 仅支持 Android 9.0 (API 28) 及以上系统
- Release APK 默认使用 debug keystore 签名，不可上架 Google Play，仅适用于家庭侧载
- 部分 OEM（华为/小米/Vivo/Oppo）对 Device Policy API 的实现差异较大，少数限制项可能在特定固件上无效，已通过 `runCatching` 兜底
- 由于使用了 `FLAG_SECURE`，截屏会显示黑屏（这是设计预期）

---

## 贡献与反馈

- Issue: <https://github.com/xxxily/child-kiosk-browser/issues>
- 调研背景：[`docs/android_kiosk_research_report.md`](docs/android_kiosk_research_report.md)
- 需求规格：[`docs/child_kiosk_browser_requirements.md`](docs/child_kiosk_browser_requirements.md)

---

## License

MIT © xxxily. 详见 [`LICENSE`](LICENSE)。
