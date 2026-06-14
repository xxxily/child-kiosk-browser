package com.example.childkiosk.util

import android.app.Activity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

object SystemUiHelper {

    /**
     * 全屏沉浸：隐藏状态栏与导航栏，并允许通过短暂边缘滑动唤起后再自动隐藏。
     */
    fun enterImmersive(activity: Activity) {
        val window = activity.window
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    /**
     * 首页锁定态：保留状态栏系统信息（时间、电量、网络），隐藏导航栏。
     *
     * 禁止下拉通知栏不属于普通沉浸式能力，必须由 Device Owner 通过
     * DevicePolicyManager#setStatusBarDisabled 兜住；非 Device Owner 不应使用此模式。
     */
    fun enterSecureSystemInfo(activity: Activity) {
        val window = activity.window
        WindowCompat.setDecorFitsSystemWindows(window, true)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.show(WindowInsetsCompat.Type.statusBars())
        controller.hide(WindowInsetsCompat.Type.navigationBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }
}
