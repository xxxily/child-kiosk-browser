# Xiaomi Android 13 background continuity validation

## Scope

This runbook records the 2026-08-04 production validation on Xiaomi `M2105K81C`, Android 13
(SDK 33), Google WebView `150.0.7871.181`, and Child Kiosk Browser enhanced release `0.4.24 (99)`.
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

## Artifact checklist

Export the redacted bundle from the admin diagnostics page before clearing events. On Android 10+
the file is written under `Download/ChildKiosk/`. Preserve a matching logcat capture outside the
repository and record device model, Android SDK, WebView package/version, app version, PID, session
ID, load ID, screen state, keyguard state, and Doze state.
