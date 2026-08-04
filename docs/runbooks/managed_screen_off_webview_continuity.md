# Managed screen-off WebView continuity runbook

## Scope

This runbook verifies the production path introduced in v0.4.22. It applies only to managed devices
where the secure Keyguard has been removed or disabled. It does not use WebView debugging or CDP.

The app never promises this behavior on every Android/OEM combination. It measures the target
device and reports degradation when Chromium makes the document hidden or stops the main heartbeat.

Current phase conclusion: OnePlus 6 / Android 10 demonstrated this natural-visible exception, but
Xiaomi Android 13 and OnePlus Android 16 did not establish it as a general no-Keyguard behavior.
See [background_continuity_phase_summary_2026-08-04.md](../background_continuity_phase_summary_2026-08-04.md).

## Required conditions

- Use the production `WebViewActivity -> FrameLayout -> WebView` host.
- Enable high-performance mode for the exact trusted Origin.
- Ensure notifications are available, battery optimization is ignored, FGS is `RUNNING`, and the
  partial WakeLock is `HELD` before screen-off.
- Remove PIN/pattern/password. If Device Owner is available, enable “禁用系统锁屏键盘锁
  (Keyguard)” and confirm diagnostics show Keyguard not showing and not secure.
- Keep Chrome Inspect disabled. Do not connect CDP during the production capability test.

## Expected state model

```text
screen off + Activity STOPPED
        |
        +-- document.hidden=false + main heartbeat responsive
        |       -> SCREEN_OFF_VISIBLE_CONTINUITY
        |
        +-- document.hidden=true
        |       +-- Worker evidence remains recent
        |       |       -> HIDDEN_LOW_FREQUENCY_CONTINUITY / BACKGROUND_THROTTLED
        |       +-- no bounded Worker evidence
        |               -> HIDDEN_DEGRADED or STALE
        |
        +-- main heartbeat stale
                -> STALE
```

`Activity.onStop()` is observation scheduling only. It is not evidence that the page is hidden.

## Verification

1. Open a trusted test page and record:
   - `:webview` PID;
   - page load ID;
   - main and Worker heartbeat timestamps;
   - renderer, FGS, WakeLock, battery and Keyguard fields.
2. Press the physical power button. Confirm the system becomes non-interactive/Dozing.
3. Sample the runtime status for at least five minutes. A passing device keeps:
   - one PID and one load ID;
   - `documentHidden=false` and `documentVisibilityState=visible`;
   - `continuityState=SCREEN_OFF_VISIBLE_CONTINUITY`;
   - advancing main and Worker heartbeats;
   - no `freeze`, renderer loss, page load ID change or recovery reload.
4. Wake the same Activity and verify the same load ID, rendering, navigation and IME input.

## Failure interpretation

- `HIDDEN_LOW_FREQUENCY_CONTINUITY`: the OEM/WebView does not support the natural-visible path, but
  bounded Worker evidence still proves low-frequency scheduling. This is not foreground-equivalent
  execution.
- `HIDDEN_DEGRADED`: the OEM/WebView does not support the natural-visible path and there is not
  enough Worker evidence to claim low-frequency continuity. Do not reintroduce visibility
  overrides, View visibility toggles, overlay moves or automatic CDP.
- `STALE` with native heartbeat alive: Blink scheduling froze or throttled the page.
- PID/load ID changed: process/renderer/page continuity failed even if the UI recovered.
- Keyguard showing/secure: remove the credential or correct Device Owner policy before retesting.

## Android 10 reference result

OnePlus 6 / Android 10 / Google WebView `150.0.7871.181` passed a seven-minute sample using a
debuggable diagnostic build of the production app module, signed with the release certificate. It
kept the same PID and load ID, a real visible document, healthy FGS/WakeLock state, and advancing
main/Worker heartbeats without a CDP connection. The isolated H4 PoC separately reproduced the
no-Keyguard path with a true non-debug release APK and debugging/CDP disabled. Later Android 13
Xiaomi and Android 16 OnePlus samples entered real hidden state instead; treat the Android 10 result
as a target-device capability exception, not an expected outcome after removing credentials.
