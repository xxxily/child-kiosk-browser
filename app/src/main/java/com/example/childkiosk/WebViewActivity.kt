package com.example.childkiosk

import android.annotation.SuppressLint
import android.app.Activity
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
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.childkiosk.data.AppDatabase
import com.example.childkiosk.data.SystemConfigEntity
import com.example.childkiosk.data.WebAppEntity
import com.example.childkiosk.ui.ParentVerificationDialog
import com.example.childkiosk.ui.QButton
import com.example.childkiosk.ui.theme.ChildKioskTheme
import com.example.childkiosk.util.AdBlocker
import com.example.childkiosk.util.KioskPrefs
import com.example.childkiosk.util.SystemUiHelper
import com.example.childkiosk.util.TimeLimiter
import com.example.childkiosk.util.WebViewRuntime
import com.example.childkiosk.util.WebViewPool
import kotlinx.coroutines.Dispatchers
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
    private val lightweightWebViews = mutableListOf<WebView>()
    private var lightweightRoot: FrameLayout? = null
    private var lightweightProgress: ProgressBar? = null

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
        // 0. 早期屏幕方向设置，避免启动闪烁
        val orientationMode = com.example.childkiosk.util.KioskPrefs.getOrientationMode(this)
        requestedOrientation = when (orientationMode) {
            com.example.childkiosk.util.KioskPrefs.ORIENTATION_PORTRAIT -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            com.example.childkiosk.util.KioskPrefs.ORIENTATION_LANDSCAPE -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            else -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR
        }

        super.onCreate(savedInstanceState)

        // 防截屏逃逸 (根据配置)
        if (com.example.childkiosk.util.KioskPrefs.isLimitFlagSecureEnabled(this)) {
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
        val hostMode = KioskPrefs.getWebViewHostMode(this)
        WebViewRuntime.logAbDiagnostics(this, "activity_created", "webAppId=$webAppId")

        if (hostMode == KioskPrefs.WEBVIEW_HOST_MODE_LIGHTWEIGHT_NATIVE) {
            startLightweightNativeWebView(webAppId)
            return
        }

        Log.d(
            "ChildKioskWebView",
            "Host mode applied: STANDARD_COMPOSE, composeHost=true, overlay=COMPOSE_FULLSCREEN, webAppId=$webAppId"
        )

        setContent {
            ChildKioskTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color.Black
                ) {
                    WebViewScreen(
                        webAppId = webAppId,
                        onWebViewReady = { rootWebView = it },
                        onWebViewReleased = { released ->
                            if (rootWebView == released) {
                                rootWebView = null
                            }
                        },
                        onClose = { finish() }
                    )
                }
            }
        }
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
        if (com.example.childkiosk.util.KioskPrefs.isLimitVolumeKeysEnabled(this)) {
            val keyCode = event.keyCode
            if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    Toast.makeText(this, "音量按键已被家长控制锁定", Toast.LENGTH_SHORT).show()
                }
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onDestroy() {
        exitFullscreenView()
        fileChooserCallback?.onReceiveValue(null)
        fileChooserCallback = null
        if (lightweightWebViews.isNotEmpty()) {
            lightweightWebViews.toList().forEach { destroyWebViewSafely(it) }
            lightweightWebViews.clear()
        } else {
            rootWebView?.let { webView ->
                destroyWebViewSafely(webView)
            }
        }
        lightweightProgress = null
        lightweightRoot = null
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
        SystemUiHelper.enterImmersive(this)
    }

    private fun startLightweightNativeWebView(webAppId: Int) {
        WebViewPool.clear()

        val root = FrameLayout(this).apply {
            setBackgroundColor(android.graphics.Color.WHITE)
        }
        lightweightRoot = root
        setContentView(root)

        val showNativeProgress = KioskPrefs.isLightweightNativeLoadingIndicatorEnabled(this)
        if (showNativeProgress) {
            lightweightProgress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 100
                progress = 0
                visibility = View.GONE
            }
            root.addView(
                lightweightProgress,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    (3 * resources.displayMetrics.density).toInt().coerceAtLeast(3)
                )
            )
        }

        Log.d(
            "ChildKioskWebView",
            "Host mode applied: LIGHTWEIGHT_NATIVE, composeHost=false, root=FrameLayout, " +
                "overlay=${if (showNativeProgress) "NATIVE_TOP_PROGRESS" else "NONE"}, webAppId=$webAppId"
        )
        WebViewRuntime.logAbDiagnostics(this, "lightweight_native_start", "webAppId=$webAppId")

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    handleLightweightBack()
                }
            }
        )

        lifecycleScope.launch {
            val db = AppDatabase.getInstance(this@WebViewActivity)
            val webApp = withContext(Dispatchers.IO) {
                db.webAppDao().getWebAppById(webAppId)
            }
            if (webApp == null) {
                Log.w("ChildKioskWebView", "Lightweight native abort: web app not found, id=$webAppId")
                Toast.makeText(this@WebViewActivity, "网页应用不存在", Toast.LENGTH_SHORT).show()
                finish()
                return@launch
            }
            attachLightweightWebView(root, webApp)
        }
    }

    private fun attachLightweightWebView(root: FrameLayout, webApp: WebAppEntity) {
        val targetUrl = webApp.url
        val originalHost = WebViewRuntime.hostOf(targetUrl)
        val webView = createSecureWebView(
            ctx = this,
            targetUrl = targetUrl,
            originalHost = originalHost,
            onSslError = { url ->
                Log.w("ChildKioskWebView", "Lightweight native SSL error: $url")
                Toast.makeText(this, "SSL 证书异常：$url", Toast.LENGTH_LONG).show()
                hideLightweightProgress()
            },
            onBlocked = { url ->
                Log.w("ChildKioskWebView", "Lightweight native blocked navigation: $url")
                Toast.makeText(this, "已拦截跳转：$url", Toast.LENGTH_LONG).show()
                hideLightweightProgress()
            },
            onDownloadBlocked = {
                Toast.makeText(this, "下载功能已受阻，若要下载应用请联系家长。", Toast.LENGTH_LONG).show()
            },
            onLoadingStateChanged = { loading ->
                Log.d(
                    "ChildKioskWebView",
                    "Lightweight loading state: loading=$loading, progress=${rootWebView?.progress ?: -1}, url=${rootWebView?.url}"
                )
                if (loading) showLightweightProgress() else hideLightweightProgress()
            },
            onProgressUpdate = { progress ->
                lightweightProgress?.progress = progress.coerceIn(0, 100)
            },
            onError = { error ->
                Log.w("ChildKioskWebView", "Lightweight native main frame error: $error")
                Toast.makeText(this, "网页加载异常：$error", Toast.LENGTH_LONG).show()
                hideLightweightProgress()
            },
            onShowFileChooser = { callback, params -> openFileChooser(callback, params) },
            onCreateWindow = { newWebView ->
                Log.d("ChildKioskWebView", "Lightweight native child window created: parent=${rootWebView?.url}")
                attachLightweightChildWebView(root, newWebView)
            }
        )

        attachLightweightChildWebView(root, webView)
        WebViewRuntime.logAbDiagnostics(this, "lightweight_webview_attached", targetUrl)
        loadInitialUrlAfterFirstLayout(webView, targetUrl)
    }

    private fun attachLightweightChildWebView(root: FrameLayout, webView: WebView) {
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
        lightweightWebViews.add(webView)
        logWebViewSurfaceState(webView, "lightweight_attached")
    }

    private fun handleLightweightBack() {
        val current = rootWebView
        if (current != null && current.canGoBack()) {
            Log.d("ChildKioskWebView", "Lightweight back: webView.goBack, url=${current.url}")
            current.goBack()
            return
        }

        if (lightweightWebViews.size > 1) {
            val removed = lightweightWebViews.removeLast()
            Log.d("ChildKioskWebView", "Lightweight back: destroy child webview, url=${removed.url}")
            destroyWebViewSafely(removed)
            rootWebView = lightweightWebViews.lastOrNull()
            rootWebView?.visibility = View.VISIBLE
            return
        }

        if (KioskPrefs.getVerifyOnWebExit(this) && KioskPrefs.getVerifyAdminActions(this)) {
            Log.w("ChildKioskWebView", "Lightweight native exit verification is not shown; closing for AB test mode")
            Toast.makeText(this, "轻量测试模式暂不显示退出验证", Toast.LENGTH_SHORT).show()
        }
        finish()
    }

    private fun showLightweightProgress() {
        lightweightProgress?.visibility = View.VISIBLE
    }

    private fun hideLightweightProgress() {
        lightweightProgress?.visibility = View.GONE
    }

    companion object {
        const val EXTRA_WEB_APP_ID = "WEB_APP_ID"
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebViewScreen(
    webAppId: Int,
    onWebViewReady: (WebView) -> Unit,
    onWebViewReleased: (WebView) -> Unit,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val db = remember { AppDatabase.getInstance(context) }

    var webApp by remember { mutableStateOf<WebAppEntity?>(null) }
    var config by remember { mutableStateOf<SystemConfigEntity?>(null) }


    var sslErrorUrl by remember { mutableStateOf<String?>(null) }
    var blockedUrl by remember { mutableStateOf<String?>(null) }
    var isTimeOut by remember { mutableStateOf(false) }

    val verifyOnExit = remember {
        com.example.childkiosk.util.KioskPrefs.getVerifyOnWebExit(context) &&
        com.example.childkiosk.util.KioskPrefs.getVerifyAdminActions(context)
    }

    var showParentVerifyForClose by remember { mutableStateOf(false) }
    var showParentVerifyForTimeout by remember { mutableStateOf(false) }


    LaunchedEffect(webAppId) {
        withContext(Dispatchers.IO) {
            webApp = db.webAppDao().getWebAppById(webAppId)
            config = db.systemConfigDao().getSystemConfig()
        }
    }

    LaunchedEffect(Unit) {
        db.systemConfigDao().getSystemConfigFlow().collect { latestConfig ->
            config = latestConfig
        }
    }

    val sessionStartTime = remember { System.currentTimeMillis() }

    LaunchedEffect(config) {
        val currentConfig = config ?: return@LaunchedEffect
        if (currentConfig.timeLimitMinutes <= 0 && currentConfig.dailyLimitMinutes <= 0) {
            isTimeOut = false
            return@LaunchedEffect
        }
        if (TimeLimiter.isLimitExceeded(currentConfig)) {
            isTimeOut = true
        }

        var lastPersistedSec = 0L
        while (true) {
            delay(1000)
            val activeConfig = config ?: continue
            val remainingMs = TimeLimiter.calculateRemainingTimeMs(activeConfig, sessionStartTime)
            if (remainingMs != -1L && remainingMs <= 0) {
                isTimeOut = true
            }
            // 每 5 秒持久化一次今日累计时间
            val elapsedSec = (System.currentTimeMillis() - sessionStartTime) / 1000
            if (elapsedSec - lastPersistedSec >= 5) {
                val deltaMs = (elapsedSec - lastPersistedSec) * 1000L
                lastPersistedSec = elapsedSec
                withContext(Dispatchers.IO) {
                    val freshConfig = db.systemConfigDao().getSystemConfig() ?: return@withContext
                    val today = TimeLimiter.getTodayDateString()
                    val baseUsed = if (freshConfig.lastUsedDate == today) freshConfig.usedTimeTodayMs else 0L
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

    val targetUrl = webApp?.url ?: "about:blank"
    val originalHost = remember(targetUrl) {
        WebViewRuntime.hostOf(targetUrl)
    }

    // WebView 池获取：URL 预加载命中时直接接管已加载实例，否则复用空白热备实例后正常 loadUrl。
    val preloadEntry = remember(targetUrl) {
        if (targetUrl != "about:blank") {
            WebViewPool.acquire(targetUrl)
        } else null
    }

    var isPageLoading by remember(preloadEntry) { mutableStateOf(preloadEntry?.isLoaded != true) }
    var shouldShowOverlay by remember(preloadEntry) { mutableStateOf(preloadEntry?.isLoaded != true) }
    var loadProgress by remember(preloadEntry) { mutableIntStateOf(preloadEntry?.progress ?: 0) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var overlayShownTime by remember { mutableLongStateOf(if (preloadEntry?.isLoaded == true) 0L else System.currentTimeMillis()) }
    val mainInitialLoadScheduled = remember(targetUrl) {
        AtomicBoolean(preloadEntry?.isUrlPreload == true)
    }
    val shouldClearPreloadedHistoryNow = preloadEntry?.let {
        it.isUrlPreload && (it.isLoaded || it.progress >= 100)
    } == true
    val shouldClearHistoryOnFirstFinish = preloadEntry?.webView != null && !shouldClearPreloadedHistoryNow

    // 多 Tab WebView 栈管理
    val webViewStack = remember { mutableStateListOf<WebView>() }
    val webViewRef by remember { derivedStateOf { webViewStack.lastOrNull() } }

    LaunchedEffect(isPageLoading, loadError) {
        if (isPageLoading || loadError != null) {
            if (!shouldShowOverlay) {
                overlayShownTime = System.currentTimeMillis()
            }
            shouldShowOverlay = true
        } else {
            val elapsed = System.currentTimeMillis() - overlayShownTime
            if (overlayShownTime > 0L && elapsed < 120L) {
                delay(120L - elapsed)
            }
            shouldShowOverlay = false
            overlayShownTime = 0L
        }
    }

    // 弱网或特殊页面漏发完成回调时的兜底，避免 100% 遮罩长期挡住网页。
    LaunchedEffect(isPageLoading, targetUrl) {
        if (isPageLoading) {
            delay(12000)
            if (isPageLoading) {
                loadProgress = 100
                isPageLoading = false
            }
        }
    }

    // 首次初始化主 WebView
    val mainWebView = remember(targetUrl) {
        if (targetUrl != "about:blank") {
            createSecureWebView(
                ctx = context,
                targetUrl = targetUrl,
                originalHost = originalHost,
                onSslError = { sslErrorUrl = it },
                onBlocked = { blockedUrl = it },
                onDownloadBlocked = {
                    Toast.makeText(context, "下载功能已受阻，若要下载应用请联系家长。", Toast.LENGTH_LONG).show()
                },
                onLoadingStateChanged = { loading ->
                    if (loading) {
                        loadError = null
                    }
                    isPageLoading = loading
                },
                onProgressUpdate = { progress -> loadProgress = progress },
                onError = { error -> loadError = error },
                existingWebView = preloadEntry?.webView,
                clearHistoryOnFirstRealPageFinish = shouldClearHistoryOnFirstFinish,
                onShowFileChooser = { callback, params ->
                    (context as? WebViewActivity)?.openFileChooser(callback, params) ?: false
                },
                onCreateWindow = { newWv ->
                    webViewStack.add(newWv)
                }
            ).also { wv ->
                onWebViewReady(wv)
                if (preloadEntry?.isUrlPreload == true) {
                    wv.post {
                        if (shouldClearPreloadedHistoryNow) {
                            clearInitialBlankHistory(wv, wv.url ?: targetUrl)
                        }
                        scheduleInjectionPasses(wv, context)
                        schedulePageActivation(wv)
                        if (wv.progress >= 100 || preloadEntry.isLoaded) {
                            loadProgress = 100
                            isPageLoading = false
                        }
                    }
                }
            }
        } else null
    }

    fun scheduleMainInitialLoadIfNeeded(view: WebView) {
        if (view != mainWebView || targetUrl == "about:blank" || !mainInitialLoadScheduled.compareAndSet(false, true)) {
            return
        }
        loadInitialUrlAfterFirstLayout(view, targetUrl)
    }

    LaunchedEffect(mainWebView) {
        if (mainWebView != null && webViewStack.isEmpty()) {
            webViewStack.add(mainWebView)
        }
    }

    // 自动资源释放销毁
    DisposableEffect(Unit) {
        onDispose {
            webViewStack.forEach { wv ->
                if (wv == mainWebView) {
                    destroyWebViewSafely(wv)
                    onWebViewReleased(wv)
                } else {
                    destroyWebViewSafely(wv)
                }
            }
            webViewStack.clear()
        }
    }

    BackHandler(enabled = true) {
        val wv = webViewStack.lastOrNull()
        if (wv != null) {
            if (wv.canGoBack()) {
                wv.goBack()
            } else {
                    if (webViewStack.size > 1) {
                        webViewStack.removeLast().apply {
                            destroyWebViewSafely(this)
                        }
                    } else {
                    if (verifyOnExit) {
                        showParentVerifyForClose = true
                    } else {
                        onClose()
                    }
                }
            }
        } else {
            onClose()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFFFF8E1)) // 暖色底色兜底
    ) {
        if (webApp != null) {
            webViewRef?.let { topWebView ->
                key(topWebView) {
                    AndroidView(
                        factory = {
                            scheduleMainInitialLoadIfNeeded(topWebView)
                            topWebView
                        },
                        modifier = Modifier
                            .fillMaxSize(),
                        update = { view ->
                            scheduleMainInitialLoadIfNeeded(view)
                            view.visibility = android.view.View.VISIBLE
                        }
                    )
                }
            }
        }

        if (shouldShowOverlay) {
            if (loadError != null) {
                LoadingErrorOverlay(
                    error = loadError!!,
                    onRetry = {
                        loadError = null
                        isPageLoading = true
                        webViewRef?.reload()
                    },
                    onClose = onClose
                )
            } else {
                LoadingOverlay(webApp = webApp, progress = loadProgress)
            }
        }

        // 已移除进入网页后的右上角隐藏手势框以允许网页右上角按钮正常点击，用户由返回键退回到主屏幕

        AnimatedVisibility(
            visible = sslErrorUrl != null,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            FullScreenAlert(
                gradientColors = listOf(Color(0xFF1A1A1A), Color(0xFF000000)),
                title = "网络安全异常！",
                message = "当前链接存在 SSL 证书错误，可能遭受中间人攻击或劫持：\n${sslErrorUrl ?: ""}\n系统已为您安全阻断。",
                primaryAction = "安全返回主屏幕",
                onPrimary = {
                    sslErrorUrl = null
                    onClose()
                }
            )
        }

        AnimatedVisibility(
            visible = blockedUrl != null,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            FullScreenAlert(
                gradientColors = listOf(Color(0xFF2B0000), Color(0xFF120000)),
                title = "非安全外部链接，已被家长助手拦截",
                message = "试图跳转到：${blockedUrl ?: ""}\n为了儿童的安全，本沙箱仅允许访问原始应用域名。",
                primaryAction = "返回游戏",
                secondaryAction = "返回主屏幕",
                onPrimary = { blockedUrl = null },
                onSecondary = {
                    blockedUrl = null
                    onClose()
                }
            )
        }

        AnimatedVisibility(
            visible = isTimeOut,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        brush = Brush.verticalGradient(
                            colors = listOf(Color(0xFF0D47A1), Color(0xFF1976D2))
                        )
                    )
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = "⏰ 休息时间到了！",
                        fontSize = 32.sp,
                        fontWeight = FontWeight.Black,
                        color = Color.White,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "小眼睛该休息啦！\n去活动一下身体，看看窗外的绿色吧！🌱",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color(0xFFBBDEFB),
                        textAlign = TextAlign.Center,
                        lineHeight = 28.sp
                    )
                    Spacer(modifier = Modifier.height(32.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        QButton(
                            onClick = onClose,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF00C853),
                                contentColor = Color.White
                            )
                        ) {
                            Text("好的，去休息", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        }
                        QButton(
                            onClick = { showParentVerifyForTimeout = true },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color.White.copy(alpha = 0.2f),
                                contentColor = Color.White
                            )
                        ) {
                            Text("家长延长可用时间", fontSize = 14.sp)
                        }
                    }
                }
            }
        }

        if (showParentVerifyForClose) {
            ParentVerificationDialog(
                config = config,
                onDismiss = { showParentVerifyForClose = false },
                onVerified = {
                    showParentVerifyForClose = false
                    onClose()
                }
            )
        }

        if (showParentVerifyForTimeout) {
            ParentVerificationDialog(
                config = config,
                onDismiss = { showParentVerifyForTimeout = false },
                onVerified = {
                    showParentVerifyForTimeout = false
                    scope.launch(Dispatchers.IO) {
                        val freshConfig = db.systemConfigDao().getSystemConfig() ?: return@launch
                        val today = TimeLimiter.getTodayDateString()
                        // 重置今日累计为零，允许继续 30 分钟（通过 dailyLimit 兜底）
                        val grantedDailyLimit = if (freshConfig.dailyLimitMinutes > 0) {
                            freshConfig.dailyLimitMinutes + 30
                        } else 30
                        db.systemConfigDao().insertOrUpdateConfig(
                            freshConfig.copy(
                                dailyLimitMinutes = grantedDailyLimit,
                                lastUsedDate = today
                            )
                        )
                        isTimeOut = false
                    }
                }
            )
        }
    }
}

@Composable
private fun FullScreenAlert(
    gradientColors: List<Color>,
    title: String,
    message: String,
    primaryAction: String,
    secondaryAction: String? = null,
    onPrimary: () -> Unit,
    onSecondary: () -> Unit = {}
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(brush = Brush.verticalGradient(colors = gradientColors))
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                tint = Color(0xFFFF4D4D),
                modifier = Modifier.size(80.dp)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = title,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = message,
                fontSize = 14.sp,
                color = Color.LightGray,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            Spacer(modifier = Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                QButton(onClick = onPrimary) {
                    Text(primaryAction, fontWeight = FontWeight.Bold)
                }
                if (secondaryAction != null) {
                    QButton(
                        onClick = onSecondary,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.DarkGray,
                            contentColor = Color.White
                        )
                    ) {
                        Text(secondaryAction, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
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
    onError: (String) -> Unit,
    existingWebView: WebView? = null,
    clearHistoryOnFirstRealPageFinish: Boolean = false,
    onShowFileChooser: (ValueCallback<Array<Uri>>, WebChromeClient.FileChooserParams?) -> Boolean,
    onCreateWindow: (WebView) -> Unit
): WebView {
    val webView = existingWebView ?: WebView(ctx)
    val shouldClearInitialHistory = AtomicBoolean(clearHistoryOnFirstRealPageFinish)

    return webView.apply {
        WebViewRuntime.applySettings(this, ctx, targetUrl)
        WebViewRuntime.logAbDiagnostics(ctx, "create_secure_webview", targetUrl)
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
                if (view != null) {
                    scheduleInjectionPasses(view, ctx, "PAGE_STARTED")
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
                    scheduleInjectionPasses(view, ctx, "PAGE_FINISHED")
                    schedulePageActivation(view)
                }

                finishLoadingWhenVisuallyReady(view, "PAGE_FINISHED", onLoadingStateChanged)
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

                if (KioskPrefs.isLimitUrlRedirectEnabled(ctx)) {
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
                val url = request?.url?.toString()
                val host = request?.url?.host
                if (KioskPrefs.isLimitAdBlockEnabled(ctx) && AdBlocker.isAdRequest(url ?: host)) {
                    Log.d("ChildKioskWebView", "Blocked ad request: ${url ?: host}")
                    return WebResourceResponse(
                        "text/plain",
                        "utf-8",
                        java.io.ByteArrayInputStream(ByteArray(0))
                    )
                }
                return super.shouldInterceptRequest(view, request)
            }

            @SuppressLint("WebViewClientOnReceivedSslError")
            override fun onReceivedSslError(
                view: WebView?,
                handler: SslErrorHandler?,
                error: SslError?
            ) {
                if (KioskPrefs.isLimitSslCheckEnabled(ctx)) {
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
                if (newProgress >= 100) {
                    view?.postDelayed({
                        schedulePageActivation(view)
                        finishLoadingWhenVisuallyReady(view, "PROGRESS_100", onLoadingStateChanged)
                    }, 250)
                }
            }

            override fun onPermissionRequest(request: PermissionRequest?) {
                if (request == null) return
                val requested = request.resources.orEmpty()
                val allowed = requested.filter { resource ->
                    when (resource) {
                        PermissionRequest.RESOURCE_VIDEO_CAPTURE,
                        PermissionRequest.RESOURCE_AUDIO_CAPTURE -> !KioskPrefs.isLimitMediaCaptureEnabled(ctx)
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
                callback?.invoke(origin, !KioskPrefs.isLimitGeolocationEnabled(ctx), false)
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
                val newWebView = createSecureWebView(
                    ctx = ctx,
                    targetUrl = "",
                    originalHost = originalHost,
                    onSslError = onSslError,
                    onBlocked = onBlocked,
                    onDownloadBlocked = onDownloadBlocked,
                    onLoadingStateChanged = onLoadingStateChanged,
                    onProgressUpdate = onProgressUpdate,
                    onError = onError,
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
            if (KioskPrefs.isLimitDownloadEnabled(ctx)) {
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
            schedulePageActivation(webView)
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

@Composable
private fun LoadingOverlay(
    webApp: WebAppEntity?,
    progress: Int
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(Color(0xFFFFF8E1), Color(0xFFFFECB3))
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            if (webApp != null) {
                val iconPath = webApp.iconPath ?: ""
                val isNetworkIcon = iconPath.startsWith("http://", ignoreCase = true) || 
                                    iconPath.startsWith("https://", ignoreCase = true)
                Box(
                    modifier = Modifier
                        .size(80.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0xFFFFF9C4)),
                    contentAlignment = Alignment.Center
                ) {
                    if (isNetworkIcon) {
                        coil.compose.AsyncImage(
                            model = iconPath,
                            contentDescription = webApp.title,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                            error = androidx.compose.ui.graphics.vector.rememberVectorPainter(Icons.Default.Star)
                        )
                    } else {
                        val iconVector = com.example.childkiosk.ui.getIconVector(webApp.iconPath)
                        Icon(
                            imageVector = iconVector,
                            contentDescription = webApp.title,
                            tint = Color(0xFFFBC02D),
                            modifier = Modifier.size(48.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = webApp.title,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF4E342E)
                )
            } else {
                Text(
                    text = "精彩内容正在加载中",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF4E342E)
                )
            }

            Spacer(modifier = Modifier.height(32.dp))

            Box(contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    progress = progress / 100f,
                    modifier = Modifier.size(72.dp),
                    color = Color(0xFFFBC02D),
                    trackColor = Color(0xFFFFF9C4),
                    strokeWidth = 6.dp
                )
                Text(
                    text = "$progress%",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF4E342E)
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            val tip = remember {
                listOf(
                    "正在为你准备精彩内容...",
                    "马上就好啦，拍拍小手等一下 👏",
                    "精彩即将呈现，小眼睛眨一眨 👀",
                    "正在加载好玩的网站，准备出发 🚀",
                    "小宝贝，稍等片刻哦，精彩内容飞奔而来 🎈"
                ).random()
            }
            Text(
                text = tip,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = Color(0xFF8D6E63)
            )
        }
    }
}

@Composable
private fun LoadingErrorOverlay(
    error: String,
    onRetry: () -> Unit,
    onClose: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(Color(0xFFFFF8E1), Color(0xFFFFECB3))
                )
            )
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                tint = Color(0xFFFF4D4D),
                modifier = Modifier.size(80.dp)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "网络连接有点小问题哦",
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF4E342E),
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = error,
                fontSize = 14.sp,
                color = Color(0xFF8D6E63),
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            Spacer(modifier = Modifier.height(32.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                QButton(
                    onClick = onRetry,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF00C853),
                        contentColor = Color.White
                    )
                ) {
                    Text("重试一下", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
                QButton(
                    onClick = onClose,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.DarkGray,
                        contentColor = Color.White
                    )
                ) {
                    Text("返回乐园", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

private fun injectDebugToolIfNeeded(webView: WebView, context: Context, currentTiming: String) {
    val timingMode = KioskPrefs.getInjectTimingMode(context)
    if (currentTiming != "BOTH" && timingMode != "BOTH" && timingMode != currentTiming) {
        return
    }

    val tool = KioskPrefs.getWebDebugTool(context)
    if (tool == "NONE") {
        return
    }

    when (tool) {
        "VCONSOLE" -> {
            val cdnUrl = KioskPrefs.getVConsoleCdnUrl(context)
            injectCdnScript(webView, context, cdnUrl, "VCONSOLE", "new VConsole();")
        }
        "ERUDA" -> {
            val cdnUrl = KioskPrefs.getErudaCdnUrl(context)
            injectCdnScript(webView, context, cdnUrl, "ERUDA", "eruda.init();")
        }
    }
}

private fun injectCustomScriptIfNeeded(webView: WebView, context: Context, currentTiming: String) {
    if (!KioskPrefs.isCustomJsInjectEnabled(context)) {
        return
    }
    val timing = KioskPrefs.getCustomJsInjectTiming(context)
    if (currentTiming != "BOTH" && timing != "BOTH" && timing != currentTiming) {
        return
    }

    val url = KioskPrefs.getCustomJsInjectUrl(context).trim()
    val code = KioskPrefs.getCustomJsInjectCode(context).trim()

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

private fun scheduleInjectionPasses(webView: WebView, context: Context, primaryTiming: String = "BOTH") {
    injectHighDprRenderCompatIfNeeded(webView, context)
    injectDebugToolIfNeeded(webView, context, primaryTiming)
    injectCustomScriptIfNeeded(webView, context, primaryTiming)

    if (!KioskPrefs.isWebViewDelayedInjectionPassesEnabled(context)) {
        Log.d("ChildKioskWebView", "Delayed injection passes skipped: url=${webView.url}")
        return
    }

    listOf(250L, 1000L, 2500L).forEach { delayMs ->
        webView.postDelayed({
            injectHighDprRenderCompatIfNeeded(webView, context)
            injectDebugToolIfNeeded(webView, context, "BOTH")
            injectCustomScriptIfNeeded(webView, context, "BOTH")
        }, delayMs)
    }
}

private fun injectHighDprRenderCompatIfNeeded(webView: WebView, context: Context) {
    if (!WebViewRuntime.isHighDprRenderCompatEnabled(context)) return

    val genericCssJson = JSONObject.quote(HIGH_DPR_RENDER_COMPAT_CSS)
    val pianoCssJson = JSONObject.quote(PIANO_RENDER_COMPAT_CSS)
    val booksCssJson = JSONObject.quote(BOOKS_RENDER_COMPAT_CSS)
    val reason = WebViewRuntime.highDprRenderCompatReason(context)
    val js = """
        (function() {
            try {
                var version = '20260614-1';
                var styleId = 'child-kiosk-high-dpr-render-compat';
                var genericCss = $genericCssJson;
                var pianoCss = $pianoCssJson;
                var booksCss = $booksCssJson;
                var path = location.pathname || '';
                var host = location.hostname || '';
                var isPiano = host === 'pages.anzz.site' && path.indexOf('/app/piano') === 0;
                var isBooks = host === 'pages.anzz.site' && path.indexOf('/books') === 0;
                var profile = isPiano ? 'piano' : (isBooks ? 'books' : 'generic');
                var style = document.getElementById(styleId);
                var alreadyCurrent = style &&
                    style.getAttribute('data-version') === version &&
                    style.getAttribute('data-profile') === profile;

                if (!alreadyCurrent) {
                    if (!style) {
                        style = document.createElement('style');
                        style.id = styleId;
                        (document.head || document.documentElement || document.body).appendChild(style);
                    }
                    style.setAttribute('data-version', version);
                    style.setAttribute('data-profile', profile);
                    style.textContent = genericCss + (isPiano ? '\n' + pianoCss : '') + (isBooks ? '\n' + booksCss : '');
                    document.documentElement.setAttribute('data-child-kiosk-render-compat', 'high-dpr');
                }

                function positionPianoBlackKeys() {
                    var whiteKeys = document.querySelectorAll('.white-key');
                    var blackKeys = document.querySelectorAll('.black-key');
                    if (!whiteKeys.length || !blackKeys.length) return;
                    var blackKeyWhiteIndices = [0, 1, 3, 4, 5];
                    blackKeys.forEach(function(blackKey, index) {
                        var octaveNumber = Math.floor(index / 5);
                        var posInOctave = index % 5;
                        var whiteKeyIndex = blackKeyWhiteIndices[posInOctave] + octaveNumber * 7;
                        var whiteKey = whiteKeys[whiteKeyIndex];
                        if (whiteKey) {
                            blackKey.style.left = (whiteKey.offsetLeft + whiteKey.offsetWidth * 0.6) + 'px';
                        }
                    });
                }

                if (isPiano) {
                    var piano = document.getElementById('piano');
                    if (piano) {
                        piano.style.setProperty('--child-kiosk-key-width', '32px');
                    }
                    var effectsLayer = document.getElementById('effects-layer');
                    if (effectsLayer) {
                        effectsLayer.style.display = 'none';
                    }
                    var wrapper = document.getElementById('piano-wrapper');
                    var display = document.getElementById('key-width-display');
                    if (wrapper && display) {
                        display.textContent = Math.max(1, Math.floor(wrapper.clientWidth / 32)) + '键';
                    }
                    [0, 50, 250, 1000].forEach(function(delayMs) {
                        setTimeout(positionPianoBlackKeys, delayMs);
                    });
                }

                return alreadyCurrent ? (profile + '-existing') : profile;
            } catch (e) {
                return 'error:' + (e && e.message ? e.message : e);
            }
        })();
    """.trimIndent()

    webView.evaluateJavascript(js) { result ->
        if (result != "\"generic-existing\"" && result != "\"piano-existing\"" && result != "\"books-existing\"") {
            Log.d(
                "ChildKioskWebView",
                "High DPR render compat injected: result=$result, $reason, url=${webView.url}"
            )
        }
    }
}

private fun schedulePageActivation(webView: WebView?) {
    webView ?: return
    if (!KioskPrefs.isWebViewPageActivationEnabled(webView.context)) {
        Log.d("ChildKioskWebView", "Page activation skipped: url=${webView.url}")
        return
    }
    listOf(0L, 120L, 450L, 1200L).forEach { delayMs ->
        webView.postDelayed({
            refreshPageViewportState(webView)
        }, delayMs)
    }
}

private fun refreshPageViewportState(webView: WebView) {
    val js = """
        (function() {
            function fire(target, name) {
                try { target.dispatchEvent(new Event(name)); } catch(e) {}
            }
            function pulse() {
                fire(window, 'focus');
                fire(window, 'pageshow');
                fire(window, 'resize');
                fire(window, 'scroll');
                fire(document, 'visibilitychange');
                if (window.visualViewport) {
                    fire(window.visualViewport, 'resize');
                    fire(window.visualViewport, 'scroll');
                }
                try {
                    var root = document.scrollingElement || document.documentElement || document.body;
                    if (root) {
                        var x = window.scrollX || root.scrollLeft || 0;
                        var y = window.scrollY || root.scrollTop || 0;
                        root.getBoundingClientRect();
                        if (root.scrollHeight > root.clientHeight) {
                            root.scrollTop = y + 1;
                            root.scrollTop = y;
                            window.scrollTo(x, y);
                        }
                    }
                } catch(e) {}
            }
            pulse();
            try {
                requestAnimationFrame(function() {
                    pulse();
                    setTimeout(pulse, 80);
                });
            } catch(e) {
                setTimeout(pulse, 80);
            }
            return true;
        })();
    """.trimIndent()
    webView.evaluateJavascript(js, null)
}

private fun waitForMeaningfulContent(webView: WebView?, onResult: (Boolean) -> Unit) {
    if (webView == null) {
        onResult(false)
        return
    }
    val js = """
        (function() {
            var body = document.body;
            if (!body) return false;
            var rect = body.getBoundingClientRect();
            var text = (body.innerText || '').trim();
            var visibleNodes = body.querySelectorAll('canvas, img, video, svg, button, input, textarea, select, [role], [data-slot], main, section, article').length;
            return !!(text.length > 0 || visibleNodes > 0 || rect.height > 0);
        })();
    """.trimIndent()
    webView.evaluateJavascript(js) { result ->
        onResult(result == "true" || result == "\"true\"")
    }
}

private fun finishLoadingWhenVisuallyReady(
    webView: WebView?,
    reason: String,
    onLoadingStateChanged: (Boolean) -> Unit
) {
    if (webView == null) {
        onLoadingStateChanged(false)
        return
    }

    if (!KioskPrefs.isWebViewVisualStateCallbackEnabled(webView.context)) {
        waitForMeaningfulContent(webView) { hasContent ->
            Log.d(
                "ChildKioskWebView",
                "Loading finish fallback: reason=$reason, hasContent=$hasContent, url=${webView.url}"
            )
            if (hasContent) {
                onLoadingStateChanged(false)
            } else {
                webView.postDelayed({ onLoadingStateChanged(false) }, 800)
            }
        }
        return
    }

    val delivered = AtomicBoolean(false)
    val requestId = System.nanoTime()
    Log.d(
        "ChildKioskWebView",
        "Visual state callback requested: id=$requestId, reason=$reason, progress=${webView.progress}, url=${webView.url}"
    )
    webView.postVisualStateCallback(
        requestId,
        object : WebView.VisualStateCallback() {
            override fun onComplete(requestId: Long) {
                if (delivered.compareAndSet(false, true)) {
                    Log.d(
                        "ChildKioskWebView",
                        "Visual state callback delivered: id=$requestId, reason=$reason, progress=${webView.progress}, url=${webView.url}"
                    )
                    onLoadingStateChanged(false)
                }
            }
        }
    )
    webView.postDelayed({
        if (delivered.compareAndSet(false, true)) {
            Log.w(
                "ChildKioskWebView",
                "Visual state callback timeout: id=$requestId, reason=$reason, progress=${webView.progress}, url=${webView.url}"
            )
            onLoadingStateChanged(false)
        }
    }, 1500)
}

private val HIGH_DPR_RENDER_COMPAT_CSS = """
html[data-child-kiosk-render-compat="high-dpr"],
html[data-child-kiosk-render-compat="high-dpr"] * {
    scroll-behavior: auto !important;
}

html[data-child-kiosk-render-compat="high-dpr"] *,
html[data-child-kiosk-render-compat="high-dpr"] *::before,
html[data-child-kiosk-render-compat="high-dpr"] *::after {
    will-change: auto !important;
    -webkit-backface-visibility: visible !important;
    backface-visibility: visible !important;
    -webkit-filter: none !important;
    filter: none !important;
    -webkit-backdrop-filter: none !important;
    backdrop-filter: none !important;
    animation-duration: 0.001ms !important;
    animation-iteration-count: 1 !important;
    transition-duration: 0.001ms !important;
}

html[data-child-kiosk-render-compat="high-dpr"] [style*="will-change"] {
    will-change: auto !important;
}
""".trimIndent()

private val PIANO_RENDER_COMPAT_CSS = """
html[data-child-kiosk-render-compat="high-dpr"] #effects-layer {
    display: none !important;
}

html[data-child-kiosk-render-compat="high-dpr"] #piano-container,
html[data-child-kiosk-render-compat="high-dpr"] #top-bar,
html[data-child-kiosk-render-compat="high-dpr"] #quick-controls,
html[data-child-kiosk-render-compat="high-dpr"] .icon-btn,
html[data-child-kiosk-render-compat="high-dpr"] .record-toggle-btn,
html[data-child-kiosk-render-compat="high-dpr"] .scroll-hint {
    box-shadow: none !important;
}

html[data-child-kiosk-render-compat="high-dpr"] #piano-container {
    border-top-width: 1px !important;
    border-bottom-width: 2px !important;
}

html[data-child-kiosk-render-compat="high-dpr"] #piano {
    --key-width: var(--child-kiosk-key-width, 32px) !important;
    padding: 4px 0 !important;
    contain: layout paint style !important;
}

html[data-child-kiosk-render-compat="high-dpr"] .white-key {
    width: var(--child-kiosk-key-width, 32px) !important;
    min-width: var(--child-kiosk-key-width, 32px) !important;
    box-shadow: none !important;
    border-radius: 0 0 4px 4px !important;
    transition: none !important;
}

html[data-child-kiosk-render-compat="high-dpr"] .black-key {
    width: calc(var(--child-kiosk-key-width, 32px) * 0.64) !important;
    box-shadow: none !important;
    border-radius: 0 0 3px 3px !important;
    transition: none !important;
}

html[data-child-kiosk-render-compat="high-dpr"] .white-key.next-note,
html[data-child-kiosk-render-compat="high-dpr"] .black-key.next-note,
html[data-child-kiosk-render-compat="high-dpr"] .record-toggle-btn.recording,
html[data-child-kiosk-render-compat="high-dpr"] .rec-dot,
html[data-child-kiosk-render-compat="high-dpr"] .key-glow {
    animation: none !important;
    box-shadow: none !important;
}

html[data-child-kiosk-render-compat="high-dpr"] .white-key.pressed::after,
html[data-child-kiosk-render-compat="high-dpr"] .black-key.pressed::after {
    background: rgba(233, 69, 96, 0.18) !important;
}
""".trimIndent()

private val BOOKS_RENDER_COMPAT_CSS = """
html[data-child-kiosk-render-compat="high-dpr"] .hero {
    animation: none !important;
    background-size: 100% 100% !important;
}

html[data-child-kiosk-render-compat="high-dpr"] .hero h1,
html[data-child-kiosk-render-compat="high-dpr"] .hero p,
html[data-child-kiosk-render-compat="high-dpr"] .hero-meta,
html[data-child-kiosk-render-compat="high-dpr"] .scroll-hint,
html[data-child-kiosk-render-compat="high-dpr"] .article-content,
html[data-child-kiosk-render-compat="high-dpr"] .category-card,
html[data-child-kiosk-render-compat="high-dpr"] .tag,
html[data-child-kiosk-render-compat="high-dpr"] .progress-bar {
    box-shadow: none !important;
    text-shadow: none !important;
}

html[data-child-kiosk-render-compat="high-dpr"] .category-card:hover,
html[data-child-kiosk-render-compat="high-dpr"] .tag:hover {
    transform: none !important;
}
""".trimIndent()

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
