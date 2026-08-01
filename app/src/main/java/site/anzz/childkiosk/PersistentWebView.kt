package site.anzz.childkiosk

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.webkit.WebView
import site.anzz.childkiosk.performance.HighPerformanceDiagnostics
import site.anzz.childkiosk.performance.HighPerformanceOverlayManager

/**
 * WebView host for managed tabs. While the tab is physically attached to the keep-alive overlay
 * window (background/screen-off high-performance protection), the screen-off signal is masked so
 * Chromium does not suspend the page; everywhere else every callback is fully native so IME and
 * focus behavior stay untouched (v0.4.15 guard: no foreground visibility falsification).
 */
class PersistentWebView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : WebView(context, attrs, defStyleAttr) {

    override fun onScreenStateChanged(screenState: Int) {
        if (HighPerformanceOverlayManager.isAttached(this)) {
            if (screenState != View.SCREEN_STATE_ON) {
                HighPerformanceDiagnostics.record(
                    type = "overlay_screen_state_kept",
                    originOrUrl = runCatching { url }.getOrNull()
                )
            }
            super.onScreenStateChanged(View.SCREEN_STATE_ON)
        } else {
            super.onScreenStateChanged(screenState)
        }
    }
}
