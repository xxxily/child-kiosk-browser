# WebView input method troubleshooting

## Symptoms

- A webpage input opens the system keyboard normally before switching modes.
- After switching from normal mode to child mode and back, tapping the same input no longer opens
  the keyboard.
- `限制输入法调起` still shows disabled.

## Root cause

The preference value and the effective runtime behavior travel through different paths, but the
preference was not the primary cause of the all-mode regression.

`MainActivity` writes mode preferences in the main process, while a surviving `WebViewActivity`
runs in `:webview`. SharedPreferences are not a reliable live synchronization mechanism across
those processes. An existing WebView host could therefore retain the child-mode system-bar snapshot
even though the main-process setting correctly reported `limitImeInput=false`.

The all-mode regression introduced after v0.3.8 had a separate, more fundamental cause:
`PersistentWebView` altered Android's real visibility, screen-state, and pause callbacks whenever a
high-performance session was active. It also reported `VISIBLE`/`isShown=true` while the actual
view or window was hidden. Chromium uses this native lifecycle and focus contract while creating and
maintaining the WebView input connection, so the deception could prevent webpage fields from
summoning the IME even while `limitImeInput=false`. The opt-in setting only changes
`onCheckIsTextEditor()`; it never accounted for those unrelated lifecycle overrides.

There was also an IME/system-bar ordering race. `WebViewActivity.onWindowFocusChanged()` previously
called `hide(systemBars)` whenever the Activity regained focus. Its old visibility check only worked
on Android 11+, so on Android 9/10 it could reset immersive system bars while the IME was starting.
That race could dismiss the keyboard without the input-limit preference ever being enabled, but it
was not sufficient to explain the remaining all-mode failure.

## Fix strategy

- Publish `limitImeInput` and the effective system-bar mode explicitly to the live `:webview`
  Activity whenever sandbox or quick-mode settings change.
- Track IME visibility through `WindowInsetsCompat`, including older Android versions.
- Remove the focus-driven system-bar reset; use the root `WindowInsetsCompat` listener instead.
- Cancel system-bar recovery while IME insets are visible and re-check visibility when the delayed
  recovery actually runs.
- Restart each managed `PersistentWebView` input connection when an input restriction is removed.
- Keep `PersistentWebView` limited to the explicit IME policy. It must not override Android View
  visibility, screen state, `isShown()`, or `onPause()` for high-performance sessions.
- Keep high-performance FGS, WakeLock, and renderer-priority management independent of View
  lifecycle spoofing. Those resources may protect process/renderer availability, but they must not
  claim Chromium remains foreground-visible.

## Verification

1. Keep `限制输入法调起` disabled.
2. Open a page with text, password, number, and textarea inputs and verify typing works.
3. Switch normal -> child -> normal without killing the app or `:webview` process.
4. Return to the existing page and repeat input in portrait and landscape.
5. Confirm logcat contains `Live IME policy applied: limited=false` after each mode switch.
6. Enable the restriction and confirm webpage inputs do not summon the keyboard; disable it and
   confirm the same live page accepts input again.
7. Repeat the same sequence for a trusted high-performance page and verify that no
   `visibility_deceived`, `screen_state_deceived`, or `webview_pause_blocked` event is emitted.

Useful log filter:

```bash
adb logcat -v time | grep -E 'Live IME policy|ChildKioskWebView|InputMethodManager'
```
