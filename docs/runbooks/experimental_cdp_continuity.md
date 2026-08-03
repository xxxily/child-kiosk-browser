# Experimental CDP low-frequency continuity runbook

## Scope and warning

This runbook covers the opt-in v0.4.23 production experiment for parent-approved trusted Origins.
It is not a promise that arbitrary WebView JavaScript runs like the foreground. The mechanism uses
the non-public WebView DevTools endpoint for a short same-UID lease. During that lease an authorized
ADB shell may inspect or mutate the page. Keep the switch off unless the device, Origin, and risk
have been explicitly approved.

The feature is intentionally disabled by default, requires high-performance mode, and is cleared
automatically when high-performance mode is turned off. It must never be enabled for an untrusted
or broad Origin set.

## Enablement

1. Open the high-performance detail page.
2. Enable high-performance mode and complete its existing risk acknowledgement.
3. Add the exact trusted HTTPS Origin (for example `https://map.anzz.site`).
4. Enable “实验性低频续行” and complete its separate warning confirmation.
5. Keep Chrome Inspect disabled unless a parent explicitly wants persistent inspection. If it is
   already enabled, the continuity lease must preserve it after the edge.

## Expected lifecycle

After the host Activity stops, the controller waits briefly and then emits a sequence similar to:

```text
experimental_cdp_edge_scheduled
experimental_cdp_debugging_enabled
experimental_cdp_edge_started
page_lifecycle_signal reason=freeze
page_lifecycle_signal reason=resume
experimental_cdp_edge_succeeded
experimental_cdp_debugging_restored
experimental_cdp_debug_socket_closed
```

The debugging-enabled/socket-closed events appear only for a temporary lease when Chrome Inspect
was off. The sequence may instead contain `experimental_cdp_edge_skipped` when the page remains
naturally visible or the session/target changes. `experimental_cdp_edge_failed` means the app has
returned to the ordinary degraded/foreground-restore boundary; it is not permission to retry
aggressively.

## Acceptance signals

- same WebView process PID, session ID, and page `loadId` before and after the edge;
- no renderer loss, reload, or new top-level navigation;
- temporary DevTools socket absent before the lease and closed after restoration;
- no `experimental_cdp_debugging_restore_failed` event;
- foreground recovery and IME input still work;
- after seven minutes of screen-off/background observation, interpret main timer/fetch cadence
  separately from Worker cadence. “No second freeze” is not “foreground-level continuity”.

## Troubleshooting

| Signal | Meaning / action |
|---|---|
| `edge_skipped/no_visible_protected_page` | The candidate was not an eligible current trusted page; inspect Origin rule, tab visibility, and heartbeat token. |
| `edge_skipped/page_still_visible` | Correct safety behavior; no CDP lifecycle command was sent. |
| `edge_skipped/session_changed` | Navigation or renderer replacement raced the lease; wait for the next real committed page. |
| `debugging_restore_failed` | Stop the experiment, capture diagnostics, and verify the main thread and WebView provider before retrying. |
| `debug_socket_open` | The temporary endpoint did not close within the bounded timeout; disable the feature and treat it as a security failure. |
| `HIDDEN_DEGRADED` / `STALE` after success | The edge avoided a full freeze but the platform still throttled or stopped page work; retain foreground restore expectations. |

## Kill switch and cleanup

Turn off the experimental switch first, then turn off high-performance mode if the session must stop.
The next config publication cancels delayed/active leases and restores the persistent Chrome Inspect
preference. If a process is being force-stopped during an incident, reopen the app and inspect the
diagnostics before enabling the feature again.
