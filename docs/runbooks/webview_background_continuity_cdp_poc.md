# WebView background-continuity CDP PoC runbook

This runbook reproduces the isolated `continuity-poc` experiments. It does not enable the mechanism
in the production `app` module; v0.4.23 production integration is documented separately in
[`experimental_cdp_continuity.md`](experimental_cdp_continuity.md).

## What the PoC measures

The release APK is intentionally `debuggable=false` and hosts a real `FrameLayout + WebView` in its
own `:webview` process. It records:

- native foreground-service heartbeat plus partial WakeLock/Wi-Fi lock state;
- one-second main-thread timer and Dedicated Worker counters;
- a 15-second `fetch` probe;
- page lifecycle events, a per-load ID, and a persistent load count;
- optional same-UID CDP `frozen → active` execution after `Activity.onStop()`.

The package is `site.anzz.childkiosk.continuitypoc`. JSONL is written to:

```text
/sdcard/Android/data/site.anzz.childkiosk.continuitypoc/files/continuity_probe.jsonl
```

## Build and install

Use JDK 17 and a dedicated test device:

```bash
./gradlew :continuity-poc:assembleRelease
adb -s "$ADB_SERIAL" install -r continuity-poc/build/outputs/apk/release/continuity-poc-release.apk
```

The device test temporarily needs enough screen-off observation time and should not battery-optimize
the PoC. Record existing values before changing them:

```bash
adb -s "$ADB_SERIAL" shell settings get system screen_off_timeout
adb -s "$ADB_SERIAL" shell dumpsys deviceidle whitelist
adb -s "$ADB_SERIAL" shell settings put system screen_off_timeout 600000
adb -s "$ADB_SERIAL" shell dumpsys deviceidle whitelist +site.anzz.childkiosk.continuitypoc
```

## Baseline: automatic freeze

Start without WebView debugging, then hide the Activity or turn the screen off:

```bash
adb -s "$ADB_SERIAL" shell am force-stop site.anzz.childkiosk.continuitypoc
adb -s "$ADB_SERIAL" shell am start \
  -n site.anzz.childkiosk.continuitypoc/.ContinuityProbeActivity \
  --es session A-no-debug \
  --ez debugging false \
  --ez automatic_cdp_edge false
```

Expected on M150 Android WebView: native heartbeat and locks continue, but a page that becomes
`document.hidden === true` reports `freeze` after about 60 seconds; main, Worker, and fetch all stop
until a real foreground transition. A pure power-button screen-off is not interchangeable with this
case: on the tested Android 10 device without secure lock, the Activity stopped while the window and
document remained visible.

## Same-UID in-app CDP edge

Start with debugging and the automatic edge enabled, wait until the page has loaded, then hide it:

```bash
adb -s "$ADB_SERIAL" shell am force-stop site.anzz.childkiosk.continuitypoc
adb -s "$ADB_SERIAL" shell am start \
  -n site.anzz.childkiosk.continuitypoc/.ContinuityProbeActivity \
  --es session G-in-app-cdp \
  --ez debugging true \
  --ez automatic_cdp_edge true
```

`onStop + 1s` schedules an in-process connection to
`localabstract:webview_devtools_remote_<pid>`. The controller then polls
`document.hidden === true` for at most three seconds. A naturally hidden page requires this sequence
in the JSONL:

```text
in_app_cdp_edge_scheduled
in_app_cdp_edge_started
freeze
resume
in_app_cdp_edge_succeeded
```

If the page is still naturally visible, the required result is instead:

```text
in_app_cdp_edge_started
in_app_cdp_edge_skipped reason=page_still_visible
```

Never send `frozen` merely because `Activity.onStop()` ran. CDP `frozen` internally calls
`WasHidden()`; sending it to a still-visible page can leave `document.visibilityState` stuck at
`hidden` after wake. On the tested device, `active`, `Page.bringToFront`, and bringing the same task
forward did not repair that poisoned visibility state.

No ADB forward should be required:

```bash
adb -s "$ADB_SERIAL" forward --list
```

## Temporary debugging exposure

This variant keeps WebView debugging disabled while the page is foregrounded. After `onStop()`, it
briefly enables the same-UID DevTools endpoint, sends the lifecycle edge, then disables debugging on
the main thread and verifies that the local endpoint no longer accepts connections:

```bash
adb -s "$ADB_SERIAL" shell am force-stop site.anzz.childkiosk.continuitypoc
adb -s "$ADB_SERIAL" shell am start \
  -n site.anzz.childkiosk.continuitypoc/.ContinuityProbeActivity \
  --es session I-temporary-in-app-cdp \
  --ez debugging false \
  --ez automatic_cdp_edge true \
  --ez temporary_cdp_debugging true
```

The required log sequence is:

```text
temporary_webview_debugging_enabled
in_app_cdp_edge_scheduled
in_app_cdp_edge_started
freeze
resume
in_app_cdp_edge_succeeded
temporary_webview_debugging_disabled
temporary_webview_debug_socket_closed
```

Also confirm through `/proc/net/unix` that `webview_devtools_remote_<pid>` is absent before
backgrounding, appears only during the edge, and disappears after the close event. Continue the
screen-off observation for at least seven minutes to cover both the automatic-freeze window and the
later hidden-page throttling window. A closed endpoint limits exposure but does not make this a
general security boundary: while the endpoint is briefly open, an authorized ADB shell can still
inspect or mutate the page.

## Export and analyze

Keep raw device results under the ignored `continuity-poc/build/test-results/` directory:

```bash
adb -s "$ADB_SERIAL" exec-out cat \
  /sdcard/Android/data/site.anzz.childkiosk.continuitypoc/files/continuity_probe.jsonl \
  > continuity-poc/build/test-results/result.jsonl
python3 scripts/analyze_continuity_log.py continuity-poc/build/test-results/result.jsonl
shasum -a 256 continuity-poc/build/test-results/result.jsonl
```

Interpret the signals separately:

- no post-resume `freeze`, same PID/load ID: full page freeze was prevented without reload;
- Worker continues near one second: worker scheduling still progresses on the tested device;
- main counter/fetch progresses only about once per minute: hidden-page intensive throttling remains;
- native heartbeat only: process survival does not prove page execution.

Do not call the result “foreground-level continuity” unless main-thread and business scheduling meet
the actual latency requirement. A 7.45-hour run proved that the same page/renderer survived, but the
PoC WakeLock expired after two hours and subsequent CPU-suspend gaps prevented it from proving 7.45
hours of uninterrupted JavaScript.

## Foreground and IME check

If the device uses a secure PIN, wake it through ADB but unlock it manually; never store or automate
the credential. After unlocking, keep the same PoC Activity visible and verify:

1. the PID and page `loadId` did not change;
2. no new `page_started`/`page_finished` occurred;
3. the input field summons the IME and accepts text;
4. focus, navigation, and rendering remain usable.

## Cleanup

Restore the exact timeout recorded before the test, remove the temporary whitelist, export the final
log, and then stop/uninstall the isolated PoC:

```bash
adb -s "$ADB_SERIAL" shell settings put system screen_off_timeout "$ORIGINAL_TIMEOUT_MS"
adb -s "$ADB_SERIAL" shell dumpsys deviceidle whitelist -site.anzz.childkiosk.continuitypoc
adb -s "$ADB_SERIAL" shell am force-stop site.anzz.childkiosk.continuitypoc
adb -s "$ADB_SERIAL" uninstall site.anzz.childkiosk.continuitypoc
adb -s "$ADB_SERIAL" forward --remove-all
```

Switching `adbd` back from TCP to USB disconnects wireless ADB and must be the final device step.

## Current measured result

On OnePlus 6 / Android 10 / Google WebView `150.0.7871.181`:

- baseline, debugging-only, and connected-without-command all froze at about 60 seconds;
- `active` alone did not revive an automatically frozen page;
- `frozen → active` revived it and a pre-freeze edge cancelled the pending automatic freeze;
- same-UID in-app control completed in 578 ms without ADB forwarding;
- G2 kept the same PID/load ID and a single page load for 7.45 hours with no renderer loss or reload;
- the G2 WakeLock intentionally expired after two hours, after which CPU-suspend gaps occurred, so
  the long run proves page survival rather than uninterrupted JavaScript;
- after about five minutes, main timer/fetch degraded to roughly one-minute cadence while the Worker
  remained near one-second cadence while the CPU was awake;
- foreground restoration and IME passed on the same WebView; entered text survived another
  screen-off cycle longer than 100 seconds;
- with secure lock removed, a pure screen-off left the page naturally visible; the visibility gate
  skipped CDP and a 160.6-second H1 cycle had no freeze or reload;
- a true-background H2 cycle naturally became hidden, completed the edge in 565 ms, stayed hidden
  for more than 95 seconds without a second freeze, and returned visible with the same PID/load ID;
- an unlocked/no-secure-keyguard H3 power-button screen-off lasted about 19 minutes 25 seconds while
  the system was non-interactive; the document stayed visible, CDP was skipped, main/Worker retained
  one-second cadence, fetch retained 15-second cadence, and no freeze or reload occurred;
- H4 disabled WebView debugging and automatic CDP entirely, entered screen-off/Dozing for more than
  six minutes, and reproduced the same visible/one-second/15-second behavior. The no-Keyguard
  screen-off result therefore does not depend on DevTools.

Android 16 / WebView 150 production integration is now represented by the opt-in v0.4.23 path in
the main app. The isolated PoC remains the preferred first-line diagnostic when validating a new
WebView/OEM because it makes socket lifetime and the lifecycle edge independently observable.
A successful PoC run does not certify the production path: repeat target matching, navigation,
renderer-rebuild, renewable-WakeLock long-duration execution, power/thermal, and broader OEM
security closure on the actual release APK.
