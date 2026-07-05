package site.anzz.childkiosk.util

import android.content.Context
import android.os.Looper
import android.os.Process
import android.util.Log

data class AmapLocationRuntimeConfigSnapshot(
    val exists: Boolean,
    val updatedAt: Long?,
    val lastModifiedAt: Long?,
    val enabled: Boolean?,
    val apiKeyLength: Int,
    val apiKeyFingerprint: String,
    val privacyAgreed: Boolean?,
    val providerStrategy: String?,
    val h5AssistantEnabled: Boolean?,
    val readError: String? = null
)

object AmapLocationDebug {
    const val TAG = "ChildKioskLocation"

    fun keyFingerprint(apiKey: String): String {
        val normalized = apiKey.trim()
        if (normalized.isBlank()) return "blank"
        return HashUtils.sha256(normalized).take(12)
    }

    fun keyLabel(apiKey: String): String {
        val normalized = apiKey.trim()
        return "len=${normalized.length},sha256=${keyFingerprint(normalized)}"
    }

    fun configSummary(context: Context, config: WebViewRuntimeConfig): String {
        val appContext = context.applicationContext
        val runtimeSnapshot = KioskPrefs.getAmapLocationRuntimeConfigDebugSnapshot(appContext)
        val prefsKey = KioskPrefs.getAmapLocationApiKey(appContext)
        return listOf(
            "process=${ProcessUtils.currentProcessName(appContext)}",
            "pid=${Process.myPid()}",
            "webviewProcess=${ProcessUtils.isWebViewProcess(appContext)}",
            "mainThread=${Looper.myLooper() == Looper.getMainLooper()}",
            "strategy=${config.amapLocationProviderStrategy}",
            "enabled=${config.amapLocationEnabled}",
            "privacy=${config.amapLocationPrivacyAgreed}",
            "effectiveKey=${keyLabel(config.amapLocationApiKey)}",
            "prefsKey=${keyLabel(prefsKey)}",
            "runtimeFile=${runtimeSnapshot.label()}",
            "bridge=${config.nativeLocationBridgeEnabled}",
            "warmup=${config.nativeLocationWarmupEnabled}",
            "h5Assistant=${config.amapLocationH5AssistantEnabled}"
        ).joinToString(", ")
    }

    fun appendToMessage(
        message: String,
        context: Context,
        config: WebViewRuntimeConfig,
        extra: String = ""
    ): String {
        val normalizedMessage = message.ifBlank { "无" }
        val details = listOf(extra, configSummary(context, config))
            .filter { it.isNotBlank() }
            .joinToString(", ")
        return "$normalizedMessage；高德调试: $details"
    }

    fun log(context: Context, event: String, config: WebViewRuntimeConfig, extra: String = "") {
        val details = listOf(extra, configSummary(context, config))
            .filter { it.isNotBlank() }
            .joinToString(", ")
        Log.d(TAG, "AMapLocation[$event]: $details")
    }

    private fun AmapLocationRuntimeConfigSnapshot.label(): String {
        if (!exists) return "missing"
        val updated = updatedAt?.let { "updatedAt=$it" } ?: "updatedAt=unknown"
        val modified = lastModifiedAt?.let { "mtime=$it" } ?: "mtime=unknown"
        val error = readError?.let { ",readError=$it" }.orEmpty()
        return "$updated,$modified,enabled=$enabled,privacy=$privacyAgreed,strategy=$providerStrategy," +
            "h5=$h5AssistantEnabled,key=len=$apiKeyLength,sha256=$apiKeyFingerprint$error"
    }
}
