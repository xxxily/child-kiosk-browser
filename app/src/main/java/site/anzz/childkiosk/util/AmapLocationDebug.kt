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
    private val LOCATION_TYPE_PATTERN = Regex("""\btype=(\d+)\b""")

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

    fun locationTypeLabel(type: Int): String {
        return when (type) {
            1 -> "GPS定位"
            2 -> "同一请求复用"
            3 -> "快速定位"
            4 -> "修正缓存"
            5 -> "Wi-Fi定位"
            6 -> "基站定位"
            7 -> "高德网络定位"
            8 -> "离线定位"
            9 -> "最近位置缓存"
            10 -> "补偿定位"
            11 -> "粗略定位"
            12 -> "网络定位"
            else -> "未知类型"
        }
    }

    fun locationTypeDisplay(type: Int): String {
        return "${locationTypeLabel(type)}(type=$type)"
    }

    fun locationTypeFromMessage(message: String): Int? {
        return LOCATION_TYPE_PATTERN.find(message)?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    fun humanizeLocationTypeInMessage(message: String): String {
        return LOCATION_TYPE_PATTERN.replace(message) { match ->
            val prefixIndex = match.range.first - 1
            if (prefixIndex >= 0 && message[prefixIndex] == '(') return@replace match.value
            val type = match.groupValues.getOrNull(1)?.toIntOrNull() ?: return@replace match.value
            locationTypeDisplay(type)
        }
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
