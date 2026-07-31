package site.anzz.childkiosk.performance

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebView
import java.util.Collections
import java.util.WeakHashMap

/**
 * Manages physical 1x1 overlay windows for WebViews in high-performance mode when Activity is stopped/paused.
 * This keeps Chromium WindowVisibility == VISIBLE without corrupting IME input during foreground execution.
 */
internal object HighPerformanceOverlayManager {
    private const val TAG = "HighPerformanceOverlay"

    private val attachedOverlays = Collections.synchronizedMap(
        WeakHashMap<WebView, OverlayContainer>()
    )

    private class OverlayContainer(
        val windowManager: WindowManager,
        val containerView: View
    )

    fun attachOverlay(context: Context, webView: WebView): Boolean {
        val appContext = context.applicationContext
        if (!Settings.canDrawOverlays(appContext)) {
            HighPerformanceDiagnostics.record(
                type = "overlay_permission_missing",
                reason = "cannot_draw_overlays"
            )
            return false
        }

        if (attachedOverlays.containsKey(webView)) {
            return true
        }

        return runCatching {
            val windowManager = appContext.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
                ?: return false

            (webView.parent as? ViewGroup)?.removeView(webView)

            val layoutParams = WindowManager.LayoutParams().apply {
                type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_PHONE
                }
                flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                format = PixelFormat.TRANSLUCENT
                width = 1
                height = 1
                gravity = Gravity.TOP or Gravity.START
                x = 0
                y = 0
            }

            windowManager.addView(webView, layoutParams)
            attachedOverlays[webView] = OverlayContainer(windowManager, webView)

            HighPerformanceDiagnostics.record(
                type = "overlay_attached",
                reason = "physical_keep_alive"
            )
            true
        }.getOrElse { failure ->
            Log.w(TAG, "Failed to attach overlay window for WebView", failure)
            HighPerformanceDiagnostics.record(
                type = "overlay_attach_failed",
                reason = failure.javaClass.simpleName
            )
            false
        }
    }

    fun detachOverlay(webView: WebView): Boolean {
        if (!attachedOverlays.containsKey(webView)) return false
        val container = attachedOverlays.remove(webView) ?: return false
        return runCatching {
            (webView.parent as? ViewGroup)?.removeView(webView)
            HighPerformanceDiagnostics.record(
                type = "overlay_detached",
                result = "ok",
                reason = "restore_foreground"
            )
            container.containerView != null
        }.getOrElse { failure ->
            Log.w(TAG, "Failed to detach overlay window for WebView", failure)
            false
        }
    }

    fun isAttached(webView: WebView): Boolean {
        return attachedOverlays.containsKey(webView)
    }
}
