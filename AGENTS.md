# Child Kiosk Browser Agent Rules

These rules apply to the whole repository.

## Project Context

- This is an Android/Kotlin child kiosk browser using Compose for app/admin UI, Room for config data, and a native `FrameLayout + WebView` path for real web content.
- `WebViewActivity` runs in the isolated `:webview` process. Do not assume long-lived WebView process reads of `SharedPreferences` are fresh after settings are changed in the main process.
- Real web pages must continue to use `WebViewActivity -> FrameLayout -> WebView`. Do not reintroduce Compose `AndroidView` as the production host for real webpage rendering unless explicitly requested and verified on device.
- Keep WebView browser-parity defaults unless the user is intentionally tightening the child-safety sandbox. Avoid broad behavior changes that break normal sites.

## Build And Commands

- Do not commit private machine paths, local shell wrappers, personal tool aliases, credentials, or workstation-specific setup notes into this file.
- Use JDK 17 for Gradle builds, matching the project README and CI workflow.
- Minimum verification for Kotlin/UI changes is `:app:compileDebugKotlin`. For behavior or release-sensitive changes, run `:app:assembleDebug`.
- Before a release, run `:app:assembleDebug :app:assembleRelease`, update `app/build.gradle.kts`, `docs/CHANGELOG.md`, and `docs/RELEASE_NOTES.md`, create a `v*` tag, push `main` and the tag, then verify the GitHub Release and APK assets.

## UI And Layout Rules

- Every UI change must be checked mentally against both portrait and landscape, phone and tablet, narrow width and short height.
- If content can exceed available height, especially in fixed landscape, wrap it in vertical scrolling or otherwise ensure every control remains reachable. This is mandatory for dialogs, verification screens, admin panels, forms, and bottom-heavy action areas.
- If content can exceed available width, especially in portrait, wrap/stack it responsively or provide horizontal scrolling where wrapping is not appropriate. Do not let text, buttons, radio groups, or form rows clip offscreen.
- Verification and unlock flows must not depend on the system soft keyboard for critical input. Prefer in-app keypads or controls that remain visible in fixed landscape and kiosk mode.
- Dialogs that contain dynamic content must have safe width/height constraints and scroll behavior. Confirm/Cancel/destructive actions must remain reachable on small screens.
- Keep touch targets large enough for children and kiosk operation. Use at least 72dp touch targets for primary kiosk controls where practical, matching the project requirement.
- Avoid overlapping text, clipped labels, or layout shifts caused by long Chinese text. Prefer wrapping, smaller local text, or responsive stacking over fixed-width rows.

## Code Organization And Modularization

- Do not grow `AdminConsoleScreen.kt` or any other screen into a catch-all file. New settings pages, complex cards, dialogs, diagnostic views, and reusable controls must be split into focused files under the same package or a clearly named subpackage.
- Keep screen files responsible for state orchestration and navigation. Move feature-specific UI into dedicated Composables, move reusable controls into small component files, and move platform/helpers such as signing identity or formatters into non-UI utility files.
- When adding an admin option with more than a simple row and switch, create or reuse a feature screen/card component instead of embedding the whole implementation directly in the parent screen.
- Avoid creating a new giant file while extracting code. A feature with multiple responsibilities should be split by role, for example: screen orchestration, detail dialog, repeated controls, data/diagnostic helpers.
- Prefer package-private (`internal`/`private`) APIs by default. Expose only the Composables or helpers that are actually reused across files.
- Keep state ownership explicit. Parent screens should pass callbacks and stable data down; child components should not silently mutate unrelated global runtime state unless that is their documented responsibility.
- Shared formatting, diagnostics rows, chip controls, and identity readers should live in reusable modules instead of being copied into individual screens.
- Before finishing substantial UI work, review the changed file sizes and responsibilities. If a file now mixes navigation, persistence, platform calls, dialogs, and repeated controls, split it before committing.

## Debugging Knowledge Capture

- When a bug takes multiple investigation cycles, involves platform behavior, SDK integration, release signing, cross-process state, or non-obvious runtime ordering, add or update a focused runbook under `docs/runbooks/`.
- Runbooks should capture symptoms, root cause, verification signals, fix strategy, and future troubleshooting steps. Keep them operational and specific enough for the next agent to avoid repeating the same investigation.
- Do not bury incident notes in broad requirements documents. Use a dedicated runbook or troubleshooting document, then link or reference it from related requirements only when useful.
- Preserve useful diagnostics while keeping user-facing UI readable. Prefer concise default summaries with expandable raw diagnostics and copyable full logs.
- Before closing a difficult fix, check whether the new lesson changes project rules, architecture constraints, or release procedure; if it does, update `AGENTS.md` or the relevant docs in the same change.

## Settings And Runtime Behavior

- When adding or changing a setting, decide explicitly whether it must apply immediately, on the next opened web page, or only after restart.
- Prefer immediate application. Update the in-memory UI state, the persisted setting, and the currently affected runtime surface in the same user action when feasible.
- If immediate application is not feasible, show clear user feedback at the setting location or via Toast, for example: "新打开的网站生效" or "重启应用后生效". Do not silently accept a setting that appears to do nothing.
- For settings affecting `WebViewActivity`, pass a runtime config snapshot through the launch `Intent` or use another explicit cross-process mechanism. Do not rely on `:webview` process `SharedPreferences` reads being up to date.
- When settings affect WebView creation, rendering, sandbox limits, user-agent, injection, preload, warm pool, or cache behavior, consider stale `WebViewPool` instances. Clear or bypass old instances when they could carry old behavior.
- Settings that affect the current Activity, such as screen orientation, system UI mode, flag secure, or lock behavior, must update the current Activity immediately when possible.
- If a setting is represented in Compose with `remember`, make sure it is recomputed when returning from admin screens or when the underlying preference changes. Avoid stale remembered values on the main screen.

## Android And Kiosk Best Practices

- Keep UI work on the main thread. Run Room/database, network, file, and expensive work on `Dispatchers.IO`.
- WebView creation and mutation must stay on the main thread.
- Use lifecycle-aware coroutines and cancel long-running jobs in `onDestroy` when needed.
- Dismiss dialogs, clear callbacks, and destroy WebViews in lifecycle teardown to avoid leaks.
- Use Activity context for UI objects and application context only for long-lived non-UI services.
- Preserve kiosk safety: do not weaken Device Owner, Lock Task, screen pinning, hidden parent verification, or safe exit behavior without an explicit user request.
- Do not add Android permissions unless the feature requires them and the security tradeoff is clear.
- Keep WebView security restrictions configurable and predictable. External schemes, downloads, media capture, geolocation, file access, mixed content, and multi-window behavior must respect the parent settings.
- Back handling in WebView must preserve the existing order: web history first, child WebView stack next, then parent verification or close.
- Orientation changes must not make exit, unlock, or parent controls unreachable.

## Release Discipline

- Version bumps are patch/minor/major according to the actual user-visible change. Bug fixes like layout/runtime setting fixes are patch releases.
- Release commits should use `chore: release vX.Y.Z`; implementation commits should use conventional commit style such as `fix(webview): ...`.
- After pushing a release tag, verify the GitHub Actions tag workflow completed successfully and that the GitHub Release contains both debug and release APK assets.
