# 指定站点高性能持续运行与能力增强信息架构需求文档

> 文档版本：1.0  
> 创建日期：2026-07-10  
> 状态：需求细化完成，待产品确认与技术实施  
> 适用范围：Android 9 及以上、targetSdk 34、standard/enhanced 两种构建  
> 关联模块：后台“能力增强”、WebViewActivity、WebViewRuntimeConfig、WebViewPool、Android 前台服务、WakeLock、电池优化与运行诊断  
> 核心结论：本需求可以显著提高指定可信站点在灭屏、锁屏和应用退到后台后的持续运行概率，但 Android、OEM 和 Chromium 均不提供“任意网页 JS 永不暂停、进程永不被杀”的公开保证。产品必须以“尽力持续运行、状态可见、异常可恢复”作为正式口径，不得承诺绝对不间断。

## 1. 背景与问题定义

当前“能力增强”页面只有网页定位增强一项，但定位配置、允许 Origin、第三方 SDK、诊断和测试都直接平铺在同一页面。继续加入高性能持续运行后，如果仍采用平铺方式，会造成：

- 页面层级不清晰，用户难以快速判断有哪些增强能力。
- 定位与高性能的系统权限、站点规则、诊断信息互相混杂。
- CapabilityEnhancementsScreen.kt 已承担过多状态、权限、配置和诊断职责，继续扩展会形成巨石文件。
- 高性能能力涉及多个 Android 系统环节，仅用一个总开关无法准确表达“已配置”“待授权”“正在运行”与“降级运行”的差异。

本需求包含两个相互关联的改造：

1. 将“能力增强”改造成“能力总览 → 单项能力详情”的两级信息架构。
2. 新增按可信 Origin 生效的“高性能持续运行”能力，通过同进程前台服务、PARTIAL_WAKE_LOCK、电池优化引导、WebView renderer 优先级、跨进程配置同步、诊断与恢复机制，尽量延长网页在灭屏和后台时的连续运行时间。

## 2. 当前项目基础与约束

### 2.1 已有基础

- 真实网页固定使用 WebViewActivity → FrameLayout → WebView，不使用 Compose AndroidView 承载生产网页。
- WebViewActivity 运行在独立的 :webview 进程。
- 配置通过 WebViewRuntimeConfig JSON 快照随启动 Intent 传入 :webview 进程。
- 项目已经有 Origin 允许列表、Origin 标准化和 WebViewPool 配置隔离的实践。
- AndroidManifest.xml 已声明 WAKE_LOCK，但当前没有高性能会话控制器，也没有为网页持续运行持有 PARTIAL_WAKE_LOCK。
- 当前没有网页高性能前台服务及其常驻通知。
- 当前 WebView 设置了 RENDERER_PRIORITY_IMPORTANT，但 waivedWhenNotVisible 为 true；网页不可见时 renderer 优先级会被放弃，不符合高性能站点的目标。
- WebViewActivity 当前没有调用 WebView.pauseTimers；未来也不得因普通生命周期处理而误暂停高性能会话中的所有 WebView。
- 已处理 onRenderProcessGone，但当前处理重点是销毁异常 WebView，尚未形成可验证的页面状态恢复链路。

### 2.2 必须遵守的项目约束

- 不改变真实网页的原生 FrameLayout + WebView 承载路径。
- 不假设 :webview 进程长期读取到的 SharedPreferences 是最新的。
- WebView 创建和变更必须在主线程执行。
- 高性能能力不得削弱 Device Owner、Lock Task、家长验证、安全退出和站点沙箱策略。
- 新设置必须明确是立即生效、新打开网页生效还是重启后生效。
- 新页面、复杂卡片、权限向导、诊断和运行控制必须拆分为专用文件，不能继续扩大 AdminConsoleScreen.kt 或现有 CapabilityEnhancementsScreen.kt。

## 3. 术语与产品口径

### 3.1 Origin，而不是模糊的“域名”

本需求在配置和运行判断中统一使用 Origin，格式为：

    scheme://host[:port]

例如：

- https://example.com
- https://example.com:8443
- http://192.168.1.10

原因：同一 host 在 HTTP、HTTPS 或不同端口下可能属于完全不同的安全边界。UI 可以继续使用用户更容易理解的“网站”或“域名”文案，但保存和匹配必须基于规范化 Origin。

### 3.2 Android System WebView 的内核口径

Android System WebView 当前基于 Chromium。需求和 UI 应使用“WebView/Chromium 内核运行限制”，不再使用容易误导的“解锁 WebKit 限制”。

### 3.3 高性能持续运行

“高性能持续运行”是一个受家长明确授权、仅对可信 Origin 生效的尽力型运行策略，目标是：

- 屏幕熄灭后尽量保持 CPU 可执行。
- 应用进入后台后尽量保持 :webview 宿主进程为高重要级别。
- 在用户完成电池优化配置后，尽量减少 Doze 与 App Standby 对网络和 WakeLock 的限制。
- 在网页不可见时继续请求较高 renderer 优先级。
- 不由应用主动调用会暂停网页 JS 的全局计时器暂停接口。
- 当系统、OEM 或 WebView renderer 仍然中断页面时，能够记录、提示并恢复到可用状态。

### 3.4 正式承诺边界

允许使用的正式描述：

> 对家长指定的可信网站启用高性能持续运行后，浏览器会使用 Android 前台服务、CPU 唤醒锁、WebView 进程与 renderer 优先级保护，并引导完成电池优化配置，以提高网页在灭屏或后台时持续运行的可靠性。实际效果仍受 Android 版本、WebView 版本、设备内存、网络和厂商后台策略影响。

禁止使用的描述：

- 永不休眠。
- 永不被杀。
- 后台 JS 绝对不节流。
- 所有手机都能完美不间断运行。
- 前台服务可以保证 WebView renderer 不退出。

## 4. Android 能力边界与方案结论

| 机制 | 能解决的问题 | 不能解决的问题 | 本需求结论 |
| --- | --- | --- | --- |
| PARTIAL_WAKE_LOCK | 灭屏后阻止 CPU 进入 suspend | 不能防 LMK、renderer 崩溃、OEM 冻结、Chromium JS timer 节流；普通 Doze 中 WakeLock 可能被忽略 | 高性能活动会话中自动持有，必须与前台服务绑定并严格释放 |
| 前台服务 | 提升承载该 Service 的进程重要级别，并向用户公开资源占用 | 不能保护另一个独立进程；不能保证 renderer 子进程不被杀；不能保证网页 JS 调度频率 | Service 必须声明在 :webview 进程，不能只放在主进程 |
| 忽略电池优化 | 部分豁免 Doze/App Standby，使应用在 Doze 中可用网络并持有 partial WakeLock | 其他系统限制仍可能存在；不能绕过 OEM 私有冻结策略；不能防内存回收 | 作为“完整就绪”的重要前置条件，由用户在系统页确认 |
| POST_NOTIFICATIONS | Android 13+ 允许前台服务通知出现在通知栏，保障透明性 | 拒绝后仍可能启动 FGS，但通知只在系统任务管理器可见 | 产品层面将通知权限作为完整高性能模式的必要授权 |
| renderer priority | 降低 WebView renderer 成为 OOM 回收目标的概率 | 不是进程存活保证，也不改变 JS 计时器策略 | 匹配站点使用 IMPORTANT + waivedWhenNotVisible=false |
| 不调用 pauseTimers | 避免应用主动暂停所有 WebView 的布局、解析和 JS timer | 不能关闭 Chromium 自身的隐藏页节流 | 高性能会话存续期间禁止调用全局 pauseTimers |
| Device Owner | 有利于 dedicated device 管理，并在部分前台服务后台启动限制中获得系统例外 | 不自动豁免 Doze、LMK、WebView renderer 回收或 OEM 策略 | 可作为托管设备增强条件，但不是高性能成功的充分条件 |
| OEM 后台白名单/自启动设置 | 部分厂商设备上可降低“墓碑”“神隐”或清后台策略影响 | 无统一 API，许多状态无法可靠读取，系统升级后入口可能变化 | 提供按厂商的尽力引导和兜底入口，不显示虚假的“已验证成功” |
| 网页自身容错或原生持续任务桥接 | 可持久化业务状态、断线重连、补偿丢失事件 | 需要网页业务配合，无法透明改造任意第三方网站 | 对真正关键业务列为后续 P1；这是接近业务连续性的必要手段 |

### 4.1 前台服务必须与 WebViewActivity 同进程

Android 提升的是“承载前台服务的进程”的重要级别。当前 WebViewActivity 在 :webview 进程，因此：

- 如果高性能 Service 使用默认主进程，只能提升主进程，不能直接提升 :webview 进程。
- 高性能 Service 应声明 android:process=":webview"，与 WebViewActivity 共用应用私有进程。
- WebView 的 Chromium renderer 仍可能是另一个受 WebView 管理的沙箱进程，前台服务不能把它变成应用自己的 Service 进程。
- setRendererPriorityPolicy 用于向 WebView 表达 renderer OOM 优先级需求，但仍不是存活保证。

### 4.2 不将 WebView 搬入 Service

本需求不创建“Service 内的隐藏 WebView”，也不把现有 WebView 从 Activity 的 View 层级搬到 Service：

- WebView 是 UI View，必须继续由 WebViewActivity 在主线程和真实 View 层级中持有。
- Service 只负责会话存续、前台通知、WakeLock、状态监控和诊断。
- Service 不能代理或接管任意网页内部的 WebSocket、fetch、Service Worker 或 JS 内存状态。
- Activity 被系统销毁或 renderer 退出后，Service 本身无法让原网页 JS 上下文继续存在。

### 4.3 Android 14 前台服务类型

项目 targetSdk 为 34。Android 14 要求前台服务声明适合的 foregroundServiceType 和相应普通权限，否则启动时可能抛出 MissingForegroundServiceTypeException 或 SecurityException。

“让可信 Web App 在后台持续执行”不自然对应 mediaPlayback、location、dataSync 等已有专用类型。初步建议使用 specialUse，并满足：

- 声明 FOREGROUND_SERVICE 与 FOREGROUND_SERVICE_SPECIAL_USE。
- Service 声明 foregroundServiceType="specialUse"。
- 用 android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE 说明具体用途。
- 如上架 Google Play，必须在 Play Console 申报并通过审核。
- 在实现前完成分发渠道合规确认；若 specialUse 用途不被接受，不能错误借用其他 FGS 类型规避审核。

### 4.4 不同网页功能的后台能力边界

“网页下的所有功能正常运行”必须按功能类型拆开验收，不能用一个高性能开关覆盖 Android 的其他权限模型：

| 网页功能 | 高性能模式能提供的帮助 | 仍然存在的边界 |
| --- | --- | --- |
| 普通 JS 业务、Promise、Worker | 提供 CPU、宿主进程与 renderer 的尽力保护 | Chromium 仍可节流、冻结或重建执行上下文 |
| WebSocket、SSE、fetch | 电池豁免和 WakeLock 可改善 Doze 下的联网条件 | 网络切换、服务器断开、OEM 冻结和 renderer 退出仍会断线，网页必须重连 |
| setTimeout / setInterval | 避免应用调用 pauseTimers 主动暂停 | 隐藏页 timer 可能被 Chromium 批处理，不能承诺原频率 |
| 动画、requestAnimationFrame、WebGL | 保留页面和 renderer，回到前台时可继续 | 不可见页面的动画不应作为后台业务时钟，屏幕关闭时不保证持续绘制 |
| 音视频播放 | 可减少宿主进程被回收概率 | 持续媒体播放应按 Android mediaPlayback FGS、音频焦点和 MediaSession 单独设计，specialUse 不能冒充媒体类型 |
| 标准网页定位与原生定位增强 | 页面仍在时可保留普通 WebView 上下文 | 当前项目会在 Activity.onStop 停止原生定位请求；真正后台定位需要 ACCESS_BACKGROUND_LOCATION、location FGS 和单独隐私评审，本需求不开放 |
| 摄像头、麦克风、WebRTC 采集 | 前台可继续使用已有权限链路 | while-in-use 权限在后台受限，Android 14 还会校验相关 FGS；本需求不允许后台静默采集 |
| 文件选择、系统授权弹窗、外部 App 跳转 | 页面状态可保留 | 需要可见 UI 或用户交互的功能不能在灭屏时自动完成 |
| Service Worker、Web Push | 可保留其所在 WebView 进程的存活概率 | WebView 不提供把任意 Service Worker 变成永久 Android 后台任务的保证 |

因此，P0 的“高性能持续运行”主要面向纯网页计算、长连接、学习计时、状态同步等不依赖后台敏感传感器的业务。定位、采集、媒体等能力如果要在后台长期工作，必须分别立项并选择正确的 Android 前台服务类型和权限，不能由本开关隐式放开。

### 4.5 Wi-Fi 高性能锁的处理

P0 不默认增加 WifiLock：

- PARTIAL_WAKE_LOCK 负责 CPU，不等同于 Wi-Fi 无线电低延迟锁。
- WifiLock 会进一步增加耗电，且不能解决移动网络、路由器断线、Doze、OEM 冻结或服务器断开。
- 只有真机数据证明特定设备在灭屏 Wi-Fi 下存在可复现的无线电省电问题，并且业务确实要求持续低延迟传输时，才在 P1 单独评估 WifiManager.WifiLock、CHANGE_WIFI_STATE、适用模式和释放策略。
- 即使未来启用，也必须跟随同一高性能会话生命周期，不能全局常驻。

## 5. 产品目标与非目标

### 5.1 P0 目标

- “能力增强”首页只展示能力卡片，不平铺任何能力的完整配置。
- 定位增强和高性能持续运行分别进入独立详情页。
- 支持按精确 Origin 配置高性能允许规则。
- 通过明确向导完成通知、电池优化和 OEM 设置等用户可操作条件。
- 匹配站点打开时，自动启动同处 :webview 进程的高性能前台服务并持有 PARTIAL_WAKE_LOCK。
- 匹配站点不可见时，保持 renderer priority 为 IMPORTANT 且不因不可见而 waived。
- 站点离开、规则关闭、用户停止或 WebView 销毁时，及时释放 WakeLock 和停止前台服务。
- 高性能配置对当前网页尽量立即生效，不能依赖 :webview 进程读取新鲜 SharedPreferences。
- 管理员可以看到真实运行状态、缺失前置条件、最近中断原因和当前高性能会话。

### 5.2 P1 目标

- 为可改造的自有网页提供运行状态与恢复协议，例如会话状态发现、事件序号、持久化检查点和重连提示。
- renderer 异常退出或进程重建后，能够恢复最后 URL、标签页身份和可持久化页面状态。
- 增加自动化测试页，量化 JS timer 漂移、WebSocket 连续性、网络心跳和 renderer 重启。
- 按经过验证的厂商提供后台运行设置引导。

### 5.3 非目标

- 不保证第三方任意网页的 JS、动画、定时器或 WebSocket 永不中断。
- 不通过静音音频、无限 WebRTC、频繁 Alarm、无障碍保活、双进程互拉等规避系统策略的方式“保活”。
- 不伪装 mediaPlayback、location、dataSync 等前台服务类型。
- 不绕过用户对通知、电池优化或厂商后台设置的选择。
- 不在后台偷偷保持所有网页，只保护明确授权的顶层可信 Origin。
- 不把 WebView、完整网页内容或用户浏览数据复制到主进程 Service。
- 不新增 ACCESS_BACKGROUND_LOCATION、摄像头、麦克风等与本需求无关的敏感权限。
- 不以普通网页轮询取代 Android 推荐的 WorkManager、用户发起的数据传输任务或服务端推送。

## 6. 能力增强信息架构改造

### 6.1 能力总览页

后台主菜单继续保留“能力增强”入口。进入后展示可滚动的能力总览，首期包含两个可点击卡片：

1. 网页定位增强。
2. 高性能持续运行。

每个卡片只展示：

- 图标与名称。
- 一句用途摘要。
- 当前综合状态。
- 必要的短提示，例如“2 个站点”“待完成电池设置”“正在保护 1 个网页”。
- 进入详情的箭头或整卡点击反馈。

总览页不得包含以下内容：

- 具体开关。
- Origin 输入框和 Chip 列表。
- SDK Key。
- 权限申请按钮。
- 诊断原文。

### 6.2 卡片状态

网页定位增强卡片沿用已有状态，并至少区分：

- 未开启。
- 已开启。
- 安全沙箱已禁用定位。
- 配置不完整。

高性能持续运行卡片至少区分：

- 未开启。
- 待配置：全局能力已启用，但没有 Origin 规则。
- 待授权：存在规则，但通知或电池优化等必要条件未完成。
- 已就绪：规则和必要系统条件已满足，当前没有匹配页面。
- 运行中：正在保护一个或多个匹配页面。
- 降级：部分条件缺失，只有 renderer 等基础增强生效。
- 异常：最近会话发生 renderer 退出、FGS 启动失败或 WakeLock 异常。

状态必须由当前权限、系统设置、规则和运行会话动态计算，不得把“已就绪”作为一个长期布尔值持久化。

### 6.3 导航与返回

- 能力总览进入任一详情页后，顶部标题显示具体能力名称。
- 返回键先回能力总览，再回后台主菜单。
- 横竖屏切换、进程重建或 Compose 重组后保持当前详情页和未提交输入状态。
- 总览页、详情页、对话框和向导都必须支持短高度滚动，底部操作不得不可达。
- 窄屏下按钮和状态行应自动纵向堆叠，不能依赖固定宽度横排。

### 6.4 定位增强迁移要求

- 将现有 CapabilityEnhancementsScreen 中的全部定位配置迁移到独立“网页定位增强详情页”。
- 迁移不得改变已有定位配置含义、默认值、standard/enhanced 差异、诊断和允许 Origin 行为。
- 能力总览只读取定位摘要状态，不重复维护定位配置状态。
- 定位详情仍明确标注“新打开的网站生效”或对应的即时生效范围。

## 7. 高性能持续运行详情页

详情页按以下顺序组织，各区块使用独立卡片或清晰分组。

### 7.1 状态摘要

展示：

- 综合状态：未开启、待授权、已就绪、运行中、降级或异常。
- 当前配置 Origin 数量。
- 当前活动高性能会话数量。
- 最近一次启动或停止时间。
- 一句能力边界说明：“提高后台持续运行可靠性，不保证系统或内核永不中断”。

### 7.2 总开关与风险确认

首次打开总开关时显示家长确认对话框，内容至少包括：

- 灭屏后仍可能持续联网、执行 JS、播放或处理网页业务。
- 会明显增加电量、流量、发热和设备磨损。
- 前台服务会显示常驻通知。
- 系统和厂商仍可能中断网页。
- 仅应为完全信任的网站开启。

用户必须主动勾选确认后才能继续。关闭总开关时：

- 立即阻止新高性能会话。
- 在 1 秒内通知 :webview 进程停止现有高性能会话。
- 释放 WakeLock、停止前台服务并恢复 renderer 的普通策略。
- 保留 Origin 规则，方便再次启用；提供单独“清空全部规则”。

### 7.3 可信网站规则

支持两种添加方式：

- 从现有 Web App 白名单选择，自动提取其启动 URL 的 Origin。
- 手动输入完整 http/https Origin。

每条规则字段建议为：

| 字段 | 说明 |
| --- | --- |
| id | 稳定主键 |
| origin | 规范化后的 scheme://host[:port] |
| enabled | 单条规则开关 |
| includeSubdomains | 是否匹配子域名，默认 false |
| displayName | 可选的用户备注或白名单应用标题 |
| sessionPolicy | 跟随页面会话；后续可扩展最大时长 |
| createdAt / updatedAt | 配置审计时间 |

规则要求：

- 默认仅精确匹配 Origin。
- 子域名继承必须单独开启，并显示示例范围。
- 不支持裸星号或全局“所有网站”。
- 不允许把 com、cn、co.uk 等公共后缀作为子域通配规则。
- 国际化域名需统一转换为稳定的 ASCII/Punycode 形式后比较，UI 可同时显示可读域名。
- 默认端口应归一化；HTTP 80 与 HTTPS 443 不能互相匹配。
- 输入非法、无 host、非 http/https 时必须拒绝，不能像普通字符串一样保存。
- HTTP Origin 可以在明确确认后加入，但必须显示“不安全连接”警告。
- 重复规则应合并或提示，不得产生多个语义相同条目。
- 删除或关闭当前正在命中的规则应立即停止对应会话。

### 7.4 系统运行条件向导

以检查清单展示每项状态、用途、操作按钮和可验证性。

#### A. 通知权限

- Android 13 以下显示“系统无需运行时授权”。
- Android 13 及以上检测 POST_NOTIFICATIONS。
- 未授权时显示“去授权”。请求前先解释常驻通知用途。
- 产品层面只有通知授权后才允许进入“完整就绪”，以确保用户能在通知栏看到资源占用。
- 用户拒绝后不得循环弹窗；显示“未授权，当前仅可使用基础增强”，并提供系统设置入口。
- 从系统设置返回时在 onResume 重新检测，不使用 remember 缓存旧状态。

#### B. 电池优化

- 使用 PowerManager.isIgnoringBatteryOptimizations(packageName) 检测。
- 未忽略时显示“去设置”和用途说明。
- 默认采用 ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS，引导用户在系统列表中手动选择本应用。
- 只有在分发渠道和 Google Play 政策确认允许时，才可声明 REQUEST_IGNORE_BATTERY_OPTIMIZATIONS 并使用 ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS 直接请求。
- 不得在没有核心功能论证时默认使用直接豁免 Intent。
- 返回应用后必须重新读取系统真实状态，不能因成功启动设置页就标记完成。
- 用户撤销豁免后，已有规则保留，但综合状态变为“降级”。

#### C. 前台服务

- 显示“系统支持”“清单声明可用”“当前运行中/未运行”“最近启动失败原因”。
- Service 只在至少一个匹配页面需要保护时启动，不因全局开关常驻。
- 第一次启动必须由可见 Activity 中的明确用户动作或打开匹配网站触发，避免 Android 12+ 后台启动限制。
- Service 启动后必须在系统规定时间内调用 startForeground。
- Android 14 使用已申报的 specialUse 类型；不能回退为错误类型。

#### D. CPU 唤醒锁

- WAKE_LOCK 是普通权限，没有系统运行时授权弹窗。
- UI 不应显示虚假的“申请电源锁权限”按钮。
- UI 应显示“由浏览器在高性能会话中自动管理”，以及当前“未持有/持有中/异常”。
- WakeLock 只在前台服务活动且至少有一个匹配页面时持有。
- 必须使用 PARTIAL_WAKE_LOCK，不点亮屏幕，不使用已废弃的 FULL_WAKE_LOCK。

#### E. 厂商后台运行设置

- 根据 manufacturer 展示已验证的厂商指南，例如自启动、后台高耗电、锁屏清理或应用启动管理。
- 如果没有可靠的系统页面 Intent，提供说明并打开应用详情或电池设置兜底页。
- 对无法读取真实状态的设置，显示“请人工确认”，不得显示“已开启”。
- 厂商页面 Intent 必须捕获 ActivityNotFoundException，并准备通用回退。
- 厂商引导属于建议项，不阻止标准 Android 设备进入“已就绪”；在已知强冻结设备上可影响风险提示等级。

### 7.5 高性能执行组件

默认将以下组件作为一个推荐组合，而不是让普通用户理解一组互相依赖的底层开关：

- WebView 宿主进程前台服务保护。
- CPU PARTIAL_WAKE_LOCK。
- renderer 高优先级且后台不 waived。
- 禁止应用主动暂停高性能 WebView 的全局 timer。
- 配置和生命周期诊断。

可以在“高级设置”中展示组件状态，但不建议默认允许任意拆分。若产品必须提供单项开关，则关闭任一核心组件后综合状态必须显示“降级”，并解释影响。

### 7.6 当前会话与手动停止

展示当前高性能会话列表：

- 网页标题或备注。
- Origin，不展示完整带查询参数 URL。
- 开始时间和已运行时长。
- 屏幕状态、Activity 前后台状态。
- FGS、WakeLock、renderer policy 的真实状态。
- 最近一次页面心跳或 WebView 回调时间；没有网页配合时标注“不可检测 JS 活性”。

提供“停止全部高性能运行”按钮：

- 需要家长验证或后台已验证会话。
- 立即停止运行，但不自动删除规则。
- 通知栏可提供“停止”动作；该动作不得绕过 kiosk 安全进入后台管理页。

### 7.7 诊断与说明

- 默认只展示简短诊断摘要。
- 原始诊断放入可展开详情或独立对话框。
- 支持刷新、复制、清空最近记录。
- 清空记录需要二次确认。
- 复制内容不得包含完整 URL 查询参数、Cookie、网页正文、表单内容或其他隐私数据。

## 8. 匹配与高性能会话生命周期

### 8.1 激活条件

同时满足以下条件时创建或加入高性能会话：

1. 高性能总开关已开启。
2. 当前顶层页面是 http/https 页面。
3. 当前顶层 Origin 命中一条已启用规则。
4. 当前 WebView 是实际打开的标签页或仍在 WebViewActivity 管理的网页，不是仅用于预加载的空白池实例。
5. 至少完成前台服务启动所需的系统条件。

通知与电池优化未完全配置时，可按实际可用组件进入“降级会话”，但 UI 和诊断不得显示“完整高性能已生效”。

### 8.2 顶层页面与子资源

- 只按主框架最终提交的 Origin 激活。
- iframe、图片、脚本、WebSocket 目标域名和 fetch 子资源不能单独激活高性能模式。
- 页面跳转时在主框架 onPageStarted/onPageCommitVisible/onPageFinished 等可靠节点重新评估。
- 重定向到非匹配 Origin 后应停止该页面的高性能资格。
- 打开新窗口或新标签时，每个顶层 WebView 独立判断。

### 8.3 blob、data 与 about 页面

- about:blank、data: 和直接输入的 blob: URL 不能独立命中规则。
- 若匹配网页在同一 WebView 中临时进入由自身创建的 blob/data 文档，可在保留明确创建者 Origin 的前提下短暂继承当前会话。
- 无法可靠确认创建者 Origin 时不继承。

### 8.4 多标签与引用计数

- 每个匹配 WebView 建立一个逻辑会话 token。
- 前台服务与 WakeLock 按“至少一个有效 token”统一持有，不为每个标签分别创建 WakeLock。
- 关闭一个标签时只移除其 token；最后一个 token 消失后才停止系统资源。
- 使用非引用计数 WakeLock或由单一控制器维护引用，避免 acquire/release 次数不匹配。

### 8.5 停止条件

发生任一情况时结束对应会话：

- 页面导航到不匹配 Origin。
- 标签页关闭或 WebView 被销毁。
- 高性能总开关关闭。
- 当前 Origin 规则被关闭或删除。
- 用户从通知或详情页手动停止。
- WebViewActivity 确认结束且不存在可恢复的匹配 WebView。
- 前台服务被用户从系统任务管理器停止。
- 运行条件出现不可恢复错误。

最后一个会话结束后可设置 15 至 30 秒短暂退场宽限，吸收同站点重定向或 WebView 重建抖动；宽限期内若没有新 token，必须释放 WakeLock 和停止 FGS。

### 8.6 灭屏与后台行为

- ACTION_SCREEN_OFF 不是停止条件。
- WebViewActivity.onStop 不是高性能会话的停止条件。
- 应用退到后台后，只要 Activity/WebView 实例仍存在且规则仍匹配，会话继续。
- 不得仅因页面不可见就把 renderer priority waived。
- 不得在高性能会话活动时调用 WebView.pauseTimers。
- 如果未来普通页面在 onStop 中调用 WebView.onPause，高性能页面必须有明确例外；WebView.onPause 虽不暂停 JS，但会暂停动画、定位等可安全暂停处理，可能影响“所有功能”目标。

## 9. 系统权限、Manifest 与发布要求

### 9.1 预计权限

P0 预计使用：

- android.permission.WAKE_LOCK：当前已有。
- android.permission.FOREGROUND_SERVICE。
- android.permission.FOREGROUND_SERVICE_SPECIAL_USE：Android 14 specialUse。
- android.permission.POST_NOTIFICATIONS：Android 13+ 运行时授权。
- android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS：仅在确认直接请求豁免的分发政策后加入；默认方案不强制声明。

不得顺带加入：

- ACCESS_BACKGROUND_LOCATION。
- REQUEST_COMPANION_RUN_IN_BACKGROUND。
- SCHEDULE_EXACT_ALARM。
- 无障碍、悬浮窗或忽略后台启动限制的非必要权限。

### 9.2 Service 声明

高性能 Service 至少满足：

- exported=false。
- android:process=":webview"。
- android:foregroundServiceType="specialUse"。
- 提供 PROPERTY_SPECIAL_USE_FGS_SUBTYPE 的清晰英文用途说明。
- 不把 stopWithTask=true 当作默认，因为从最近任务移除不应在仍存在受控页面的情况下误停；但 Activity 真正结束且无 WebView 时 Service 必须自行停止。

### 9.3 通知要求

- 建立独立的“高性能网页运行”通知渠道。
- 使用 ongoing 通知，优先低打扰但可见的优先级。
- 文案通用化，例如“可信网页正在高性能运行”，默认不展示完整标题、路径或儿童业务数据。
- 展示活动站点数量和运行时长。
- 点击通知只能回到安全的浏览器界面，不直接打开无需家长验证的后台设置。
- 提供“停止”动作；停止后记录来源为 notification_action。
- Android 13+ 未授权通知时不进入完整就绪。虽然系统允许 FGS 在通知权限被拒后启动，但通知可能只在系统任务管理器可见，不满足本产品的透明性要求。

### 9.4 Google Play 与其他分发渠道

- specialUse FGS 和直接电池优化豁免都需要单独进行商店政策评估。
- 如果仅通过 GitHub Release 或企业私有分发安装，也仍需遵守 Android 平台行为，不得因此省略用户解释和撤销入口。
- 如果未来上架 Google Play，发布前必须完成：FGS 类型申报、specialUse 用途审核、直接电池优化请求可接受用例核验和 Data Safety 复查。
- 政策未确认前，直接豁免请求不得作为默认发布实现。

## 10. 运行时架构要求

### 10.1 建议组件

主进程：

- HighPerformanceConfigRepository：持久化全局配置与规则。
- HighPerformanceRuntimePublisher：发布带版本号的运行快照。
- 能力总览与高性能详情 UI。

:webview 进程：

- HighPerformanceSessionController：根据页面和规则维护会话 token。
- HighPerformanceForegroundService：前台通知、WakeLock 和会话总状态。
- HighPerformanceOriginMatcher：严格 Origin 匹配。
- HighPerformanceDiagnostics：环形审计与当前状态快照。
- WebViewRuntime 集成：renderer policy、页面生命周期和 renderer 异常。

### 10.2 配置持久化

- Origin 规则包含多字段和审计时间，建议存入独立 Room 表，而不是继续扩大 StringSet。
- :webview 进程不得直接依赖 Room Flow 或 SharedPreferences 的实时新鲜度。
- WebViewRuntimeConfig 应增加只读 HighPerformanceRuntimeSnapshot，至少包含全局开关、规则摘要、策略版本和必要的运行参数。
- 规则数量可能增长时，应控制 Intent 快照体积；可使用版本化 AtomicFile 或受控 Binder IPC 传递完整快照。
- 配置快照必须有 schemaVersion 与 configVersion，解析失败时安全回退到“高性能关闭”。

### 10.3 当前页面立即生效

以下操作必须尽量立即推送到正在运行的 :webview 进程：

- 打开或关闭总开关。
- 新增、删除或启用/禁用 Origin 规则。
- 修改子域名匹配。
- 用户完成或撤销系统授权后的有效状态变化。

推荐通过显式 Binder、受保护广播加版本化运行文件，或已有跨进程服务扩展实现。不能只写 SharedPreferences 后等待 :webview 自己重新读取。

若某个配置因 WebView 创建时机无法对当前页安全应用，必须：

- 在设置位置明确显示“重新打开网站后生效”。
- 清理或绕过携带旧策略的 WebViewPool 实例。
- 不把未生效的当前页面显示为“运行中”。

### 10.4 WebViewPool

- 预热的 about:blank WebView 和未展示的 URL 预加载实例不得启动高性能前台服务或持有 WakeLock。
- 高性能规则和 renderer 策略如果影响 WebView 创建或复用，必须进入 poolKey 或在领取实例时重新应用。
- 从池中领取后，应在主框架 Origin 明确且页面成为实际标签时才创建会话 token。
- 放回池或销毁前必须移除 token、恢复普通 renderer policy 并清理高性能监听。

### 10.5 Renderer 优先级

- 普通网页保持现有兼容策略或项目统一基线。
- 高性能网页调用 setRendererPriorityPolicy(RENDERER_PRIORITY_IMPORTANT, false)。
- 页面离开高性能规则后恢复普通策略，不能让曾经命中过的 WebView 永久高优先级。
- 多个 WebView 可能共享 renderer，诊断应记录请求策略而不是声称精确控制 renderer 最终 OOM 分数。
- 继续实现 onRenderProcessGone，并记录 didCrash、rendererPriorityAtExit、Origin、会话时长和恢复结果。

### 10.6 WakeLock 管理

- 由 :webview 进程内前台 Service 在主控制器授权后创建 PARTIAL_WAKE_LOCK。
- tag 使用稳定、无 PII 的名称，例如 site.anzz.childkiosk:HighPerformanceWebSession。
- setReferenceCounted(false)，由单一状态机管理。
- acquire 必须带有限超时并由健康检查在有效会话仍存在时续期，避免控制器异常导致永久持锁。
- 所有停止、异常、Service.onDestroy、配置关闭和最后 token 移除路径都必须幂等 release。
- acquire/release 异常不得导致应用崩溃；必须记录并进入降级状态。
- 不使用 WakeLock 点亮屏幕，灭屏时屏幕应保持关闭。

### 10.7 前台服务与页面关系

- Service 只保护已有 WebViewActivity/WebView 会话，不自行创建网页。
- FGS 启动失败时，页面继续按普通模式运行，UI 显示降级和原因。
- 用户在 Android 13+ 活跃应用任务管理器中停止 Service 后，应视为明确撤销当前会话，不能立即偷偷重启。
- 应等待用户再次从浏览器前台明确启动匹配网页或重新启用高性能能力。
- 设备重启后规则保留，但不在没有网页会话时开机启动 FGS。

### 10.8 网页和网络连续性

- 容器不得声称能够透明控制所有网页的 WebSocket、Service Worker、定时器和网络库。
- PARTIAL_WAKE_LOCK 与电池豁免只提供系统执行条件，不能改变 Chromium 对隐藏页面的全部调度策略。
- Chrome/Chromium 对长时间隐藏页面的链式 JS timer 可能进行批处理或强节流；没有受支持的 Android WebView API 可按 Origin 完全关闭所有此类内核策略。
- requestAnimationFrame 在页面不可见时本就不应作为后台业务时钟。
- 关键业务不得依赖高频 setInterval 证明连续性，应使用服务端时间、事件序号、WebSocket ping/pong、断线重连和本地检查点。

## 11. 可选的网页连续性协议（P1）

对可修改的自有网页提供渐进增强 API，第三方网页不注入写权限能力。

建议能力：

- 查询当前容器高性能状态和缺失条件。
- 订阅前后台、灭屏、降级、renderer 恢复等事件。
- 提交不含敏感内容的业务检查点标识。
- renderer 重建后向网页提供“需要恢复”的启动上下文。
- 上报 WebSocket 最后事件序号，辅助业务发现消息缺口。

安全要求：

- 只对白名单主框架 Origin 开放。
- iframe 默认不可调用。
- 不把系统设置跳转、WakeLock 或 FGS 的直接控制权交给网页 JS。
- 网页只能读取状态或请求家长界面，不能静默扩大授权范围。
- 检查点存储有大小、数量、过期和隐私限制。

该协议是“业务连续性”的增强，不是 P0 容器保活的前置条件。

## 12. 状态模型与错误处理

### 12.1 综合状态计算

建议内部状态：

- DISABLED：总开关关闭。
- NO_RULES：开启但无有效规则。
- NEEDS_NOTIFICATION_PERMISSION：缺通知权限。
- NEEDS_BATTERY_SETUP：未完成电池优化配置。
- READY：配置和必要条件满足。
- ACTIVE：至少一个会话完整运行。
- DEGRADED：会话存在，但缺少某个核心组件或系统设置。
- INTERRUPTED：最近会话因 renderer、Service、进程或系统动作中断。
- ERROR：配置解析、FGS、WakeLock 或 IPC 出现持续错误。

UI 文案可简化，但原始状态必须可诊断。

### 12.2 不应自动重试的情况

- 用户拒绝通知权限。
- 用户从系统任务管理器停止 FGS。
- 用户撤销电池优化豁免。
- 用户关闭总开关或删除规则。
- FGS 类型或权限清单错误。

### 12.3 可有限重试的情况

- 首次 startForegroundService 暂时失败，但 Activity 仍在前台且用户动作有效。
- WakeLock acquire 出现瞬时系统异常。
- IPC 快照版本短暂不一致。
- renderer 异常后重新构建 WebView。

重试必须有退避和上限，不能形成双进程互拉或高频重启。

### 12.4 Renderer 或进程退出

- onRenderProcessGone 后旧 JS 内存状态不可恢复，必须明确记录“发生中断”。
- 可重建 WebView 并恢复最后可信 URL、标签页 ID、导航状态和允许持久化的数据。
- 未得到自有网页恢复协议确认前，不得把重载后的页面标记为“业务无中断”。
- 系统直接杀死 :webview 进程时 onDestroy 不保证执行，因此 WakeLock 和 FGS 的清理由系统完成；主进程恢复后应读取上次异常结束标记并更新诊断。
- force-stop、设备重启、应用更新、WebView provider 更新、崩溃和极端低内存均允许中断，这是验收中的预期边界。

## 13. 诊断、审计与可观测性

### 13.1 当前状态诊断

至少包含：

- App 版本、Android 版本、WebView provider 与版本。
- 当前进程名和 PID，确认 Service 是否确实位于 :webview。
- 全局开关、configVersion、规则数量。
- 通知权限状态。
- isIgnoringBatteryOptimizations 结果。
- 厂商和机型，仅用于本地诊断。
- FGS 声明类型、当前是否运行、启动来源。
- WakeLock 是否持有、最近 acquire/release 时间和原因。
- 当前会话数量和脱敏 Origin。
- 每个会话的 WebView 可见性、Activity 生命周期、renderer requested priority。
- 最近一次 onTrimMemory 和 onRenderProcessGone。

### 13.2 审计事件

至少记录：

- config_enabled / config_disabled。
- rule_added / rule_updated / rule_removed。
- session_started / session_stopped。
- fgs_started / fgs_start_failed / fgs_stopped。
- wake_lock_acquired / renewed / released / failed。
- renderer_policy_applied / restored。
- battery_status_changed / notification_permission_changed。
- renderer_gone / page_recovery_started / page_recovery_result。
- user_stopped_from_notification / system_task_manager_stop。

每条记录包含时间、脱敏 Origin、会话 ID、结果和简短原因。使用有上限的环形记录，避免长期增长。

### 13.3 隐私要求

- 默认只保存 Origin，不保存 path、query、fragment。
- 不保存 Cookie、Authorization、WebSocket payload、网页表单、JS 变量或页面正文。
- 通知和日志不显示儿童账号、课程名称等可能的敏感内容。
- 复制诊断时再次执行脱敏。

## 14. 安全与资源治理

- 只有经过家长验证的后台可以启用高性能、添加规则或完成系统设置引导。
- 从当前站点快捷加入高性能规则时也必须经过家长验证。
- 所有规则默认关闭子域名继承。
- 高性能不改变摄像头、麦克风、定位、文件选择、下载、外部 Scheme 等既有权限策略。
- 页面后台运行时仍必须遵守健康时限、家长退出和应用关闭逻辑；高性能不能成为绕过使用时长限制的通道。
- 达到健康时限或家长明确关闭网页时，必须结束高性能会话。
- 监控异常电量、温度和内存压力；Android 提供严重 thermal 状态时可进入降级或停止，并清晰提示。
- 不使用 largeHeap 作为后台保活保证；现有 largeHeap 不能取代内存治理。
- 高性能页面仍应控制标签数量、资源缓存和上传下载规模，避免因为提高优先级挤压系统前台应用。

## 15. UI 与可用性验收要求

### 15.1 能力总览

- 竖屏手机、横屏手机、平板和短高度设备都能看到两个能力卡片并进入详情。
- 卡片最小触摸高度至少 72dp。
- 长中文摘要可换行，不与状态或箭头重叠。
- 总览不出现定位 Key、Origin 输入框或高性能权限按钮。

### 15.2 详情页

- 全页可垂直滚动。
- 权限清单、规则列表、诊断和底部操作均可达。
- 窄屏时“状态 + 操作按钮”自动改为纵向排列。
- 动态规则数量增加后不会让删除、停止或返回操作不可达。
- 系统设置返回后权限状态立即刷新。
- 对话框有安全宽高限制，内容可滚动，确认与取消始终可触达。

### 15.3 文案

- 必须同时表达收益和限制。
- “电源锁”统一解释为“CPU 唤醒锁”，避免用户误以为屏幕会常亮。
- “忽略电池优化”不能描述为“已彻底关闭省电策略”。
- 厂商设置不可验证时使用“请人工确认”，不显示绿色已完成。

## 16. 功能验收标准

### 16.1 信息架构

- 能力增强首页只有“定位增强”和“高性能持续运行”两个详情入口及摘要。
- 点击定位增强后可访问迁移前的全部定位配置和诊断，功能无回归。
- 点击高性能持续运行后进入独立详情页。
- 新增第三个能力时不需要复制整个能力增强页面结构。

### 16.2 Origin 规则

- HTTP/HTTPS、默认端口、显式端口、大小写、IDN 和子域名规则按定义匹配。
- 非法输入、javascript:、file:、data:、无 host 字符串被拒绝。
- iframe 或子资源域名不会激活高性能。
- 顶层重定向离开规则后停止会话。
- 多标签下最后一个匹配标签关闭后才释放共享资源。

### 16.3 系统授权

- Android 13+ 通知允许、拒绝和从设置撤销三条路径状态准确。
- 电池优化设置返回后以 isIgnoringBatteryOptimizations 的真实结果为准。
- direct exemption 未经分发政策确认时不出现在默认构建。
- 厂商设置页不可打开时有通用回退，不崩溃。
- WakeLock UI 不请求不存在的运行时权限。

### 16.4 前台服务与进程

- 使用 adb 或诊断确认 FGS 与 WebViewActivity 同处 :webview 进程。
- 主进程退出或被回收不会因为错误的 Service 归属直接停止仍存在的 :webview 会话。
- Android 14 不出现 MissingForegroundServiceTypeException 或 FGS 类型 SecurityException。
- 通知在 5 秒要求内建立，并反映当前活动站点数。
- 用户从系统任务管理器停止后不自动无提示重启。

### 16.5 WakeLock

- 匹配会话启动后 dumpsys power 或系统诊断可看到预期 partial WakeLock。
- 灭屏时屏幕保持关闭，CPU 唤醒锁仍按会话持有。
- 最后会话停止、总开关关闭、Service 销毁和异常路径都能释放。
- 重复开始/停止、多标签切换和快速重定向不会泄漏或错误释放。

### 16.6 WebView 行为

- 高性能页面请求 IMPORTANT + waivedWhenNotVisible=false。
- 导航离开规则或 WebView 回池后恢复普通策略。
- 高性能会话中不调用 WebView.pauseTimers。
- onRenderProcessGone 能记录中断并重建可用页面，不崩溃。
- 不把页面重载误报为“业务完全连续”。

### 16.7 配置即时性

- 当前已打开页面被新增到规则后，无需重启应用即可进入会话，或在实现确有限制时给出明确的“重新打开网站后生效”提示。
- 删除当前规则或关闭总开关后 1 秒内开始停止会话和释放资源。
- :webview 进程长时间存在时仍能收到最新 configVersion，不能依赖 SharedPreferences 恰好刷新。
- 旧 WebViewPool 实例不会携带错误的高性能策略。

## 17. 真机与自动化测试矩阵

### 17.1 系统版本

至少覆盖：

- Android 9：最低支持版本和旧后台行为。
- Android 12：后台启动 FGS 限制。
- Android 13：通知权限和活跃应用任务管理器。
- Android 14：FGS 类型与权限强制。
- 项目升级 targetSdk 后，补测对应最新 Android 版本。

### 17.2 设备类型

- 至少一台接近 AOSP/Pixel 行为的设备。
- 至少两类有积极后台管理策略的国内厂商设备。
- Device Owner 与非 Device Owner 各一组。
- 低内存设备或可制造内存压力的测试环境。

### 17.3 场景

- 屏幕常亮前台。
- 灭屏 5、30、120 分钟。
- HOME/其他 App 覆盖后后台 5、30、120 分钟。
- 开启系统 Battery Saver。
- 通过 adb 强制进入 Doze，再退出。
- 电池优化已忽略与未忽略。
- 通知允许与拒绝。
- 厂商后台设置完成与未完成。
- Wi-Fi 与移动网络切换、短时断网恢复。
- 多标签、重定向、弹窗、新窗口和关闭标签。
- renderer crash/kill、内存压力、系统回收、force-stop、重启。

### 17.4 测试页指标

测试页至少记录：

- performance.now 与系统时间的 timer 漂移。
- setTimeout/setInterval 实际触发间隔。
- WebSocket ping/pong、事件序号和断线重连次数。
- 定时 fetch 心跳及失败原因。
- Page Visibility 状态变化。
- IndexedDB 检查点与恢复结果。
- 页面加载 ID，用于识别 JS 上下文是否被重建。

这些指标用于量化设备表现，不能把“所有 timer 毫秒级准时”作为跨设备验收要求。

### 17.5 分级验收口径

L1 容器保护验收：

- FGS、WakeLock、renderer policy、Doze 豁免状态和会话状态均按设计生效。
- 浏览器自身没有主动暂停或销毁高性能页面。

L2 参考设备连续性验收：

- 在指定 AOSP/Pixel 参考设备、固定 WebView 版本、通知与电池设置完整的条件下，灭屏和后台测试期间页面进程保持存活，测试页记录可解释。
- 若 Chromium 对隐藏 timer 节流，按真实数据记录，不用伪造保活技巧绕过。

L3 业务恢复验收：

- 自有网页接入 P1 协议后，断线或 renderer 重建能发现事件缺口、恢复检查点并明确提示恢复结果。

本产品不提供 L4“任意设备、任意网页、任意时长绝对连续”的验收等级。

## 18. 代码组织要求

建议拆分：

UI：

- CapabilityEnhancementsOverviewScreen.kt
- CapabilityEnhancementCard.kt
- LocationEnhancementDetailScreen.kt
- HighPerformanceDetailScreen.kt
- HighPerformanceSetupChecklist.kt
- HighPerformanceOriginRulesCard.kt
- HighPerformanceSessionsCard.kt
- HighPerformanceDiagnosticsDialog.kt

配置与运行：

- performance/HighPerformanceConfig.kt
- performance/HighPerformanceConfigRepository.kt
- performance/HighPerformanceRuntimeSnapshot.kt
- performance/HighPerformanceOriginMatcher.kt
- performance/HighPerformanceSessionController.kt
- performance/HighPerformanceForegroundService.kt
- performance/HighPerformanceWakeLockController.kt
- performance/HighPerformanceDiagnostics.kt

要求：

- AdminConsoleScreen 只负责二级/三级导航和必要回调。
- 能力总览不持有定位或高性能的完整配置状态。
- UI 不直接控制 WakeLock；通过明确的控制器和状态流操作。
- Origin 匹配、系统状态检测和诊断格式化应可单元测试。
- 平台 API、Compose UI、Room 持久化和 WebView 生命周期不要混入同一个文件。

## 19. 实施阶段

### 阶段 0：信息架构与配置底座

1. 新增能力总览和详情导航。
2. 将定位增强完整迁移到独立详情页。
3. 建立高性能配置模型、严格 Origin 校验和规则持久化。
4. 增加系统状态检查清单，但暂不启动 FGS。

### 阶段 1：容器级高性能会话

1. 增加 :webview 前台服务与通知。
2. 增加 WakeLock 控制器。
3. 增加 renderer policy 动态应用。
4. 建立页面 token、多标签引用和停止逻辑。
5. 建立跨进程 configVersion 推送与 WebViewPool 隔离。
6. 增加运行诊断和基础真机测试页。

### 阶段 2：系统引导与恢复

1. 完成通知权限与电池优化引导。
2. 增加经过验证的 OEM 设置指南和回退。
3. 完善 renderer/process 中断诊断和页面恢复。
4. 完成 Android 9/12/13/14 及主要 OEM 真机矩阵。

### 阶段 3：业务连续性协议

1. 为自有白名单网页提供只读状态与恢复桥接。
2. 支持检查点、事件序号和恢复确认。
3. 输出网页开发者接入文档。

## 20. 上线阻断条件

出现以下任一情况不得宣称“高性能持续运行已完成”：

- FGS 实际运行在主进程而不是 :webview。
- Android 14 使用了不匹配的 FGS 类型或启动会崩溃。
- WakeLock 在最后会话结束后仍泄漏。
- 规则匹配可被 iframe、子资源或非法 Origin 意外触发。
- 通知权限被拒绝时仍显示“完整就绪”。
- 电池设置页打开成功即被误判为已经豁免。
- 配置只写 SharedPreferences，长期 :webview 进程无法及时生效。
- WebViewPool 预热页面能误启动 FGS 或 WakeLock。
- UI 使用“永不被杀”“完美不间断”等无法兑现文案。
- 未完成 specialUse 和直接电池豁免的分发政策评估。

## 21. 待产品确认事项

1. 分发渠道是否计划进入 Google Play；这会直接影响 specialUse FGS 与直接电池优化豁免方案。
2. Android 13+ 是否坚持“通知权限未授权就不启动 FGS”。本文推荐坚持，以保证资源占用透明。
3. 是否允许 HTTP Origin 开启高性能。本文推荐允许但必须二次警告，以兼容局域网页面。
4. 是否需要家长配置单次最长持续运行时长。本文建议 P0 跟随实际页面会话，并用有限 WakeLock 超时续期防泄漏；后续可增加 30 分钟、2 小时、8 小时和跟随页面等策略。
5. 首批重点验证的 OEM 和业务网站清单。
6. 哪些自有网页可以配合接入 P1 连续性协议。

## 22. 官方资料依据

- Android 选择保持设备唤醒的正确 API：  
  https://developer.android.com/develop/background-work/background-tasks/awake
- Android WakeLock 最佳实践：  
  https://developer.android.com/develop/background-work/background-tasks/awake/wakelock/best-practices
- Android Doze 与 App Standby，包括电池优化豁免能力和 Google Play 限制：  
  https://developer.android.com/training/monitoring-device-state/doze-standby
- Android 前台服务概览：  
  https://developer.android.com/develop/background-work/services/fgs
- Android 12+ 后台启动前台服务限制：  
  https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start
- Android 14 前台服务类型、specialUse 和 Play 申报：  
  https://developer.android.com/about/versions/14/changes/fgs-types-required
- Android 13+ 通知运行时权限和前台服务通知行为：  
  https://developer.android.com/develop/ui/views/notifications/notification-permission
- Android 进程生命周期与前台服务进程重要级别：  
  https://developer.android.com/guide/components/activities/process-lifecycle
- Android Service 的 android:process 语义：  
  https://developer.android.com/guide/topics/manifest/service-element
- Android WebView 生命周期、pauseTimers 和 renderer priority：  
  https://developer.android.com/reference/android/webkit/WebView
- Chromium 隐藏页面 JS timer 节流说明：  
  https://developer.chrome.com/blog/timer-throttling-in-chrome-88

## 23. 最终产品结论

本需求应实现的是一套“指定可信 Origin 的受控高性能会话”，而不是一个不可验证的保活开关。完整能力由五层共同组成：

1. 清晰的能力总览与独立详情页。
2. 精确、可撤销的 Origin 规则。
3. 同处 :webview 进程的前台服务与 CPU WakeLock。
4. 电池优化、通知和厂商设置的真实状态引导。
5. renderer 优先级、诊断、恢复和网页自身容错。

完成这些工作后，指定站点在灭屏与后台状态下的持续运行可靠性会明显高于普通 WebView 页面；但系统回收、OEM 冻结、WebView renderer 退出和 Chromium 隐藏页调度仍可能造成中断。需求、UI、验收和发布说明必须始终保留这一边界。
