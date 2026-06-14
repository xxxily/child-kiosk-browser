package com.example.childkiosk

import android.app.Application
import android.content.ComponentCallbacks2
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.WebView
import com.example.childkiosk.util.KioskPrefs
import com.example.childkiosk.util.WebDataManager
import com.example.childkiosk.util.WebViewPool

class ChildKioskApplication : Application(), ComponentCallbacks2 {

    override fun onCreate() {
        super.onCreate()

        // 1. 初始化 WebView 预加载池
        WebViewPool.init(this)

        // 2. 预热 WebView 渲染引擎 (利用空闲时机在主线程初始化)
        Handler(mainLooper).post {
            try {
                WebViewPool.warmupBlank()
                Log.d("ChildKioskApp", "WebView warm pool prepared successfully.")
            } catch (e: Exception) {
                Log.w("ChildKioskApp", "Failed to prepare WebView warm pool", e)
            }
        }

        // 3. 检查 7 天自动网页缓存清理
        val lastClear = KioskPrefs.getLastCacheClearTime(this)
        val now = System.currentTimeMillis()
        if (now - lastClear > 7 * 24 * 60 * 60 * 1000L) {
            Handler(mainLooper).post {
                try {
                    WebViewPool.clear()
                    WebView(this).apply {
                        clearCache(true)
                        destroy()
                    }
                    WebDataManager.clearKnownWebCacheFiles(this)
                    KioskPrefs.setLastCacheClearTime(this, now)
                    Log.i("ChildKioskApp", "7-day automatic WebView cache cleanup executed.")
                } catch (e: Exception) {
                    Log.w("ChildKioskApp", "Failed to automatically clear cache", e)
                }
            }
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // 根据内存紧张等级裁减或清理预加载池，防止 OOM
        when {
            level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> {
                Log.w("ChildKioskApp", "Trim memory critical: clearing WebViewPool")
                WebViewPool.clear()
            }
            level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> {
                Log.w("ChildKioskApp", "Trim memory low: clearing WebViewPool")
                WebViewPool.clear()
            }
            level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE -> {
                Log.d("ChildKioskApp", "Trim memory moderate: trimming WebViewPool to size 1")
                WebViewPool.trimToSize(1)
            }
        }
    }
}
