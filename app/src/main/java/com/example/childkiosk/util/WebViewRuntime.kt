package com.example.childkiosk.util

import android.annotation.SuppressLint
import android.content.Context
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
            offscreenPreRaster = KioskPrefs.isWebViewOffscreenPreRasterEnabled(context)
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
        val useSoftware = when (requestedMode) {
            KioskPrefs.WEBVIEW_RENDER_MODE_SOFTWARE -> true
            KioskPrefs.WEBVIEW_RENDER_MODE_HARDWARE -> false
            else -> shouldUseSoftwareRenderForHighDpr(context)
        }
        val targetLayerType = if (useSoftware) {
            View.LAYER_TYPE_SOFTWARE
        } else {
            View.LAYER_TYPE_NONE
        }
        if (webView.layerType != targetLayerType) {
            webView.setLayerType(targetLayerType, null)
        }

        val metrics = context.resources.displayMetrics
        Log.d(
            "ChildKioskWebView",
            "Render mode applied: requested=$requestedMode, actual=${if (useSoftware) "SOFTWARE" else "HARDWARE"}, " +
                "screen=${metrics.widthPixels}x${metrics.heightPixels}, density=${metrics.density}"
        )
    }

    private fun shouldUseSoftwareRenderForHighDpr(context: Context): Boolean {
        val metrics = context.resources.displayMetrics
        val pixelCount = metrics.widthPixels.toLong() * metrics.heightPixels.toLong()
        return metrics.density >= 3.5f && (metrics.heightPixels >= 3000 || pixelCount >= 4_000_000L)
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
