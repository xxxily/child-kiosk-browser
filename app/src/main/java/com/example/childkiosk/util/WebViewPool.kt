package com.example.childkiosk.util

import android.annotation.SuppressLint
import android.content.Context
import android.net.http.SslError
import android.os.Looper
import android.webkit.*

data class PreloadEntry(
    val webView: WebView,
    var progress: Int = 0,
    var isLoaded: Boolean = false,
    val isUrlPreload: Boolean = true,
    val shouldDestroyOnDispose: Boolean = true
)

object WebViewPool {
    private val pool = mutableMapOf<String, PreloadEntry>()
    private val warmPool = ArrayDeque<WebView>()
    private var appContext: Context? = null
    private const val MAX_POOL_SIZE = 3
    private const val MAX_WARM_POOL_SIZE = 1

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun warmupBlank(count: Int = MAX_WARM_POOL_SIZE) {
        val ctx = appContext ?: return
        if (Looper.myLooper() != Looper.getMainLooper()) return
        if (!ProcessUtils.isWebViewProcess(ctx)) return
        if (KioskPrefs.getWebViewHostMode(ctx) == KioskPrefs.WEBVIEW_HOST_MODE_LIGHTWEIGHT_NATIVE) return
        if (!KioskPrefs.getWebViewWarmPoolEnabled(ctx)) return

        repeat((count - warmPool.size).coerceAtLeast(0)) {
            if (warmPool.size >= MAX_WARM_POOL_SIZE) return@repeat
            val webView = createBlankWebView(ctx)
            warmPool.addLast(webView)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun preload(url: String) {
        val ctx = appContext ?: return
        if (Looper.myLooper() != Looper.getMainLooper()) return // WebView must be created on main thread
        if (!ProcessUtils.isWebViewProcess(ctx)) return
        if (KioskPrefs.getWebViewHostMode(ctx) == KioskPrefs.WEBVIEW_HOST_MODE_LIGHTWEIGHT_NATIVE) return
        if (!KioskPrefs.getWebPreloadEnabled(ctx)) return
        val cleanUrl = url.trim()
        if (cleanUrl.isEmpty() || cleanUrl == "about:blank") return
        if (pool.containsKey(cleanUrl)) return
        if (pool.size >= MAX_POOL_SIZE) return

        val originalHost = WebViewRuntime.hostOf(cleanUrl)

        val webView = acquireWarmWebView(ctx, cleanUrl)

        val entry = PreloadEntry(webView)
        pool[cleanUrl] = entry

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                entry.progress = 0
                entry.isLoaded = false
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                entry.isLoaded = true
                entry.progress = 100
            }

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val urlStr = request?.url?.toString() ?: return false
                if (WebViewRuntime.isInternalWebViewUrl(urlStr)) {
                    return false
                }
                if (!WebViewRuntime.isWebUrl(urlStr)) {
                    return true
                }
                val host = WebViewRuntime.hostOf(urlStr)
                val shouldLimitRedirect = KioskPrefs.isLimitUrlRedirectEnabled(ctx)
                if (shouldLimitRedirect && !WebViewRuntime.isSameHostOrSubdomain(host, originalHost)) {
                    return true
                }
                return false
            }

            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                val requestUrl = request?.url?.toString()
                val host = request?.url?.host
                if (KioskPrefs.isLimitAdBlockEnabled(ctx) && AdBlocker.isAdRequest(requestUrl ?: host)) {
                    return WebResourceResponse(
                        "text/plain",
                        "utf-8",
                        java.io.ByteArrayInputStream(ByteArray(0))
                    )
                }
                return super.shouldInterceptRequest(view, request)
            }

            @SuppressLint("WebViewClientOnReceivedSslError")
            override fun onReceivedSslError(
                view: WebView?,
                handler: SslErrorHandler?,
                error: SslError?
            ) {
                if (KioskPrefs.isLimitSslCheckEnabled(ctx)) {
                    handler?.cancel()
                } else {
                    handler?.proceed()
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                super.onProgressChanged(view, newProgress)
                entry.progress = newProgress
                if (newProgress >= 100) {
                    entry.isLoaded = true
                }
            }
        }

        webView.loadUrl(cleanUrl)
    }

    fun acquire(url: String): PreloadEntry? {
        val ctx = appContext ?: return null
        if (!ProcessUtils.isWebViewProcess(ctx)) return null
        if (KioskPrefs.getWebViewHostMode(ctx) == KioskPrefs.WEBVIEW_HOST_MODE_LIGHTWEIGHT_NATIVE) return null
        val cleanUrl = url.trim()
        pool.remove(cleanUrl)?.let { return it }

        if (!KioskPrefs.getWebViewWarmPoolEnabled(ctx)) return null
        if (Looper.myLooper() != Looper.getMainLooper()) return null

        return warmPool.removeFirstOrNull()?.let { webView ->
            WebViewRuntime.applySettings(webView, ctx, cleanUrl)
            PreloadEntry(
                webView = webView,
                isLoaded = false,
                isUrlPreload = false,
                shouldDestroyOnDispose = true
            )
        }
    }

    fun recycleBlank(webView: WebView): Boolean {
        val ctx = appContext ?: return false
        if (Looper.myLooper() != Looper.getMainLooper()) return false
        if (!ProcessUtils.isWebViewProcess(ctx)) return false
        if (KioskPrefs.getWebViewHostMode(ctx) == KioskPrefs.WEBVIEW_HOST_MODE_LIGHTWEIGHT_NATIVE) return false
        if (!KioskPrefs.getWebViewWarmPoolEnabled(ctx)) return false
        if (warmPool.any { it === webView } || pool.values.any { it.webView === webView }) return true
        if (warmPool.size >= MAX_WARM_POOL_SIZE) return false

        return runCatching {
            resetToBlank(webView)
            WebViewRuntime.applySettings(webView, ctx, "about:blank")
            warmPool.addLast(webView)
            true
        }.getOrDefault(false)
    }

    fun release(url: String) {
        val cleanUrl = url.trim()
        pool.remove(cleanUrl)?.webView?.let { destroyWebView(it) }
    }

    fun clear() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            pool.values.forEach { destroyWebView(it.webView) }
            pool.clear()
            warmPool.forEach { destroyWebView(it) }
            warmPool.clear()
        } else {
            android.os.Handler(Looper.getMainLooper()).post {
                pool.values.forEach { destroyWebView(it.webView) }
                pool.clear()
                warmPool.forEach { destroyWebView(it) }
                warmPool.clear()
            }
        }
    }

    fun trimToSize(maxSize: Int) {
        val action = {
            while (pool.size > maxSize && pool.isNotEmpty()) {
                val key = pool.keys.first()
                pool.remove(key)?.webView?.let { destroyWebView(it) }
            }
            while (warmPool.size > MAX_WARM_POOL_SIZE) {
                destroyWebView(warmPool.removeLast())
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action()
        } else {
            android.os.Handler(Looper.getMainLooper()).post { action() }
        }
    }

    fun snapshot(): String {
        val ctx = appContext
        if (ctx != null && KioskPrefs.getWebViewHostMode(ctx) == KioskPrefs.WEBVIEW_HOST_MODE_LIGHTWEIGHT_NATIVE) {
            return "轻量原生承载：热备/预加载已跳过"
        }
        if (ctx != null && !ProcessUtils.isWebViewProcess(ctx)) {
            return "独立 WebView 进程：主页进程不保留热备/预加载"
        }
        return "热备 ${warmPool.size}/${MAX_WARM_POOL_SIZE}，预加载 ${pool.size}/$MAX_POOL_SIZE"
    }

    private fun acquireWarmWebView(ctx: Context, targetUrl: String): WebView {
        return warmPool.removeFirstOrNull()?.apply {
            WebViewRuntime.applySettings(this, ctx, targetUrl)
            setBackgroundColor(android.graphics.Color.parseColor("#FFF8E1"))
        } ?: WebView(ctx).apply {
            WebViewRuntime.applySettings(this, ctx, targetUrl)
            setBackgroundColor(android.graphics.Color.parseColor("#FFF8E1"))
        }
    }

    private fun createBlankWebView(ctx: Context): WebView {
        return WebView(ctx).apply {
            WebViewRuntime.applySettings(this, ctx, "about:blank")
            setBackgroundColor(android.graphics.Color.parseColor("#FFF8E1"))
            loadUrl("about:blank")
        }
    }

    private fun resetToBlank(webView: WebView) {
        webView.stopLoading()
        webView.webChromeClient = null
        webView.webViewClient = WebViewClient()
        runCatching { webView.removeJavascriptInterface("ChildKioskDebugBridge") }
        (webView.parent as? android.view.ViewGroup)?.removeView(webView)
        webView.removeAllViews()
        webView.loadUrl("about:blank")
        webView.clearHistory()
    }

    private fun destroyWebView(webView: WebView) {
        runCatching {
            resetToBlank(webView)
            webView.destroy()
        }
    }
}
