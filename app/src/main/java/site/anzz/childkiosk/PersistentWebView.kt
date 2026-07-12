package site.anzz.childkiosk

import android.content.Context
import android.util.AttributeSet
import android.util.Log
import android.view.View
import android.webkit.WebView
import site.anzz.childkiosk.performance.HighPerformanceDiagnostics
import site.anzz.childkiosk.performance.HighPerformanceSessionController

/**
 * A specialized [WebView] that intercepts and overrides visibility/screen-state changes
 * when a high-performance session is active.
 *
 * This deceives the Chromium WebView engine into thinking it is always active on a visible
 * window, preventing background/screen-off CPU and JS timer throttling.
 */
class PersistentWebView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : WebView(context, attrs, defStyleAttr) {

    private var hasLoggedVisibilityDeception = false
    private var hasLoggedScreenDeception = false

    /**
     * When true, the WebView reports itself as a non-text-editor so the system IME is never
     * invoked for this view. This is an opt-in restriction controlled by the parent setting
     * [site.anzz.childkiosk.util.KioskPrefs.isLimitImeInputEnabled].
     */
    var imeInputLimited: Boolean = false

    override fun onCheckIsTextEditor(): Boolean {
        if (imeInputLimited) return false
        return super.onCheckIsTextEditor()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        if (HighPerformanceSessionController.isProtected(this)) {
            if (visibility != View.VISIBLE) {
                logVisibilityDeception("onWindowVisibilityChanged", visibility)
            }
            super.onWindowVisibilityChanged(View.VISIBLE)
        } else {
            resetVisibilityDeceptionFlags()
            super.onWindowVisibilityChanged(visibility)
        }
    }

    override fun dispatchWindowVisibilityChanged(visibility: Int) {
        if (HighPerformanceSessionController.isProtected(this)) {
            if (visibility != View.VISIBLE) {
                logVisibilityDeception("dispatchWindowVisibilityChanged", visibility)
            }
            super.dispatchWindowVisibilityChanged(View.VISIBLE)
        } else {
            resetVisibilityDeceptionFlags()
            super.dispatchWindowVisibilityChanged(visibility)
        }
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        if (HighPerformanceSessionController.isProtected(this)) {
            if (visibility != View.VISIBLE) {
                logVisibilityDeception("onVisibilityChanged", visibility)
            }
            super.onVisibilityChanged(changedView, View.VISIBLE)
        } else {
            resetVisibilityDeceptionFlags()
            super.onVisibilityChanged(changedView, visibility)
        }
    }

    override fun dispatchVisibilityChanged(changedView: View, visibility: Int) {
        if (HighPerformanceSessionController.isProtected(this)) {
            if (visibility != View.VISIBLE) {
                logVisibilityDeception("dispatchVisibilityChanged", visibility)
            }
            super.dispatchVisibilityChanged(changedView, View.VISIBLE)
        } else {
            resetVisibilityDeceptionFlags()
            super.dispatchVisibilityChanged(changedView, visibility)
        }
    }

    override fun onScreenStateChanged(screenState: Int) {
        if (HighPerformanceSessionController.isProtected(this)) {
            if (screenState != View.SCREEN_STATE_ON) {
                logScreenDeception(screenState)
            }
            super.onScreenStateChanged(View.SCREEN_STATE_ON)
        } else {
            resetScreenDeceptionFlags()
            super.onScreenStateChanged(screenState)
        }
    }

    override fun getWindowVisibility(): Int {
        return if (HighPerformanceSessionController.isProtected(this)) {
            View.VISIBLE
        } else {
            super.getWindowVisibility()
        }
    }

    override fun getVisibility(): Int {
        return if (HighPerformanceSessionController.isProtected(this)) {
            View.VISIBLE
        } else {
            super.getVisibility()
        }
    }

    override fun isShown(): Boolean {
        return if (HighPerformanceSessionController.isProtected(this)) {
            true
        } else {
            super.isShown()
        }
    }

    override fun onPause() {
        if (HighPerformanceSessionController.isProtected(this)) {
            Log.d("PersistentWebView", "onPause blocked under active high-performance session to preserve timers.")
            HighPerformanceDiagnostics.record(
                type = "webview_pause_blocked",
                result = "ok",
                originOrUrl = this.url,
                reason = "active_protection"
            )
        } else {
            super.onPause()
        }
    }

    private fun logVisibilityDeception(method: String, realVisibility: Int) {
        if (!hasLoggedVisibilityDeception) {
            hasLoggedVisibilityDeception = true
            val visName = when (realVisibility) {
                View.GONE -> "GONE"
                View.INVISIBLE -> "INVISIBLE"
                else -> "UNKNOWN($realVisibility)"
            }
            Log.d("PersistentWebView", "Deceiving visibility in $method (real visibility was $visName)")
            HighPerformanceDiagnostics.record(
                type = "visibility_deceived",
                result = "ok",
                originOrUrl = this.url,
                reason = "${method}_$visName"
            )
        }
    }

    private fun resetVisibilityDeceptionFlags() {
        hasLoggedVisibilityDeception = false
    }

    private fun logScreenDeception(realScreenState: Int) {
        if (!hasLoggedScreenDeception) {
            hasLoggedScreenDeception = true
            val stateName = when (realScreenState) {
                View.SCREEN_STATE_OFF -> "OFF"
                else -> "UNKNOWN($realScreenState)"
            }
            Log.d("PersistentWebView", "Deceiving screen state (real state was $stateName)")
            HighPerformanceDiagnostics.record(
                type = "screen_state_deceived",
                result = "ok",
                originOrUrl = this.url,
                reason = "real_state_$stateName"
            )
        }
    }

    private fun resetScreenDeceptionFlags() {
        hasLoggedScreenDeception = false
    }
}
