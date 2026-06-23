package site.anzz.childkiosk.util

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
    fun applySettings(
        webView: WebView,
        context: Context,
        targetUrl: String,
        config: WebViewRuntimeConfig = KioskPrefs.getWebViewRuntimeConfig(context)
    ) {
        WebView.setWebContentsDebuggingEnabled(config.chromeInspectEnabled)
        applyRenderMode(webView, context, config)

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, config.thirdPartyCookiesEnabled)
        }

        webView.settings.apply {
            javaScriptEnabled = true
            javaScriptCanOpenWindowsAutomatically = !config.limitMultiWindow
            domStorageEnabled = true
            databaseEnabled = true

            loadsImagesAutomatically = true
            blockNetworkImage = false
            blockNetworkLoads = false

            val limitFile = config.limitFileAccess
            allowFileAccess = !limitFile
            allowContentAccess = !limitFile
            allowFileAccessFromFileURLs = !limitFile
            allowUniversalAccessFromFileURLs = !limitFile

            setSupportMultipleWindows(!config.limitMultiWindow)

            saveFormData = false
            @Suppress("DEPRECATION")
            savePassword = false
            setGeolocationEnabled(!config.limitGeolocation)

            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = when {
                targetUrl.startsWith("http://", ignoreCase = true) -> WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                config.strictMixedContent -> WebSettings.MIXED_CONTENT_NEVER_ALLOW
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
            userAgentString = resolveUserAgent(defaultUserAgent, config)
        }

        if (config.limitLongClick) {
            webView.setOnLongClickListener { true }
            webView.isLongClickable = false
        } else {
            webView.setOnLongClickListener(null)
            webView.isLongClickable = true
        }
    }

    private fun applyRenderMode(webView: WebView, context: Context, config: WebViewRuntimeConfig) {
        val requestedMode = config.webViewRenderMode
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

    fun webViewDiagnosticSummary(
        context: Context,
        config: WebViewRuntimeConfig = KioskPrefs.getWebViewRuntimeConfig(context)
    ): String {
        val metrics = context.resources.displayMetrics
        return "host=NATIVE_FRAME_LAYOUT, " +
            "renderMode=${config.webViewRenderMode}, " +
            "topProgress=${config.webViewTopProgressEnabled}, " +
            "warmPool=${config.webViewWarmPoolEnabled}, " +
            "urlPreload=${config.webPreloadEnabled}, " +
            "offscreenPreRaster=false, " +
            "screen=${metrics.widthPixels}x${metrics.heightPixels}, density=${metrics.density}, " +
            "process=${ProcessUtils.currentProcessName(context)}, " +
            memorySummary(context)
    }

    fun logWebViewDiagnostics(
        context: Context,
        event: String,
        url: String? = null,
        config: WebViewRuntimeConfig = KioskPrefs.getWebViewRuntimeConfig(context)
    ) {
        Log.d(
            "ChildKioskWebView",
            "WebView diagnostics: event=$event, url=${url.orEmpty()}, ${webViewDiagnosticSummary(context, config)}"
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

    fun resolveUserAgent(defaultUserAgent: String, config: WebViewRuntimeConfig): String {
        val customUserAgent = config.customUserAgent.trim()
        if (customUserAgent.isNotEmpty()) {
            return customUserAgent
        }
        return if (config.useBrowserUserAgent) {
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
