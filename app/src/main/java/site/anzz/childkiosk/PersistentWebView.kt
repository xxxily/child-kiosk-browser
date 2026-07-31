package site.anzz.childkiosk

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.webkit.WebView
import site.anzz.childkiosk.performance.HighPerformanceDiagnostics
import site.anzz.childkiosk.performance.HighPerformanceSessionController

/**
 * WebView host for managed tabs with opt-in background continuity for trusted
 * high-performance pages.
 *
 * Chromium derives page scheduling (timer throttling, task suspension, screen-off pause) from the
 * WebView's real window visibility and screen state. A page whose host Activity is stopped or
 * whose screen turned off is hidden to Blink and gets throttled/frozen even when the process
 * survives through the foreground service and WakeLock.
 *
 * [PersistentWebView] reports VISIBLE/SCREEN_STATE_ON to Chromium ONLY while BOTH conditions
 * hold: the page has an active protected session AND its host Activity is paused/stopped. While
 * the Activity is started/resumed every callback passes through untouched, keeping the Chromium
 * input connection and system IME fully native (v0.4.10 regression guard).
 */
class PersistentWebView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : WebView(context, attrs, defStyleAttr) {

    private var continuityActive = false

    override fun onWindowVisibilityChanged(visibility: Int) {
        val keepAlive = keepContinuity()
        trackContinuityTransition(keepAlive)
        super.onWindowVisibilityChanged(if (keepAlive) View.VISIBLE else visibility)
    }

    override fun dispatchWindowVisibilityChanged(visibility: Int) {
        val keepAlive = keepContinuity()
        trackContinuityTransition(keepAlive)
        super.dispatchWindowVisibilityChanged(if (keepAlive) View.VISIBLE else visibility)
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        val keepAlive = keepContinuity()
        trackContinuityTransition(keepAlive)
        super.onVisibilityChanged(changedView, if (keepAlive) View.VISIBLE else visibility)
    }

    override fun dispatchVisibilityChanged(changedView: View, visibility: Int) {
        val keepAlive = keepContinuity()
        trackContinuityTransition(keepAlive)
        super.dispatchVisibilityChanged(changedView, if (keepAlive) View.VISIBLE else visibility)
    }

    override fun onScreenStateChanged(screenState: Int) {
        val keepAlive = keepContinuity()
        trackContinuityTransition(keepAlive)
        super.onScreenStateChanged(if (keepAlive) View.SCREEN_STATE_ON else screenState)
    }

    override fun getWindowVisibility(): Int {
        return if (keepContinuity()) View.VISIBLE else super.getWindowVisibility()
    }

    override fun getVisibility(): Int {
        return if (keepContinuity()) View.VISIBLE else super.getVisibility()
    }

    override fun isShown(): Boolean {
        return if (keepContinuity()) true else super.isShown()
    }

    override fun onPause() {
        val keepAlive = keepContinuity()
        trackContinuityTransition(keepAlive)
        if (!keepAlive) super.onPause()
    }

    private fun keepContinuity(): Boolean =
        HighPerformanceSessionController.isProtectedAndBackground(this)

    private fun trackContinuityTransition(active: Boolean) {
        if (active == continuityActive) return
        continuityActive = active
        HighPerformanceDiagnostics.record(
            type = if (active) "webview_continuity_enabled" else "webview_continuity_disabled",
            originOrUrl = runCatching { url }.getOrNull(),
            reason = if (active) "background_or_screen_off" else "foreground_restored"
        )
    }
}
