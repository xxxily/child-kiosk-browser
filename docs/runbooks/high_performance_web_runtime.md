# High-performance web runtime runbook

## Purpose and product boundary

High-performance runtime protects only parent-approved top-level HTTP/HTTPS Origins. It combines a foreground service in `:webview`, a bounded partial CPU WakeLock, and a non-waived renderer-priority request. It improves survival probability; it does not guarantee uninterrupted JavaScript, timers, network sockets, renderer memory, or foreground-equivalent Chromium scheduling.

The runtime must preserve Android's real WebView lifecycle contract. It does not falsify a
WebView's visibility, window visibility, `isShown()`, screen state, or `onPause()` callbacks. Those
signals are part of Chromium's focus and IME integration. FGS/WakeLock protect process and CPU
availability; renderer priority is an OOM/offscreen-waiver hint. None of them make a hidden page a
foreground view.

> **v0.4.15 regression (reverted in v0.4.16)**: a "controlled" variant that faked visibility/screen
> state ONLY while a protected page's Activity was paused/stopped still broke IME globally. Even
> gated deception desynchronizes the window-level input channel from Chromium's input state: after
> a background/foreground cycle the IME can no longer be summoned on ANY WebView in the window,
> protected or not. This confirms the v0.4.10 conclusion structurally: Chromium input connections
> are window-scoped, so faking view/window visibility is never safe. Do not reintroduce any
> visibility/screen-state/onPause falsification for this feature; the physical overlay attempt
> (v0.4.13) had its own Surface-detach failure and is also not a verified path.

## Architecture signals

- Room in the main process is the source of truth for the global switch and Origin rules.
- A versioned AtomicFile is the cross-process runtime source. The main process writes it after a successful Room transaction and broadcasts only that a newer version exists.
- If AtomicFile publication or the config-update signal fails after Room commits, a disabled snapshot at the new config version becomes the disk/cache source of truth and `publication_failed` stops existing sessions. The admin UI must report the synchronization failure and allow retry; it must not claim the change was fully applied.
- `WebViewActivity` reevaluates only managed top-level WebViews. Preload/blank WebViews and subresources cannot create sessions.
- `HighPerformanceForegroundService` and `WebViewActivity` must both report the package process suffix `:webview`.
- The Service owns the only partial WakeLock. Page/session code never acquires a WakeLock directly.
- Runtime status is written by `:webview` and is considered stale after its documented freshness window.
- Trusted-page lifecycle protection is installed with `addDocumentStartJavaScript` and a strict
  Origin-scoped WebMessage listener before site scripts run. A random per-navigation token binds
  main-thread and Dedicated Worker heartbeats to the current protected session; the token is never
  persisted. A responsive probe proves only that the injected diagnostic timer was scheduled, not
  that every site timer, socket, fetch, or business task stayed continuous.
- Runtime configuration broadcasts reconfigure every managed WebView and update the live Activity
  snapshot. Disabling or removing a rule deactivates the current document immediately; changing
  rules must not rely on a future Activity restart or a stale launch Intent.
- Notification Stop suppression is tracked per logical tab across frozen-tab and renderer reconstruction. A confirmed user action may re-authorize only that tab; it must not silently re-enable other stopped tabs or script-created popups.
- A health-time-limit latch is owner-scoped. Ordinary navigation cannot clear it; only successful parent authorization may resume that Activity, and it must not resume another Activity or override a concurrent global Stop.
- The ordinary background-WebView cap may freeze ordinary tabs only. Protected tabs stay alive even when retaining them temporarily exceeds the normal cap; the runtime records one degraded warning until the owner returns within the cap.
- In normal mode, real pages use `PersistentWebViewActivity` in the dedicated `${applicationId}.webview` task. Device Owner, active Lock Task, and configured soft-lock launches use the same-task `WebViewActivity`; the main process selects the host and passes the runtime snapshot.

## Common symptoms

### Rule changes do not affect an already-open page

Check, in order:

1. The Room mutation increased `configVersion`.
2. The AtomicFile contains the same or newer version.
3. The `:webview` receiver adopted that version rather than an older Intent snapshot.
4. The page's committed top-level URL normalizes to the configured Origin.
5. The WebView is a real managed tab, not a preload or `about:blank` instance.

Do not diagnose this by reading `SharedPreferences` in `:webview`; cross-process preference freshness is not part of the design.

If the Room version advanced but publication failed, retry from the detail-page refresh action. Until a successful publish occurs, the runtime intentionally stays disabled at the newer version instead of falling back to an older enabled file.

### Switching to background immediately destroys the page

Check `dumpsys activity activities` and the `activity_destroyed task_*` diagnostic. If `MainActivity(singleTask)` and the normal-mode WebView share one task, Launcher/HOME re-entry clears the WebView above Main and destroys its DOM/JS heap. This is a task-stack failure, not a JavaScript throttling failure.

Normal mode must resolve to `PersistentWebViewActivity` with the dedicated `.webview` affinity. Device Owner, active Lock Task, and configured soft lock must resolve to same-task `WebViewActivity`; unconditional task isolation is a release blocker because Screen Pinning/Lock Task can reject the cross-task launch.

The high-performance notification must resume the exact live WebView host. It must never target `MainActivity(singleTask)`: in the same-task kiosk topology that would clear every Activity above Main and destroy the page. The floating browser Home action follows the same split: normal-mode `PersistentWebViewActivity` brings Main's task forward without finishing the WebView task, while same-task `WebViewActivity` finishes back to Main as an explicit user exit. If the protection mode changes while the other host is still alive, the new host checkpoints the tabs and removes the old host before restoring them; two hosts must not share the process-local tab/controller state concurrently.

Do not fix this by changing Main to `singleTop`, skipping `super.onStop()`, or clearing `Application.ActivityLifecycleCallbacks`. Those approaches break HOME singleton or lifecycle contracts without preserving both task topologies.

### A protected tab increases memory usage above the ordinary cap

This is intentional L1 behavior: the browser must not destroy an active protected page to enforce its normal WebView count. Confirm that ordinary background tabs were frozen first and look for `protected_tab_retained_over_memory_cap`. The warning is bounded until the Activity returns within the normal cap. System or renderer memory pressure may still interrupt the page and should follow the normal renderer-recovery path.

### A health-limit dialog is visible in one browser Activity

Only that Activity's owner should be suppressed. Navigation, refresh, tab switching, renderer reconstruction, and configuration re-enable must not bypass the latch. Successful parent verification resumes only that owner; notification/admin Stop remains authoritative if it happened concurrently.

### Renderer protection is active but FGS/WakeLock is not

This is an expected degraded state when Android 13+ notification permission is missing, when the first FGS start would occur from the background, or when the Service start failed. Inspect the readiness checklist and the most recent `fgs_start_failed` record. Battery optimization not being ignored also makes the overall state degraded, but does not by itself prove that Android rejected the FGS.

### Service remains after the last page closes

Look for the corresponding `session_stopped` event and confirm the active token count reached zero. Every tab close, freeze, renderer exit, Activity teardown, total-switch disable, and rule removal must remove its token before destroying the WebView. Then verify Service teardown and WakeLock release independently.

### Page says it was rebuilt after a renderer exit

`onRenderProcessGone` means the old JavaScript heap is gone. The browser can reconstruct a usable WebView at the last trusted URL, but this is a page reload, not business continuity. Use the renderer/recovery audit events to distinguish “usable again” from “uninterrupted”.

`page_recovery_result=usable` is emitted only after the replacement WebView commits a real web URL, with `onPageFinished` as a provider fallback. Main-frame errors, blocked/SSL-failed loads, repeated renderer exits, synchronous rebuild failures, and a bounded recovery timeout finish the attempt as failed.

### Notification Stop appears to undo itself

Verify the event sequence and tab identity. Automatic frozen-tab restoration, renderer recovery, Activity resume, redirect callbacks, and script-created popups must not clear Stop suppression. Re-authorization is limited to a trusted in-app page-open action, an explicit browser navigation gesture/control, or parent authorization. External `ACTION_VIEW` relay intents are not trusted restart authorization.

### WakeLock expires during screen-off; JS timers stop (Handler/Doze issue)

**Symptom**: Diagnostic logs show a long gap (10+ minutes) with no `wake_lock_renewed` events after screen-off, followed by `session_stopped reason=activity_destroyed`. The diagnostic snapshot shows `wakeLock=NOT_HELD`, `fgs=STOPPED`, `sessions=0`, `stale=true reason=heartbeat_stale`.

**Root cause**: All periodic timers (WakeLock renewal, FGS health check, SessionController heartbeat) previously used `Handler.postDelayed()`, which **does not wake the CPU from suspend/Doze**. On aggressive OEM devices (e.g. OnePlus with OxygenOS), the system can force the CPU into suspend even while a `PARTIAL_WAKE_LOCK` is held. Once the CPU sleeps:

1. Handler callbacks stop firing.
2. The WakeLock lease (default 10 min) expires without renewal.
3. The CPU enters full suspend with no way to wake up.
4. The system eventually destroys the Activity → JS execution stops.

This is a **vicious cycle**: WakeLock expires → CPU sleeps → Handler can't fire → WakeLock can't be renewed.

**Fix (v0.4.3)**: WakeLock renewal now uses a dual mechanism:

1. **Handler.postDelayed** (fast path) — fires when the CPU is already awake. Renewal interval: `leaseMs / 3` (~3.3 min for a 10-min lease).
2. **AlarmManager.setAndAllowWhileIdle** (best-effort wake path) — requests an `ELAPSED_REALTIME_WAKEUP` while idle without `SCHEDULE_EXACT_ALARM`. It is inexact and Android/OEM power policy may batch it; it is not a deadline guarantee.

The alarm fires a broadcast to `HighPerformanceAlarmReceiver` (registered in `:webview` process), which:
- Renews the WakeLock via `HighPerformanceWakeLockController.triggerAlarmRenewal()`
- Triggers a full health check via `HighPerformanceSessionController.triggerAlarmHealthCheck()`

The system holds a WakeLock for the duration of the BroadcastReceiver's `onReceive()`, ensuring the CPU stays awake long enough to process the renewal.

**Verification**: Healthy devices should show `wake_lock_renewed reason=alarm_renewal` events near the configured interval. Record actual gaps; a delayed or missing inexact alarm is a degraded platform condition, not proof that arbitrary JS can be guaranteed in the background.

**ADB verification**:

```bash
# Verify the alarm receiver is registered in :webview process
adb shell dumpsys alarm | grep -A2 HighPerformanceAlarmReceiver

# Verify WakeLock remains held during screen-off
adb shell dumpsys power | grep -i HighPerformanceWebSession
```

## ADB verification

Replace the package ID only if the application ID changes.

```bash
adb shell ps -A | grep site.anzz.childkiosk
adb shell dumpsys activity services site.anzz.childkiosk
adb shell dumpsys activity activities | grep -E 'MainActivity|PersistentWebViewActivity|WebViewActivity|taskId|affinity'
adb shell dumpsys power | grep -i HighPerformanceWebSession
adb shell dumpsys notification --noredact | grep -i childkiosk
adb shell dumpsys deviceidle
```

Expected signals:

- `WebViewActivity` and `HighPerformanceForegroundService` use the same `site.anzz.childkiosk:webview` process.
- Normal mode shows Main and `PersistentWebViewActivity` in separate tasks; Device Owner/Lock Task/soft lock shows Main and `WebViewActivity` in the same locked task.
- A partial WakeLock with the stable high-performance tag exists only while the Service has at least one eligible session.
- The ongoing notification appears within the Android foreground-service deadline.
- Removing the last rule/session or disabling the feature starts releasing resources within one second.

For Doze testing on a non-production test device:

```bash
adb shell dumpsys battery unplug
adb shell dumpsys deviceidle force-idle
adb shell dumpsys deviceidle unforce
adb shell dumpsys battery reset
```

Record the Android version, WebView provider/version, device/OEM, notification permission, battery-optimization state, Device Owner state, and test duration with every result.

### FGS and WakeLock stay healthy but a site timer still stops

Treat this as a renderer/page scheduling failure, not proof that the resource shell is healthy enough.
The status model has separate signals:

- `nativeHeartbeatAt`: the WebView process/controller is still scheduling health checks.
- `lastMainJsHeartbeatAt`: the trusted page's injected main-thread timer is still scheduled.
- `lastWorkerJsHeartbeatAt`: a Dedicated Worker is still scheduled.
- `fullSystemProtection`: FGS, WakeLock, renderer priority, notification, and battery setup are ready.

`ACTIVE` requires both complete system protection and a responsive main-thread JS heartbeat. If the
main heartbeat is older than 20 seconds, the session becomes `STALE` and the composite state becomes
`DEGRADED`, even when FGS and WakeLock remain healthy. This distinction prevents resource readiness
from being reported as JavaScript continuity.

### Background freeze after ~60 seconds (Blink Page Lifecycle freeze)

**Symptom**: FGS `RUNNING`, WakeLock `HELD`, battery ignored, renderer alive — but the page's JS
stops ~60 seconds after switching to background or screen-off. The diagnostic stream shows
`visibility_change`, then ~60 s later `page_lifecycle_signal reason=freeze`, then
`js_heartbeat_stale reason=main_timer_missing`; everything resumes on the next foreground
`reason=resume`/`js_heartbeat_responsive`.

**Root cause**: Chromium freezes a hidden page (Page Lifecycle `freeze`) about 60 seconds after the
window becomes invisible. A frozen page suspends every timer, worker, and network task. The
FGS/WakeLock resource shell cannot prevent this; visibility falsification cannot either (v0.4.15,
and it breaks IME), and moving the view to an overlay destroys its Surface (v0.4.13).

**Fix attempts and current state**: `WebView.onResume()` maps to `WebContents.SetFrozen(false)`
in principle, but on Android 16 / WebView 150 neither a single onResume() (v0.4.17) nor an
onResume + view-visibility-toggle combination (v0.4.18, verified by the
`webview_unfreeze_ineffective` events) revived a hidden-frozen page; only the next foreground
transition resumes it. Because the freeze follows the window becoming invisible, the only
remaining lever is to keep the protected WebView in a window that never becomes invisible:

- **v0.4.19 physical overlay keep-alive**: on Activity `onStop` (background or screen-off, not
  finishing) every protected WebView is moved into a 1x1 `TYPE_APPLICATION_OVERLAY` window with
  `FLAG_SHOW_WHEN_LOCKED`. The overlay window stays "visible" to WindowManager even while the
  display is off (keyguard up), so Chromium never receives the hidden transition and the ~60 s
  freeze timer never starts. The screen-off signal is masked by `PersistentWebView` while the view
  is attached (`overlay_screen_state_kept`); foreground callbacks stay 100% native so IME is
  untouched. `onStart` moves the view back and forces re-compositing (`overlay_redraw_forced`)
  because window switching destroys the Surface (the v0.4.13 white-screen failure).
- Overlay requires the "display over other apps" permission; without it the runtime degrades to
  the pre-v0.4.19 resource-shell behavior and records `overlay_permission_missing`.

Verify with the diagnostic events `overlay_attached` (or `overlay_permission_missing` /
`overlay_attach_failed`), absence of `freeze` while backgrounded, `overlay_detached` +
`overlay_redraw_forced` on foreground, and a `RESPONSIVE` heartbeat through the whole background
period. If the heartbeat still goes stale, the device/OEM hides overlay windows while the display
is off or suspends them; then no public WebView API can keep the page foreground-like and the
remaining options are PiP (background only) or accepting freeze with page-side freeze/resume
handling.

The page runtime may provide an Origin-scoped Page Visibility compatibility layer at document start
for parent-approved pages. This layer is separate from Android View state and cannot replace it. It
deliberately does not block `pagehide`, because sites and BFCache use that event for navigation
cleanup. An Android 16/WebView or OEM scheduler can still throttle or freeze Blink below all public
Android APIs; no supported WebView API can guarantee arbitrary third-party JavaScript runs exactly
like the foreground indefinitely.

For a reproducible screen-off report, capture at least 2-5 minutes of the three heartbeats plus page
business timestamps from `docs/test-pages/high_performance_runtime_test.html`. If the injected main
heartbeat continues while only the site's timer stops, inspect the site's own visibility/freeze/
network logic. If both main and Worker heartbeats stop while native heartbeat continues, the renderer
was frozen. If all three stop, inspect process death, WakeLock renewal, Doze, and OEM power controls.

## Required regression scenarios

- Exact Origin, default/explicit port, IDN, subdomain opt-in, and public-suffix rejection.
- Cross-Origin redirects and rapid redirect loops.
- Two matching tabs, closing one, then closing the last.
- Protected background tabs below and above the ordinary WebView memory cap; no protected tab may be selected for automatic freezing.
- Two WebViewActivity owners where one reaches the health limit; ordinary navigation cannot bypass it and parent authorization must not resume the other owner.
- Normal mode HOME/background/restore retains the same WebView and JS sentinel; soft lock and Device Owner still launch the same-task host without a black screen.
- Notification clicks resume the exact live host without starting `MainActivity`; floating Home preserves the normal-mode host and explicitly closes the same-task kiosk host.
- Switching between normal and kiosk protection modes leaves only one live WebView host and restores the checkpointed tabs once.
- FGS start failure and unexpected Service destruction retain active session tokens and renderer priority as degraded; explicit Stop still removes and suppresses them.
- Repeated Alarm health checks leave one Handler heartbeat chain.
- Notification permission allowed, denied, and revoked from Settings.
- Dedicated high-performance notification channel enabled and disabled independently of the app-wide notification switch.
- Battery exemption enabled and disabled.
- Total switch off, rule disable/delete, notification Stop, healthy Activity close, and forced renderer exit.
- Exact and subdomain document-start Origin scopes; malformed/cross-Origin WebMessage input; token
  rotation across same-Origin and cross-Origin navigation; live rule disable/re-enable without restart.
- Main/Worker JS heartbeat transitions from waiting to responsive to stale while native heartbeat and
  system-resource readiness remain independently visible.
- Android 9, 12, 13, and 14; at least one AOSP-like device and two relevant OEM device families.
- Text/password/number/textarea fields summon the IME in normal and child mode, including normal
  -> child -> normal on the same live page and on a protected page.
- A protected page keeps its JS timers and network activity during 5+ minutes of background and
  5+ minutes of screen-off (wake lock held): freeze signals are answered by an immediate unfreeze
  (`webview_unfrozen`), the main heartbeat stays `RESPONSIVE`, and returning to the foreground
  resumes native rendering with working IME input on the same live document (no reload, no white
  screen).
- An unprotected page still receives the real freeze/visibility callbacks and background
  throttling behavior is unchanged.

## Release blockers

- The Service is not in `:webview`.
- Normal-mode WebView shares Main's task, or a Lock Task/soft-lock launch targets the dedicated WebView task.
- Notification/Home navigation starts `MainActivity(singleTask)` above a same-task kiosk WebView, or two WebView host classes remain alive together.
- Android 14 reports a missing or mismatched foreground-service type/permission.
- WakeLock remains held after the final token.
- A subresource, iframe, invalid URL, preload, or public-suffix wildcard can activate a session.
- A stale or malformed snapshot preserves an enabled state.
- A failed AtomicFile/config broadcast is reported as success or leaves an older enabled runtime file usable.
- Notification Stop can be undone by automatic tab/renderer reconstruction or by an untrusted external VIEW intent.
- UI reports fully ready while notification permission or battery readiness is missing.
- Play distribution is planned but the `specialUse` declaration has not been approved and documented.
