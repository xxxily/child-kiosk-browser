package com.example.childkiosk

import android.annotation.SuppressLint
import android.content.Context
import android.net.http.SslError
import android.os.Bundle
import android.view.KeyEvent
import android.view.ViewGroup
import android.webkit.*
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
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
import com.example.childkiosk.util.SystemUiHelper
import com.example.childkiosk.util.TimeLimiter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URL

class WebViewActivity : ComponentActivity() {

    private var rootWebView: WebView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        // 0. 早期屏幕方向设置，避免启动闪烁
        val orientationMode = com.example.childkiosk.util.KioskPrefs.getOrientationMode(this)
        requestedOrientation = when (orientationMode) {
            com.example.childkiosk.util.KioskPrefs.ORIENTATION_PORTRAIT -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            com.example.childkiosk.util.KioskPrefs.ORIENTATION_LANDSCAPE -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            else -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR
        }

        super.onCreate(savedInstanceState)

        // 防截屏逃逸
        window.setFlags(
            android.view.WindowManager.LayoutParams.FLAG_SECURE,
            android.view.WindowManager.LayoutParams.FLAG_SECURE
        )

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

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        rootWebView?.let {
            it.loadUrl("about:blank")
            it.clearHistory()
            it.clearCache(true)
            (it.parent as? ViewGroup)?.removeView(it)
            it.removeAllViews()
            it.destroy()
        }
        rootWebView = null
        super.onDestroy()
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
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val db = remember { AppDatabase.getInstance(context) }

    var webApp by remember { mutableStateOf<WebAppEntity?>(null) }
    var config by remember { mutableStateOf<SystemConfigEntity?>(null) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    var sslErrorUrl by remember { mutableStateOf<String?>(null) }
    var blockedUrl by remember { mutableStateOf<String?>(null) }
    var isTimeOut by remember { mutableStateOf(false) }

    var showParentVerifyForClose by remember { mutableStateOf(false) }
    var showParentVerifyForTimeout by remember { mutableStateOf(false) }

    val clicks = remember { mutableStateListOf<Long>() }

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
        runCatching { URL(targetUrl).host }.getOrNull()?.lowercase().orEmpty()
    }

    BackHandler(enabled = true) {
        val wv = webViewRef
        if (wv != null && wv.canGoBack()) {
            wv.goBack()
        } else {
            showParentVerifyForClose = true
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (webApp != null) {
            AndroidView(
                factory = { ctx ->
                    createSecureWebView(
                        ctx = ctx,
                        targetUrl = targetUrl,
                        originalHost = originalHost,
                        onSslError = { sslErrorUrl = it },
                        onBlocked = { blockedUrl = it },
                        onDownloadBlocked = {
                            Toast.makeText(ctx, "下载功能已受阻，若要下载应用请联系家长。", Toast.LENGTH_LONG).show()
                        }
                    ).also { wv ->
                        webViewRef = wv
                        onWebViewReady(wv)
                        wv.loadUrl(targetUrl)
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        }

        // 右上角隐藏点击手势区域 (80dp x 80dp)
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .size(80.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) {
                    val now = System.currentTimeMillis()
                    clicks.add(now)
                    if (clicks.size > 5) clicks.removeAt(0)
                    if (clicks.size == 5 && (now - clicks[0]) <= 2000) {
                        clicks.clear()
                        showParentVerifyForClose = true
                    }
                }
        )

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
    onDownloadBlocked: () -> Unit
): WebView {
    return WebView(ctx).apply {
        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true

            allowFileAccess = false
            allowContentAccess = false
            allowFileAccessFromFileURLs = false
            allowUniversalAccessFromFileURLs = false

            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false

            saveFormData = false
            @Suppress("DEPRECATION")
            savePassword = false
            setGeolocationEnabled(false)

            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = if (targetUrl.startsWith("http://", ignoreCase = true)) {
                WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            } else {
                WebSettings.MIXED_CONTENT_NEVER_ALLOW
            }

            cacheMode = WebSettings.LOAD_DEFAULT
            useWideViewPort = true
            loadWithOverviewMode = true
            textZoom = 100
        }

        // 禁用长按选择，防儿童误触召唤复制/分享菜单
        setOnLongClickListener { true }
        isLongClickable = false

        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val urlStr = request?.url?.toString() ?: return false

                if (!urlStr.startsWith("http://", true) && !urlStr.startsWith("https://", true)) {
                    onBlocked(urlStr)
                    return true
                }

                val host = runCatching { URL(urlStr).host }.getOrNull()?.lowercase().orEmpty()
                if (originalHost.isNotEmpty() && host.isNotEmpty()) {
                    val isAllowed = host == originalHost || host.endsWith(".$originalHost")
                    if (!isAllowed) {
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
                val host = request?.url?.host
                if (AdBlocker.isAdHost(host)) {
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
                handler?.cancel()
                onSslError(error?.url ?: "未知链接")
            }
        }

        webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest?) {
                request?.deny()
            }

            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: android.os.Message?
            ): Boolean {
                // 禁止任何 window.open 弹窗
                return false
            }
        }

        setDownloadListener { _, _, _, _, _ ->
            onDownloadBlocked()
        }
    }
}
