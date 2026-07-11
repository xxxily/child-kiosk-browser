# High-performance web runtime runbook

## Purpose and product boundary

High-performance runtime protects only parent-approved top-level HTTP/HTTPS Origins. It combines a foreground service in `:webview`, a bounded partial CPU WakeLock, and a non-waived renderer-priority request. It improves survival probability; it does not guarantee uninterrupted JavaScript, timers, network sockets, or renderer memory.

## Architecture signals

- Room in the main process is the source of truth for the global switch and Origin rules.
- A versioned AtomicFile is the cross-process runtime source. The main process writes it after a successful Room transaction and broadcasts only that a newer version exists.
- If AtomicFile publication or the config-update signal fails after Room commits, a disabled snapshot at the new config version becomes the disk/cache source of truth and `publication_failed` stops existing sessions. The admin UI must report the synchronization failure and allow retry; it must not claim the change was fully applied.
- `WebViewActivity` reevaluates only managed top-level WebViews. Preload/blank WebViews and subresources cannot create sessions.
- `HighPerformanceForegroundService` and `WebViewActivity` must both report the package process suffix `:webview`.
- The Service owns the only partial WakeLock. Page/session code never acquires a WakeLock directly.
- Runtime status is written by `:webview` and is considered stale after its documented freshness window.
- Notification Stop suppression is tracked per logical tab across frozen-tab and renderer reconstruction. A confirmed user action may re-authorize only that tab; it must not silently re-enable other stopped tabs or script-created popups.
- A health-time-limit latch is owner-scoped. Ordinary navigation cannot clear it; only successful parent authorization may resume that Activity, and it must not resume another Activity or override a concurrent global Stop.
- The ordinary background-WebView cap may freeze ordinary tabs only. Protected tabs stay alive even when retaining them temporarily exceeds the normal cap; the runtime records one degraded warning until the owner returns within the cap.

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

## ADB verification

Replace the package ID only if the application ID changes.

```bash
adb shell ps -A | grep site.anzz.childkiosk
adb shell dumpsys activity services site.anzz.childkiosk
adb shell dumpsys power | grep -i HighPerformanceWebSession
adb shell dumpsys notification --noredact | grep -i childkiosk
adb shell dumpsys deviceidle
```

Expected signals:

- `WebViewActivity` and `HighPerformanceForegroundService` use the same `site.anzz.childkiosk:webview` process.
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

## Required regression scenarios

- Exact Origin, default/explicit port, IDN, subdomain opt-in, and public-suffix rejection.
- Cross-Origin redirects and rapid redirect loops.
- Two matching tabs, closing one, then closing the last.
- Protected background tabs below and above the ordinary WebView memory cap; no protected tab may be selected for automatic freezing.
- Two WebViewActivity owners where one reaches the health limit; ordinary navigation cannot bypass it and parent authorization must not resume the other owner.
- Notification permission allowed, denied, and revoked from Settings.
- Dedicated high-performance notification channel enabled and disabled independently of the app-wide notification switch.
- Battery exemption enabled and disabled.
- Total switch off, rule disable/delete, notification Stop, healthy Activity close, and forced renderer exit.
- Android 9, 12, 13, and 14; at least one AOSP-like device and two relevant OEM device families.

## Release blockers

- The Service is not in `:webview`.
- Android 14 reports a missing or mismatched foreground-service type/permission.
- WakeLock remains held after the final token.
- A subresource, iframe, invalid URL, preload, or public-suffix wildcard can activate a session.
- A stale or malformed snapshot preserves an enabled state.
- A failed AtomicFile/config broadcast is reported as success or leaves an older enabled runtime file usable.
- Notification Stop can be undone by automatic tab/renderer reconstruction or by an untrusted external VIEW intent.
- UI reports fully ready while notification permission or battery readiness is missing.
- Play distribution is planned but the `specialUse` declaration has not been approved and documented.
