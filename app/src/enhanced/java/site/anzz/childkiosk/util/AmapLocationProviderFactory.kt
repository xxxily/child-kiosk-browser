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

    fun configureApiKey(context: Context, apiKey: String) {
        AmapLocationDebug.log(
            context.applicationContext,
            "configure_api_key_entry",
            KioskPrefs.mergeFreshAmapLocationRuntimeConfig(
                context.applicationContext,
                KioskPrefs.getWebViewRuntimeConfig(context.applicationContext)
            ),
            "entryKey=${AmapLocationDebug.keyLabel(apiKey)}"
        )
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
    private var lastBackgroundRefreshQueuedAtMs: Long = 0L

    @Volatile
    private var lastDiagnostic: String = "暂无高德定位诊断"
    @Volatile
    private var lastSuccessResult: NativeLocationResult? = null

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
        refreshAfterCache: Boolean,
        origin: String?,
        callback: (NativeLocationResult) -> Unit
    ): String {
        val latestConfig = KioskPrefs.mergeFreshAmapLocationRuntimeConfig(appContext, config)
        if (!isUsable(latestConfig)) {
            AmapLocationDebug.log(
                appContext,
                "single_unusable",
                latestConfig,
                "origin=${origin.orEmpty()}, reason=${availabilityLabel(latestConfig)}"
            )
            dispatchError(NativeLocationError.PROVIDER_UNAVAILABLE, availabilityLabel(config), callback)
            return ""
        }

        if (allowCached) {
            cachedResult(latestConfig.nativeLocationMaxCacheAgeMs)?.let { cached ->
                val result = cached.copy(
                    cached = true,
                    elapsedMs = 0L,
                    message = cached.message.stripProviderDebug().ifBlank { "命中近期高德定位缓存" }
                )
                AmapLocationDebug.log(
                    appContext,
                    "single_cache_hit",
                    latestConfig,
                    "origin=${origin.orEmpty()}, ageMs=${result.cacheAgeMillis ?: -1}, refresh=$refreshAfterCache"
                )
                dispatch(result, callback)
                maybeQueueBackgroundRefresh(latestConfig, timeoutMs, origin, result.cacheAgeMillis, refreshAfterCache)
                return ""
            }
        }

        return enqueueSingleRequest(latestConfig, timeoutMs, allowCached, refreshAfterCache, origin, callback)
    }

    private fun enqueueSingleRequest(
        config: WebViewRuntimeConfig,
        timeoutMs: Long,
        allowCached: Boolean,
        refreshAfterCache: Boolean,
        origin: String?,
        callback: ((NativeLocationResult) -> Unit)?
    ): String {
        val requestId = UUID.randomUUID().toString()
        activeRequests[requestId] = ActiveAmapRequest(
            single = SingleAmapRequest(
                config = config,
                timeoutMs = timeoutMs,
                allowCached = allowCached,
                refreshAfterCache = refreshAfterCache,
                origin = origin,
                callback = callback
            )
        )
        AmapLocationDebug.log(
            appContext,
            "single_enqueue",
            config,
            "request=${shortId(requestId)}, origin=${origin.orEmpty()}, timeoutMs=$timeoutMs, allowCached=$allowCached"
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
            if (single.allowCached) {
                val cached = cachedResult(single.config.nativeLocationMaxCacheAgeMs)
                if (cached != null) {
                    activeRequests.remove(requestId)?.destroy()
                    dispatch(cached, single.callback)
                    maybeQueueBackgroundRefresh(
                        config = single.config,
                        timeoutMs = single.timeoutMs,
                        origin = single.origin,
                        cacheAgeMs = cached.cacheAgeMillis,
                        refreshAfterCache = single.refreshAfterCache
                    )
                    continue
                }
            }
            runningSingleRequestId = requestId
            startSingleAttempt(requestId, active, single, retryAuthFailure = true, attempt = 1)
            return
        }
    }

    private fun startSingleAttempt(
        requestId: String,
        active: ActiveAmapRequest,
        single: SingleAmapRequest,
        retryAuthFailure: Boolean,
        attempt: Int
    ) {
        if (!activeRequests.containsKey(requestId) || runningSingleRequestId != requestId) return

        active.destroyClientOnly()
        val startedAt = System.currentTimeMillis()
        AmapLocationDebug.log(
            appContext,
            "single_attempt_start",
            single.config,
            "request=${shortId(requestId)}, attempt=$attempt, origin=${single.origin.orEmpty()}, retryAuth=$retryAuthFailure"
        )
        val client = createClient(
            single.config,
            single.callback,
            startedAt,
            "single:${shortId(requestId)}:attempt=$attempt"
        )
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
                    message = providerDebugMessage(
                        "高德定位请求超时",
                        single.config,
                        "request=${shortId(requestId)}, attempt=$attempt, origin=${single.origin.orEmpty()}"
                    )
                ),
                single.callback
            )
        }
        active.attachClient(client, timeout)

        val option = AMapLocationClientOption().apply {
            locationMode = modeFor(single.config)
            isOnceLocation = true
            isOnceLocationLatest = false
            httpTimeOut = single.timeoutMs.coerceIn(1_000L, 60_000L)
        }
        client.setLocationListener { location ->
            val current = activeRequests[requestId] ?: return@setLocationListener
            if (current.client !== client) return@setLocationListener
            current.destroyClientOnly()
            val result = resultFromLocation(
                location = location,
                startedAt = startedAt,
                successMessage = "高德单次定位返回",
                config = single.config,
                debugExtra = "request=${shortId(requestId)}, attempt=$attempt, origin=${single.origin.orEmpty()}"
            )
            AmapLocationDebug.log(
                appContext,
                "single_callback",
                single.config,
                "request=${shortId(requestId)}, attempt=$attempt, success=${result.success}, error=${result.error?.name ?: "none"}, elapsedMs=${result.elapsedMs}"
            )
            if (retryAuthFailure && isAuthFailure(result)) {
                val retry = Runnable {
                    current.retryRunnable = null
                    if (activeRequests.containsKey(requestId) && runningSingleRequestId == requestId) {
                        AmapLocationDebug.log(
                            appContext,
                            "single_auth_retry",
                            single.config,
                            "request=${shortId(requestId)}, nextAttempt=${attempt + 1}, origin=${single.origin.orEmpty()}"
                        )
                        startSingleAttempt(requestId, current, single, retryAuthFailure = false, attempt = attempt + 1)
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
            dispatchError(
                NativeLocationError.UNKNOWN,
                providerDebugMessage(
                    e.message ?: "启动高德定位失败",
                    single.config,
                    "request=${shortId(requestId)}, attempt=$attempt, origin=${single.origin.orEmpty()}, exception=${e.javaClass.simpleName}"
                ),
                single.callback,
                startedAt
            )
        }
    }

    private fun releaseSingleRequestSlot(requestId: String) {
        if (runningSingleRequestId == requestId) {
            runningSingleRequestId = null
            startNextSingleRequestIfIdle()
        }
    }

    private fun maybeQueueBackgroundRefresh(
        config: WebViewRuntimeConfig,
        timeoutMs: Long,
        origin: String?,
        cacheAgeMs: Long?,
        refreshAfterCache: Boolean
    ) {
        if (!refreshAfterCache) return
        val age = cacheAgeMs ?: return
        if (age < MIN_BACKGROUND_REFRESH_CACHE_AGE_MS) return
        val now = System.currentTimeMillis()
        if (now - lastBackgroundRefreshQueuedAtMs < BACKGROUND_REFRESH_THROTTLE_MS) return
        lastBackgroundRefreshQueuedAtMs = now
        enqueueSingleRequest(
            config = config,
            timeoutMs = timeoutMs,
            allowCached = false,
            refreshAfterCache = false,
            origin = origin,
            callback = null
        )
    }

    override fun startWatch(
        config: WebViewRuntimeConfig,
        origin: String?,
        callback: (NativeLocationResult) -> Unit
    ): String {
        if (!isUsable(config)) {
            AmapLocationDebug.log(
                appContext,
                "watch_unusable",
                config,
                "origin=${origin.orEmpty()}, reason=${availabilityLabel(config)}"
            )
            dispatchError(NativeLocationError.PROVIDER_UNAVAILABLE, availabilityLabel(config), callback)
            return ""
        }

        val startedAt = System.currentTimeMillis()
        val requestId = UUID.randomUUID().toString()
        val client = createClient(config, callback, startedAt, "watch:${shortId(requestId)}") ?: return ""
        val timeout = Runnable {
            cancelRequest(requestId)
            dispatchError(
                NativeLocationError.TIMEOUT,
                providerDebugMessage(
                    "高德 watchPosition 达到最长持续时间",
                    config,
                    "request=${shortId(requestId)}, origin=${origin.orEmpty()}"
                ),
                callback,
                startedAt
            )
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
            dispatch(
                resultFromLocation(
                    location = location,
                    startedAt = startedAt,
                    successMessage = "高德连续定位返回",
                    config = config,
                    debugExtra = "request=${shortId(requestId)}, origin=${origin.orEmpty()}"
                ),
                callback
            )
        }
        client.setLocationOption(option)
        mainHandler.postDelayed(timeout, config.nativeLocationWatchMaxDurationMs)
        runCatching {
            client.startLocation()
        }.onFailure { e ->
            activeRequests.remove(requestId)?.destroy()
            dispatchError(
                NativeLocationError.UNKNOWN,
                providerDebugMessage(
                    e.message ?: "启动高德连续定位失败",
                    config,
                    "request=${shortId(requestId)}, origin=${origin.orEmpty()}, exception=${e.javaClass.simpleName}"
                ),
                callback,
                startedAt
            )
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
        val client = createClient(
            config,
            callback = null,
            startedAt = System.currentTimeMillis(),
            debugSource = "h5_assistant:$normalizedOrigin"
        ) ?: return false
        return runCatching {
            client.startAssistantLocation(webView)
            assistantClients[webView] = client
            AmapLocationDebug.log(appContext, "h5_assistant_started", config, "origin=$normalizedOrigin")
            true
        }.getOrElse { e ->
            lastDiagnostic = providerDebugMessage(
                "H5辅助定位启动失败: ${e.message ?: e.javaClass.simpleName}",
                config,
                "origin=$normalizedOrigin, exception=${e.javaClass.simpleName}"
            )
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
        startedAt: Long,
        debugSource: String
    ): AMapLocationClient? {
        return runCatching {
            AmapLocationDebug.log(
                appContext,
                "create_client_start",
                config,
                "source=$debugSource, lastConfigured=${configuredKeyLabel()}"
            )
            AMapLocationClient.updatePrivacyShow(appContext, true, true)
            AMapLocationClient.updatePrivacyAgree(appContext, true)
            configureApiKey(config.amapLocationApiKey, force = true)
            AMapLocationClient(appContext).also {
                AmapLocationDebug.log(
                    appContext,
                    "create_client_success",
                    config,
                    "source=$debugSource, lastConfigured=${configuredKeyLabel()}"
                )
            }
        }.getOrElse { e ->
            val debugMessage = providerDebugMessage(
                e.message ?: "初始化高德定位客户端失败",
                config,
                "source=$debugSource, exception=${e.javaClass.simpleName}, lastConfigured=${configuredKeyLabel()}"
            )
            callback?.let {
                dispatchError(
                    NativeLocationError.UNKNOWN,
                    debugMessage,
                    it,
                    startedAt
                )
            }
            AmapLocationDebug.log(appContext, "create_client_failed", config, debugMessage)
            null
        }
    }

    companion object : AmapLocationApiKeyConfigurator {
        private const val AUTH_FAILURE_RETRY_DELAY_MS = 450L
        private const val MIN_BACKGROUND_REFRESH_CACHE_AGE_MS = 5_000L
        private const val BACKGROUND_REFRESH_THROTTLE_MS = 15_000L

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

        fun configuredKeyLabel(): String = AmapLocationDebug.keyLabel(lastConfiguredApiKey)
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
        successMessage: String,
        config: WebViewRuntimeConfig,
        debugExtra: String
    ): NativeLocationResult {
        if (location == null) {
            return NativeLocationResult(
                success = false,
                provider = "amap",
                elapsedMs = System.currentTimeMillis() - startedAt,
                error = NativeLocationError.PROVIDER_UNAVAILABLE,
                message = providerDebugMessage("高德定位返回空结果", config, debugExtra)
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
                message = providerDebugMessage(amapFailureMessage(location), config, debugExtra)
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
            message = providerDebugMessage("$successMessage type=${location.locationType}", config, debugExtra)
        )
    }

    private fun dispatchError(
        error: NativeLocationError,
        message: String,
        callback: ((NativeLocationResult) -> Unit)?,
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

    private fun dispatch(result: NativeLocationResult, callback: ((NativeLocationResult) -> Unit)?) {
        lastDiagnostic = result.toDiagnosticLine(redactCoordinates = true)
        if (result.success && result.provider == "amap" && !result.cached) {
            lastSuccessResult = result.copy(cached = false, cacheAgeMillis = 0L)
        }
        if (callback == null) return
        if (Looper.myLooper() == Looper.getMainLooper()) {
            callback(result)
        } else {
            mainHandler.post { callback(result) }
        }
    }

    private fun cachedResult(maxAgeMs: Long): NativeLocationResult? {
        if (maxAgeMs <= 0L) return null
        val result = lastSuccessResult ?: return null
        val ageMs = cacheAgeMillis(result) ?: return null
        if (ageMs !in 0..maxAgeMs) return null
        return result.copy(
            cached = true,
            cacheAgeMillis = ageMs,
            elapsedMs = 0L,
            message = result.message.stripProviderDebug().ifBlank { "命中近期高德定位缓存" }
        )
    }

    private fun cacheAgeMillis(result: NativeLocationResult): Long? {
        result.elapsedRealtimeNanos
            ?.takeIf { it > 0L }
            ?.let { return ((android.os.SystemClock.elapsedRealtimeNanos() - it) / 1_000_000L).coerceAtLeast(0L) }
        result.wallTimeMillis
            ?.takeIf { it > 0L }
            ?.let { return (System.currentTimeMillis() - it).coerceAtLeast(0L) }
        return null
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

    private fun providerDebugMessage(message: String, config: WebViewRuntimeConfig, extra: String): String {
        return "$message；SDK调试: $extra, configuredKey=${configuredKeyLabel()}, requestKey=${AmapLocationDebug.keyLabel(config.amapLocationApiKey)}"
    }

    private fun shortId(id: String): String = id.take(8)

    private fun String.stripProviderDebug(): String {
        return substringBefore("；SDK调试:")
            .substringBefore("; SDK debug:")
            .trim()
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
        val refreshAfterCache: Boolean,
        val origin: String?,
        val callback: ((NativeLocationResult) -> Unit)?
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
