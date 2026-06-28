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
    val limitMediaCapture: Boolean,
    val limitDownload: Boolean,
    val webDebugTool: String,
    val injectTimingMode: String,
    val vConsoleCdnUrl: String,
    val erudaCdnUrl: String,
    val customJsInjectEnabled: Boolean,
    val customJsInjectTiming: String,
    val customJsInjectUrl: String,
    val customJsInjectCode: String
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
            .put("limitMediaCapture", limitMediaCapture)
            .put("limitDownload", limitDownload)
            .put("webDebugTool", webDebugTool)
            .put("injectTimingMode", injectTimingMode)
            .put("vConsoleCdnUrl", vConsoleCdnUrl)
            .put("erudaCdnUrl", erudaCdnUrl)
            .put("customJsInjectEnabled", customJsInjectEnabled)
            .put("customJsInjectTiming", customJsInjectTiming)
            .put("customJsInjectUrl", customJsInjectUrl)
            .put("customJsInjectCode", customJsInjectCode)
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
                limitMediaCapture = json.optBoolean("limitMediaCapture", fallback.limitMediaCapture),
                limitDownload = json.optBoolean("limitDownload", fallback.limitDownload),
                webDebugTool = json.optString("webDebugTool", fallback.webDebugTool),
                injectTimingMode = json.optString("injectTimingMode", fallback.injectTimingMode),
                vConsoleCdnUrl = json.optString("vConsoleCdnUrl", fallback.vConsoleCdnUrl),
                erudaCdnUrl = json.optString("erudaCdnUrl", fallback.erudaCdnUrl),
                customJsInjectEnabled = json.optBoolean("customJsInjectEnabled", fallback.customJsInjectEnabled),
                customJsInjectTiming = json.optString("customJsInjectTiming", fallback.customJsInjectTiming),
                customJsInjectUrl = json.optString("customJsInjectUrl", fallback.customJsInjectUrl),
                customJsInjectCode = json.optString("customJsInjectCode", fallback.customJsInjectCode)
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
            .putBoolean("limit_media_capture", false)
            .putBoolean("third_party_cookies_enabled", true)
            .putBoolean("strict_mixed_content", false)
            .putBoolean("use_browser_user_agent", true)
            .putString("custom_user_agent", "")
            .putBoolean("chrome_inspect_enabled", false)
            .putString("web_debug_tool", "NONE")
            .putString("inject_timing_mode", "BOTH")
            .putBoolean("custom_js_inject_enabled", false)
            .putString("custom_js_inject_timing", "BOTH")
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
            .putBoolean("limit_media_capture", true)
            .putBoolean("third_party_cookies_enabled", true)
            .putBoolean("strict_mixed_content", true)
            .putBoolean("use_browser_user_agent", true)
            .putString("custom_user_agent", "")
            .putBoolean("chrome_inspect_enabled", false)
            .putString("web_debug_tool", "NONE")
            .putString("inject_timing_mode", "BOTH")
            .putBoolean("custom_js_inject_enabled", false)
            .putString("custom_js_inject_timing", "BOTH")
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
            .putBoolean("limit_media_capture", false)
            .putBoolean("third_party_cookies_enabled", true)
            .putBoolean("strict_mixed_content", false)
            .putBoolean("use_browser_user_agent", true)
            .putString("custom_user_agent", "")
            .putBoolean("chrome_inspect_enabled", true)
            .putString("web_debug_tool", "VCONSOLE")
            .putString("inject_timing_mode", "BOTH")
            .putBoolean("custom_js_inject_enabled", false)
            .putString("custom_js_inject_timing", "BOTH")
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
            limitMediaCapture = isLimitMediaCaptureEnabled(context),
            limitDownload = isLimitDownloadEnabled(context),
            webDebugTool = getWebDebugTool(context),
            injectTimingMode = getInjectTimingMode(context),
            vConsoleCdnUrl = getVConsoleCdnUrl(context),
            erudaCdnUrl = getErudaCdnUrl(context),
            customJsInjectEnabled = isCustomJsInjectEnabled(context),
            customJsInjectTiming = getCustomJsInjectTiming(context),
            customJsInjectUrl = getCustomJsInjectUrl(context),
            customJsInjectCode = getCustomJsInjectCode(context)
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

    fun isLimitSslCheckEnabled(context: Context): Boolean = prefs(context).getBoolean("limit_ssl_check", true)
    fun setLimitSslCheckEnabled(context: Context, enabled: Boolean) =
        customEditor(context).putBoolean("limit_ssl_check", enabled).apply()

    fun isLimitMultiWindowEnabled(context: Context): Boolean = prefs(context).getBoolean("limit_multi_window", false)
    fun setLimitMultiWindowEnabled(context: Context, enabled: Boolean) =
        customEditor(context).putBoolean("limit_multi_window", enabled).apply()

    fun isLimitFileAccessEnabled(context: Context): Boolean = prefs(context).getBoolean("limit_file_access", false)
    fun setLimitFileAccessEnabled(context: Context, enabled: Boolean) =
        customEditor(context).putBoolean("limit_file_access", enabled).apply()

    fun isLimitMediaCaptureEnabled(context: Context): Boolean = prefs(context).getBoolean("limit_media_capture", false)
    fun setLimitMediaCaptureEnabled(context: Context, enabled: Boolean) =
        customEditor(context).putBoolean("limit_media_capture", enabled).apply()

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

    private fun customEditor(context: Context): SharedPreferences.Editor =
        prefs(context).edit().putString(KEY_QUICK_MODE, QUICK_MODE_CUSTOM)

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
