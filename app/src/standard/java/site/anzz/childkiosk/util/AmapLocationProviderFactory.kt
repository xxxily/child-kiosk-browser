package site.anzz.childkiosk.util

import android.content.Context
import android.webkit.WebView

object AmapLocationProviderFactory {
    fun create(@Suppress("UNUSED_PARAMETER") context: Context): AmapLocationProvider = NoOpAmapLocationProvider
    fun configureApiKey(
        @Suppress("UNUSED_PARAMETER") context: Context,
        @Suppress("UNUSED_PARAMETER") apiKey: String
    ) = Unit
}

private object NoOpAmapLocationProvider : AmapLocationProvider {
    override val sdkIncluded: Boolean = false
    override val sdkVersion: String = ""

    override fun isUsable(config: WebViewRuntimeConfig): Boolean = false

    override fun availabilityLabel(config: WebViewRuntimeConfig): String = "当前 standard 版本未集成高德定位 SDK"

    override fun diagnosticSummary(config: WebViewRuntimeConfig): String = availabilityLabel(config)

    override fun requestSingleLocation(
        config: WebViewRuntimeConfig,
        timeoutMs: Long,
        allowCached: Boolean,
        origin: String?,
        callback: (NativeLocationResult) -> Unit
    ): String {
        callback(
            NativeLocationResult(
                success = false,
                provider = "amap",
                error = NativeLocationError.PROVIDER_UNAVAILABLE,
                message = availabilityLabel(config)
            )
        )
        return ""
    }

    override fun startWatch(
        config: WebViewRuntimeConfig,
        origin: String?,
        callback: (NativeLocationResult) -> Unit
    ): String {
        callback(
            NativeLocationResult(
                success = false,
                provider = "amap",
                error = NativeLocationError.PROVIDER_UNAVAILABLE,
                message = availabilityLabel(config)
            )
        )
        return ""
    }

    override fun cancelRequest(id: String) = Unit
    override fun stopAll() = Unit
    override fun destroy() = Unit
    override fun startAssistantLocation(webView: WebView, config: WebViewRuntimeConfig, origin: String): Boolean = false
    override fun stopAssistantLocation(webView: WebView) = Unit
    override fun stopAllAssistantLocations() = Unit
}
