package com.example.childkiosk.util

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.util.Log
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import java.net.URL

object WebViewRuntime {

    @SuppressLint("SetJavaScriptEnabled")
    fun applySettings(webView: WebView, context: Context, targetUrl: String) {
        WebView.setWebContentsDebuggingEnabled(KioskPrefs.isChromeInspectEnabled(context))
        applyRenderMode(webView, context)

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, KioskPrefs.isThirdPartyCookiesEnabled(context))
        }

        webView.settings.apply {
            javaScriptEnabled = true
            javaScriptCanOpenWindowsAutomatically = !KioskPrefs.isLimitMultiWindowEnabled(context)
            domStorageEnabled = true
            databaseEnabled = true

            loadsImagesAutomatically = true
            blockNetworkImage = false
            blockNetworkLoads = false

            val limitFile = KioskPrefs.isLimitFileAccessEnabled(context)
            allowFileAccess = !limitFile
            allowContentAccess = !limitFile
            allowFileAccessFromFileURLs = !limitFile
            allowUniversalAccessFromFileURLs = !limitFile

            setSupportMultipleWindows(!KioskPrefs.isLimitMultiWindowEnabled(context))

            saveFormData = false
            @Suppress("DEPRECATION")
            savePassword = false
            setGeolocationEnabled(!KioskPrefs.isLimitGeolocationEnabled(context))

            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = when {
                targetUrl.startsWith("http://", ignoreCase = true) -> WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                KioskPrefs.isStrictMixedContentEnabled(context) -> WebSettings.MIXED_CONTENT_NEVER_ALLOW
                else -> WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            }

            cacheMode = WebSettings.LOAD_DEFAULT
            useWideViewPort = false
            loadWithOverviewMode = false
            textZoom = 100
            builtInZoomControls = false
            displayZoomControls = false
            // Keep this disabled for browser parity. On high-DPR devices it can increase
            // Chromium tile pressure and reproduce partial rendering failures.
            offscreenPreRaster = false
            safeBrowsingEnabled = true

            val defaultUserAgent = runCatching {
                WebSettings.getDefaultUserAgent(context)
            }.getOrDefault(userAgentString)
            userAgentString = resolveUserAgent(context, defaultUserAgent)
        }

        val limitLongClick = KioskPrefs.isLimitLongClickEnabled(context)
        if (limitLongClick) {
            webView.setOnLongClickListener { true }
            webView.isLongClickable = false
        } else {
            webView.setOnLongClickListener(null)
            webView.isLongClickable = true
        }
    }

    private fun applyRenderMode(webView: WebView, context: Context) {
        val requestedMode = KioskPrefs.getWebViewRenderMode(context)
        val targetLayerType = View.LAYER_TYPE_NONE
        if (webView.layerType != targetLayerType) {
            webView.setLayerType(targetLayerType, null)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            webView.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, true)
        }

        val metrics = context.resources.displayMetrics
        Log.d(
            "ChildKioskWebView",
            "Render mode applied: requested=$requestedMode, actual=HARDWARE, " +
                "screen=${metrics.widthPixels}x${metrics.heightPixels}, density=${metrics.density}, " +
                "process=${ProcessUtils.currentProcessName(context)}, " +
                memorySummary(context)
        )
    }

    private fun memorySummary(context: Context): String {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val runtime = Runtime.getRuntime()
        return "memoryClass=${activityManager?.memoryClass ?: -1}MB, " +
            "largeMemoryClass=${activityManager?.largeMemoryClass ?: -1}MB, " +
            "heapMax=${runtime.maxMemory() / BYTES_PER_MB}MB, " +
            "heapTotal=${runtime.totalMemory() / BYTES_PER_MB}MB, " +
            "heapFree=${runtime.freeMemory() / BYTES_PER_MB}MB"
    }

    private const val BYTES_PER_MB = 1024L * 1024L

    fun webViewDiagnosticSummary(context: Context): String {
        val metrics = context.resources.displayMetrics
        return "host=NATIVE_FRAME_LAYOUT, " +
            "renderMode=${KioskPrefs.getWebViewRenderMode(context)}, " +
            "topProgress=${KioskPrefs.isWebViewTopProgressEnabled(context)}, " +
            "warmPool=${KioskPrefs.getWebViewWarmPoolEnabled(context)}, " +
            "urlPreload=${KioskPrefs.getWebPreloadEnabled(context)}, " +
            "offscreenPreRaster=false, " +
            "screen=${metrics.widthPixels}x${metrics.heightPixels}, density=${metrics.density}, " +
            "process=${ProcessUtils.currentProcessName(context)}, " +
            memorySummary(context)
    }

    fun logWebViewDiagnostics(context: Context, event: String, url: String? = null) {
        Log.d(
            "ChildKioskWebView",
            "WebView diagnostics: event=$event, url=${url.orEmpty()}, ${webViewDiagnosticSummary(context)}"
        )
    }

    fun isWebUrl(url: String): Boolean {
        return url.startsWith("http://", ignoreCase = true) ||
            url.startsWith("https://", ignoreCase = true)
    }

    fun isInternalWebViewUrl(url: String): Boolean {
        return url.equals("about:blank", ignoreCase = true) ||
            url.startsWith("about:", ignoreCase = true) ||
            url.startsWith("data:", ignoreCase = true) ||
            url.startsWith("blob:", ignoreCase = true) ||
            url.startsWith("javascript:", ignoreCase = true)
    }

    fun hostOf(url: String): String {
        return runCatching { URL(url).host }.getOrNull()?.lowercase().orEmpty()
    }

    fun isSameHostOrSubdomain(host: String, originalHost: String): Boolean {
        if (host.isBlank() || originalHost.isBlank()) return true
        return host == originalHost || host.endsWith(".$originalHost")
    }

    fun resolveUserAgent(context: Context, defaultUserAgent: String): String {
        val customUserAgent = KioskPrefs.getCustomUserAgent(context).trim()
        if (customUserAgent.isNotEmpty()) {
            return customUserAgent
        }
        return if (KioskPrefs.isUseBrowserUserAgentEnabled(context)) {
            browserLikeUserAgent(defaultUserAgent)
        } else {
            defaultUserAgent
        }
    }

    fun browserLikeUserAgent(defaultUserAgent: String): String {
        return defaultUserAgent
            .replace("; wv", "")
            .replace("Version/4.0 ", "")
            .replace("  ", " ")
            .trim()
    }
}
