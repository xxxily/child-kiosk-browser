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
import androidx.core.content.ContextCompat
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor

enum class NativeLocationError {
    DISABLED,
    PERMISSION_DENIED,
    PROVIDER_UNAVAILABLE,
    TIMEOUT,
    CANCELLED,
    UNKNOWN
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
            appendLine("活跃请求数: ${activeRequests.size}")
            appendLine("最近注册: ${formatRelativeTime(lastListenerRegisteredAtMs)}")
            appendLine("最近释放: ${formatRelativeTime(lastListenerReleasedAtMs)}")
        }.trim()
    }

    fun requestSingleLocation(
        config: WebViewRuntimeConfig,
        timeoutMs: Long = config.nativeLocationRequestTimeoutMs,
        allowCached: Boolean = true,
        purpose: String = "single",
        callback: (NativeLocationResult) -> Unit
    ): String {
        val startedAt = System.currentTimeMillis()
        val denied = preflightDenied(startedAt)
        if (denied != null) {
            recordAndDispatch(denied, callback)
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
                recordAndDispatch(result, callback)
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
            recordAndDispatch(result, callback)
            return ""
        }

        val requestId = UUID.randomUUID().toString()
        val active = ActiveRequest(callback = callback)
        activeRequests[requestId] = active

        fun finish(result: NativeLocationResult) {
            val removed = activeRequests.remove(requestId) ?: return
            removed.cancel()
            recordAndDispatch(result, removed.callback)
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
            purpose = "warmup:$origin"
        ) { result ->
            callback?.invoke(result)
        }
    }

    @SuppressLint("MissingPermission")
    fun startWatch(
        config: WebViewRuntimeConfig,
        intervalMs: Long = 5_000L,
        callback: (NativeLocationResult) -> Unit
    ): String {
        val startedAt = System.currentTimeMillis()
        val denied = preflightDenied(startedAt)
        if (denied != null) {
            recordAndDispatch(denied, callback)
            return ""
        }
        val provider = providerOrder(config).firstOrNull()
        if (provider == null) {
            recordAndDispatch(
                errorResult(NativeLocationError.PROVIDER_UNAVAILABLE, "没有可用系统定位 provider", startedAt),
                callback
            )
            return ""
        }

        val watchId = UUID.randomUUID().toString()
        val active = ActiveRequest(callback = callback)
        activeRequests[watchId] = active
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                recordAndDispatch(
                    resultFromLocation(location, cached = false, startedAt = startedAt, message = "watchPosition 更新"),
                    callback
                )
            }

            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
            override fun onProviderEnabled(provider: String) = Unit
            override fun onProviderDisabled(provider: String) {
                recordAndDispatch(
                    errorResult(NativeLocationError.PROVIDER_UNAVAILABLE, "Provider 已关闭: $provider", startedAt),
                    callback
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
                errorResult(NativeLocationError.UNKNOWN, it.message ?: "启动持续定位失败", startedAt),
                callback
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
            active.callback
        )
    }

    fun cancelRequest(id: String) {
        val active = activeRequests.remove(id) ?: return
        active.cancel()
    }

    fun stopAll() {
        activeRequests.keys.toList().forEach { id ->
            val active = activeRequests.remove(id) ?: return@forEach
            active.cancel()
        }
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

    private fun recordAndDispatch(result: NativeLocationResult, callback: (NativeLocationResult) -> Unit) {
        lastResult = result
        callback(result)
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
}
