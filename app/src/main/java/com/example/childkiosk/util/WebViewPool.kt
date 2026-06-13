package com.example.childkiosk.util

import android.annotation.SuppressLint
import android.content.Context
import android.net.http.SslError
import android.os.Looper
import android.webkit.*
import com.example.childkiosk.util.AdBlocker
import java.net.URL

data class PreloadEntry(
    val webView: WebView,
    var progress: Int = 0,
    var isLoaded: Boolean = false
)

object WebViewPool {
    private val pool = mutableMapOf<String, PreloadEntry>()
    private var appContext: Context? = null
    private const val MAX_POOL_SIZE = 3

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun preload(url: String) {
        val ctx = appContext ?: return
        if (Looper.myLooper() != Looper.getMainLooper()) return // WebView must be created on main thread
        val cleanUrl = url.trim()
        if (cleanUrl.isEmpty() || cleanUrl == "about:blank") return
        if (pool.containsKey(cleanUrl)) return
        if (pool.size >= MAX_POOL_SIZE) return

        val originalHost = runCatching { URL(cleanUrl).host }.getOrNull()?.lowercase().orEmpty()

        val webView = WebView(ctx).apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true

                allowFileAccess = false
                allowContentAccess = false
                allowFileAccessFromFileURLs = false
                allowUniversalAccessFromFileURLs = false

                setSupportMultipleWindows(false)
                javaScriptCanOpenWindowsAutomatically = false

                saveFormData = false
                @Suppress("DEPRECATION")
                savePassword = false
                setGeolocationEnabled(false)

                mediaPlaybackRequiresUserGesture = false
                mixedContentMode = if (cleanUrl.startsWith("http://", ignoreCase = true)) {
                    WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                } else {
                    WebSettings.MIXED_CONTENT_NEVER_ALLOW
                }

                cacheMode = WebSettings.LOAD_DEFAULT
                useWideViewPort = true
                loadWithOverviewMode = true
                textZoom = 100
            }

            setOnLongClickListener { true }
            isLongClickable = false
            setBackgroundColor(android.graphics.Color.parseColor("#FFF8E1"))
        }

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
                if (!urlStr.startsWith("http://", true) && !urlStr.startsWith("https://", true)) {
                    return true
                }
                val host = runCatching { URL(urlStr).host }.getOrNull()?.lowercase().orEmpty()
                if (originalHost.isNotEmpty() && host.isNotEmpty()) {
                    val isAllowed = host == originalHost || host.endsWith(".$originalHost")
                    if (!isAllowed) {
                        return true
                    }
                }
                return false
            }

            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                val host = request?.url?.host
                if (AdBlocker.isAdHost(host)) {
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
                handler?.cancel()
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                super.onProgressChanged(view, newProgress)
                entry.progress = newProgress
                if (newProgress >= 85) {
                    entry.isLoaded = true
                }
            }
        }

        webView.loadUrl(cleanUrl)
    }

    fun acquire(url: String): PreloadEntry? {
        val cleanUrl = url.trim()
        return pool.remove(cleanUrl)
    }

    fun release(url: String) {
        val cleanUrl = url.trim()
        pool.remove(cleanUrl)?.webView?.destroy()
    }

    fun clear() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            pool.values.forEach { it.webView.destroy() }
            pool.clear()
        } else {
            android.os.Handler(Looper.getMainLooper()).post {
                pool.values.forEach { it.webView.destroy() }
                pool.clear()
            }
        }
    }

    fun trimToSize(maxSize: Int) {
        val action = {
            while (pool.size > maxSize && pool.isNotEmpty()) {
                val key = pool.keys.first()
                pool.remove(key)?.webView?.destroy()
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action()
        } else {
            android.os.Handler(Looper.getMainLooper()).post { action() }
        }
    }
}
