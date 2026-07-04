package site.anzz.childkiosk.util

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.Executors

enum class NativeLocationError {
    DISABLED,
    PERMISSION_DENIED,
    PROVIDER_UNAVAILABLE,
    TIMEOUT,
    CANCELLED,
    UNKNOWN
}

object NativeLocationCoordinateSystem {
    const val WGS84 = "WGS84"
    const val GCJ02 = "GCJ02"
    const val UNKNOWN = "UNKNOWN"
}

data class NativeLocationResult(
    val success: Boolean,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val accuracyMeters: Float? = null,
    val altitude: Double? = null,
    val bearing: Float? = null,
    val speed: Float? = null,
    val elapsedRealtimeNanos: Long? = null,
    val wallTimeMillis: Long? = null,
    val provider: String? = null,
    val coordinateSystem: String = NativeLocationCoordinateSystem.UNKNOWN,
    val cached: Boolean = false,
    val cacheAgeMillis: Long? = null,
    val precisePermission: Boolean = false,
    val elapsedMs: Long = 0L,
    val error: NativeLocationError? = null,
    val message: String = ""
) {
    fun toDiagnosticLine(redactCoordinates: Boolean = true): String {
        val status = if (success) "成功" else "失败"
        val locationText = if (!success || redactCoordinates || latitude == null || longitude == null) {
            "坐标=已隐藏"
        } else {
            "lat=${"%.6f".format(Locale.US, latitude)}, lon=${"%.6f".format(Locale.US, longitude)}"
        }
        return listOf(
            "状态=$status",
            "provider=${provider ?: "无"}",
            "坐标系=$coordinateSystem",
            "耗时=${elapsedMs}ms",
            "精度=${accuracyMeters?.let { "${it.toInt()}m" } ?: "未知"}",
            "缓存=${if (cached) "是" else "否"}",
            "缓存年龄=${cacheAgeMillis?.let { "${it / 1000}s" } ?: "未知"}",
            "权限=${if (precisePermission) "精确" else "近似/未知"}",
            "错误=${error?.name ?: "无"}",
            locationText,
            "说明=${message.ifBlank { "无" }}"
        ).joinToString("，")
    }
}

data class NativeLocationStatus(
    val locationEnabled: Boolean,
    val fineGranted: Boolean,
    val coarseGranted: Boolean,
    val providers: List<String>,
    val lastDiagnostic: String
) {
    fun permissionLabel(): String {
        return when {
            fineGranted -> "精确位置"
            coarseGranted -> "近似位置"
            else -> "未授权"
        }
    }

    fun enabledLabel(): String = if (locationEnabled) "已开启" else "已关闭"
}

class NativeLocationManager(private val context: Context) {
    private val appContext = context.applicationContext
    private val locationManager = appContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val mainExecutor = Executor { command -> mainHandler.post(command) }
    private val activeRequests = ConcurrentHashMap<String, ActiveRequest>()
    private val routedRequests = ConcurrentHashMap<String, RoutedRequest>()
    private val amapProvider = AmapLocationProviderFactory.create(appContext)

    @Volatile
    private var lastResult: NativeLocationResult? = null
    @Volatile
    private var lastListenerRegisteredAtMs: Long = 0L
    @Volatile
    private var lastListenerReleasedAtMs: Long = 0L

    fun status(): NativeLocationStatus {
        return NativeLocationStatus(
            locationEnabled = isLocationEnabled(),
            fineGranted = hasFinePermission(),
            coarseGranted = hasCoarsePermission(),
            providers = availableProviders(),
            lastDiagnostic = lastResult?.toDiagnosticLine(redactCoordinates = true) ?: "暂无定位诊断"
        )
    }

    fun diagnosticSummary(): String = status().let { status ->
        buildString {
            appendLine("系统定位: ${status.enabledLabel()}")
            appendLine("应用权限: ${status.permissionLabel()}")
            appendLine("可用 Provider: ${status.providers.ifEmpty { listOf("无") }.joinToString()}")
            appendLine("最近一次: ${status.lastDiagnostic}")
            appendLine("高德 SDK: ${amapProvider.diagnosticSummary(KioskPrefs.getWebViewRuntimeConfig(appContext))}")
            appendLine("活跃请求数: ${activeRequests.size}")
            appendLine("路由请求数: ${routedRequests.size}")
            appendLine("最近注册: ${formatRelativeTime(lastListenerRegisteredAtMs)}")
            appendLine("最近释放: ${formatRelativeTime(lastListenerReleasedAtMs)}")
            appendLine("最近定位记录:")
            appendLine(NativeLocationAuditStore.summary(appContext, limit = 20).prependIndent("  "))
        }.trim()
    }

    fun requestSingleLocation(
        config: WebViewRuntimeConfig,
        timeoutMs: Long = config.nativeLocationRequestTimeoutMs,
        allowCached: Boolean = true,
        purpose: String = "single",
        origin: String? = null,
        callback: (NativeLocationResult) -> Unit
    ): String {
        if (shouldUseAmap(config)) {
            return requestAmapFirstLocation(config, timeoutMs, allowCached, purpose, origin, callback)
        }
        return requestSystemSingleLocation(config, timeoutMs, allowCached, purpose, origin, callback)
    }

    private fun requestAmapFirstLocation(
        config: WebViewRuntimeConfig,
        timeoutMs: Long,
        allowCached: Boolean,
        purpose: String,
        origin: String?,
        callback: (NativeLocationResult) -> Unit
    ): String {
        val strategy = config.amapLocationProviderStrategy
        if (!amapProvider.isUsable(config)) {
            if (strategy == KioskPrefs.NATIVE_LOCATION_PROVIDER_AMAP_ONLY) {
                val result = NativeLocationResult(
                    success = false,
                    provider = "amap",
                    error = NativeLocationError.PROVIDER_UNAVAILABLE,
                    message = amapProvider.availabilityLabel(config)
                )
                recordAndDispatch(result, callback, purpose, origin)
                return ""
            }
            return requestSystemSingleLocation(config, timeoutMs, allowCached, purpose, origin, callback)
        }

        val routeId = UUID.randomUUID().toString()
        val route = RoutedRequest()
        routedRequests[routeId] = route

        fun deliver(result: NativeLocationResult) {
            routedRequests.remove(routeId)
            recordAndDispatch(resultForWeb(result, config, origin), callback, purpose, origin)
        }

        fun fallbackToSystem(amapFailure: NativeLocationResult) {
            if (strategy == KioskPrefs.NATIVE_LOCATION_PROVIDER_AMAP_ONLY || !shouldFallbackFromAmap(amapFailure)) {
                deliver(amapFailure)
                return
            }
            NativeLocationAuditStore.record(appContext, "amap_fallback:$purpose", origin, amapFailure)
            val systemId = requestSystemSingleLocation(
                config = config,
                timeoutMs = timeoutMs,
                allowCached = allowCached,
                purpose = "fallback_after_amap:$purpose",
                origin = origin
            ) { systemResult ->
                deliver(systemResult.copy(message = "${systemResult.message.ifBlank { "系统定位返回" }}；高德回退原因: ${amapFailure.message}"))
            }
            route.systemId = systemId
            if (systemId.isBlank()) {
                routedRequests.remove(routeId)
            }
        }

        val amapId = amapProvider.requestSingleLocation(
            config = config,
            timeoutMs = timeoutMs,
            allowCached = allowCached,
            origin = origin
        ) { result ->
            if (!routedRequests.containsKey(routeId)) return@requestSingleLocation
            if (result.success) {
                deliver(result)
            } else {
                fallbackToSystem(result)
            }
        }
        route.amapId = amapId.takeIf { it.isNotBlank() }
        if (!routedRequests.containsKey(routeId)) return ""
        return routeId
    }

    private fun requestSystemSingleLocation(
        config: WebViewRuntimeConfig,
        timeoutMs: Long = config.nativeLocationRequestTimeoutMs,
        allowCached: Boolean = true,
        purpose: String = "single",
        origin: String? = null,
        callback: (NativeLocationResult) -> Unit
    ): String {
        val startedAt = System.currentTimeMillis()
        val denied = preflightDenied(startedAt)
        if (denied != null) {
            recordAndDispatch(resultForWeb(denied, config, origin), callback, purpose, origin)
            return ""
        }

        if (allowCached) {
            val cached = bestCachedLocation(config.nativeLocationMaxCacheAgeMs)
            if (cached != null) {
                val result = resultFromLocation(
                    location = cached,
                    cached = true,
                    startedAt = startedAt,
                    message = "命中近期系统定位缓存"
                )
                recordAndDispatch(resultForWeb(result, config, origin), callback, purpose, origin)
                return ""
            }
        }

        val providers = providerOrder(config)
        if (providers.isEmpty()) {
            val result = errorResult(
                NativeLocationError.PROVIDER_UNAVAILABLE,
                "没有可用系统定位 provider",
                startedAt
            )
            recordAndDispatch(resultForWeb(result, config, origin), callback, purpose, origin)
            return ""
        }

        val requestId = UUID.randomUUID().toString()
        val active = ActiveRequest(callback = callback)
        activeRequests[requestId] = active

        fun finish(result: NativeLocationResult) {
            val removed = activeRequests.remove(requestId) ?: return
            removed.cancel()
            recordAndDispatch(resultForWeb(result, config, origin), removed.callback, purpose, origin)
        }

        val timeout = Runnable {
            finish(errorResult(NativeLocationError.TIMEOUT, "系统定位请求超时: $purpose", startedAt))
        }
        active.timeoutRunnable = timeout
        mainHandler.postDelayed(timeout, timeoutMs.coerceAtLeast(1_000L))

        startProviderRequest(
            providers = providers,
            index = 0,
            requestId = requestId,
            config = config,
            startedAt = startedAt,
            finish = ::finish
        )

        return requestId
    }

    fun warmup(
        config: WebViewRuntimeConfig,
        origin: String,
        callback: ((NativeLocationResult) -> Unit)? = null
    ): String {
        if (!config.nativeLocationOptimizationEnabled || !config.nativeLocationWarmupEnabled) return ""
        return requestSingleLocation(
            config = config,
            timeoutMs = config.nativeLocationWarmupTimeoutMs,
            allowCached = false,
            purpose = "warmup:$origin",
            origin = origin
        ) { result ->
            callback?.invoke(result)
        }
    }

    @SuppressLint("MissingPermission")
    fun startWatch(
        config: WebViewRuntimeConfig,
        intervalMs: Long = 5_000L,
        origin: String? = null,
        callback: (NativeLocationResult) -> Unit
    ): String {
        if (shouldUseAmap(config) && amapProvider.isUsable(config)) {
            var suppressStartupFailure = true
            var startupFailure: NativeLocationResult? = null
            val watchId = amapProvider.startWatch(config, origin) amapCallback@{ result ->
                if (suppressStartupFailure && !result.success) {
                    startupFailure = result
                    return@amapCallback
                }
                recordAndDispatch(resultForWeb(result, config, origin), callback, "watch", origin)
            }
            suppressStartupFailure = false
            if (watchId.isNotBlank()) {
                return "amap:$watchId"
            }
            if (config.amapLocationProviderStrategy == KioskPrefs.NATIVE_LOCATION_PROVIDER_AMAP_ONLY) {
                val result = startupFailure ?: NativeLocationResult(
                    success = false,
                    provider = "amap",
                    error = NativeLocationError.PROVIDER_UNAVAILABLE,
                    message = amapProvider.availabilityLabel(config)
                )
                recordAndDispatch(resultForWeb(result, config, origin), callback, "watch", origin)
                return ""
            }
        } else if (config.amapLocationProviderStrategy == KioskPrefs.NATIVE_LOCATION_PROVIDER_AMAP_ONLY) {
            recordAndDispatch(
                NativeLocationResult(
                    success = false,
                    provider = "amap",
                    error = NativeLocationError.PROVIDER_UNAVAILABLE,
                    message = amapProvider.availabilityLabel(config)
                ),
                callback,
                "watch",
                origin
            )
            return ""
        }

        val startedAt = System.currentTimeMillis()
        val denied = preflightDenied(startedAt)
        if (denied != null) {
            recordAndDispatch(resultForWeb(denied, config, origin), callback, "watch", origin)
            return ""
        }
        val provider = providerOrder(config).firstOrNull()
        if (provider == null) {
            recordAndDispatch(
                resultForWeb(
                    errorResult(NativeLocationError.PROVIDER_UNAVAILABLE, "没有可用系统定位 provider", startedAt),
                    config,
                    origin
                ),
                callback,
                "watch",
                origin
            )
            return ""
        }

        val watchId = UUID.randomUUID().toString()
        val active = ActiveRequest(callback = callback)
        activeRequests[watchId] = active
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                recordAndDispatch(
                    resultForWeb(
                        resultFromLocation(location, cached = false, startedAt = startedAt, message = "watchPosition 更新"),
                        config,
                        origin
                    ),
                    callback,
                    "watch",
                    origin
                )
            }

            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
            override fun onProviderEnabled(provider: String) = Unit
            override fun onProviderDisabled(provider: String) {
                recordAndDispatch(
                    resultForWeb(
                        errorResult(NativeLocationError.PROVIDER_UNAVAILABLE, "Provider 已关闭: $provider", startedAt),
                        config,
                        origin
                    ),
                    callback,
                    "watch",
                    origin
                )
            }
        }
        active.listener = listener
        active.provider = provider
        val timeout = Runnable {
            stopRequest(watchId, NativeLocationError.TIMEOUT, "watchPosition 达到最长持续时间")
        }
        active.timeoutRunnable = timeout
        mainHandler.postDelayed(timeout, config.nativeLocationWatchMaxDurationMs)

        runCatching {
            lastListenerRegisteredAtMs = System.currentTimeMillis()
            locationManager.requestLocationUpdates(provider, intervalMs.coerceAtLeast(1_000L), 0f, listener, Looper.getMainLooper())
        }.onFailure {
            activeRequests.remove(watchId)
            active.cancel()
            recordAndDispatch(
                resultForWeb(
                    errorResult(NativeLocationError.UNKNOWN, it.message ?: "启动持续定位失败", startedAt),
                    config,
                    origin
                ),
                callback,
                "watch",
                origin
            )
            return ""
        }
        return watchId
    }

    fun stopRequest(id: String, error: NativeLocationError = NativeLocationError.CANCELLED, message: String = "定位请求已取消") {
        val active = activeRequests.remove(id) ?: return
        active.cancel()
        recordAndDispatch(
            errorResult(error, message, System.currentTimeMillis()),
            active.callback,
            "cancel",
            null
        )
    }

    fun cancelRequest(id: String) {
        if (id.startsWith("amap:")) {
            amapProvider.cancelRequest(id.removePrefix("amap:"))
            return
        }
        routedRequests.remove(id)?.let { route ->
            route.amapId?.let { amapProvider.cancelRequest(it) }
            route.systemId?.let { cancelRequest(it) }
            return
        }
        val active = activeRequests.remove(id) ?: return
        active.cancel()
    }

    fun stopAll() {
        routedRequests.clear()
        amapProvider.stopAll()
        amapProvider.stopAllAssistantLocations()
        activeRequests.keys.toList().forEach { id ->
            val active = activeRequests.remove(id) ?: return@forEach
            active.cancel()
        }
    }

    fun destroy() {
        stopAll()
        amapProvider.destroy()
    }

    fun startAmapAssistantLocation(webView: WebView, config: WebViewRuntimeConfig, origin: String): Boolean {
        return amapProvider.startAssistantLocation(webView, config, origin)
    }

    fun stopAmapAssistantLocation(webView: WebView) {
        amapProvider.stopAssistantLocation(webView)
    }

    fun recordAuditOnly(
        purpose: String,
        origin: String?,
        result: NativeLocationResult
    ) {
        NativeLocationAuditStore.record(appContext, purpose, origin, result)
    }

    @SuppressLint("MissingPermission")
    private fun startProviderRequest(
        providers: List<String>,
        index: Int,
        requestId: String,
        config: WebViewRuntimeConfig,
        startedAt: Long,
        finish: (NativeLocationResult) -> Unit
    ) {
        val active = activeRequests[requestId] ?: return
        val provider = providers.getOrNull(index)
        if (provider == null) {
            finish(errorResult(NativeLocationError.PROVIDER_UNAVAILABLE, "所有 provider 都不可用", startedAt))
            return
        }

        active.cancelSignal?.cancel()
        active.cancelSignal = null
        active.listener?.let { runCatching { locationManager.removeUpdates(it) } }
        active.listener = null
        active.provider = null

        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val signal = CancellationSignal()
                active.cancelSignal = signal
                locationManager.getCurrentLocation(provider, signal, mainExecutor) { location ->
                    if (!activeRequests.containsKey(requestId)) return@getCurrentLocation
                    if (location != null && accepts(location, config)) {
                        finish(resultFromLocation(location, cached = false, startedAt = startedAt, message = "单次定位成功"))
                    } else {
                        startProviderRequest(providers, index + 1, requestId, config, startedAt, finish)
                    }
                }
            } else {
                val listener = object : LocationListener {
                    override fun onLocationChanged(location: Location) {
                        if (!activeRequests.containsKey(requestId)) return
                        if (accepts(location, config)) {
                            finish(resultFromLocation(location, cached = false, startedAt = startedAt, message = "单次定位成功"))
                        }
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
                    override fun onProviderEnabled(provider: String) = Unit
                    override fun onProviderDisabled(provider: String) {
                        startProviderRequest(providers, index + 1, requestId, config, startedAt, finish)
                    }
                }
                active.listener = listener
                active.provider = provider
                lastListenerRegisteredAtMs = System.currentTimeMillis()
                locationManager.requestLocationUpdates(provider, 0L, 0f, listener, Looper.getMainLooper())
            }
        }.onFailure {
            startProviderRequest(providers, index + 1, requestId, config, startedAt, finish)
        }
    }

    @SuppressLint("MissingPermission")
    private fun bestCachedLocation(maxAgeMs: Long): Location? {
        if (maxAgeMs <= 0L) return null
        val nowElapsed = android.os.SystemClock.elapsedRealtimeNanos()
        return providerOrderForCache().mapNotNull { provider ->
            runCatching {
                locationManager.getLastKnownLocation(provider)
            }.getOrNull()
        }.filter { location ->
            cacheAgeMillis(location, nowElapsed)?.let { it in 0..maxAgeMs } == true
        }.minWithOrNull(
            compareBy<Location> { it.accuracy.takeIf { accuracy -> accuracy > 0f } ?: Float.MAX_VALUE }
                .thenBy { cacheAgeMillis(it, nowElapsed) ?: Long.MAX_VALUE }
        )
    }

    private fun providerOrder(config: WebViewRuntimeConfig): List<String> {
        val candidates = when {
            !hasFinePermission() -> listOf(LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            config.nativeLocationMode == KioskPrefs.NATIVE_LOCATION_MODE_HIGH_ACCURACY ->
                listOf("fused", LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            config.nativeLocationMode == KioskPrefs.NATIVE_LOCATION_MODE_LOW_POWER ->
                listOf(LocationManager.PASSIVE_PROVIDER, LocationManager.NETWORK_PROVIDER)
            else -> listOf("fused", LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.PASSIVE_PROVIDER)
        }
        return candidates.distinct().filter { providerAvailable(it) }
    }

    private fun providerOrderForCache(): List<String> {
        return listOf("fused", LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            .distinct()
            .filter { providerAvailable(it) }
    }

    private fun availableProviders(): List<String> {
        return listOf("fused", LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            .filter { providerAvailable(it) }
    }

    private fun providerAvailable(provider: String): Boolean {
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                locationManager.hasProvider(provider)
            } else {
                @Suppress("DEPRECATION")
                locationManager.allProviders.contains(provider)
            } && locationManager.isProviderEnabled(provider)
        }.getOrDefault(false)
    }

    private fun accepts(location: Location, config: WebViewRuntimeConfig): Boolean {
        val age = cacheAgeMillis(location, android.os.SystemClock.elapsedRealtimeNanos()) ?: return true
        return age <= config.nativeLocationMaxCacheAgeMs.coerceAtLeast(config.nativeLocationRequestTimeoutMs)
    }

    private fun preflightDenied(startedAt: Long): NativeLocationResult? {
        return when {
            !isLocationEnabled() -> errorResult(NativeLocationError.DISABLED, "系统定位服务已关闭", startedAt)
            !hasAnyLocationPermission() -> errorResult(NativeLocationError.PERMISSION_DENIED, "应用未获得定位权限", startedAt)
            else -> null
        }
    }

    private fun resultFromLocation(
        location: Location,
        cached: Boolean,
        startedAt: Long,
        message: String
    ): NativeLocationResult {
        val age = cacheAgeMillis(location, android.os.SystemClock.elapsedRealtimeNanos())
        return NativeLocationResult(
            success = true,
            latitude = location.latitude,
            longitude = location.longitude,
            accuracyMeters = if (location.hasAccuracy()) location.accuracy else null,
            altitude = if (location.hasAltitude()) location.altitude else null,
            bearing = if (location.hasBearing()) location.bearing else null,
            speed = if (location.hasSpeed()) location.speed else null,
            elapsedRealtimeNanos = location.elapsedRealtimeNanos,
            wallTimeMillis = location.time,
            provider = location.provider,
            coordinateSystem = NativeLocationCoordinateSystem.WGS84,
            cached = cached,
            cacheAgeMillis = age,
            precisePermission = hasFinePermission(),
            elapsedMs = System.currentTimeMillis() - startedAt,
            message = message
        )
    }

    private fun errorResult(error: NativeLocationError, message: String, startedAt: Long): NativeLocationResult {
        return NativeLocationResult(
            success = false,
            precisePermission = hasFinePermission(),
            elapsedMs = (System.currentTimeMillis() - startedAt).coerceAtLeast(0L),
            error = error,
            message = message
        )
    }

    private fun recordAndDispatch(
        result: NativeLocationResult,
        callback: (NativeLocationResult) -> Unit,
        purpose: String = "unknown",
        origin: String? = null
    ) {
        lastResult = result
        NativeLocationAuditStore.record(appContext, purpose, origin, result)
        callback(result)
    }

    private fun shouldUseAmap(config: WebViewRuntimeConfig): Boolean {
        return config.amapLocationProviderStrategy != KioskPrefs.NATIVE_LOCATION_PROVIDER_SYSTEM &&
            config.amapLocationEnabled
    }

    private fun shouldFallbackFromAmap(result: NativeLocationResult): Boolean {
        return result.error != NativeLocationError.PERMISSION_DENIED &&
            result.error != NativeLocationError.DISABLED
    }

    private fun resultForWeb(
        result: NativeLocationResult,
        config: WebViewRuntimeConfig,
        origin: String?
    ): NativeLocationResult {
        if (!result.success) return result
        if (result.coordinateSystem != NativeLocationCoordinateSystem.GCJ02) return result
        val normalizedOrigin = origin?.let { KioskPrefs.normalizeOriginKey(it) }.orEmpty()
        val allowGcj02 = config.nativeLocationCoordinateMode == KioskPrefs.NATIVE_LOCATION_COORDINATE_GCJ02_PER_SITE &&
            normalizedOrigin.isNotBlank() &&
            config.nativeLocationGcj02AllowedOrigins.contains(normalizedOrigin)
        if (allowGcj02) return result
        val lat = result.latitude ?: return result
        val lon = result.longitude ?: return result
        val converted = CoordinateTransforms.gcj02ToWgs84(lat, lon)
        return result.copy(
            latitude = converted.latitude,
            longitude = converted.longitude,
            coordinateSystem = NativeLocationCoordinateSystem.WGS84,
            message = "${result.message.ifBlank { "定位成功" }}；坐标已转换为 WGS84"
        )
    }

    private fun isLocationEnabled(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            locationManager.isLocationEnabled
        } else {
            @Suppress("DEPRECATION")
            runCatching {
                locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                    locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
            }.getOrDefault(false)
        }
    }

    private fun hasAnyLocationPermission(): Boolean = hasFinePermission() || hasCoarsePermission()

    private fun hasFinePermission(): Boolean {
        return ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun hasCoarsePermission(): Boolean {
        return ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun cacheAgeMillis(location: Location, nowElapsedNanos: Long): Long? {
        val elapsed = location.elapsedRealtimeNanos
        if (elapsed <= 0L) return null
        return ((nowElapsedNanos - elapsed) / 1_000_000L).coerceAtLeast(0L)
    }

    private fun formatRelativeTime(timestampMs: Long): String {
        if (timestampMs <= 0L) return "无"
        val ageSeconds = ((System.currentTimeMillis() - timestampMs).coerceAtLeast(0L) / 1000L)
        return "${ageSeconds}s 前"
    }

    private inner class ActiveRequest(
        val callback: (NativeLocationResult) -> Unit
    ) {
        var provider: String? = null
        var listener: LocationListener? = null
        var cancelSignal: CancellationSignal? = null
        var timeoutRunnable: Runnable? = null

        fun cancel() {
            cancelSignal?.cancel()
            listener?.let {
                runCatching { locationManager.removeUpdates(it) }
                lastListenerReleasedAtMs = System.currentTimeMillis()
            }
            timeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        }
    }

    private class RoutedRequest {
        @Volatile
        var amapId: String? = null
        @Volatile
        var systemId: String? = null
    }
}

private object NativeLocationAuditStore {
    private const val FILE_NAME = "native_location_audit.jsonl"
    private const val MAX_RECORDS = 80
    private const val MAX_FILE_BYTES = 96 * 1024L
    private const val MAX_MESSAGE_CHARS = 220
    private val executor = Executors.newSingleThreadExecutor()
    private val recentLines = ArrayDeque<String>()
    private val lock = Any()
    private val timeFormat = ThreadLocal.withInitial {
        SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA)
    }

    fun record(context: Context, purpose: String, origin: String?, result: NativeLocationResult) {
        val appContext = context.applicationContext
        val json = JSONObject()
            .put("time", System.currentTimeMillis())
            .put("origin", origin.orEmpty().ifBlank { originFromPurpose(purpose) }.ifBlank { "app/internal" })
            .put("purpose", purposeLabel(purpose))
            .put("success", result.success)
            .put("provider", result.provider ?: "无")
            .put("accuracy", result.accuracyMeters?.toInt() ?: -1)
            .put("elapsed", result.elapsedMs)
            .put("cached", result.cached)
            .put("coordinateSystem", result.coordinateSystem)
            .put("error", result.error?.name ?: "无")
            .put("message", sanitizeMessage(result.message))

        val line = json.toString()
        synchronized(lock) {
            recentLines.addLast(line)
            while (recentLines.size > MAX_RECORDS) {
                recentLines.removeFirst()
            }
        }

        executor.execute {
            runCatching {
                val file = auditFile(appContext)
                file.parentFile?.mkdirs()
                file.appendText(line + "\n")
                if (file.length() > MAX_FILE_BYTES) {
                    trim(file)
                }
            }
        }
    }

    fun summary(context: Context, limit: Int): String {
        val file = auditFile(context.applicationContext)
        val boundedLimit = limit.coerceAtLeast(1)
        val memoryLines = synchronized(lock) { recentLines.toList() }
        val fileLines = if (file.exists()) {
            runCatching {
                file.readLines().filter { it.isNotBlank() }
            }.getOrDefault(emptyList())
        } else {
            emptyList()
        }
        val lines = (fileLines + memoryLines)
            .distinct()
            .takeLast(boundedLimit)
        if (lines.isEmpty()) return "暂无定位记录"

        return lines.asReversed().mapIndexed { index, line ->
            runCatching {
                val json = JSONObject(line)
                val time = timeFormat.get()?.format(Date(json.optLong("time"))) ?: "未知时间"
                val status = if (json.optBoolean("success")) "成功" else "失败"
                val accuracy = json.optInt("accuracy", -1).takeIf { it >= 0 }?.let { "${it}m" } ?: "未知"
                val cached = if (json.optBoolean("cached")) "缓存" else "实时"
                val message = json.optString("message", "").takeIf { it.isNotBlank() }?.let { "，说明=$it" }.orEmpty()
                "${index + 1}. $time，来源=${json.optString("origin", "未知")}，类型=${json.optString("purpose", "未知")}，状态=$status，provider=${json.optString("provider", "无")}，精度=$accuracy，耗时=${json.optLong("elapsed")}ms，$cached，错误=${json.optString("error", "无")}$message"
            }.getOrElse {
                "${index + 1}. 记录解析失败"
            }
        }.joinToString("\n")
    }

    private fun auditFile(context: Context): File = File(context.filesDir, FILE_NAME)

    private fun trim(file: File) {
        val lines = file.readLines().filter { it.isNotBlank() }.takeLast(MAX_RECORDS)
        file.writeText(lines.joinToString(separator = "\n", postfix = "\n"))
    }

    private fun originFromPurpose(purpose: String): String {
        return purpose.substringAfter("bridge_get:", missingDelimiterValue = "")
            .ifBlank { purpose.substringAfter("warmup:", missingDelimiterValue = "") }
            .substringBefore(" ")
            .trim()
    }

    private fun purposeLabel(purpose: String): String {
        return when {
            purpose.startsWith("bridge_get:") -> "网页单次定位"
            purpose.startsWith("fallback_after_amap:") -> "高德失败后系统回退"
            purpose.startsWith("amap_fallback:") -> "高德失败"
            purpose.startsWith("warmup:") -> "页面预热"
            purpose == "watch" -> "网页持续定位"
            purpose == "watch_denied_limit" -> "网页持续定位被拒绝"
            purpose == "watch_denied_permission" -> "网页持续定位权限拒绝"
            purpose == "watch_denied_policy" -> "网页持续定位策略拒绝"
            purpose == "bridge_get_denied_permission" -> "网页单次定位权限拒绝"
            purpose == "bridge_get_denied_policy" -> "网页单次定位策略拒绝"
            purpose == "admin_test" -> "后台测试"
            purpose == "cancel" -> "定位取消"
            else -> purpose.ifBlank { "未知" }
        }
    }

    private fun sanitizeMessage(message: String): String {
        return message
            .replace(Regex("#gsid#[^#，\\s]+"), "#gsid#<hidden>")
            .replace(Regex("#csid#[^#，\\s]+"), "#csid#<hidden>")
            .take(MAX_MESSAGE_CHARS)
    }
}

data class CoordinatePoint(val latitude: Double, val longitude: Double)

object CoordinateTransforms {
    private const val PI = 3.1415926535897932384626
    private const val A = 6378245.0
    private const val EE = 0.00669342162296594323

    fun gcj02ToWgs84(latitude: Double, longitude: Double): CoordinatePoint {
        if (outOfChina(latitude, longitude)) return CoordinatePoint(latitude, longitude)
        val delta = delta(latitude, longitude)
        return CoordinatePoint(latitude * 2 - delta.latitude, longitude * 2 - delta.longitude)
    }

    fun outOfChina(latitude: Double, longitude: Double): Boolean {
        return longitude < 72.004 || longitude > 137.8347 || latitude < 0.8293 || latitude > 55.8271
    }

    private fun delta(latitude: Double, longitude: Double): CoordinatePoint {
        var dLat = transformLat(longitude - 105.0, latitude - 35.0)
        var dLon = transformLon(longitude - 105.0, latitude - 35.0)
        val radLat = latitude / 180.0 * PI
        var magic = kotlin.math.sin(radLat)
        magic = 1 - EE * magic * magic
        val sqrtMagic = kotlin.math.sqrt(magic)
        dLat = (dLat * 180.0) / ((A * (1 - EE)) / (magic * sqrtMagic) * PI)
        dLon = (dLon * 180.0) / (A / sqrtMagic * kotlin.math.cos(radLat) * PI)
        return CoordinatePoint(latitude + dLat, longitude + dLon)
    }

    private fun transformLat(x: Double, y: Double): Double {
        var ret = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y +
            0.2 * kotlin.math.sqrt(kotlin.math.abs(x))
        ret += (20.0 * kotlin.math.sin(6.0 * x * PI) + 20.0 * kotlin.math.sin(2.0 * x * PI)) * 2.0 / 3.0
        ret += (20.0 * kotlin.math.sin(y * PI) + 40.0 * kotlin.math.sin(y / 3.0 * PI)) * 2.0 / 3.0
        ret += (160.0 * kotlin.math.sin(y / 12.0 * PI) + 320 * kotlin.math.sin(y * PI / 30.0)) * 2.0 / 3.0
        return ret
    }

    private fun transformLon(x: Double, y: Double): Double {
        var ret = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y +
            0.1 * kotlin.math.sqrt(kotlin.math.abs(x))
        ret += (20.0 * kotlin.math.sin(6.0 * x * PI) + 20.0 * kotlin.math.sin(2.0 * x * PI)) * 2.0 / 3.0
        ret += (20.0 * kotlin.math.sin(x * PI) + 40.0 * kotlin.math.sin(x / 3.0 * PI)) * 2.0 / 3.0
        ret += (150.0 * kotlin.math.sin(x / 12.0 * PI) + 300.0 * kotlin.math.sin(x / 30.0 * PI)) * 2.0 / 3.0
        return ret
    }
}
