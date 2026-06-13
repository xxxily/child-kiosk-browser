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
    private const val KEY_MAIN_TITLE_TEXT = "main_title_text"
    private const val KEY_HIDE_MAIN_TITLE = "hide_main_title"

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

    fun getMainTitleText(context: Context): String {
        return prefs(context).getString(KEY_MAIN_TITLE_TEXT, "我的游戏乐园") ?: "我的游戏乐园"
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

    fun getWebPreloadEnabled(context: Context): Boolean {
        return prefs(context).getBoolean("web_preload_enabled", true)
    }

    fun setWebPreloadEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean("web_preload_enabled", enabled).apply()
    }

    fun getLastCacheClearTime(context: Context): Long {
        return prefs(context).getLong("last_cache_clear_time", 0L)
    }

    fun setLastCacheClearTime(context: Context, time: Long) {
        prefs(context).edit().putLong("last_cache_clear_time", time).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
