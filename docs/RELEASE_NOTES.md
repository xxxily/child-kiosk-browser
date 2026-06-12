## Child Kiosk Browser v0.0.1

首个可公开预览的版本。完整落地需求文档 `docs/child_kiosk_browser_requirements.md` 中所有 REQ-1xx ~ REQ-4xx 模块。

> **说明**：本 APK 使用 debug 签名，仅供调试与家庭内部部署。生产/商用请自行用正式 keystore 重新签名。

### 亮点功能

- 企业级 **Lock Task Mode** 静默锁死，物理键 + 下拉栏 + 语音助手 + 调试 + 重启全方位防逃逸
- WebView **独立进程** + HTTPS 强制 + 同源 Host 白名单 + 50+ 广告域名拦截
- 右上角 80dp 隐藏区域 **2 秒内连击 5 次** 触发家长验证
- 动态口算题 / 4 位 PIN 双模式家长锁
- 单次 + 每日双重时长限制，超时进入"小眼睛该休息啦"亲子提醒页
- 预设 3 个安全儿童网站：Scratch、PBS Kids、NASA Kids' Club
- 完整 Material3 儿童主题：72dp 大触控目标 + Q 弹动效 + 触觉反馈

### 安装与部署

请详细阅读 README 的 [快速开始](https://github.com/xxxily/child-kiosk-browser#-快速开始与部署) 章节。简要流程：

1. **下载 APK**：从下方 Assets 选择 `child-kiosk-browser-0.0.1-release.apk`
2. **平板恢复出厂设置**（关键！Device Owner 仅能在初始化的设备上激活）
3. **跳过开机向导的 WiFi 与账号绑定**，直接进入桌面
4. **开启开发者选项与 USB 调试**
5. **adb install**：
   ```bash
   adb install child-kiosk-browser-0.0.1-release.apk
   ```
6. **激活 Device Owner**：
   ```bash
   adb shell dpm set-device-owner com.example.childkiosk/.MyDeviceAdminReceiver
   ```
   返回 `Active admin set` 即成功。
7. **设为默认主屏幕**：在系统设置 → 默认应用 → 主屏幕中选择"儿童防误触主屏"
8. 重启平板，开机自动进入儿童沙箱

### 家长操作手册

- **进入家长后台**：屏幕 **右上角** 80dp 区域 2 秒内连续点击 5 次 → 通过验证 → "进入系统白名单及时间配置后台"
- **退出 Kiosk 模式**：家长控制中心 → "退出并安全解锁（返回系统桌面）"
- **修改密码 / 切换验证方式**：管理后台 → 家长身份验证配置
- **设置时长**：管理后台 → 儿童健康使用时长限制（拖动 Slider）
- **添加新网站**：管理后台 → 右下角 + 按钮（仅支持 https，自动连通性检测）

### 已知限制

- APK 使用 debug 签名，仅适用于个人测试 / 家庭部署
- Device Owner 一旦激活无法通过卸载本应用解除，必须 `adb shell dpm remove-active-admin` 或恢复出厂设置
- 部分国产 ROM 对 Device Owner 兼容性不一致，建议使用接近原生 Android 的设备（Pixel、AOSP 平板、Lenovo M 系列等）

### 完整变更日志

参见 [CHANGELOG](https://github.com/xxxily/child-kiosk-browser/blob/main/docs/CHANGELOG.md)
