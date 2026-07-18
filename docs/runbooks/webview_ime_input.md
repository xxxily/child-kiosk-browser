# WebView input method troubleshooting

## Symptoms

- A webpage input opens the system keyboard normally before switching modes.
- After switching from normal mode to child mode and back, tapping the same input no longer opens
  the keyboard.
- `限制输入法调起` still shows disabled.

## Root cause

The preference value and the effective runtime behavior travel through different paths.

`MainActivity` writes mode preferences in the main process, while a surviving `WebViewActivity`
runs in `:webview`. SharedPreferences are not a reliable live synchronization mechanism across
those processes. Before this fix, the existing WebView host could retain the child-mode system-bar
snapshot even though the main-process setting correctly reported `limitImeInput=false`.

There was also an IME/system-bar ordering race. `WebViewActivity.onWindowFocusChanged()` called
`hide(systemBars)` whenever the Activity regained focus. Its old visibility check only worked on
Android 11+, so on Android 9/10 it could reset immersive system bars while the IME was starting.
That affected every mode and dismissed the keyboard without the input-limit preference ever being
enabled.

## Fix strategy

- Publish `limitImeInput` and the effective system-bar mode explicitly to the live `:webview`
  Activity whenever sandbox or quick-mode settings change.
- Track IME visibility through `WindowInsetsCompat`, including older Android versions.
- Remove the focus-driven system-bar reset; use the root `WindowInsetsCompat` listener instead.
- Cancel system-bar recovery while IME insets are visible and re-check visibility when the delayed
  recovery actually runs.
- Restart each managed `PersistentWebView` input connection when an input restriction is removed.

## Verification

1. Keep `限制输入法调起` disabled.
2. Open a page with text, password, number, and textarea inputs and verify typing works.
3. Switch normal -> child -> normal without killing the app or `:webview` process.
4. Return to the existing page and repeat input in portrait and landscape.
5. Confirm logcat contains `Live IME policy applied: limited=false` after each mode switch.
6. Enable the restriction and confirm webpage inputs do not summon the keyboard; disable it and
   confirm the same live page accepts input again.

Useful log filter:

```bash
adb logcat -v time | grep -E 'Live IME policy|ChildKioskWebView|InputMethodManager'
```
