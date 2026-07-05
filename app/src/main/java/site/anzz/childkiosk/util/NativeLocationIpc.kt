package site.anzz.childkiosk.util

import android.os.Bundle

object NativeLocationIpc {
    const val MSG_REQUEST_SINGLE = 1
    const val MSG_START_WATCH = 2
    const val MSG_CANCEL = 3
    const val MSG_RESULT = 4

    const val KEY_REQUEST_ID = "requestId"

    private const val KEY_TIMEOUT_MS = "timeoutMs"
    private const val KEY_ALLOW_CACHED = "allowCached"
    private const val KEY_PURPOSE = "purpose"
    private const val KEY_ORIGIN = "origin"
    private const val KEY_NATIVE_LOCATION_MODE = "nativeLocationMode"
    private const val KEY_NATIVE_LOCATION_MAX_CACHE_AGE_MS = "nativeLocationMaxCacheAgeMs"
    private const val KEY_NATIVE_LOCATION_WATCH_MAX_DURATION_MS = "nativeLocationWatchMaxDurationMs"
    private const val KEY_NATIVE_LOCATION_COORDINATE_MODE = "nativeLocationCoordinateMode"
    private const val KEY_NATIVE_LOCATION_GCJ02_ALLOWED_ORIGINS = "nativeLocationGcj02AllowedOrigins"
    private const val KEY_AMAP_LOCATION_ENABLED = "amapLocationEnabled"
    private const val KEY_AMAP_LOCATION_API_KEY = "amapLocationApiKey"
    private const val KEY_AMAP_LOCATION_PRIVACY_AGREED = "amapLocationPrivacyAgreed"
    private const val KEY_AMAP_LOCATION_PROVIDER_STRATEGY = "amapLocationProviderStrategy"

    private const val KEY_SUCCESS = "success"
    private const val KEY_LATITUDE = "latitude"
    private const val KEY_LONGITUDE = "longitude"
    private const val KEY_ACCURACY = "accuracy"
    private const val KEY_ALTITUDE = "altitude"
    private const val KEY_BEARING = "bearing"
    private const val KEY_SPEED = "speed"
    private const val KEY_ELAPSED_REALTIME_NANOS = "elapsedRealtimeNanos"
    private const val KEY_WALL_TIME_MILLIS = "wallTimeMillis"
    private const val KEY_PROVIDER = "provider"
    private const val KEY_COORDINATE_SYSTEM = "coordinateSystem"
    private const val KEY_CACHED = "cached"
    private const val KEY_CACHE_AGE_MILLIS = "cacheAgeMillis"
    private const val KEY_PRECISE_PERMISSION = "precisePermission"
    private const val KEY_ELAPSED_MS = "elapsedMs"
    private const val KEY_ERROR = "error"
    private const val KEY_MESSAGE = "message"

    fun requestBundle(
        config: WebViewRuntimeConfig,
        timeoutMs: Long,
        allowCached: Boolean,
        purpose: String,
        origin: String?
    ): Bundle {
        return Bundle().apply {
            putLong(KEY_TIMEOUT_MS, timeoutMs)
            putBoolean(KEY_ALLOW_CACHED, allowCached)
            putString(KEY_PURPOSE, purpose)
            putString(KEY_ORIGIN, origin.orEmpty())
            putString(KEY_NATIVE_LOCATION_MODE, config.nativeLocationMode)
            putLong(KEY_NATIVE_LOCATION_MAX_CACHE_AGE_MS, config.nativeLocationMaxCacheAgeMs)
            putLong(KEY_NATIVE_LOCATION_WATCH_MAX_DURATION_MS, config.nativeLocationWatchMaxDurationMs)
            putString(KEY_NATIVE_LOCATION_COORDINATE_MODE, config.nativeLocationCoordinateMode)
            putStringArrayList(
                KEY_NATIVE_LOCATION_GCJ02_ALLOWED_ORIGINS,
                ArrayList(config.nativeLocationGcj02AllowedOrigins)
            )
            putBoolean(KEY_AMAP_LOCATION_ENABLED, config.amapLocationEnabled)
            putString(KEY_AMAP_LOCATION_API_KEY, config.amapLocationApiKey)
            putBoolean(KEY_AMAP_LOCATION_PRIVACY_AGREED, config.amapLocationPrivacyAgreed)
            putString(KEY_AMAP_LOCATION_PROVIDER_STRATEGY, config.amapLocationProviderStrategy)
        }
    }

    fun configFrom(bundle: Bundle, fallback: WebViewRuntimeConfig): WebViewRuntimeConfig {
        return fallback.copy(
            nativeLocationOptimizationEnabled = true,
            nativeLocationMode = bundle.getString(KEY_NATIVE_LOCATION_MODE, fallback.nativeLocationMode),
            nativeLocationRequestTimeoutMs = timeoutMsFrom(bundle, fallback.nativeLocationRequestTimeoutMs),
            nativeLocationMaxCacheAgeMs = bundle.getLong(
                KEY_NATIVE_LOCATION_MAX_CACHE_AGE_MS,
                fallback.nativeLocationMaxCacheAgeMs
            ),
            nativeLocationWatchMaxDurationMs = bundle.getLong(
                KEY_NATIVE_LOCATION_WATCH_MAX_DURATION_MS,
                fallback.nativeLocationWatchMaxDurationMs
            ),
            nativeLocationCoordinateMode = bundle.getString(
                KEY_NATIVE_LOCATION_COORDINATE_MODE,
                fallback.nativeLocationCoordinateMode
            ),
            nativeLocationGcj02AllowedOrigins = bundle.getStringArrayList(
                KEY_NATIVE_LOCATION_GCJ02_ALLOWED_ORIGINS
            )?.toSet() ?: fallback.nativeLocationGcj02AllowedOrigins,
            amapLocationEnabled = bundle.getBoolean(KEY_AMAP_LOCATION_ENABLED, fallback.amapLocationEnabled),
            amapLocationApiKey = bundle.getString(KEY_AMAP_LOCATION_API_KEY, fallback.amapLocationApiKey),
            amapLocationPrivacyAgreed = bundle.getBoolean(
                KEY_AMAP_LOCATION_PRIVACY_AGREED,
                fallback.amapLocationPrivacyAgreed
            ),
            amapLocationProviderStrategy = bundle.getString(
                KEY_AMAP_LOCATION_PROVIDER_STRATEGY,
                fallback.amapLocationProviderStrategy
            )
        )
    }

    fun timeoutMsFrom(bundle: Bundle, fallback: Long): Long = bundle.getLong(KEY_TIMEOUT_MS, fallback)

    fun allowCachedFrom(bundle: Bundle): Boolean = bundle.getBoolean(KEY_ALLOW_CACHED, true)

    fun purposeFrom(bundle: Bundle): String = bundle.getString(KEY_PURPOSE).orEmpty().ifBlank { "ipc" }

    fun originFrom(bundle: Bundle): String? = bundle.getString(KEY_ORIGIN)?.takeIf { it.isNotBlank() }

    fun resultBundle(result: NativeLocationResult): Bundle {
        return Bundle().apply {
            putBoolean(KEY_SUCCESS, result.success)
            result.latitude?.let { putDouble(KEY_LATITUDE, it) }
            result.longitude?.let { putDouble(KEY_LONGITUDE, it) }
            result.accuracyMeters?.let { putFloat(KEY_ACCURACY, it) }
            result.altitude?.let { putDouble(KEY_ALTITUDE, it) }
            result.bearing?.let { putFloat(KEY_BEARING, it) }
            result.speed?.let { putFloat(KEY_SPEED, it) }
            result.elapsedRealtimeNanos?.let { putLong(KEY_ELAPSED_REALTIME_NANOS, it) }
            result.wallTimeMillis?.let { putLong(KEY_WALL_TIME_MILLIS, it) }
            result.provider?.let { putString(KEY_PROVIDER, it) }
            putString(KEY_COORDINATE_SYSTEM, result.coordinateSystem)
            putBoolean(KEY_CACHED, result.cached)
            result.cacheAgeMillis?.let { putLong(KEY_CACHE_AGE_MILLIS, it) }
            putBoolean(KEY_PRECISE_PERMISSION, result.precisePermission)
            putLong(KEY_ELAPSED_MS, result.elapsedMs)
            result.error?.let { putString(KEY_ERROR, it.name) }
            putString(KEY_MESSAGE, result.message)
        }
    }

    fun resultFrom(bundle: Bundle): NativeLocationResult {
        return NativeLocationResult(
            success = bundle.getBoolean(KEY_SUCCESS),
            latitude = doubleOrNull(bundle, KEY_LATITUDE),
            longitude = doubleOrNull(bundle, KEY_LONGITUDE),
            accuracyMeters = floatOrNull(bundle, KEY_ACCURACY),
            altitude = doubleOrNull(bundle, KEY_ALTITUDE),
            bearing = floatOrNull(bundle, KEY_BEARING),
            speed = floatOrNull(bundle, KEY_SPEED),
            elapsedRealtimeNanos = longOrNull(bundle, KEY_ELAPSED_REALTIME_NANOS),
            wallTimeMillis = longOrNull(bundle, KEY_WALL_TIME_MILLIS),
            provider = bundle.getString(KEY_PROVIDER),
            coordinateSystem = bundle.getString(KEY_COORDINATE_SYSTEM) ?: NativeLocationCoordinateSystem.UNKNOWN,
            cached = bundle.getBoolean(KEY_CACHED),
            cacheAgeMillis = longOrNull(bundle, KEY_CACHE_AGE_MILLIS),
            precisePermission = bundle.getBoolean(KEY_PRECISE_PERMISSION),
            elapsedMs = bundle.getLong(KEY_ELAPSED_MS),
            error = bundle.getString(KEY_ERROR)?.let { name ->
                runCatching { NativeLocationError.valueOf(name) }.getOrNull()
            },
            message = bundle.getString(KEY_MESSAGE).orEmpty()
        )
    }

    private fun doubleOrNull(bundle: Bundle, key: String): Double? {
        return if (bundle.containsKey(key)) bundle.getDouble(key) else null
    }

    private fun floatOrNull(bundle: Bundle, key: String): Float? {
        return if (bundle.containsKey(key)) bundle.getFloat(key) else null
    }

    private fun longOrNull(bundle: Bundle, key: String): Long? {
        return if (bundle.containsKey(key)) bundle.getLong(key) else null
    }
}
