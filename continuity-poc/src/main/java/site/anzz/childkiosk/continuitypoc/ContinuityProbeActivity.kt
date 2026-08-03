package site.anzz.childkiosk.continuitypoc

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout

class ContinuityProbeActivity : Activity() {
    private lateinit var root: FrameLayout
    private lateinit var webView: WebView
    private var debuggingEnabled = false
    private var automaticCdpEdgeEnabled = false

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val session = intent.getStringExtra(EXTRA_SESSION).orEmpty()
        debuggingEnabled = intent.getBooleanExtra(EXTRA_DEBUGGING, false)
        automaticCdpEdgeEnabled = intent.getBooleanExtra(EXTRA_AUTOMATIC_CDP_EDGE, false)
        ProbeLog.beginSession(this, session)
        ProbeLog.append(
            this,
            "activity_create",
            mapOf(
                "debugging" to debuggingEnabled,
                "automaticCdpEdge" to automaticCdpEdgeEnabled,
                "savedState" to (savedInstanceState != null)
            )
        )

        WebView.setWebContentsDebuggingEnabled(debuggingEnabled)
        startForegroundService(Intent(this, ContinuityForegroundService::class.java))

        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        webView = WebView(this).apply {
            setBackgroundColor(Color.WHITE)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.cacheMode = WebSettings.LOAD_NO_CACHE
            settings.mediaPlaybackRequiresUserGesture = true
            addJavascriptInterface(NativeProbeBridge(), "NativeProbe")
            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                    ProbeLog.append(this@ContinuityProbeActivity, "page_started", mapOf("url" to url))
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    ProbeLog.append(this@ContinuityProbeActivity, "page_finished", mapOf("url" to url))
                }

                override fun onRenderProcessGone(
                    view: WebView?,
                    detail: RenderProcessGoneDetail?
                ): Boolean {
                    ProbeLog.append(
                        this@ContinuityProbeActivity,
                        "renderer_gone",
                        mapOf(
                            "didCrash" to detail?.didCrash(),
                            "priority" to detail?.rendererPriorityAtExit()
                        )
                    )
                    return false
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                    val message = consoleMessage ?: return false
                    Log.d(
                        ProbeLog.TAG,
                        "console ${message.messageLevel()} ${message.sourceId()}:${message.lineNumber()} " +
                            message.message()
                    )
                    return true
                }
            }
        }
        root.addView(
            webView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        setContentView(root)

        val encodedSession = Uri.encode(session.ifBlank { "default" })
        webView.loadUrl("file:///android_asset/continuity_probe.html?session=$encodedSession")
    }

    override fun onStart() {
        InAppCdpLifecycleController.cancel()
        super.onStart()
        logLifecycle("activity_start")
    }

    override fun onResume() {
        super.onResume()
        logLifecycle("activity_resume")
    }

    override fun onPause() {
        logLifecycle("activity_pause")
        super.onPause()
    }

    override fun onStop() {
        logLifecycle("activity_stop")
        super.onStop()
        if (automaticCdpEdgeEnabled && debuggingEnabled) {
            InAppCdpLifecycleController.schedule(applicationContext)
        } else if (automaticCdpEdgeEnabled) {
            ProbeLog.append(this, "in_app_cdp_edge_skipped", mapOf("reason" to "debugging_disabled"))
        }
    }

    override fun onTrimMemory(level: Int) {
        ProbeLog.append(this, "trim_memory", mapOf("level" to level))
        super.onTrimMemory(level)
    }

    override fun onDestroy() {
        InAppCdpLifecycleController.cancel()
        ProbeLog.append(this, "activity_destroy")
        stopService(Intent(this, ContinuityForegroundService::class.java))
        if (::webView.isInitialized) {
            runCatching { webView.removeJavascriptInterface("NativeProbe") }
            runCatching { webView.loadUrl("about:blank") }
            runCatching { root.removeView(webView) }
            runCatching { webView.destroy() }
        }
        super.onDestroy()
    }

    private fun logLifecycle(event: String) {
        ProbeLog.append(
            this,
            event,
            mapOf(
                "hasFocus" to hasWindowFocus(),
                "windowVisibility" to window.decorView.windowVisibility
            )
        )
    }

    private inner class NativeProbeBridge {
        @JavascriptInterface
        fun report(payload: String?) {
            ProbeLog.append(
                this@ContinuityProbeActivity,
                "js_report",
                mapOf("payload" to payload.orEmpty().take(MAX_JS_PAYLOAD_LENGTH))
            )
        }
    }

    companion object {
        const val EXTRA_SESSION = "session"
        const val EXTRA_DEBUGGING = "debugging"
        const val EXTRA_AUTOMATIC_CDP_EDGE = "automatic_cdp_edge"
        private const val MAX_JS_PAYLOAD_LENGTH = 4_096
    }
}
