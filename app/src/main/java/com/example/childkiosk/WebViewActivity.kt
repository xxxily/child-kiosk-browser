package com.example.childkiosk

import android.annotation.SuppressLint
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.ViewGroup
import android.webkit.*
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
import com.example.childkiosk.util.TimeLimiter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URL

class WebViewActivity : ComponentActivity() {

    private var webView: WebView? = null
    private var webAppId: Int = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // 1. 设置 FLAG_SECURE 防截屏逃逸
        window.setFlags(
            android.view.WindowManager.LayoutParams.FLAG_SECURE,
            android.view.WindowManager.LayoutParams.FLAG_SECURE
        )

        webAppId = intent.getIntExtra("WEB_APP_ID", -1)

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color.Black
                ) {
                    WebViewScreen(
                        webAppId = webAppId,
                        onClose = { finish() }
                    )
                }
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // 拦截音量键，消费掉
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        // 显式彻底销毁 WebView，防止内存泄露
        webView?.let {
            it.loadUrl("about:blank")
            it.clearHistory()
            it.clearCache(true)
            it.removeAllViews()
            (it.parent as? ViewGroup)?.removeView(it)
            it.destroy()
        }
        webView = null
        super.onDestroy()
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebViewScreen(
    webAppId: Int,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val db = remember { AppDatabase.getInstance(context) }
    
    var webApp by remember { mutableStateOf<WebAppEntity?>(null) }
    var config by remember { mutableStateOf<SystemConfigEntity?>(null) }
    
    // UI 警告与锁定状态
    var sslErrorUrl by remember { mutableStateOf<String?>(null) }
    var blockedUrl by remember { mutableStateOf<String?>(null) }
    var isTimeOut by remember { mutableStateOf(false) }
    
    // 家长验证控制
    var showParentVerifyForClose by remember { mutableStateOf(false) }
    var showParentVerifyForTimeout by remember { mutableStateOf(false) }
    
    // 隐藏点击手势计数
    val clicks = remember { mutableStateListOf<Long>() }

    // 初始化数据
    LaunchedEffect(webAppId) {
        withContext(Dispatchers.IO) {
            webApp = db.webAppDao().getWebAppById(webAppId)
            config = db.systemConfigDao().getSystemConfig()
        }
    }

    // 跨进程同步最新的系统配置
    LaunchedEffect(Unit) {
        db.systemConfigDao().getSystemConfigFlow().collect { latestConfig ->
            config = latestConfig
        }
    }

    // 计时与时长扣减协程
    val sessionStartTime = remember { System.currentTimeMillis() }
    LaunchedEffect(config) {
        val currentConfig = config ?: return@LaunchedEffect
        if (currentConfig.timeLimitMinutes <= 0 && currentConfig.dailyLimitMinutes <= 0) {
            isTimeOut = false
            return@LaunchedEffect
        }
        
        // 检查当前是否已超限
        if (TimeLimiter.isLimitExceeded(currentConfig)) {
            isTimeOut = true
        }

        while (true) {
            delay(1000)
            val activeConfig = config ?: continue
            val remainingMs = TimeLimiter.calculateRemainingTimeMs(activeConfig, sessionStartTime)
            if (remainingMs != -1L && remainingMs <= 0) {
                isTimeOut = true
            }

            // 每 5 秒钟向 Room 数据库更新一次今日已玩时间
            val elapsedSec = (System.currentTimeMillis() - sessionStartTime) / 1000
            if (elapsedSec > 0 && elapsedSec % 5 == 0L) {
                withContext(Dispatchers.IO) {
                    val freshConfig = db.systemConfigDao().getSystemConfig() ?: return@withContext
                    val today = TimeLimiter.getTodayDateString()
                    val usedToday = if (freshConfig.lastUsedDate == today) {
                        freshConfig.usedTimeTodayMs + 5000L
                    } else {
                        5000L // 跨天重置
                    }
                    db.systemConfigDao().insertOrUpdateConfig(
                        freshConfig.copy(
                            usedTimeTodayMs = usedToday,
                            lastUsedDate = today
                        )
                    )
                }
            }
        }
    }

    val targetUrl = webApp?.url ?: "about:blank"
    val originalHost = remember(targetUrl) {
        runCatching { URL(targetUrl).host }.getOrNull() ?: ""
    }

    BackHandler(enabled = true) {
        // 优先使用 WebView back
        val wv = (context as? WebViewActivity)?.findViewById<WebView>(android.R.id.content)
        if (wv != null && wv.canGoBack()) {
            wv.goBack()
        } else {
            // 已退无可退，触发家长验证后才允许返回
            showParentVerifyForClose = true
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (webApp != null) {
            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        // 关联到 Activity 以便清理和按键拦截
                        (ctx as? WebViewActivity)?.let { activity ->
                            // 利用反射或者 tag 绑定，在此处给 activity 持有 webView 引用
                            val viewGroup = activity.window.decorView.findViewById<ViewGroup>(android.R.id.content)
                            // 存储到 activity 的 private 属性中
                            val field = WebViewActivity::class.java.getDeclaredField("webView")
                            field.isAccessible = true
                            field.set(activity, this)
                        }

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
                            savePassword = false
                            setGeolocationEnabled(false)
                            
                            mediaPlaybackRequiresUserGesture = false
                            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                        }

                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                                val urlStr = request?.url?.toString() ?: return false
                                
                                // 1. Scheme 拦截：只允许 http 和 https
                                if (!urlStr.startsWith("http://") && !urlStr.startsWith("https://")) {
                                    return true // 拦截其他类似 market://, weixin:// 的唤起
                                }

                                // 2. 同源 Host 校验
                                val currentHost = runCatching { URL(urlStr).host }.getOrNull() ?: ""
                                if (originalHost.isNotEmpty() && currentHost.isNotEmpty()) {
                                    val isAllowed = currentHost == originalHost || currentHost.endsWith(".$originalHost")
                                    if (!isAllowed) {
                                        blockedUrl = urlStr
                                        return true // 拦截非同源跳转
                                    }
                                }
                                return false
                            }

                            @SuppressLint("WebViewClientOnReceivedSslError")
                            override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
                                // 强制 cancel，绝不 proceed
                                handler?.cancel()
                                sslErrorUrl = error?.url ?: "未知链接"
                            }
                        }

                        webChromeClient = object : WebChromeClient() {
                            override fun onPermissionRequest(request: PermissionRequest?) {
                                // 拒绝所有麦克风、摄像头权限
                                request?.deny()
                            }
                        }

                        setDownloadListener { _, _, _, _, _ ->
                            Toast.makeText(context, "下载功能已受阻，若要下载应用请联系家长。", Toast.LENGTH_LONG).show()
                        }

                        loadUrl(targetUrl)
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
                    if (clicks.size > 5) {
                        clicks.removeAt(0)
                    }
                    if (clicks.size == 5 && (now - clicks[0]) <= 2000) {
                        clicks.clear()
                        showParentVerifyForClose = true
                    }
                }
        )

        // SSL 错误警示页面
        AnimatedVisibility(
            visible = sslErrorUrl != null,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF1A1A1A))
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = "Security Alert",
                        tint = Color.Red,
                        modifier = Modifier.size(80.dp)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "网络安全异常！",
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "当前链接存在 SSL 证书错误，可能遭受中间人攻击或劫持：\n$sslErrorUrl\n系统已为您安全阻断。",
                        fontSize = 14.sp,
                        color = Color.Gray,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    QButton(onClick = {
                        sslErrorUrl = null
                        onClose()
                    }) {
                        Text("安全返回主屏幕", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // 同源域名拦截警告页面
        AnimatedVisibility(
            visible = blockedUrl != null,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        brush = Brush.verticalGradient(
                            colors = listOf(Color(0xFF2B0000), Color(0xFF120000))
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
                        contentDescription = "Blocked URL",
                        tint = Color(0xFFFF4D4D),
                        modifier = Modifier.size(80.dp)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "非安全外部链接，已被家长助手拦截",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "试图跳转到：$blockedUrl\n为了儿童的安全，本沙箱仅允许访问原始应用域名。",
                        fontSize = 14.sp,
                        color = Color.LightGray,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        QButton(
                            onClick = { blockedUrl = null },
                            colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray, contentColor = Color.White)
                        ) {
                            Text("返回游戏", fontWeight = FontWeight.Bold)
                        }
                        QButton(onClick = {
                            blockedUrl = null
                            onClose()
                        }) {
                            Text("返回主屏幕", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        // 超时健康限制全屏覆盖锁屏 UI
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
                    // 儿童友好插画/提醒
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
                            onClick = { onClose() },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00C853), contentColor = Color.White)
                        ) {
                            Text("好的，去休息", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        }
                        
                        QButton(
                            onClick = { showParentVerifyForTimeout = true },
                            colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.2f), contentColor = Color.White)
                        ) {
                            Text("家长延长可用时间", fontSize = 14.sp)
                        }
                    }
                }
            }
        }

        // 主动退出验证弹窗
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

        // 超时解除验证弹窗
        if (showParentVerifyForTimeout) {
            ParentVerificationDialog(
                config = config,
                onDismiss = { showParentVerifyForTimeout = false },
                onVerified = {
                    showParentVerifyForTimeout = false
                    // 验证通过，延长每日和单次限制时长各 30 分钟以供继续使用
                    scope.launch(Dispatchers.IO) {
                        val freshConfig = db.systemConfigDao().getSystemConfig() ?: return@launch
                        val today = TimeLimiter.getTodayDateString()
                        
                        // 为了重置单次限时，我们需要对 usedTimeTodayMs 进行补偿调整，或者直接扩大 dailyLimitMinutes。
                        // 这里我们选择直接为家长增加 30 分钟的可用配额
                        val newDailyLimit = if (freshConfig.dailyLimitMinutes > 0) freshConfig.dailyLimitMinutes + 30 else 30
                        val newTimeLimit = if (freshConfig.timeLimitMinutes > 0) freshConfig.timeLimitMinutes + 30 else 30
                        
                        db.systemConfigDao().insertOrUpdateConfig(
                            freshConfig.copy(
                                dailyLimitMinutes = newDailyLimit,
                                timeLimitMinutes = newTimeLimit,
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
