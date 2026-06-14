package com.example.childkiosk.util

import android.content.Context

/**
 * 轻量级本地偏好存储，仅用于保存「非 Device Owner 场景下的防护等级」。
 *
 * 之所以不放进 Room：防护等级的决策发生在 [com.example.childkiosk.MainActivity] 的
 * onResume 中（早于、且独立于 Compose 内的 Room Flow 订阅），用 SharedPreferences
 * 可同步读取，避免为单个开关引入数据库 schema 迁移。
 */
object KioskPrefs {

    private const val PREFS_NAME = "kiosk_prefs"
    private const val KEY_PROTECTION_MODE = "non_owner_protection_mode"
    private const val KEY_ORIENTATION_MODE = "orientation_mode"
    private const val KEY_ICON_SIZE_MODE = "icon_size_mode"

    private const val KEY_VERIFY_ON_WEB_EXIT = "verify_on_web_exit"
    private const val KEY_HIDE_ADMIN_ICON = "hide_admin_icon"
    private const val KEY_ADMIN_ICON_ALPHA = "admin_icon_alpha"
    private const val KEY_MAIN_TITLE_TEXT = "main_title_text"
    private const val KEY_HIDE_MAIN_TITLE = "hide_main_title"
    private const val KEY_BROWSER_SANDBOX_BASELINE_VERSION = "browser_sandbox_baseline_version"
    private const val BROWSER_SANDBOX_BASELINE_VERSION = 1

    /** 屏幕固定软锁：调用 startLockTask() 触发系统「屏幕固定」，拦截 Home/最近任务。 */
    const val MODE_SOFT_LOCK = "SOFT_LOCK"

    /** 无系统级锁定：仅沉浸式全屏 + 自定义 Launcher + 家长验证退出。 */
    const val MODE_NONE = "NONE"

    /** 默认值：未取得 Device Owner 时，自动进入屏幕固定软锁。 */
    private const val DEFAULT_MODE = MODE_SOFT_LOCK

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

    fun getProtectionMode(context: Context): String {
        return prefs(context).getString(KEY_PROTECTION_MODE, DEFAULT_MODE) ?: DEFAULT_MODE
    }

    fun setProtectionMode(context: Context, mode: String) {
        prefs(context).edit().putString(KEY_PROTECTION_MODE, mode).apply()
    }

    fun getOrientationMode(context: Context): String {
        return prefs(context).getString(KEY_ORIENTATION_MODE, ORIENTATION_AUTO) ?: ORIENTATION_AUTO
    }

    fun setOrientationMode(context: Context, mode: String) {
        prefs(context).edit().putString(KEY_ORIENTATION_MODE, mode).apply()
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
        prefs(context).edit().putBoolean(KEY_VERIFY_ON_WEB_EXIT, verify).apply()
    }

    fun getHideAdminIcon(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_HIDE_ADMIN_ICON, false)
    }

    fun setHideAdminIcon(context: Context, hide: Boolean) {
        prefs(context).edit().putBoolean(KEY_HIDE_ADMIN_ICON, hide).apply()
    }

    fun getAdminIconAlpha(context: Context): Float {
        return prefs(context).getFloat(KEY_ADMIN_ICON_ALPHA, 0.4f)
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

    fun applyBrowserSandboxDefaultBaseline(context: Context) {
        val storage = prefs(context)
        val appliedVersion = storage.getInt(KEY_BROWSER_SANDBOX_BASELINE_VERSION, 0)
        if (appliedVersion >= BROWSER_SANDBOX_BASELINE_VERSION) return

        storage.edit()
            .putBoolean("limit_ad_block", false)
            .putBoolean("limit_url_redirect", false)
            .putInt(KEY_BROWSER_SANDBOX_BASELINE_VERSION, BROWSER_SANDBOX_BASELINE_VERSION)
            .apply()
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
    fun isLimitAdbEnabled(context: Context): Boolean = prefs(context).getBoolean("limit_adb", true)
    fun setLimitAdbEnabled(context: Context, enabled: Boolean) = prefs(context).edit().putBoolean("limit_adb", enabled).apply()

    fun isLimitSafeBootEnabled(context: Context): Boolean = prefs(context).getBoolean("limit_safe_boot", true)
    fun setLimitSafeBootEnabled(context: Context, enabled: Boolean) = prefs(context).edit().putBoolean("limit_safe_boot", enabled).apply()

    fun isLimitFactoryResetEnabled(context: Context): Boolean = prefs(context).getBoolean("limit_factory_reset", true)
    fun setLimitFactoryResetEnabled(context: Context, enabled: Boolean) = prefs(context).edit().putBoolean("limit_factory_reset", enabled).apply()

    fun isLimitAddUserEnabled(context: Context): Boolean = prefs(context).getBoolean("limit_add_user", true)
    fun setLimitAddUserEnabled(context: Context, enabled: Boolean) = prefs(context).edit().putBoolean("limit_add_user", enabled).apply()

    fun isLimitUsbTransferEnabled(context: Context): Boolean = prefs(context).getBoolean("limit_usb_transfer", true)
    fun setLimitUsbTransferEnabled(context: Context, enabled: Boolean) = prefs(context).edit().putBoolean("limit_usb_transfer", enabled).apply()

    fun isLimitScreenshotEnabled(context: Context): Boolean = prefs(context).getBoolean("limit_screenshot", true)
    fun setLimitScreenshotEnabled(context: Context, enabled: Boolean) = prefs(context).edit().putBoolean("limit_screenshot", enabled).apply()

    fun isLimitStatusBarEnabled(context: Context): Boolean = prefs(context).getBoolean("limit_status_bar", true)
    fun setLimitStatusBarEnabled(context: Context, enabled: Boolean) = prefs(context).edit().putBoolean("limit_status_bar", enabled).apply()

    fun isLimitKeyguardEnabled(context: Context): Boolean = prefs(context).getBoolean("limit_keyguard", true)
    fun setLimitKeyguardEnabled(context: Context, enabled: Boolean) = prefs(context).edit().putBoolean("limit_keyguard", enabled).apply()

    // 2. 物理与界面限制开关
    fun isLimitFlagSecureEnabled(context: Context): Boolean = prefs(context).getBoolean("limit_flag_secure", true)
    fun setLimitFlagSecureEnabled(context: Context, enabled: Boolean) = prefs(context).edit().putBoolean("limit_flag_secure", enabled).apply()

    fun isLimitVolumeKeysEnabled(context: Context): Boolean = prefs(context).getBoolean("limit_volume_keys", false)
    fun setLimitVolumeKeysEnabled(context: Context, enabled: Boolean) = prefs(context).edit().putBoolean("limit_volume_keys", enabled).apply()

    // 3. 网页浏览器沙箱限制
    fun isLimitAdBlockEnabled(context: Context): Boolean = prefs(context).getBoolean("limit_ad_block", false)
    fun setLimitAdBlockEnabled(context: Context, enabled: Boolean) = prefs(context).edit().putBoolean("limit_ad_block", enabled).apply()

    fun isLimitDownloadEnabled(context: Context): Boolean = prefs(context).getBoolean("limit_download", false)
    fun setLimitDownloadEnabled(context: Context, enabled: Boolean) = prefs(context).edit().putBoolean("limit_download", enabled).apply()

    fun isLimitLongClickEnabled(context: Context): Boolean = prefs(context).getBoolean("limit_long_click", false)
    fun setLimitLongClickEnabled(context: Context, enabled: Boolean) = prefs(context).edit().putBoolean("limit_long_click", enabled).apply()

    fun isLimitUrlRedirectEnabled(context: Context): Boolean = prefs(context).getBoolean("limit_url_redirect", false)
    fun setLimitUrlRedirectEnabled(context: Context, enabled: Boolean) = prefs(context).edit().putBoolean("limit_url_redirect", enabled).apply()

    fun isLimitGeolocationEnabled(context: Context): Boolean = prefs(context).getBoolean("limit_geolocation", false)
    fun setLimitGeolocationEnabled(context: Context, enabled: Boolean) = prefs(context).edit().putBoolean("limit_geolocation", enabled).apply()

    fun isLimitSslCheckEnabled(context: Context): Boolean = prefs(context).getBoolean("limit_ssl_check", true)
    fun setLimitSslCheckEnabled(context: Context, enabled: Boolean) = prefs(context).edit().putBoolean("limit_ssl_check", enabled).apply()

    fun isLimitMultiWindowEnabled(context: Context): Boolean = prefs(context).getBoolean("limit_multi_window", false)
    fun setLimitMultiWindowEnabled(context: Context, enabled: Boolean) = prefs(context).edit().putBoolean("limit_multi_window", enabled).apply()

    fun isLimitFileAccessEnabled(context: Context): Boolean = prefs(context).getBoolean("limit_file_access", false)
    fun setLimitFileAccessEnabled(context: Context, enabled: Boolean) = prefs(context).edit().putBoolean("limit_file_access", enabled).apply()

    fun isLimitMediaCaptureEnabled(context: Context): Boolean = prefs(context).getBoolean("limit_media_capture", false)
    fun setLimitMediaCaptureEnabled(context: Context, enabled: Boolean) = prefs(context).edit().putBoolean("limit_media_capture", enabled).apply()

    fun isThirdPartyCookiesEnabled(context: Context): Boolean = prefs(context).getBoolean("third_party_cookies_enabled", true)
    fun setThirdPartyCookiesEnabled(context: Context, enabled: Boolean) = prefs(context).edit().putBoolean("third_party_cookies_enabled", enabled).apply()

    fun isStrictMixedContentEnabled(context: Context): Boolean = prefs(context).getBoolean("strict_mixed_content", false)
    fun setStrictMixedContentEnabled(context: Context, enabled: Boolean) = prefs(context).edit().putBoolean("strict_mixed_content", enabled).apply()

    fun isUseBrowserUserAgentEnabled(context: Context): Boolean = prefs(context).getBoolean("use_browser_user_agent", true)
    fun setUseBrowserUserAgentEnabled(context: Context, enabled: Boolean) = prefs(context).edit().putBoolean("use_browser_user_agent", enabled).apply()

    fun getCustomUserAgent(context: Context): String = prefs(context).getString("custom_user_agent", "") ?: ""
    fun setCustomUserAgent(context: Context, userAgent: String) = prefs(context).edit().putString("custom_user_agent", userAgent).apply()

    // 4. 网页调试与开发配置
    fun getWebDebugTool(context: Context): String = prefs(context).getString("web_debug_tool", "NONE") ?: "NONE"
    fun setWebDebugTool(context: Context, tool: String) = prefs(context).edit().putString("web_debug_tool", tool).apply()

    fun getVConsoleCdnUrl(context: Context): String =
        prefs(context).getString("vconsole_cdn_url", "https://unpkg.com/vconsole@latest/dist/vconsole.min.js") ?: "https://unpkg.com/vconsole@latest/dist/vconsole.min.js"
    fun setVConsoleCdnUrl(context: Context, url: String) = prefs(context).edit().putString("vconsole_cdn_url", url).apply()

    fun getErudaCdnUrl(context: Context): String =
        prefs(context).getString("eruda_cdn_url", "https://cdn.jsdelivr.net/npm/eruda") ?: "https://cdn.jsdelivr.net/npm/eruda"
    fun setErudaCdnUrl(context: Context, url: String) = prefs(context).edit().putString("eruda_cdn_url", url).apply()

    fun getInjectTimingMode(context: Context): String = prefs(context).getString("inject_timing_mode", "BOTH") ?: "BOTH"
    fun setInjectTimingMode(context: Context, mode: String) = prefs(context).edit().putString("inject_timing_mode", mode).apply()

    fun isChromeInspectEnabled(context: Context): Boolean = prefs(context).getBoolean("chrome_inspect_enabled", false)
    fun setChromeInspectEnabled(context: Context, enabled: Boolean) = prefs(context).edit().putBoolean("chrome_inspect_enabled", enabled).apply()

    // 5. 家长操作验证机制
    fun getVerifyAdminActions(context: Context): Boolean = prefs(context).getBoolean("verify_admin_actions", true)
    fun setVerifyAdminActions(context: Context, enabled: Boolean) = prefs(context).edit().putBoolean("verify_admin_actions", enabled).apply()

    // 6. 自定义独立 JS 注入配置
    fun isCustomJsInjectEnabled(context: Context): Boolean = prefs(context).getBoolean("custom_js_inject_enabled", false)
    fun setCustomJsInjectEnabled(context: Context, enabled: Boolean) = prefs(context).edit().putBoolean("custom_js_inject_enabled", enabled).apply()

    fun getCustomJsInjectTiming(context: Context): String = prefs(context).getString("custom_js_inject_timing", "BOTH") ?: "BOTH"
    fun setCustomJsInjectTiming(context: Context, timing: String) = prefs(context).edit().putString("custom_js_inject_timing", timing).apply()

    fun getCustomJsInjectUrl(context: Context): String = prefs(context).getString("custom_js_inject_url", "") ?: ""
    fun setCustomJsInjectUrl(context: Context, url: String) = prefs(context).edit().putString("custom_js_inject_url", url).apply()

    fun getCustomJsInjectCode(context: Context): String = prefs(context).getString("custom_js_inject_code", "") ?: ""
    fun setCustomJsInjectCode(context: Context, code: String) = prefs(context).edit().putString("custom_js_inject_code", code).apply()

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
