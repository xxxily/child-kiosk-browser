package site.anzz.childkiosk.performance

import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebView
import java.util.Collections
import java.util.WeakHashMap

/**
 * Physical keep-alive for protected WebViews: while the host Activity is stopped (background or
 * screen-off), the WebView is moved into a 1x1 `TYPE_APPLICATION_OVERLAY` window with
 * `FLAG_SHOW_WHEN_LOCKED`. The overlay window stays visible to WindowManager even when the screen
 * is off (keyguard is up), so Chromium never sees the page as hidden and never starts the ~60 s
 * Blink freeze timer; the screen-off signal is separately masked by [PersistentWebView] while the
 * view is attached here.
 *
 * Window switching detaches the view and rebuilds its surface, which caused a white screen / reload
 * in the v0.4.13 attempt; [forceRedraw] actively pushes Chromium to re-composite after every
 * attach/detach instead of waiting for a surface event that may never arrive.
 */
internal object HighPerformanceOverlayManager {
    private const val TAG = "HighPerformanceOverlay"

    private val mainHandler = Handler(Looper.getMainLooper())
    private val attached = Collections.synchronizedMap(WeakHashMap<WebView, Unit>())

    fun canUseOverlay(context: Context): Boolean = Settings.canDrawOverlays(context.applicationContext)

    fun isAttached(webView: WebView): Boolean = attached.containsKey(webView)

    fun attach(context: Context, webView: WebView): Boolean {
        val appContext = context.applicationContext
        val windowManager = appContext.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            ?: return false
        if (!Settings.canDrawOverlays(appContext)) {
            HighPerformanceDiagnostics.record(
                type = "overlay_permission_missing",
                result = "degraded",
                reason = "cannot_draw_overlays"
            )
            return false
        }
        if (attached.containsKey(webView)) return true

        return runCatching {
            (webView.parent as? ViewGroup)?.removeView(webView)
            val layoutParams = WindowManager.LayoutParams().apply {
                type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                format = PixelFormat.TRANSLUCENT
                width = 1
                height = 1
                gravity = Gravity.TOP or Gravity.START
                x = 0
                y = 0
            }
            windowManager.addView(webView, layoutParams)
            attached[webView] = Unit
            forceRedraw(webView, "overlay_attached")
            HighPerformanceDiagnostics.record(
                type = "overlay_attached",
                reason = "physical_keep_alive"
            )
            true
        }.getOrElse { failure ->
            Log.w(TAG, "Failed to attach overlay window", failure)
            HighPerformanceDiagnostics.record(
                type = "overlay_attach_failed",
                result = "failed",
                reason = failure.javaClass.simpleName
            )
            false
        }
    }

    fun detach(webView: WebView): Boolean {
        if (!attached.containsKey(webView)) return false
        return runCatching {
            (webView.parent as? ViewGroup)?.removeView(webView)
            attached.remove(webView)
            HighPerformanceDiagnostics.record(
                type = "overlay_detached",
                result = "ok",
                reason = "restore_foreground"
            )
            true
        }.getOrElse { failure ->
            Log.w(TAG, "Failed to detach overlay window", failure)
            false
        }
    }

    /** Pushes Chromium to re-composite onto the (possibly new) window surface. */
    fun forceRedraw(webView: WebView, reason: String) {
        runCatching {
            webView.onResume()
            webView.invalidate()
            mainHandler.postDelayed({ runCatching { webView.invalidate() } }, 100L)
            mainHandler.postDelayed({ runCatching { webView.invalidate() } }, 500L)
            HighPerformanceDiagnostics.record(
                type = "overlay_redraw_forced",
                reason = reason
            )
        }
    }
}
