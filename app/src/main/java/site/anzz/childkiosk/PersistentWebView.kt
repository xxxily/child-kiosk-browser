package site.anzz.childkiosk

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.webkit.WebView
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

    override fun onWindowVisibilityChanged(visibility: Int) {
        if (HighPerformanceSessionController.isProtected(this)) {
            super.onWindowVisibilityChanged(View.VISIBLE)
        } else {
            super.onWindowVisibilityChanged(visibility)
        }
    }

    override fun dispatchWindowVisibilityChanged(visibility: Int) {
        if (HighPerformanceSessionController.isProtected(this)) {
            super.dispatchWindowVisibilityChanged(View.VISIBLE)
        } else {
            super.dispatchWindowVisibilityChanged(visibility)
        }
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        if (HighPerformanceSessionController.isProtected(this)) {
            super.onVisibilityChanged(changedView, View.VISIBLE)
        } else {
            super.onVisibilityChanged(changedView, visibility)
        }
    }

    override fun dispatchVisibilityChanged(changedView: View, visibility: Int) {
        if (HighPerformanceSessionController.isProtected(this)) {
            super.dispatchVisibilityChanged(changedView, View.VISIBLE)
        } else {
            super.dispatchVisibilityChanged(changedView, visibility)
        }
    }

    override fun onScreenStateChanged(screenState: Int) {
        if (HighPerformanceSessionController.isProtected(this)) {
            super.onScreenStateChanged(View.SCREEN_STATE_ON)
        } else {
            super.onScreenStateChanged(screenState)
        }
    }
}
