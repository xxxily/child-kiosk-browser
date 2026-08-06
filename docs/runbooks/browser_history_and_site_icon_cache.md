# 浏览历史去重与预设网站图标缓存运行手册

## 适用症状

- 打开一个网站后，浏览历史在几秒内出现多条相同 URL、相同时间附近的记录。
- 从悬浮球无法直接查看浏览历史，或为了查看历史而重新启动 WebView 宿主，导致现有标签状态丢失。
- 默认白名单应用长期显示内置通用图标，网站辨识度低；列表渲染又不应同时抓取大量 favicon。

## 根因

### 历史记录重复

旧实现只在单个 `WebViewActivity` 实例内保存最近一次 URL 和时间。WebView 宿主重建、正常/锁定宿主切换、多标签回调接近同时到达时，各 Activity 的内存防重状态互不共享，多个后台协程仍可能分别插入 Room。

### 默认网站图标

`icon_path` 同时承担用户选择图标和网站图片两种语义。直接覆盖它会丢失预设应用原有的内置图标兜底；在白名单列表中即时发现网站图标又会造成批量网络请求和重复下载。

## 当前修复策略

### 历史写入

- `BrowserHistoryDao.recordVisit()` 在 Room 事务内查询同 URL 最近 30 秒记录。
- 命中时更新原记录的标题、访问时间和应用关联，并删除同窗口内的额外重复行；未命中才插入新行。
- 数据库 v9 → v10 迁移会清理升级前已经存在的 30 秒窗口重复记录。
- Activity 内存变量不再承担正确性，只由数据库事务处理跨回调、跨 Activity 和跨进程串行化。

### 悬浮球历史入口

- 默认浏览分组新增 `browser.history` / “历史”动作。
- 宿主通过 `BrowserHistoryActivity` 展示与后台设置复用的 Compose 历史页面。
- 从历史中选择 URL 使用 Activity Result 返回原宿主；不要从历史页重新创建同任务 WebViewActivity，否则锁定模式下可能产生双宿主或丢失原标签。

### 预设应用网站图标

- 数据库新增独立 `site_icon_path`，只保存自动发现后已落盘的网站图标；`icon_path` 继续作为内置/用户自定义兜底。
- 只有实际打开预设应用后才延迟触发自动发现。已有有效缓存时不创建刷新任务，普通白名单列表不会批量联网。
- 自动刷新全局单通道执行；同应用请求合并。网站图标发现沿用成功约 7 天、失败约 12 小时的候选缓存。
- 图片下载按 URL 合并并限制并发，成功文件保存在 App `filesDir/web_app_icons/`；UI 只读取有效本地文件，文件缺失或下载失败立即回退 `icon_path`。
- 主进程通过 Room 多实例失效通知获得 `site_icon_path` 更新，不依赖 `:webview` 进程的 SharedPreferences 新鲜度。

## 验证信号

1. 连续刷新或多标签同时打开同一 URL，30 秒内数据库只保留一条记录，标题和时间更新为最近值。
2. 升级旧数据库后，原有同 URL/近时间重复行被合并，间隔超过 30 秒的真实再次访问仍保留。
3. 在首页和网页内展开悬浮球，均可看到“历史”；进入历史后返回，原 WebView 标签、前进后退状态仍在。
4. 预设应用第一次打开后查看日志：成功时出现 `WebAppSiteIcon: Cached website icon after app open`；失败时保留内置图标。
5. 返回首页或白名单管理后，成功缓存的网站使用真实站点图标；再次打开不产生新的图标网络下载。
6. 删除或损坏缓存文件后，UI 不显示破图，自动回退内置图标，并在下次打开该预设应用时重新尝试。

## 回归命令

```bash
JAVA_HOME="/path/to/jdk17" ./gradlew \
  :app:testStandardDebugUnitTest \
  :app:testEnhancedDebugUnitTest \
  :app:assembleDebug \
  :app:assembleRelease
```

重点单测：

- `BrowserHistoryDaoTest`
- `WebAppIconCacheTest`
- `WebAppSiteIconUpdaterTest`
