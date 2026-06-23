package site.anzz.childkiosk.util

import android.content.Context
import android.content.pm.PackageInfo
import android.os.Build
import android.util.Log
import android.webkit.WebSettings
import androidx.webkit.WebViewCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class WebViewProviderSnapshot(
    val providerPackageName: String?,
    val providerVersionName: String?,
    val providerVersionCode: Long?,
    val defaultUserAgent: String,
    val chromiumVersion: String?,
    val chromiumMajor: Int?,
    val androidVersion: String,
    val androidSdk: Int,
    val deviceModel: String,
    val processName: String,
    val isWebViewProcess: Boolean,
    val runtimeConfig: WebViewRuntimeConfig,
    val detectedAtMillis: Long,
    val readError: String?
) {
    val providerSummary: String
        get() {
            val packageName = providerPackageName ?: "System WebView"
            val version = providerVersionName ?: chromiumVersion?.let { "Chrome $it" } ?: "无法获取版本"
            return "$packageName ($version)"
        }

    val status: WebViewProviderStatus
        get() = when {
            readError != null -> WebViewProviderStatus.UNKNOWN
            chromiumMajor == null -> WebViewProviderStatus.UNKNOWN
            androidSdk <= Build.VERSION_CODES.P && chromiumMajor < 90 -> WebViewProviderStatus.HIGH_RISK
            chromiumMajor < 100 -> WebViewProviderStatus.HIGH_RISK
            chromiumMajor < 115 -> WebViewProviderStatus.OUTDATED
            else -> WebViewProviderStatus.NORMAL
        }

    fun diagnosticText(): String {
        val date = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(detectedAtMillis))
        return buildString {
            appendLine("WebView 内核运行环境诊断")
            appendLine("检测时间: $date")
            appendLine("Provider 包名: ${providerPackageName ?: "无法读取"}")
            appendLine("Provider 版本: ${providerVersionName ?: "无法读取"}")
            appendLine("Provider versionCode: ${providerVersionCode?.toString() ?: "无法读取"}")
            appendLine("Chromium 版本: ${chromiumVersion ?: "无法解析"}")
            appendLine("Chromium 主版本: ${chromiumMajor?.toString() ?: "无法解析"}")
            appendLine("状态: ${status.label}")
            appendLine("Android: $androidVersion (SDK $androidSdk)")
            appendLine("设备: $deviceModel")
            appendLine("进程: $processName")
            appendLine("WebView 独立进程: ${if (isWebViewProcess) "是" else "否"}")
            appendLine("渲染路径: WebViewActivity -> FrameLayout -> WebView")
            appendLine("Chrome Inspect: ${enabledText(runtimeConfig.chromeInspectEnabled)}")
            appendLine("手机浏览器 UA: ${enabledText(runtimeConfig.useBrowserUserAgent)}")
            appendLine("自定义 UA: ${if (runtimeConfig.customUserAgent.isBlank()) "未设置" else "已设置"}")
            appendLine("第三方 Cookie: ${enabledText(runtimeConfig.thirdPartyCookiesEnabled)}")
            appendLine("严格混合内容: ${enabledText(runtimeConfig.strictMixedContent)}")
            appendLine("热备 WebView: ${enabledText(runtimeConfig.webViewWarmPoolEnabled)}")
            appendLine("后台预加载: ${enabledText(runtimeConfig.webPreloadEnabled)}")
            appendLine("顶部进度条: ${enabledText(runtimeConfig.webViewTopProgressEnabled)}")
            appendLine("默认 UA: ${defaultUserAgent.ifBlank { "无法读取" }}")
            readError?.let { appendLine("读取错误: $it") }
        }
    }

    private fun enabledText(enabled: Boolean): String = if (enabled) "开启" else "关闭"
}

enum class WebViewProviderStatus(val label: String, val description: String) {
    NORMAL(
        label = "正常",
        description = "当前 Chromium 主版本较新，未发现明显 WebView 版本风险。"
    ),
    OUTDATED(
        label = "偏旧",
        description = "当前 Chromium 主版本偏旧，现代网页可能出现兼容性或渲染问题。"
    ),
    HIGH_RISK(
        label = "高风险",
        description = "当前 WebView/Android 组合较旧，建议优先升级 WebView、系统或更换可更新设备。"
    ),
    UNKNOWN(
        label = "无法识别",
        description = "无法完整读取 WebView provider 信息，请复制诊断信息并结合 logcat 排查。"
    )
}

object WebViewProviderDiagnostics {
    private val chromeVersionRegex = "Chrome/([\\d.]+)".toRegex()

    fun collect(context: Context): WebViewProviderSnapshot {
        val appContext = context.applicationContext
        var providerInfo: PackageInfo? = null
        var error: String? = null
        runCatching {
            providerInfo = WebViewCompat.getCurrentWebViewPackage(appContext)
        }.onFailure {
            error = it.message ?: it.javaClass.simpleName
        }

        val defaultUserAgent = runCatching {
            WebSettings.getDefaultUserAgent(appContext)
        }.getOrElse {
            if (error == null) {
                error = it.message ?: it.javaClass.simpleName
            }
            ""
        }
        val chromiumVersion = chromeVersionRegex.find(defaultUserAgent)?.groupValues?.getOrNull(1)
        val chromiumMajor = chromiumVersion
            ?.substringBefore('.')
            ?.toIntOrNull()

        val snapshot = WebViewProviderSnapshot(
            providerPackageName = providerInfo?.packageName,
            providerVersionName = providerInfo?.versionName,
            providerVersionCode = providerInfo?.longVersionCodeCompat(),
            defaultUserAgent = defaultUserAgent,
            chromiumVersion = chromiumVersion,
            chromiumMajor = chromiumMajor,
            androidVersion = Build.VERSION.RELEASE ?: "未知",
            androidSdk = Build.VERSION.SDK_INT,
            deviceModel = listOf(Build.MANUFACTURER, Build.MODEL)
                .filter { !it.isNullOrBlank() }
                .joinToString(" ")
                .ifBlank { "未知设备" },
            processName = ProcessUtils.currentProcessName(appContext),
            isWebViewProcess = ProcessUtils.isWebViewProcess(appContext),
            runtimeConfig = KioskPrefs.getWebViewRuntimeConfig(appContext),
            detectedAtMillis = System.currentTimeMillis(),
            readError = error
        )
        Log.d(
            "ChildKioskWebView",
            "WebView provider diagnostics: " +
                "providerPackage=${snapshot.providerPackageName.orEmpty()}, " +
                "providerVersion=${snapshot.providerVersionName.orEmpty()}, " +
                "chromiumMajor=${snapshot.chromiumMajor ?: -1}, " +
                "status=${snapshot.status.label}, " +
                "process=${snapshot.processName}, " +
                "android=${snapshot.androidVersion}, " +
                "device=${snapshot.deviceModel}"
        )
        return snapshot
    }

    private fun PackageInfo.longVersionCodeCompat(): Long {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            longVersionCode
        } else {
            @Suppress("DEPRECATION")
            versionCode.toLong()
        }
    }
}
