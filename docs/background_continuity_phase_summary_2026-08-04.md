# Android WebView 后台 / 息屏连续性阶段总结

> 阶段范围：v0.4.3 ～ v0.4.26（重点为 v0.4.13 ～ v0.4.26）  
> 总结日期：2026-08-04  
> 主要内核：Google Android System WebView `150.0.7871.181`  
> 当前发布基线：Child Kiosk Browser Enhanced `0.4.26 (101)`  
> 本文是当前阶段的结论入口；早期探索细节、源码研究和现场操作分别保留在关联报告与 runbook 中。

## 1. 一句话最终结论

**当前高性能模式不能让 Android WebView 在真实后台或真实息屏后保持与前台完全相同的正常运行。**

目前已经实现的是：在受支持且配置正确的设备上，尽量保持 WebView 进程、页面实例、DOM/JS
上下文和系统资源不被中断；当页面被 Chromium 标记为 hidden 时，尽可能维持可观测的低频
调度，并在回到前台后快速恢复。它是“页面存活与低频连续性增强”，不是“前台级持续执行”。

当前最准确的产品口径是：

> 高性能模式可以显著增强可信网页在后台和息屏期间的页面存活、低频调度与恢复能力，
> 但无法绕过 Android WebView / Chromium 对隐藏页面的主线程节流、冻结、网络和资源策略，
> 因而不能承诺任意网站业务像前台一样实时、连续运行。

## 2. 当前能做到什么

### 2.1 已经稳定实现的能力

| 能力 | 当前状态 | 说明 |
| --- | --- | --- |
| WebView 进程与 CPU 资源保护 | 已实现 | `:webview` 同进程 FGS、有限 `PARTIAL_WAKE_LOCK`、Alarm 续期、电池优化状态与通知检查 |
| Renderer 回收风险降低 | 已实现 | 对受保护页面使用 `RENDERER_PRIORITY_IMPORTANT` 且不 waive；仍不能抵抗所有内存压力 |
| 页面实例保留 | 双设备已验证 | HOME、息屏和 Light Doze 中保持相同 PID、session、load ID，无主动 reload |
| DOM / JS 上下文保留 | 双设备已验证 | 返回前台时继续同一页面实例，而不是重新创建页面 |
| Dedicated Worker 低频连续性 | 目标设备已验证 | Xiaomi Android 13 和 OnePlus Android 16 样本中约 5 秒持续产生诊断心跳 |
| 隐藏页面主线程低频运行 | 目标设备已验证 | 稳态约 60 秒一次，不是原 5 秒 cadence |
| 前台恢复 | 已验证 | 唤醒或返回应用后主线程恢复约 5 秒探针 cadence，页面不重载，IME 可继续使用 |
| 运行状态可观测 | 已实现 | 分离 native/main/Worker 心跳、Page Visibility、Activity、FGS、WakeLock、PID、load ID 和事件流 |
| 正式包脱敏诊断导出 | 已实现 | Release APK 可导出到 `Downloads/ChildKiosk/`，无需 `run-as` |
| 外部链接复用后台标签 | Xiaomi 已验证 | v0.4.26 五轮 HOME → 外部再入不崩溃、不重载、PID/session/load ID 不变 |

### 2.2 不能保证的能力

- 不能保证页面主线程的 `setTimeout` / `setInterval` 按前台频率执行。
- 不能保证 `requestAnimationFrame`、动画、地图渲染和 UI 更新在息屏时继续。
- 不能由诊断 Worker 的持续心跳推导出网站自己的 Worker、fetch、WebSocket 或业务任务也同样持续。
- 不能保证实时定位、音视频、秒级上报、长连接或任意网络请求不被 Android、OEM、WebView 或站点自身中断。
- 不能保证 Deep Doze、热控、极端内存压力、WebView renderer crash、应用强制停止后的连续性。
- 不能把某一 OEM 或某一 WebView 版本的成功结果推广成 Android 平台契约。

### 2.3 业务适用性判断

| 业务诉求 | 当前判断 |
| --- | --- |
| 保持登录、页面状态和 DOM，回来尽量不重载 | 适合 |
| 息屏期间容忍分钟级主线程延迟 | 可尝试，必须逐设备验证 |
| 使用 Worker 承载可容忍中断、可补偿的轻量任务 | 有条件可行，不能做平台级承诺 |
| 秒级计时、实时轨迹、心跳上报、可靠 WebSocket | 不应只依赖 WebView |
| 与屏幕亮着时完全等价 | 当前无法实现 |

## 3. 为什么 FGS 和 WakeLock 仍然不够

整个问题必须拆成三层：

1. **进程与 CPU 是否存活**：FGS、WakeLock、电池豁免和 renderer priority 主要解决这一层。
2. **Chromium 是否调度隐藏页面**：由 Page Visibility、Page Lifecycle、timer throttling、冻结、Doze、热控和 OEM 策略决定。
3. **业务是否连续**：还取决于页面自身是否因 `visibilitychange` / `freeze` 主动停任务，以及网络、服务端和业务重试设计。

本阶段最重要的机制结论是：

> 进程活着、CPU 可唤醒、FGS 正常、WakeLock 持有，只能证明资源外壳仍在；不能证明
> Blink 主线程、网站 Worker、fetch、WebSocket 或业务定时器仍按前台 cadence 调度。

这也是为什么诊断必须分开记录 native、main 和 Worker 三类证据，而不能只显示一个“运行中”。

## 4. 探索过程与路线结论

### 4.1 资源保护层：保留，构成稳定基线

早期仅使用 `Handler.postDelayed()` 续期 WakeLock，CPU suspend 或 Doze 后 Handler 可能不再
执行，导致有限租约过期并形成“CPU 睡眠 → 无法续期 → 更深睡眠”的闭环。v0.4.3 起加入：

- Handler 快速续期；
- `AlarmManager.setAndAllowWhileIdle()` 的 best-effort 唤醒续期；
- `HighPerformanceAlarmReceiver` 中同时触发 WakeLock 续期和健康检查。

这解决了资源层的长期稳定问题，但没有改变隐藏页面的 Chromium 调度策略。

### 4.2 可见性与屏幕状态伪造：结构性失败，不得重试

v0.4.0～v0.4.9 和 v0.4.15 两轮尝试覆盖 WebView/View 的窗口可见性、`isShown()`、屏幕状态
或 `onPause()`，希望让 Chromium 继续把页面当作前台。结果是：

- 可信与非可信网页的 IME 都可能无法再次唤起；
- Chromium 的窗口级输入连接与 Android `ViewRootImpl` 状态失步；
- 即便伪造已经生效，约 60 秒冻结仍可能发生。

结论：任何范围、任何时机的可见性、屏幕状态或生命周期伪造都不可接受。

### 4.3 `WebView.onResume()` 和 View 切换解冻：实机无效

v0.4.17 尝试在页面报告 `freeze` 后调用 `WebView.onResume()`；v0.4.18 又加入
`INVISIBLE → 恢复` 和有效性验证。Android 16 / WebView 150 上连续出现
`webview_unfreeze_ineffective`，直到真正回到前台才恢复。

结论：`WebView.onResume()` 可以解除应用显式 pause，但不能可靠解除窗口隐藏导致的 Blink
冻结；View 可见性切换还会重新引入输入和窗口状态风险。

### 4.4 将 WebView 移入悬浮窗：两次破坏页面，不得重试

v0.4.13 和 v0.4.19～v0.4.20 两次尝试把现有 WebView 移到 1×1
`TYPE_APPLICATION_OVERLAY` 窗口。第二次还加入 `FLAG_SHOW_WHEN_LOCKED`、权限和强制重绘。

虽然悬浮窗可挂载且能避免 freeze 事件，但 WebView 跨窗口 detach/attach 会破坏与窗口绑定的
Surface / compositor 状态，造成白屏、页面丢失或强制 reload。`invalidate()` 无法修复 Chromium
内部渲染状态。

结论：真实页面必须终身留在同一 Activity、同一窗口和原生 `FrameLayout + WebView` 宿主中。

### 4.5 PiP、音频、虚拟时间和自定义 Provider：没有成为当前生产方案

- PiP 最多改善“切后台仍可见”，不能覆盖真实息屏，且与 Kiosk / Lock Task 交互复杂。
- 音频例外依赖真实可听媒体和系统策略，不适合作为隐式保活手段。
- CDP virtual time 不等价于让真实墙钟时间、I/O 和任意业务无限连续推进。
- 自定义 WebView Provider 需要系统镜像、签名、兼容测试和长期维护，不是普通 APK 能控制的能力。

这些路线可保留为研究材料，但不应包装成当前产品能力。

### 4.6 同 UID CDP `frozen → active`：有效但仍是受限实验

源码研究和 Android 10 隔离 PoC 证明：同 UID APK 可以连接自己的 WebView DevTools abstract
socket，发送 `Page.setWebLifecycleState(frozen)` 再发送 `active`，从而解除或阻止一次完整
freeze。Android 16 Release PoC 和生产实验也验证了短时 edge、同一页面实例及 socket 关闭。

生产实现从 v0.4.23 起提供默认关闭的“实验性低频续行”，安全限制包括：

- 必须先开启高性能模式并二次确认风险；
- 只匹配当前可信顶层 Origin 和随机文档 token；
- 必须验证真实 `document.hidden === true`；
- 只发送一次受控 edge；
- debugging 使用 6～10 秒受限租约，并有强制关闭宽限；
- 通过 `WebViewDebuggingGate` 恢复管理员最新 Chrome Inspect 偏好；
- 记录调试 socket 是否确实关闭。

它能降低“完全 freeze 后所有探针停止”的概率，但不会让 hidden 页面恢复前台调度优先级。
最终双设备样本仍是主线程约 60 秒、诊断 Worker 约 5 秒。因此其正确名称是“实验性低频
续行”，不是“后台解冻”或“前台级保活”。

### 4.7 无安全 Keyguard：设备能力例外，不是通用解法

OnePlus 6 / Android 10 曾出现自然例外：移除安全锁屏后，电源键息屏没有让真实页面变
hidden，约 19 分钟内 main、Worker 和 fetch 保持接近前台 cadence，且关闭 debugging/CDP 后
仍能复现。

后续 Xiaomi Android 13 和 OnePlus Android 16 都表明，这个现象不能推广：即使
`secure=false`、`deviceLocked=0`，息屏后仍可能出现非安全 Keyguard 外壳，页面仍进入 hidden；
Xiaomi 的主线程在最初约一分钟内接近 5 秒，随后稳定降为约 60 秒。

结论：`SCREEN_OFF_VISIBLE_CONTINUITY` 只能作为运行时检测到的设备能力状态，不能作为配置
成功后的预期结果，也不能通过伪造来强制实现。

## 5. v0.4.22 ～ v0.4.26 的优化收敛

### v0.4.22：从“强行保活”转为真实观测

- 删除 Page Visibility、生命周期、屏幕状态和 View 可见性伪造。
- 不再移动 WebView，也不再用 `WebView.onResume()` 伪造解冻。
- 增加稳定 load ID、真实 `document.hidden` / `visibilityState`、Keyguard 状态。
- 引入 `SCREEN_OFF_VISIBLE_CONTINUITY`、`HIDDEN_DEGRADED`、`STALE` 等真实能力分类。

### v0.4.23：加入受控 CDP 实验

- 默认关闭、独立风险确认、严格可信 Origin 和文档 token。
- 临时 WebView debugging 租约、偏好恢复和 socket 关闭验证。
- 跨进程即时同步，不依赖长期 `:webview` 进程读取新鲜 SharedPreferences。

### v0.4.24：把低频运行和完全中断区分开

- 主线程超过 20 秒但 Worker 90 秒内仍有证据时，分类为
  `LOW_FREQUENCY_RESPONSIVE` / `HIDDEN_LOW_FREQUENCY_CONTINUITY`。
- 综合状态使用 `BACKGROUND_THROTTLED`，不再误报 `STALE`。
- 增加保守/均衡/激进三档有界 CDP 时序、默认关闭的详细日志和脱敏诊断导出。

### v0.4.25：修正隐藏旧标签和 URL 身份

- 从未产生心跳、可见性或 load ID 的 `View.GONE` 旧标签保持等待激活，不再污染健康会话的
  综合状态。
- 标签重新可见时轮换 token 并重试 bootstrap；有过真实证据后再丢失仍按中断处理。
- 规范化 HTTP(S) 根地址身份，避免 `https://host` 和 `https://host/` 创建重复标签。

### v0.4.26：消除恢复阶段的 WebView 原生崩溃

Xiaomi Android 13 / WebView 150 稳定复现：HOME 后外部链接恢复已有标签时，重复拆装
document-start ScriptHandler / WebMessage listener，约 300 ms 后在
`libwebviewchromium.so` 发生 `SIGSEGV`，fault address 为 `0x18`。

最终修复原则：

- 相同版本、相同有效配置直接忽略，`generatedAt` 变化不算真实变更；
- 相同版本但内容冲突的普通快照拒绝；
- CDP 时序、详细日志、WakeLock 租期等控制字段变化不重装页面 runtime；
- 只有启用的可信 Origin 范围变化才重新安装页面脚本与消息监听器；
- Activity 使用控制器实际接受的快照，而不是被拒绝的 Intent 副本；
- 保留 `publication_failed` 同版本禁用墓碑的 fail-closed 特例。

这次事故形成了一个可复用经验：**WebView document-start ScriptHandler 和 WebMessage
listener 是有原生生命周期的对象，不能因为 Activity resume/new intent 或无效配置刷新而反复
拆装。运行配置必须语义比较、幂等应用，并把“页面 runtime 字段”和“控制器字段”分开。**

## 6. 设备验证证据

### 6.1 验证矩阵

| 设备 | Android / WebView | 主要结果 |
| --- | --- | --- |
| OnePlus PHB110 | Android 16 / SDK 36 / WebView 150 | HOME/息屏保持页面实例；真实 hidden；主线程稳态约 60 秒、Worker 约 5 秒；CDP edge 与 socket 关闭已验证 |
| Xiaomi M2105K81C | Android 13 / SDK 33 / WebView 150 | HOME、息屏、Light Doze 保持页面实例；hidden 主线程约 60 秒、Worker 约 5 秒；Deep Doze shell 强制被 MIUI 拒绝 |
| OnePlus 6 | Android 10 / SDK 29 / WebView 150 | CDP PoC、7.45 小时页面/renderer 存活及无安全 Keyguard 自然 visible 例外；不是通用设备结论 |

### 6.2 v0.4.26 Xiaomi 正式发布包闭环

- 正式 APK：Enhanced `0.4.26 (101)`。
- WebView PID：`13383`。
- Session：`f8fd42c6-9a61-4521-8382-81b7ab5cb5bb`。
- Load ID：`1785818978014`。
- 交替执行五轮 `https://map.anzz.site` 与尾斜杠 URL 的 HOME → 外部再入。
- 全程 PID、session、load ID 不变；没有新的 Page started/finished、renderer loss 或 crash。
- 息屏和 Light Idle 中 FGS、WakeLock、通知和电池豁免保持；DevTools socket 关闭。
- Light Idle 中 Worker 约 5 秒、主线程约 60 秒；亮屏后主线程恢复约 5 秒。
- 移除设备锁后仍是同一长期稳态，说明取消安全凭据不能消除 hidden-page throttling。

### 6.3 证据的正确解释

- 相同 PID 只证明 WebView 进程没换。
- 相同 session 只证明高性能逻辑会话没换。
- 相同 load ID 才能较强地证明当前顶层 document 没有重载。
- Worker 心跳只证明注入的 Dedicated Worker 被调度，不能代表页面全部业务。
- 主线程 60 秒一次代表低频连续性，不代表“正常运行”。
- 无 DevTools socket 是实验租约安全收尾的重要证据。

## 7. 当前运行状态应如何解释

| 状态 | 解释 | 产品判断 |
| --- | --- | --- |
| `ACTIVE` | 系统资源完整，前台主线程探针正常 | 当前正常 |
| `BACKGROUND_THROTTLED` | hidden 页面主线程已降频，但 Worker 在窗口内仍有证据 | 页面存活且低频运行，不是前台级 |
| `HIDDEN_LOW_FREQUENCY_CONTINUITY` | 真实 hidden，Worker 仍近期响应 | 可以继续观察，业务必须容忍延迟与中断 |
| `HIDDEN_DEGRADED` | 真实 hidden，但没有足够 Worker 证据 | 不能宣称页面仍连续执行 |
| `STALE` | main 和 Worker 证据都过期 | 页面调度已停止或无法证明 |
| `SCREEN_OFF_VISIBLE_CONTINUITY` | 息屏但真实 document 仍 visible，主线程正常 | 罕见设备能力例外，必须现场验证 |
| `INTERRUPTED` / `ERROR` | FGS、进程、配置或 renderer 等发生明确故障 | 需要排障或恢复 |

## 8. 诊断与测试方法沉淀

### 8.1 每次实机报告必须记录

- App 版本和 versionCode；
- Android 版本、SDK、OEM、型号；
- WebView provider 包名和版本；
- WebView PID、process instance、session、load ID；
- Activity 状态、真实 `document.hidden` / `visibilityState`；
- native/main/Worker 心跳时间和年龄；
- FGS、WakeLock、通知、电池优化、renderer priority；
- Keyguard showing / secure / deviceLocked；
- Doze 状态和测试持续时间；
- crash buffer、renderer loss、页面 started/finished；
- 实验性 CDP 是否启用、时序档位、socket 是否关闭。

### 8.2 最短有效采样窗口

- HOME 或息屏后只观察几十秒容易误判。
- Xiaomi 无安全锁屏样本最初约一分钟主线程仍接近 5 秒，之后才降到约 60 秒。
- 因此能力分类至少观察 90 秒；常规回归建议 3～5 分钟；长时可靠性必须单独做小时级测试。

### 8.3 推荐判定顺序

1. PID/process instance 是否变化：先排进程重启。
2. load ID 是否变化：排页面重载或 renderer 重建。
3. native heartbeat 是否持续：判断控制器/进程是否仍调度。
4. Worker heartbeat 是否持续：判断是否还有后台低频页面证据。
5. main heartbeat cadence：区分前台、低频节流和停止。
6. `document.hidden`、Activity、screen、Keyguard：解释调度变化来源。
7. FGS/WakeLock/通知/电池：解释资源保护缺口。
8. page lifecycle、CDP 和 socket 事件：确认实验 edge 与安全收尾。

### 8.4 测试后必须恢复的设备状态

```bash
adb shell dumpsys deviceidle unforce
adb shell dumpsys battery reset
adb shell input keyevent 224
```

同时恢复测试前的屏幕超时、亮度模式、通知授权和其他临时设置。MIUI 不能强制进入 Deep Doze
时，应记录为 ROM 测试限制，不能当作应用通过了 Deep Doze。

## 9. 工程经验与长期约束

1. **真实观测优先于伪造状态**：不能为了得到绿色状态破坏 Android/WebView 生命周期契约。
2. **WebView 必须留在同一窗口**：不跨 Activity、Service 或 overlay 搬移真实页面。
3. **页面调度和资源存活必须分层建模**：FGS/WakeLock 健康不等于 JS 健康。
4. **跨进程配置必须显式同步**：`:webview` 长进程不能依赖 SharedPreferences 新鲜度。
5. **配置应用必须幂等**：先比较 configVersion 和有效内容，再决定是否触碰 WebView 原生对象。
6. **配置字段按影响面分层**：只有可信 Origin 范围变化才应重装页面 runtime；控制器参数应原位更新。
7. **失败必须 fail-closed**：快照解析、发布或同版本冲突不能回退到旧的 enabled 配置。
8. **调试能力是安全边界**：WebView debugging 进程全局，统一通过 `WebViewDebuggingGate`，默认关闭、短租约、精确 Origin、验证 socket 关闭。
9. **诊断不得过度推论**：探针只证明探针本身；产品 UI 必须明确“低频”“降级”“无法证明”。
10. **同 PID 不等于同页面**：回归必须同时保留 session 和 load ID。
11. **隐藏未激活标签不是中断证据**：无心跳、无 visibility、无 load ID 时先等待激活；有过证据后丢失才算降级。
12. **OEM 结论必须带设备和版本**：Android 10 的自然 visible 成功不能覆盖 Android 13/16 的 hidden 结果。

## 10. 下一阶段方向

### 10.1 推荐的正式架构方向

若业务要求息屏后秒级、可靠、可补偿地持续工作，应把关键任务移出 WebView：

- 定位由符合权限和 FGS 类型要求的原生定位服务负责；
- WebSocket、轮询、上传、计时和 outbox 由原生服务或服务端负责；
- 使用持久化队列、幂等事件和断线重连保证业务连续性；
- WebView 作为展示与交互层，前台恢复后从原生层同步状态；
- 页面监听 `visibilitychange`、`freeze`、`resume`，保存状态并在恢复后补偿。

这条路线不能让页面本身前台级运行，但能真正满足业务连续性目标。

### 10.2 高性能模式后续定位

- 继续作为“可信页面存活、低频连续性和恢复增强”能力维护。
- 实验性 CDP 保持默认关闭，不扩大到任意 Origin，不延长为持久 debugging。
- 每次 WebView provider 大版本升级后重新做短时、长时和安全租约回归。
- Android 16、Xiaomi 及后续目标设备继续维护明确的兼容矩阵。
- 若平台未来提供受支持的后台 WebView 调度 API，再重新评估产品承诺。

### 10.3 不再投入的路线

- WebView/View 可见性、屏幕状态或生命周期伪造；
- 反复调用 `WebView.onResume()` 或 View 可见性切换解冻；
- 把现有 WebView 搬到 Service、overlay 或另一窗口；
- 用隐式音频、无限 DevTools、无限 WakeLock 等方式规避平台策略；
- 只依据 FGS/WakeLock 或单一心跳宣称“网页正常运行”。

## 11. 文档索引

- 历史探索与失败路线：[background_continuity_research_report.md](background_continuity_research_report.md)
- Chromium 源码与候选技术深研：[background_continuity_deep_research.md](background_continuity_deep_research.md)
- 高性能运行时排障手册：[runbooks/high_performance_web_runtime.md](runbooks/high_performance_web_runtime.md)
- 实验性 CDP 操作手册：[runbooks/experimental_cdp_continuity.md](runbooks/experimental_cdp_continuity.md)
- Xiaomi Android 13 现场证据：[runbooks/xiaomi_android13_background_continuity.md](runbooks/xiaomi_android13_background_continuity.md)
- 无安全 Keyguard 能力测试：[runbooks/managed_screen_off_webview_continuity.md](runbooks/managed_screen_off_webview_continuity.md)
- CDP 隔离 PoC：[runbooks/webview_background_continuity_cdp_poc.md](runbooks/webview_background_continuity_cdp_poc.md)
- 产品与架构需求：[requirements/high_performance_web_runtime_requirements.md](requirements/high_performance_web_runtime_requirements.md)
- 测试页：[test-pages/high_performance_runtime_test.html](test-pages/high_performance_runtime_test.html)

## 12. 阶段完成标准

本阶段不以“WebView 已实现前台等价后台运行”为完成标准，而以下列事实为阶段成果：

- 已明确并实机验证平台能力边界；
- 已停止多条高风险且无效的路线；
- 已把页面存活、页面调度和业务连续性分层；
- 已实现可信 Origin、资源保护、真实观测、低频分类、诊断导出和受控实验开关；
- 已在 Android 13 / Android 16 目标设备完成正式包闭环；
- 已修复重复配置导致的 WebView 原生崩溃；
- 已形成可复用的设备验证和问题定位方法；
- 已明确真正的强业务连续性需要原生/服务端架构承载。

因此，当前阶段可以视为：**高性能 WebView 的应用层能力已经收敛到可维护、可诊断、边界
诚实的低频连续性方案；“前台级息屏运行”仍是未被 Android WebView 公共能力解决的平台问题。**
