package com.example.childkiosk.util

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Process

object ProcessUtils {
    const val WEBVIEW_PROCESS_SUFFIX = ":webview"
    const val WEBVIEW_DATA_DIRECTORY_SUFFIX = "webview"

    fun currentProcessName(context: Context): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return Application.getProcessName()
        }
        val pid = Process.myPid()
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        return activityManager
            ?.runningAppProcesses
            ?.firstOrNull { it.pid == pid }
            ?.processName
            ?: context.packageName
    }

    fun isWebViewProcess(context: Context): Boolean {
        return currentProcessName(context) == context.packageName + WEBVIEW_PROCESS_SUFFIX
    }
}
