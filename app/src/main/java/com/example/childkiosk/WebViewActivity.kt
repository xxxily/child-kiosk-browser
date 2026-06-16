package com.example.childkiosk

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.*
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.example.childkiosk.data.AppDatabase
import com.example.childkiosk.data.SystemConfigEntity
import com.example.childkiosk.data.WebAppEntity
import com.example.childkiosk.ui.browser.FloatingBrowserControlsCallbacks
import com.example.childkiosk.ui.browser.FloatingBrowserControlsOverlay
import com.example.childkiosk.ui.browser.FloatingBrowserControlsState
import com.example.childkiosk.util.AdBlocker
import com.example.childkiosk.util.HashUtils
import com.example.childkiosk.util.KioskPrefs
import com.example.childkiosk.util.SystemUiHelper
import com.example.childkiosk.util.TimeLimiter
import com.example.childkiosk.util.WebViewRuntime
import com.example.childkiosk.util.WebViewRuntimeConfig
import com.example.childkiosk.util.WebViewPool
import com.example.childkiosk.util.filter.FilterAction
import com.example.childkiosk.util.filter.FilterRepository
import com.example.childkiosk.util.filter.FilterRequestContext
import com.example.childkiosk.util.filter.FilterResourceType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class WebViewActivity : ComponentActivity() {

    private var rootWebView: WebView? = null
    private var fileChooserCallback: ValueCallback<Array<Uri>>? = null
    private var fullscreenView: View? = null
    private var fullscreenCallback: WebChromeClient.CustomViewCallback? = null
    private val webViewStack = mutableListOf<WebView>()
    private var webViewRoot: FrameLayout? = null
    private var topProgress: ProgressBar? = null
    private var floatingControlsOverlay: FloatingBrowserControlsOverlay? = null
    private var exitVerificationDialog: AlertDialog? = null
    private var timeoutDialog: AlertDialog? = null
    private var timeLimitJob: Job? = null
    private var sessionStartTimeMs: Long = 0L
    private var currentPageLoading = false
    private var currentPageProgress = 0
    private var navigationRootHost = ""
    private lateinit var runtimeConfig: WebViewRuntimeConfig

    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val callback = fileChooserCallback ?: return@registerForActivityResult
        fileChooserCallback = null
        val uris = if (result.resultCode == Activity.RESULT_OK) {
            WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
        } else {
            null
        }
        callback.onReceiveValue(uris ?: emptyArray())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        runtimeConfig = KioskPrefs.getWebViewRuntimeConfig(intent, this)

        // 0. 早期屏幕方向设置，避免启动闪烁
        val orientationMode = intent.getStringExtra(EXTRA_ORIENTATION_MODE)
            ?: KioskPrefs.getOrientationMode(this)
        requestedOrientation = KioskPrefs.requestedOrientationForMode(orientationMode)

        super.onCreate(savedInstanceState)

        // 防截屏逃逸 (根据配置)
        if (runtimeConfig.limitFlagSecure) {
            window.setFlags(
                android.view.WindowManager.LayoutParams.FLAG_SECURE,
                android.view.WindowManager.LayoutParams.FLAG_SECURE
            )
        } else {
            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        }

        // 沉浸式全屏，隐藏状态栏与导航栏
        SystemUiHelper.enterImmersive(this)

        // 监听 System UI / Window 边距变化，防止状态栏灰色半透明条卡死，3秒自动收回
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            window.decorView.setOnApplyWindowInsetsListener { view, insets ->
                val isVisible = insets.isVisible(android.view.WindowInsets.Type.statusBars()) || 
                                insets.isVisible(android.view.WindowInsets.Type.navigationBars())
                if (isVisible) {
                    view.postDelayed({
                        if (!isDestroyed && !isFinishing) {
                            SystemUiHelper.enterImmersive(this@WebViewActivity)
                        }
                    }, 3000)
                }
                view.onApplyWindowInsets(insets)
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.setOnSystemUiVisibilityChangeListener { visibility ->
                if ((visibility and android.view.View.SYSTEM_UI_FLAG_FULLSCREEN) == 0) {
                    window.decorView.postDelayed({
                        if (!isDestroyed && !isFinishing) {
                            SystemUiHelper.enterImmersive(this@WebViewActivity)
                        }
                    }, 3000)
                }
            }
        }

        val webAppId = intent.getIntExtra(EXTRA_WEB_APP_ID, -1)
        Log.d(
            "ChildKioskWebView",
            "Host mode applied: NATIVE_FRAME_LAYOUT, composeHost=false, webAppId=$webAppId"
        )
        WebViewRuntime.logWebViewDiagnostics(this, "activity_created", "webAppId=$webAppId", runtimeConfig)
        startNativeWebView(webAppId)
        startTimeLimitTracking()
    }

    override fun onResume() {
        super.onResume()
        // 恢复沉浸式（部分手势可能短暂打破）
        SystemUiHelper.enterImmersive(this)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) SystemUiHelper.enterImmersive(this)
    }



    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (runtimeConfig.limitVolumeKeys) {
            val keyCode = event.keyCode
            if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    Toast.makeText(this, "音量按键已被锁定", Toast.LENGTH_SHORT).show()
                }
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onDestroy() {
        timeLimitJob?.cancel()
        timeLimitJob = null
        exitVerificationDialog?.dismiss()
        exitVerificationDialog = null
        timeoutDialog?.dismiss()
        timeoutDialog = null
        exitFullscreenView()
        fileChooserCallback?.onReceiveValue(null)
        fileChooserCallback = null
        if (webViewStack.isNotEmpty()) {
            webViewStack.toList().forEach { destroyWebViewSafely(it) }
            webViewStack.clear()
        } else {
            rootWebView?.let { webView ->
                destroyWebViewSafely(webView)
            }
        }
        floatingControlsOverlay = null
        topProgress = null
        webViewRoot = null
        rootWebView = null
        super.onDestroy()
    }

    fun openFileChooser(
        callback: ValueCallback<Array<Uri>>,
        params: WebChromeClient.FileChooserParams?
    ): Boolean {
        fileChooserCallback?.onReceiveValue(null)
        fileChooserCallback = callback

        val intent = runCatching {
            params?.createIntent() ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
            }
        }.getOrElse {
            Intent(Intent.ACTION_GET_CONTENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
            }
        }

        return runCatching {
            fileChooserLauncher.launch(intent)
            true
        }.getOrElse {
            fileChooserCallback = null
            callback.onReceiveValue(null)
            Toast.makeText(this, "无法打开文件选择器", Toast.LENGTH_SHORT).show()
            false
        }
    }

    fun enterFullscreenView(view: View?, callback: WebChromeClient.CustomViewCallback?) {
        if (view == null) {
            callback?.onCustomViewHidden()
            return
        }
        if (fullscreenView != null) {
            callback?.onCustomViewHidden()
            return
        }
        fullscreenView = view
        fullscreenCallback = callback
        floatingControlsOverlay?.apply {
            collapsePanel()
            visibility = View.GONE
        }
        (window.decorView as? ViewGroup)?.addView(
            view,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        rootWebView?.visibility = View.GONE
        SystemUiHelper.enterImmersive(this)
    }

    fun exitFullscreenView() {
        val view = fullscreenView ?: return
        (view.parent as? ViewGroup)?.removeView(view)
        fullscreenView = null
        fullscreenCallback?.onCustomViewHidden()
        fullscreenCallback = null
        rootWebView?.visibility = View.VISIBLE
        if (runtimeConfig.floatingBrowserControlsEnabled) {
            floatingControlsOverlay?.visibility = View.VISIBLE
            updateFloatingControlsState()
        }
        SystemUiHelper.enterImmersive(this)
    }

    private fun startNativeWebView(webAppId: Int) {
        sessionStartTimeMs = System.currentTimeMillis()
        val root = FrameLayout(this).apply {
            setBackgroundColor(android.graphics.Color.WHITE)
        }
        webViewRoot = root
        setContentView(root)

        val showTopProgress = runtimeConfig.webViewTopProgressEnabled
        if (showTopProgress) {
            topProgress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 100
                progress = 0
                visibility = View.GONE
            }
            root.addView(
                topProgress,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    (3 * resources.displayMetrics.density).toInt().coerceAtLeast(3)
                )
            )
        }
        if (runtimeConfig.floatingBrowserControlsEnabled) {
            attachFloatingControls(root)
        }

        Log.d(
            "ChildKioskWebView",
            "Native WebView start: root=FrameLayout, " +
                "topProgress=${if (showTopProgress) "ENABLED" else "DISABLED"}, webAppId=$webAppId"
        )
        WebViewRuntime.logWebViewDiagnostics(this, "native_start", "webAppId=$webAppId", runtimeConfig)

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    handleNativeBack()
                }
            }
        )

        lifecycleScope.launch {
            val db = AppDatabase.getInstance(this@WebViewActivity)
            val webApp = withContext(Dispatchers.IO) {
                db.webAppDao().getWebAppById(webAppId)
            }
            if (webApp == null) {
                Log.w("ChildKioskWebView", "Native WebView abort: web app not found, id=$webAppId")
                Toast.makeText(this@WebViewActivity, "网页应用不存在", Toast.LENGTH_SHORT).show()
                finish()
                return@launch
            }
            attachNativeWebView(root, webApp)
        }
    }

    private fun attachNativeWebView(root: FrameLayout, webApp: WebAppEntity) {
        val targetUrl = webApp.url
        val originalHost = WebViewRuntime.hostOf(targetUrl)
        navigationRootHost = originalHost
        val preloadEntry = if (targetUrl != "about:blank") {
            WebViewPool.acquire(
                url = targetUrl,
                allowUrlPreload = runtimeConfig.webPreloadEnabled,
                allowWarmPool = runtimeConfig.webViewWarmPoolEnabled
            )
        } else {
            null
        }
        val shouldClearPreloadedHistoryNow = preloadEntry?.let {
            it.isUrlPreload && (it.isLoaded || it.progress >= 100)
        } == true
        val shouldClearHistoryOnFirstFinish = preloadEntry?.webView != null && !shouldClearPreloadedHistoryNow
        val webView = createSecureWebView(
            ctx = this,
            targetUrl = targetUrl,
            originalHost = originalHost,
            onSslError = { url ->
                Log.w("ChildKioskWebView", "Native WebView SSL error: $url")
                Toast.makeText(this, "SSL 证书异常：$url", Toast.LENGTH_LONG).show()
                hideTopProgress()
            },
            onBlocked = { url ->
                Log.w("ChildKioskWebView", "Native WebView blocked navigation: $url")
                Toast.makeText(this, "已拦截跳转：$url", Toast.LENGTH_LONG).show()
                hideTopProgress()
            },
            onDownloadBlocked = {
                Toast.makeText(this, "下载功能已受限制，如需下载应用请联系管理员。", Toast.LENGTH_LONG).show()
            },
            onLoadingStateChanged = { loading ->
                Log.d(
                    "ChildKioskWebView",
                    "Native loading state: loading=$loading, progress=${rootWebView?.progress ?: -1}, url=${rootWebView?.url}"
                )
                if (loading) showTopProgress() else hideTopProgress()
                updateFloatingControlsState(loading = loading)
            },
            onProgressUpdate = { progress ->
                val safeProgress = progress.coerceIn(0, 100)
                topProgress?.progress = safeProgress
                updateFloatingControlsState(progress = safeProgress)
            },
            onNavigationStateChanged = {
                updateFloatingControlsState()
            },
            onError = { error ->
                Log.w("ChildKioskWebView", "Native WebView main frame error: $error")
                Toast.makeText(this, "网页加载异常：$error", Toast.LENGTH_LONG).show()
                hideTopProgress()
                updateFloatingControlsState(loading = false)
            },
            existingWebView = preloadEntry?.webView,
            runtimeConfig = runtimeConfig,
            clearHistoryOnFirstRealPageFinish = shouldClearHistoryOnFirstFinish,
            onShowFileChooser = { callback, params -> openFileChooser(callback, params) },
            onCreateWindow = { newWebView ->
                Log.d("ChildKioskWebView", "Native child window created: parent=${rootWebView?.url}")
                attachNativeChildWebView(root, newWebView)
            }
        )

        attachNativeChildWebView(root, webView)
        WebViewRuntime.logWebViewDiagnostics(this, "native_webview_attached", targetUrl, runtimeConfig)

        if (shouldClearPreloadedHistoryNow) {
            webView.post {
                clearInitialBlankHistory(webView, webView.url ?: targetUrl)
            }
        }
        if (preloadEntry?.isUrlPreload == true) {
            if (preloadEntry.isLoaded || preloadEntry.progress >= 100) {
                topProgress?.progress = 100
                hideTopProgress()
            } else {
                showTopProgress()
            }
        } else {
            loadInitialUrlAfterFirstLayout(webView, targetUrl)
        }
    }

    private fun attachNativeChildWebView(root: FrameLayout, webView: WebView) {
        rootWebView?.visibility = View.GONE
        root.addView(
            webView,
            0,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        rootWebView = webView
        webViewStack.add(webView)
        logWebViewSurfaceState(webView, "native_attached")
        currentPageProgress = webView.progress.coerceIn(0, 100)
        currentPageLoading = currentPageProgress in 1..99
        updateFloatingControlsState()
    }

    private fun handleNativeBack() {
        val current = rootWebView
        if (current != null && current.canGoBack()) {
            Log.d("ChildKioskWebView", "Native back: webView.goBack, url=${current.url}")
            current.goBack()
            updateFloatingControlsState(loading = true)
            return
        }

        if (webViewStack.size > 1) {
            val removed = webViewStack.removeLast()
            Log.d("ChildKioskWebView", "Native back: destroy child webview, url=${removed.url}")
            destroyWebViewSafely(removed)
            rootWebView = webViewStack.lastOrNull()
            rootWebView?.visibility = View.VISIBLE
            updateFloatingControlsState()
            return
        }

        requestCloseWithVerification()
    }

    private fun showTopProgress() {
        topProgress?.visibility = View.VISIBLE
    }

    private fun hideTopProgress() {
        topProgress?.visibility = View.GONE
    }

    private fun attachFloatingControls(root: FrameLayout) {
        if (floatingControlsOverlay != null) return
        floatingControlsOverlay = FloatingBrowserControlsOverlay.attachTo(
            root = root,
            initialState = currentFloatingControlsState(),
            callbacks = FloatingBrowserControlsCallbacks(
                onNavigateToUrl = { url -> loadUrlFromFloatingControls(url) },
                onBack = { goBackFromFloatingControls() },
                onForward = { goForwardFromFloatingControls() },
                onRefresh = { refreshFromFloatingControls() },
                onStopLoading = { stopLoadingFromFloatingControls() },
                onPanelExpandedChanged = {
                    SystemUiHelper.enterImmersive(this)
                },
                onActionSelected = { actionId ->
                    Log.d("ChildKioskWebView", "Floating browser action: $actionId")
                }
            )
        )
    }

    private fun loadUrlFromFloatingControls(url: String) {
        val current = rootWebView ?: return
        if (!WebViewRuntime.isWebUrl(url)) {
            Toast.makeText(this, "仅支持打开 http/https 网站", Toast.LENGTH_SHORT).show()
            return
        }
        if (runtimeConfig.limitUrlRedirect) {
            val targetHost = WebViewRuntime.hostOf(url)
            if (!WebViewRuntime.isSameHostOrSubdomain(targetHost, navigationRootHost)) {
                Toast.makeText(this, "已拦截跳转：$url", Toast.LENGTH_LONG).show()
                updateFloatingControlsState()
                return
            }
        }
        currentPageLoading = true
        currentPageProgress = 0
        current.loadUrl(url)
        updateFloatingControlsState()
    }

    private fun goBackFromFloatingControls() {
        val current = rootWebView ?: return
        if (!current.canGoBack()) return
        currentPageLoading = true
        current.goBack()
        updateFloatingControlsState()
    }

    private fun goForwardFromFloatingControls() {
        val current = rootWebView ?: return
        if (!current.canGoForward()) return
        currentPageLoading = true
        current.goForward()
        updateFloatingControlsState()
    }

    private fun refreshFromFloatingControls() {
        val current = rootWebView ?: return
        currentPageLoading = true
        current.reload()
        updateFloatingControlsState()
    }

    private fun stopLoadingFromFloatingControls() {
        rootWebView?.stopLoading()
        updateFloatingControlsState(loading = false)
    }

    private fun updateFloatingControlsState(
        loading: Boolean? = null,
        progress: Int? = null
    ) {
        loading?.let { currentPageLoading = it }
        progress?.let { currentPageProgress = it.coerceIn(0, 100) }
        floatingControlsOverlay?.updateState(currentFloatingControlsState())
    }

    private fun currentFloatingControlsState(): FloatingBrowserControlsState {
        val current = rootWebView
        return FloatingBrowserControlsState(
            currentUrl = current?.url.orEmpty(),
            pageTitle = current?.title.orEmpty(),
            canGoBack = current?.canGoBack() == true,
            canGoForward = current?.canGoForward() == true,
            isLoading = currentPageLoading,
            progress = currentPageProgress.coerceIn(0, 100)
        )
    }

    private fun startTimeLimitTracking() {
        timeLimitJob?.cancel()
        timeLimitJob = lifecycleScope.launch {
            val db = AppDatabase.getInstance(this@WebViewActivity)
            var lastPersistedSec = 0L

            while (!isFinishing && !isDestroyed) {
                delay(1000)
                val config = withContext(Dispatchers.IO) {
                    db.systemConfigDao().getSystemConfig()
                } ?: continue

                if (config.timeLimitMinutes <= 0 && config.dailyLimitMinutes <= 0) {
                    continue
                }

                val remainingMs = TimeLimiter.calculateRemainingTimeMs(config, sessionStartTimeMs)
                if (TimeLimiter.isLimitExceeded(config) || remainingMs == 0L) {
                    showTimeoutDialog(config)
                    return@launch
                }

                val elapsedSec = (System.currentTimeMillis() - sessionStartTimeMs) / 1000
                if (elapsedSec - lastPersistedSec >= 5) {
                    val deltaMs = (elapsedSec - lastPersistedSec) * 1000L
                    lastPersistedSec = elapsedSec
                    withContext(Dispatchers.IO) {
                        val freshConfig = db.systemConfigDao().getSystemConfig() ?: return@withContext
                        val today = TimeLimiter.getTodayDateString()
                        val baseUsed = if (freshConfig.lastUsedDate == today) {
                            freshConfig.usedTimeTodayMs
                        } else {
                            0L
                        }
                        db.systemConfigDao().insertOrUpdateConfig(
                            freshConfig.copy(
                                usedTimeTodayMs = baseUsed + deltaMs,
                                lastUsedDate = today
                            )
                        )
                    }
                }
            }
        }
    }

    private fun showTimeoutDialog(config: SystemConfigEntity?) {
        if (timeoutDialog?.isShowing == true || isFinishing || isDestroyed) return
        val dialog = AlertDialog.Builder(this)
            .setTitle("休息时间到了")
            .setMessage("当前网页使用时间已到，请休息一下。")
            .setCancelable(false)
            .setNegativeButton("好的，去休息") { _, _ ->
                finish()
            }
            .setPositiveButton("延长可用时间", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                showParentVerificationDialog(config, "请完成认证后继续使用网页。") {
                    grantExtraWebTime()
                }
            }
        }
        timeoutDialog = dialog
        dialog.show()
    }

    private fun grantExtraWebTime() {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                val db = AppDatabase.getInstance(this@WebViewActivity)
                val freshConfig = db.systemConfigDao().getSystemConfig() ?: return@withContext
                val today = TimeLimiter.getTodayDateString()
                val grantedDailyLimit = if (freshConfig.dailyLimitMinutes > 0) {
                    freshConfig.dailyLimitMinutes + 30
                } else {
                    30
                }
                db.systemConfigDao().insertOrUpdateConfig(
                    freshConfig.copy(
                        dailyLimitMinutes = grantedDailyLimit,
                        lastUsedDate = today
                    )
                )
            }
            sessionStartTimeMs = System.currentTimeMillis()
            timeoutDialog?.dismiss()
            timeoutDialog = null
            startTimeLimitTracking()
        }
    }

    private fun requestCloseWithVerification() {
        if (!runtimeConfig.verifyOnWebExit || !runtimeConfig.verifyAdminActions) {
            finish()
            return
        }
        if (exitVerificationDialog?.isShowing == true) return

        lifecycleScope.launch {
            val config = withContext(Dispatchers.IO) {
                AppDatabase.getInstance(this@WebViewActivity).systemConfigDao().getSystemConfig()
            }
            if (!isFinishing && !isDestroyed) {
                showParentVerificationDialog(config, "请完成认证后退出网页。") {
                    finish()
                }
            }
        }
    }

    private fun showParentVerificationDialog(
        config: SystemConfigEntity?,
        message: String,
        onVerified: () -> Unit
    ) {
        exitVerificationDialog?.dismiss()
        val pinHash = config?.pinHash.orEmpty()
        if (config?.verificationMode == "PIN" && pinHash.isNotBlank()) {
            showPinVerification(pinHash, message, onVerified)
        } else {
            showMathVerification(message, onVerified)
        }
    }

    private fun showPinVerification(targetHash: String, message: String, onVerified: () -> Unit) {
        val density = resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density).toInt()

        val enteredPin = StringBuilder()
        lateinit var dialog: AlertDialog
        lateinit var pinDotsView: TextView
        lateinit var errorView: TextView

        fun refreshPinDots() {
            pinDotsView.text = buildString {
                repeat(4) { index ->
                    append(if (index < enteredPin.length) "●" else "○")
                    if (index < 3) append("  ")
                }
            }
        }

        fun clearError() {
            errorView.visibility = View.GONE
            errorView.text = ""
        }

        fun showError() {
            errorView.text = "密码错误，请重新输入"
            errorView.visibility = View.VISIBLE
        }

        fun handlePinKey(key: String) {
            clearError()
            when (key) {
                "清除" -> enteredPin.clear()
                "删除" -> if (enteredPin.isNotEmpty()) enteredPin.deleteCharAt(enteredPin.length - 1)
                else -> {
                    if (enteredPin.length < 4) {
                        enteredPin.append(key)
                    }
                    if (enteredPin.length == 4) {
                        if (HashUtils.sha256(enteredPin.toString()) == targetHash) {
                            dialog.dismiss()
                            onVerified()
                            return
                        } else {
                            enteredPin.clear()
                            showError()
                        }
                    }
                }
            }
            refreshPinDots()
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))

            addView(
                TextView(this@WebViewActivity).apply {
                    text = message
                    textSize = 14f
                    setPadding(0, 0, 0, dp(12))
                }
            )

            pinDotsView = TextView(this@WebViewActivity).apply {
                textSize = 28f
                textAlignment = View.TEXT_ALIGNMENT_CENTER
                setPadding(0, 0, 0, dp(10))
            }
            addView(
                pinDotsView,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )

            errorView = TextView(this@WebViewActivity).apply {
                textSize = 13f
                setTextColor(android.graphics.Color.rgb(176, 0, 32))
                textAlignment = View.TEXT_ALIGNMENT_CENTER
                visibility = View.GONE
                setPadding(0, 0, 0, dp(8))
            }
            addView(
                errorView,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )

            val keyHeight = if (resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) {
                dp(44)
            } else {
                dp(52)
            }
            val rowGap = dp(6)
            val columnGap = dp(6)
            val keyRows = listOf(
                listOf("1", "2", "3"),
                listOf("4", "5", "6"),
                listOf("7", "8", "9"),
                listOf("清除", "0", "删除")
            )

            keyRows.forEachIndexed { rowIndex, rowKeys ->
                val row = LinearLayout(this@WebViewActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                }
                rowKeys.forEachIndexed { keyIndex, key ->
                    row.addView(
                        Button(this@WebViewActivity).apply {
                            text = key
                            textSize = if (key.length > 1) 13f else 18f
                            minHeight = 0
                            minimumHeight = 0
                            setAllCaps(false)
                            setOnClickListener { handlePinKey(key) }
                        },
                        LinearLayout.LayoutParams(0, keyHeight, 1f).apply {
                            if (keyIndex < rowKeys.lastIndex) {
                                marginEnd = columnGap
                            }
                        }
                    )
                }
                addView(
                    row,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        if (rowIndex < keyRows.lastIndex) {
                            bottomMargin = rowGap
                        }
                    }
                )
            }

            addView(
                Button(this@WebViewActivity).apply {
                    text = "取消"
                    setOnClickListener { dialog.dismiss() }
                },
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    if (resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) {
                        dp(44)
                    } else {
                        dp(48)
                    }
                ).apply {
                    topMargin = dp(12)
                }
            )
        }
        refreshPinDots()

        val scrollView = ScrollView(this).apply {
            isFillViewport = false
            addView(container)
        }

        dialog = AlertDialog.Builder(this)
            .setTitle("认证")
            .setView(scrollView)
            .create()
        exitVerificationDialog = dialog
        dialog.show()
    }

    private fun showMathVerification(message: String, onVerified: () -> Unit) {
        val density = resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density).toInt()

        var question = generateNativeMathQuestion()
        val enteredAnswer = StringBuilder()
        lateinit var dialog: AlertDialog
        val questionView = TextView(this).apply {
            text = question.expression
            textSize = 28f
            textAlignment = View.TEXT_ALIGNMENT_CENTER
            setPadding(0, 0, 0, dp(10))
        }
        lateinit var answerView: TextView
        lateinit var errorView: TextView

        fun refreshAnswer() {
            answerView.text = if (enteredAnswer.isEmpty()) {
                "请输入答案"
            } else {
                enteredAnswer.toString()
            }
        }

        fun clearError() {
            errorView.visibility = View.GONE
            errorView.text = ""
        }

        fun showError() {
            errorView.text = "答案错误，请再试一次"
            errorView.visibility = View.VISIBLE
        }

        fun resetQuestion() {
            question = generateNativeMathQuestion()
            questionView.text = question.expression
            enteredAnswer.clear()
            refreshAnswer()
        }

        fun submitAnswer() {
            val answer = enteredAnswer.toString().toIntOrNull()
            if (answer == question.answer) {
                dialog.dismiss()
                onVerified()
            } else {
                showError()
                resetQuestion()
            }
        }

        fun handleAnswerKey(key: String) {
            clearError()
            when (key) {
                "清除" -> enteredAnswer.clear()
                "删除" -> if (enteredAnswer.isNotEmpty()) enteredAnswer.deleteCharAt(enteredAnswer.length - 1)
                else -> {
                    if (enteredAnswer.length < 3) {
                        enteredAnswer.append(key)
                    }
                }
            }
            refreshAnswer()
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))

            addView(
                TextView(this@WebViewActivity).apply {
                    text = message
                    textSize = 14f
                    setPadding(0, 0, 0, dp(10))
                }
            )
            addView(questionView)

            answerView = TextView(this@WebViewActivity).apply {
                textSize = 22f
                textAlignment = View.TEXT_ALIGNMENT_CENTER
                setPadding(0, 0, 0, dp(8))
            }
            addView(
                answerView,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )

            errorView = TextView(this@WebViewActivity).apply {
                textSize = 13f
                setTextColor(android.graphics.Color.rgb(176, 0, 32))
                textAlignment = View.TEXT_ALIGNMENT_CENTER
                visibility = View.GONE
                setPadding(0, 0, 0, dp(8))
            }
            addView(
                errorView,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )

            val keyHeight = if (resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) {
                dp(42)
            } else {
                dp(50)
            }
            val rowGap = dp(6)
            val columnGap = dp(6)
            val keyRows = listOf(
                listOf("1", "2", "3"),
                listOf("4", "5", "6"),
                listOf("7", "8", "9"),
                listOf("清除", "0", "删除")
            )

            keyRows.forEachIndexed { rowIndex, rowKeys ->
                val row = LinearLayout(this@WebViewActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                }
                rowKeys.forEachIndexed { keyIndex, key ->
                    row.addView(
                        Button(this@WebViewActivity).apply {
                            text = key
                            textSize = if (key.length > 1) 13f else 18f
                            minHeight = 0
                            minimumHeight = 0
                            setAllCaps(false)
                            setOnClickListener { handleAnswerKey(key) }
                        },
                        LinearLayout.LayoutParams(0, keyHeight, 1f).apply {
                            if (keyIndex < rowKeys.lastIndex) {
                                marginEnd = columnGap
                            }
                        }
                    )
                }
                addView(
                    row,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        if (rowIndex < keyRows.lastIndex) {
                            bottomMargin = rowGap
                        }
                    }
                )
            }

            addView(
                Button(this@WebViewActivity).apply {
                    text = "确认"
                    setOnClickListener { submitAnswer() }
                },
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    if (resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) {
                        dp(42)
                    } else {
                        dp(48)
                    }
                ).apply {
                    topMargin = dp(12)
                }
            )

            addView(
                Button(this@WebViewActivity).apply {
                    text = "取消"
                    setOnClickListener { dialog.dismiss() }
                },
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    if (resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) {
                        dp(42)
                    } else {
                        dp(48)
                    }
                ).apply {
                    topMargin = dp(8)
                }
            )
        }
        refreshAnswer()

        val scrollView = ScrollView(this).apply {
            isFillViewport = false
            addView(container)
        }

        dialog = AlertDialog.Builder(this)
            .setTitle("认证")
            .setView(scrollView)
            .create()
        exitVerificationDialog = dialog
        dialog.show()
    }

    private data class NativeMathQuestion(val expression: String, val answer: Int)

    private fun generateNativeMathQuestion(): NativeMathQuestion {
        return when ((0..2).random()) {
            0 -> {
                val a = (1..89).random()
                val b = (1..(100 - a)).random()
                NativeMathQuestion("$a + $b = ?", a + b)
            }
            1 -> {
                val a = (10..100).random()
                val b = (1..a).random()
                NativeMathQuestion("$a - $b = ?", a - b)
            }
            else -> {
                val a = (2..9).random()
                val b = (2..9).random()
                NativeMathQuestion("$a × $b = ?", a * b)
            }
        }
    }

    companion object {
        const val EXTRA_WEB_APP_ID = "WEB_APP_ID"
        const val EXTRA_ORIENTATION_MODE = "ORIENTATION_MODE"
    }
}

@SuppressLint("SetJavaScriptEnabled")
private fun createSecureWebView(
    ctx: Context,
    targetUrl: String,
    originalHost: String,
    onSslError: (String) -> Unit,
    onBlocked: (String) -> Unit,
    onDownloadBlocked: () -> Unit,
    onLoadingStateChanged: (Boolean) -> Unit,
    onProgressUpdate: (Int) -> Unit,
    onNavigationStateChanged: () -> Unit,
    onError: (String) -> Unit,
    existingWebView: WebView? = null,
    runtimeConfig: WebViewRuntimeConfig,
    clearHistoryOnFirstRealPageFinish: Boolean = false,
    onShowFileChooser: (ValueCallback<Array<Uri>>, WebChromeClient.FileChooserParams?) -> Boolean,
    onCreateWindow: (WebView) -> Unit
): WebView {
    val webView = existingWebView ?: WebView(ctx)
    val shouldClearInitialHistory = AtomicBoolean(clearHistoryOnFirstRealPageFinish)

    return webView.apply {
        WebViewRuntime.applySettings(this, ctx, targetUrl, runtimeConfig)
        WebViewRuntime.logWebViewDiagnostics(ctx, "create_secure_webview", targetUrl, runtimeConfig)
        logWebViewSurfaceState(this, "created_after_settings")

        // 仅在网页未加载完成时设置暖色底色以防止白屏；已加载完的实例直接使用白色底色
        val initialBgColor = if (existingWebView != null && existingWebView.progress == 100) {
            android.graphics.Color.WHITE
        } else {
            android.graphics.Color.parseColor("#FFF8E1")
        }
        setBackgroundColor(initialBgColor)

        webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                Log.d("ChildKioskWebView", "Page started: $url")
                onProgressUpdate(0)
                view?.setBackgroundColor(android.graphics.Color.parseColor("#FFF8E1"))
                if (view != null && view.progress < 100) {
                    onLoadingStateChanged(true)
                }
                onNavigationStateChanged()
                if (view != null) {
                    injectPageScripts(view, ctx, runtimeConfig, "PAGE_STARTED")
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                Log.d(
                    "ChildKioskWebView",
                    "Page finished: progress=${view?.progress}, canGoBack=${view?.canGoBack()}, url=$url"
                )
                // 网页载入完成后恢复白色背景，防止无背景网页的文字无法看清
                view?.setBackgroundColor(android.graphics.Color.WHITE)

                if (view != null) {
                    if (
                        url != null &&
                        WebViewRuntime.isWebUrl(url) &&
                        shouldClearInitialHistory.compareAndSet(true, false)
                    ) {
                        view.post {
                            clearInitialBlankHistory(view, url)
                        }
                    }
                    injectPageScripts(view, ctx, runtimeConfig, "PAGE_FINISHED")
                }

                onLoadingStateChanged(false)
                onNavigationStateChanged()
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame == true) {
                    Log.w(
                        "ChildKioskWebView",
                        "Main frame error: ${error?.errorCode}, ${error?.description}, url=${request.url}"
                    )
                    onLoadingStateChanged(false)
                    onNavigationStateChanged()
                    onError(error?.description?.toString() ?: "网络连接异常")
                }
            }

            override fun onReceivedHttpError(
                view: WebView?,
                request: WebResourceRequest?,
                errorResponse: WebResourceResponse?
            ) {
                super.onReceivedHttpError(view, request, errorResponse)
                if (request?.isForMainFrame == true) {
                    val code = errorResponse?.statusCode ?: 200
                    if (code >= 400) {
                        Log.w(
                            "ChildKioskWebView",
                            "Main frame HTTP error: HTTP $code, url=${request.url}"
                        )
                        onLoadingStateChanged(false)
                        onNavigationStateChanged()
                        onError("服务器返回异常: HTTP $code")
                    }
                }
            }

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val urlStr = request?.url?.toString() ?: return false

                if (WebViewRuntime.isInternalWebViewUrl(urlStr)) {
                    return false
                }

                if (!WebViewRuntime.isWebUrl(urlStr)) {
                    onBlocked(urlStr)
                    return true
                }

                if (runtimeConfig.limitAdBlock && request?.isForMainFrame == true) {
                    val cleanedUrl = FilterRepository.getEngine(ctx, runtimeConfig.filterSnapshot)
                        .cleanUrlForNavigation(urlStr, view?.url ?: targetUrl)
                    if (!cleanedUrl.isNullOrBlank() && cleanedUrl != urlStr) {
                        Log.d("ChildKioskWebView", "Cleaned tracking params: $urlStr -> $cleanedUrl")
                        view?.loadUrl(cleanedUrl)
                        return true
                    }
                }

                if (runtimeConfig.limitUrlRedirect) {
                    val host = WebViewRuntime.hostOf(urlStr)
                    if (!WebViewRuntime.isSameHostOrSubdomain(host, originalHost)) {
                        onBlocked(urlStr)
                        return true
                    }
                }
                return false
            }

            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                if (runtimeConfig.limitAdBlock) {
                    val topLevelUrl = view?.url ?: targetUrl
                    val decision = AdBlocker.shouldBlock(ctx, request, topLevelUrl, runtimeConfig.filterSnapshot)
                    if (decision.action == FilterAction.BLOCK) {
                        val requestUrl = request?.url?.toString().orEmpty()
                        Log.d(
                            "ChildKioskWebView",
                            "Blocked filter request: $requestUrl, rule=${decision.rule?.rawText}, source=${decision.rule?.sourceName}"
                        )
                        val resourceType = FilterResourceType.infer(
                            url = requestUrl,
                            acceptHeader = request?.requestHeaders?.get("Accept"),
                            isMainFrame = request?.isForMainFrame == true
                        )
                        return AdBlocker.emptyResponse(resourceType)
                    }
                }
                return super.shouldInterceptRequest(view, request)
            }

            @SuppressLint("WebViewClientOnReceivedSslError")
            override fun onReceivedSslError(
                view: WebView?,
                handler: SslErrorHandler?,
                error: SslError?
            ) {
                if (runtimeConfig.limitSslCheck) {
                    Log.w("ChildKioskWebView", "SSL error blocked: ${error?.url}")
                    handler?.cancel()
                    onLoadingStateChanged(false)
                    onSslError(error?.url ?: "未知链接")
                } else {
                    handler?.proceed()
                }
            }
        }

        webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                super.onProgressChanged(view, newProgress)
                onProgressUpdate(newProgress)
                onNavigationStateChanged()
                if (newProgress >= 100) {
                    view?.postDelayed({
                        injectPageScripts(view, ctx, runtimeConfig, "BOTH")
                        onLoadingStateChanged(false)
                        onNavigationStateChanged()
                    }, 250)
                }
            }

            override fun onPermissionRequest(request: PermissionRequest?) {
                if (request == null) return
                val requested = request.resources.orEmpty()
                val allowed = requested.filter { resource ->
                    when (resource) {
                        PermissionRequest.RESOURCE_VIDEO_CAPTURE,
                        PermissionRequest.RESOURCE_AUDIO_CAPTURE -> !runtimeConfig.limitMediaCapture
                        else -> true
                    }
                }.toTypedArray()
                if (allowed.isEmpty()) {
                    request.deny()
                } else {
                    request.grant(allowed)
                }
            }

            override fun onGeolocationPermissionsShowPrompt(
                origin: String?,
                callback: GeolocationPermissions.Callback?
            ) {
                callback?.invoke(origin, !runtimeConfig.limitGeolocation, false)
            }

            override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                consoleMessage ?: return super.onConsoleMessage(consoleMessage)
                Log.d(
                    "ChildKioskWebView",
                    "${consoleMessage.messageLevel()}: ${consoleMessage.message()} (${consoleMessage.sourceId()}:${consoleMessage.lineNumber()})"
                )
                return super.onConsoleMessage(consoleMessage)
            }

            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                return filePathCallback?.let { onShowFileChooser(it, fileChooserParams) } ?: false
            }

            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                (ctx as? WebViewActivity)?.enterFullscreenView(view, callback)
                    ?: callback?.onCustomViewHidden()
            }

            override fun onHideCustomView() {
                (ctx as? WebViewActivity)?.exitFullscreenView()
            }

            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: android.os.Message?
            ): Boolean {
                if (resultMsg == null) return false
                if (runtimeConfig.limitAdBlock && shouldBlockPopup(ctx, view, runtimeConfig)) {
                    Log.d("ChildKioskWebView", "Blocked popup window: parent=${view?.url}")
                    return false
                }
                val newWebView = createSecureWebView(
                    ctx = ctx,
                    targetUrl = "",
                    originalHost = originalHost,
                    onSslError = onSslError,
                    onBlocked = onBlocked,
                    onDownloadBlocked = onDownloadBlocked,
                    onLoadingStateChanged = onLoadingStateChanged,
                    onProgressUpdate = onProgressUpdate,
                    onNavigationStateChanged = onNavigationStateChanged,
                    onError = onError,
                    runtimeConfig = runtimeConfig,
                    onShowFileChooser = onShowFileChooser,
                    onCreateWindow = onCreateWindow
                )
                onCreateWindow(newWebView)
                val transport = resultMsg.obj as WebView.WebViewTransport
                transport.webView = newWebView
                resultMsg.sendToTarget()
                return true
            }
        }

        setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            if (runtimeConfig.limitDownload) {
                onDownloadBlocked()
            } else {
                enqueueDownload(ctx, url, userAgent, contentDisposition, mimeType)
            }
        }
    }
}

private fun enqueueDownload(
    context: Context,
    url: String?,
    userAgent: String?,
    contentDisposition: String?,
    mimeType: String?
) {
    if (url.isNullOrBlank()) return
    runCatching {
        val uri = Uri.parse(url)
        val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
        val request = DownloadManager.Request(uri).apply {
            setMimeType(mimeType)
            addRequestHeader("User-Agent", userAgent.orEmpty())
            CookieManager.getInstance().getCookie(url)?.let { cookie ->
                addRequestHeader("Cookie", cookie)
            }
            setTitle(fileName)
            setDescription(uri.host ?: url)
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
            setAllowedOverMetered(true)
            setAllowedOverRoaming(true)
        }
        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        manager.enqueue(request)
        Toast.makeText(context, "已开始下载：$fileName", Toast.LENGTH_SHORT).show()
    }.onFailure { e ->
        Toast.makeText(context, "无法开始下载：${e.message}", Toast.LENGTH_SHORT).show()
    }
}

private fun loadInitialUrlAfterFirstLayout(webView: WebView, targetUrl: String) {
    fun attempt(remainingAttempts: Int) {
        if (!webView.isAttachedToWindow || webView.width <= 0 || webView.height <= 0) {
            if (remainingAttempts > 0) {
                webView.postDelayed({ attempt(remainingAttempts - 1) }, 50)
            } else {
                webView.post { webView.loadUrl(targetUrl) }
            }
            return
        }
        webView.post {
            logWebViewSurfaceState(webView, "initial_load_after_layout")
            Log.d(
                "ChildKioskWebView",
                "Initial load after layout: ${webView.width}x${webView.height}, url=$targetUrl"
            )
            webView.loadUrl(targetUrl)
        }
    }
    webView.post { attempt(20) }
}

private fun logWebViewSurfaceState(webView: WebView, event: String) {
    val parent = webView.parent?.javaClass?.simpleName ?: "none"
    val contextName = webView.context.javaClass.name
    val layer = when (webView.layerType) {
        View.LAYER_TYPE_NONE -> "HARDWARE_DEFAULT"
        View.LAYER_TYPE_HARDWARE -> "HARDWARE"
        View.LAYER_TYPE_SOFTWARE -> "SOFTWARE"
        else -> webView.layerType.toString()
    }
    Log.d(
        "ChildKioskWebView",
        "WebView surface: event=$event, attached=${webView.isAttachedToWindow}, " +
            "size=${webView.width}x${webView.height}, layer=$layer, parent=$parent, " +
            "context=$contextName, progress=${webView.progress}, url=${webView.url}"
    )
}

private fun clearInitialBlankHistory(webView: WebView, currentUrl: String) {
    if (!WebViewRuntime.isWebUrl(currentUrl)) return
    webView.clearHistory()
    Log.d(
        "ChildKioskWebView",
        "Cleared initial blank history for warm WebView: $currentUrl"
    )
}

private fun injectDebugToolIfNeeded(
    webView: WebView,
    context: Context,
    config: WebViewRuntimeConfig,
    currentTiming: String
) {
    val timingMode = config.injectTimingMode
    if (currentTiming != "BOTH" && timingMode != "BOTH" && timingMode != currentTiming) {
        return
    }

    val tool = config.webDebugTool
    if (tool == "NONE") {
        return
    }

    when (tool) {
        "VCONSOLE" -> {
            val cdnUrl = config.vConsoleCdnUrl
            injectCdnScript(webView, context, cdnUrl, "VCONSOLE", "new VConsole();")
        }
        "ERUDA" -> {
            val cdnUrl = config.erudaCdnUrl
            injectCdnScript(webView, context, cdnUrl, "ERUDA", "eruda.init();")
        }
    }
}

private fun injectCustomScriptIfNeeded(
    webView: WebView,
    config: WebViewRuntimeConfig,
    currentTiming: String
) {
    if (!config.customJsInjectEnabled) {
        return
    }
    val timing = config.customJsInjectTiming
    if (currentTiming != "BOTH" && timing != "BOTH" && timing != currentTiming) {
        return
    }

    val url = config.customJsInjectUrl.trim()
    val code = config.customJsInjectCode.trim()

    if (url.isEmpty() && code.isEmpty()) {
        return
    }

    if (url.isNotEmpty()) {
        // 有外链，需要先动态 append 外部 JS 链接，onload 后执行 code
        val urlJson = JSONObject.quote(url)
        val codeJson = JSONObject.quote(code)
        val js = """
            (function() {
                if (window.__custom_script_injected__) return;
                window.__custom_script_injected__ = true;
                
                var script = document.createElement('script');
                script.src = $urlJson;
                script.onload = function() {
                    try {
                        (0, eval)($codeJson);
                    } catch(e) {
                        console.error('Custom injected JS code error:', e);
                    }
                };
                script.onerror = function() {
                    console.error('Failed to load custom script from URL:', $urlJson);
                };
                (document.head || document.documentElement).appendChild(script);
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    } else {
        // 没有外链，直接自执行 code
        val js = """
            (function() {
                if (window.__custom_code_injected__) return;
                window.__custom_code_injected__ = true;
                try {
                    $code
                } catch(e) {
                    console.error('Custom injected JS error:', e);
                }
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }
}

private fun injectPageScripts(
    webView: WebView?,
    context: Context,
    config: WebViewRuntimeConfig,
    currentTiming: String
) {
    webView ?: return
    injectCosmeticCssIfNeeded(webView, context, config)
    injectFilterScriptletsIfNeeded(webView, context, config)
    injectDebugToolIfNeeded(webView, context, config, currentTiming)
    injectCustomScriptIfNeeded(webView, config, currentTiming)
}

private fun injectCosmeticCssIfNeeded(
    webView: WebView,
    context: Context,
    config: WebViewRuntimeConfig
) {
    if (!config.limitAdBlock || !config.filterSnapshot.enabled) return
    val pageUrl = webView.url ?: return
    val host = WebViewRuntime.hostOf(pageUrl)
    if (host.isBlank()) return
    val siteOverride = FilterRepository.siteOverrideFor(config.filterSnapshot, host)
    val css = FilterRepository.getEngine(context, config.filterSnapshot)
        .cosmeticCssFor(host, siteOverride)
        .take(256 * 1024)
    if (css.isBlank()) return
    val cssJson = JSONObject.quote(css)
    val js = """
        (function() {
            var id = 'child-kiosk-cosmetic-style';
            var style = document.getElementById(id);
            if (!style) {
                style = document.createElement('style');
                style.id = id;
                (document.head || document.documentElement).appendChild(style);
            }
            style.textContent = $cssJson;
        })();
    """.trimIndent()
    webView.evaluateJavascript(js, null)
}

private fun injectFilterScriptletsIfNeeded(
    webView: WebView,
    context: Context,
    config: WebViewRuntimeConfig
) {
    if (!config.limitAdBlock || !config.filterSnapshot.enabled) return
    val pageUrl = webView.url ?: return
    val host = WebViewRuntime.hostOf(pageUrl)
    if (host.isBlank()) return
    val siteOverride = FilterRepository.siteOverrideFor(config.filterSnapshot, host)
    val scriptlets = FilterRepository.getEngine(context, config.filterSnapshot)
        .scriptletJsFor(host, siteOverride)
    if (scriptlets.isBlank()) return
    val js = """
        (function() {
            if (window.__child_kiosk_filter_scriptlets__) return;
            window.__child_kiosk_filter_scriptlets__ = true;
            $scriptlets
        })();
    """.trimIndent()
    webView.evaluateJavascript(js, null)
}

private fun shouldBlockPopup(
    context: Context,
    parent: WebView?,
    config: WebViewRuntimeConfig
): Boolean {
    val parentUrl = parent?.url.orEmpty()
    if (parentUrl.isBlank()) return false
    val requestContext = FilterRequestContext(
        requestUrl = parentUrl,
        topLevelUrl = parentUrl,
        resourceType = FilterResourceType.POPUP,
        isMainFrame = true,
        method = "GET",
        hasGesture = false
    )
    val siteOverride = FilterRepository.siteOverrideFor(config.filterSnapshot, requestContext.topLevelHost)
    return FilterRepository.getEngine(context, config.filterSnapshot)
        .decide(requestContext, siteOverride)
        .action == FilterAction.BLOCK
}

private fun destroyWebViewSafely(webView: WebView) {
    runCatching {
        webView.stopLoading()
        webView.webChromeClient = null
        webView.webViewClient = WebViewClient()
        runCatching { webView.removeJavascriptInterface("ChildKioskDebugBridge") }
        webView.loadUrl("about:blank")
        webView.clearHistory()
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.removeAllViews()
        webView.destroy()
    }
}

private fun injectCdnScript(webView: WebView, context: Context, cdnUrl: String, toolKey: String, initJs: String) {
    val urlJson = JSONObject.quote(cdnUrl)
    val guardKey = "__debug_tool_injected_$toolKey"
    val guardJson = JSONObject.quote(guardKey)
    val callbackName = "__ChildKioskDebugInject_$toolKey"
    val callbackJson = JSONObject.quote(callbackName)
    val loadedCheck = when (toolKey) {
        "ERUDA" -> "!!window.eruda"
        "VCONSOLE" -> "!!window.VConsole"
        else -> "false"
    }
    ensureDebugBridge(webView, context, callbackName, cdnUrl, guardKey, initJs)
    val js = """
        (function() {
            if (window[$guardJson]) {
                try { $initJs } catch(e) {}
                return 'already';
            }
            window[$guardJson] = true;
            
            var script = document.createElement('script');
            script.src = $urlJson;
            script.onload = function() {
                try {
                    $initJs
                } catch(e) {
                    console.error('Debug tool init error:', e);
                }
            };
            script.onerror = function() {
                console.error('Failed to load debug tool from CDN');
                if (window.ChildKioskDebugBridge && window.ChildKioskDebugBridge.requestFallback) {
                    window.ChildKioskDebugBridge.requestFallback($callbackJson);
                }
            };
            (document.head || document.documentElement).appendChild(script);
            setTimeout(function() {
                try {
                    if (window[$guardJson] && !($loadedCheck) &&
                        window.ChildKioskDebugBridge && window.ChildKioskDebugBridge.requestFallback) {
                        window.ChildKioskDebugBridge.requestFallback($callbackJson);
                    }
                } catch(e) {}
            }, 2500);
            return 'loading';
        })();
    """.trimIndent()
    webView.evaluateJavascript(js) { result ->
        if (result == "\"already\"" || result == "\"loading\"") return@evaluateJavascript
        fetchAndInjectExternalScript(webView, context, cdnUrl, guardKey, initJs)
    }
}

@SuppressLint("JavascriptInterface")
private fun ensureDebugBridge(
    webView: WebView,
    context: Context,
    callbackName: String,
    cdnUrl: String,
    guardKey: String,
    initJs: String
) {
    debugFallbackCallbacks[callbackName] = DebugFallbackRequest(
        webView = WeakReference(webView),
        context = WeakReference(context),
        cdnUrl = cdnUrl,
        guardKey = guardKey,
        initJs = initJs
    )
    runCatching {
        webView.addJavascriptInterface(DebugInjectBridge(), "ChildKioskDebugBridge")
    }
}

private data class DebugFallbackRequest(
    val webView: WeakReference<WebView>,
    val context: WeakReference<Context>,
    val cdnUrl: String,
    val guardKey: String,
    val initJs: String
)

private class DebugInjectBridge {
    @JavascriptInterface
    fun requestFallback(callbackName: String?) {
        callbackName ?: return
        val request = debugFallbackCallbacks[callbackName] ?: return
        val webView = request.webView.get() ?: return
        val context = request.context.get() ?: return
        webView.post {
            fetchAndInjectExternalScript(
                webView = webView,
                context = context,
                cdnUrl = request.cdnUrl,
                guardKey = request.guardKey,
                initJs = request.initJs
            )
        }
    }
}

private fun fetchAndInjectExternalScript(
    webView: WebView,
    context: Context,
    cdnUrl: String,
    guardKey: String,
    initJs: String
) {
    val cacheKey = "$guardKey:$cdnUrl"
    val cached = externalScriptCache[cacheKey]
    if (cached != null) {
        injectRawExternalScript(webView, cached, guardKey, initJs)
        return
    }

    (context as? ComponentActivity)?.lifecycleScope?.launch(Dispatchers.IO) {
        val script = runCatching {
            val connection = URL(cdnUrl).openConnection()
            connection.connectTimeout = 8000
            connection.readTimeout = 8000
            connection.getInputStream().bufferedReader().use { it.readText() }
        }.getOrNull()

        if (!script.isNullOrBlank()) {
            externalScriptCache[cacheKey] = script
            withContext(Dispatchers.Main) {
                injectRawExternalScript(webView, script, guardKey, initJs)
            }
        }
    }
}

private fun injectRawExternalScript(webView: WebView, rawJs: String, guardKey: String, initJs: String) {
    val guardJson = JSONObject.quote(guardKey)
    val sourceJson = JSONObject.quote(rawJs)
    val js = """
        (function() {
            if (window[$guardJson + '_raw']) {
                try { $initJs } catch(e) {}
                return;
            }
            window[$guardJson + '_raw'] = true;
            try {
                (0, eval)($sourceJson);
                $initJs
            } catch(e) {
                console.error('Debug tool raw inject error:', e);
            }
        })();
    """.trimIndent()
    webView.evaluateJavascript(js, null)
}

private val externalScriptCache = ConcurrentHashMap<String, String>()
private val debugFallbackCallbacks = ConcurrentHashMap<String, DebugFallbackRequest>()
