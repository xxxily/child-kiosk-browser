package com.example.childkiosk.util

import android.annotation.SuppressLint
import android.content.Context
import android.net.http.SslError
import android.os.Handler
import android.os.Looper
import android.webkit.*
import com.example.childkiosk.util.filter.FilterAction
import com.example.childkiosk.util.filter.FilterRepository
import com.example.childkiosk.util.filter.FilterResourceType

data class PreloadEntry(
    val webView: WebView,
    var progress: Int = 0,
    var isLoaded: Boolean = false,
    val isUrlPreload: Boolean = true,
    val shouldDestroyOnDispose: Boolean = true,
    val runtimeConfigKey: String = "",
    @Volatile var isDisposed: Boolean = false
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
        if (!KioskPrefs.getWebPreloadEnabled(ctx)) return
        val cleanUrl = url.trim()
        if (cleanUrl.isEmpty() || cleanUrl == "about:blank") return
        if (pool.containsKey(cleanUrl)) return
        if (pool.size >= MAX_POOL_SIZE) return

        val originalHost = WebViewRuntime.hostOf(cleanUrl)
        val runtimeConfig = KioskPrefs.getWebViewRuntimeConfig(ctx)

        val webView = acquireWarmWebView(ctx, cleanUrl, runtimeConfig)

        val entry = PreloadEntry(webView, runtimeConfigKey = runtimeConfig.poolKey())
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
                if (runtimeConfig.limitUrlRedirect && !WebViewRuntime.isSameHostOrSubdomain(host, originalHost)) {
                    return true
                }
                return false
            }

            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                if (runtimeConfig.limitAdBlock) {
                    val snapshot = runtimeConfig.filterSnapshot
                    val topLevelUrl = view?.url ?: url
                    val decision = AdBlocker.shouldBlock(ctx, request, topLevelUrl, snapshot)
                    if (decision.action == FilterAction.BLOCK) {
                        val requestUrl = request?.url?.toString().orEmpty()
                        val resourceType = FilterResourceType.infer(
                            url = requestUrl,
                            acceptHeader = request?.requestHeaders?.get("Accept"),
                            isMainFrame = request?.isForMainFrame == true
                        )
                        return AdBlocker.emptyResponse(resourceType)
                    }
                }
                return super.shouldInterceptRequest(view, request)
            }

            @SuppressLint("WebViewClientOnReceivedSslError")
            override fun onReceivedSslError(
                view: WebView?,
                handler: SslErrorHandler?,
                error: SslError?
            ) {
                if (runtimeConfig.limitSslCheck) {
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

        if (runtimeConfig.limitAdBlock && runtimeConfig.filterSnapshot.enabled) {
            Thread {
                runCatching {
                    FilterRepository.getEngine(ctx, runtimeConfig.filterSnapshot)
                }
                Handler(Looper.getMainLooper()).post {
                    if (!entry.isDisposed) {
                        webView.loadUrl(cleanUrl)
                    }
                }
            }.start()
        } else {
            Handler(Looper.getMainLooper()).post {
                if (!entry.isDisposed) {
                    webView.loadUrl(cleanUrl)
                }
            }
        }
    }

    fun acquire(
        url: String,
        allowUrlPreload: Boolean = true,
        allowWarmPool: Boolean? = null,
        runtimeConfig: WebViewRuntimeConfig? = null
    ): PreloadEntry? {
        val ctx = appContext ?: return null
        if (!ProcessUtils.isWebViewProcess(ctx)) return null
        val effectiveRuntimeConfig = runtimeConfig ?: KioskPrefs.getWebViewRuntimeConfig(ctx)
        val cleanUrl = url.trim()
        val runtimeConfigKey = effectiveRuntimeConfig.poolKey()
        pool.remove(cleanUrl)?.let { entry ->
            if (allowUrlPreload && entry.runtimeConfigKey == runtimeConfigKey) {
                return entry
            }
            entry.isDisposed = true
            destroyWebView(entry.webView)
        }

        val shouldUseWarmPool = allowWarmPool ?: KioskPrefs.getWebViewWarmPoolEnabled(ctx)
        if (!shouldUseWarmPool) return null
        if (Looper.myLooper() != Looper.getMainLooper()) return null

        return warmPool.removeFirstOrNull()?.let { webView ->
            WebViewRuntime.applySettings(webView, ctx, cleanUrl, effectiveRuntimeConfig)
            PreloadEntry(
                webView = webView,
                isLoaded = false,
                isUrlPreload = false,
                shouldDestroyOnDispose = true,
                runtimeConfigKey = runtimeConfigKey
            )
        }
    }

    fun recycleBlank(webView: WebView): Boolean {
        val ctx = appContext ?: return false
        if (Looper.myLooper() != Looper.getMainLooper()) return false
        if (!ProcessUtils.isWebViewProcess(ctx)) return false
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
        pool.remove(cleanUrl)?.let { entry ->
            entry.isDisposed = true
            destroyWebView(entry.webView)
        }
    }

    fun clear() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            pool.values.forEach { entry ->
                entry.isDisposed = true
                destroyWebView(entry.webView)
            }
            pool.clear()
            warmPool.forEach { destroyWebView(it) }
            warmPool.clear()
        } else {
            android.os.Handler(Looper.getMainLooper()).post {
                pool.values.forEach { entry ->
                    entry.isDisposed = true
                    destroyWebView(entry.webView)
                }
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
                pool.remove(key)?.let { entry ->
                    entry.isDisposed = true
                    destroyWebView(entry.webView)
                }
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
        if (ctx != null && !ProcessUtils.isWebViewProcess(ctx)) {
            return "独立 WebView 进程：主页进程不保留热备/预加载"
        }
        return "热备 ${warmPool.size}/${MAX_WARM_POOL_SIZE}，预加载 ${pool.size}/$MAX_POOL_SIZE"
    }

    private fun acquireWarmWebView(
        ctx: Context,
        targetUrl: String,
        runtimeConfig: WebViewRuntimeConfig
    ): WebView {
        return warmPool.removeFirstOrNull()?.apply {
            WebViewRuntime.applySettings(this, ctx, targetUrl, runtimeConfig)
            setBackgroundColor(android.graphics.Color.parseColor("#FFF8E1"))
        } ?: WebView(ctx).apply {
            WebViewRuntime.applySettings(this, ctx, targetUrl, runtimeConfig)
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

    private fun WebViewRuntimeConfig.poolKey(): String {
        return toJson().toString()
    }
}
