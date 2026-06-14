package com.example.childkiosk

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.*
import android.widget.Toast
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
import androidx.compose.ui.draw.alpha
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

class WebViewActivity : ComponentActivity() {

    private var rootWebView: WebView? = null
    private var fileChooserCallback: ValueCallback<Array<Uri>>? = null
    private var fullscreenView: View? = null
    private var fullscreenCallback: WebChromeClient.CustomViewCallback? = null

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
        rootWebView?.let { webView ->
            if (!WebViewPool.recycleBlank(webView)) {
                destroyWebViewSafely(webView)
            }
        }
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
            if (overlayShownTime > 0L && elapsed < 600L) {
                delay(600L - elapsed)
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
                        scheduleInjectionPasses(wv, context)
                        if (wv.progress >= 100 || preloadEntry.isLoaded) {
                            loadProgress = 100
                            isPageLoading = false
                        }
                    }
                } else {
                    wv.loadUrl(targetUrl)
                }
            }
        } else null
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
                    if (!WebViewPool.recycleBlank(wv)) {
                        destroyWebViewSafely(wv)
                    }
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
            webViewStack.forEachIndexed { index, wv ->
                val isTop = index == webViewStack.lastIndex
                AndroidView(
                    factory = { wv },
                    modifier = Modifier
                        .fillMaxSize()
                        .alpha(if (isTop) 1f else 0f),
                    update = { view ->
                        view.visibility = if (isTop) android.view.View.VISIBLE else android.view.View.GONE
                    }
                )
            }
        }

        AnimatedVisibility(
            visible = shouldShowOverlay,
            exit = fadeOut(animationSpec = androidx.compose.animation.core.tween(400))
        ) {
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
    onShowFileChooser: (ValueCallback<Array<Uri>>, WebChromeClient.FileChooserParams?) -> Boolean,
    onCreateWindow: (WebView) -> Unit
): WebView {
    val webView = existingWebView ?: WebView(ctx)

    return webView.apply {
        WebViewRuntime.applySettings(this, ctx, targetUrl)

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
                // 网页载入完成后恢复白色背景，防止无背景网页的文字无法看清
                view?.setBackgroundColor(android.graphics.Color.WHITE)

                if (view != null) {
                    scheduleInjectionPasses(view, ctx, "PAGE_FINISHED")
                }

                waitForMeaningfulContent(view) { hasContent ->
                    if (hasContent) {
                        onLoadingStateChanged(false)
                    } else {
                        view?.postDelayed({ onLoadingStateChanged(false) }, 800)
                    }
                }
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame == true) {
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
                        waitForMeaningfulContent(view) { onLoadingStateChanged(false) }
                    }, 250)
                }
            }

            override fun onPermissionRequest(request: PermissionRequest?) {
                if (request == null) return
                val requested = request.resources.orEmpty()
                val allowed = requested.filter { resource ->
                    when (resource) {
                        PermissionRequest.RESOURCE_VIDEO_CAPTURE,
                        PermissionRequest.RESOURCE_AUDIO_CAPTURE -> !KioskPrefs.isLimitGeolocationEnabled(ctx)
                        else -> true
                    }
                }.toTypedArray()
                if (allowed.isEmpty()) {
                    request.deny()
                } else {
                    request.grant(allowed)
                }
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

        setDownloadListener { _, _, _, _, _ ->
            if (com.example.childkiosk.util.KioskPrefs.isLimitDownloadEnabled(ctx)) {
                onDownloadBlocked()
            }
        }
    }
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
    injectDebugToolIfNeeded(webView, context, primaryTiming)
    injectCustomScriptIfNeeded(webView, context, primaryTiming)

    listOf(250L, 1000L, 2500L).forEach { delayMs ->
        webView.postDelayed({
            injectDebugToolIfNeeded(webView, context, "BOTH")
            injectCustomScriptIfNeeded(webView, context, "BOTH")
        }, delayMs)
    }
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
