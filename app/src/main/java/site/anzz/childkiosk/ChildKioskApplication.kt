package site.anzz.childkiosk

import android.app.Application
import android.content.ComponentCallbacks2
import android.os.Build
import android.os.Handler
import android.util.Log
import android.webkit.WebView
import site.anzz.childkiosk.util.KioskPrefs
import site.anzz.childkiosk.util.ProcessUtils
import site.anzz.childkiosk.util.WebDataManager
import site.anzz.childkiosk.util.WebViewPool
import site.anzz.childkiosk.util.WhitelistSubscriptionRepository
import site.anzz.childkiosk.performance.HighPerformanceRuntimeBridge
import site.anzz.childkiosk.performance.HighPerformanceSessionController
import site.anzz.childkiosk.performance.HighPerformanceDiagnostics
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
        if (isWebViewProcess) {
            HighPerformanceSessionController.initialize(this)
            HighPerformanceRuntimeBridge.register(this)
        }
        if (!isWebViewProcess) {
            KioskPrefs.refreshAmapLocationRuntimeConfig(this)
        }
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
        val decision = memoryTrimDecision(level)
        if (ProcessUtils.isWebViewProcess(this)) {
            HighPerformanceDiagnostics.record(
                type = "trim_memory",
                result = "level_$level",
                reason = decision.levelName
            )
        }
        // Only pooled/preloaded WebViews are touched; active tabs remain owned by WebViewActivity.
        when (decision.action) {
            WebViewPoolTrimAction.CLEAR -> {
                Log.w("ChildKioskApp", "Trim memory ${decision.levelName}: clearing WebViewPool")
                WebViewPool.clear()
            }
            WebViewPoolTrimAction.TRIM_TO_ONE -> {
                Log.d("ChildKioskApp", "Trim memory ${decision.levelName}: trimming WebViewPool to size 1")
                WebViewPool.trimToSize(1)
            }
            WebViewPoolTrimAction.NONE -> Unit
        }
    }
}
