# Xiaomi Android 13 background continuity validation

## Scope

This runbook records the 2026-08-04 production validation on Xiaomi `M2105K81C`, Android 13
(SDK 33), Google WebView `150.0.7871.181`, and Child Kiosk Browser enhanced releases
`0.4.24 (99)` through `0.4.26 (101)`.
The tested trusted Origin was `https://map.anzz.site` with high-performance mode, experimental CDP
continuity, balanced timing, verbose diagnostics, notification permission, and battery exemption
enabled.

This is a device capability result. It does not turn hidden WebView JavaScript into foreground-level
execution and must not be generalized to every Xiaomi/MIUI release.

## Verified signals

- WebView process PID remained `27273` throughout the valid test session.
- Session ID remained `fc277ee1-c301-44e1-9ea1-65b81d464143`.
- Page load ID remained `1785802523511`; no new top-level page start/finish or renderer loss was
  observed.
- HOME background and power-button screen-off both changed the real document to `hidden=true`.
- The main-thread heartbeat settled near one execution per 60 seconds while the Dedicated Worker
  continued near the 5-second diagnostic interval.
- Runtime state correctly became `LOW_FREQUENCY_RESPONSIVE` /
  `HIDDEN_LOW_FREQUENCY_CONTINUITY`, with FGS running, partial WakeLock held, renderer priority not
  waived, notifications visible, and battery optimization ignored.
- Each balanced CDP edge completed in about 0.53-0.55 seconds. Diagnostics recorded
  `experimental_cdp_edge_succeeded`, `experimental_cdp_debugging_restored`, and
  `experimental_cdp_debug_socket_closed`; no DevTools socket remained open.
- Returning through the FGS notification preserved PID, session, load ID, and page state without a
  reload.

## Doze result

After simulating battery unplug and sending `KEYCODE_SLEEP`, MIUI accepted forced Light Doze and
reported `IDLE`. During about three minutes of Light Doze, PID/session/load ID remained stable,
Worker samples continued, and the main-thread timer retained its hidden-page low-frequency cadence.

MIUI rejected AOSP-style forced Deep Doze:

```text
Unable to go deep idle; stopped at INACTIVE
```

`force-inactive` followed by repeated `step deep` also remained at `INACTIVE`. Record this as a ROM
test limitation, not an application continuity failure. Always restore the device after a test:

```bash
adb shell dumpsys deviceidle unforce
adb shell dumpsys battery reset
adb shell input keyevent 224
```

## Hidden pre-existing tab finding

The diagnostic screen showed two protected sessions for the same Origin. The valid visible tab had
normal page evidence. An older `View.GONE` tab was matched when the rule was added dynamically, but
never produced a heartbeat, visibility sample, or load ID. It was therefore reported as `STALE` and
incorrectly downgraded the composite state even though the active tab was healthy.

The runtime fix is:

- keep a hidden, never-observed tab in pending-first-heartbeat state;
- exclude only that unobserved pending tab from composite health;
- rotate its token and retry current-document bootstrap when it becomes visible;
- continue treating any later loss after real page evidence as degraded;
- normalize HTTP(S) root URL identity so `https://host` and `https://host/` do not create duplicate
  external-entry tabs, while meaningful path/query/fragment differences remain distinct.

Expected diagnostics are `js_heartbeat_hidden_activation_pending` followed by
`js_heartbeat_activation_retried` when the tab is selected.

## Existing-host native crash on repeated external open

Enhanced release `0.4.25 (100)` consistently reproduced a WebView native crash when an external
`ACTION_VIEW` resumed an existing background `PersistentWebViewActivity` for the same tab. Both the
exact URL and the normalized root-URL variant reproduced it:

```text
signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0x18
Cause: null pointer dereference
libwebviewchromium.so #00 pc 0x3abab8c
BuildId: a3a1ae52ee233d93d2a14e4190f6d8d4057f132a
```

The stable pre-crash sequence was:

1. the existing page remained alive in the background with the same session and load ID;
2. `activity_new_intent` was recorded;
3. WebView/MIUI registered configuration listeners while the existing host was being brought back;
4. the process crashed about 300 ms later in `libwebviewchromium.so`.

The application-side trigger was a redundant equal-version runtime snapshot application.
`HighPerformanceSessionController.applySnapshot()` unconditionally removed and recreated the
document-start script and WebMessage listener for every managed WebView, then bootstrapped the
already-loaded document. Reconfiguring those WebView-owned native objects while MIUI was
reattaching the background Activity window exposed the WebView 150 null dereference.

The runtime fix is deliberately narrow:

- ignore an equal-version snapshot when its effective configuration is unchanged, including a
  harmless new `generatedAt` value;
- reject an equal-version snapshot whose configuration content differs, because the main-process
  writer contract increments `configVersion` for every real mutation;
- apply newer CDP timing, verbose-diagnostic, WakeLock lease, and other controller-only changes
  without touching the page ScriptHandler or WebMessage listener;
- reinstall the page runtime only when the enabled Origin scope changes;
- after an ignored or rejected snapshot, keep `WebViewActivity` aligned with the controller's
  accepted snapshot instead of adopting the rejected launch copy.

Regression procedure:

1. clear the crash log buffer and open `https://map.anzz.site`;
2. record WebView PID, session ID, and load ID, then press HOME;
3. send external `ACTION_VIEW` for both `https://map.anzz.site` and
   `https://map.anzz.site/` several times;
4. verify the same PID/session/load ID, one logical tab/session, an unchanged page-runtime
   configuration, and no new `data_app_native_crash` entry;
5. repeat a short HOME and screen-off sample and verify the bounded CDP lease still restores
   debugging state and closes the DevTools socket.

Useful evidence commands:

```bash
adb logcat -b crash -c
adb logcat -b crash -d -v threadtime
adb shell dumpsys dropbox --print data_app_native_crash
```

## Artifact checklist

Export the redacted bundle from the admin diagnostics page before clearing events. On Android 10+
the file is written under `Download/ChildKiosk/`. Preserve a matching logcat capture outside the
repository and record device model, Android SDK, WebView package/version, app version, PID, session
ID, load ID, screen state, keyguard state, and Doze state.
