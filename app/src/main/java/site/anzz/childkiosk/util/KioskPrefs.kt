package site.anzz.childkiosk.util

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import site.anzz.childkiosk.util.filter.FilterPreset
import site.anzz.childkiosk.util.filter.FilterRepository
import site.anzz.childkiosk.util.filter.FilterRuntimeSnapshot
import org.json.JSONObject
import site.anzz.childkiosk.ui.browser.BrowserTab
import site.anzz.childkiosk.ui.browser.TabStateInfo

data class WebViewRuntimeConfig(
    val verifyOnWebExit: Boolean,
    val verifyAdminActions: Boolean,
    val limitFlagSecure: Boolean,
    val limitVolumeKeys: Boolean,
    val normalSystemBars: Boolean,
    val floatingBrowserControlsEnabled: Boolean,
    val webViewTopProgressEnabled: Boolean,
    val webViewWarmPoolEnabled: Boolean,
    val webPreloadEnabled: Boolean,
    val webViewRenderMode: String,
    val chromeInspectEnabled: Boolean,
    val thirdPartyCookiesEnabled: Boolean,
    val limitMultiWindow: Boolean,
    val limitFileAccess: Boolean,
    val limitGeolocation: Boolean,
    val strictMixedContent: Boolean,
    val limitLongClick: Boolean,
    val customUserAgent: String,
    val useBrowserUserAgent: Boolean,
    val limitUrlRedirect: Boolean,
    val limitAdBlock: Boolean,
    val filterSnapshot: FilterRuntimeSnapshot,
    val limitSslCheck: Boolean,
    val limitCameraCapture: Boolean,
    val limitMicrophoneCapture: Boolean,
    val limitFileChooser: Boolean,
    val limitFullscreenVideo: Boolean,
    val limitDownload: Boolean,
    val pullToRefreshEnabled: Boolean,
    val webDebugTool: String,
    val injectTimingMode: String,
    val vConsoleCdnUrl: String,
    val erudaCdnUrl: String,
    val customJsInjectEnabled: Boolean,
    val customJsInjectTiming: String,
    val customJsInjectUrl: String,
    val customJsInjectCode: String,
    val limitCustomScheme: Boolean,
    val schemeBlacklist: Set<String>,
    val geolocationBlacklist: Set<String>,
    val cameraBlacklist: Set<String>,
    val microphoneBlacklist: Set<String>,
    val fileChooserBlacklist: Set<String>,
    val nativeLocationOptimizationEnabled: Boolean,
    val nativeLocationWarmupEnabled: Boolean,
    val nativeLocationBridgeEnabled: Boolean,
    val nativeLocationBridgeAllowedOrigins: Set<String>,
    val nativeLocationWarmupTimeoutMs: Long,
    val nativeLocationRequestTimeoutMs: Long,
    val nativeLocationMaxCacheAgeMs: Long,
    val nativeLocationMode: String,
    val nativeLocationWatchMaxDurationMs: Long,
    val amapLocationEnabled: Boolean,
    val amapLocationApiKey: String,
    val amapLocationPrivacyAgreed: Boolean,
    val amapLocationProviderStrategy: String,
    val amapLocationH5AssistantEnabled: Boolean,
    val amapLocationH5AssistantAllowedOrigins: Set<String>,
    val nativeLocationCoordinateMode: String,
    val nativeLocationGcj02AllowedOrigins: Set<String>
) {
    fun toJson(): JSONObject {
        return JSONObject()
            .put("verifyOnWebExit", verifyOnWebExit)
            .put("verifyAdminActions", verifyAdminActions)
            .put("limitFlagSecure", limitFlagSecure)
            .put("limitVolumeKeys", limitVolumeKeys)
            .put("normalSystemBars", normalSystemBars)
            .put("floatingBrowserControlsEnabled", floatingBrowserControlsEnabled)
            .put("webViewTopProgressEnabled", webViewTopProgressEnabled)
            .put("webViewWarmPoolEnabled", webViewWarmPoolEnabled)
            .put("webPreloadEnabled", webPreloadEnabled)
            .put("webViewRenderMode", webViewRenderMode)
            .put("chromeInspectEnabled", chromeInspectEnabled)
            .put("thirdPartyCookiesEnabled", thirdPartyCookiesEnabled)
            .put("limitMultiWindow", limitMultiWindow)
            .put("limitFileAccess", limitFileAccess)
            .put("limitGeolocation", limitGeolocation)
            .put("strictMixedContent", strictMixedContent)
            .put("limitLongClick", limitLongClick)
            .put("customUserAgent", customUserAgent)
            .put("useBrowserUserAgent", useBrowserUserAgent)
            .put("limitUrlRedirect", limitUrlRedirect)
            .put("limitAdBlock", limitAdBlock)
            .put("filterSnapshot", filterSnapshot.toJson())
            .put("limitSslCheck", limitSslCheck)
            .put("limitMediaCapture", limitCameraCapture || limitMicrophoneCapture)
            .put("limitCameraCapture", limitCameraCapture)
            .put("limitMicrophoneCapture", limitMicrophoneCapture)
            .put("limitFileChooser", limitFileChooser)
            .put("limitFullscreenVideo", limitFullscreenVideo)
            .put("limitDownload", limitDownload)
            .put("pullToRefreshEnabled", pullToRefreshEnabled)
            .put("webDebugTool", webDebugTool)
            .put("injectTimingMode", injectTimingMode)
            .put("vConsoleCdnUrl", vConsoleCdnUrl)
            .put("erudaCdnUrl", erudaCdnUrl)
            .put("customJsInjectEnabled", customJsInjectEnabled)
            .put("customJsInjectTiming", customJsInjectTiming)
            .put("customJsInjectUrl", customJsInjectUrl)
            .put("customJsInjectCode", customJsInjectCode)
            .put("limitCustomScheme", limitCustomScheme)
            .put("schemeBlacklist", org.json.JSONArray(schemeBlacklist))
            .put("geolocationBlacklist", org.json.JSONArray(geolocationBlacklist))
            .put("cameraBlacklist", org.json.JSONArray(cameraBlacklist))
            .put("microphoneBlacklist", org.json.JSONArray(microphoneBlacklist))
            .put("fileChooserBlacklist", org.json.JSONArray(fileChooserBlacklist))
            .put("nativeLocationOptimizationEnabled", nativeLocationOptimizationEnabled)
            .put("nativeLocationWarmupEnabled", nativeLocationWarmupEnabled)
            .put("nativeLocationBridgeEnabled", nativeLocationBridgeEnabled)
            .put("nativeLocationBridgeAllowedOrigins", org.json.JSONArray(nativeLocationBridgeAllowedOrigins))
            .put("nativeLocationWarmupTimeoutMs", nativeLocationWarmupTimeoutMs)
            .put("nativeLocationRequestTimeoutMs", nativeLocationRequestTimeoutMs)
            .put("nativeLocationMaxCacheAgeMs", nativeLocationMaxCacheAgeMs)
            .put("nativeLocationMode", nativeLocationMode)
            .put("nativeLocationWatchMaxDurationMs", nativeLocationWatchMaxDurationMs)
            .put("amapLocationEnabled", amapLocationEnabled)
            .put("amapLocationApiKey", amapLocationApiKey)
            .put("amapLocationPrivacyAgreed", amapLocationPrivacyAgreed)
            .put("amapLocationProviderStrategy", amapLocationProviderStrategy)
            .put("amapLocationH5AssistantEnabled", amapLocationH5AssistantEnabled)
            .put("amapLocationH5AssistantAllowedOrigins", org.json.JSONArray(amapLocationH5AssistantAllowedOrigins))
            .put("nativeLocationCoordinateMode", nativeLocationCoordinateMode)
            .put("nativeLocationGcj02AllowedOrigins", org.json.JSONArray(nativeLocationGcj02AllowedOrigins))
    }

    companion object {
        fun fromJson(json: JSONObject, fallback: WebViewRuntimeConfig): WebViewRuntimeConfig {
            return WebViewRuntimeConfig(
                verifyOnWebExit = json.optBoolean("verifyOnWebExit", fallback.verifyOnWebExit),
                verifyAdminActions = json.optBoolean("verifyAdminActions", fallback.verifyAdminActions),
                limitFlagSecure = json.optBoolean("limitFlagSecure", fallback.limitFlagSecure),
                limitVolumeKeys = json.optBoolean("limitVolumeKeys", fallback.limitVolumeKeys),
                normalSystemBars = json.optBoolean("normalSystemBars", fallback.normalSystemBars),
                floatingBrowserControlsEnabled = json.optBoolean(
                    "floatingBrowserControlsEnabled",
                    fallback.floatingBrowserControlsEnabled
                ),
                webViewTopProgressEnabled = json.optBoolean("webViewTopProgressEnabled", fallback.webViewTopProgressEnabled),
                webViewWarmPoolEnabled = json.optBoolean("webViewWarmPoolEnabled", fallback.webViewWarmPoolEnabled),
                webPreloadEnabled = json.optBoolean("webPreloadEnabled", fallback.webPreloadEnabled),
                webViewRenderMode = json.optString("webViewRenderMode", fallback.webViewRenderMode),
                chromeInspectEnabled = json.optBoolean("chromeInspectEnabled", fallback.chromeInspectEnabled),
                thirdPartyCookiesEnabled = json.optBoolean("thirdPartyCookiesEnabled", fallback.thirdPartyCookiesEnabled),
                limitMultiWindow = json.optBoolean("limitMultiWindow", fallback.limitMultiWindow),
                limitFileAccess = json.optBoolean("limitFileAccess", fallback.limitFileAccess),
                limitGeolocation = json.optBoolean("limitGeolocation", fallback.limitGeolocation),
                strictMixedContent = json.optBoolean("strictMixedContent", fallback.strictMixedContent),
                limitLongClick = json.optBoolean("limitLongClick", fallback.limitLongClick),
                customUserAgent = json.optString("customUserAgent", fallback.customUserAgent),
                useBrowserUserAgent = json.optBoolean("useBrowserUserAgent", fallback.useBrowserUserAgent),
                limitUrlRedirect = json.optBoolean("limitUrlRedirect", fallback.limitUrlRedirect),
                limitAdBlock = json.optBoolean("limitAdBlock", fallback.limitAdBlock),
                filterSnapshot = FilterRuntimeSnapshot.fromJson(json.optJSONObject("filterSnapshot")).let {
                    if (json.has("filterSnapshot")) it else fallback.filterSnapshot
                },
                limitSslCheck = json.optBoolean("limitSslCheck", fallback.limitSslCheck),
                limitCameraCapture = json.optBoolean(
                    "limitCameraCapture",
                    json.optBoolean("limitMediaCapture", fallback.limitCameraCapture)
                ),
                limitMicrophoneCapture = json.optBoolean(
                    "limitMicrophoneCapture",
                    json.optBoolean("limitMediaCapture", fallback.limitMicrophoneCapture)
                ),
                limitFileChooser = json.optBoolean("limitFileChooser", fallback.limitFileChooser),
                limitFullscreenVideo = json.optBoolean("limitFullscreenVideo", fallback.limitFullscreenVideo),
                limitDownload = json.optBoolean("limitDownload", fallback.limitDownload),
                pullToRefreshEnabled = json.optBoolean("pullToRefreshEnabled", fallback.pullToRefreshEnabled),
                webDebugTool = json.optString("webDebugTool", fallback.webDebugTool),
                injectTimingMode = json.optString("injectTimingMode", fallback.injectTimingMode),
                vConsoleCdnUrl = json.optString("vConsoleCdnUrl", fallback.vConsoleCdnUrl),
                erudaCdnUrl = json.optString("erudaCdnUrl", fallback.erudaCdnUrl),
                customJsInjectEnabled = json.optBoolean("customJsInjectEnabled", fallback.customJsInjectEnabled),
                customJsInjectTiming = json.optString("customJsInjectTiming", fallback.customJsInjectTiming),
                customJsInjectUrl = json.optString("customJsInjectUrl", fallback.customJsInjectUrl),
                customJsInjectCode = json.optString("customJsInjectCode", fallback.customJsInjectCode),
                limitCustomScheme = json.optBoolean("limitCustomScheme", fallback.limitCustomScheme),
                schemeBlacklist = json.optJSONArray("schemeBlacklist")?.let { array ->
                    val set = mutableSetOf<String>()
                    for (i in 0 until array.length()) {
                        set.add(array.getString(i))
                    }
                    set
                } ?: fallback.schemeBlacklist,
                geolocationBlacklist = json.optJSONArray("geolocationBlacklist")?.let { array ->
                    val set = mutableSetOf<String>()
                    for (i in 0 until array.length()) {
                        set.add(array.getString(i))
                    }
                    set
                } ?: fallback.geolocationBlacklist,
                cameraBlacklist = json.optJSONArray("cameraBlacklist")?.let { array ->
                    val set = mutableSetOf<String>()
                    for (i in 0 until array.length()) {
                        set.add(array.getString(i))
                    }
                    set
                } ?: fallback.cameraBlacklist,
                microphoneBlacklist = json.optJSONArray("microphoneBlacklist")?.let { array ->
                    val set = mutableSetOf<String>()
                    for (i in 0 until array.length()) {
                        set.add(array.getString(i))
                    }
                    set
                } ?: fallback.microphoneBlacklist,
                fileChooserBlacklist = json.optJSONArray("fileChooserBlacklist")?.let { array ->
                    val set = mutableSetOf<String>()
                    for (i in 0 until array.length()) {
                        set.add(array.getString(i))
                    }
                    set
                } ?: fallback.fileChooserBlacklist,
                nativeLocationOptimizationEnabled = json.optBoolean(
                    "nativeLocationOptimizationEnabled",
                    fallback.nativeLocationOptimizationEnabled
                ),
                nativeLocationWarmupEnabled = json.optBoolean(
                    "nativeLocationWarmupEnabled",
                    fallback.nativeLocationWarmupEnabled
                ),
                nativeLocationBridgeEnabled = json.optBoolean(
                    "nativeLocationBridgeEnabled",
                    fallback.nativeLocationBridgeEnabled
                ),
                nativeLocationBridgeAllowedOrigins = json.optJSONArray("nativeLocationBridgeAllowedOrigins")?.let { array ->
                    val set = mutableSetOf<String>()
                    for (i in 0 until array.length()) {
                        set.add(array.getString(i))
                    }
                    set
                } ?: fallback.nativeLocationBridgeAllowedOrigins,
                nativeLocationWarmupTimeoutMs = json.optLong(
                    "nativeLocationWarmupTimeoutMs",
                    fallback.nativeLocationWarmupTimeoutMs
                ),
                nativeLocationRequestTimeoutMs = json.optLong(
                    "nativeLocationRequestTimeoutMs",
                    fallback.nativeLocationRequestTimeoutMs
                ),
                nativeLocationMaxCacheAgeMs = json.optLong(
                    "nativeLocationMaxCacheAgeMs",
                    fallback.nativeLocationMaxCacheAgeMs
                ),
                nativeLocationMode = json.optString("nativeLocationMode", fallback.nativeLocationMode),
                nativeLocationWatchMaxDurationMs = json.optLong(
                    "nativeLocationWatchMaxDurationMs",
                    fallback.nativeLocationWatchMaxDurationMs
                ),
                amapLocationEnabled = json.optBoolean("amapLocationEnabled", fallback.amapLocationEnabled),
                amapLocationApiKey = json.optString("amapLocationApiKey", fallback.amapLocationApiKey),
                amapLocationPrivacyAgreed = json.optBoolean(
                    "amapLocationPrivacyAgreed",
                    fallback.amapLocationPrivacyAgreed
                ),
                amapLocationProviderStrategy = json.optString(
                    "amapLocationProviderStrategy",
                    fallback.amapLocationProviderStrategy
                ),
                amapLocationH5AssistantEnabled = json.optBoolean(
                    "amapLocationH5AssistantEnabled",
                    fallback.amapLocationH5AssistantEnabled
                ),
                amapLocationH5AssistantAllowedOrigins = json.optJSONArray("amapLocationH5AssistantAllowedOrigins")?.let { array ->
                    val set = mutableSetOf<String>()
                    for (i in 0 until array.length()) {
                        set.add(array.getString(i))
                    }
                    set
                } ?: fallback.amapLocationH5AssistantAllowedOrigins,
                nativeLocationCoordinateMode = json.optString(
                    "nativeLocationCoordinateMode",
                    fallback.nativeLocationCoordinateMode
                ),
                nativeLocationGcj02AllowedOrigins = json.optJSONArray("nativeLocationGcj02AllowedOrigins")?.let { array ->
                    val set = mutableSetOf<String>()
                    for (i in 0 until array.length()) {
                        set.add(array.getString(i))
                    }
                    set
                } ?: fallback.nativeLocationGcj02AllowedOrigins
            )
        }
    }
}

/**
 * 轻量级本地偏好存储，仅用于保存「非 Device Owner 场景下的防护等级」。
 *
 * 之所以不放进 Room：防护等级的决策发生在 [site.anzz.childkiosk.MainActivity] 的
 * onResume 中（早于、且独立于 Compose 内的 Room Flow 订阅），用 SharedPreferences
 * 可同步读取，避免为单个开关引入数据库 schema 迁移。
 */
object KioskPrefs {

    private const val PREFS_NAME = "kiosk_prefs"
    private const val EXTRA_WEBVIEW_RUNTIME_CONFIG = "WEBVIEW_RUNTIME_CONFIG_JSON"
    private const val KEY_QUICK_MODE = "quick_mode"
    private const val KEY_PROTECTION_MODE = "non_owner_protection_mode"
    private const val KEY_ORIENTATION_MODE = "orientation_mode"
    private const val KEY_ICON_SIZE_MODE = "icon_size_mode"

    private const val KEY_VERIFY_ON_WEB_EXIT = "verify_on_web_exit"
    private const val KEY_HIDE_ADMIN_ICON = "hide_admin_icon"
    private const val KEY_ADMIN_QUICK_OPEN = "admin_quick_open"
    private const val KEY_ADMIN_ICON_ALPHA = "admin_icon_alpha"
    private const val KEY_MAIN_TITLE_TEXT = "main_title_text"
    private const val KEY_HIDE_MAIN_TITLE = "hide_main_title"
    private const val KEY_FLOATING_BROWSER_CONTROLS_ENABLED = "floating_browser_controls_enabled"
    private const val KEY_PULL_TO_REFRESH_ENABLED = "pull_to_refresh_enabled"
    private const val KEY_PULL_TO_REFRESH_DEFAULT_DISABLED_MIGRATED =
        "pull_to_refresh_default_disabled_migrated"
    private const val KEY_LIMIT_MEDIA_CAPTURE_LEGACY = "limit_media_capture"
    private const val KEY_LIMIT_CAMERA_CAPTURE = "limit_camera_capture"
    private const val KEY_LIMIT_MICROPHONE_CAPTURE = "limit_microphone_capture"
    private const val KEY_LIMIT_FILE_CHOOSER = "limit_file_chooser"
    private const val KEY_LIMIT_FULLSCREEN_VIDEO = "limit_fullscreen_video"
    /** 屏幕固定软锁：调用 startLockTask() 触发系统「屏幕固定」，拦截 Home/最近任务。 */
    const val MODE_SOFT_LOCK = "SOFT_LOCK"

    /** 无系统级锁定：仅沉浸式全屏 + 自定义 Launcher + 认证退出。 */
    const val MODE_NONE = "NONE"

    /** 默认值：正常模式下不主动进入屏幕固定软锁。 */
    private const val DEFAULT_MODE = MODE_NONE

    const val QUICK_MODE_NORMAL = "NORMAL"
    const val QUICK_MODE_CHILD = "CHILD"
    const val QUICK_MODE_DEBUG = "DEBUG"
    const val QUICK_MODE_CUSTOM = "CUSTOM"
    private const val DEFAULT_QUICK_MODE = QUICK_MODE_NORMAL

    const val ORIENTATION_AUTO = "AUTO"
    const val ORIENTATION_LANDSCAPE = "LANDSCAPE"
    const val ORIENTATION_PORTRAIT = "PORTRAIT"

    const val ICON_SIZE_SMALL = "SMALL"
    const val ICON_SIZE_MEDIUM = "MEDIUM"
    const val ICON_SIZE_LARGE = "LARGE"

    const val WEBVIEW_RENDER_MODE_AUTO = "AUTO"
    const val WEBVIEW_RENDER_MODE_HARDWARE = "HARDWARE"
    const val WEBVIEW_RENDER_MODE_SOFTWARE = "SOFTWARE"

    const val NATIVE_LOCATION_MODE_COMPAT = "COMPAT"
    const val NATIVE_LOCATION_MODE_HIGH_ACCURACY = "HIGH_ACCURACY"
    const val NATIVE_LOCATION_MODE_LOW_POWER = "LOW_POWER"
    const val NATIVE_LOCATION_PROVIDER_SYSTEM = "SYSTEM"
    const val NATIVE_LOCATION_PROVIDER_AMAP_FIRST = "AMAP_FIRST"
    const val NATIVE_LOCATION_PROVIDER_AMAP_ONLY = "AMAP_ONLY"
    const val NATIVE_LOCATION_COORDINATE_WGS84 = "WGS84"
    const val NATIVE_LOCATION_COORDINATE_GCJ02_PER_SITE = "GCJ02_PER_SITE"

    private const val KEY_NATIVE_LOCATION_OPTIMIZATION_ENABLED = "native_location_optimization_enabled"
    private const val KEY_NATIVE_LOCATION_WARMUP_ENABLED = "native_location_warmup_enabled"
    private const val KEY_NATIVE_LOCATION_BRIDGE_ENABLED = "native_location_bridge_enabled"
    private const val KEY_NATIVE_LOCATION_BRIDGE_ALLOWED_ORIGINS = "native_location_bridge_allowed_origins"
    private const val KEY_NATIVE_LOCATION_WARMUP_TIMEOUT_MS = "native_location_warmup_timeout_ms"
    private const val KEY_NATIVE_LOCATION_REQUEST_TIMEOUT_MS = "native_location_request_timeout_ms"
    private const val KEY_NATIVE_LOCATION_MAX_CACHE_AGE_MS = "native_location_max_cache_age_ms"
    private const val KEY_NATIVE_LOCATION_MODE = "native_location_mode"
    private const val KEY_NATIVE_LOCATION_WATCH_MAX_DURATION_MS = "native_location_watch_max_duration_ms"
    private const val KEY_AMAP_LOCATION_ENABLED = "amap_location_enabled"
    private const val KEY_AMAP_LOCATION_API_KEY = "amap_location_api_key"
    private const val KEY_AMAP_LOCATION_PRIVACY_AGREED = "amap_location_privacy_agreed"
    private const val KEY_AMAP_LOCATION_PROVIDER_STRATEGY = "amap_location_provider_strategy"
    private const val KEY_AMAP_LOCATION_H5_ASSISTANT_ENABLED = "amap_location_h5_assistant_enabled"
    private const val KEY_AMAP_LOCATION_H5_ASSISTANT_ALLOWED_ORIGINS =
        "amap_location_h5_assistant_allowed_origins"
    private const val KEY_NATIVE_LOCATION_COORDINATE_MODE = "native_location_coordinate_mode"
    private const val KEY_NATIVE_LOCATION_GCJ02_ALLOWED_ORIGINS = "native_location_gcj02_allowed_origins"

    private const val KEY_WEBVIEW_TOP_PROGRESS_ENABLED = "webview_top_progress_enabled"
    private const val KEY_LEGACY_LIGHTWEIGHT_NATIVE_LOADING_INDICATOR_ENABLED =
        "webview_lightweight_native_loading_indicator_enabled"

    fun getQuickMode(context: Context): String {
        return when (prefs(context).getString(KEY_QUICK_MODE, DEFAULT_QUICK_MODE)) {
            QUICK_MODE_CHILD -> QUICK_MODE_CHILD
            QUICK_MODE_DEBUG -> QUICK_MODE_DEBUG
            QUICK_MODE_CUSTOM -> QUICK_MODE_CUSTOM
            else -> QUICK_MODE_NORMAL
        }
    }

    fun setQuickModeCustom(context: Context) {
        prefs(context).edit().putString(KEY_QUICK_MODE, QUICK_MODE_CUSTOM).apply()
    }

    fun applyQuickMode(context: Context, mode: String) {
        val normalized = when (mode) {
            QUICK_MODE_CHILD -> QUICK_MODE_CHILD
            QUICK_MODE_DEBUG -> QUICK_MODE_DEBUG
            QUICK_MODE_CUSTOM -> QUICK_MODE_CUSTOM
            else -> QUICK_MODE_NORMAL
        }
        val editor = prefs(context).edit().putString(KEY_QUICK_MODE, normalized)
        when (normalized) {
            QUICK_MODE_CHILD -> applyChildMode(editor)
            QUICK_MODE_DEBUG -> applyDebugMode(editor)
            QUICK_MODE_CUSTOM -> Unit
            else -> applyNormalMode(editor)
        }
        editor.apply()
        when (normalized) {
            QUICK_MODE_CHILD -> {
                FilterRepository.setPreset(context, FilterPreset.STANDARD_CHILD)
                FilterRepository.setEnabled(context, true)
            }
            QUICK_MODE_DEBUG -> {
                FilterRepository.setPreset(context, FilterPreset.LIGHT)
                FilterRepository.setEnabled(context, false)
            }
            QUICK_MODE_CUSTOM -> Unit
            else -> {
                FilterRepository.setPreset(context, FilterPreset.LIGHT)
                FilterRepository.setEnabled(context, false)
            }
        }
    }

    private fun applyNormalMode(editor: SharedPreferences.Editor) {
        editor
            .putString(KEY_PROTECTION_MODE, MODE_NONE)
            .putBoolean(KEY_VERIFY_ON_WEB_EXIT, false)
            .putBoolean(KEY_HIDE_ADMIN_ICON, false)
            .putBoolean(KEY_ADMIN_QUICK_OPEN, true)
            .putBoolean(KEY_FLOATING_BROWSER_CONTROLS_ENABLED, true)
            .putBoolean("verify_admin_actions", false)
            .putBoolean("limit_adb", false)
            .putBoolean("limit_safe_boot", false)
            .putBoolean("limit_factory_reset", false)
            .putBoolean("limit_add_user", false)
            .putBoolean("limit_usb_transfer", false)
            .putBoolean("limit_screenshot", false)
            .putBoolean("limit_status_bar", false)
            .putBoolean("limit_keyguard", false)
            .putBoolean("limit_voice_assistants", false)
            .putBoolean("limit_unknown_sources", false)
            .putBoolean("limit_flag_secure", false)
            .putBoolean("limit_volume_keys", false)
            .putBoolean("limit_ad_block", false)
            .putBoolean("limit_download", false)
            .putBoolean("limit_long_click", false)
            .putBoolean("limit_url_redirect", false)
            .putBoolean("limit_geolocation", false)
            .putBoolean("limit_ssl_check", true)
            .putBoolean("limit_multi_window", false)
            .putBoolean("limit_file_access", false)
            .putBoolean(KEY_LIMIT_MEDIA_CAPTURE_LEGACY, false)
            .putBoolean(KEY_LIMIT_CAMERA_CAPTURE, false)
            .putBoolean(KEY_LIMIT_MICROPHONE_CAPTURE, false)
            .putBoolean(KEY_LIMIT_FILE_CHOOSER, false)
            .putBoolean(KEY_LIMIT_FULLSCREEN_VIDEO, false)
            .putBoolean(KEY_PULL_TO_REFRESH_ENABLED, false)
            .putBoolean("third_party_cookies_enabled", true)
            .putBoolean("strict_mixed_content", false)
            .putBoolean("use_browser_user_agent", true)
            .putString("custom_user_agent", "")
            .putBoolean("chrome_inspect_enabled", false)
            .putString("web_debug_tool", "NONE")
            .putString("inject_timing_mode", "BOTH")
            .putBoolean("custom_js_inject_enabled", false)
            .putString("custom_js_inject_timing", "BOTH")
            .putBoolean("limit_custom_scheme", false)
    }

    private fun applyChildMode(editor: SharedPreferences.Editor) {
        editor
            .putString(KEY_PROTECTION_MODE, MODE_SOFT_LOCK)
            .putBoolean(KEY_VERIFY_ON_WEB_EXIT, true)
            .putBoolean(KEY_HIDE_ADMIN_ICON, true)
            .putBoolean(KEY_ADMIN_QUICK_OPEN, false)
            .putBoolean(KEY_FLOATING_BROWSER_CONTROLS_ENABLED, false)
            .putBoolean("verify_admin_actions", true)
            .putBoolean("limit_adb", true)
            .putBoolean("limit_safe_boot", true)
            .putBoolean("limit_factory_reset", true)
            .putBoolean("limit_add_user", true)
            .putBoolean("limit_usb_transfer", true)
            .putBoolean("limit_screenshot", true)
            .putBoolean("limit_status_bar", true)
            .putBoolean("limit_keyguard", true)
            .putBoolean("limit_voice_assistants", true)
            .putBoolean("limit_unknown_sources", true)
            .putBoolean("limit_flag_secure", true)
            .putBoolean("limit_volume_keys", true)
            .putBoolean("limit_ad_block", true)
            .putBoolean("limit_download", true)
            .putBoolean("limit_long_click", true)
            .putBoolean("limit_url_redirect", true)
            .putBoolean("limit_geolocation", true)
            .putBoolean("limit_ssl_check", true)
            .putBoolean("limit_multi_window", true)
            .putBoolean("limit_file_access", true)
            .putBoolean(KEY_LIMIT_MEDIA_CAPTURE_LEGACY, true)
            .putBoolean(KEY_LIMIT_CAMERA_CAPTURE, true)
            .putBoolean(KEY_LIMIT_MICROPHONE_CAPTURE, true)
            .putBoolean(KEY_LIMIT_FILE_CHOOSER, true)
            .putBoolean(KEY_LIMIT_FULLSCREEN_VIDEO, true)
            .putBoolean(KEY_PULL_TO_REFRESH_ENABLED, false)
            .putBoolean("third_party_cookies_enabled", true)
            .putBoolean("strict_mixed_content", true)
            .putBoolean("use_browser_user_agent", true)
            .putString("custom_user_agent", "")
            .putBoolean("chrome_inspect_enabled", false)
            .putString("web_debug_tool", "NONE")
            .putString("inject_timing_mode", "BOTH")
            .putBoolean("custom_js_inject_enabled", false)
            .putString("custom_js_inject_timing", "BOTH")
            .putBoolean("limit_custom_scheme", true)
    }

    private fun applyDebugMode(editor: SharedPreferences.Editor) {
        editor
            .putString(KEY_PROTECTION_MODE, MODE_NONE)
            .putBoolean(KEY_VERIFY_ON_WEB_EXIT, false)
            .putBoolean(KEY_HIDE_ADMIN_ICON, false)
            .putBoolean(KEY_ADMIN_QUICK_OPEN, true)
            .putBoolean(KEY_FLOATING_BROWSER_CONTROLS_ENABLED, true)
            .putBoolean("verify_admin_actions", false)
            .putBoolean("limit_adb", false)
            .putBoolean("limit_safe_boot", false)
            .putBoolean("limit_factory_reset", false)
            .putBoolean("limit_add_user", false)
            .putBoolean("limit_usb_transfer", false)
            .putBoolean("limit_screenshot", false)
            .putBoolean("limit_status_bar", false)
            .putBoolean("limit_keyguard", false)
            .putBoolean("limit_voice_assistants", false)
            .putBoolean("limit_unknown_sources", false)
            .putBoolean("limit_flag_secure", false)
            .putBoolean("limit_volume_keys", false)
            .putBoolean("limit_ad_block", false)
            .putBoolean("limit_download", false)
            .putBoolean("limit_long_click", false)
            .putBoolean("limit_url_redirect", false)
            .putBoolean("limit_geolocation", false)
            .putBoolean("limit_ssl_check", false)
            .putBoolean("limit_multi_window", false)
            .putBoolean("limit_file_access", false)
            .putBoolean(KEY_LIMIT_MEDIA_CAPTURE_LEGACY, false)
            .putBoolean(KEY_LIMIT_CAMERA_CAPTURE, false)
            .putBoolean(KEY_LIMIT_MICROPHONE_CAPTURE, false)
            .putBoolean(KEY_LIMIT_FILE_CHOOSER, false)
            .putBoolean(KEY_LIMIT_FULLSCREEN_VIDEO, false)
            .putBoolean(KEY_PULL_TO_REFRESH_ENABLED, false)
            .putBoolean("third_party_cookies_enabled", true)
            .putBoolean("strict_mixed_content", false)
            .putBoolean("use_browser_user_agent", true)
            .putString("custom_user_agent", "")
            .putBoolean("chrome_inspect_enabled", true)
            .putString("web_debug_tool", "VCONSOLE")
            .putString("inject_timing_mode", "BOTH")
            .putBoolean("custom_js_inject_enabled", false)
            .putString("custom_js_inject_timing", "BOTH")
            .putBoolean("limit_custom_scheme", false)
    }

    fun getProtectionMode(context: Context): String {
        return prefs(context).getString(KEY_PROTECTION_MODE, DEFAULT_MODE) ?: DEFAULT_MODE
    }

    fun setProtectionMode(context: Context, mode: String) {
        prefs(context).edit()
            .putString(KEY_PROTECTION_MODE, mode)
            .putString(KEY_QUICK_MODE, QUICK_MODE_CUSTOM)
            .apply()
    }

    fun getOrientationMode(context: Context): String {
        return prefs(context).getString(KEY_ORIENTATION_MODE, ORIENTATION_AUTO) ?: ORIENTATION_AUTO
    }

    fun setOrientationMode(context: Context, mode: String) {
        prefs(context).edit().putString(KEY_ORIENTATION_MODE, mode).apply()
    }

    fun getRequestedOrientation(context: Context): Int {
        return requestedOrientationForMode(getOrientationMode(context))
    }

    fun requestedOrientationForMode(mode: String): Int {
        return when (mode) {
            ORIENTATION_PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            ORIENTATION_LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    fun getIconSizeMode(context: Context): String {
        return prefs(context).getString(KEY_ICON_SIZE_MODE, ICON_SIZE_MEDIUM) ?: ICON_SIZE_MEDIUM
    }

    fun setIconSizeMode(context: Context, mode: String) {
        prefs(context).edit().putString(KEY_ICON_SIZE_MODE, mode).apply()
    }

    fun getVerifyOnWebExit(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_VERIFY_ON_WEB_EXIT, false)
    }

    fun setVerifyOnWebExit(context: Context, verify: Boolean) {
        prefs(context).edit()
            .putBoolean(KEY_VERIFY_ON_WEB_EXIT, verify)
            .putString(KEY_QUICK_MODE, QUICK_MODE_CUSTOM)
            .apply()
    }

    fun getHideAdminIcon(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_HIDE_ADMIN_ICON, false)
    }

    fun setHideAdminIcon(context: Context, hide: Boolean) {
        prefs(context).edit()
            .putBoolean(KEY_HIDE_ADMIN_ICON, hide)
            .putString(KEY_QUICK_MODE, QUICK_MODE_CUSTOM)
            .apply()
    }

    fun getAdminQuickOpen(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_ADMIN_QUICK_OPEN, true)
    }

    fun setAdminQuickOpen(context: Context, enabled: Boolean) {
        prefs(context).edit()
            .putBoolean(KEY_ADMIN_QUICK_OPEN, enabled)
            .putString(KEY_QUICK_MODE, QUICK_MODE_CUSTOM)
            .apply()
    }

    fun getAdminIconAlpha(context: Context): Float {
        return prefs(context).getFloat(KEY_ADMIN_ICON_ALPHA, 0.1f)
    }

    fun setAdminIconAlpha(context: Context, alpha: Float) {
        prefs(context).edit().putFloat(KEY_ADMIN_ICON_ALPHA, alpha).apply()
    }

    fun getMainTitleText(context: Context): String {
        return prefs(context).getString(KEY_MAIN_TITLE_TEXT, "儿童空间") ?: "儿童空间"
    }

    fun setMainTitleText(context: Context, text: String) {
        prefs(context).edit().putString(KEY_MAIN_TITLE_TEXT, text).apply()
    }

    fun getHideMainTitle(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_HIDE_MAIN_TITLE, false)
    }

    fun setHideMainTitle(context: Context, hide: Boolean) {
        prefs(context).edit().putBoolean(KEY_HIDE_MAIN_TITLE, hide).apply()
    }

    fun getWallpaperPreset(context: Context): String {
        return prefs(context).getString("wallpaper_preset", "YELLOW") ?: "YELLOW"
    }

    fun setWallpaperPreset(context: Context, preset: String) {
        prefs(context).edit().putString("wallpaper_preset", preset).apply()
    }

    fun isFloatingBrowserControlsEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_FLOATING_BROWSER_CONTROLS_ENABLED, true)
    }

    fun setFloatingBrowserControlsEnabled(context: Context, enabled: Boolean) {
        customEditor(context)
            .putBoolean(KEY_FLOATING_BROWSER_CONTROLS_ENABLED, enabled)
            .apply()
    }

    fun isNormalSystemBarsEnabled(context: Context): Boolean {
        return getProtectionMode(context) == MODE_NONE && !isLimitStatusBarEnabled(context)
    }

    private const val KEY_TABS_SNAPSHOT = "tabs_snapshot"

    fun saveTabsSnapshot(context: Context, tabs: List<BrowserTab>, activeTabId: String?) {
        val array = org.json.JSONArray()
        tabs.forEach { tab ->
            val obj = org.json.JSONObject().apply {
                put("id", tab.id)
                put("title", tab.title)
                put("url", tab.url)
                put("isActive", tab.id == activeTabId)
            }
            array.put(obj)
        }
        runCatching {
            val file = java.io.File(context.cacheDir, "tabs_snapshot.json")
            file.writeText(array.toString())
        }
    }

    fun getTabsSnapshot(context: Context): List<TabStateInfo> {
        val file = java.io.File(context.cacheDir, "tabs_snapshot.json")
        if (!file.exists()) return emptyList()
        val raw = runCatching { file.readText() }.getOrNull() ?: return emptyList()
        return runCatching {
            val array = org.json.JSONArray(raw)
            val list = mutableListOf<TabStateInfo>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(
                    TabStateInfo(
                        id = obj.getString("id"),
                        title = obj.optString("title", "新标签页"),
                        url = obj.optString("url", "about:blank"),
                        isActive = obj.optBoolean("isActive", false)
                    )
                )
            }
            list
        }.getOrDefault(emptyList())
    }

    fun getWebViewRuntimeConfig(context: Context): WebViewRuntimeConfig {
        return WebViewRuntimeConfig(
            verifyOnWebExit = getVerifyOnWebExit(context),
            verifyAdminActions = getVerifyAdminActions(context),
            limitFlagSecure = isLimitFlagSecureEnabled(context),
            limitVolumeKeys = isLimitVolumeKeysEnabled(context),
            normalSystemBars = isNormalSystemBarsEnabled(context),
            floatingBrowserControlsEnabled = isFloatingBrowserControlsEnabled(context),
            webViewTopProgressEnabled = isWebViewTopProgressEnabled(context),
            webViewWarmPoolEnabled = getWebViewWarmPoolEnabled(context),
            webPreloadEnabled = getWebPreloadEnabled(context),
            webViewRenderMode = getWebViewRenderMode(context),
            chromeInspectEnabled = isChromeInspectEnabled(context),
            thirdPartyCookiesEnabled = isThirdPartyCookiesEnabled(context),
            limitMultiWindow = isLimitMultiWindowEnabled(context),
            limitFileAccess = isLimitFileAccessEnabled(context),
            limitGeolocation = isLimitGeolocationEnabled(context),
            strictMixedContent = isStrictMixedContentEnabled(context),
            limitLongClick = isLimitLongClickEnabled(context),
            customUserAgent = getCustomUserAgent(context),
            useBrowserUserAgent = isUseBrowserUserAgentEnabled(context),
            limitUrlRedirect = isLimitUrlRedirectEnabled(context),
            limitAdBlock = isLimitAdBlockEnabled(context),
            filterSnapshot = FilterRepository.getRuntimeSnapshot(context).let { snapshot ->
                snapshot.copy(enabled = isLimitAdBlockEnabled(context))
            },
            limitSslCheck = isLimitSslCheckEnabled(context),
            limitCameraCapture = isLimitCameraCaptureEnabled(context),
            limitMicrophoneCapture = isLimitMicrophoneCaptureEnabled(context),
            limitFileChooser = isLimitFileChooserEnabled(context),
            limitFullscreenVideo = isLimitFullscreenVideoEnabled(context),
            limitDownload = isLimitDownloadEnabled(context),
            pullToRefreshEnabled = isPullToRefreshEnabled(context),
            webDebugTool = getWebDebugTool(context),
            injectTimingMode = getInjectTimingMode(context),
            vConsoleCdnUrl = getVConsoleCdnUrl(context),
            erudaCdnUrl = getErudaCdnUrl(context),
            customJsInjectEnabled = isCustomJsInjectEnabled(context),
            customJsInjectTiming = getCustomJsInjectTiming(context),
            customJsInjectUrl = getCustomJsInjectUrl(context),
            customJsInjectCode = getCustomJsInjectCode(context),
            limitCustomScheme = isLimitCustomSchemeEnabled(context),
            schemeBlacklist = getSchemeBlacklist(context),
            geolocationBlacklist = getGeolocationBlacklist(context),
            cameraBlacklist = getCameraBlacklist(context),
            microphoneBlacklist = getMicrophoneBlacklist(context),
            fileChooserBlacklist = getFileChooserBlacklist(context),
            nativeLocationOptimizationEnabled = isNativeLocationOptimizationEnabled(context),
            nativeLocationWarmupEnabled = isNativeLocationWarmupEnabled(context),
            nativeLocationBridgeEnabled = isNativeLocationBridgeEnabled(context),
            nativeLocationBridgeAllowedOrigins = getNativeLocationBridgeAllowedOrigins(context),
            nativeLocationWarmupTimeoutMs = getNativeLocationWarmupTimeoutMs(context),
            nativeLocationRequestTimeoutMs = getNativeLocationRequestTimeoutMs(context),
            nativeLocationMaxCacheAgeMs = getNativeLocationMaxCacheAgeMs(context),
            nativeLocationMode = getNativeLocationMode(context),
            nativeLocationWatchMaxDurationMs = getNativeLocationWatchMaxDurationMs(context),
            amapLocationEnabled = isAmapLocationEnabled(context),
            amapLocationApiKey = getAmapLocationApiKey(context),
            amapLocationPrivacyAgreed = isAmapLocationPrivacyAgreed(context),
            amapLocationProviderStrategy = getAmapLocationProviderStrategy(context),
            amapLocationH5AssistantEnabled = isAmapLocationH5AssistantEnabled(context),
            amapLocationH5AssistantAllowedOrigins = getAmapLocationH5AssistantAllowedOrigins(context),
            nativeLocationCoordinateMode = getNativeLocationCoordinateMode(context),
            nativeLocationGcj02AllowedOrigins = getNativeLocationGcj02AllowedOrigins(context)
        )
    }

    fun putWebViewRuntimeConfig(intent: Intent, context: Context) {
        intent.putExtra(EXTRA_WEBVIEW_RUNTIME_CONFIG, getWebViewRuntimeConfig(context).toJson().toString())
    }

    fun putWebViewRuntimeConfig(intent: Intent, context: Context, normalSystemBars: Boolean) {
        intent.putExtra(
            EXTRA_WEBVIEW_RUNTIME_CONFIG,
            getWebViewRuntimeConfig(context)
                .copy(normalSystemBars = normalSystemBars)
                .toJson()
                .toString()
        )
    }

    fun getWebViewRuntimeConfig(intent: Intent?, context: Context): WebViewRuntimeConfig {
        val fallback = getWebViewRuntimeConfig(context)
        val rawConfig = intent?.getStringExtra(EXTRA_WEBVIEW_RUNTIME_CONFIG) ?: return fallback
        return runCatching {
            WebViewRuntimeConfig.fromJson(JSONObject(rawConfig), fallback)
        }.getOrDefault(fallback)
    }

    fun getWebPreloadEnabled(context: Context): Boolean {
        return prefs(context).getBoolean("web_preload_enabled", false)
    }

    fun setWebPreloadEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean("web_preload_enabled", enabled).apply()
    }

    fun getWebViewWarmPoolEnabled(context: Context): Boolean {
        return prefs(context).getBoolean("webview_warm_pool_enabled", false)
    }

    fun setWebViewWarmPoolEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean("webview_warm_pool_enabled", enabled).apply()
    }

    fun getWebViewRenderMode(context: Context): String {
        return when (prefs(context).getString("webview_render_mode", WEBVIEW_RENDER_MODE_AUTO)) {
            WEBVIEW_RENDER_MODE_HARDWARE -> WEBVIEW_RENDER_MODE_HARDWARE
            else -> WEBVIEW_RENDER_MODE_AUTO
        }
    }

    fun setWebViewRenderMode(context: Context, mode: String) {
        val normalized = when (mode) {
            WEBVIEW_RENDER_MODE_HARDWARE -> WEBVIEW_RENDER_MODE_HARDWARE
            else -> WEBVIEW_RENDER_MODE_AUTO
        }
        prefs(context).edit().putString("webview_render_mode", normalized).apply()
    }

    fun isWebViewTopProgressEnabled(context: Context): Boolean {
        val storage = prefs(context)
        return if (storage.contains(KEY_WEBVIEW_TOP_PROGRESS_ENABLED)) {
            storage.getBoolean(KEY_WEBVIEW_TOP_PROGRESS_ENABLED, false)
        } else {
            storage.getBoolean(KEY_LEGACY_LIGHTWEIGHT_NATIVE_LOADING_INDICATOR_ENABLED, false)
        }
    }

    fun setWebViewTopProgressEnabled(context: Context, enabled: Boolean) =
        prefs(context).edit()
            .putBoolean(KEY_WEBVIEW_TOP_PROGRESS_ENABLED, enabled)
            .remove(KEY_LEGACY_LIGHTWEIGHT_NATIVE_LOADING_INDICATOR_ENABLED)
            .apply()

    fun isPullToRefreshEnabled(context: Context): Boolean {
        val storage = prefs(context)
        if (!storage.getBoolean(KEY_PULL_TO_REFRESH_DEFAULT_DISABLED_MIGRATED, false)) {
            storage.edit()
                .putBoolean(KEY_PULL_TO_REFRESH_ENABLED, false)
                .putBoolean(KEY_PULL_TO_REFRESH_DEFAULT_DISABLED_MIGRATED, true)
                .apply()
            return false
        }
        return storage.getBoolean(KEY_PULL_TO_REFRESH_ENABLED, false)
    }

    fun setPullToRefreshEnabled(context: Context, enabled: Boolean) =
        customEditor(context)
            .putBoolean(KEY_PULL_TO_REFRESH_ENABLED, enabled)
            .putBoolean(KEY_PULL_TO_REFRESH_DEFAULT_DISABLED_MIGRATED, true)
            .apply()

    fun getLastCacheClearTime(context: Context): Long {
        return prefs(context).getLong("last_cache_clear_time", 0L)
    }

    fun setLastCacheClearTime(context: Context, time: Long) {
        prefs(context).edit().putLong("last_cache_clear_time", time).apply()
    }

    // 1. 系统加固开关 (Device Owner)
    fun isLimitAdbEnabled(context: Context): Boolean = prefs(context).getBoolean("limit_adb", false)
    fun setLimitAdbEnabled(context: Context, enabled: Boolean) =
        customEditor(context).putBoolean("limit_adb", enabled).apply()

    fun isLimitSafeBootEnabled(context: Context): Boolean = prefs(context).getBoolean("limit_safe_boot", false)
    fun setLimitSafeBootEnabled(context: Context, enabled: Boolean) =
        customEditor(context).putBoolean("limit_safe_boot", enabled).apply()

    fun isLimitFactoryResetEnabled(context: Context): Boolean = prefs(context).getBoolean("limit_factory_reset", false)
    fun setLimitFactoryResetEnabled(context: Context, enabled: Boolean) =
        customEditor(context).putBoolean("limit_factory_reset", enabled).apply()

    fun isLimitAddUserEnabled(context: Context): Boolean = prefs(context).getBoolean("limit_add_user", false)
    fun setLimitAddUserEnabled(context: Context, enabled: Boolean) =
        customEditor(context).putBoolean("limit_add_user", enabled).apply()

    fun isLimitUsbTransferEnabled(context: Context): Boolean = prefs(context).getBoolean("limit_usb_transfer", false)
    fun setLimitUsbTransferEnabled(context: Context, enabled: Boolean) =
        customEditor(context).putBoolean("limit_usb_transfer", enabled).apply()

    fun isLimitScreenshotEnabled(context: Context): Boolean = prefs(context).getBoolean("limit_screenshot", false)
    fun setLimitScreenshotEnabled(context: Context, enabled: Boolean) =
        customEditor(context).putBoolean("limit_screenshot", enabled).apply()

    fun isLimitStatusBarEnabled(context: Context): Boolean = prefs(context).getBoolean("limit_status_bar", false)
    fun setLimitStatusBarEnabled(context: Context, enabled: Boolean) =
        customEditor(context).putBoolean("limit_status_bar", enabled).apply()

    fun isLimitKeyguardEnabled(context: Context): Boolean = prefs(context).getBoolean("limit_keyguard", false)
    fun setLimitKeyguardEnabled(context: Context, enabled: Boolean) =
        customEditor(context).putBoolean("limit_keyguard", enabled).apply()

    fun isLimitVoiceAssistantsEnabled(context: Context): Boolean = prefs(context).getBoolean("limit_voice_assistants", false)
    fun setLimitVoiceAssistantsEnabled(context: Context, enabled: Boolean) =
        customEditor(context).putBoolean("limit_voice_assistants", enabled).apply()

    fun isLimitUnknownSourcesEnabled(context: Context): Boolean = prefs(context).getBoolean("limit_unknown_sources", false)
    fun setLimitUnknownSourcesEnabled(context: Context, enabled: Boolean) =
        customEditor(context).putBoolean("limit_unknown_sources", enabled).apply()

    // 2. 物理与界面限制开关
    fun isLimitFlagSecureEnabled(context: Context): Boolean = prefs(context).getBoolean("limit_flag_secure", false)
    fun setLimitFlagSecureEnabled(context: Context, enabled: Boolean) =
        customEditor(context).putBoolean("limit_flag_secure", enabled).apply()

    fun isLimitVolumeKeysEnabled(context: Context): Boolean = prefs(context).getBoolean("limit_volume_keys", false)
    fun setLimitVolumeKeysEnabled(context: Context, enabled: Boolean) =
        customEditor(context).putBoolean("limit_volume_keys", enabled).apply()

    // 3. 网页浏览器沙箱限制
    fun isLimitAdBlockEnabled(context: Context): Boolean = prefs(context).getBoolean("limit_ad_block", false)
    fun setLimitAdBlockEnabled(context: Context, enabled: Boolean) =
        customEditor(context).putBoolean("limit_ad_block", enabled).apply().also {
            FilterRepository.setEnabled(context, enabled)
        }

    fun isLimitDownloadEnabled(context: Context): Boolean = prefs(context).getBoolean("limit_download", false)
    fun setLimitDownloadEnabled(context: Context, enabled: Boolean) =
        customEditor(context).putBoolean("limit_download", enabled).apply()

    fun isLimitLongClickEnabled(context: Context): Boolean = prefs(context).getBoolean("limit_long_click", false)
    fun setLimitLongClickEnabled(context: Context, enabled: Boolean) =
        customEditor(context).putBoolean("limit_long_click", enabled).apply()

    fun isLimitUrlRedirectEnabled(context: Context): Boolean = prefs(context).getBoolean("limit_url_redirect", false)
    fun setLimitUrlRedirectEnabled(context: Context, enabled: Boolean) =
        customEditor(context).putBoolean("limit_url_redirect", enabled).apply()

    fun isLimitGeolocationEnabled(context: Context): Boolean = prefs(context).getBoolean("limit_geolocation", false)
    fun setLimitGeolocationEnabled(context: Context, enabled: Boolean) =
        customEditor(context).putBoolean("limit_geolocation", enabled).apply()

    fun isLimitCustomSchemeEnabled(context: Context): Boolean = prefs(context).getBoolean("limit_custom_scheme", false)
    fun setLimitCustomSchemeEnabled(context: Context, enabled: Boolean) =
        customEditor(context).putBoolean("limit_custom_scheme", enabled).apply()

    fun getSchemeBlacklist(context: Context): Set<String> {
        return normalizeStringSet(
            prefs(context).getStringSet("scheme_blacklist", emptySet()) ?: emptySet(),
            ::normalizeSchemeKey
        )
    }
    fun setSchemeBlacklist(context: Context, list: Set<String>) {
        prefs(context).edit()
            .putStringSet("scheme_blacklist", normalizeStringSet(list, ::normalizeSchemeKey))
            .apply()
    }
    fun addSchemeToBlacklist(context: Context, scheme: String) {
        val current = getSchemeBlacklist(context).toMutableSet()
        normalizeSchemeKey(scheme).takeIf { it.isNotBlank() }?.let { current.add(it) }
        setSchemeBlacklist(context, current)
    }
    fun removeSchemeFromBlacklist(context: Context, scheme: String) {
        val current = getSchemeBlacklist(context).toMutableSet()
        current.remove(normalizeSchemeKey(scheme))
        setSchemeBlacklist(context, current)
    }

    fun getGeolocationBlacklist(context: Context): Set<String> {
        return normalizeStringSet(
            prefs(context).getStringSet("geolocation_blacklist", emptySet()) ?: emptySet(),
            ::normalizeOriginKey
        )
    }
    fun setGeolocationBlacklist(context: Context, list: Set<String>) {
        prefs(context).edit()
            .putStringSet("geolocation_blacklist", normalizeStringSet(list, ::normalizeOriginKey))
            .apply()
    }
    fun addGeolocationToBlacklist(context: Context, origin: String) {
        val current = getGeolocationBlacklist(context).toMutableSet()
        normalizeOriginKey(origin).takeIf { it.isNotBlank() }?.let { current.add(it) }
        setGeolocationBlacklist(context, current)
    }
    fun removeGeolocationFromBlacklist(context: Context, origin: String) {
        val current = getGeolocationBlacklist(context).toMutableSet()
        current.remove(normalizeOriginKey(origin))
        setGeolocationBlacklist(context, current)
    }

    fun isNativeLocationOptimizationEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_NATIVE_LOCATION_OPTIMIZATION_ENABLED, false)

    fun setNativeLocationOptimizationEnabled(context: Context, enabled: Boolean) =
        customEditor(context).putBoolean(KEY_NATIVE_LOCATION_OPTIMIZATION_ENABLED, enabled).apply()

    fun isNativeLocationWarmupEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_NATIVE_LOCATION_WARMUP_ENABLED, false)

    fun setNativeLocationWarmupEnabled(context: Context, enabled: Boolean) =
        customEditor(context).putBoolean(KEY_NATIVE_LOCATION_WARMUP_ENABLED, enabled).apply()

    fun isNativeLocationBridgeEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_NATIVE_LOCATION_BRIDGE_ENABLED, false)

    fun setNativeLocationBridgeEnabled(context: Context, enabled: Boolean) =
        customEditor(context).putBoolean(KEY_NATIVE_LOCATION_BRIDGE_ENABLED, enabled).apply()

    fun getNativeLocationBridgeAllowedOrigins(context: Context): Set<String> {
        return normalizeStringSet(
            prefs(context).getStringSet(KEY_NATIVE_LOCATION_BRIDGE_ALLOWED_ORIGINS, emptySet()) ?: emptySet(),
            ::normalizeOriginKey
        )
    }

    fun setNativeLocationBridgeAllowedOrigins(context: Context, origins: Set<String>) {
        prefs(context).edit()
            .putStringSet(KEY_NATIVE_LOCATION_BRIDGE_ALLOWED_ORIGINS, normalizeStringSet(origins, ::normalizeOriginKey))
            .apply()
    }

    fun addNativeLocationBridgeAllowedOrigin(context: Context, origin: String) {
        val current = getNativeLocationBridgeAllowedOrigins(context).toMutableSet()
        normalizeOriginKey(origin).takeIf { it.isNotBlank() }?.let { current.add(it) }
        setNativeLocationBridgeAllowedOrigins(context, current)
    }

    fun removeNativeLocationBridgeAllowedOrigin(context: Context, origin: String) {
        val current = getNativeLocationBridgeAllowedOrigins(context).toMutableSet()
        current.remove(normalizeOriginKey(origin))
        setNativeLocationBridgeAllowedOrigins(context, current)
    }

    fun getNativeLocationWarmupTimeoutMs(context: Context): Long =
        prefs(context)
            .getLong(KEY_NATIVE_LOCATION_WARMUP_TIMEOUT_MS, 5_000L)
            .coerceIn(3_000L, 15_000L)

    fun setNativeLocationWarmupTimeoutMs(context: Context, timeoutMs: Long) =
        customEditor(context)
            .putLong(KEY_NATIVE_LOCATION_WARMUP_TIMEOUT_MS, timeoutMs.coerceIn(3_000L, 15_000L))
            .apply()

    fun getNativeLocationRequestTimeoutMs(context: Context): Long =
        prefs(context)
            .getLong(KEY_NATIVE_LOCATION_REQUEST_TIMEOUT_MS, 10_000L)
            .coerceIn(3_000L, 30_000L)

    fun setNativeLocationRequestTimeoutMs(context: Context, timeoutMs: Long) =
        customEditor(context)
            .putLong(KEY_NATIVE_LOCATION_REQUEST_TIMEOUT_MS, timeoutMs.coerceIn(3_000L, 30_000L))
            .apply()

    fun getNativeLocationMaxCacheAgeMs(context: Context): Long =
        prefs(context)
            .getLong(KEY_NATIVE_LOCATION_MAX_CACHE_AGE_MS, 30_000L)
            .coerceIn(0L, 10 * 60_000L)

    fun setNativeLocationMaxCacheAgeMs(context: Context, maxAgeMs: Long) =
        customEditor(context)
            .putLong(KEY_NATIVE_LOCATION_MAX_CACHE_AGE_MS, maxAgeMs.coerceIn(0L, 10 * 60_000L))
            .apply()

    fun getNativeLocationMode(context: Context): String {
        return when (prefs(context).getString(KEY_NATIVE_LOCATION_MODE, NATIVE_LOCATION_MODE_COMPAT)) {
            NATIVE_LOCATION_MODE_HIGH_ACCURACY -> NATIVE_LOCATION_MODE_HIGH_ACCURACY
            NATIVE_LOCATION_MODE_LOW_POWER -> NATIVE_LOCATION_MODE_LOW_POWER
            else -> NATIVE_LOCATION_MODE_COMPAT
        }
    }

    fun setNativeLocationMode(context: Context, mode: String) {
        val normalized = when (mode) {
            NATIVE_LOCATION_MODE_HIGH_ACCURACY -> NATIVE_LOCATION_MODE_HIGH_ACCURACY
            NATIVE_LOCATION_MODE_LOW_POWER -> NATIVE_LOCATION_MODE_LOW_POWER
            else -> NATIVE_LOCATION_MODE_COMPAT
        }
        customEditor(context).putString(KEY_NATIVE_LOCATION_MODE, normalized).apply()
    }

    fun getNativeLocationWatchMaxDurationMs(context: Context): Long =
        prefs(context)
            .getLong(KEY_NATIVE_LOCATION_WATCH_MAX_DURATION_MS, 10 * 60_000L)
            .coerceIn(60_000L, 60 * 60_000L)

    fun setNativeLocationWatchMaxDurationMs(context: Context, durationMs: Long) =
        customEditor(context)
            .putLong(KEY_NATIVE_LOCATION_WATCH_MAX_DURATION_MS, durationMs.coerceIn(60_000L, 60 * 60_000L))
            .apply()

    fun isAmapLocationEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AMAP_LOCATION_ENABLED, false)

    fun setAmapLocationEnabled(context: Context, enabled: Boolean) =
        customEditor(context).putBoolean(KEY_AMAP_LOCATION_ENABLED, enabled).apply()

    fun getAmapLocationApiKey(context: Context): String =
        prefs(context).getString(KEY_AMAP_LOCATION_API_KEY, "")?.trim().orEmpty()

    fun setAmapLocationApiKey(context: Context, apiKey: String) =
        customEditor(context).putString(KEY_AMAP_LOCATION_API_KEY, apiKey.trim()).apply()

    fun maskedAmapLocationApiKey(context: Context): String {
        val key = getAmapLocationApiKey(context)
        return when {
            key.isBlank() -> "未设置"
            key.length <= 4 -> "已设置"
            else -> "已设置 (*${key.takeLast(4)})"
        }
    }

    fun isAmapLocationPrivacyAgreed(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AMAP_LOCATION_PRIVACY_AGREED, false)

    fun setAmapLocationPrivacyAgreed(context: Context, agreed: Boolean) =
        customEditor(context).putBoolean(KEY_AMAP_LOCATION_PRIVACY_AGREED, agreed).apply()

    fun getAmapLocationProviderStrategy(context: Context): String {
        return when (prefs(context).getString(KEY_AMAP_LOCATION_PROVIDER_STRATEGY, NATIVE_LOCATION_PROVIDER_AMAP_FIRST)) {
            NATIVE_LOCATION_PROVIDER_SYSTEM -> NATIVE_LOCATION_PROVIDER_SYSTEM
            NATIVE_LOCATION_PROVIDER_AMAP_ONLY -> NATIVE_LOCATION_PROVIDER_AMAP_ONLY
            else -> NATIVE_LOCATION_PROVIDER_AMAP_FIRST
        }
    }

    fun setAmapLocationProviderStrategy(context: Context, strategy: String) {
        val normalized = when (strategy) {
            NATIVE_LOCATION_PROVIDER_SYSTEM -> NATIVE_LOCATION_PROVIDER_SYSTEM
            NATIVE_LOCATION_PROVIDER_AMAP_ONLY -> NATIVE_LOCATION_PROVIDER_AMAP_ONLY
            else -> NATIVE_LOCATION_PROVIDER_AMAP_FIRST
        }
        customEditor(context).putString(KEY_AMAP_LOCATION_PROVIDER_STRATEGY, normalized).apply()
    }

    fun isAmapLocationH5AssistantEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AMAP_LOCATION_H5_ASSISTANT_ENABLED, false)

    fun setAmapLocationH5AssistantEnabled(context: Context, enabled: Boolean) =
        customEditor(context).putBoolean(KEY_AMAP_LOCATION_H5_ASSISTANT_ENABLED, enabled).apply()

    fun getAmapLocationH5AssistantAllowedOrigins(context: Context): Set<String> {
        return normalizeStringSet(
            prefs(context).getStringSet(KEY_AMAP_LOCATION_H5_ASSISTANT_ALLOWED_ORIGINS, emptySet()) ?: emptySet(),
            ::normalizeOriginKey
        )
    }

    fun setAmapLocationH5AssistantAllowedOrigins(context: Context, origins: Set<String>) {
        prefs(context).edit()
            .putStringSet(
                KEY_AMAP_LOCATION_H5_ASSISTANT_ALLOWED_ORIGINS,
                normalizeStringSet(origins, ::normalizeOriginKey)
            )
            .apply()
    }

    fun addAmapLocationH5AssistantAllowedOrigin(context: Context, origin: String) {
        val current = getAmapLocationH5AssistantAllowedOrigins(context).toMutableSet()
        normalizeOriginKey(origin).takeIf { it.isNotBlank() }?.let { current.add(it) }
        setAmapLocationH5AssistantAllowedOrigins(context, current)
    }

    fun removeAmapLocationH5AssistantAllowedOrigin(context: Context, origin: String) {
        val current = getAmapLocationH5AssistantAllowedOrigins(context).toMutableSet()
        current.remove(normalizeOriginKey(origin))
        setAmapLocationH5AssistantAllowedOrigins(context, current)
    }

    fun getNativeLocationCoordinateMode(context: Context): String {
        return when (prefs(context).getString(KEY_NATIVE_LOCATION_COORDINATE_MODE, NATIVE_LOCATION_COORDINATE_WGS84)) {
            NATIVE_LOCATION_COORDINATE_GCJ02_PER_SITE -> NATIVE_LOCATION_COORDINATE_GCJ02_PER_SITE
            else -> NATIVE_LOCATION_COORDINATE_WGS84
        }
    }

    fun setNativeLocationCoordinateMode(context: Context, mode: String) {
        val normalized = when (mode) {
            NATIVE_LOCATION_COORDINATE_GCJ02_PER_SITE -> NATIVE_LOCATION_COORDINATE_GCJ02_PER_SITE
            else -> NATIVE_LOCATION_COORDINATE_WGS84
        }
        customEditor(context).putString(KEY_NATIVE_LOCATION_COORDINATE_MODE, normalized).apply()
    }

    fun getNativeLocationGcj02AllowedOrigins(context: Context): Set<String> {
        return normalizeStringSet(
            prefs(context).getStringSet(KEY_NATIVE_LOCATION_GCJ02_ALLOWED_ORIGINS, emptySet()) ?: emptySet(),
            ::normalizeOriginKey
        )
    }

    fun setNativeLocationGcj02AllowedOrigins(context: Context, origins: Set<String>) {
        prefs(context).edit()
            .putStringSet(
                KEY_NATIVE_LOCATION_GCJ02_ALLOWED_ORIGINS,
                normalizeStringSet(origins, ::normalizeOriginKey)
            )
            .apply()
    }

    fun isLimitSslCheckEnabled(context: Context): Boolean = prefs(context).getBoolean("limit_ssl_check", true)
    fun setLimitSslCheckEnabled(context: Context, enabled: Boolean) =
        customEditor(context).putBoolean("limit_ssl_check", enabled).apply()

    fun isLimitMultiWindowEnabled(context: Context): Boolean = prefs(context).getBoolean("limit_multi_window", false)
    fun setLimitMultiWindowEnabled(context: Context, enabled: Boolean) =
        customEditor(context).putBoolean("limit_multi_window", enabled).apply()

    fun isLimitFileAccessEnabled(context: Context): Boolean = prefs(context).getBoolean("limit_file_access", false)
    fun setLimitFileAccessEnabled(context: Context, enabled: Boolean) =
        customEditor(context).putBoolean("limit_file_access", enabled).apply()

    fun isLimitMediaCaptureEnabled(context: Context): Boolean =
        isLimitCameraCaptureEnabled(context) || isLimitMicrophoneCaptureEnabled(context)

    fun setLimitMediaCaptureEnabled(context: Context, enabled: Boolean) =
        customEditor(context)
            .putBoolean(KEY_LIMIT_MEDIA_CAPTURE_LEGACY, enabled)
            .putBoolean(KEY_LIMIT_CAMERA_CAPTURE, enabled)
            .putBoolean(KEY_LIMIT_MICROPHONE_CAPTURE, enabled)
            .apply()

    fun isLimitCameraCaptureEnabled(context: Context): Boolean {
        val storage = prefs(context)
        return if (storage.contains(KEY_LIMIT_CAMERA_CAPTURE)) {
            storage.getBoolean(KEY_LIMIT_CAMERA_CAPTURE, false)
        } else {
            storage.getBoolean(KEY_LIMIT_MEDIA_CAPTURE_LEGACY, false)
        }
    }
    fun setLimitCameraCaptureEnabled(context: Context, enabled: Boolean) =
        customEditor(context).putBoolean(KEY_LIMIT_CAMERA_CAPTURE, enabled).apply()

    fun isLimitMicrophoneCaptureEnabled(context: Context): Boolean {
        val storage = prefs(context)
        return if (storage.contains(KEY_LIMIT_MICROPHONE_CAPTURE)) {
            storage.getBoolean(KEY_LIMIT_MICROPHONE_CAPTURE, false)
        } else {
            storage.getBoolean(KEY_LIMIT_MEDIA_CAPTURE_LEGACY, false)
        }
    }
    fun setLimitMicrophoneCaptureEnabled(context: Context, enabled: Boolean) =
        customEditor(context).putBoolean(KEY_LIMIT_MICROPHONE_CAPTURE, enabled).apply()

    fun isLimitFileChooserEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_LIMIT_FILE_CHOOSER, false)
    fun setLimitFileChooserEnabled(context: Context, enabled: Boolean) =
        customEditor(context).putBoolean(KEY_LIMIT_FILE_CHOOSER, enabled).apply()

    fun isLimitFullscreenVideoEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_LIMIT_FULLSCREEN_VIDEO, false)
    fun setLimitFullscreenVideoEnabled(context: Context, enabled: Boolean) =
        customEditor(context).putBoolean(KEY_LIMIT_FULLSCREEN_VIDEO, enabled).apply()

    fun getCameraBlacklist(context: Context): Set<String> =
        getOriginBlacklist(context, "camera_blacklist")
    fun setCameraBlacklist(context: Context, list: Set<String>) =
        setOriginBlacklist(context, "camera_blacklist", list)
    fun addCameraToBlacklist(context: Context, origin: String) =
        addToOriginBlacklist(context, "camera_blacklist", origin)
    fun removeCameraFromBlacklist(context: Context, origin: String) =
        removeFromOriginBlacklist(context, "camera_blacklist", origin)

    fun getMicrophoneBlacklist(context: Context): Set<String> =
        getOriginBlacklist(context, "microphone_blacklist")
    fun setMicrophoneBlacklist(context: Context, list: Set<String>) =
        setOriginBlacklist(context, "microphone_blacklist", list)
    fun addMicrophoneToBlacklist(context: Context, origin: String) =
        addToOriginBlacklist(context, "microphone_blacklist", origin)
    fun removeMicrophoneFromBlacklist(context: Context, origin: String) =
        removeFromOriginBlacklist(context, "microphone_blacklist", origin)

    fun getFileChooserBlacklist(context: Context): Set<String> =
        getOriginBlacklist(context, "file_chooser_blacklist")
    fun setFileChooserBlacklist(context: Context, list: Set<String>) =
        setOriginBlacklist(context, "file_chooser_blacklist", list)
    fun addFileChooserToBlacklist(context: Context, origin: String) =
        addToOriginBlacklist(context, "file_chooser_blacklist", origin)
    fun removeFileChooserFromBlacklist(context: Context, origin: String) =
        removeFromOriginBlacklist(context, "file_chooser_blacklist", origin)

    fun isThirdPartyCookiesEnabled(context: Context): Boolean = prefs(context).getBoolean("third_party_cookies_enabled", true)
    fun setThirdPartyCookiesEnabled(context: Context, enabled: Boolean) =
        customEditor(context).putBoolean("third_party_cookies_enabled", enabled).apply()

    fun isStrictMixedContentEnabled(context: Context): Boolean = prefs(context).getBoolean("strict_mixed_content", false)
    fun setStrictMixedContentEnabled(context: Context, enabled: Boolean) =
        customEditor(context).putBoolean("strict_mixed_content", enabled).apply()

    fun isUseBrowserUserAgentEnabled(context: Context): Boolean = prefs(context).getBoolean("use_browser_user_agent", true)
    fun setUseBrowserUserAgentEnabled(context: Context, enabled: Boolean) =
        customEditor(context).putBoolean("use_browser_user_agent", enabled).apply()

    fun getCustomUserAgent(context: Context): String = prefs(context).getString("custom_user_agent", "") ?: ""
    fun setCustomUserAgent(context: Context, userAgent: String) =
        customEditor(context).putString("custom_user_agent", userAgent).apply()

    // 4. 网页调试与开发配置
    fun getWebDebugTool(context: Context): String = prefs(context).getString("web_debug_tool", "NONE") ?: "NONE"
    fun setWebDebugTool(context: Context, tool: String) =
        customEditor(context).putString("web_debug_tool", tool).apply()

    fun getVConsoleCdnUrl(context: Context): String =
        prefs(context).getString("vconsole_cdn_url", "https://unpkg.com/vconsole@latest/dist/vconsole.min.js") ?: "https://unpkg.com/vconsole@latest/dist/vconsole.min.js"
    fun setVConsoleCdnUrl(context: Context, url: String) = prefs(context).edit().putString("vconsole_cdn_url", url).apply()

    fun getErudaCdnUrl(context: Context): String =
        prefs(context).getString("eruda_cdn_url", "https://cdn.jsdelivr.net/npm/eruda") ?: "https://cdn.jsdelivr.net/npm/eruda"
    fun setErudaCdnUrl(context: Context, url: String) = prefs(context).edit().putString("eruda_cdn_url", url).apply()

    fun getInjectTimingMode(context: Context): String = prefs(context).getString("inject_timing_mode", "BOTH") ?: "BOTH"
    fun setInjectTimingMode(context: Context, mode: String) =
        customEditor(context).putString("inject_timing_mode", mode).apply()

    fun isChromeInspectEnabled(context: Context): Boolean = prefs(context).getBoolean("chrome_inspect_enabled", false)
    fun setChromeInspectEnabled(context: Context, enabled: Boolean) =
        customEditor(context).putBoolean("chrome_inspect_enabled", enabled).apply()

    // 5. 操作验证机制
    fun getVerifyAdminActions(context: Context): Boolean = prefs(context).getBoolean("verify_admin_actions", false)
    fun setVerifyAdminActions(context: Context, enabled: Boolean) =
        customEditor(context).putBoolean("verify_admin_actions", enabled).apply()

    // 6. 自定义独立 JS 注入配置
    fun isCustomJsInjectEnabled(context: Context): Boolean = prefs(context).getBoolean("custom_js_inject_enabled", false)
    fun setCustomJsInjectEnabled(context: Context, enabled: Boolean) =
        customEditor(context).putBoolean("custom_js_inject_enabled", enabled).apply()

    fun getCustomJsInjectTiming(context: Context): String = prefs(context).getString("custom_js_inject_timing", "BOTH") ?: "BOTH"
    fun setCustomJsInjectTiming(context: Context, timing: String) =
        customEditor(context).putString("custom_js_inject_timing", timing).apply()

    fun getCustomJsInjectUrl(context: Context): String = prefs(context).getString("custom_js_inject_url", "") ?: ""
    fun setCustomJsInjectUrl(context: Context, url: String) = prefs(context).edit().putString("custom_js_inject_url", url).apply()

    fun getCustomJsInjectCode(context: Context): String = prefs(context).getString("custom_js_inject_code", "") ?: ""
    fun setCustomJsInjectCode(context: Context, code: String) = prefs(context).edit().putString("custom_js_inject_code", code).apply()

    fun getWhitelistSubscriptionUrl(context: Context): String =
        prefs(context).getString("whitelist_subscription_url", "") ?: ""

    fun setWhitelistSubscriptionUrl(context: Context, url: String) =
        prefs(context).edit().putString("whitelist_subscription_url", url.trim()).apply()

    fun isWhitelistSubscriptionAutoRefreshEnabled(context: Context): Boolean =
        prefs(context).getBoolean("whitelist_subscription_auto_refresh", false)

    fun setWhitelistSubscriptionAutoRefreshEnabled(context: Context, enabled: Boolean) =
        prefs(context).edit().putBoolean("whitelist_subscription_auto_refresh", enabled).apply()

    fun getWhitelistSubscriptionRefreshIntervalHours(context: Context): Int =
        prefs(context).getInt("whitelist_subscription_refresh_interval_hours", 24).coerceIn(1, 168)

    fun setWhitelistSubscriptionRefreshIntervalHours(context: Context, hours: Int) =
        prefs(context).edit()
            .putInt("whitelist_subscription_refresh_interval_hours", hours.coerceIn(1, 168))
            .apply()

    fun getWhitelistSubscriptionTitle(context: Context): String =
        prefs(context).getString("whitelist_subscription_title", "") ?: ""

    fun setWhitelistSubscriptionTitle(context: Context, title: String) =
        prefs(context).edit().putString("whitelist_subscription_title", title).apply()

    fun getWhitelistSubscriptionLastAttemptAt(context: Context): Long =
        prefs(context).getLong("whitelist_subscription_last_attempt_at", 0L)

    fun setWhitelistSubscriptionLastAttemptAt(context: Context, timestamp: Long) =
        prefs(context).edit().putLong("whitelist_subscription_last_attempt_at", timestamp).apply()

    fun getWhitelistSubscriptionLastSuccessAt(context: Context): Long =
        prefs(context).getLong("whitelist_subscription_last_success_at", 0L)

    fun setWhitelistSubscriptionLastSuccessAt(context: Context, timestamp: Long) =
        prefs(context).edit().putLong("whitelist_subscription_last_success_at", timestamp).apply()

    fun getWhitelistSubscriptionImportedCount(context: Context): Int =
        prefs(context).getInt("whitelist_subscription_imported_count", 0)

    fun setWhitelistSubscriptionImportedCount(context: Context, count: Int) =
        prefs(context).edit().putInt("whitelist_subscription_imported_count", count.coerceAtLeast(0)).apply()

    fun getWhitelistSubscriptionLastError(context: Context): String =
        prefs(context).getString("whitelist_subscription_last_error", "") ?: ""

    fun setWhitelistSubscriptionLastError(context: Context, error: String) =
        prefs(context).edit().putString("whitelist_subscription_last_error", error).apply()

    private fun getOriginBlacklist(context: Context, key: String): Set<String> {
        return normalizeStringSet(
            prefs(context).getStringSet(key, emptySet()) ?: emptySet(),
            ::normalizeOriginKey
        )
    }

    private fun setOriginBlacklist(context: Context, key: String, list: Set<String>) {
        prefs(context).edit()
            .putStringSet(key, normalizeStringSet(list, ::normalizeOriginKey))
            .apply()
    }

    private fun addToOriginBlacklist(context: Context, key: String, origin: String) {
        val normalized = normalizeOriginKey(origin)
        if (normalized.isBlank()) return
        val current = getOriginBlacklist(context, key).toMutableSet()
        current.add(normalized)
        setOriginBlacklist(context, key, current)
    }

    private fun removeFromOriginBlacklist(context: Context, key: String, origin: String) {
        val current = getOriginBlacklist(context, key).toMutableSet()
        current.remove(normalizeOriginKey(origin))
        setOriginBlacklist(context, key, current)
    }

    private fun normalizeStringSet(
        values: Set<String>,
        normalizer: (String) -> String
    ): Set<String> {
        return values.mapNotNull { value ->
            normalizer(value).takeIf { it.isNotBlank() }
        }.toSet()
    }

    private fun normalizeSchemeKey(raw: String): String {
        return raw.trim()
            .removeSuffix("://")
            .removeSuffix(":")
            .lowercase()
    }

    fun normalizeOriginKey(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return ""
        return runCatching {
            val uri = android.net.Uri.parse(trimmed)
            val scheme = uri.scheme?.lowercase()?.takeIf { it == "http" || it == "https" }
            val host = uri.host?.lowercase()
            if (scheme != null && !host.isNullOrBlank()) {
                val port = if (uri.port >= 0) ":${uri.port}" else ""
                "$scheme://$host$port"
            } else {
                trimmed.lowercase()
            }
        }.getOrDefault(trimmed.lowercase())
    }

    private fun customEditor(context: Context): SharedPreferences.Editor =
        prefs(context).edit().putString(KEY_QUICK_MODE, QUICK_MODE_CUSTOM)

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
