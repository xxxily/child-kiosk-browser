package site.anzz.childkiosk

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.*
import android.widget.Button
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import site.anzz.childkiosk.data.AppDatabase
import site.anzz.childkiosk.data.BrowserHistoryEntity
import site.anzz.childkiosk.data.SystemConfigEntity
import site.anzz.childkiosk.data.WebAppEntity
import site.anzz.childkiosk.ui.AddEditWebAppDialog
import site.anzz.childkiosk.ui.browser.BrowserTab
import site.anzz.childkiosk.ui.browser.TabStateInfo
import site.anzz.childkiosk.ui.browser.TabMemoryCache
import site.anzz.childkiosk.ui.browser.TabCacheItem
import site.anzz.childkiosk.ui.browser.FloatingBrowserControlsCallbacks
import site.anzz.childkiosk.ui.browser.FloatingBrowserControlsOverlay
import site.anzz.childkiosk.ui.browser.FloatingBrowserControlsState
import site.anzz.childkiosk.util.AdBlocker
import site.anzz.childkiosk.util.HashUtils
import site.anzz.childkiosk.util.KioskPrefs
import site.anzz.childkiosk.util.SystemUiHelper
import site.anzz.childkiosk.util.TimeLimiter
import site.anzz.childkiosk.util.WebViewRuntime
import site.anzz.childkiosk.util.WebViewRuntimeConfig
import site.anzz.childkiosk.util.WebViewPool
import site.anzz.childkiosk.ui.theme.ChildKioskTheme
import site.anzz.childkiosk.util.filter.FilterAction
import site.anzz.childkiosk.util.filter.FilterRepository
import site.anzz.childkiosk.util.filter.FilterRequestContext
import site.anzz.childkiosk.util.filter.FilterResourceType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.net.URL
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

private object FilterBlockLogLimiter {
    private const val MAX_LOGS_PER_SECOND = 8
    private val windowStartMs = AtomicLong(0L)
    private val windowCount = AtomicInteger(0)

    fun shouldLog(nowMs: Long = System.currentTimeMillis()): Boolean {
        val windowStart = windowStartMs.get()
        if (nowMs - windowStart >= 1_000L && windowStartMs.compareAndSet(windowStart, nowMs)) {
            windowCount.set(0)
        }
        return windowCount.incrementAndGet() <= MAX_LOGS_PER_SECOND
    }
}

class WebViewActivity : ComponentActivity() {

    private val tabList = mutableListOf<BrowserTab>()
    internal var activeTabId: String? = null
    private val MAX_ACTIVE_WEBVIEWS = 2
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
    private var forceRefreshDialog: AlertDialog? = null
    private var timeLimitJob: Job? = null
    private var sessionStartTimeMs: Long = 0L
    private var currentPageLoading = false
    private var currentPageProgress = 0
    private var navigationRootHost = ""
    private var launchedWebAppId: Int? = null
    private var lastRecordedHistoryUrl: String = ""
    private var lastRecordedHistoryAtMs: Long = 0L
    private lateinit var runtimeConfig: WebViewRuntimeConfig
    private var pendingGeolocationRequest: PendingGeolocationRequest? = null
    private var geolocationPermissionDialog: AlertDialog? = null
    private var pendingMediaPermissionRequest: PendingMediaPermissionRequest? = null
    private var pendingDownloadRequest: PendingDownloadRequest? = null
    private var downloadPermissionDialog: AlertDialog? = null
    private var bookmarkEditorView: ComposeView? = null
    private var customDialogView: ComposeView? = null
    internal val lastAttemptedSchemeMap = java.util.concurrent.ConcurrentHashMap<String, String>()

    private fun showCustomComposeDialog(content: @Composable () -> Unit) {
        val root = webViewRoot ?: return
        dismissCustomComposeDialog()
        val dialogView = ComposeView(this).apply {
            setContent {
                ChildKioskTheme {
                    content()
                }
            }
        }
        customDialogView = dialogView
        root.addView(
            dialogView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
    }

    private fun dismissCustomComposeDialog() {
        customDialogView?.let { view ->
            (view.parent as? ViewGroup)?.removeView(view)
            view.disposeComposition()
        }
        customDialogView = null
    }

    private data class PendingGeolocationRequest(
        val origin: String?,
        val callback: GeolocationPermissions.Callback
    )

    private data class PendingMediaPermissionRequest(
        val request: PermissionRequest,
        val origin: String,
        val resources: Array<String>
    )

    private data class PendingDownloadRequest(
        val url: String,
        val userAgent: String?,
        val contentDisposition: String?,
        val mimeType: String?
    )

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

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = hasLocationPermission() ||
            permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (granted) {
            finishPendingGeolocationRequest(allow = true, retain = true)
        } else {
            finishPendingGeolocationRequest(allow = false, retain = false)
            Toast.makeText(this, "未获得系统定位权限，网页无法获取位置", Toast.LENGTH_SHORT).show()
        }
    }

    private val mediaPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        finishPendingMediaPermissionRequest(permissions)
    }

    private val downloadStoragePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val pending = pendingDownloadRequest ?: return@registerForActivityResult
        pendingDownloadRequest = null
        if (granted || !requiresLegacyDownloadStoragePermission()) {
            enqueueDownload(
                this,
                pending.url,
                pending.userAgent,
                pending.contentDisposition,
                pending.mimeType
            )
        } else {
            Toast.makeText(this, "未获得存储权限，无法保存下载文件", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        runtimeConfig = KioskPrefs.getWebViewRuntimeConfig(intent, this)

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode =
                android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

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

        applySystemUiMode()

        // 监听 System UI / Window 边距变化，锁定态下被手势短暂唤起后自动收回。
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            window.decorView.setOnApplyWindowInsetsListener { view, insets ->
                val shouldReapply = if (shouldUseNormalSystemBars()) {
                    !shouldShowNormalStatusBar() &&
                        insets.isVisible(android.view.WindowInsets.Type.statusBars())
                } else {
                    insets.isVisible(android.view.WindowInsets.Type.statusBars()) ||
                        insets.isVisible(android.view.WindowInsets.Type.navigationBars())
                }
                if (shouldReapply) {
                    view.postDelayed({
                        if (!isDestroyed && !isFinishing) {
                            applySystemUiMode()
                        }
                    }, 3000)
                }
                view.onApplyWindowInsets(insets)
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.setOnSystemUiVisibilityChangeListener { visibility ->
                if ((!shouldUseNormalSystemBars() || !shouldShowNormalStatusBar()) &&
                    (visibility and android.view.View.SYSTEM_UI_FLAG_FULLSCREEN) == 0) {
                    window.decorView.postDelayed({
                        if (!isDestroyed && !isFinishing) {
                            applySystemUiMode()
                        }
                    }, 3000)
                }
            }
        }

        val webAppId = intent.getIntExtra(EXTRA_WEB_APP_ID, -1)
        launchedWebAppId = webAppId.takeIf { it > 0 }
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
        applySystemUiMode()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applySystemUiMode()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applySystemUiMode()
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
        forceRefreshDialog?.dismiss()
        forceRefreshDialog = null
        geolocationPermissionDialog?.dismiss()
        geolocationPermissionDialog = null
        finishPendingGeolocationRequest(allow = false, retain = false)
        cancelPendingMediaPermissionRequest()
        downloadPermissionDialog?.dismiss()
        downloadPermissionDialog = null
        pendingDownloadRequest = null
        dismissBookmarkEditor()
        exitFullscreenView()
        fileChooserCallback?.onReceiveValue(null)
        fileChooserCallback = null
        tabList.forEach { tab ->
            tab.webView?.let { destroyWebViewSafely(it) }
        }
        tabList.clear()
        webViewStack.clear()
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
        val origin = currentPageOrigin()
        val latestConfig = latestRuntimeConfig()
        if (latestConfig.limitFileChooser) {
            callback.onReceiveValue(null)
            Toast.makeText(this, "网页文件选择功能已受限制", Toast.LENGTH_SHORT).show()
            return true
        }
        if (isOriginBlacklisted(latestConfig.fileChooserBlacklist, origin)) {
            callback.onReceiveValue(null)
            Toast.makeText(this, "已拒绝该网站选择文件", Toast.LENGTH_SHORT).show()
            return true
        }

        fileChooserCallback?.onReceiveValue(null)
        fileChooserCallback = callback

        showCustomComposeDialog {
            BeautifulConfirmDialog(
                title = "允许网站选择文件？",
                message = "${displayOrigin(origin)} 正在请求打开系统文件选择器，用于上传图片、视频或其他文件。",
                icon = Icons.Default.Info,
                blacklistText = "拒绝且不再提示（加入黑名单）",
                onNegative = {
                    dismissCustomComposeDialog()
                    fileChooserCallback = null
                    callback.onReceiveValue(null)
                },
                onPositive = {
                    dismissCustomComposeDialog()
                    launchFileChooserIntent(callback, params)
                },
                onBlacklist = {
                    dismissCustomComposeDialog()
                    fileChooserCallback = null
                    callback.onReceiveValue(null)
                    if (origin.isNotBlank()) {
                        addFileChooserOriginToBlacklist(origin)
                        Toast.makeText(this@WebViewActivity, "已将该网站加入文件选择黑名单", Toast.LENGTH_SHORT).show()
                    }
                },
                onDismiss = {
                    dismissCustomComposeDialog()
                    fileChooserCallback = null
                    callback.onReceiveValue(null)
                }
            )
        }
        return true
    }

    private fun launchFileChooserIntent(
        callback: ValueCallback<Array<Uri>>,
        params: WebChromeClient.FileChooserParams?
    ): Boolean {
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

    fun requestGeolocationPermission(
        origin: String?,
        callback: GeolocationPermissions.Callback?
    ) {
        if (callback == null) return
        val normalizedOrigin = normalizePermissionOrigin(origin)
        val siteName = displayOrigin(normalizedOrigin.ifBlank { origin })
        val latestConfig = latestRuntimeConfig()

        if (latestConfig.limitGeolocation) {
            callback.invoke(origin, false, false)
            Toast.makeText(this, "网页定位功能已受限制", Toast.LENGTH_SHORT).show()
            return
        }

        if (isOriginBlacklisted(latestConfig.geolocationBlacklist, normalizedOrigin)) {
            Log.d("ChildKioskWebView", "Geolocation origin is in blacklist: $normalizedOrigin")
            callback.invoke(origin, false, false)
            return
        }

        finishPendingGeolocationRequest(allow = false, retain = false)
        geolocationPermissionDialog?.dismiss()
        dismissCustomComposeDialog()

        pendingGeolocationRequest = PendingGeolocationRequest(origin, callback)

        showCustomComposeDialog {
            BeautifulConfirmDialog(
                title = "允许网站获取位置？",
                message = "$siteName 正在请求获取当前设备位置。\n（这有助于提供本地化的服务或内容）",
                icon = Icons.Default.LocationOn,
                blacklistText = "拒绝且不再提示（加入黑名单）",
                onNegative = {
                    dismissCustomComposeDialog()
                    finishPendingGeolocationRequest(allow = false, retain = false)
                },
                onPositive = {
                    dismissCustomComposeDialog()
                    if (hasLocationPermission()) {
                        finishPendingGeolocationRequest(allow = true, retain = true)
                    } else {
                        requestAndroidLocationPermission()
                    }
                },
                onBlacklist = {
                    dismissCustomComposeDialog()
                    finishPendingGeolocationRequest(allow = false, retain = false)
                    if (normalizedOrigin.isNotBlank()) {
                        addGeolocationOriginToBlacklist(normalizedOrigin)
                        Toast.makeText(this@WebViewActivity, "已将该网址加入定位黑名单", Toast.LENGTH_SHORT).show()
                    }
                },
                onDismiss = {
                    dismissCustomComposeDialog()
                    finishPendingGeolocationRequest(allow = false, retain = false)
                }
            )
        }
    }

    fun requestMediaPermission(request: PermissionRequest?) {
        request ?: return
        val origin = normalizePermissionOrigin(request.origin?.toString()).ifBlank { currentPageOrigin() }
        val resources = request.resources.orEmpty().filter { resource ->
            resource == PermissionRequest.RESOURCE_VIDEO_CAPTURE ||
                resource == PermissionRequest.RESOURCE_AUDIO_CAPTURE
        }.distinct().toTypedArray()
        if (resources.isEmpty()) {
            request.deny()
            return
        }

        val latestConfig = latestRuntimeConfig()
        val blockedByGlobal = resources.any { resource ->
            when (resource) {
                PermissionRequest.RESOURCE_VIDEO_CAPTURE -> latestConfig.limitCameraCapture
                PermissionRequest.RESOURCE_AUDIO_CAPTURE -> latestConfig.limitMicrophoneCapture
                else -> true
            }
        }
        if (blockedByGlobal) {
            request.deny()
            Toast.makeText(this, "网页摄像头或麦克风功能已受限制", Toast.LENGTH_SHORT).show()
            return
        }

        val blockedBySite = resources.any { resource ->
            when (resource) {
                PermissionRequest.RESOURCE_VIDEO_CAPTURE -> isOriginBlacklisted(latestConfig.cameraBlacklist, origin)
                PermissionRequest.RESOURCE_AUDIO_CAPTURE -> isOriginBlacklisted(latestConfig.microphoneBlacklist, origin)
                else -> true
            }
        }
        if (blockedBySite) {
            request.deny()
            Toast.makeText(this, "已拒绝该网站使用摄像头或麦克风", Toast.LENGTH_SHORT).show()
            return
        }

        cancelPendingMediaPermissionRequest()
        dismissCustomComposeDialog()
        pendingMediaPermissionRequest = PendingMediaPermissionRequest(request, origin, resources)
        val permissionNames = mediaPermissionNames(resources)

        showCustomComposeDialog {
            BeautifulConfirmDialog(
                title = "允许网站使用$permissionNames？",
                message = "${displayOrigin(origin)} 正在请求使用$permissionNames。仅在确认站点可信时允许。",
                icon = Icons.Default.Info,
                blacklistText = "拒绝且不再提示（加入黑名单）",
                onNegative = {
                    dismissCustomComposeDialog()
                    cancelPendingMediaPermissionRequest()
                },
                onPositive = {
                    dismissCustomComposeDialog()
                    val missing = missingAndroidMediaPermissions(resources)
                    if (missing.isEmpty()) {
                        grantPendingMediaPermissionRequest()
                    } else {
                        requestAndroidMediaPermissions(missing)
                    }
                },
                onBlacklist = {
                    dismissCustomComposeDialog()
                    denyPendingMediaPermissionRequest(addToBlacklist = true)
                },
                onDismiss = {
                    dismissCustomComposeDialog()
                    cancelPendingMediaPermissionRequest()
                }
            )
        }
    }

    internal fun handleCustomSchemeRedirect(urlStr: String, scheme: String) {
        dismissCustomComposeDialog()
        val normalizedScheme = scheme.trim().removeSuffix("://").removeSuffix(":").lowercase(Locale.US)
        if (normalizedScheme.isBlank()) return

        showCustomComposeDialog {
            BeautifulConfirmDialog(
                title = "允许网页唤起外部应用？",
                message = "网页正在请求打开第三方应用 (协议: $normalizedScheme://)。\n这可能会跳转至其他软件，请确认是否安全。",
                icon = Icons.Default.Share,
                blacklistText = "拒绝且不再提示（加入黑名单）",
                onNegative = {
                    dismissCustomComposeDialog()
                },
                onPositive = {
                    dismissCustomComposeDialog()
                    try {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(urlStr))
                        startActivity(intent)
                    } catch (e: Exception) {
                        Log.w("ChildKioskWebView", "Failed to launch external app: $urlStr", e)
                        Toast.makeText(this@WebViewActivity, "无法打开对应的外部应用", Toast.LENGTH_SHORT).show()
                    }
                },
                onBlacklist = {
                    dismissCustomComposeDialog()
                    KioskPrefs.addSchemeToBlacklist(this@WebViewActivity, normalizedScheme)
                    val currentList = runtimeConfig.schemeBlacklist.toMutableSet()
                    currentList.add(normalizedScheme)
                    runtimeConfig = runtimeConfig.copy(schemeBlacklist = currentList)
                    Toast.makeText(this@WebViewActivity, "已将协议 [$normalizedScheme] 加入 Scheme 黑名单", Toast.LENGTH_SHORT).show()
                },
                onDismiss = {
                    dismissCustomComposeDialog()
                }
            )
        }
    }

    fun requestDownload(
        url: String?,
        userAgent: String?,
        contentDisposition: String?,
        mimeType: String?
    ) {
        if (url.isNullOrBlank()) return
        val uri = runCatching { Uri.parse(url) }.getOrNull()
        val scheme = uri?.scheme?.lowercase()
        val isNormalMode = KioskPrefs.getProtectionMode(this) == KioskPrefs.MODE_NONE
        if (!isNormalMode && scheme != "http" && scheme != "https") {
            Toast.makeText(this, "暂不支持此下载链接", Toast.LENGTH_SHORT).show()
            return
        }

        if (requiresLegacyDownloadStoragePermission() && !hasLegacyDownloadStoragePermission()) {
            pendingDownloadRequest = PendingDownloadRequest(url, userAgent, contentDisposition, mimeType)
            showLegacyDownloadPermissionPrompt()
        } else {
            enqueueDownload(this, url, userAgent, contentDisposition, mimeType)
        }
    }

    private fun applySystemUiMode() {
        if (shouldUseNormalSystemBars()) {
            SystemUiHelper.enterNormal(
                this,
                showStatusBar = shouldShowNormalStatusBar(),
                decorFitsSystemWindows = false
            )
        } else {
            SystemUiHelper.enterImmersive(this)
        }
        webViewRoot?.let { ViewCompat.requestApplyInsets(it) }
    }

    private fun shouldUseNormalSystemBars(): Boolean {
        return runtimeConfig.normalSystemBars
    }

    private fun shouldShowNormalStatusBar(): Boolean {
        return false
    }

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestAndroidLocationPermission() {
        runCatching {
            locationPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }.onFailure { e ->
            Log.w("ChildKioskWebView", "Location permission request failed", e)
            finishPendingGeolocationRequest(allow = false, retain = false)
            Toast.makeText(this, "无法请求系统定位权限", Toast.LENGTH_SHORT).show()
        }
    }

    private fun requestAndroidMediaPermissions(permissions: Array<String>) {
        runCatching {
            mediaPermissionLauncher.launch(permissions)
        }.onFailure { e ->
            Log.w("ChildKioskWebView", "Media permission request failed", e)
            cancelPendingMediaPermissionRequest()
            Toast.makeText(this, "无法请求系统摄像头或麦克风权限", Toast.LENGTH_SHORT).show()
        }
    }

    private fun finishPendingGeolocationRequest(allow: Boolean, retain: Boolean) {
        val pending = pendingGeolocationRequest ?: return
        pendingGeolocationRequest = null
        pending.callback.invoke(pending.origin, allow, retain)
    }

    private fun finishPendingMediaPermissionRequest(grantedPermissions: Map<String, Boolean>) {
        val pending = pendingMediaPermissionRequest ?: return
        val allGranted = pending.resources.all { resource ->
            when (resource) {
                PermissionRequest.RESOURCE_VIDEO_CAPTURE ->
                    hasCameraPermission() || grantedPermissions[Manifest.permission.CAMERA] == true
                PermissionRequest.RESOURCE_AUDIO_CAPTURE ->
                    hasMicrophonePermission() || grantedPermissions[Manifest.permission.RECORD_AUDIO] == true
                else -> false
            }
        }
        pendingMediaPermissionRequest = null
        if (allGranted) {
            pending.request.grant(pending.resources)
        } else {
            pending.request.deny()
            Toast.makeText(this, "未获得系统摄像头或麦克风权限，网页无法使用该功能", Toast.LENGTH_SHORT).show()
        }
    }

    private fun cancelPendingMediaPermissionRequest() {
        val pending = pendingMediaPermissionRequest ?: return
        pendingMediaPermissionRequest = null
        pending.request.deny()
    }

    private fun grantPendingMediaPermissionRequest() {
        val pending = pendingMediaPermissionRequest ?: return
        pendingMediaPermissionRequest = null
        pending.request.grant(pending.resources)
    }

    private fun denyPendingMediaPermissionRequest(addToBlacklist: Boolean) {
        val pending = pendingMediaPermissionRequest ?: return
        pendingMediaPermissionRequest = null
        pending.request.deny()
        if (addToBlacklist && pending.origin.isNotBlank()) {
            if (pending.resources.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE)) {
                addCameraOriginToBlacklist(pending.origin)
            }
            if (pending.resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)) {
                addMicrophoneOriginToBlacklist(pending.origin)
            }
            Toast.makeText(this, "已将该网站加入摄像头/麦克风黑名单", Toast.LENGTH_SHORT).show()
        }
    }

    private fun missingAndroidMediaPermissions(resources: Array<String>): Array<String> {
        return buildList {
            if (resources.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE) && !hasCameraPermission()) {
                add(Manifest.permission.CAMERA)
            }
            if (resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE) && !hasMicrophonePermission()) {
                add(Manifest.permission.RECORD_AUDIO)
            }
        }.toTypedArray()
    }

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasMicrophonePermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun mediaPermissionNames(resources: Array<String>): String {
        val names = buildList {
            if (resources.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE)) add("摄像头")
            if (resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)) add("麦克风")
        }
        return names.joinToString("和").ifBlank { "媒体设备" }
    }

    private fun displayOrigin(origin: String?): String {
        if (origin.isNullOrBlank()) return "当前网站"
        return runCatching {
            Uri.parse(origin).host?.takeIf { it.isNotBlank() } ?: origin
        }.getOrDefault(origin)
    }

    internal fun latestRuntimeConfig(): WebViewRuntimeConfig {
        return runtimeConfig
    }

    private fun normalizePermissionOrigin(origin: String?): String {
        return KioskPrefs.normalizeOriginKey(origin.orEmpty())
    }

    private fun currentPageOrigin(): String {
        return originForWebStorage(rootWebView?.url.orEmpty()).orEmpty()
    }

    private fun isOriginBlacklisted(blacklist: Set<String>, origin: String): Boolean {
        val normalized = normalizePermissionOrigin(origin)
        if (normalized.isBlank()) return false
        if (blacklist.contains(normalized)) return true
        val host = runCatching { Uri.parse(normalized).host?.lowercase(Locale.US) }.getOrNull()
        return host != null && blacklist.contains(host)
    }

    private fun addGeolocationOriginToBlacklist(origin: String) {
        val normalized = normalizePermissionOrigin(origin)
        if (normalized.isBlank()) return
        KioskPrefs.addGeolocationToBlacklist(this, normalized)
        val currentList = runtimeConfig.geolocationBlacklist.toMutableSet()
        currentList.add(normalized)
        runtimeConfig = runtimeConfig.copy(geolocationBlacklist = currentList)
    }

    private fun addCameraOriginToBlacklist(origin: String) {
        val normalized = normalizePermissionOrigin(origin)
        if (normalized.isBlank()) return
        KioskPrefs.addCameraToBlacklist(this, normalized)
        val currentList = runtimeConfig.cameraBlacklist.toMutableSet()
        currentList.add(normalized)
        runtimeConfig = runtimeConfig.copy(cameraBlacklist = currentList)
    }

    private fun addMicrophoneOriginToBlacklist(origin: String) {
        val normalized = normalizePermissionOrigin(origin)
        if (normalized.isBlank()) return
        KioskPrefs.addMicrophoneToBlacklist(this, normalized)
        val currentList = runtimeConfig.microphoneBlacklist.toMutableSet()
        currentList.add(normalized)
        runtimeConfig = runtimeConfig.copy(microphoneBlacklist = currentList)
    }

    private fun addFileChooserOriginToBlacklist(origin: String) {
        val normalized = normalizePermissionOrigin(origin)
        if (normalized.isBlank()) return
        KioskPrefs.addFileChooserToBlacklist(this, normalized)
        val currentList = runtimeConfig.fileChooserBlacklist.toMutableSet()
        currentList.add(normalized)
        runtimeConfig = runtimeConfig.copy(fileChooserBlacklist = currentList)
    }

    private fun requiresLegacyDownloadStoragePermission(): Boolean {
        return Build.VERSION.SDK_INT <= Build.VERSION_CODES.P
    }

    private fun hasLegacyDownloadStoragePermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun showLegacyDownloadPermissionPrompt() {
        downloadPermissionDialog?.dismiss()
        val fileName = pendingDownloadRequest?.let {
            URLUtil.guessFileName(it.url, it.contentDisposition, it.mimeType)
        } ?: "文件"
        val dialog = AlertDialog.Builder(this)
            .setTitle("允许保存下载文件？")
            .setMessage("需要存储权限才能将 $fileName 保存到下载目录。")
            .setNegativeButton("取消") { _, _ ->
                pendingDownloadRequest = null
            }
            .setPositiveButton("允许") { _, _ ->
                runCatching {
                    downloadStoragePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                }.onFailure { e ->
                    Log.w("ChildKioskWebView", "Download storage permission request failed", e)
                    pendingDownloadRequest = null
                    Toast.makeText(this, "无法请求存储权限", Toast.LENGTH_SHORT).show()
                }
            }
            .setOnCancelListener {
                pendingDownloadRequest = null
            }
            .create()
        dialog.setOnDismissListener {
            if (downloadPermissionDialog === dialog) {
                downloadPermissionDialog = null
            }
        }
        downloadPermissionDialog = dialog
        dialog.show()
    }

    fun enterFullscreenView(view: View?, callback: WebChromeClient.CustomViewCallback?) {
        if (latestRuntimeConfig().limitFullscreenVideo) {
            callback?.onCustomViewHidden()
            Toast.makeText(this, "网页全屏视频已受限制", Toast.LENGTH_SHORT).show()
            return
        }
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
        rootWebView?.let { setWebViewVisible(it, false) }
        SystemUiHelper.enterImmersive(this)
    }

    fun exitFullscreenView() {
        val view = fullscreenView ?: return
        (view.parent as? ViewGroup)?.removeView(view)
        fullscreenView = null
        fullscreenCallback?.onCustomViewHidden()
        fullscreenCallback = null
        rootWebView?.let { setWebViewVisible(it, true) }
        if (runtimeConfig.floatingBrowserControlsEnabled) {
            floatingControlsOverlay?.visibility = View.VISIBLE
            updateFloatingControlsState()
        }
        applySystemUiMode()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        val webAppId = intent.getIntExtra(EXTRA_WEB_APP_ID, -1)
        val customUrl = intent.getStringExtra(EXTRA_CUSTOM_URL)
        val switchTabId = intent.getStringExtra(EXTRA_SWITCH_TAB_ID)
        val closeTabId = intent.getStringExtra(EXTRA_CLOSE_TAB_ID)

        if (TabMemoryCache.tabList.isNotEmpty() && tabList.isEmpty()) {
            tabList.clear()
            TabMemoryCache.tabList.forEach { cached ->
                tabList.add(
                    BrowserTab(
                        id = cached.id,
                        url = cached.url,
                        title = cached.title,
                        savedState = cached.savedState,
                        lastActiveTimeMs = cached.lastActiveTimeMs
                    )
                )
            }
            activeTabId = TabMemoryCache.activeTabId
        }

        if (!switchTabId.isNullOrBlank()) {
            switchToTab(switchTabId)
            return
        }

        if (!closeTabId.isNullOrBlank()) {
            closeTab(closeTabId)
            return
        }

        lifecycleScope.launch {
            val db = AppDatabase.getInstance(this@WebViewActivity)
            val webApp = if (webAppId != -1) {
                withContext(Dispatchers.IO) {
                    db.webAppDao().getWebAppById(webAppId)
                }
            } else null

            withContext(Dispatchers.Main) {
                if (webApp != null) {
                    launchedWebAppId = webApp.id
                    val existing = tabList.firstOrNull { it.url == webApp.url }
                    if (existing != null) {
                        switchToTab(existing.id)
                    } else {
                        createNewTab(webApp.url, focus = true)
                    }
                } else if (!customUrl.isNullOrBlank()) {
                    launchedWebAppId = null
                    val existing = tabList.firstOrNull { it.url == customUrl }
                    if (existing != null) {
                        switchToTab(existing.id)
                    } else {
                        createNewTab(customUrl, focus = true)
                    }
                } else {
                    val activeId = activeTabId
                    if (!activeId.isNullOrBlank() && tabList.any { it.id == activeId }) {
                        switchToTab(activeId)
                    } else if (tabList.isNotEmpty()) {
                        switchToTab(tabList.first().id)
                    } else {
                        createNewTab("about:blank", focus = true)
                    }
                }
            }
        }
    }

    private fun startNativeWebView(webAppId: Int) {
        sessionStartTimeMs = System.currentTimeMillis()
        val root = FrameLayout(this).apply {
            setBackgroundColor(android.graphics.Color.WHITE)
        }
        webViewRoot = root
        setContentView(root)
        installWebViewRootInsets(root)
        applySystemUiMode()

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
            withContext(Dispatchers.IO) {
                if (runtimeConfig.limitAdBlock && runtimeConfig.filterSnapshot.enabled) {
                    runCatching {
                        FilterRepository.getEngine(
                            this@WebViewActivity.applicationContext,
                            runtimeConfig.filterSnapshot
                        )
                    }.onFailure { e ->
                        Log.w("ChildKioskWebView", "Filter engine prewarm failed", e)
                    }
                }
            }
            withContext(Dispatchers.Main) {
                handleIntent(intent)
            }
        }
    }

    private fun installWebViewRootInsets(root: FrameLayout) {
        val initialLeft = root.paddingLeft
        val initialTop = root.paddingTop
        val initialRight = root.paddingRight
        val initialBottom = root.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val navigationBars = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            val shouldInsetForNormalMode = shouldUseNormalSystemBars()
            view.setPadding(
                initialLeft + if (shouldInsetForNormalMode) navigationBars.left else 0,
                initialTop, // 移除对 statusBars.top 的 padding，使 WebView 延伸到状态栏下方以实现透明底状态栏
                initialRight + if (shouldInsetForNormalMode) navigationBars.right else 0,
                initialBottom + if (shouldInsetForNormalMode) navigationBars.bottom else 0
            )
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }

    private fun addWebViewToRoot(webView: WebView) {
        val root = webViewRoot ?: return
        if (webView.parent == root) return
        (webView.parent as? ViewGroup)?.removeView(webView)
        installPullToRefreshGesture(webView)
        root.addView(
            webView,
            0,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
    }

    private fun removeWebViewFromRoot(webView: WebView) {
        (webView.parent as? ViewGroup)?.removeView(webView)
    }

    private fun setWebViewVisible(webView: WebView, visible: Boolean) {
        webView.visibility = if (visible) View.VISIBLE else View.GONE
    }

    private fun installPullToRefreshGesture(webView: WebView) {
        val touchSlop = android.view.ViewConfiguration.get(this).scaledTouchSlop
        val triggerDistance = (resources.displayMetrics.density * 96f).coerceAtLeast((touchSlop * 4).toFloat())
        var downY = 0f
        var tracking = false
        var triggered = false
        webView.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    downY = event.y
                    tracking = latestRuntimeConfig().pullToRefreshEnabled &&
                        fullscreenView == null &&
                        !currentPageLoading &&
                        !webView.canScrollVertically(-1)
                    triggered = false
                    false
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    if (
                        tracking &&
                        !triggered &&
                        event.y - downY >= triggerDistance &&
                        !webView.canScrollVertically(-1)
                    ) {
                        triggered = true
                        tracking = false
                        view.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                        currentPageLoading = true
                        webView.reload()
                        updateFloatingControlsState(loading = true)
                    }
                    false
                }
                android.view.MotionEvent.ACTION_UP,
                android.view.MotionEvent.ACTION_CANCEL -> {
                    tracking = false
                    false
                }
                else -> false
            }
        }
    }

    private fun createNewTab(
        url: String,
        focus: Boolean = true,
        existingWebView: WebView? = null
    ): BrowserTab {
        val cleanUrl = url.trim()
        val originalHost = WebViewRuntime.hostOf(cleanUrl)
        val shouldClearHistoryOnFirstFinish = false
        
        var webViewRef: WebView? = null
        val webView = createSecureWebView(
            ctx = this,
            targetUrl = cleanUrl,
            originalHost = originalHost,
            onSslError = { sslUrl ->
                Log.w("ChildKioskWebView", "Native WebView SSL error: $sslUrl")
                Toast.makeText(this, "SSL 证书异常：$sslUrl", Toast.LENGTH_LONG).show()
                if (rootWebView?.url == sslUrl) hideTopProgress()
            },
            onBlocked = { blockedUrl ->
                Log.w("ChildKioskWebView", "Native WebView blocked navigation: $blockedUrl")
                Toast.makeText(this, "已拦截跳转：$blockedUrl", Toast.LENGTH_LONG).show()
                if (rootWebView?.url == blockedUrl) hideTopProgress()
            },
            onDownloadBlocked = {
                Toast.makeText(this, "下载功能已受限制，如需下载应用请联系管理员。", Toast.LENGTH_LONG).show()
            },
            onLoadingStateChanged = { loading ->
                val currentTab = tabList.firstOrNull { it.webView === webViewRef }
                if (currentTab != null) {
                    currentTab.isLoading = loading
                    if (!loading) currentTab.progress = 100
                }
                if (rootWebView === webViewRef) {
                    currentPageLoading = loading
                    if (loading) showTopProgress() else hideTopProgress()
                    updateFloatingControlsState(loading = loading)
                }
            },
            onProgressUpdate = { progress ->
                val safeProgress = progress.coerceIn(0, 100)
                val currentTab = tabList.firstOrNull { it.webView === webViewRef }
                if (currentTab != null) {
                    currentTab.progress = safeProgress
                }
                if (rootWebView === webViewRef) {
                    currentPageProgress = safeProgress
                    topProgress?.progress = safeProgress
                    updateFloatingControlsState(progress = safeProgress)
                }
            },
            onNavigationStateChanged = {
                val currentTab = tabList.firstOrNull { it.webView === webViewRef }
                if (currentTab != null) {
                    currentTab.url = webViewRef?.url.orEmpty()
                    currentTab.title = webViewRef?.title ?: "新标签页"
                }
                if (rootWebView === webViewRef) {
                    updateFloatingControlsState()
                }
            },
            onPageCommitted = { pageUrl, pageTitle ->
                recordBrowserHistory(pageUrl, pageTitle)
            },
            onError = { error ->
                Log.w("ChildKioskWebView", "Native WebView main frame error: $error")
                Toast.makeText(this, "网页加载异常：$error", Toast.LENGTH_LONG).show()
                val currentTab = tabList.firstOrNull { it.webView === webViewRef }
                if (currentTab != null) {
                    currentTab.isLoading = false
                }
                if (rootWebView === webViewRef) {
                    hideTopProgress()
                    currentPageLoading = false
                    updateFloatingControlsState(loading = false)
                }
            },
            existingWebView = existingWebView,
            runtimeConfig = runtimeConfig,
            clearHistoryOnFirstRealPageFinish = shouldClearHistoryOnFirstFinish,
            onShowFileChooser = { callback, params -> openFileChooser(callback, params) },
            onCreateWindow = { newWebView ->
                val newTab = createNewTab(
                    url = "about:blank",
                    focus = true,
                    existingWebView = newWebView
                )
                Log.d("ChildKioskWebView", "Native child window created via onCreateWindow, newTabId=${newTab.id}")
            }
        )
        webViewRef = webView
        
        val tab = BrowserTab(
            id = java.util.UUID.randomUUID().toString(),
            url = cleanUrl,
            title = webView.title.takeIf { !it.isNullOrBlank() } ?: "新标签页",
            webView = webView
        )
        tabList.add(tab)
        
        addWebViewToRoot(webView)
        
        if (focus) {
            switchToTab(tab.id)
        } else {
            setWebViewVisible(webView, false)
        }
        
        if (existingWebView == null) {
            if (cleanUrl != "about:blank" && cleanUrl.isNotBlank()) {
                loadInitialUrlAfterFirstLayout(webView, cleanUrl)
            } else {
                webView.loadUrl("about:blank")
            }
        }
        
        return tab
    }

    private fun switchToTab(tabId: String) {
        val targetTab = tabList.firstOrNull { it.id == tabId } ?: return
        
        tabList.forEach { tab ->
            if (tab.id != tabId) {
                tab.webView?.let { setWebViewVisible(it, false) }
            }
        }
        
        activeTabId = tabId
        targetTab.lastActiveTimeMs = System.currentTimeMillis()
        
        if (targetTab.webView == null) {
            restoreTab(targetTab)
        } else {
            targetTab.webView?.let { setWebViewVisible(it, true) }
        }
        
        rootWebView = targetTab.webView
        webViewStack.clear()
        targetTab.webView?.let { webViewStack.add(it) }
        currentPageProgress = targetTab.progress
        currentPageLoading = targetTab.isLoading
        
        if (currentPageLoading) {
            showTopProgress()
        } else {
            hideTopProgress()
        }
        topProgress?.progress = currentPageProgress
        
        checkAndFreezeTabsIfNeeded()
        updateFloatingControlsState()
    }

    private fun closeTab(tabId: String) {
        val targetTab = tabList.firstOrNull { it.id == tabId } ?: return
        
        tabList.remove(targetTab)
        
        val webView = targetTab.webView
        if (webView != null) {
            removeWebViewFromRoot(webView)
            runCatching {
                webView.stopLoading()
                webView.destroy()
            }
            targetTab.webView = null
        }
        
        if (activeTabId == tabId) {
            val nextTab = tabList.maxByOrNull { it.lastActiveTimeMs }
            if (nextTab != null) {
                switchToTab(nextTab.id)
            } else {
                TabMemoryCache.clear()
                KioskPrefs.saveTabsSnapshot(this, emptyList(), null)
                requestCloseWithVerification()
            }
        } else {
            updateFloatingControlsState()
        }
    }

    private fun freezeTab(tab: BrowserTab) {
        val webView = tab.webView ?: return
        Log.d("ChildKioskWebView", "Freezing tab: id=${tab.id}, url=${tab.url}")
        
        val stateBundle = Bundle()
        webView.saveState(stateBundle)
        tab.savedState = stateBundle
        
        removeWebViewFromRoot(webView)
        runCatching {
            webView.stopLoading()
            webView.destroy()
        }
        tab.webView = null
    }

    private fun restoreTab(tab: BrowserTab) {
        Log.d("ChildKioskWebView", "Restoring frozen tab: id=${tab.id}, url=${tab.url}")
        val cleanUrl = tab.url
        val originalHost = WebViewRuntime.hostOf(cleanUrl)
        val shouldClearHistoryOnFirstFinish = false
        
        var webViewRef: WebView? = null
        val webView = createSecureWebView(
            ctx = this,
            targetUrl = cleanUrl,
            originalHost = originalHost,
            onSslError = { sslUrl ->
                Log.w("ChildKioskWebView", "Native WebView SSL error: $sslUrl")
                Toast.makeText(this, "SSL 证书异常：$sslUrl", Toast.LENGTH_LONG).show()
                if (rootWebView?.url == sslUrl) hideTopProgress()
            },
            onBlocked = { blockedUrl ->
                Log.w("ChildKioskWebView", "Native WebView blocked navigation: $blockedUrl")
                Toast.makeText(this, "已拦截跳转：$blockedUrl", Toast.LENGTH_LONG).show()
                if (rootWebView?.url == blockedUrl) hideTopProgress()
            },
            onDownloadBlocked = {
                Toast.makeText(this, "下载功能已受限制，如需下载应用请联系管理员。", Toast.LENGTH_LONG).show()
            },
            onLoadingStateChanged = { loading ->
                val currentTab = tabList.firstOrNull { it.webView === webViewRef }
                if (currentTab != null) {
                    currentTab.isLoading = loading
                    if (!loading) currentTab.progress = 100
                }
                if (rootWebView === webViewRef) {
                    currentPageLoading = loading
                    if (loading) showTopProgress() else hideTopProgress()
                    updateFloatingControlsState(loading = loading)
                }
            },
            onProgressUpdate = { progress ->
                val safeProgress = progress.coerceIn(0, 100)
                val currentTab = tabList.firstOrNull { it.webView === webViewRef }
                if (currentTab != null) {
                    currentTab.progress = safeProgress
                }
                if (rootWebView === webViewRef) {
                    currentPageProgress = safeProgress
                    topProgress?.progress = safeProgress
                    updateFloatingControlsState(progress = safeProgress)
                }
            },
            onNavigationStateChanged = {
                val currentTab = tabList.firstOrNull { it.webView === webViewRef }
                if (currentTab != null) {
                    currentTab.url = webViewRef?.url.orEmpty()
                    currentTab.title = webViewRef?.title ?: "新标签页"
                }
                if (rootWebView === webViewRef) {
                    updateFloatingControlsState()
                }
            },
            onPageCommitted = { pageUrl, pageTitle ->
                recordBrowserHistory(pageUrl, pageTitle)
            },
            onError = { error ->
                Log.w("ChildKioskWebView", "Native WebView main frame error: $error")
                Toast.makeText(this, "网页加载异常：$error", Toast.LENGTH_LONG).show()
                val currentTab = tabList.firstOrNull { it.webView === webViewRef }
                if (currentTab != null) {
                    currentTab.isLoading = false
                }
                if (rootWebView === webViewRef) {
                    hideTopProgress()
                    currentPageLoading = false
                    updateFloatingControlsState(loading = false)
                }
            },
            existingWebView = null,
            runtimeConfig = runtimeConfig,
            clearHistoryOnFirstRealPageFinish = shouldClearHistoryOnFirstFinish,
            onShowFileChooser = { callback, params -> openFileChooser(callback, params) },
            onCreateWindow = { newWebView ->
                val newTab = createNewTab(
                    url = "about:blank",
                    focus = true,
                    existingWebView = newWebView
                )
                Log.d("ChildKioskWebView", "Native child window created via onCreateWindow, newTabId=${newTab.id}")
            }
        )
        webViewRef = webView
        
        tab.webView = webView
        addWebViewToRoot(webView)
        
        val state = tab.savedState
        if (state != null) {
            webView.restoreState(state)
        } else if (cleanUrl.isNotBlank()) {
            webView.loadUrl(cleanUrl)
        }
    }

    private fun checkAndFreezeTabsIfNeeded() {
        val activeWebViews = tabList.filter { it.webView != null && it.id != activeTabId }
        val maxBackgroundWebViews = (MAX_ACTIVE_WEBVIEWS - 1).coerceAtLeast(0)
        if (activeWebViews.size > maxBackgroundWebViews) {
            val tabsToFreeze = activeWebViews.sortedBy { it.lastActiveTimeMs }
                .take(activeWebViews.size - maxBackgroundWebViews)
            tabsToFreeze.forEach { freezeTab(it) }
        }
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
            rootWebView?.let { setWebViewVisible(it, true) }
            updateFloatingControlsState()
            return
        }

        activeTabId?.let { tabId ->
            closeTab(tabId)
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
                onForceRefresh = { showForceRefreshDialog() },
                onStopLoading = { stopLoadingFromFloatingControls() },
                onBookmarkCurrentPage = { bookmarkCurrentPageFromFloatingControls() },
                onPanelExpandedChanged = {
                    applySystemUiMode()
                },
                onActionSelected = { actionId ->
                    Log.d("ChildKioskWebView", "Floating browser action: $actionId")
                },
                onNewTab = { createNewTab("about:blank", focus = true) },
                onCloseTab = { id -> closeTab(id) },
                onSwitchTab = { id -> switchToTab(id) },
                onHome = {
                    finish()
                },
                onOpenWebApp = { webApp ->
                    createNewTab(webApp.url, focus = true)
                },
                onShowSiteInfoPanel = { url ->
                    showSiteInfoPanel(url)
                }
            )
        )
    }

    private fun showSiteInfoPanel(url: String) {
        floatingControlsOverlay?.setPanelExpanded(false)
        showSiteInfoDialog(url)
    }

    private fun bookmarkCurrentPageFromFloatingControls() {
        val current = rootWebView
        val activeTab = tabList.firstOrNull { it.id == activeTabId }
        val rawUrl = current?.url?.trim().orEmpty().ifBlank { activeTab?.url.orEmpty() }
        val normalizedUrl = normalizeWhitelistWebUrl(rawUrl)
        if (normalizedUrl.isBlank()) {
            Toast.makeText(this, "当前页面不能加入白名单", Toast.LENGTH_SHORT).show()
            return
        }

        val title = bookmarkTitleForCurrentPage(
            currentTitle = current?.title.orEmpty().ifBlank { activeTab?.title.orEmpty() },
            normalizedUrl = normalizedUrl
        )

        runCatching {
            showBookmarkEditor(title = title, url = normalizedUrl)
            floatingControlsOverlay?.collapsePanel()
        }.onFailure { error ->
            Log.e("ChildKioskWebView", "Failed to open bookmark editor", error)
            Toast.makeText(this, "无法打开收藏编辑界面：${error.message ?: "未知错误"}", Toast.LENGTH_LONG).show()
        }
    }

    private fun showBookmarkEditor(title: String, url: String) {
        val root = webViewRoot ?: return
        dismissBookmarkEditor()
        val editorView = ComposeView(this).apply {
            setContent {
                ChildKioskTheme {
                    AddEditWebAppDialog(
                        app = null,
                        initialTitle = title,
                        initialUrl = url,
                        initialCategory = WebAppEntity.CATEGORY_OTHER,
                        onDismiss = { dismissBookmarkEditor() },
                        onSave = { savedTitle, savedUrl, icon, category ->
                            lifecycleScope.launch(Dispatchers.IO) {
                                val db = AppDatabase.getInstance(this@WebViewActivity)
                                val existing = db.webAppDao().getAllWebApps().firstOrNull { app ->
                                    normalizeWhitelistWebUrl(app.url) == normalizeWhitelistWebUrl(savedUrl)
                                }
                                if (existing == null) {
                                    db.webAppDao().insertWebApp(
                                        WebAppEntity(
                                            title = savedTitle,
                                            url = savedUrl,
                                            iconPath = icon,
                                            isPreset = false,
                                            isEnabled = true,
                                            category = category,
                                            sourceType = WebAppEntity.SOURCE_LOCAL
                                        )
                                    )
                                } else {
                                    db.webAppDao().updateWebApp(
                                        existing.copy(
                                            title = savedTitle,
                                            url = savedUrl,
                                            iconPath = icon,
                                            category = category,
                                            isEnabled = true
                                        )
                                    )
                                }
                                withContext(Dispatchers.Main) {
                                    dismissBookmarkEditor()
                                    Toast.makeText(this@WebViewActivity, "已收藏网站", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    )
                }
            }
        }
        bookmarkEditorView = editorView
        root.addView(
            editorView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
    }

    private fun dismissBookmarkEditor() {
        bookmarkEditorView?.let { view ->
            (view.parent as? ViewGroup)?.removeView(view)
            view.disposeComposition()
        }
        bookmarkEditorView = null
    }

    private fun normalizeWhitelistWebUrl(url: String): String {
        return runCatching {
            val uri = Uri.parse(url.trim())
            val scheme = uri.scheme?.lowercase(Locale.US) ?: return@runCatching ""
            val host = uri.host?.lowercase(Locale.US) ?: return@runCatching ""
            if (scheme != "http" && scheme != "https") return@runCatching ""
            val port = if (uri.port >= 0) ":${uri.port}" else ""
            val path = uri.encodedPath?.takeIf { it.isNotBlank() } ?: "/"
            val query = uri.encodedQuery?.let { "?$it" }.orEmpty()
            "$scheme://$host$port$path$query"
        }.getOrDefault("")
    }

    private fun bookmarkTitleForCurrentPage(currentTitle: String, normalizedUrl: String): String {
        val cleanTitle = currentTitle.trim().replace(Regex("\\s+"), " ")
        if (
            cleanTitle.isNotBlank() &&
            !cleanTitle.startsWith("http://", ignoreCase = true) &&
            !cleanTitle.startsWith("https://", ignoreCase = true)
        ) {
            return cleanTitle.take(60)
        }
        return WebViewRuntime.hostOf(normalizedUrl).ifBlank { "收藏网站" }.take(60)
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

    private fun showForceRefreshDialog() {
        val current = rootWebView ?: return
        val currentUrl = current.url.orEmpty()
        if (!WebViewRuntime.isWebUrl(currentUrl)) {
            Toast.makeText(this, "当前页面不支持强制刷新", Toast.LENGTH_SHORT).show()
            return
        }
        if (forceRefreshDialog?.isShowing == true) return

        val density = resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density).toInt()
        val clearSiteDataCheckBox = CheckBox(this).apply {
            text = "同时清理当前网站 Cookie、本地存储、会话存储等登录/本地数据"
            textSize = 14f
            setPadding(0, dp(8), 0, 0)
        }
        val content = ScrollView(this).apply {
            addView(
                LinearLayout(this@WebViewActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(20), dp(4), dp(20), dp(4))
                    addView(
                        TextView(this@WebViewActivity).apply {
                            text = "默认只清理网页缓存并绕过缓存重新加载，不会删除登录信息、Cookie、localStorage 或 sessionStorage。"
                            textSize = 14f
                        }
                    )
                    addView(clearSiteDataCheckBox)
                }
            )
        }

        forceRefreshDialog = AlertDialog.Builder(this)
            .setTitle("强制刷新")
            .setView(content)
            .setNegativeButton("取消", null)
            .setPositiveButton("强制刷新") { _, _ ->
                forceRefreshCurrentPage(clearSiteData = clearSiteDataCheckBox.isChecked)
            }
            .create()
        forceRefreshDialog?.setOnDismissListener {
            forceRefreshDialog = null
        }
        forceRefreshDialog?.show()
    }

    private fun forceRefreshCurrentPage(clearSiteData: Boolean) {
        val current = rootWebView ?: return
        val currentUrl = current.url.orEmpty()
        if (!WebViewRuntime.isWebUrl(currentUrl)) {
            Toast.makeText(this, "当前页面不支持强制刷新", Toast.LENGTH_SHORT).show()
            return
        }

        current.stopLoading()
        current.clearCache(true)
        if (clearSiteData) {
            clearCurrentSiteData(current, currentUrl) {
                reloadBypassingCache(current, currentUrl)
            }
        } else {
            reloadBypassingCache(current, currentUrl)
        }
    }

    private fun reloadBypassingCache(webView: WebView, url: String) {
        currentPageLoading = true
        currentPageProgress = 0
        webView.loadUrl(
            url,
            mapOf(
                "Cache-Control" to "no-cache, no-store, must-revalidate",
                "Pragma" to "no-cache"
            )
        )
        updateFloatingControlsState()
        Toast.makeText(this, "已强制刷新当前页面", Toast.LENGTH_SHORT).show()
    }

    private fun clearCurrentSiteData(webView: WebView, url: String, onDone: () -> Unit) {
        clearCookiesForUrl(url)
        originForWebStorage(url)?.let { origin ->
            WebStorage.getInstance().deleteOrigin(origin)
        }
        val clearScript = """
            (function() {
              try { localStorage.clear(); } catch (e) {}
              try { sessionStorage.clear(); } catch (e) {}
              try {
                if (window.caches) {
                  caches.keys().then(function(keys) {
                    keys.forEach(function(key) { caches.delete(key); });
                  });
                }
              } catch (e) {}
              try {
                if (window.indexedDB && indexedDB.databases) {
                  indexedDB.databases().then(function(databases) {
                    databases.forEach(function(database) {
                      if (database && database.name) indexedDB.deleteDatabase(database.name);
                    });
                  });
                }
              } catch (e) {}
              try {
                if (navigator.serviceWorker) {
                  navigator.serviceWorker.getRegistrations().then(function(registrations) {
                    registrations.forEach(function(registration) { registration.unregister(); });
                  });
                }
              } catch (e) {}
            })();
        """.trimIndent()
        webView.evaluateJavascript(clearScript) {
            webView.postDelayed(onDone, 250)
        }
    }

    private fun clearCookiesForUrl(url: String) {
        val cookieManager = CookieManager.getInstance()
        val cookies = cookieManager.getCookie(url).orEmpty()
        cookies.split(';')
            .mapNotNull { cookie ->
                cookie.substringBefore("=", missingDelimiterValue = "")
                    .trim()
                    .takeIf { it.isNotBlank() }
            }
            .forEach { name ->
                cookieManager.setCookie(
                    url,
                    "$name=; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Max-Age=0; Path=/"
                )
            }
        cookieManager.flush()
    }

    private fun originForWebStorage(url: String): String? {
        return runCatching {
            val uri = Uri.parse(url)
            val scheme = uri.scheme?.lowercase() ?: return@runCatching null
            val host = uri.host ?: return@runCatching null
            if (scheme != "http" && scheme != "https") return@runCatching null
            val port = if (uri.port >= 0) ":${uri.port}" else ""
            "$scheme://$host$port"
        }.getOrNull()
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
        
        KioskPrefs.saveTabsSnapshot(this, tabList, activeTabId)
        
        TabMemoryCache.activeTabId = activeTabId
        TabMemoryCache.tabList.clear()
        tabList.forEach { tab ->
            val stateBundle = tab.savedState ?: Bundle()
            tab.webView?.saveState(stateBundle)
            TabMemoryCache.tabList.add(
                TabCacheItem(
                    id = tab.id,
                    url = tab.webView?.url ?: tab.url,
                    title = tab.webView?.title ?: tab.title,
                    savedState = stateBundle,
                    lastActiveTimeMs = tab.lastActiveTimeMs
                )
            )
        }
        
        floatingControlsOverlay?.updateState(currentFloatingControlsState())
    }

    private fun currentFloatingControlsState(): FloatingBrowserControlsState {
        val current = rootWebView
        val tabStateInfos = tabList.map { tab ->
            TabStateInfo(
                id = tab.id,
                title = tab.title,
                url = tab.url,
                isActive = (tab.id == activeTabId)
            )
        }
        return FloatingBrowserControlsState(
            currentUrl = current?.url.orEmpty(),
            pageTitle = current?.title.orEmpty(),
            canGoBack = current?.canGoBack() == true,
            canGoForward = current?.canGoForward() == true,
            isLoading = currentPageLoading,
            progress = currentPageProgress.coerceIn(0, 100),
            tabs = tabStateInfos
        )
    }

    private fun recordBrowserHistory(pageUrl: String, pageTitle: String?) {
        val normalizedUrl = normalizeWhitelistWebUrl(pageUrl)
        if (normalizedUrl.isBlank()) return
        val now = System.currentTimeMillis()
        if (normalizedUrl == lastRecordedHistoryUrl && now - lastRecordedHistoryAtMs < 30_000L) {
            return
        }
        lastRecordedHistoryUrl = normalizedUrl
        lastRecordedHistoryAtMs = now

        val host = WebViewRuntime.hostOf(normalizedUrl).lowercase(Locale.US)
        val cleanTitle = bookmarkTitleForCurrentPage(
            currentTitle = pageTitle.orEmpty(),
            normalizedUrl = normalizedUrl
        )
        val webAppId = launchedWebAppId
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching {
                val dao = AppDatabase.getInstance(this@WebViewActivity).browserHistoryDao()
                dao.insert(
                    BrowserHistoryEntity(
                        title = cleanTitle,
                        url = normalizedUrl,
                        host = host,
                        visitedAt = now,
                        webAppId = webAppId
                    )
                )
                dao.deleteOlderThan(now - HISTORY_RETENTION_MS)
            }.onFailure { error ->
                Log.w("ChildKioskWebView", "Failed to record browser history", error)
            }
        }
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
        val isNormalMode = KioskPrefs.getProtectionMode(this) == KioskPrefs.MODE_NONE
        if (isNormalMode || !runtimeConfig.verifyOnWebExit || !runtimeConfig.verifyAdminActions) {
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

    private fun showSiteInfoDialog(url: String) {
        dismissCustomComposeDialog()

        val host = try {
            Uri.parse(url).host?.lowercase() ?: ""
        } catch (e: Exception) {
            ""
        }
        val pageOrigin = originForWebStorage(url).orEmpty()
        val activeTab = activeTabId
        val currentAttemptedScheme = activeTab?.let { lastAttemptedSchemeMap[it] }

        showCustomComposeDialog {
            SiteInfoContent(
                host = host,
                origin = pageOrigin,
                url = url,
                currentAttemptedScheme = currentAttemptedScheme,
                initialLimitGeolocation = runtimeConfig.limitGeolocation,
                initialLimitCameraCapture = runtimeConfig.limitCameraCapture,
                initialLimitMicrophoneCapture = runtimeConfig.limitMicrophoneCapture,
                initialLimitFileChooser = runtimeConfig.limitFileChooser,
                initialLimitCustomScheme = runtimeConfig.limitCustomScheme,
                initialGeoBlacklist = runtimeConfig.geolocationBlacklist,
                initialCameraBlacklist = runtimeConfig.cameraBlacklist,
                initialMicrophoneBlacklist = runtimeConfig.microphoneBlacklist,
                initialFileChooserBlacklist = runtimeConfig.fileChooserBlacklist,
                initialSchemeBlacklist = runtimeConfig.schemeBlacklist,
                onDismiss = { dismissCustomComposeDialog() },
                onClearData = {
                    val origin = try {
                        val uri = Uri.parse(url)
                        "${uri.scheme}://${uri.host}"
                    } catch (ex: Exception) {
                        ""
                    }
                    if (origin.isNotBlank()) {
                        android.webkit.WebStorage.getInstance().deleteOrigin(origin)
                        Toast.makeText(this@WebViewActivity, "已清理本站 ($host) 的本地存储和缓存", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this@WebViewActivity, "无法解析本站域名，清理失败", Toast.LENGTH_SHORT).show()
                    }
                },
                onUpdateGeoBlacklist = { newSet ->
                    KioskPrefs.setGeolocationBlacklist(this@WebViewActivity, newSet)
                    runtimeConfig = runtimeConfig.copy(geolocationBlacklist = newSet)
                },
                onUpdateCameraBlacklist = { newSet ->
                    KioskPrefs.setCameraBlacklist(this@WebViewActivity, newSet)
                    runtimeConfig = runtimeConfig.copy(cameraBlacklist = newSet)
                },
                onUpdateMicrophoneBlacklist = { newSet ->
                    KioskPrefs.setMicrophoneBlacklist(this@WebViewActivity, newSet)
                    runtimeConfig = runtimeConfig.copy(microphoneBlacklist = newSet)
                },
                onUpdateFileChooserBlacklist = { newSet ->
                    KioskPrefs.setFileChooserBlacklist(this@WebViewActivity, newSet)
                    runtimeConfig = runtimeConfig.copy(fileChooserBlacklist = newSet)
                },
                onUpdateSchemeBlacklist = { newSet ->
                    KioskPrefs.setSchemeBlacklist(this@WebViewActivity, newSet)
                    runtimeConfig = runtimeConfig.copy(schemeBlacklist = newSet)
                }
            )
        }
    }

    companion object {
        const val EXTRA_WEB_APP_ID = "WEB_APP_ID"
        const val EXTRA_ORIENTATION_MODE = "ORIENTATION_MODE"
        const val EXTRA_CUSTOM_URL = "CUSTOM_URL"
        const val EXTRA_SWITCH_TAB_ID = "SWITCH_TAB_ID"
        const val EXTRA_CLOSE_TAB_ID = "CLOSE_TAB_ID"
        private const val HISTORY_RETENTION_MS = 90L * 24L * 60L * 60L * 1000L
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
    onPageCommitted: (url: String, title: String?) -> Unit,
    onError: (String) -> Unit,
    existingWebView: WebView? = null,
    runtimeConfig: WebViewRuntimeConfig,
    clearHistoryOnFirstRealPageFinish: Boolean = false,
    onShowFileChooser: (ValueCallback<Array<Uri>>, WebChromeClient.FileChooserParams?) -> Boolean,
    onCreateWindow: (WebView) -> Unit
): WebView {
    val webView = existingWebView ?: WebView(ctx)
    val shouldClearInitialHistory = AtomicBoolean(clearHistoryOnFirstRealPageFinish)
    val currentTopUrl = java.util.concurrent.atomic.AtomicReference<String>(targetUrl)

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
                if (!url.isNullOrBlank()) {
                    currentTopUrl.set(url)
                }
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
                if (view != null && !url.isNullOrBlank() && WebViewRuntime.isWebUrl(url)) {
                    onPageCommitted(url, view.title)
                }
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

                val uri = Uri.parse(urlStr)
                val scheme = uri.scheme?.lowercase()?.trim()
                val isCustomScheme = scheme != null && 
                                     scheme != "http" && 
                                     scheme != "https" && 
                                     scheme != "file" && 
                                     scheme != "about" && 
                                     scheme != "javascript" && 
                                     scheme != "data"

                if (isCustomScheme && scheme != null) {
                    (ctx as? WebViewActivity)?.let { activity ->
                        activity.activeTabId?.let { tabId ->
                            activity.lastAttemptedSchemeMap[tabId] = scheme
                        }
                    }
                    val latestConfig = (ctx as? WebViewActivity)?.latestRuntimeConfig() ?: runtimeConfig
                    val isNormalMode = KioskPrefs.getProtectionMode(ctx) == KioskPrefs.MODE_NONE
                    if (!isNormalMode) {
                        onBlocked(urlStr)
                        return true
                    }
                    if (latestConfig.limitCustomScheme) {
                        Log.d("ChildKioskWebView", "Custom scheme redirect is disabled globally")
                        onBlocked(urlStr)
                        return true
                    }
                    if (latestConfig.schemeBlacklist.contains(scheme)) {
                        Log.d("ChildKioskWebView", "Custom scheme is in blacklist: $scheme")
                        onBlocked(urlStr)
                        return true
                    }
                    (ctx as? WebViewActivity)?.handleCustomSchemeRedirect(urlStr, scheme)
                    return true
                }

                if (runtimeConfig.limitAdBlock && request.isForMainFrame) {
                    val cleanedUrl = FilterRepository.getCachedEngine(runtimeConfig.filterSnapshot)
                        ?.cleanUrlForNavigation(urlStr, currentTopUrl.get())
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
                    val topLevelUrl = currentTopUrl.get()
                    val decision = AdBlocker.shouldBlock(ctx, request, topLevelUrl, runtimeConfig.filterSnapshot)
                    if (decision.action == FilterAction.BLOCK) {
                        val requestUrl = request?.url?.toString().orEmpty()
                        if (FilterBlockLogLimiter.shouldLog()) {
                            Log.d(
                                "ChildKioskWebView",
                                "Blocked filter request: $requestUrl, rule=${decision.rule?.rawText}, source=${decision.rule?.sourceName}"
                            )
                        }
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

            override fun onRenderProcessGone(
                view: WebView?,
                detail: RenderProcessGoneDetail?
            ): Boolean {
                Log.e("ChildKioskWebView", "Renderer process gone! Did crash: ${detail?.didCrash()}")
                view?.let {
                    destroyWebViewSafely(it)
                }
                Toast.makeText(ctx, "网页渲染进程异常退出，正在尝试重构页面", Toast.LENGTH_SHORT).show()
                return true
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
                val hasKnownMediaRequest = requested.any { resource ->
                    resource == PermissionRequest.RESOURCE_VIDEO_CAPTURE ||
                        resource == PermissionRequest.RESOURCE_AUDIO_CAPTURE
                }
                val hasUnknownRequest = requested.any { resource ->
                    resource != PermissionRequest.RESOURCE_VIDEO_CAPTURE &&
                        resource != PermissionRequest.RESOURCE_AUDIO_CAPTURE
                }
                if (hasUnknownRequest || !hasKnownMediaRequest) {
                    Log.d(
                        "ChildKioskWebView",
                        "Denied unsupported WebView permission request: ${requested.joinToString()}"
                    )
                    request.deny()
                } else {
                    (ctx as? WebViewActivity)?.requestMediaPermission(request) ?: request.deny()
                }
            }

            override fun onGeolocationPermissionsShowPrompt(
                origin: String?,
                callback: GeolocationPermissions.Callback?
            ) {
                (ctx as? WebViewActivity)?.requestGeolocationPermission(origin, callback)
                    ?: callback?.invoke(origin, false, false)
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
                if (runtimeConfig.limitAdBlock && shouldBlockPopup(view, runtimeConfig)) {
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
                    onPageCommitted = onPageCommitted,
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
                (ctx as? WebViewActivity)?.requestDownload(url, userAgent, contentDisposition, mimeType)
                    ?: enqueueDownload(ctx, url, userAgent, contentDisposition, mimeType)
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
    if (url.startsWith("data:", ignoreCase = true)) {
        runCatching {
            val base64Index = url.indexOf("base64,")
            if (base64Index != -1) {
                val base64Data = url.substring(base64Index + 7)
                val bytes = android.util.Base64.decode(base64Data, android.util.Base64.DEFAULT)
                
                val mimeTypeClean = url.substring(5, base64Index - 1).split(";")[0]
                val extension = android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeTypeClean) ?: "bin"
                val fileName = "download_" + System.currentTimeMillis() + "." + extension
                
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!downloadsDir.exists()) {
                    downloadsDir.mkdirs()
                }
                val file = java.io.File(downloadsDir, fileName)
                java.io.FileOutputStream(file).use { fos ->
                    fos.write(bytes)
                }
                
                android.media.MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), null, null)
                Toast.makeText(context, "文件已保存至下载目录: $fileName", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(context, "暂不支持此数据格式的下载", Toast.LENGTH_SHORT).show()
            }
        }.onFailure { e ->
            Log.e("ChildKioskWebView", "Data URL save failed", e)
            Toast.makeText(context, "保存失败: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
        }
        return
    }
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
    if (currentTiming != "BOTH") {
        injectCosmeticCssIfNeeded(webView, config)
        injectFilterScriptletsIfNeeded(webView, config)
    }
    injectDebugToolIfNeeded(webView, context, config, currentTiming)
    injectCustomScriptIfNeeded(webView, config, currentTiming)
}

private fun injectCosmeticCssIfNeeded(
    webView: WebView,
    config: WebViewRuntimeConfig
) {
    if (!config.limitAdBlock || !config.filterSnapshot.enabled) return
    if (config.filterSnapshot.preset == "LIGHT") return
    val pageUrl = webView.url ?: return
    val host = WebViewRuntime.hostOf(pageUrl)
    if (host.isBlank()) return
    val siteOverride = FilterRepository.siteOverrideFor(config.filterSnapshot, host)
    val engine = FilterRepository.getCachedEngine(config.filterSnapshot) ?: return
    val css = engine.cosmeticCssFor(host, siteOverride).take(256 * 1024)
    FilterRepository.maybeRecordPerfSnapshot(webView.context, config.filterSnapshot, engine)
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
    config: WebViewRuntimeConfig
) {
    if (!config.limitAdBlock || !config.filterSnapshot.enabled) return
    if (config.filterSnapshot.preset == "LIGHT") return
    val pageUrl = webView.url ?: return
    val host = WebViewRuntime.hostOf(pageUrl)
    if (host.isBlank()) return
    val siteOverride = FilterRepository.siteOverrideFor(config.filterSnapshot, host)
    val engine = FilterRepository.getCachedEngine(config.filterSnapshot) ?: return
    val scriptlets = engine.scriptletJsFor(host, siteOverride)
    FilterRepository.maybeRecordPerfSnapshot(webView.context, config.filterSnapshot, engine)
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
    return FilterRepository.getCachedEngine(config.filterSnapshot)
        ?.decide(requestContext, siteOverride)
        ?.action == FilterAction.BLOCK
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

@Composable
private fun BeautifulConfirmDialog(
    title: String,
    message: String,
    icon: ImageVector = Icons.Default.Info,
    negativeText: String = "拒绝",
    positiveText: String = "允许",
    blacklistText: String? = null,
    onNegative: () -> Unit,
    onPositive: () -> Unit,
    onBlacklist: (() -> Unit)? = null,
    onDismiss: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.5f))
            .clickable(enabled = true, onClick = onDismiss),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.85f)
                .padding(16.dp)
                .clickable(enabled = false) {},
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(
                modifier = Modifier
                    .padding(24.dp)
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .size(48.dp)
                        .padding(bottom = 12.dp)
                )

                Text(
                    text = title,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                Text(
                    text = message,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    lineHeight = 20.sp,
                    modifier = Modifier.padding(bottom = 24.dp)
                )

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = onNegative,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(negativeText)
                        }

                        Button(
                            onClick = onPositive,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(positiveText)
                        }
                    }

                    if (blacklistText != null && onBlacklist != null) {
                        TextButton(
                            onClick = onBlacklist,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.textButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            )
                        ) {
                            Text(blacklistText, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SiteInfoContent(
    host: String,
    origin: String,
    url: String,
    currentAttemptedScheme: String?,
    initialLimitGeolocation: Boolean,
    initialLimitCameraCapture: Boolean,
    initialLimitMicrophoneCapture: Boolean,
    initialLimitFileChooser: Boolean,
    initialLimitCustomScheme: Boolean,
    initialGeoBlacklist: Set<String>,
    initialCameraBlacklist: Set<String>,
    initialMicrophoneBlacklist: Set<String>,
    initialFileChooserBlacklist: Set<String>,
    initialSchemeBlacklist: Set<String>,
    onDismiss: () -> Unit,
    onClearData: () -> Unit,
    onUpdateGeoBlacklist: (Set<String>) -> Unit,
    onUpdateCameraBlacklist: (Set<String>) -> Unit,
    onUpdateMicrophoneBlacklist: (Set<String>) -> Unit,
    onUpdateFileChooserBlacklist: (Set<String>) -> Unit,
    onUpdateSchemeBlacklist: (Set<String>) -> Unit
) {
    var geoBlacklist by remember { mutableStateOf(initialGeoBlacklist) }
    var cameraBlacklist by remember { mutableStateOf(initialCameraBlacklist) }
    var microphoneBlacklist by remember { mutableStateOf(initialMicrophoneBlacklist) }
    var fileChooserBlacklist by remember { mutableStateOf(initialFileChooserBlacklist) }
    var schemeBlacklist by remember { mutableStateOf(initialSchemeBlacklist) }
    val permissionOrigin = origin.ifBlank { host }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.5f))
            .clickable(enabled = true, onClick = onDismiss),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .heightIn(max = 520.dp)
                .clickable(enabled = false, onClick = {}),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                val trimmed = url.trim()
                val isHttps = trimmed.startsWith("https://", ignoreCase = true)
                val isHttp = trimmed.startsWith("http://", ignoreCase = true)
                val (icon, titleText, color, descText) = when {
                    isHttps -> QuadrupleInfo(Icons.Default.Lock, "此连接是安全的", Color(0xFF4CAF50), "你与该网站建立的是加密 HTTPS 安全连接。")
                    isHttp -> QuadrupleInfo(Icons.Default.Warning, "此连接不安全", Color(0xFFF44336), "你与该网站的连接未加密，请勿在此输入任何敏感隐私信息。")
                    else -> QuadrupleInfo(Icons.Default.Info, "网站信息", MaterialTheme.colorScheme.primary, "当前页面正通过系统特殊协议进行渲染。")
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = color,
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (host.isNotBlank()) host else "未知网站",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = titleText.toString(),
                            fontSize = 11.sp,
                            color = color,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = descText.toString(),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(16.dp))
                Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
                Spacer(modifier = Modifier.height(16.dp))

                SitePermissionSwitchRow(
                    title = "定位权限 (Geolocation)",
                    allowedText = "允许网页询问定位",
                    blockedText = "彻底禁止并拉黑",
                    globallyBlockedText = "已被管理员在沙箱中全局禁用定位功能",
                    origin = permissionOrigin,
                    isGloballyBlocked = initialLimitGeolocation,
                    blacklist = geoBlacklist,
                    onUpdateBlacklist = {
                        geoBlacklist = it
                        onUpdateGeoBlacklist(it)
                    }
                )

                Spacer(modifier = Modifier.height(14.dp))

                SitePermissionSwitchRow(
                    title = "摄像头权限",
                    allowedText = "允许网页询问摄像头",
                    blockedText = "彻底禁止摄像头",
                    globallyBlockedText = "已被管理员在沙箱中全局禁用摄像头",
                    origin = permissionOrigin,
                    isGloballyBlocked = initialLimitCameraCapture,
                    blacklist = cameraBlacklist,
                    onUpdateBlacklist = {
                        cameraBlacklist = it
                        onUpdateCameraBlacklist(it)
                    }
                )

                Spacer(modifier = Modifier.height(14.dp))

                SitePermissionSwitchRow(
                    title = "麦克风权限",
                    allowedText = "允许网页询问麦克风",
                    blockedText = "彻底禁止麦克风",
                    globallyBlockedText = "已被管理员在沙箱中全局禁用麦克风",
                    origin = permissionOrigin,
                    isGloballyBlocked = initialLimitMicrophoneCapture,
                    blacklist = microphoneBlacklist,
                    onUpdateBlacklist = {
                        microphoneBlacklist = it
                        onUpdateMicrophoneBlacklist(it)
                    }
                )

                Spacer(modifier = Modifier.height(14.dp))

                SitePermissionSwitchRow(
                    title = "文件选择/上传",
                    allowedText = "允许网页询问文件选择",
                    blockedText = "彻底禁止文件选择",
                    globallyBlockedText = "已被管理员在沙箱中全局禁用文件选择",
                    origin = permissionOrigin,
                    isGloballyBlocked = initialLimitFileChooser,
                    blacklist = fileChooserBlacklist,
                    onUpdateBlacklist = {
                        fileChooserBlacklist = it
                        onUpdateFileChooserBlacklist(it)
                    }
                )

                Spacer(modifier = Modifier.height(20.dp))

                Text(
                    text = "外部应用跳转 (Custom Scheme)",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(6.dp))
                if (initialLimitCustomScheme) {
                    Text(
                        text = "⚠️ 已被管理员在沙箱中全局禁用自定义 Scheme 跳转",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.SemiBold
                    )
                } else if (currentAttemptedScheme != null) {
                    val isSchemeBlocked = schemeBlacklist.contains(currentAttemptedScheme)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.1f), RoundedCornerShape(8.dp))
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "检测到尝试调起: $currentAttemptedScheme://",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = if (isSchemeBlocked) "已拉黑" else "允许询问",
                                fontSize = 11.sp,
                                color = if (isSchemeBlocked) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(end = 6.dp)
                            )
                            Switch(
                                checked = !isSchemeBlocked,
                                onCheckedChange = {
                                    val newSet = schemeBlacklist.toMutableSet()
                                    if (!it) {
                                        newSet.add(currentAttemptedScheme)
                                    } else {
                                        newSet.remove(currentAttemptedScheme)
                                    }
                                    schemeBlacklist = newSet
                                    onUpdateSchemeBlacklist(newSet)
                                }
                            )
                        }
                    }
                } else {
                    Text(
                        text = "此网页近期无外部应用调起请求记录",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }

                if (schemeBlacklist.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "当前已拉黑的 Scheme 协议列表：",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Column(
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        val chunks = schemeBlacklist.chunked(3)
                        for (rowItems in chunks) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                for (item in rowItems) {
                                    Row(
                                        modifier = Modifier
                                            .background(MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(6.dp))
                                            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.15f), RoundedCornerShape(6.dp))
                                            .padding(horizontal = 6.dp, vertical = 3.dp)
                                            .weight(1f),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = "$item://",
                                            fontSize = 10.sp,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f)
                                        )
                                        Icon(
                                            imageVector = Icons.Default.Close,
                                            contentDescription = "移除",
                                            tint = MaterialTheme.colorScheme.error,
                                            modifier = Modifier
                                                .size(14.dp)
                                                .clickable {
                                                    val newSet = schemeBlacklist.toMutableSet()
                                                    newSet.remove(item)
                                                    schemeBlacklist = newSet
                                                    onUpdateSchemeBlacklist(newSet)
                                                }
                                        )
                                    }
                                }
                                val remaining = 3 - rowItems.size
                                if (remaining > 0) {
                                    repeat(remaining) {
                                        Spacer(modifier = Modifier.weight(1f))
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))
                Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
                Spacer(modifier = Modifier.height(20.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = onClearData,
                        modifier = Modifier.weight(1.2f),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Text("清理本站缓存与数据", fontSize = 11.sp)
                    }

                    Button(
                        onClick = onDismiss,
                        modifier = Modifier.weight(0.8f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("关闭")
                    }
                }
            }
        }
    }
}

@Composable
private fun SitePermissionSwitchRow(
    title: String,
    allowedText: String,
    blockedText: String,
    globallyBlockedText: String,
    origin: String,
    isGloballyBlocked: Boolean,
    blacklist: Set<String>,
    onUpdateBlacklist: (Set<String>) -> Unit
) {
    val normalizedOrigin = KioskPrefs.normalizeOriginKey(origin)
    val host = runCatching { Uri.parse(normalizedOrigin).host?.lowercase(Locale.US) }.getOrNull()
    val isBlacklisted = normalizedOrigin.isNotBlank() &&
        (blacklist.contains(normalizedOrigin) || (host != null && blacklist.contains(host)))

    Text(
        text = title,
        fontSize = 14.sp,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface
    )
    Spacer(modifier = Modifier.height(6.dp))
    if (isGloballyBlocked) {
        Text(
            text = "⚠️ $globallyBlockedText",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.error,
            fontWeight = FontWeight.SemiBold
        )
        return
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.1f), RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = if (isBlacklisted) blockedText else allowedText,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Switch(
            checked = !isBlacklisted,
            enabled = normalizedOrigin.isNotBlank(),
            onCheckedChange = { checked ->
                val newSet = blacklist.toMutableSet()
                if (!checked) {
                    newSet.add(normalizedOrigin)
                } else {
                    newSet.remove(normalizedOrigin)
                    host?.let { newSet.remove(it) }
                }
                onUpdateBlacklist(newSet)
            }
        )
    }
}

private data class QuadrupleInfo<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)
