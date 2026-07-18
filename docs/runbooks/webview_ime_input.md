# WebView input method troubleshooting

## Expected behavior

Webpage text, password, number, search, and textarea fields must use Android WebView's native
input connection and summon the system IME in normal, child, and debug modes. The app has no setting
or runtime policy that can prevent a webpage from opening the IME.

## Root cause and fix

The prior custom WebView could override `onCheckIsTextEditor()` to deny Chromium an input
connection. Its setting, runtime-snapshot field, and cross-process broadcast made input behavior
depend on an unnecessary policy path. This was removed entirely: real pages now use the platform
`WebView` directly, so Chromium owns focus and IME negotiation.

`WebViewActivity` still tracks IME visibility with `WindowInsetsCompat`. While an IME is visible, it
defers immersive system-bar recovery so a system-bar transition cannot dismiss a keyboard that is
opening. Only the system-bar mode is synchronized to the isolated `:webview` process.

## Verification

1. Open a page with text, password, number, search, and textarea inputs and verify typing works.
2. Switch normal -> child -> normal without killing the app or `:webview` process.
3. Return to the same page and repeat the inputs in portrait and landscape.
4. Repeat with a trusted high-performance page. No View lifecycle spoofing or input override may be
   introduced to keep that page alive.

Useful log filter:

```bash
adb logcat -v time | grep -E 'ChildKioskWebView|InputMethodManager|chromium'
```
