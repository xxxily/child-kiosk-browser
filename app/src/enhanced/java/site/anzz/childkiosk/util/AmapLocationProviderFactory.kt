package site.anzz.childkiosk.util

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import com.amap.api.location.AMapLocation
import com.amap.api.location.AMapLocationClient
import com.amap.api.location.AMapLocationClientOption
import java.util.ArrayDeque
import java.util.Collections
import java.util.UUID
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap

object AmapLocationProviderFactory {
    fun create(context: Context): AmapLocationProvider = RealAmapLocationProvider(context.applicationContext)

    fun configureApiKey(@Suppress("UNUSED_PARAMETER") context: Context, apiKey: String) {
        RealAmapLocationProvider.configureApiKey(apiKey, force = false)
    }
}

private class RealAmapLocationProvider(private val appContext: Context) : AmapLocationProvider {
    override val sdkIncluded: Boolean = true
    override val sdkVersion: String = site.anzz.childkiosk.BuildConfig.AMAP_LOCATION_SDK_VERSION

    private val mainHandler = Handler(Looper.getMainLooper())
    private val activeRequests = ConcurrentHashMap<String, ActiveAmapRequest>()
    private val singleRequestQueue = ArrayDeque<String>()
    private val assistantClients = Collections.synchronizedMap(WeakHashMap<WebView, AMapLocationClient>())

    private var runningSingleRequestId: String? = null

    @Volatile
    private var lastDiagnostic: String = "暂无高德定位诊断"

    override fun isUsable(config: WebViewRuntimeConfig): Boolean {
        return config.amapLocationEnabled &&
            config.amapLocationPrivacyAgreed &&
            config.amapLocationApiKey.isNotBlank()
    }

    override fun availabilityLabel(config: WebViewRuntimeConfig): String {
        return when {
            !sdkIncluded -> "当前版本未集成高德定位 SDK"
            !config.amapLocationEnabled -> "高德定位增强未开启"
            config.amapLocationApiKey.isBlank() -> "未设置高德 Android SDK Key"
            !config.amapLocationPrivacyAgreed -> "未确认高德定位 SDK 隐私合规"
            else -> "高德定位 SDK 可用"
        }
    }

    override fun diagnosticSummary(config: WebViewRuntimeConfig): String {
        return "已集成 ${sdkVersion.ifBlank { "unknown" }}，${availabilityLabel(config)}，最近一次: $lastDiagnostic"
    }

    override fun requestSingleLocation(
        config: WebViewRuntimeConfig,
        timeoutMs: Long,
        allowCached: Boolean,
        origin: String?,
        callback: (NativeLocationResult) -> Unit
    ): String {
        val latestConfig = KioskPrefs.mergeFreshAmapLocationRuntimeConfig(appContext, config)
        if (!isUsable(latestConfig)) {
            dispatchError(NativeLocationError.PROVIDER_UNAVAILABLE, availabilityLabel(config), callback)
            return ""
        }

        val requestId = UUID.randomUUID().toString()
        activeRequests[requestId] = ActiveAmapRequest(
            single = SingleAmapRequest(
                config = latestConfig,
                timeoutMs = timeoutMs,
                allowCached = allowCached,
                callback = callback
            )
        )
        mainHandler.post {
            if (!activeRequests.containsKey(requestId)) return@post
            singleRequestQueue.addLast(requestId)
            startNextSingleRequestIfIdle()
        }
        return requestId
    }

    private fun startNextSingleRequestIfIdle() {
        if (runningSingleRequestId != null) return
        while (singleRequestQueue.isNotEmpty()) {
            val requestId = singleRequestQueue.removeFirst()
            val active = activeRequests[requestId] ?: continue
            val single = active.single ?: continue
            runningSingleRequestId = requestId
            startSingleAttempt(requestId, active, single, retryAuthFailure = true)
            return
        }
    }

    private fun startSingleAttempt(
        requestId: String,
        active: ActiveAmapRequest,
        single: SingleAmapRequest,
        retryAuthFailure: Boolean
    ) {
        if (!activeRequests.containsKey(requestId) || runningSingleRequestId != requestId) return

        active.destroyClientOnly()
        val startedAt = System.currentTimeMillis()
        val client = createClient(single.config, single.callback, startedAt)
        if (client == null) {
            activeRequests.remove(requestId)?.destroy()
            releaseSingleRequestSlot(requestId)
            return
        }

        val timeout = Runnable {
            val removed = activeRequests.remove(requestId) ?: return@Runnable
            removed.destroy()
            releaseSingleRequestSlot(requestId)
            dispatch(
                NativeLocationResult(
                    success = false,
                    provider = "amap",
                    precisePermission = true,
                    elapsedMs = System.currentTimeMillis() - startedAt,
                    error = NativeLocationError.TIMEOUT,
                    message = "高德定位请求超时"
                ),
                single.callback
            )
        }
        active.attachClient(client, timeout)

        val option = AMapLocationClientOption().apply {
            locationMode = modeFor(single.config)
            isOnceLocation = true
            isOnceLocationLatest = single.allowCached
            httpTimeOut = single.timeoutMs.coerceIn(1_000L, 60_000L)
        }
        client.setLocationListener { location ->
            val current = activeRequests[requestId] ?: return@setLocationListener
            if (current.client !== client) return@setLocationListener
            current.destroyClientOnly()
            val result = resultFromLocation(location, startedAt, "高德单次定位返回")
            if (retryAuthFailure && isAuthFailure(result)) {
                val retry = Runnable {
                    current.retryRunnable = null
                    if (activeRequests.containsKey(requestId) && runningSingleRequestId == requestId) {
                        startSingleAttempt(requestId, current, single, retryAuthFailure = false)
                    }
                }
                current.retryRunnable = retry
                mainHandler.postDelayed(retry, AUTH_FAILURE_RETRY_DELAY_MS)
                return@setLocationListener
            }

            activeRequests.remove(requestId)?.destroy()
            releaseSingleRequestSlot(requestId)
            dispatch(result, single.callback)
        }
        client.setLocationOption(option)
        mainHandler.postDelayed(timeout, single.timeoutMs.coerceAtLeast(1_000L))
        runCatching {
            client.startLocation()
        }.onFailure { e ->
            activeRequests.remove(requestId)?.destroy()
            releaseSingleRequestSlot(requestId)
            dispatchError(NativeLocationError.UNKNOWN, e.message ?: "启动高德定位失败", single.callback, startedAt)
        }
    }

    private fun releaseSingleRequestSlot(requestId: String) {
        if (runningSingleRequestId == requestId) {
            runningSingleRequestId = null
            startNextSingleRequestIfIdle()
        }
    }

    override fun startWatch(
        config: WebViewRuntimeConfig,
        origin: String?,
        callback: (NativeLocationResult) -> Unit
    ): String {
        if (!isUsable(config)) {
            dispatchError(NativeLocationError.PROVIDER_UNAVAILABLE, availabilityLabel(config), callback)
            return ""
        }

        val startedAt = System.currentTimeMillis()
        val client = createClient(config, callback, startedAt) ?: return ""
        val requestId = UUID.randomUUID().toString()
        val timeout = Runnable {
            cancelRequest(requestId)
            dispatchError(NativeLocationError.TIMEOUT, "高德 watchPosition 达到最长持续时间", callback, startedAt)
        }
        activeRequests[requestId] = ActiveAmapRequest(client = client, timeout = timeout)

        val option = AMapLocationClientOption().apply {
            locationMode = modeFor(config)
            isOnceLocation = false
            interval = 5_000L
            httpTimeOut = config.nativeLocationRequestTimeoutMs.coerceIn(1_000L, 60_000L)
        }
        client.setLocationListener { location ->
            if (!activeRequests.containsKey(requestId)) return@setLocationListener
            dispatch(resultFromLocation(location, startedAt, "高德连续定位返回"), callback)
        }
        client.setLocationOption(option)
        mainHandler.postDelayed(timeout, config.nativeLocationWatchMaxDurationMs)
        runCatching {
            client.startLocation()
        }.onFailure { e ->
            activeRequests.remove(requestId)?.destroy()
            dispatchError(NativeLocationError.UNKNOWN, e.message ?: "启动高德连续定位失败", callback, startedAt)
            return ""
        }
        return requestId
    }

    override fun cancelRequest(id: String) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { cancelRequest(id) }
            return
        }
        singleRequestQueue.remove(id)
        val active = activeRequests.remove(id) ?: return
        active.destroy()
        releaseSingleRequestSlot(id)
    }

    override fun stopAll() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { stopAll() }
            return
        }
        singleRequestQueue.clear()
        activeRequests.keys.toList().forEach(::cancelRequest)
        runningSingleRequestId = null
    }

    override fun destroy() {
        stopAll()
        stopAllAssistantLocations()
    }

    override fun startAssistantLocation(
        webView: WebView,
        config: WebViewRuntimeConfig,
        origin: String
    ): Boolean {
        if (!isUsable(config) || !config.amapLocationH5AssistantEnabled) return false
        val normalizedOrigin = KioskPrefs.normalizeOriginKey(origin)
        if (normalizedOrigin.isBlank() || !config.amapLocationH5AssistantAllowedOrigins.contains(normalizedOrigin)) {
            return false
        }
        stopAssistantLocation(webView)
        val client = createClient(config, callback = null, startedAt = System.currentTimeMillis()) ?: return false
        return runCatching {
            client.startAssistantLocation(webView)
            assistantClients[webView] = client
            true
        }.getOrElse { e ->
            lastDiagnostic = "H5辅助定位启动失败: ${e.message ?: e.javaClass.simpleName}"
            runCatching { client.onDestroy() }
            false
        }
    }

    override fun stopAssistantLocation(webView: WebView) {
        assistantClients.remove(webView)?.let { client ->
            runCatching { client.stopAssistantLocation() }
            runCatching { client.onDestroy() }
        }
    }

    override fun stopAllAssistantLocations() {
        assistantClients.keys.toList().forEach(::stopAssistantLocation)
    }

    @SuppressLint("MissingPermission")
    private fun createClient(
        config: WebViewRuntimeConfig,
        callback: ((NativeLocationResult) -> Unit)?,
        startedAt: Long
    ): AMapLocationClient? {
        return runCatching {
            configureApiKey(config.amapLocationApiKey, force = true)
            AMapLocationClient.updatePrivacyShow(appContext, true, true)
            AMapLocationClient.updatePrivacyAgree(appContext, true)
            AMapLocationClient(appContext)
        }.getOrElse { e ->
            callback?.let {
                dispatchError(
                    NativeLocationError.UNKNOWN,
                    e.message ?: "初始化高德定位客户端失败",
                    it,
                    startedAt
                )
            }
            null
        }
    }

    companion object : AmapLocationApiKeyConfigurator {
        private const val AUTH_FAILURE_RETRY_DELAY_MS = 450L

        @Volatile
        private var lastConfiguredApiKey: String = ""

        override fun configureApiKey(apiKey: String) {
            configureApiKey(apiKey, force = false)
        }

        fun configureApiKey(apiKey: String, force: Boolean) {
            val normalized = apiKey.trim()
            if (normalized.isBlank()) return
            if (!force && normalized == lastConfiguredApiKey) return
            AMapLocationClient.setApiKey(normalized)
            lastConfiguredApiKey = normalized
        }
    }

    private fun modeFor(config: WebViewRuntimeConfig): AMapLocationClientOption.AMapLocationMode {
        return when (config.nativeLocationMode) {
            KioskPrefs.NATIVE_LOCATION_MODE_LOW_POWER -> AMapLocationClientOption.AMapLocationMode.Battery_Saving
            KioskPrefs.NATIVE_LOCATION_MODE_HIGH_ACCURACY -> AMapLocationClientOption.AMapLocationMode.Hight_Accuracy
            else -> AMapLocationClientOption.AMapLocationMode.Hight_Accuracy
        }
    }

    private fun resultFromLocation(
        location: AMapLocation?,
        startedAt: Long,
        successMessage: String
    ): NativeLocationResult {
        if (location == null) {
            return NativeLocationResult(
                success = false,
                provider = "amap",
                elapsedMs = System.currentTimeMillis() - startedAt,
                error = NativeLocationError.PROVIDER_UNAVAILABLE,
                message = "高德定位返回空结果"
            )
        }
        if (location.errorCode != AMapLocation.LOCATION_SUCCESS) {
            return NativeLocationResult(
                success = false,
                provider = "amap",
                elapsedRealtimeNanos = location.elapsedRealtimeNanos,
                wallTimeMillis = location.time,
                elapsedMs = System.currentTimeMillis() - startedAt,
                error = NativeLocationError.PROVIDER_UNAVAILABLE,
                message = amapFailureMessage(location)
            )
        }
        val coordinateSystem = if (CoordinateTransforms.outOfChina(location.latitude, location.longitude)) {
            NativeLocationCoordinateSystem.WGS84
        } else {
            NativeLocationCoordinateSystem.GCJ02
        }
        return NativeLocationResult(
            success = true,
            latitude = location.latitude,
            longitude = location.longitude,
            accuracyMeters = location.accuracy.takeIf { it > 0f },
            altitude = location.altitude.takeIf { it != 0.0 },
            bearing = location.bearing.takeIf { it != 0f },
            speed = location.speed.takeIf { it != 0f },
            elapsedRealtimeNanos = location.elapsedRealtimeNanos,
            wallTimeMillis = location.time,
            provider = "amap",
            coordinateSystem = coordinateSystem,
            precisePermission = true,
            elapsedMs = System.currentTimeMillis() - startedAt,
            message = "$successMessage type=${location.locationType}"
        )
    }

    private fun dispatchError(
        error: NativeLocationError,
        message: String,
        callback: (NativeLocationResult) -> Unit,
        startedAt: Long = System.currentTimeMillis()
    ) {
        dispatch(
            NativeLocationResult(
                success = false,
                provider = "amap",
                elapsedMs = (System.currentTimeMillis() - startedAt).coerceAtLeast(0L),
                error = error,
                message = message
            ),
            callback
        )
    }

    private fun dispatch(result: NativeLocationResult, callback: (NativeLocationResult) -> Unit) {
        lastDiagnostic = result.toDiagnosticLine(redactCoordinates = true)
        if (Looper.myLooper() == Looper.getMainLooper()) {
            callback(result)
        } else {
            mainHandler.post { callback(result) }
        }
    }

    private fun amapFailureMessage(location: AMapLocation): String {
        val errorInfo = location.errorInfo.orEmpty()
        val detail = location.locationDetail.orEmpty()
        val conciseErrorInfo = errorInfo
            .substringBefore("请到")
            .substringBefore(",错误详细信息")
            .trim()
            .ifBlank { errorInfo.take(80).trim() }
        val shaAndPackage = detail
            .substringAfter("SHA1AndPackage#", missingDelimiterValue = "")
            .substringBefore("#")
            .trim()
        val invalidUserKey = location.errorCode == 7 || detail.contains("INVALID_USER_KEY", ignoreCase = true)

        return buildString {
            append("高德定位失败: ")
            append(location.errorCode)
            if (conciseErrorInfo.isNotBlank()) {
                append(' ')
                append(conciseErrorInfo)
            }
            if (invalidUserKey) {
                append("；鉴权失败 INVALID_USER_KEY，请检查高德控制台 Key 类型是否为 Android 定位 SDK Key，且已绑定当前包名和发布签名 SHA1")
            }
            if (shaAndPackage.isNotBlank()) {
                append("；高德校验包名/SHA1=")
                append(shaAndPackage)
            } else {
                val conciseDetail = detail
                    .substringBefore("#gsid#")
                    .substringBefore("#csid#")
                    .trim()
                    .take(180)
                if (conciseDetail.isNotBlank()) {
                    append("；")
                    append(conciseDetail)
                }
            }
        }
    }

    private fun isAuthFailure(result: NativeLocationResult): Boolean {
        return !result.success &&
            result.provider == "amap" &&
            result.message.contains("INVALID_USER_KEY", ignoreCase = true)
    }

    private data class SingleAmapRequest(
        val config: WebViewRuntimeConfig,
        val timeoutMs: Long,
        val allowCached: Boolean,
        val callback: (NativeLocationResult) -> Unit
    )

    private inner class ActiveAmapRequest(
        var client: AMapLocationClient? = null,
        private var timeout: Runnable? = null,
        val single: SingleAmapRequest? = null
    ) {
        var retryRunnable: Runnable? = null

        fun attachClient(client: AMapLocationClient, timeout: Runnable) {
            destroyClientOnly()
            this.client = client
            this.timeout = timeout
        }

        fun destroyClientOnly() {
            timeout?.let { mainHandler.removeCallbacks(it) }
            timeout = null
            client?.let { activeClient ->
                runCatching { activeClient.stopLocation() }
                runCatching { activeClient.onDestroy() }
            }
            client = null
        }

        fun destroy() {
            retryRunnable?.let { mainHandler.removeCallbacks(it) }
            retryRunnable = null
            destroyClientOnly()
        }
    }
}
