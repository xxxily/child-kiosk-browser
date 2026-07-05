package site.anzz.childkiosk

import android.app.Application
import android.content.ComponentCallbacks2
import android.os.Build
import android.os.Handler
import android.util.Log
import android.webkit.WebView
import site.anzz.childkiosk.util.AmapLocationProviderFactory
import site.anzz.childkiosk.util.KioskPrefs
import site.anzz.childkiosk.util.ProcessUtils
import site.anzz.childkiosk.util.WebDataManager
import site.anzz.childkiosk.util.WebViewPool
import site.anzz.childkiosk.util.WhitelistSubscriptionRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class ChildKioskApplication : Application(), ComponentCallbacks2 {

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()

        val processName = ProcessUtils.currentProcessName(this)
        val isWebViewProcess = ProcessUtils.isWebViewProcess(this)
        if (isWebViewProcess && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching {
                WebView.setDataDirectorySuffix(ProcessUtils.WEBVIEW_DATA_DIRECTORY_SUFFIX)
            }.onFailure { e ->
                Log.w("ChildKioskApp", "Failed to set WebView data directory suffix", e)
            }
        }
        Log.d("ChildKioskApp", "Process started: $processName, webViewProcess=$isWebViewProcess")
        AmapLocationProviderFactory.configureApiKey(this, KioskPrefs.getAmapLocationApiKey(this))

        // 1. 初始化 WebView 预加载池
        WebViewPool.init(this)

        // 2. 预热 WebView 渲染引擎 (利用空闲时机在主线程初始化)
        if (isWebViewProcess && KioskPrefs.getWebViewWarmPoolEnabled(this)) {
            Handler(mainLooper).post {
                try {
                    WebViewPool.warmupBlank()
                    Log.d("ChildKioskApp", "WebView warm pool prepared successfully.")
                } catch (e: Exception) {
                    Log.w("ChildKioskApp", "Failed to prepare WebView warm pool", e)
                }
            }
        } else {
            Log.d("ChildKioskApp", "WebView warm pool skipped: process=$processName.")
        }

        if (!isWebViewProcess) {
            applicationScope.launch {
                while (true) {
                    runCatching {
                        WhitelistSubscriptionRepository.refreshIfDue(this@ChildKioskApplication)
                    }.onFailure { e ->
                        Log.w("ChildKioskApp", "Whitelist subscription auto refresh failed", e)
                    }
                    delay(15 * 60 * 1000L)
                }
            }
        }

        // 3. 检查 7 天自动网页缓存清理
        val lastClear = KioskPrefs.getLastCacheClearTime(this)
        val now = System.currentTimeMillis()
        if (now - lastClear > 7 * 24 * 60 * 60 * 1000L) {
            Handler(mainLooper).post {
                try {
                    WebViewPool.clear()
                    if (isWebViewProcess) {
                        WebView(this).apply {
                            clearCache(true)
                            destroy()
                        }
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
