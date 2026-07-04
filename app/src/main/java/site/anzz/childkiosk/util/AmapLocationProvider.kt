package site.anzz.childkiosk.util

import android.webkit.WebView

interface AmapLocationProvider {
    val sdkIncluded: Boolean
    val sdkVersion: String

    fun isUsable(config: WebViewRuntimeConfig): Boolean
    fun availabilityLabel(config: WebViewRuntimeConfig): String
    fun diagnosticSummary(config: WebViewRuntimeConfig): String

    fun requestSingleLocation(
        config: WebViewRuntimeConfig,
        timeoutMs: Long,
        allowCached: Boolean,
        origin: String?,
        callback: (NativeLocationResult) -> Unit
    ): String

    fun startWatch(
        config: WebViewRuntimeConfig,
        origin: String?,
        callback: (NativeLocationResult) -> Unit
    ): String

    fun cancelRequest(id: String)
    fun stopAll()
    fun destroy()

    fun startAssistantLocation(
        webView: WebView,
        config: WebViewRuntimeConfig,
        origin: String
    ): Boolean

    fun stopAssistantLocation(webView: WebView)
    fun stopAllAssistantLocations()
}
