# Android WebView 后台/息屏持续运行技术探索报告

> 探索周期：v0.4.13 ~ v0.4.21（2026-07-31 ~ 2026-08-01）
> 测试环境：OnePlus PHB110 · Android 16 (API 36) · Google WebView 150.0.7871.181
> 原始结论：**三条技术路线均已实证证伪**，当时基线为 v0.4.21；2026-08-03 的后续结论见下方“深研更新”。

> **深研更新（2026-08-03）**：本文的“唯一恢复路径”结论是针对公开、受支持的 Android WebView API。隔离 PoC 已在 OnePlus 6 / Android 10 / WebView 150 上验证 DevTools/CDP `frozen → active` 能解除或阻止完全冻结，并可由 APK 同 UID 自己操作；同一页面/renderer 已存活 7.45 小时且通过前台恢复和 IME。但主线程 timer/fetch 在 hidden 约 5 分钟后仍降为约 60 秒一次，2 小时 WakeLock 到期后又出现 CPU suspend 间隙，所以它不是前台级持续运行。生产实现已在 v0.4.23 候选中加入默认关闭、可信 Origin 限定、短租约和风险确认的实验性低频续行；Android 16 设备闭环仍需现场验证，不能把它表述成前台级保证。详见 [background_continuity_deep_research.md](background_continuity_deep_research.md)。

> **设备闭环更新（2026-08-04）**：生产 v0.4.24 已在 OnePlus PHB110 / Android 16 和 Xiaomi M2105K81C / Android 13（均为 Google WebView 150.0.7871.181）完成现场验证。HOME 与电源键息屏都保持同一 PID、session、load ID，无页面重载；实验性 CDP 边缘触发成功且临时调试 socket 每次约 0.54 秒后关闭。两个设备的真实页面都进入 hidden，主线程随后约 60 秒运行一次，Dedicated Worker 保持约 5 秒采样，因此稳定分类为 `LOW_FREQUENCY_RESPONSIVE` / `HIDDEN_LOW_FREQUENCY_CONTINUITY`，不是前台级运行。Xiaomi 强制 Light Doze `IDLE` 约 3 分钟仍保持该结果；MIUI 拒绝 shell 强制 Deep Doze，状态停在 `INACTIVE`。详见 [xiaomi_android13_background_continuity.md](runbooks/xiaomi_android13_background_continuity.md)。

---

## 1. 背景与目标

**产品诉求**：开启"高性能持续运行"并将站点加入可信名单后，可信网站在**息屏 / 切换后台**时，应像在前台一样持续稳定执行定时器与网络请求。

**技术约束**：真实网页必须运行在 `WebViewActivity -> FrameLayout -> WebView` 原生路径；不能破坏儿童 Kiosk 安全沙箱；不能破坏网页输入法（IME）；不能移动 WebView 导致页面丢失。

**结论先行**：在 Android 16 + WebView 150 上，**"页面在后台/息屏保持前台级运行"无法通过公开、受支持的应用层 API 实现**。已实证证伪三条生产路线（可见性伪造、`WebView.onResume()` 解冻、物理悬浮窗）；当前稳定能力边界为"进程与资源存活 + 回前台自动恢复"。Android 10 的 CDP PoC 把 hidden 页能力推进到“避免完全 freeze、Worker 继续运行”，但没有消除主线程节流，且安全/兼容性没有公开 API 保证。另有一条受管设备条件化结果：移除安全 Keyguard 后，纯电源键息屏没有让页面 hidden，实机约 19 分钟保持前台 cadence，且关闭 debugging/CDP 后复核仍通过；它仍需在 Android 16/OEM 目标设备逐机验证，不能当作通用 Android 契约。

---

## 2. 系统架构基线（v0.4.14 稳定基线）

高性能运行时（全部运行在 `:webview` 独立进程）由四层构成：

| 层次 | 组件 | 作用 |
|---|---|---|
| 进程/资源层 | `HighPerformanceForegroundService`（FGS，`specialUse` 类型，30s 健康检查） | 进程保持前台优先级，防被杀/缓存冻结 |
| | `HighPerformanceWakeLockController`（`PARTIAL_WAKE_LOCK`，10min 租约，Handler + `AlarmManager.setAndAllowWhileIdle` 双通道续期） | 息屏/Doze 下 CPU 保持清醒（v0.4.3 修复续期死循环） |
| | 渲染器优先级 `RENDERER_PRIORITY_IMPORTANT`（不 waive） | 降低渲染器被 OOM 回收概率 |
| | 电池优化豁免 + 通知权限（Android 13+）检查 | 前置条件校验 |
| 页面监控层 | `HighPerformancePageRuntime`：`addDocumentStartJavaScript` 注入运行时脚本 + Origin 限定 WebMessage 桥 | 心跳探针（5s 主线程 + Worker）、真实 Page Visibility / load ID 采样、freeze/resume/visibilitychange 事件上报；v0.4.22 起不再伪造可见性或拦截生命周期事件 |
| 会话状态机 | `HighPerformanceSessionController`：Origin 匹配 → 会话建立 → 心跳 20s 无响应判定 STALE/DEGRADED | 资源启停、渲染器崩溃恢复、状态发布 |
| 诊断体系 | `HighPerformanceDiagnostics` 事件流 + 三路心跳（native/main/worker）+ 复合状态机 | 观测与排障 |

---

## 3. 问题现象与诊断证据

**症状**：切后台或息屏约 60 秒后，网页定时器与网络请求全部中断；回前台立即恢复。

**诊断证据链**（v0.4.16 ~ v0.4.18 日志，三份日志结论一致）：

```
14:08:07  activity_stopped          ← 切后台
14:08:07  visibility_change         ← Blink 页面变 hidden
14:09:07  freeze                    ← 后台 60 秒后 Blink 冻结页面
14:09:07  webview_unfrozen          ← 解冻尝试（v0.4.17/18）
14:09:17  webview_unfreeze_ineffective  ← 解冻无效（心跳未恢复）
14:10:21  之后每 30s 一次无效解冻（v0.4.18 已降频）
14:15:22  resume + js_heartbeat_responsive  ← 只有回前台才真正恢复
```

同时刻资源层状态**完全健康**：`fgs=RUNNING`、`wakeLock=HELD`、`batteryIgnored=true`、渲染器未崩、进程未死。

**根因**：Chromium（Blink）对**隐藏页面**执行 Page Lifecycle `freeze`（窗口不可见后约 60 秒触发），冻结后页面所有定时器、Worker、网络任务全部挂起，直至下一次前台过渡。**进程/CPU 存活 ≠ 页面调度存活**——FGS 与 WakeLock 只能保住前者。

---

## 4. 探索路线与实证结论（核心章节）

### 路线 A：资源保护层强化（v0.4.3 起，基线能力）

| 尝试 | 结果 |
|---|---|
| `PARTIAL_WAKE_LOCK` + Handler 续期 | ❌ 息屏后 CPU 强制挂起，Handler 不再触发 → WakeLock 过期 → 死循环（v0.4.3 前） |
| 双通道续期：Handler（CPU 清醒时）+ `AlarmManager.setAndAllowWhileIdle`（Doze 唤醒，广播持锁执行续期） | ✅ 进程/CPU 存活稳定，WakeLock 不再过期 |

**结论**：资源层已到平台上限，但**无法阻止页面级冻结**。

### 路线 B：可见性伪造（v0.4.0–v0.4.9 起源；v0.4.15 重试，两次证伪）

**做法**：`PersistentWebView` 覆盖 `onWindowVisibilityChanged` / `onVisibilityChanged` / `getWindowVisibility` / `isShown` / `onScreenStateChanged` / `onPause`，向 Chromium 伪造"可见"。

**失败 1（v0.4.10 回退）**：无条件伪造 → **所有模式**下网页输入框无法唤起 IME。
**失败 2（v0.4.15，v0.4.16 回退）**：改为"仅受保护页面 + 宿主 Activity 处于 PAUSED/STOPPED 时"受控伪造 → **IME 依然全坏（可信+非可信网站），且冻结依旧**。

**机制解释**：
- Chromium 输入连接（InputConnection）是**窗口级状态**。伪造可见性使 Chromium 输入状态与系统 ViewRootImpl 真实输入通道失步，后台/前台切换后窗口内**所有** WebView 都无法重建输入连接 → IME 全坏。伪造的"时机控制"无法解决，因为破坏发生在状态缓存层面。
- 冻结判定**不依赖（或不只依赖）View 可见性回调**：v0.4.15 伪造明显生效（IME 被破坏为证），但 60 秒冻结照常发生。

**结论**：❌ 结构性不可行，任何时机、任何范围的可见性伪造都不可接受。

### 路线 C：冻结后解冻（v0.4.17 / v0.4.18，证伪）

**理论**：`WebView.onResume()` 对应 `WebContents.SetFrozen(false)`，理论上可唤醒冻结页面；且 Blink freeze 计时器每次隐藏过渡只触发一次。

**v0.4.17**：收到 `freeze` 信号后立即 `webView.onResume()`。**无效**——解冻后页面从未恢复心跳、从未收到 `resume` 信号，直到前台过渡。
**v0.4.18**：升级组合拳（`onResume()` + view 可见性属性短暂切换 `INVISIBLE→恢复` + `onResume()`）+ 10s 后心跳有效性验证（`webview_unfreeze_ineffective` 事件）+ 连续 3 次无效后降频至 5 分钟一次。**无效**——`unfreeze_ineffective` streak 1→2→3 后自动停止，页面 7 分钟后台期间完全冻结。

**结论**：❌ `WebView.onResume()` 无法解冻"窗口隐藏导致的 Blink 冻结"（它只解除 `onPause()` 显式暂停）。冻结的恢复路径只有 `WasShown()`（窗口真实可见）。

### 路线 D：物理窗口保活（v0.4.13 / v0.4.19–20，两次独立证伪）

**理论**：既然冻结跟随"窗口不可见"，就让 WebView 处于**真实可见**的窗口——系统悬浮窗。

**v0.4.13（Overlay v1）**：Activity `onStop` 时把受保护 WebView `removeView` 移入 1x1 `TYPE_APPLICATION_OVERLAY` 窗口，`onStart` 移回。**失败**：`onDetachedFromWindow` 触发 Surface Compositor 销毁，恢复前台时**白屏/崩溃/强制 Reload**（v0.4.14 回退）。

**v0.4.19–20（Overlay v2）**：修复版——`FLAG_SHOW_WHEN_LOCKED`（息屏锁屏时窗口保持可见，理论上冻结计时器无从启动）+ 挂载/移回时 `forceRedraw`（`onResume` + 多次 `invalidate` 强制 Chromium 重新合成）+ 仅挂载期间屏蔽 `SCREEN_STATE_OFF`（前台 100% 原生，IME 不受影响）+ 补回 `SYSTEM_ALERT_WINDOW` 权限声明（v0.4.20）。**挂载成功、全程无 freeze**，但**页面在挂载后直接丢失、回前台需重新加载**——与 v0.4.13 同源失败（v0.4.21 回退）。

**机制解释**：WebView 渲染 Surface 与窗口绑定，跨窗口移动（detach/attach）导致 Chromium 合成器/渲染上下文损坏。`forceRedraw` 无法弥补——损坏发生在 Chromium 内部状态机，而非"没触发重绘"。

**结论**：❌ 跨窗口移动 WebView 在任何形式下都会破坏页面，**已两次独立验证，不得再尝试**。

### 路线 E：画中画 PiP（未实施，理论评估）

- 原理：Activity 进入 PiP 后窗口保持可见 → 页面不 hidden → 不冻结。
- **局限**：① 仅"切后台"场景有效，**息屏时 PiP 窗口同样被暂停/隐藏，冻结依旧**；② Kiosk / Lock Task（Tier 1/2）下的 PiP 行为未验证，可能被禁用；③ 后台出现悬浮小窗，与儿童 Kiosk 设计冲突。
- 状态：❌ 理论半可行，未达"息屏+后台完全正常运行"目标，暂不实施。

---

## 5. 关键机制知识（防止重走弯路）

1. **Blink 隐藏页面冻结（Page Lifecycle freeze）**：窗口不可见 → 页面 hidden → 约 60 秒后冻结（Android WebView 比 Chrome 桌面激进，桌面仅内存压力才冻结）。冻结 = 定时器/Worker/网络/渲染全部挂起。**对公开 WebView API，唯一可靠恢复路径 = 窗口真实可见（前台过渡）**；实验性 CDP/音频/虚拟时间路径见[深研文档](background_continuity_deep_research.md)。
2. **`WebView.onResume()` ≠ 解冻**：它只解除 `onPause()` 的显式暂停；对"隐藏触发"的 Blink 冻结无效（v0.4.17/18 实证）。
3. **Chromium 输入连接是窗口级状态**：任何 View 可见性/窗口可见性/屏幕状态伪造（无论时机与范围）都会破坏 IME，影响窗口内所有 WebView。**可见性层永远不能碰**。
4. **WebView 跨窗口移动 = 渲染损坏**：Surface 绑定窗口，detach/attach 损坏 Chromium 合成状态；白屏/重载/页面丢失，`invalidate` 无法补救。**WebView 必须终身驻留同一窗口**。
5. **FGS + WakeLock 只保进程与 CPU**，不保页面调度；心跳三路（native=进程、main/worker=页面）是区分"进程死了"与"页面冻结"的关键观测。
6. **OEM/版本差异**：OnePlus ColorOS + Android 16 + WebView 150 的组合行为（60s 冻结、对可见性伪造免疫、overlay 挂载后页面丢失）需记录为已知平台事实；其他 OEM/版本可能表现不同，验证新方案前必须做设备矩阵测试。

---

## 6. 当前边界与未解问题

| 能力 | 状态 |
|---|---|
| 进程/渲染器/CPU 存活（FGS+WakeLock+渲染器优先级） | ✅ 稳定（基线） |
| 回前台自动恢复（页面续行、心跳恢复） | ✅ 稳定（基线） |
| 页面冻结状态可观测（freeze 信号、STALE、unfreeze_ineffective） | ✅ 稳定（诊断） |
| **后台/息屏页面前台级持续运行（公开 API）** | ❌ **平台限制，公开应用层无解** |
| 无安全 Keyguard 的纯息屏（Android 10 / OnePlus 6） | ⚠️ 约 19 分钟页面保持 visible 且 main/Worker/fetch 保持前台 cadence；关闭 debugging/CDP 复核通过；强依赖设备策略，Android 16 未验证 |
| CDP 避免完全 freeze（生产 v0.4.24 + M150） | ⚠️ Android 10 PoC、Android 13 Xiaomi、Android 16 OnePlus 均已验证同 UID 可自控且不重载；hidden 主线程仍约 60 秒节流，Worker 较高频，不能当作前台级运行 |
| PiP（切后台场景） | ⚠️ 理论可行，未实施（息屏无效） |
| 页面侧 freeze/resume 适配（站点配合） | ✅ 可行，业务连续性最优（**不等于完全正常运行**，冻结期间站点仍暂停） |

**未解问题**：Android 平台是否会提供"后台 WebView 渲染/调度策略"类新 API（待追踪后续 Android/WebView 版本）；OEM 行为差异能否通过设备矩阵规避。

---

## 7. 后续方向候选（待规划，勿直接实施）

1. **CDP 跨版本/完整性补测**：先在 Android 16 复现 Android 10 结果，再覆盖可续期 WakeLock 下的长时业务延迟、导航、renderer crash、Doze/热控和安全边界；在完成前保持隔离 PoC，不接生产开关。
2. **PiP 专项试验**：仅切后台场景；需先验证 Lock Task 兼容性；接受"息屏仍冻结"。
3. **站点侧 Page Lifecycle 适配**（推荐底线能力）：站点监听 `freeze`/`resume`，冻结时保存状态、恢复后续传重连——把"感知中断"降到最低。
4. **业务迁移**：关键定时/上报逻辑从网页迁到服务端或 Android 原生层（FGS/native network/outbox；不要把 WorkManager 当持续亚秒调度器）——架构级改动。
5. **追踪平台演进**：Android 16+ 后续 WebView 版本若有后台调度策略 API，重新评估。
6. **生产条件化路径**：v0.4.22 采用 observation-only 探针；无安全 Keyguard 的目标设备若实测保持 visible，则报告息屏连续运行，否则明确降级并保留前台恢复预期。

---

## 8. 版本时间线附录

| 版本 | 变更 | 结果 |
|---|---|---|
| v0.4.3 | WakeLock 双通道续期（Alarm 兜底） | ✅ 资源层稳定 |
| v0.4.0–0.4.9 | 可见性伪造（起源期） | ❌ IME 破坏 |
| v0.4.10 | 移除可见性伪造（保 IME） | ✅ IME 恢复 |
| v0.4.11 | 删除 `PersistentWebView`（IME 限制设置下线） | — |
| v0.4.13 | Overlay v1（1x1 悬浮窗） | ❌ 白屏/重载 |
| v0.4.14 | 回退 Overlay v1 | ✅ 稳定基线 |
| v0.4.15 | 受控可见性伪造 | ❌ IME 全坏 + 冻结依旧 |
| v0.4.16 | 回退 v0.4.15 | ✅ 稳定基线 |
| v0.4.17 | `onResume()` 解冻 | ❌ 无效 |
| v0.4.18 | 组合拳解冻 + 有效性验证 + 降频 | ❌ 无效（实证） |
| v0.4.19–20 | Overlay v2（SHOW_WHEN_LOCKED + forceRedraw + 权限） | ❌ 页面丢失 |
| **v0.4.21** | **回退 Overlay v2，最终基线** | ✅ 页面不丢、冻结+前台恢复 |
| **v0.4.22** | **真实 Page Visibility 观测 + 无安全 Keyguard 条件化路径** | ✅ Android 10 已验证；Android 16/OEM 需逐机复测 |
| **v0.4.23** | **默认关闭的实验性 CDP 低频续行** | ⚠️ 仅可信 Origin、短租约、明确风险确认；避免完全 freeze 但不保证前台级调度 |
| **v0.4.24** | **后台低频心跳分类 + 脱敏导出 + 详细诊断** | ✅ OnePlus Android 16 与 Xiaomi Android 13 均确认 hidden 主线程约 60 秒、Worker 继续，保持同 PID/load ID 且无重载 |

---

## 9. 诊断观测指南

**关键事件解读**：

| 事件 | 含义 |
|---|---|
| `freeze` / `resume`（page_lifecycle_signal） | 页面被 Blink 冻结 / 恢复（页面 JS 探针上报） |
| `js_heartbeat_stale` | 主线程心跳 20s 无响应 = 页面已冻结 |
| `webview_unfrozen` / `webview_unfreeze_ineffective` | 解冻尝试 / 解冻无效（streak N） |
| `overlay_attached` / `overlay_attach_failed` / `overlay_permission_missing` | 悬浮窗挂载结果（v0.4.19–20 期间） |
| `fgs_started` / `fgs_start_failed` | 前台服务状态 |
| `wake_lock_renewed` | WakeLock 续期（CPU 存活证据） |
| `screen_off` / `screen_on` | 屏幕状态变化 |

**判定树**（后台 2–5 分钟后）：
1. `nativeHeartbeat` 停止 → 进程/资源层问题（FGS 被杀、通知权限、OEM 后台限制）
2. native 心跳正常 + `freeze` + main/worker 心跳停 → **页面冻结（平台行为，公开 API 无解；实验性路径见深研文档）**
3. native 正常 + main 心跳在但业务定时器停 → 站点自身逻辑问题（站点监听 visibilitychange 主动停止）

**ADB 命令**：

```bash
adb shell dumpsys activity services site.anzz.childkiosk      # FGS 状态
adb shell dumpsys power | grep -i HighPerformanceWebSession  # WakeLock 状态
adb shell ps -A | grep childkiosk                             # 进程存活
```

**测试页**：`docs/test-pages/high_performance_runtime_test.html`（三路心跳 + 业务时间戳）。

---

## 10. 附：相关代码与文档索引

- 运行手册：`docs/runbooks/high_performance_web_runtime.md`（含本报告结论的"不要重试"警告）
- 核心代码：`app/src/main/java/site/anzz/childkiosk/performance/`（SessionController、ForegroundService、WakeLockController、PageRuntime、ProcessState、RuntimeStatus 等）
- 版本历史：`docs/CHANGELOG.md`（v0.4.13–v0.4.21）
