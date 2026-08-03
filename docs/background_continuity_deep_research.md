# Android WebView 后台 / 息屏持续运行深度研究

> 研究日期：2026-08-02 ~ 2026-08-03  
> Chromium 证据版本：`150.0.7871.181`  
> 历史生产基线：OnePlus PHB110 · Android 16 (API 36) · Google WebView 150  
> CDP 隔离 PoC：OnePlus 6 (ONEPLUS A6000) · Android 10 (API 29, user/release-keys) · Google WebView 150  
> 研究状态：源码结论及 Android 10 / M150 的 CDP A–H4、7.45 小时页面/renderer 存活、前台恢复和 IME 已完成；Android 16 已完成正式 Release 隔离 PoC 的临时 debugging 暴露、`frozen → active` 和 socket 关闭短时验证，生产 v0.4.23 路径、长时不间断业务调度、导航/renderer 重建、音频和自定义 Provider 仍待补齐
> 关联基线：[background_continuity_research_report.md](background_continuity_research_report.md)、[high_performance_web_runtime.md](runbooks/high_performance_web_runtime.md)

## 结论摘要

先把“持续运行”拆成三个不同目标：

1. **进程和 CPU 不被杀**：现有 FGS、`PARTIAL_WAKE_LOCK`、电池优化豁免和 renderer priority 已经覆盖了主要能力。
2. **隐藏的 Blink 页面继续执行 JavaScript / Worker / 网络回调**：Android WebView M150 默认会在隐藏约 60 秒后冻结页面；公开 Android WebView API 没有关闭这个策略的接口。
3. **业务在息屏期间不丢事件**：可以通过原生服务/服务端承载业务状态，回前台后与网页重同步。

对普通、未定制的 Android 设备，结论仍然是：**不能保证任意 WebView 页面在真实后台或真实息屏期间保持前台级调度**。源码复核发现的实验性突破口已经在 Android 10 + WebView M150 真机上得到验证：

> 通过 WebView 自己的 DevTools socket 发送 `Page.setWebLifecycleState(frozen)` → `Page.setWebLifecycleState(active)`，可以强制走一次浏览器侧冻结状态边沿，从而解除已经发生的 Blink 自主冻结，或取消尚未到期的自动冻结计时器。自动冻结后单独发送 `active` 虽返回成功，但页面不恢复。

另一个更直接、但强依赖设备策略的结果是：OnePlus 6 / Android 10 在移除安全锁屏后，纯电源键息屏虽然让 Activity 进入 `onStop()` 且系统 `interactive=false`，却没有让窗口或文档变 hidden。H3 连续息屏 `1,165,262 ms`（约 19 分 25 秒）期间，页面始终 `visible`，main timer 和 Dedicated Worker 保持约 1 秒 cadence，fetch 保持约 15 秒 cadence，没有 freeze 或 reload。H4 又关闭 WebView debugging 和自动 CDP edge，连续观察 `395,829 ms`，结果仍相同。**这说明受管设备若能保证“息屏不出现 Keyguard/其他覆盖窗口”，可以绕开 hidden 页面策略，且该主路径不依赖 DevTools；但这不是 Android 公共 API 保证，必须在目标 Android/OEM 上逐机验证。**

PoC 还确认：非 debuggable APK 可以由同 UID 直接连接 `webview_devtools_remote_<pid>`，不需要 ADB forward，也不需要电脑维持 CDP 连接。不过这条路线有五个明确限制：

- CDP 是 experimental 协议，不是 Android WebView 的兼容性承诺。
- 开启 WebView debugging 会允许 ADB/shell 检查和修改页面，官方明确把它标为生产安全风险。
- `frozen` 会在 Chromium 内部调用 `WasHidden()`。只有 CDP 实测 `document.hidden === true` 后才能发送；纯电源键息屏在某些无安全锁屏场景下仍可能保持 `visible`，误发会把页面可见性留在 `hidden`。
- 即使解除冻结，页面仍然是 `hidden`。本次实测在约 5 分钟后，主线程 1 秒 timer 和 15 秒 fetch 都退化到约 60 秒一次；Dedicated Worker 仍约 1 秒。
- Android 10 + M150 已验证同一页面/renderer 存活 7.45 小时并通过 IME；但 PoC 的 2 小时 WakeLock 租约到期后出现多分钟 CPU suspend 间隙，因此这只能证明长时存活，不能证明 7.45 小时不间断 JavaScript。Android 16、导航和 renderer 重建仍未闭环。

如果产品必须在自有设备上实现真正的前台级执行，优先级如下：

| 目标 | 最现实的路线 | 适用边界 |
| --- | --- | --- |
| 真实电源键息屏并保持前台级页面调度 | 受管设备禁用安全 Keyguard，WebView 留在同一顶层窗口，FGS + 可续期 partial WakeLock/Wi-Fi lock；以 `document.hidden` 实测为准 | Android 10 / OnePlus 6 已连续验证约 19 分钟，并以 debugging/CDP 全关闭复核；OEM/Android 差异大，Android 16 未验证 |
| 视觉上像息屏，但设备仍可持续运行 | 同一 Activity/同一窗口保持可见，`FLAG_KEEP_SCREEN_ON` + 最低亮度 + 原生黑色遮罩 | 受管 Kiosk；不是真正的电源键息屏 |
| 关键业务不丢数据 | FGS/原生网络层/服务端承载状态，WebView 只负责 UI | 最推荐；需要修改业务架构 |
| 让 hidden 页面不再被完全 freeze | CDP `frozen → active` | Android 10 + M150 PoC 已成功；仍受约 60 秒主线程节流，不进生产默认路径 |
| 所有网页获得前台级调度 | 自建 WebView Provider 或 OEM/AOSP 镜像，修改 Chromium 调度策略 | 必须控制系统镜像和更新链路 |
| 用普通 APK 在任意 user 设备实现 | 没有受支持的通用方案 | 不应继续投入可见性伪造、跨窗口搬移或 `onResume()` 组合拳 |

## 1. 现有报告结论的精确化

原报告中的“冻结后唯一恢复路径是窗口重新可见”应加上限定：

- 对**公开、受支持的 Android WebView API**，这基本成立。
- Chromium 内部的 `SetPageFrozen(false)`、真实 audible 状态，以及 DevTools/CDP 都可以触发其他解冻路径；这些路径没有稳定的 Android App API 契约。
- 原报告对可见性伪造、`WebView.onResume()` 和 detach/attach overlay 的否定结论仍然有效，不能因为发现 CDP 而重新尝试这些路线。

原报告保留了 Android 16 历史版本的实证记录；本文补充源码级机制、Android 10 + M150 的 CDP 真机结果和正式落地边界。两个设备结论不能互相替代：Android 16 上 CDP 仍需复测。

## 2. M150 的真实冻结链路

### 2.1 60 秒不是经验值，而是源码默认值

M150 的 `PageSchedulerImpl` 定义：

```cpp
constexpr base::TimeDelta kDefaultDelayForBackgroundTabFreezing =
    base::Minutes(1);
```

Android（排除 Cast Android 和 Desktop Android）默认开启 Blink Feature `kStopInBackground`，Feature 名为 `stop-in-background`。当页面满足下面三个条件时，冻结计时器会生效：

```cpp
!IsPageVisible() &&
!IsAudioPlaying() &&
!IsVirtualTimeEnabled()
```

因此，FGS 和 WakeLock 即使都健康，也不会改变 `IsPageVisible()`；WebSocket、Dedicated Worker 也不会改变这个判断。

### 2.2 冻结前已经有后台节流

“关闭冻结”不等于“恢复前台调度”。页面隐藏后还有独立的两层策略：

- 隐藏约 10 秒后，CPU time budget 可能开始限制可节流任务队列。M150 的默认 background budget recovery rate 是 `0.01`，不是一个无限运行的前台预算。
- hidden wake-up pool 默认按约 1 秒对齐唤醒；Intensive Wake Up Throttling 在非 loading 页面默认宽限约 60 秒、loading 页面约 5 分钟，之后可能按 1 分钟对齐。

`--disable-background-timer-throttling` 只切换稳定的 background timer runtime feature；CPU time budget 的初始化和后台策略是另一条路径。一个自定义 Provider 需要同时评估这两层，不能只关闭 `stop-in-background`。

### 2.3 FGS、WakeLock 和 renderer priority 各自能做什么

| 层 | 能保证 | 不能保证 |
| --- | --- | --- |
| FGS | 进程有更高的 Android 重要性，并满足后台服务使用边界 | Blink 页面不被冻结 |
| `PARTIAL_WAKE_LOCK` | 尽量保持 CPU 可运行 | WebView scheduler 不节流、不冻结 |
| `RENDERER_PRIORITY_IMPORTANT` 且不 waive | 降低 renderer 被 OOM 回收的概率 | 页面可见性和 Page Lifecycle 状态 |
| `WebView.onResume()` | 解除此前显式 `WebView.onPause()` 的暂停 | 窗口隐藏导致的 Blink 自动冻结 |

这解释了现有三路心跳的诊断价值：native 心跳正常而 main/worker 心跳同时停止，就是页面调度层冻结，而不是 FGS 失效。

## 3. 最高优先级候选：CDP 强制状态边沿

### 3.1 为什么 `active` 单独调用可能无效

M150 的 CDP 实现是：

```cpp
if (state == Frozen) {
  web_contents->WasHidden();
  web_contents->SetPageFrozen(true);
}
if (state == Active) {
  web_contents->SetPageFrozen(false);
}
```

浏览器侧 `PageLifecycleStateManager` 另外维护一个 `frozen_explicitly_`：

```cpp
if (frozen_explicitly_ == frozen)
  return;
frozen_explicitly_ = frozen;
SendUpdatesToRendererIfNeeded(...);
```

Blink 自己因为隐藏页面自动冻结时，只改变 renderer 内的 `PageSchedulerImpl::is_frozen_`；它不会把 browser 侧的 `frozen_explicitly_` 设成 `true`。所以自动冻结以后直接发 `active`，浏览器侧看到的是 `false → false`，可能完全不发 IPC。

### 3.2 为什么 `frozen → active` 有机会生效

建议的状态边沿是：

```json
{"id": 1, "method": "Page.setWebLifecycleState", "params": {"state": "frozen"}}
{"id": 2, "method": "Page.setWebLifecycleState", "params": {"state": "active"}}
```

源码推导的状态变化：

```text
自动冻结：renderer is_frozen=true，browser frozen_explicitly=false
       │
       ├─ CDP frozen：browser false→true，强制发送 frozen IPC
       │
       └─ CDP active：browser true→false，强制发送 active IPC
                         renderer SetPageFrozen(false)
                         停止 Blink 的自动冻结计时器
```

`PageSchedulerImpl::SetPageFrozenImpl(false)` 会停止 `update_frozen_state_timer_`，然后执行 `OnPageResumed()`。如果页面仍保持 hidden、没有新的 visibility/audio 状态变化，源码上看不到立即重新排程 60 秒冻结计时器的路径。这是“可能长期有效”的依据，但仍属于源码推断，必须真机长时验证。

### 3.3 这条路线的硬限制

1. `Page.setWebLifecycleState(frozen)` 会调用 `WasHidden()`。只能在页面已经自然 hidden 后发送；不能只凭 `Activity.onStop()` 推断。Android 10 无安全锁屏时，纯电源键息屏虽然触发 `onStop()`，窗口和 `document.visibilityState` 仍可能保持 visible。旧控制器在此时误发边沿后，唤醒、`active`、`Page.bringToFront`、HOME 后重新置顶都不能修复残留的 `hidden`；必须以 CDP `Runtime.evaluate("document.hidden === true")` 为硬门禁。
2. 页面解冻后仍然是 `document.visibilityState === "hidden"`。即使 main/worker 心跳继续，后台 CPU budget、wake-up pool、Doze、网络和 OEM 策略仍可能限制业务。
3. 后续 visibility/audio 变化、导航、renderer 重建或 proactive memory reduction 可能重新冻结。不能只发一次就宣称永久有效。
4. 每次显式 `frozen` 都可能触发冻结相关的内存处理。Android 默认启用 `kMemoryPurgeOnFreeze`，高频轮询式“冻结再解冻”可能增加内存抖动和页面状态风险。
5. CDP target 会随导航、renderer crash 和 WebView 重建变化，控制器必须重新发现 target；不能缓存一个永久 WebSocket URL。

### 3.4 在本项目中的最小 PoC 设计

只在开发/实验 build 开启，且不要把它接到普通管理员开关：

1. 在 `:webview` 进程确认 `WebView.setWebContentsDebuggingEnabled(true)`。
2. WebView M150 的 socket 名是 `webview_devtools_remote_<pid>`，使用 Android `LocalSocket` 的 abstract namespace 连接。
3. 先向 socket 发送 HTTP `GET /json/list`，按 URL、title 或 target type 找到当前顶层 WebView。
4. 对返回的 `webSocketDebuggerUrl` 建立 WebSocket；LocalSocket 不能直接交给普通 TCP WebSocket 客户端，需使用支持 Unix/abstract socket 的实现，或自己实现 RFC 6455 client framing。
5. `onStop + 1s` 后先通过 CDP 轮询 `document.hidden`（当前 PoC 最多 3 秒）。只有返回 `true` 才发送 `frozen → active`；超时仍为 visible 时记录 `in_app_cdp_edge_skipped reason=page_still_visible`，不发送任何生命周期命令。在 `onStart`/真实 resume 后停止控制器并重新让 Android/Chromium 自己管理可见性。
6. 所有 socket I/O 放在 IO 线程；UI/WebView 操作仍回主线程。
7. 监听页面导航、renderer exit、target detach，重新执行 `/json/list`；socket 错误不得触发 reload 或移动 WebView。

伪代码（仅表达顺序，不是生产实现）：

```text
if (hostIsReallyHidden && targetIsCurrentTopLevel()) {
    cdp.send("Page.setWebLifecycleState", {state: "frozen"})
    cdp.send("Page.setWebLifecycleState", {state: "active"})
}
```

### 3.5 CDP 对照实验矩阵

2026-08-03 在 OnePlus 6 / Android 10 / WebView `150.0.7871.181` 上使用隔离 PoC APK 实测。PoC 是 release、`debuggable=false`，使用原生 `FrameLayout + WebView`、FGS、partial WakeLock、Wi-Fi lock、主线程/Worker/fetch 三路探针和 JSONL 日志；没有修改生产 `app/`。

| 编号 | 条件 | Android 10 + M150 实测结果 |
| --- | --- | --- |
| A | debugging 关闭，无命令 | hidden 后 `60,009 ms` 自动 `freeze`；native/WakeLock 继续，main/Worker/fetch 同时停止；回前台同一 `loadId` 恢复，无 reload |
| B | debugging 开启但不连接 | hidden 后约 `60,010 ms` 仍自动冻结；仅开启 DevTools 不改变调度 |
| C | WebSocket 已连接，不发命令 | 仍在约 60 秒冻结；连接本身不改变调度 |
| D | 自动冻结后只发 `active` | CDP 返回成功，但 main/Worker/fetch 均不恢复，支持 browser `false → false` 空操作推断 |
| E | 自动冻结后发 `frozen → active` | 立即收到 `resume`；同一 `loadId`、无 reload，Worker 和网络恢复，观察 12 分钟以上无二次冻结 |
| F2 | hidden 后、自动冻结前发 `frozen → active` | 人工 `freeze/resume` 立即出现，原 60 秒自动冻结未再发生；连续观察 `929,355 ms` 无二次冻结 |
| G2 | APK 同 UID 直连 abstract socket，`onStop + 1s` 自动发边沿 | 无 ADB forward、无电脑常连；边沿 `578 ms` 完成；同一 PID `21111`、同一 `loadId` 连续存活 7.45 小时，只有一次 `activity_create/page_started/page_finished`，无 renderer 丢失或 reload |
| G2 长时执行边界 | 2 小时 WakeLock 租约故意不续期 | WakeLock 到期后 native/Worker 出现多分钟 CPU suspend 间隙，main timer 出现小时级间隙；证明页面/renderer 长时存活，不证明 7.45 小时不间断 JS |
| G2 前台与 IME | 长时运行后恢复、输入并再次息屏 | `mInputShown=true`，served view 是同一个 WebView，页面回读 `Continuity IME＿20260803`；文本又经历一次超过 100 秒息屏后仍保留 |
| H0 | 无安全锁屏时，旧控制器在纯电源键息屏的 visible 页面误发边沿 | 唤醒后 Activity 已 resumed/displayed，但 `document.visibilityState` 残留 `hidden`；`active`、`Page.bringToFront` 和任务重新置顶均不能修复，确认必须增加自然 hidden 门禁 |
| H1 | 新控制器；纯电源键息屏但页面仍自然 visible | 3 秒后跳过边沿，`reason=page_still_visible`；160.6 秒内 `freezeCount=0`，main/Worker/fetch 持续，同一 PID/`loadId`，唤醒后仍为 visible |
| H2 | 新控制器；打开系统设置真正覆盖 PoC，使页面自然 hidden | hidden 后边沿 `565 ms` 成功；后台超过 95 秒无二次 freeze，恢复原 task 后自然回到 visible；471 秒日志只有一次 Activity/page load，同一 PID `30851`、同一 `loadId`，无 reload |
| H3 | 无安全锁屏纯息屏长时验证 | Activity 停止到恢复间隔 `1,165,262 ms`（约 19 分 25 秒），系统 `interactive=false`；gate 跳过，页面始终 visible，`freezeCount=0`，main/Worker 最大报告间隔 `5,006/5,002 ms`（内部约 1 秒），fetch 最大间隔 `15,029 ms`；同一 PID `31955`、同一 `loadId`、无 reload |
| H4 | H3 去混杂：debugging 关闭、自动 CDP edge 关闭 | 屏幕关闭并进入 Dozing，`395,829 ms` 日志仍为 visible、`freezeCount=0`；main/Worker 最大报告间隔 `5,005/5,012 ms`，fetch `15,021 ms`，同一 PID `32737`、同一 `loadId`；证明 H3 不依赖 DevTools/CDP |
| I | 导航、renderer crash | 尚未执行 |

G2 证明了“电脑维护 CDP 连接”不是必要条件：与 WebView 同 UID 的 APK 能直接访问自己的 abstract DevTools socket。G1 曾因在 `LocalSocket.connect()` 前设置 `soTimeout` 而报 `socket not created`；调整为先连接、再设置 timeout 后成功，这只是 PoC 客户端调用顺序问题，不是 Chromium 限制。

H0–H4 补出了一个比“是否 onStop”更重要的判定：**控制器必须读取页面自己的自然 Page Visibility，不能由 Android Activity 生命周期代替它做决定**。`onStop()` 只负责安排检查；`document.hidden` 才负责授权这个具有副作用的 CDP 命令。H3/H4 还证明，至少在当前无安全锁屏的 Android 10 设备上，“Activity stopped”与“Blink 页面 backgrounded”不是同义词；只要文档保持 visible，真实屏幕关闭本身没有触发 hidden timer/intensive throttling/freeze，而且不需要开启 WebView debugging。

F2 和 G2 同时观察到第二层限制：前约 5 分钟 main timer 约 1 秒、fetch 约 15 秒，随后 main timer 和 fetch 约 60 秒一次；Dedicated Worker 仍约 1 秒。这应判为：

```text
解除/阻止完全冻结：成功
保持任意主线程 JavaScript 的前台级调度：失败
```

每组至少记录：

- native / main JS / worker 三路心跳的实际间隔；
- `freeze`、`resume`、`visibilitychange`、WebSocket 收发和业务时间戳；
- DOM/IME 是否保持同一实例，是否发生 reload；
- `document.visibilityState`、页面内 `performance.now()` 与 `Date.now()`；
- CPU、温度、电量、网络类型、系统媒体音量和 Doze 状态。

**通过标准**不能只看“没有 freeze 日志”：Android 10 已满足页面/renderer 过夜级存活、无意外 reload 和回前台 IME 正常，但长时 WakeLock 到期后的 CPU suspend 证明“页面还在”不等于“业务持续执行”。正式候选仍必须让业务请求延迟满足产品要求，并覆盖 Android 16、导航、renderer 重建、可续期 WakeLock、Doze/热控和断网恢复。本轮应判为“解除冻结和长时页面存活成功，任意主线程 JavaScript 的前台级调度失败”。

### 3.6 CDP 的安全边界

Chromium Android WebView 的 DevTools server 使用 abstract Unix socket；`CanUserConnectToDevTools()` 允许 root、shell，以及与 WebView 进程同 UID 的连接。Android `WebView` API 文档明确写着：开启 web contents debugging 会让用户通过 adb 检查和修改页面，是生产安全风险。

同 UID 直连成功并没有消除 debugging 风险：打开 DevTools server 后，shell/ADB 仍有权连接。本项目已经把 Chrome Inspect 与 ADB 限制联动；自动为高性能功能打开 debugging 会改变现有安全承诺。建议：

- CDP PoC 只放在隔离包、内部签名或明确的实验分支；
- 不在儿童模式 UI 暴露“持续运行”即开启 Inspect 的隐式行为；
- 不把 CDP socket 当作可被网页内容调用的 bridge；
- 如果最终必须产品化，优先在自定义 WebView Provider 内做受签名 UID/Origin 限制的 native API，而不是开放通用 DevTools socket。

## 4. 第二条实验路线：虚拟时间

Blink 源码明确把“虚拟时间已启用”作为不进入 `IsBackgrounded()` 的条件。M150 单元测试还验证了两件事：

- hidden 页面启用 virtual time 后不会进入冻结；
- 已冻结的 hidden 页面启用 virtual time 后会被解冻；
- hidden 页面在 virtual time 下的 repeating timer 可以继续推进。

这使 `Emulation.setVirtualTimePolicy` 成为很好的**诊断工具**：如果开启它后心跳恢复，说明问题确实是 PageScheduler 的 background/freeze policy，而不是 FGS、WakeLock 或网络进程。

它不适合作为正常生产运行时：

- `Date.now()`、`performance.now()`、timer deadline 和动画时钟都可能改用虚拟时间；
- `advance` 可能让大量 timer 在很短的真实时间内快进，造成 CPU 飙升；`pause`/`pauseIfNetworkFetchesPending` 又可能暂停业务；
- 网络请求、超时、WebSocket 重连和第三方库对时钟的假设会改变；
- 同样依赖 experimental CDP 和 debugging 安全边界。

因此虚拟时间的定位是“证明因果链 + 研究站点兼容性”，不是“后台常驻开关”。

## 5. 第三条实验路线：真实网页音频

### 5.1 源码边界

M150 只有在页面 `IsAudioPlaying()` 时才不被 `IsBackgrounded()` 判定为可冻结。音频输出层的实际判定不是“存在一个 `<audio>` 元素”这么简单：

- 输出功率达到 `-72.24719896 dBFS` 以上才立即算 audible；
- 连续低于阈值约 100 ms 才转为 silent；
- 输出流约每 1/15 秒轮询；
- 页面停止音频后，Blink 仍保留约 30 秒 recently-audible 宽限；
- 已冻结页面收到真实 `AudioStateChanged(true)` 时可以解冻。

这也解释了为什么“静音音轨、`muted=true`、gain=0、只调用 `play()`”不能直接假定有效。原生 `AudioTrack`/`MediaPlayer` 也不会自动把某个 WebContents 标记为 audible；必须是该 WebContents 的网页音频输出。

### 5.2 可控测试页

在 `docs/test-pages/` 增加一个仅用于实验的页面（不接入生产注入），依次测试：

1. 无音频；
2. `muted` 音频；
3. `AudioContext` oscillator，幅度约 `-80 / -70 / -60 dBFS`；
4. `<audio>` 播放真实 WAV，音量分别为 0、0.0001、0.001；
5. 系统媒体音量为 0、耳机/蓝牙、音频焦点被抢占；
6. 后台前启动音频，后台后启动音频，冻结后再尝试启动音频。

每个条件至少 10 分钟，记录实际声压/输出路由、heartbeat、freeze 信号和系统媒体控制。

### 5.3 产品判断

真实音频是 Chromium 认可的“用户可能仍在使用页面”的信号，但用不可听音频专门逃避冻结会带来：耗电、音频焦点争用、耳机/蓝牙泄漏、系统媒体通知、OEM 差异和商店合规风险。它可以作为明确标注的企业设备实验开关，不应成为普通儿童模式默认行为。

## 6. 第四条路线：关闭 Feature / 自建 WebView Provider

### 6.1 userdebug / emulator 先做因果验证

Chromium WebView 文档说明 `/data/local/tmp/webview-command-line` 只适用于 userdebug/eng 或 emulator；生产 user 设备只能使用 WebView DevTools 的 curated flags。建议按层级测试：

```text
_ --disable-features=stop-in-background
```

如果冻结消失，再测试调度层：

```text
_ --disable-features=stop-in-background,IntensiveWakeUpThrottling \
  --disable-background-timer-throttling \
  --intensive-wake-up-throttling-policy=0 \
  --webview-verbose-logging
```

注意：

- 每次修改后必须完全杀掉并重启 WebView 宿主；
- `--disable-renderer-backgrounding` 只影响 renderer 进程的后台优先级，不能替代页面 scheduler 修改；
- 如果只关闭 `stop-in-background`，仍可能看到 CPU budget 或 wake-up 对齐；这不是实验失败，而是证明存在第二层节流。

### 6.2 正式 Provider 需要改哪些层

如果实验设备证明“关闭冻结 + 关闭节流”满足业务，正式实现不应依赖 ADB flags，而应在自有 Chromium/WebView Provider 中加入受控开关。至少要审查：

1. `PageSchedulerImpl::UpdateFrozenState`：对受保护 WebContents 不安排自动 freeze。
2. `PageSchedulerImpl::UpdatePolicyOnVisibilityChange`：避免对受保护页面启动后台 CPU/intensive throttling。
3. `FrameSchedulerImpl::ComputeThrottlingType`：对受保护 main frame/同源 frame 返回不节流策略。
4. renderer/OOM 优先级：继续使用现有 FGS 和 non-waived renderer priority，避免把“调度不停”误当成“进程永远不会被杀”。
5. 内存/热控/总 CPU 配额：即使是受保护页面，也要有系统级上限和管理员撤销路径。

最安全的设计是**按签名 UID + 明确的 WebView API/Origin allowlist**启用，而不是把所有 Android WebView 页面全局改成前台策略。仅在 `stop-in-background` 上打补丁容易得到“页面不冻结但定时器仍不准”的半成品。

### 6.3 Provider 的系统边界

普通 APK 不能把自带 WebView APK 放进私有目录后让 `android.webkit.WebView` 使用它。Android WebView Update Service 会校验：

- 预设 package name；
- user build 的 release signature；
- target SDK/versionCode；
- 所有 user profile 可用；
- `com.android.webview.WebViewLibrary` native library 元数据。

AOSP/OEM 可以配置 provider 列表；含 GMS/Play Store 的生产镜像通常必须使用 Google 的 WebView 配置并通过 GTS。故正式 Provider 路线的实际前提是：

```text
自有硬件 / AOSP 或 OEM 镜像控制 / Provider 签名与更新链路 / Chromium 安全补丁持续维护
```

这不是可以随 APK 发布的普通功能，而是设备平台项目。

## 7. 公开 API 下最可靠的“视觉息屏”方案

如果产品允许屏幕实际上保持 on，只要求儿童看起来像息屏，公开 API 可以做一条稳定路线：

1. Activity 和原生窗口保持真实前台、同一个 `FrameLayout`，WebView 继续 `VISIBLE`、attached，不改 window visibility。
2. 使用 `FLAG_KEEP_SCREEN_ON`；Android 文档明确它只在窗口对用户可见时有效。
3. 将 window `screenBrightness` 设为 `BRIGHTNESS_OVERRIDE_OFF`（最低亮度，而不是物理断电）。
4. 在现有 FrameLayout 最上层放纯黑原生遮罩，遮罩负责拦截触摸；不要把 WebView 移到 overlay，也不要 `INVISIBLE → VISIBLE` 伪造可见性。
5. 恢复时只移除遮罩，WebView 的 Surface、IME 和窗口关系没有改变。

这条路线能让 OLED 设备接近黑屏并保持页面可见；LCD、功耗、发热和长按电源键仍是现实成本。Android 普通 App/Lock Task 没有可靠的公开 API 禁止用户短按电源键；如果“真熄屏”是硬要求，需要 OEM 电源键策略、系统应用权限或物理设备设计。

它也不能解决“另一个 App 真正在前台”的场景，因为官方文档说明 Activity 进入后台后 `FLAG_KEEP_SCREEN_ON` 不再阻止屏幕正常关闭。后台场景若只需继续显示一个小窗口，可单独评估 PiP；PiP 仍不解决屏幕关闭，且与儿童 Kiosk/Lock Task 的交互需要设备矩阵验证。

## 8. 业务连续性的正式架构：把持续工作移出页面

如果需求本质是“定时上报、长连接、接收指令、状态机不能丢”，不要把它等同于“任意网页 DOM/JS 永不暂停”：

```text
网页 UI  ──受 Origin 限定的 WebMessage──  :webview 原生控制层
                                      │
                                      ├─ FGS + native WebSocket/HTTP
                                      ├─ Room / append-only event log
                                      └─ 服务端状态与幂等事件
```

建议：

- native 层持有连接、重试、事件序号、幂等提交和本地 outbox；
- 页面只消费快照、显示状态和发起用户操作；
- `freeze`/`resume` 时保存页面 UI 状态，回前台按 event id 重放/对账；
- 用 FGS 承载用户可见、需要连续运行的任务；不要把 WorkManager 当作亚秒级或持续 JS 调度器；
- Service Worker 可作为缓存/事件驱动补充，但其 worker 也会被浏览器按生命周期启动和终止，不是永久常驻线程；
- 如果业务完全由第三方页面控制且无法增加 bridge，普通 WebView 没有办法在不改内核/设备的前提下提供同等保证。

这是唯一不依赖 Chromium 内部实现细节、能跨 WebView 版本和 OEM 长期维护的“业务连续性”方案。

## 9. 推荐执行顺序

### P0：补齐 CDP 的跨版本与长时边界

Android 10 + M150 已完成 A–H4：`active` 单独无效、`frozen → active` 有效、同 UID 本地控制有效、自然 hidden 门禁有效，且已经确认约 5 分钟后主线程/fetch 进入约 60 秒节流；同一页面/renderer 7.45 小时存活和 IME 也已通过，无安全 Keyguard 的纯息屏路径还完成了 debugging/CDP 全关闭复核。下一步不再重复这些短时对照，而是补齐：

1. Android 16 + M150 同样矩阵，确认历史生产设备是否一致；
2. Android 16 正式生产 v0.4.23 的可信 Origin/token 匹配、临时 socket 关闭和七分钟以上真机闭环；
3. 可续期 WakeLock 下的 1 小时/过夜业务延迟、Doze、断网/恢复和热控场景；
4. 导航和 renderer 重建无副作用；
5. WebView provider 升级后的回归测试；
6. debugging 开启后的威胁模型和可接受部署范围。

### P1：同时建立内核上限

在 userdebug/emulator 用 `--disable-features=stop-in-background` 做一次因果实验，再逐层关闭 background timer / intensive throttling。这个结果能回答“自定义 Provider 是否值得投入”，比继续修改 Kotlin 生命周期代码更有信息量。

### P2：根据产品选择正式方向

- **受管设备、必须真实电源键息屏**：先禁用安全 Keyguard 并在目标 OEM/Android 上证明息屏后 `document.hidden === false`；保持同一窗口和可续期 WakeLock。主路径不需要开启 WebView debugging。这个路线一旦变 hidden，就退回下面的 CDP/native/provider 边界，不能靠伪造可见性补救。
- **设备可控、只要视觉息屏**：同窗口保持可见 + 黑遮罩。
- **设备可控、必须前台级 JS**：定制 WebView Provider/系统镜像。
- **普通 APK、必须业务不丢**：native/server offload。
- **只想让某一类媒体页继续播放**：真实网页音频 + media policy 专项评估，不要泛化到所有可信站点。

### 明确停止的路线

- 任何 `getWindowVisibility()`、`isShown()`、`onScreenStateChanged()`、`onPause()` 伪造；
- 把现有 WebView detach/attach 到 overlay 或其他窗口；
- 只调用 `WebView.onResume()` 期待解除隐藏页面冻结；
- 把 FGS/WakeLock/renderer priority 当作页面调度保证；
- 用静音音轨、原生 AudioTrack 或 WebSocket 存在性推断页面一定不会冻结。

## 10. 验收矩阵

每次换 WebView、Android 或 OEM 都要重复以下矩阵：

| 维度 | 最低覆盖 |
| --- | --- |
| 设备 | 已有 OnePlus 6；还需历史 OnePlus/Android 16、AOSP emulator、至少一个 Samsung 或 Pixel |
| Android | 已测 Android 10；还需 Android 14/15/16 |
| WebView | 已测 M150 stable；后续每次 provider 更新 |
| 状态 | Activity hidden、HOME/切换 App、锁屏、短按电源、Doze |
| 时长 | 10 分钟、1 小时、过夜 |
| 页面 | 普通 timer、Dedicated Worker、WebSocket、fetch、Web Audio、IME |
| 网络 | Wi‑Fi、移动网络、断网/恢复、VPN/代理 |
| 资源 | FGS/WakeLock、renderer crash、内存压力、低电量、温度 |
| 安全 | ADB/Inspect 开关、`FLAG_SECURE`、儿童退出/Lock Task |

“成功”至少需要同时满足：无意外 reload、DOM/会话不丢、main+worker 心跳持续、业务事件无重复/丢失、回前台 IME 正常、功耗和温度在产品预算内。只有 native 心跳持续不能算成功。

## 11. 证据与参考资料

以下源码均按 `refs/tags/150.0.7871.181` 阅读；当前 Chromium main 和 M153 的 `stop-in-background` / 60 秒默认冻结代码仍保持同样结构，因此后续升级仍需重新跑设备矩阵：

- [Blink `features.cc`](https://github.com/chromium/chromium/blob/150.0.7871.181/third_party/blink/common/features.cc)：`kStopInBackground` 在 Android 非 Cast/Desktop 构建默认启用。
- [Blink `page_scheduler_impl.cc`](https://github.com/chromium/chromium/blob/150.0.7871.181/third_party/blink/renderer/platform/scheduler/main_thread/page_scheduler_impl.cc)：60 秒冻结延迟、可冻结条件、audio/virtual-time 例外、后台 CPU/wake-up policy。
- [Blink `page_scheduler_impl_unittest.cc`](https://github.com/chromium/chromium/blob/150.0.7871.181/third_party/blink/renderer/platform/scheduler/main_thread/page_scheduler_impl_unittest.cc)：hidden/virtual-time/audio/freeze-unfreeze 行为测试。
- [Content `page_handler.cc`](https://github.com/chromium/chromium/blob/150.0.7871.181/content/browser/devtools/protocol/page_handler.cc)：`Page.setWebLifecycleState` 的 `WasHidden()`、`SetPageFrozen(true/false)` 调用。
- [Content `page_lifecycle_state_manager.cc`](https://github.com/chromium/chromium/blob/150.0.7871.181/content/browser/renderer_host/page_lifecycle_state_manager.cc)：`frozen_explicitly_` 的边沿判断与 renderer IPC。
- [Android WebView `aw_devtools_server.cc`](https://github.com/chromium/chromium/blob/150.0.7871.181/android_webview/browser/aw_devtools_server.cc)：`webview_devtools_remote_<pid>` abstract Unix socket。
- [Content Android `devtools_auth.cc`](https://github.com/chromium/chromium/blob/150.0.7871.181/content/browser/android/devtools_auth.cc)：root、shell、同 UID 的连接授权。
- [Audio `output_stream.cc`](https://github.com/chromium/chromium/blob/150.0.7871.181/services/audio/output_stream.cc)：`-72.24719896 dBFS`、100 ms silence tolerance、audibility polling。
- [WebView command-line flags](https://chromium.googlesource.com/chromium/src/+/refs/tags/150.0.7871.181/android_webview/docs/commandline-flags.md)：userdebug/eng 限制、`/data/local/tmp/webview-command-line`、重启要求。
- [WebView DevTools user guide](https://chromium.googlesource.com/chromium/src/+/refs/tags/150.0.7871.181/android_webview/docs/developer-ui.md)：生产设备只提供 curated flags；Feature 不一定可切换。
- [Android WebView providers](https://chromium.googlesource.com/chromium/src/+/refs/tags/150.0.7871.181/android_webview/docs/webview-providers.md)：provider package/signature/target SDK/version/native library 约束，以及 GMS/GTS 边界。
- [Android WebView debugging](https://developer.android.com/develop/ui/views/layout/webapps/debug-chrome-devtools)：官方建议只在开发 build 开启 `setWebContentsDebuggingEnabled`。
- [Android `WebView` API](https://developer.android.com/reference/android/webkit/WebView#setWebContentsDebuggingEnabled(boolean))：debugging 可由 adb 检查和修改页面，属于安全风险。
- [Android keep the screen on](https://developer.android.com/develop/background-work/background-tasks/awake/screen-on)：`FLAG_KEEP_SCREEN_ON` 只在窗口可见时保持屏幕。
- [Android `WindowManager.LayoutParams`](https://developer.android.com/reference/android/view/WindowManager.LayoutParams#BRIGHTNESS_OVERRIDE_OFF)：最低亮度不是物理断电。
- [Android PiP](https://developer.android.com/develop/ui/views/picture-in-picture)：可保持 Activity 以 PiP 形式显示，但不覆盖真实屏幕关闭和 Kiosk 约束。
- [Chrome Page Lifecycle API](https://developer.chrome.com/docs/web-platform/page-lifecycle-api)：页面应把 freeze/resume 当作正常生命周期，并保存可恢复状态。
- [项目现有运行手册](runbooks/high_performance_web_runtime.md)：资源层、三路心跳和已证伪路线的现场排障步骤。

### 证据等级

- **源码确认**：上述 M150 文件和单元测试直接支持的行为。
- **设备已证伪**：原报告在 OnePlus / Android 16 / WebView 150 上记录的公开 API/overlay 行为。
- **设备已验证**：OnePlus 6 / Android 10 / WebView 150 上，`active` 单独无效、`frozen → active` 有效、同 UID abstract socket 控制有效、自然 hidden 门禁有效、页面/renderer 7.45 小时存活、前台/IME 恢复、hidden 约 5 分钟后的主线程/fetch 60 秒节流，以及无安全锁屏纯息屏约 19 分钟保持 visible/前台 cadence；后者已用 debugging/CDP 全关闭的 H4 独立复核。
- **仍待验证**：Android 16 上的 CDP、可续期 WakeLock 下的长时不间断业务调度、导航/renderer 重建，以及虚拟时间在 WebView target 上的具体语义。
- **产品建议**：根据安全、维护和架构边界给出的落地排序，不是 Chromium API 保证。

## 12. 本项目的生产接入判断

当前生产代码已经满足 H3/H4 主路径的大部分结构前提：

- `WebViewActivity` 继续使用同一个 `FrameLayout + WebView`，在 `onPause()` / `onStop()` 没有调用 `WebView.onPause()`、`pauseTimers()`、detach 或 destroy。
- Device Owner 管理页已有“禁用系统锁屏键盘锁 (Keyguard)”设置，并通过 `DevicePolicyManager.setKeyguardDisabled()` 应用。
- `HighPerformanceForegroundService` 与 WebView 同在 `:webview` 进程，已有可续期、有限租约的 partial WakeLock 和 AlarmManager 兜底。
- 页面探针已经能区分 native、main、Worker、Page Visibility 和 freeze/resume，不需要伪造可见性。

因此，下一版生产候选不应先接入 CDP，而应先实现一个**设备能力门禁**：

1. 只对明确启用高性能模式、Device Owner 和“禁用 Keyguard”均成功的受管设备开放“真实息屏连续运行候选”。记录 `setKeyguardDisabled()` 的返回结果，不能只记录设置值。
2. 息屏/`onStop()` 时保持 WebView 原位和真实生命周期，不调用 visibility、screen state、`onPause()` 伪造，也不移动窗口。
3. 保持现有 FGS + 可续期 partial WakeLock；是否增加 Wi-Fi lock 必须用真实业务网络在目标设备上单独 A/B 验证，不能因为 PoC 使用了它就直接增加生产权限。
4. 让受保护页面持续上报真实 `document.hidden`、main/Worker cadence、业务请求时间戳和每次 load ID。
5. 息屏后若页面持续 `visible` 且 cadence 达标，则标记为 `SCREEN_OFF_VISIBLE_CONTINUITY`；这是 H3/H4 已验证的主路径，不需要 WebView debugging。
6. 若页面自然变为 `hidden`，立即降级为“页面存活但调度不保证”，不要伪造 visible。实验分支可以在确认 hidden 后使用 CDP edge；正式版本在 Android 16、安全和 renderer/navigation 闭环前不应自动开启 debugging。
7. 唤醒后必须核对同一 load ID、无新增 page load、IME 可用和业务事件无丢失/重复；任一失败都应把该设备/WebView 版本标成不兼容。

建议的状态判定如下：

```text
screen off / Activity stopped
        │
        ├─ document.hidden=false + cadence 达标
        │      → 自然 visible 息屏连续运行（主路径，无 CDP）
        │
        └─ document.hidden=true
               → hidden 页面能力边界
                  ├─ 生产默认：DEGRADED，回前台恢复
                  └─ 隔离实验：visibility gate 后 CDP frozen→active
```

在 Android 16 目标设备完成同样的 no-debug H4 验证之前，不应把 Android 10 的结果升级为通用产品承诺。

### 12.1 v0.4.22 生产接入与 Android 10 自测结果

v0.4.22 生产接入采用上述“自然 visible + 明确降级”路径，没有把 CDP 带入正式模块：

- `HighPerformancePageRuntime` 不再覆盖 `document.hidden` / `visibilityState`，也不再阻止
  `visibilitychange` / `freeze`；探针通过 `Document.prototype` 原生 getter 上报真实状态。
- 删除冻结信号和陈旧心跳触发的 `WebView.onResume()` + `View.INVISIBLE` 切换组合拳，避免
  IME、焦点和 Chromium 可见性状态副作用。
- 新增稳定的页面 load ID、Keyguard showing/secure 条件、真实可见性采样时间和连续性状态：
  `FOREGROUND_RESPONSIVE`、`BACKGROUND_VISIBLE_CONTINUITY`、
  `SCREEN_OFF_VISIBLE_CONTINUITY`、`HIDDEN_DEGRADED`、`STALE`。
- `HIDDEN_DEGRADED` 不能再因为心跳暂时响应而让综合状态宣称 `ACTIVE`。

2026-08-03 在 OnePlus 6 / Android 10 / WebView 150 上用正式 `app` 模块、同一发布签名的
debuggable 诊断构建和真实 `PersistentWebViewActivity -> FrameLayout -> WebView` 完成自测：
前台建立会话后按电源键息屏并进入 Dozing，连续采样约 7 分钟。全程同一 `:webview` PID、
同一页面 load ID，`document.hidden=false`、`visibilityState=visible`、FGS `RUNNING`、WakeLock
`HELD`，主线程和 Worker 心跳持续推进，无 `freeze`、renderer loss 或 reload。测试没有连接
CDP，且 `chrome_inspect_enabled=false`。真正 non-debug release APK 的去混杂证据来自隔离
PoC 的 H4：该轮同时关闭 WebView debugging 和自动 CDP，仍复现自然 visible 息屏连续运行。

该结果验证了生产状态机能识别 Android 10 设备上的自然息屏连续运行能力；Android 16 仍须
按相同流程复测，若真实页面变 hidden，正式版本会显示 `HIDDEN_DEGRADED` 而不会伪造可见性。

### 12.2 Android 16 临时暴露 PoC 与 v0.4.23 实验接入

2026-08-03 在 OnePlus PHB110 / Android 16 / WebView 150 上，使用非 debuggable Release PoC
验证了“前台 debugging 关闭，后台短暂开启”的同 UID 路径：事件出现
`temporary_webview_debugging_enabled → freeze → resume → in_app_cdp_edge_succeeded`，随后
`temporary_webview_debugging_disabled` 和 `temporary_webview_debug_socket_closed`；同一 PID、
同一 load ID，临时暴露约 1.5 秒。关闭 socket 后的短时观察未出现第二次 freeze，但无线
ADB 在 Doze 中离线，因此该次只能证明 Android 16 上短时 edge 与关闭机制成立，不能证明
长时前台级调度。

v0.4.23 因此加入默认关闭的生产实验开关，并额外收紧为：仅当前可信 Origin、随机心跳
token 精确 target 匹配、真实 `document.hidden` 门禁、单次 edge、8 秒租约、5 秒强制关闭、
恢复最新 Chrome Inspect 偏好和 socket 关闭诊断。该接入仍属于高风险实验能力，生产 Release
真机七分钟以上、导航/renderer/IME 和 OEM 回归未完成前不得升级为通用承诺。
