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
import android.os.Handler
import android.os.Looper
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.Log
import android.view.KeyEvent
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.VelocityTracker
import android.view.animation.LinearInterpolator
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
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import site.anzz.childkiosk.data.AppDatabase
import site.anzz.childkiosk.data.BrowserHistoryEntity
import site.anzz.childkiosk.data.SystemConfigEntity
import site.anzz.childkiosk.data.WebAppEntity
import site.anzz.childkiosk.ui.AddEditWebAppDialog
import site.anzz.childkiosk.ui.browser.BrowserTab
import site.anzz.childkiosk.ui.browser.FloatingControlAction
import site.anzz.childkiosk.ui.browser.FloatingControlActionStyle
import site.anzz.childkiosk.ui.browser.FloatingControlSection
import site.anzz.childkiosk.ui.browser.TabStateInfo
import site.anzz.childkiosk.ui.browser.TabMemoryCache
import site.anzz.childkiosk.ui.browser.TabCacheItem
import site.anzz.childkiosk.ui.browser.FloatingBrowserControlsCallbacks
import site.anzz.childkiosk.ui.browser.FloatingBrowserControlsOverlay
import site.anzz.childkiosk.ui.browser.FloatingBrowserControlsState
import site.anzz.childkiosk.util.AdBlocker
import site.anzz.childkiosk.util.HashUtils
import site.anzz.childkiosk.util.KioskPrefs
import site.anzz.childkiosk.util.NativeLocationMainProcessClient
import site.anzz.childkiosk.util.NativeLocationError
import site.anzz.childkiosk.util.NativeLocationManager
import site.anzz.childkiosk.util.NativeLocationResult
import site.anzz.childkiosk.util.SystemUiHelper
import site.anzz.childkiosk.util.TimeLimiter
import site.anzz.childkiosk.util.WebAppIconCache
import site.anzz.childkiosk.util.WebViewRuntime
import site.anzz.childkiosk.util.WebViewRuntimeConfig
import site.anzz.childkiosk.util.WebViewPool
import site.anzz.childkiosk.ui.theme.ChildKioskTheme
import site.anzz.childkiosk.util.filter.CosmeticFilterMatch
import site.anzz.childkiosk.util.filter.FilterAction
import site.anzz.childkiosk.util.filter.FilterDecision
import site.anzz.childkiosk.util.filter.FilterRepository
import site.anzz.childkiosk.util.filter.FilterResourceType
import site.anzz.childkiosk.util.filter.PopupFilterDisposition
import site.anzz.childkiosk.util.filter.PopupFilterGate
import site.anzz.childkiosk.util.filter.PopupFilterResult
import site.anzz.childkiosk.util.filter.WebViewFilterEngineHandle
import site.anzz.childkiosk.util.filter.WebViewFilterInjector
import site.anzz.childkiosk.util.filter.WebViewFilterRuntime
import site.anzz.childkiosk.util.filter.WebViewFilterRuntimeStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONTokener
import java.lang.ref.WeakReference
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.cos
import kotlin.math.sin

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

private const val ACTION_SHOW_CURRENT_PAGE_FILTERS = "show_current_page_filter_events"
private const val CURRENT_PAGE_FILTER_EVENT_LIMIT = 80
private const val CURRENT_PAGE_COSMETIC_EVENT_LIMIT = 120
private const val MAX_NATIVE_LOCATION_WATCHES_PER_WEBVIEW = 3
private val nativeLocationDocumentScripts =
    Collections.synchronizedMap(WeakHashMap<WebView, ScriptHandler>())
private const val COSMETIC_HIT_TEST_LIMIT = 120

private class PullToRefreshIndicatorView(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        style = Paint.Style.FILL
    }
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.rgb(25, 103, 210)
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 3f * density
    }
    private val arrowPaint = Paint(arcPaint)
    private val arcBounds = RectF()
    private var pullProgress = 0f
    private var spinAnimator: ObjectAnimator? = null
    var isRefreshing: Boolean = false
        private set

    init {
        setWillNotDraw(false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            elevation = 8f * density
        }
    }

    fun setPullProgress(progress: Float, armed: Boolean) {
        if (isRefreshing) return
        pullProgress = progress.coerceIn(0f, 1f)
        arcPaint.color = if (armed) {
            android.graphics.Color.rgb(15, 157, 88)
        } else {
            android.graphics.Color.rgb(25, 103, 210)
        }
        arrowPaint.color = arcPaint.color
        rotation = pullProgress * 210f
        invalidate()
    }

    fun startRefreshing() {
        if (isRefreshing) return
        isRefreshing = true
        pullProgress = 1f
        arcPaint.color = android.graphics.Color.rgb(25, 103, 210)
        arrowPaint.color = arcPaint.color
        spinAnimator?.cancel()
        spinAnimator = ObjectAnimator.ofFloat(this, View.ROTATION, rotation, rotation + 360f).apply {
            duration = 720L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            start()
        }
        invalidate()
    }

    fun stopRefreshing() {
        spinAnimator?.cancel()
        spinAnimator = null
        isRefreshing = false
        pullProgress = 0f
        rotation = 0f
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val circleRadius = width.coerceAtMost(height) * 0.42f
        canvas.drawCircle(cx, cy, circleRadius, circlePaint)

        val iconRadius = width.coerceAtMost(height) * 0.22f
        arcBounds.set(cx - iconRadius, cy - iconRadius, cx + iconRadius, cy + iconRadius)
        val sweep = if (isRefreshing) 285f else 44f + 246f * pullProgress
        val startAngle = -90f + 120f * pullProgress
        canvas.drawArc(arcBounds, startAngle, sweep, false, arcPaint)

        if (isRefreshing || pullProgress > 0.18f) {
            val endAngle = Math.toRadians((startAngle + sweep).toDouble())
            val endX = cx + iconRadius * cos(endAngle).toFloat()
            val endY = cy + iconRadius * sin(endAngle).toFloat()
            val direction = endAngle + Math.PI / 2.0
            val arrowSize = 5.5f * density
            val backA = direction + Math.toRadians(148.0)
            val backB = direction - Math.toRadians(148.0)
            canvas.drawLine(
                endX,
                endY,
                endX + arrowSize * cos(backA).toFloat(),
                endY + arrowSize * sin(backA).toFloat(),
                arrowPaint
            )
            canvas.drawLine(
                endX,
                endY,
                endX + arrowSize * cos(backB).toFloat(),
                endY + arrowSize * sin(backB).toFloat(),
                arrowPaint
            )
        }
    }
}

private data class CurrentPageNetworkFilterEvent(
    val timestamp: Long,
    val topLevelUrl: String,
    val requestUrl: String,
    val resourceType: String,
    val ruleText: String,
    val sourceName: String,
    val reason: String,
    val matchType: String,
    val candidateCount: Int,
    val cacheStatus: String
)

private data class CurrentPageCosmeticFilterEvent(
    val selector: String,
    val ruleText: String,
    val sourceName: String
)

private data class CurrentPageFilterSnapshot(
    val pageUrl: String,
    val networkTotalCount: Int,
    val networkEvents: List<CurrentPageNetworkFilterEvent>,
    val cosmeticCandidateCount: Int,
    val cosmeticMatchedCount: Int,
    val cosmeticEvents: List<CurrentPageCosmeticFilterEvent>
) {
    val networkCount: Int get() = networkTotalCount
    val cosmeticCount: Int get() = cosmeticMatchedCount
    val totalCount: Int get() = networkCount + cosmeticCount

    companion object {
        val EMPTY = CurrentPageFilterSnapshot(
            pageUrl = "",
            networkTotalCount = 0,
            networkEvents = emptyList(),
            cosmeticCandidateCount = 0,
            cosmeticMatchedCount = 0,
            cosmeticEvents = emptyList()
        )
    }
}

private class MutablePageFilterDiagnostics {
    private var pageUrl: String = ""
    private var networkTotalCount: Int = 0
    private val networkEvents = java.util.ArrayDeque<CurrentPageNetworkFilterEvent>()
    private var cosmeticCandidateCount: Int = 0
    private var cosmeticMatchedCount: Int = 0
    private var cosmeticEvents: List<CurrentPageCosmeticFilterEvent> = emptyList()

    @Synchronized
    fun reset(url: String) {
        pageUrl = url
        networkTotalCount = 0
        networkEvents.clear()
        cosmeticCandidateCount = 0
        cosmeticMatchedCount = 0
        cosmeticEvents = emptyList()
    }

    @Synchronized
    fun addNetwork(event: CurrentPageNetworkFilterEvent) {
        if (pageUrl.isBlank()) {
            pageUrl = event.topLevelUrl
        }
        networkTotalCount += 1
        networkEvents.addFirst(event)
        while (networkEvents.size > CURRENT_PAGE_FILTER_EVENT_LIMIT) {
            networkEvents.removeLast()
        }
    }

    @Synchronized
    fun setCosmeticCandidates(pageUrl: String, candidateCount: Int) {
        if (pageUrl.isNotBlank()) {
            this.pageUrl = pageUrl
        }
        cosmeticCandidateCount = candidateCount
        cosmeticMatchedCount = 0
        cosmeticEvents = emptyList()
    }

    @Synchronized
    fun setCosmeticHits(pageUrl: String, matches: List<CosmeticFilterMatch>) {
        if (pageUrl.isNotBlank()) {
            this.pageUrl = pageUrl
        }
        cosmeticMatchedCount = matches.size
        cosmeticEvents = matches
            .take(CURRENT_PAGE_COSMETIC_EVENT_LIMIT)
            .map { match ->
                CurrentPageCosmeticFilterEvent(
                    selector = match.selector,
                    ruleText = match.rawText,
                    sourceName = match.sourceName
                )
            }
    }

    @Synchronized
    fun snapshot(): CurrentPageFilterSnapshot {
        return CurrentPageFilterSnapshot(
            pageUrl = pageUrl,
            networkTotalCount = networkTotalCount,
            networkEvents = networkEvents.toList(),
            cosmeticCandidateCount = cosmeticCandidateCount,
            cosmeticMatchedCount = cosmeticMatchedCount,
            cosmeticEvents = cosmeticEvents
        )
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
    private var pullToRefreshIndicator: PullToRefreshIndicatorView? = null
    private var floatingControlsOverlay: FloatingBrowserControlsOverlay? = null
    private var exitVerificationDialog: AlertDialog? = null
    private var timeoutDialog: AlertDialog? = null
    private var forceRefreshDialog: AlertDialog? = null
    private var timeLimitJob: Job? = null
    private var filterRuntimeInitializationJob: Job? = null
    private var sessionStartTimeMs: Long = 0L
    private var currentPageLoading = false
    private var currentPageProgress = 0
    private var navigationRootHost = ""
    private var launchedWebAppId: Int? = null
    private var lastRecordedHistoryUrl: String = ""
    private var lastRecordedHistoryAtMs: Long = 0L
    @Volatile
    private lateinit var runtimeConfig: WebViewRuntimeConfig
    private lateinit var webViewFilterRuntime: WebViewFilterRuntime
    private var pendingFilterNavigationGeneration: Long = -1L
    private var pendingFilterNavigationIntent: Intent? = null
    private var lastReportedDegradedFilterGeneration: Long = -1L
    private var pendingGeolocationRequest: PendingGeolocationRequest? = null
    private var pendingNativeLocationPermissionRequest: PendingNativeLocationPermissionRequest? = null
    private var geolocationPermissionDialog: AlertDialog? = null
    private var pendingMediaPermissionRequest: PendingMediaPermissionRequest? = null
    private var pendingDownloadRequest: PendingDownloadRequest? = null
    private var downloadPermissionDialog: AlertDialog? = null
    private var bookmarkEditorView: ComposeView? = null
    private var customDialogView: ComposeView? = null
    internal val lastAttemptedSchemeMap = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val currentPageFilterDiagnostics = ConcurrentHashMap<String, MutablePageFilterDiagnostics>()
    private val webViewFilterTabIds = ConcurrentHashMap<WebView, String>()
    private data class PendingPopup(
        val resolution: AtomicBoolean,
        val timeout: Runnable
    )

    private val pendingPopupWebViews = ConcurrentHashMap<WebView, PendingPopup>()
    private val pendingPopupReloads = ConcurrentHashMap<WebView, Runnable>()
    private val popupMainHandler = Handler(Looper.getMainLooper())
    private val pullToRefreshPageOptOut = ConcurrentHashMap<WebView, Boolean>()
    private val nativeLocationBridgeWatchIds = ConcurrentHashMap<String, String>()
    private val nativeLocationBridgeNativeRequestIds = ConcurrentHashMap<String, String>()
    private val nativeLocationBridgeWebViewRequests = ConcurrentHashMap<WebView, MutableSet<String>>()
    private val nativeLocationWarmupTimestamps = ConcurrentHashMap<String, Long>()
    private val filterControlsUpdateScheduled = AtomicBoolean(false)
    private val nativeLocationManager by lazy { NativeLocationManager(this) }
    private val nativeLocationMainProcessClient by lazy { NativeLocationMainProcessClient(this) }

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

    private data class PendingNativeLocationPermissionRequest(
        val onGranted: () -> Unit,
        val onDenied: () -> Unit
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
            val pendingNative = pendingNativeLocationPermissionRequest
            pendingNativeLocationPermissionRequest = null
            pendingNative?.onGranted?.invoke()
        } else {
            finishPendingGeolocationRequest(allow = false, retain = false)
            val pendingNative = pendingNativeLocationPermissionRequest
            pendingNativeLocationPermissionRequest = null
            pendingNative?.onDenied?.invoke()
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

    override fun onStop() {
        stopAllNativeLocationRequests("activity_stop")
        super.onStop()
    }

    override fun onDestroy() {
        timeLimitJob?.cancel()
        timeLimitJob = null
        filterRuntimeInitializationJob?.cancel()
        filterRuntimeInitializationJob = null
        exitVerificationDialog?.dismiss()
        exitVerificationDialog = null
        timeoutDialog?.dismiss()
        timeoutDialog = null
        forceRefreshDialog?.dismiss()
        forceRefreshDialog = null
        geolocationPermissionDialog?.dismiss()
        geolocationPermissionDialog = null
        finishPendingGeolocationRequest(allow = false, retain = false)
        pendingNativeLocationPermissionRequest?.onDenied?.invoke()
        pendingNativeLocationPermissionRequest = null
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
        pendingPopupWebViews.entries.toList().forEach { (webView, pending) ->
            popupMainHandler.removeCallbacks(pending.timeout)
            pending.resolution.compareAndSet(false, true)
            destroyWebViewSafely(webView)
        }
        pendingPopupWebViews.clear()
        pendingPopupReloads.values.forEach(popupMainHandler::removeCallbacks)
        pendingPopupReloads.clear()
        nativeLocationManager.destroy()
        nativeLocationMainProcessClient.destroy()
        nativeLocationBridgeWatchIds.clear()
        nativeLocationBridgeNativeRequestIds.clear()
        nativeLocationBridgeWebViewRequests.clear()
        tabList.clear()
        webViewStack.clear()
        floatingControlsOverlay = null
        pullToRefreshIndicator?.stopRefreshing()
        pullToRefreshIndicator = null
        topProgress = null
        webViewRoot = null
        rootWebView = null
        pendingFilterNavigationIntent = null
        pendingFilterNavigationGeneration = -1L
        if (::webViewFilterRuntime.isInitialized) {
            webViewFilterRuntime.close()
        }
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

    private fun requestAndroidLocationPermissionForNativeLocation(
        onGranted: () -> Unit,
        onDenied: () -> Unit
    ) {
        pendingNativeLocationPermissionRequest?.onDenied?.invoke()
        pendingNativeLocationPermissionRequest = PendingNativeLocationPermissionRequest(onGranted, onDenied)
        runCatching {
            locationPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }.onFailure { e ->
            Log.w("ChildKioskLocation", "Native location permission request failed", e)
            val pending = pendingNativeLocationPermissionRequest
            pendingNativeLocationPermissionRequest = null
            pending?.onDenied?.invoke()
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

    internal fun currentWebViewFilterHandle(): WebViewFilterEngineHandle {
        return webViewFilterRuntime.currentHandle()
    }

    internal fun currentWebViewFilterHandleOrNull(): WebViewFilterEngineHandle? {
        return if (::webViewFilterRuntime.isInitialized) webViewFilterRuntime.currentHandle() else null
    }

    internal fun registerPendingPopup(webView: WebView, resolution: AtomicBoolean): Boolean {
        if (pendingPopupWebViews.size >= MAX_PENDING_POPUPS) return false
        lateinit var pending: PendingPopup
        val timeout = Runnable {
            if (pendingPopupWebViews.remove(webView, pending) &&
                resolution.compareAndSet(false, true)
            ) {
                Log.w("ChildKioskFilter", "Popup target timed out before a filter decision")
                destroyWebViewSafely(webView)
            }
        }
        pending = PendingPopup(resolution, timeout)
        if (pendingPopupWebViews.putIfAbsent(webView, pending) != null) return false
        popupMainHandler.postDelayed(timeout, POPUP_TARGET_TIMEOUT_MS)
        return true
    }

    internal fun claimPendingPopup(webView: WebView, resolution: AtomicBoolean): Boolean {
        val pending = pendingPopupWebViews[webView]
        if (pending == null || pending.resolution !== resolution) return false
        if (!resolution.compareAndSet(false, true)) return false
        pendingPopupWebViews.remove(webView, pending)
        popupMainHandler.removeCallbacks(pending.timeout)
        return true
    }

    internal fun cancelPendingPopup(webView: WebView) {
        pendingPopupWebViews.remove(webView)?.let { pending ->
            popupMainHandler.removeCallbacks(pending.timeout)
            pending.resolution.compareAndSet(false, true)
        }
        pendingPopupReloads.remove(webView)?.let(popupMainHandler::removeCallbacks)
    }

    internal fun scheduleRegisteredPopupReload(webView: WebView, targetUrl: String) {
        lateinit var reload: Runnable
        reload = Runnable {
            if (!pendingPopupReloads.remove(webView, reload)) return@Runnable
            if (isDestroyed || isFinishing || tabList.none { it.webView === webView }) return@Runnable
            runCatching { loadFilteredMainFrame(webView, targetUrl) }
                .onFailure { error ->
                    Log.w("ChildKioskFilter", "Skipped failed popup reload", error)
                }
        }
        pendingPopupReloads.put(webView, reload)?.let(popupMainHandler::removeCallbacks)
        popupMainHandler.post(reload)
    }

    private fun filteredMainFrameUrl(url: String, topLevelUrl: String = url): String {
        if (!runtimeConfig.limitAdBlock || !WebViewRuntime.isWebUrl(url)) return url
        val handle = currentWebViewFilterHandleOrNull() ?: return url
        val siteOverride = FilterRepository.siteOverrideFor(
            handle.snapshot,
            WebViewRuntime.hostOf(topLevelUrl)
        )
        return handle.engine.cleanUrlForNavigation(
            url = url,
            topLevelUrl = topLevelUrl,
            method = "GET",
            isMainFrame = true,
            siteOverride = siteOverride
        ) ?: url
    }

    internal fun loadFilteredMainFrame(
        webView: WebView,
        url: String,
        additionalHeaders: Map<String, String>? = null
    ) {
        val filteredUrl = filteredMainFrameUrl(url, webView.url.orEmpty().ifBlank { url })
        if (additionalHeaders.isNullOrEmpty()) {
            webView.loadUrl(filteredUrl)
        } else {
            webView.loadUrl(filteredUrl, additionalHeaders)
        }
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

    private fun addNativeLocationBridgeAllowedOrigin(origin: String) {
        val normalized = normalizePermissionOrigin(origin)
        if (normalized.isBlank()) return
        KioskPrefs.addNativeLocationBridgeAllowedOrigin(this, normalized)
        val currentList = runtimeConfig.nativeLocationBridgeAllowedOrigins.toMutableSet()
        currentList.add(normalized)
        runtimeConfig = runtimeConfig.copy(nativeLocationBridgeAllowedOrigins = currentList)
    }

    fun nativeLocationDiagnosticSummary(): String = nativeLocationManager.diagnosticSummary()

    fun requestNativeLocationForAdmin(callback: (NativeLocationResult) -> Unit) {
        val runRequest: () -> Unit = {
                nativeLocationManager.requestSingleLocation(
                    config = runtimeConfig.copy(nativeLocationOptimizationEnabled = true),
                    timeoutMs = runtimeConfig.nativeLocationRequestTimeoutMs,
                    allowCached = true,
                    purpose = "admin_test",
                    origin = currentPageOrigin(),
                    callback = callback
                )
        }
        if (hasLocationPermission()) {
            runRequest()
        } else {
            requestAndroidLocationPermissionForNativeLocation(
                onGranted = { runRequest() },
                onDenied = {
                    callback(
                        NativeLocationResult(
                            success = false,
                            error = NativeLocationError.PERMISSION_DENIED,
                            message = "未获得系统定位权限"
                        )
                    )
                }
            )
        }
    }

    private fun maybeWarmupNativeLocation(url: String?) {
        val latestConfig = latestRuntimeConfig()
        if (!latestConfig.nativeLocationOptimizationEnabled || !latestConfig.nativeLocationWarmupEnabled) return
        if (latestConfig.limitGeolocation) return
        val origin = normalizePermissionOrigin(originForWebStorage(url.orEmpty()).orEmpty())
        if (origin.isBlank()) return
        if (isOriginBlacklisted(latestConfig.geolocationBlacklist, origin)) return
        if (!hasLocationPermission()) return
        if (!shouldWarmupNativeLocation(origin, latestConfig.nativeLocationWarmupTimeoutMs)) return
        nativeLocationMainProcessClient.requestSingleLocation(
            config = latestConfig.copy(
                nativeLocationRequestTimeoutMs = latestConfig.nativeLocationWarmupTimeoutMs
            ),
            timeoutMs = latestConfig.nativeLocationWarmupTimeoutMs,
            allowCached = true,
            purpose = "warmup:$origin",
            origin = origin
        ) { result ->
            Log.d("ChildKioskLocation", "Warmup result: ${result.toDiagnosticLine(redactCoordinates = true)}")
        }
    }

    private fun shouldWarmupNativeLocation(origin: String, timeoutMs: Long): Boolean {
        val now = System.currentTimeMillis()
        val minIntervalMs = timeoutMs.coerceAtLeast(3_000L)
        val previous = nativeLocationWarmupTimestamps[origin] ?: 0L
        if (now - previous < minIntervalMs) return false
        nativeLocationWarmupTimestamps[origin] = now
        if (nativeLocationWarmupTimestamps.size > 24) {
            nativeLocationWarmupTimestamps.entries
                .sortedBy { it.value }
                .take(nativeLocationWarmupTimestamps.size - 24)
                .forEach { nativeLocationWarmupTimestamps.remove(it.key) }
        }
        return true
    }

    private fun stopAllNativeLocationRequests(reason: String) {
        nativeLocationBridgeWebViewRequests.keys.toList().forEach { webView ->
            clearNativeLocationBridgeRequests(webView)
        }
        nativeLocationBridgeWatchIds.clear()
        nativeLocationBridgeNativeRequestIds.clear()
        nativeLocationManager.destroy()
        nativeLocationMainProcessClient.destroy()
        Log.d("ChildKioskLocation", "Stopped native location requests: $reason")
    }

    private fun requestNativeLocationBridgePermission(
        webView: WebView,
        origin: String,
        onAllowed: () -> Unit,
        onDenied: (NativeLocationError, String) -> Unit
    ) {
        val normalizedOrigin = normalizePermissionOrigin(origin)
        val latestConfig = latestRuntimeConfig()
        if (!latestConfig.nativeLocationOptimizationEnabled || !latestConfig.nativeLocationBridgeEnabled) {
            onDenied(NativeLocationError.PERMISSION_DENIED, "原生定位托管未开启")
            return
        }
        if (latestConfig.limitGeolocation) {
            onDenied(NativeLocationError.PERMISSION_DENIED, "网页定位功能已受限制")
            return
        }
        val currentOrigin = normalizePermissionOrigin(originForWebStorage(webView.url.orEmpty()).orEmpty())
        if (currentOrigin.isBlank() || currentOrigin != normalizedOrigin) {
            onDenied(NativeLocationError.PERMISSION_DENIED, "定位请求来源与当前页面不一致")
            return
        }
        if (normalizedOrigin.isBlank() || isOriginBlacklisted(latestConfig.geolocationBlacklist, normalizedOrigin)) {
            onDenied(NativeLocationError.PERMISSION_DENIED, "该网站已被禁止获取位置")
            return
        }
        if (latestConfig.nativeLocationBridgeAllowedOrigins.contains(normalizedOrigin)) {
            onAllowed()
            return
        }

        finishPendingGeolocationRequest(allow = false, retain = false)
        geolocationPermissionDialog?.dismiss()
        dismissCustomComposeDialog()

        val siteName = displayOrigin(normalizedOrigin)
        showCustomComposeDialog {
            BeautifulConfirmDialog(
                title = "允许原生定位托管？",
                message = "$siteName 正在通过系统 LocationManager 请求位置。仅在确认站点可信时允许；允许后会加入原生定位托管允许列表。",
                icon = Icons.Default.LocationOn,
                blacklistText = "拒绝且不再提示（加入黑名单）",
                onNegative = {
                    dismissCustomComposeDialog()
                    onDenied(NativeLocationError.PERMISSION_DENIED, "用户拒绝原生定位托管")
                },
                onPositive = {
                    dismissCustomComposeDialog()
                    addNativeLocationBridgeAllowedOrigin(normalizedOrigin)
                    onAllowed()
                },
                onBlacklist = {
                    dismissCustomComposeDialog()
                    addGeolocationOriginToBlacklist(normalizedOrigin)
                    Toast.makeText(this@WebViewActivity, "已将该网址加入定位黑名单", Toast.LENGTH_SHORT).show()
                    onDenied(NativeLocationError.PERMISSION_DENIED, "该网站已加入定位黑名单")
                },
                onDismiss = {
                    dismissCustomComposeDialog()
                    onDenied(NativeLocationError.PERMISSION_DENIED, "用户取消原生定位托管")
                }
            )
        }
    }

    internal fun nativeLocationBridgeGetCurrentPosition(
        webView: WebView,
        requestId: String,
        origin: String,
        timeoutMs: Long?,
        maximumAgeMs: Long?
    ) {
        if (requestId.isBlank()) return
        registerNativeLocationBridgeRequest(webView, requestId)
        val runRequest: () -> Unit = runRequest@{
            if (!isNativeLocationBridgeRequestActive(webView, requestId)) return@runRequest
            val latestConfig = latestRuntimeConfig()
            val requestConfig = latestConfig.copy(
                nativeLocationRequestTimeoutMs = timeoutMs?.coerceIn(1_000L, 60_000L)
                    ?: latestConfig.nativeLocationRequestTimeoutMs,
                nativeLocationMaxCacheAgeMs = maximumAgeMs?.coerceIn(0L, 10 * 60_000L)
                    ?: latestConfig.nativeLocationMaxCacheAgeMs
            )
            val nativeRequestId = nativeLocationMainProcessClient.requestSingleLocation(
                config = requestConfig,
                timeoutMs = requestConfig.nativeLocationRequestTimeoutMs,
                allowCached = true,
                purpose = "bridge_get:$origin",
                origin = origin
            ) { result ->
                nativeLocationBridgeNativeRequestIds.remove(requestId)
                dispatchNativeLocationBridgeResult(webView, requestId, result)
                unregisterNativeLocationBridgeRequest(webView, requestId)
            }
            if (nativeRequestId.isNotBlank()) {
                nativeLocationBridgeNativeRequestIds[requestId] = nativeRequestId
            }
        }
        requestNativeLocationBridgePermission(
            webView = webView,
            origin = origin,
            onAllowed = {
                if (hasLocationPermission()) {
                    runRequest()
                } else {
                    requestAndroidLocationPermissionForNativeLocation(
                        onGranted = runRequest,
                        onDenied = {
                            val result = NativeLocationResult(
                                success = false,
                                error = NativeLocationError.PERMISSION_DENIED,
                                message = "未获得系统定位权限"
                            )
                            nativeLocationManager.recordAuditOnly("bridge_get_denied_permission", origin, result)
                            dispatchNativeLocationBridgeResult(
                                webView,
                                requestId,
                                result
                            )
                            unregisterNativeLocationBridgeRequest(webView, requestId)
                        }
                    )
                }
            },
            onDenied = { error, message ->
                val result = NativeLocationResult(success = false, error = error, message = message)
                nativeLocationManager.recordAuditOnly("bridge_get_denied_policy", origin, result)
                dispatchNativeLocationBridgeResult(
                    webView,
                    requestId,
                    result
                )
                unregisterNativeLocationBridgeRequest(webView, requestId)
            }
        )
    }

    internal fun nativeLocationBridgeStartWatch(
        webView: WebView,
        requestId: String,
        origin: String
    ) {
        if (requestId.isBlank()) return
        val currentWatchCount = nativeLocationBridgeWebViewRequests[webView]
            ?.count { nativeLocationBridgeWatchIds.containsKey(it) }
            ?: 0
        if (currentWatchCount >= MAX_NATIVE_LOCATION_WATCHES_PER_WEBVIEW) {
            val result = NativeLocationResult(
                success = false,
                error = NativeLocationError.PROVIDER_UNAVAILABLE,
                message = "watchPosition 数量已达到上限"
            )
            nativeLocationManager.recordAuditOnly("watch_denied_limit", origin, result)
            dispatchNativeLocationBridgeResult(
                webView = webView,
                requestId = requestId,
                result = result,
                isWatch = true,
                requireActive = false
            )
            return
        }
        registerNativeLocationBridgeRequest(webView, requestId)
        val runRequest: () -> Unit = runRequest@{
            if (!isNativeLocationBridgeRequestActive(webView, requestId)) return@runRequest
            val watchId = nativeLocationMainProcessClient.startWatch(latestRuntimeConfig(), origin = origin) { result ->
                dispatchNativeLocationBridgeResult(webView, requestId, result, isWatch = true)
                if (!result.success) {
                    nativeLocationBridgeWatchIds.remove(requestId)?.let { nativeId ->
                        nativeLocationMainProcessClient.cancelRequest(nativeId)
                    }
                    unregisterNativeLocationBridgeRequest(webView, requestId)
                }
            }
            if (watchId.isNotBlank()) {
                nativeLocationBridgeWatchIds[requestId] = watchId
            } else {
                unregisterNativeLocationBridgeRequest(webView, requestId)
            }
        }
        requestNativeLocationBridgePermission(
            webView = webView,
            origin = origin,
            onAllowed = {
                if (hasLocationPermission()) {
                    runRequest()
                } else {
                    requestAndroidLocationPermissionForNativeLocation(
                        onGranted = runRequest,
                        onDenied = {
                            val result = NativeLocationResult(
                                success = false,
                                error = NativeLocationError.PERMISSION_DENIED,
                                message = "未获得系统定位权限"
                            )
                            nativeLocationManager.recordAuditOnly("watch_denied_permission", origin, result)
                            dispatchNativeLocationBridgeResult(
                                webView,
                                requestId,
                                result,
                                isWatch = true
                            )
                            unregisterNativeLocationBridgeRequest(webView, requestId)
                        }
                    )
                }
            },
            onDenied = { error, message ->
                val result = NativeLocationResult(success = false, error = error, message = message)
                nativeLocationManager.recordAuditOnly("watch_denied_policy", origin, result)
                dispatchNativeLocationBridgeResult(
                    webView,
                    requestId,
                    result,
                    isWatch = true
                )
                unregisterNativeLocationBridgeRequest(webView, requestId)
            }
        )
    }

    internal fun nativeLocationBridgeClearWatch(webView: WebView, requestId: String) {
        if (requestId.isBlank()) return
        unregisterNativeLocationBridgeRequest(webView, requestId)
        nativeLocationBridgeNativeRequestIds.remove(requestId)?.let { nativeId ->
            nativeLocationMainProcessClient.cancelRequest(nativeId)
        }
        nativeLocationBridgeWatchIds.remove(requestId)?.let { nativeId ->
            nativeLocationMainProcessClient.cancelRequest(nativeId)
        }
    }

    internal fun handleNativeLocationBridgeMessage(
        webView: WebView,
        rawMessage: String?,
        sourceOrigin: Uri,
        isMainFrame: Boolean
    ) {
        if (!isMainFrame) {
            Log.w("ChildKioskLocation", "Rejected native location request from iframe: $sourceOrigin")
            return
        }
        val request = parseNativeLocationBridgeRequest(rawMessage) ?: return
        val normalizedSourceOrigin = normalizePermissionOrigin(sourceOrigin.toString())
        if (normalizedSourceOrigin.isBlank() || normalizedSourceOrigin != request.origin) {
            dispatchNativeLocationBridgeResult(
                webView = webView,
                requestId = request.id,
                result = NativeLocationResult(
                    success = false,
                    error = NativeLocationError.PERMISSION_DENIED,
                    message = "定位请求来源不可信"
                ),
                isWatch = request.type == "watchPosition",
                requireActive = false
            )
            return
        }
        when (request.type) {
            "getCurrentPosition" -> nativeLocationBridgeGetCurrentPosition(
                webView = webView,
                requestId = request.id,
                origin = request.origin,
                timeoutMs = request.timeoutMs,
                maximumAgeMs = request.maximumAgeMs
            )
            "watchPosition" -> nativeLocationBridgeStartWatch(
                webView = webView,
                requestId = request.id,
                origin = request.origin
            )
            "clearWatch" -> nativeLocationBridgeClearWatch(webView, request.id)
        }
    }

    private fun parseNativeLocationBridgeRequest(rawMessage: String?): NativeLocationBridgeRequest? {
        return runCatching {
            val obj = JSONObject(rawMessage.orEmpty())
            val id = obj.optString("id", "").take(80)
            if (id.isBlank()) return@runCatching null
            NativeLocationBridgeRequest(
                type = obj.optString("type", ""),
                id = id,
                origin = normalizePermissionOrigin(obj.optString("origin", "")),
                timeoutMs = obj.optLongOrNull("timeout"),
                maximumAgeMs = obj.optLongOrNull("maximumAge")
            )
        }.getOrNull()
    }

    private fun registerNativeLocationBridgeRequest(webView: WebView, requestId: String) {
        nativeLocationBridgeWebViewRequests.getOrPut(webView) { mutableSetOf() }.add(requestId)
    }

    private fun unregisterNativeLocationBridgeRequest(webView: WebView, requestId: String) {
        nativeLocationBridgeWebViewRequests[webView]?.remove(requestId)
    }

    private fun isNativeLocationBridgeRequestActive(webView: WebView, requestId: String): Boolean {
        return nativeLocationBridgeWebViewRequests[webView]?.contains(requestId) == true
    }

    internal fun clearNativeLocationBridgeRequests(webView: WebView) {
        nativeLocationBridgeWebViewRequests.remove(webView)?.forEach { requestId ->
            nativeLocationBridgeNativeRequestIds.remove(requestId)?.let { nativeId ->
                nativeLocationMainProcessClient.cancelRequest(nativeId)
            }
            nativeLocationBridgeWatchIds.remove(requestId)?.let { nativeId ->
                nativeLocationMainProcessClient.cancelRequest(nativeId)
            }
        }
    }

    private fun dispatchNativeLocationBridgeResult(
        webView: WebView,
        requestId: String,
        result: NativeLocationResult,
        isWatch: Boolean = false,
        requireActive: Boolean = true
    ) {
        if (requireActive && nativeLocationBridgeWebViewRequests[webView]?.contains(requestId) != true) return
        val payload = nativeLocationBridgePayload(requestId, result, isWatch)
        val js = "window.__ChildKioskNativeLocation && window.__ChildKioskNativeLocation.dispatch($payload);"
        webView.post {
            if (webView.url.isNullOrBlank()) return@post
            webView.evaluateJavascript(js, null)
        }
    }

    internal fun maybeStartAmapAssistantLocation(webView: WebView, url: String?) {
        val latestConfig = KioskPrefs.mergeFreshAmapLocationRuntimeConfig(this, latestRuntimeConfig())
        if (!latestConfig.amapLocationEnabled || !latestConfig.amapLocationH5AssistantEnabled) return
        if (latestConfig.limitGeolocation) return
        val origin = normalizePermissionOrigin(originForWebStorage(url.orEmpty()).orEmpty())
        if (origin.isBlank()) return
        if (isOriginBlacklisted(latestConfig.geolocationBlacklist, origin)) return
        nativeLocationManager.startAmapAssistantLocation(webView, latestConfig, origin)
    }

    internal fun stopAmapAssistantLocation(webView: WebView) {
        nativeLocationManager.stopAmapAssistantLocation(webView)
    }

    private fun nativeLocationBridgePayload(
        requestId: String,
        result: NativeLocationResult,
        isWatch: Boolean
    ): String {
        val obj = JSONObject()
            .put("id", requestId)
            .put("watch", isWatch)
            .put("success", result.success)
        if (result.success) {
            val coords = JSONObject()
                .put("latitude", result.latitude)
                .put("longitude", result.longitude)
                .put("accuracy", result.accuracyMeters ?: 0.0)
                .put("altitude", result.altitude ?: JSONObject.NULL)
                .put("altitudeAccuracy", JSONObject.NULL)
                .put("heading", result.bearing ?: JSONObject.NULL)
                .put("speed", result.speed ?: JSONObject.NULL)
            obj.put("coords", coords)
            obj.put("timestamp", result.wallTimeMillis ?: System.currentTimeMillis())
        } else {
            obj.put("errorCode", when (result.error) {
                NativeLocationError.PERMISSION_DENIED -> 1
                NativeLocationError.TIMEOUT -> 3
                else -> 2
            })
            obj.put("message", result.message.ifBlank { result.error?.name ?: "定位失败" })
        }
        return obj.toString()
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
        runtimeConfig = KioskPrefs.getWebViewRuntimeConfig(intent, this)
        prepareFilterRuntimeThenHandle(intent)
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
        installPullToRefreshIndicator(root)
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

        initializeWebViewFilterRuntimeThenHandle(intent)
    }

    private fun buildWebViewFilterRuntime(
        fallbackRequest: site.anzz.childkiosk.util.filter.FilterRuntimeSnapshot
    ): WebViewFilterRuntime {
        // Always retain a deterministic local safety baseline for future enabled snapshots.
        val fallbackSnapshot = FilterRepository.bundledFallbackSnapshot(fallbackRequest)
        val fallbackEngine = FilterRepository.getBundledFallbackEngine(fallbackRequest)
        return WebViewFilterRuntime(
            engineLoader = { snapshot ->
                FilterRepository.getEngine(applicationContext, snapshot)
            },
            bundledSnapshot = fallbackSnapshot,
            bundledEngine = fallbackEngine
        )
    }

    private fun initializeWebViewFilterRuntimeThenHandle(initialIntent: Intent?) {
        pendingFilterNavigationIntent = initialIntent
        val fallbackRequest = runtimeConfig.filterSnapshot.copy(enabled = true)
        filterRuntimeInitializationJob = lifecycleScope.launch {
            val runtimeResult = withContext(Dispatchers.Default) {
                runCatching { buildWebViewFilterRuntime(fallbackRequest) }
            }
            if (isDestroyed || isFinishing) {
                runtimeResult.getOrNull()?.close()
                return@launch
            }
            val initializedRuntime = runtimeResult.getOrElse { error ->
                Log.e("ChildKioskFilter", "Bundled filter runtime initialization failed", error)
                Toast.makeText(
                    this@WebViewActivity,
                    "网页过滤器初始化失败，已停止打开网页",
                    Toast.LENGTH_LONG
                ).show()
                return@launch
            }
            webViewFilterRuntime = initializedRuntime
            val intentToHandle = pendingFilterNavigationIntent ?: initialIntent
            pendingFilterNavigationIntent = null
            prepareFilterRuntimeThenHandle(intentToHandle)
        }
    }

    private fun prepareFilterRuntimeThenHandle(nextIntent: Intent?) {
        nextIntent ?: return
        if (!::webViewFilterRuntime.isInitialized) {
            pendingFilterNavigationIntent = nextIntent
            return
        }
        val requestedSnapshot = runtimeConfig.filterSnapshot.copy(
            enabled = runtimeConfig.limitAdBlock
        )
        if (!requestedSnapshot.enabled) {
            pendingFilterNavigationIntent = null
            pendingFilterNavigationGeneration = -1L
            handleIntent(nextIntent)
            return
        }

        pendingFilterNavigationIntent = nextIntent
        val generation = webViewFilterRuntime.prepare(requestedSnapshot) { handle ->
            runOnUiThread { onWebViewFilterRuntimeChanged(handle) }
        }
        pendingFilterNavigationGeneration = generation
    }

    private fun onWebViewFilterRuntimeChanged(handle: WebViewFilterEngineHandle) {
        if (isDestroyed || isFinishing) return
        Log.i(
            "ChildKioskFilter",
            "runtime=${handle.status}, generation=${handle.generation}, " +
                "servingPreset=${handle.snapshot.preset}, requestedPreset=${handle.requestedSnapshot.preset}, " +
                "reason=${handle.reason}"
        )
        if (handle.status == WebViewFilterRuntimeStatus.PREPARING ||
            handle.generation != pendingFilterNavigationGeneration
        ) {
            return
        }

        if ((handle.status == WebViewFilterRuntimeStatus.DEGRADED_LKG ||
                handle.status == WebViewFilterRuntimeStatus.DEGRADED_BUNDLED) &&
            lastReportedDegradedFilterGeneration != handle.generation
        ) {
            lastReportedDegradedFilterGeneration = handle.generation
            val source = if (handle.status == WebViewFilterRuntimeStatus.DEGRADED_LKG) {
                "上一版有效规则"
            } else {
                "内置安全规则"
            }
            Toast.makeText(
                this,
                "网页过滤加载失败，已降级使用$source",
                Toast.LENGTH_LONG
            ).show()
        }

        val intentToHandle = pendingFilterNavigationIntent
        pendingFilterNavigationIntent = null
        pendingFilterNavigationGeneration = -1L
        handleIntent(intentToHandle)
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

    private fun installPullToRefreshIndicator(root: FrameLayout) {
        val density = resources.displayMetrics.density
        val size = (48f * density).toInt().coerceAtLeast(48)
        pullToRefreshIndicator = PullToRefreshIndicatorView(this).apply {
            visibility = View.GONE
            alpha = 0f
            isClickable = false
            isFocusable = false
            translationY = -size.toFloat()
        }
        root.addView(
            pullToRefreshIndicator,
            FrameLayout.LayoutParams(size, size, Gravity.TOP or Gravity.CENTER_HORIZONTAL)
        )
    }

    private fun updatePullToRefreshIndicator(dragDistance: Float, triggerDistance: Float, armed: Boolean) {
        val indicator = pullToRefreshIndicator ?: return
        if (indicator.isRefreshing) return
        val progress = (dragDistance / triggerDistance).coerceIn(0f, 1f)
        val hiddenY = pullToRefreshIndicatorHiddenY(indicator)
        val maxY = 44f * resources.displayMetrics.density
        indicator.visibility = View.VISIBLE
        indicator.alpha = (0.25f + progress * 0.75f).coerceIn(0f, 1f)
        indicator.translationY = hiddenY + (maxY - hiddenY) * progress
        indicator.setPullProgress(progress, armed)
    }

    private fun resetPullToRefreshIndicator(animated: Boolean = true) {
        val indicator = pullToRefreshIndicator ?: return
        if (indicator.isRefreshing) return
        val hiddenY = pullToRefreshIndicatorHiddenY(indicator)
        indicator.animate().cancel()
        if (animated) {
            indicator.animate()
                .translationY(hiddenY)
                .alpha(0f)
                .setDuration(180L)
                .withEndAction {
                    if (!indicator.isRefreshing) {
                        indicator.visibility = View.GONE
                        indicator.setPullProgress(0f, false)
                    }
                }
                .start()
        } else {
            indicator.translationY = hiddenY
            indicator.alpha = 0f
            indicator.visibility = View.GONE
            indicator.setPullProgress(0f, false)
        }
    }

    private fun pullToRefreshIndicatorHiddenY(indicator: View): Float {
        val measuredHeight = indicator.height.takeIf { it > 0 }?.toFloat()
            ?: (48f * resources.displayMetrics.density)
        return -measuredHeight
    }

    private fun startPullToRefreshIndicator() {
        val indicator = pullToRefreshIndicator ?: return
        val refreshY = 44f * resources.displayMetrics.density
        indicator.animate().cancel()
        indicator.visibility = View.VISIBLE
        indicator.alpha = 1f
        indicator.translationY = refreshY
        indicator.setPullProgress(1f, true)
        indicator.startRefreshing()
    }

    private fun finishPullToRefreshIndicator() {
        val indicator = pullToRefreshIndicator ?: return
        indicator.stopRefreshing()
        resetPullToRefreshIndicator(animated = true)
    }

    private fun installPullToRefreshGesture(webView: WebView) {
        val touchSlop = android.view.ViewConfiguration.get(this).scaledTouchSlop
        val density = resources.displayMetrics.density
        val triggerDistance = (density * 112f).coerceAtLeast((touchSlop * 5).toFloat())
        val minDragDurationMs = 160L
        val maxTriggerVelocityY = density * 4200f
        var velocityTracker: VelocityTracker? = null
        var downX = 0f
        var downY = 0f
        var downAtMs = 0L
        var tracking = false
        var armed = false
        webView.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    velocityTracker?.recycle()
                    velocityTracker = VelocityTracker.obtain().apply { addMovement(event) }
                    downX = event.x
                    downY = event.y
                    downAtMs = event.eventTime
                    tracking = latestRuntimeConfig().pullToRefreshEnabled &&
                        fullscreenView == null &&
                        !currentPageLoading &&
                        pullToRefreshPageOptOut[webView] != true &&
                        !webView.canScrollVertically(-1)
                    armed = false
                    if (!tracking) {
                        resetPullToRefreshIndicator(animated = false)
                    }
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    velocityTracker?.addMovement(event)
                    if (tracking) {
                        val deltaX = kotlin.math.abs(event.x - downX)
                        val deltaY = event.y - downY
                        if (deltaX <= touchSlop && kotlin.math.abs(deltaY) <= touchSlop) {
                            return@setOnTouchListener false
                        }
                        val mostlyVertical = deltaY > touchSlop && deltaY > deltaX * 1.45f
                        if (
                            deltaY < -touchSlop ||
                            !mostlyVertical ||
                            fullscreenView != null ||
                            currentPageLoading ||
                            pullToRefreshPageOptOut[webView] == true ||
                            webView.canScrollVertically(-1)
                        ) {
                            tracking = false
                            armed = false
                            resetPullToRefreshIndicator(animated = true)
                        } else {
                            armed = deltaY >= triggerDistance
                            updatePullToRefreshIndicator(deltaY, triggerDistance, armed)
                        }
                    }
                    false
                }
                MotionEvent.ACTION_UP -> {
                    velocityTracker?.addMovement(event)
                    velocityTracker?.computeCurrentVelocity(1000)
                    val velocityY = velocityTracker?.yVelocity ?: 0f
                    val deltaY = event.y - downY
                    val deltaX = kotlin.math.abs(event.x - downX)
                    val durationMs = event.eventTime - downAtMs
                    if (
                        tracking &&
                        armed &&
                        deltaY >= triggerDistance &&
                        deltaY > deltaX * 1.45f &&
                        durationMs >= minDragDurationMs &&
                        velocityY in 0f..maxTriggerVelocityY &&
                        fullscreenView == null &&
                        !currentPageLoading &&
                        pullToRefreshPageOptOut[webView] != true &&
                        !webView.canScrollVertically(-1)
                    ) {
                        view.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                        startPullToRefreshIndicator()
                        currentPageLoading = true
                        webView.reload()
                        updateFloatingControlsState(loading = true)
                    } else {
                        resetPullToRefreshIndicator(animated = true)
                    }
                    tracking = false
                    armed = false
                    velocityTracker?.recycle()
                    velocityTracker = null
                    false
                }
                MotionEvent.ACTION_POINTER_DOWN,
                MotionEvent.ACTION_CANCEL -> {
                    tracking = false
                    armed = false
                    resetPullToRefreshIndicator(animated = true)
                    velocityTracker?.recycle()
                    velocityTracker = null
                    false
                }
                else -> false
            }
        }
    }

    internal fun updatePullToRefreshPagePolicy(webView: WebView, pageUrl: String?) {
        if (!WebViewRuntime.isWebUrl(pageUrl.orEmpty())) {
            pullToRefreshPageOptOut[webView] = false
            return
        }
        val targetUrl = pageUrl.orEmpty()
        val js = """
            (function() {
                function readValue(el) {
                    if (!el || !window.getComputedStyle) return '';
                    var style = window.getComputedStyle(el);
                    return (style.overscrollBehaviorY || style.overscrollBehavior || '').toLowerCase();
                }
                return JSON.stringify([readValue(document.documentElement), readValue(document.body)]);
            })();
        """.trimIndent()
        webView.evaluateJavascript(js) { rawResult ->
            if (webView.url != targetUrl) return@evaluateJavascript
            val values = parseStringArrayJavascriptResult(rawResult)
            pullToRefreshPageOptOut[webView] = values.any { value ->
                value.split(Regex("\\s+")).any { token ->
                    token == "contain" || token == "none"
                }
            }
        }
    }

    private fun createNewTab(
        url: String,
        focus: Boolean = true,
        existingWebView: WebView? = null,
        popupFilterContext: PopupFilterContext? = null
    ): BrowserTab {
        val cleanUrl = url.trim()
        val tabId = java.util.UUID.randomUUID().toString()
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
            onPageStartedInActivity = { webView, pageUrl ->
                pullToRefreshPageOptOut[webView] = false
                maybeWarmupNativeLocation(pageUrl)
            },
            onPageFinishedInActivity = { webView, pageUrl ->
                updatePullToRefreshPagePolicy(webView, pageUrl)
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
            onCreateWindow = { newWebView, popupTargetUrl, childPopupContext ->
                val newTab = createNewTab(
                    url = popupTargetUrl.ifBlank { "about:blank" },
                    focus = true,
                    existingWebView = newWebView,
                    popupFilterContext = childPopupContext
                )
                Log.d("ChildKioskWebView", "Native child window created via onCreateWindow, newTabId=${newTab.id}")
            },
            popupFilterContext = popupFilterContext
        )
        webViewRef = webView
        
        val tab = BrowserTab(
            id = tabId,
            url = cleanUrl,
            title = webView.title.takeIf { !it.isNullOrBlank() } ?: "新标签页",
            webView = webView
        )
        tabList.add(tab)
        registerFilterDiagnosticsWebView(webView, tab.id)
        
        addWebViewToRoot(webView)
        
        if (focus) {
            switchToTab(tab.id)
        } else {
            setWebViewVisible(webView, false)
        }
        
        if (existingWebView == null) {
            if (cleanUrl != "about:blank" && cleanUrl.isNotBlank()) {
                loadInitialUrlAfterFirstLayout(webView, filteredMainFrameUrl(cleanUrl))
            } else {
                loadFilteredMainFrame(webView, "about:blank")
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
        currentPageFilterDiagnostics.remove(tabId)
        
        val webView = targetTab.webView
        if (webView != null) {
            clearNativeLocationBridgeRequests(webView)
            stopAmapAssistantLocation(webView)
            unregisterFilterDiagnosticsWebView(webView)
            removeWebViewFromRoot(webView)
            destroyWebViewSafely(webView)
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
        clearNativeLocationBridgeRequests(webView)
        stopAmapAssistantLocation(webView)
        destroyWebViewSafely(webView)
        unregisterFilterDiagnosticsWebView(webView)
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
            onPageStartedInActivity = { webView, pageUrl ->
                pullToRefreshPageOptOut[webView] = false
                maybeWarmupNativeLocation(pageUrl)
            },
            onPageFinishedInActivity = { webView, pageUrl ->
                updatePullToRefreshPagePolicy(webView, pageUrl)
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
            onCreateWindow = { newWebView, popupTargetUrl, childPopupContext ->
                val newTab = createNewTab(
                    url = popupTargetUrl.ifBlank { "about:blank" },
                    focus = true,
                    existingWebView = newWebView,
                    popupFilterContext = childPopupContext
                )
                Log.d("ChildKioskWebView", "Native child window created via onCreateWindow, newTabId=${newTab.id}")
            }
        )
        webViewRef = webView
        
        tab.webView = webView
        registerFilterDiagnosticsWebView(webView, tab.id)
        addWebViewToRoot(webView)
        
        val state = tab.savedState
        if (state != null) {
            val restored = webView.restoreState(state)
            if (restored == null && cleanUrl.isNotBlank()) {
                loadFilteredMainFrame(webView, cleanUrl)
            }
        } else if (cleanUrl.isNotBlank()) {
            loadFilteredMainFrame(webView, cleanUrl)
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
            Log.d(
                "ChildKioskWebView",
                "Native back: webView.goBack, url=${redactWebUrlForLog(current.url)}"
            )
            current.goBack()
            updateFloatingControlsState(loading = true)
            return
        }

        if (webViewStack.size > 1) {
            val removed = webViewStack.removeLast()
            Log.d(
                "ChildKioskWebView",
                "Native back: destroy child webview, url=${redactWebUrlForLog(removed.url)}"
            )
            clearNativeLocationBridgeRequests(removed)
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
        finishPullToRefreshIndicator()
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
                    if (actionId == ACTION_SHOW_CURRENT_PAGE_FILTERS) {
                        showCurrentPageFilterDialog()
                    } else {
                        Log.d("ChildKioskWebView", "Floating browser action: $actionId")
                    }
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
        updateFloatingControlsExtraSections()
    }

    private fun registerFilterDiagnosticsWebView(webView: WebView, tabId: String) {
        webViewFilterTabIds[webView] = tabId
        currentPageFilterDiagnostics.getOrPut(tabId) { MutablePageFilterDiagnostics() }
    }

    private fun unregisterFilterDiagnosticsWebView(webView: WebView) {
        webViewFilterTabIds.remove(webView)
        pullToRefreshPageOptOut.remove(webView)
    }

    internal fun resetCurrentPageFilterDiagnostics(webView: WebView?, pageUrl: String) {
        val tabId = webView?.let { webViewFilterTabIds[it] } ?: return
        currentPageFilterDiagnostics.getOrPut(tabId) { MutablePageFilterDiagnostics() }
            .reset(pageUrl)
        scheduleFilterControlsUpdate()
    }

    internal fun recordCurrentPageNetworkFilterBlock(
        webView: WebView?,
        topLevelUrl: String,
        requestUrl: String,
        resourceType: FilterResourceType,
        decision: FilterDecision
    ) {
        val tabId = webView?.let { webViewFilterTabIds[it] } ?: return
        val diagnostics = decision.diagnostics
        currentPageFilterDiagnostics.getOrPut(tabId) { MutablePageFilterDiagnostics() }
            .addNetwork(
                CurrentPageNetworkFilterEvent(
                    timestamp = System.currentTimeMillis(),
                    topLevelUrl = topLevelUrl,
                    requestUrl = requestUrl,
                    resourceType = resourceType.optionName,
                    ruleText = decision.rule?.rawText.orEmpty(),
                    sourceName = decision.rule?.sourceName.orEmpty(),
                    reason = decision.reason,
                    matchType = diagnostics?.ruleMatchType.orEmpty(),
                    candidateCount = diagnostics?.candidateCount ?: 0,
                    cacheStatus = diagnostics?.cacheStatus.orEmpty()
                )
            )
        scheduleFilterControlsUpdate()
    }

    internal fun recordCurrentPageCosmeticFilterCandidates(
        webView: WebView,
        pageUrl: String,
        candidateCount: Int
    ) {
        val tabId = webViewFilterTabIds[webView] ?: return
        currentPageFilterDiagnostics.getOrPut(tabId) { MutablePageFilterDiagnostics() }
            .setCosmeticCandidates(pageUrl, candidateCount)
        scheduleFilterControlsUpdate()
    }

    internal fun recordCurrentPageCosmeticFilterHits(
        webView: WebView,
        pageUrl: String,
        matches: List<CosmeticFilterMatch>
    ) {
        val tabId = webViewFilterTabIds[webView] ?: return
        currentPageFilterDiagnostics.getOrPut(tabId) { MutablePageFilterDiagnostics() }
            .setCosmeticHits(pageUrl, matches)
        scheduleFilterControlsUpdate()
    }

    private fun scheduleFilterControlsUpdate() {
        if (!filterControlsUpdateScheduled.compareAndSet(false, true)) return
        val update = Runnable {
            filterControlsUpdateScheduled.set(false)
            updateFloatingControlsExtraSections()
        }
        val root = webViewRoot
        if (root != null) {
            root.post(update)
        } else {
            runOnUiThread(update)
        }
    }

    private fun updateFloatingControlsExtraSections() {
        floatingControlsOverlay?.setExtraSections(currentFloatingControlExtraSections())
    }

    private fun currentFloatingControlExtraSections(): List<FloatingControlSection> {
        if (!runtimeConfig.limitAdBlock || !runtimeConfig.filterSnapshot.enabled) return emptyList()
        val currentUrl = rootWebView?.url.orEmpty()
        if (!WebViewRuntime.isWebUrl(currentUrl)) return emptyList()

        val snapshot = activePageFilterSnapshot()
        val helperText = if (snapshot.totalCount > 0) {
            "当前页：网络 ${snapshot.networkCount} 条，元素命中 ${snapshot.cosmeticCount} 条"
        } else if (snapshot.cosmeticCandidateCount > 0) {
            "元素隐藏候选 ${snapshot.cosmeticCandidateCount} 条，暂无实际命中"
        } else {
            "当前页面暂无过滤拦截记录"
        }
        return listOf(
            FloatingControlSection(
                id = "filter",
                title = "过滤",
                helperText = helperText,
                actions = listOf(
                    FloatingControlAction(
                        id = ACTION_SHOW_CURRENT_PAGE_FILTERS,
                        title = "拦截 ${formatCompactCount(snapshot.totalCount)}",
                        iconRes = if (snapshot.totalCount > 0) {
                            R.drawable.ic_browser_warning_24
                        } else {
                            R.drawable.ic_browser_info_24
                        },
                        enabled = true,
                        highlighted = snapshot.totalCount > 0,
                        style = if (snapshot.totalCount > 0) {
                            FloatingControlActionStyle.PRIMARY
                        } else {
                            FloatingControlActionStyle.NORMAL
                        }
                    )
                )
            )
        )
    }

    private fun activePageFilterSnapshot(): CurrentPageFilterSnapshot {
        val tabId = activeTabId ?: return CurrentPageFilterSnapshot.EMPTY
        val snapshot = currentPageFilterDiagnostics[tabId]?.snapshot()
            ?: CurrentPageFilterSnapshot.EMPTY
        val currentUrl = rootWebView?.url.orEmpty()
            .ifBlank { tabList.firstOrNull { it.id == tabId }?.url.orEmpty() }
        return if (snapshot.pageUrl.isBlank() && currentUrl.isNotBlank()) {
            snapshot.copy(pageUrl = currentUrl)
        } else {
            snapshot
        }
    }

    private fun formatCompactCount(count: Int): String {
        return if (count > 99) "99+" else count.toString()
    }

    private fun showCurrentPageFilterDialog() {
        floatingControlsOverlay?.collapsePanel()
        val snapshot = activePageFilterSnapshot()
        showCustomComposeDialog {
            CurrentPageFilterDiagnosticsDialog(
                snapshot = snapshot,
                onDismiss = { dismissCustomComposeDialog() }
            )
        }
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
                                val frozenIcon = WebAppIconCache.freezeNetworkIcon(this@WebViewActivity, icon, savedUrl)
                                val db = AppDatabase.getInstance(this@WebViewActivity)
                                val existing = db.webAppDao().getAllWebApps().firstOrNull { app ->
                                    normalizeWhitelistWebUrl(app.url) == normalizeWhitelistWebUrl(savedUrl)
                                }
                                if (existing == null) {
                                    db.webAppDao().insertWebApp(
                                        WebAppEntity(
                                            title = savedTitle,
                                            url = savedUrl,
                                            iconPath = frozenIcon,
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
                                            iconPath = frozenIcon,
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
        loadFilteredMainFrame(current, url)
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
        loadFilteredMainFrame(
            webView = webView,
            url = url,
            additionalHeaders = mapOf(
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
        finishPullToRefreshIndicator()
        updateFloatingControlsState(loading = false)
    }

    private fun updateFloatingControlsState(
        loading: Boolean? = null,
        progress: Int? = null
    ) {
        loading?.let { currentPageLoading = it }
        if (loading == false) {
            finishPullToRefreshIndicator()
        }
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
        updateFloatingControlsExtraSections()
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
                initialNativeLocationBridgeEnabled = runtimeConfig.nativeLocationOptimizationEnabled &&
                    runtimeConfig.nativeLocationBridgeEnabled,
                initialGeoBlacklist = runtimeConfig.geolocationBlacklist,
                initialNativeLocationAllowedOrigins = runtimeConfig.nativeLocationBridgeAllowedOrigins,
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
                onUpdateNativeLocationAllowedOrigins = { newSet ->
                    KioskPrefs.setNativeLocationBridgeAllowedOrigins(this@WebViewActivity, newSet)
                    runtimeConfig = runtimeConfig.copy(nativeLocationBridgeAllowedOrigins = newSet)
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
        private const val POPUP_TARGET_TIMEOUT_MS = 10_000L
        private const val MAX_PENDING_POPUPS = 4
    }
}

private data class PopupFilterContext(
    val openerUrl: String,
    val hasGesture: Boolean
)

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
    onPageStartedInActivity: (WebView, String?) -> Unit = { _, _ -> },
    onPageFinishedInActivity: (WebView, String?) -> Unit = { _, _ -> },
    onError: (String) -> Unit,
    existingWebView: WebView? = null,
    runtimeConfig: WebViewRuntimeConfig,
    clearHistoryOnFirstRealPageFinish: Boolean = false,
    onShowFileChooser: (ValueCallback<Array<Uri>>, WebChromeClient.FileChooserParams?) -> Boolean,
    onCreateWindow: (WebView, String, PopupFilterContext?) -> Unit,
    onPendingMainFrameNavigation: ((WebView, String) -> Boolean)? = null,
    onPendingMainFrameCommit: ((WebView, String) -> Unit)? = null,
    isPendingPopupTransport: Boolean = false,
    popupFilterContext: PopupFilterContext? = null
): WebView {
    val webView = existingWebView ?: WebView(ctx)
    val shouldClearInitialHistory = AtomicBoolean(clearHistoryOnFirstRealPageFinish)
    val currentTopUrl = java.util.concurrent.atomic.AtomicReference<String>(targetUrl)

    fun evaluateRegisteredPopupTarget(targetUrl: String): PopupFilterResult? {
        val popupContext = popupFilterContext ?: return null
        if (!WebViewRuntime.isWebUrl(targetUrl)) return null
        val activity = ctx as? WebViewActivity ?: return null
        val latestConfig = activity.latestRuntimeConfig()
        val handle = activity.currentWebViewFilterHandle()
        return PopupFilterGate.evaluate(
            targetUrl = targetUrl,
            openerUrl = popupContext.openerUrl,
            hasGesture = popupContext.hasGesture,
            engine = handle.engine,
            snapshot = handle.snapshot.copy(enabled = latestConfig.limitAdBlock)
        )
    }

    return webView.apply {
        WebViewRuntime.applySettings(this, ctx, targetUrl, runtimeConfig)
        WebViewRuntime.logWebViewDiagnostics(
            ctx,
            "create_secure_webview",
            redactWebUrlForLog(targetUrl),
            runtimeConfig
        )
        logWebViewSurfaceState(this, "created_after_settings")
        if (!isPendingPopupTransport) {
            installNativeLocationBridgeIfNeeded(this, ctx, runtimeConfig)
        }

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
                if (view != null && !url.isNullOrBlank() &&
                    onPendingMainFrameNavigation?.invoke(view, url) == true
                ) {
                    view.stopLoading()
                    return
                }
                if (isPendingPopupTransport) {
                    if (!url.isNullOrBlank()) currentTopUrl.set(url)
                    return
                }
                if (!url.isNullOrBlank()) {
                    currentTopUrl.set(url)
                }
                view?.let { onPageStartedInActivity(it, url) }
                (ctx as? WebViewActivity)?.resetCurrentPageFilterDiagnostics(view, url.orEmpty())
                Log.d("ChildKioskWebView", "Page started: ${redactWebUrlForLog(url)}")
                onProgressUpdate(0)
                view?.setBackgroundColor(android.graphics.Color.parseColor("#FFF8E1"))
                if (view != null && view.progress < 100) {
                    onLoadingStateChanged(true)
                }
                onNavigationStateChanged()
                if (view != null) {
                    (ctx as? WebViewActivity)?.clearNativeLocationBridgeRequests(view)
                    (ctx as? WebViewActivity)?.stopAmapAssistantLocation(view)
                    injectNativeLocationBridgeIfNeeded(view, runtimeConfig)
                    injectPageScripts(view, ctx, runtimeConfig, "PAGE_STARTED")
                    (ctx as? WebViewActivity)?.maybeStartAmapAssistantLocation(view, url)
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (isPendingPopupTransport) return
                Log.d(
                    "ChildKioskWebView",
                    "Page finished: progress=${view?.progress}, canGoBack=${view?.canGoBack()}, " +
                        "url=${redactWebUrlForLog(url)}"
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
                    injectNativeLocationBridgeIfNeeded(view, runtimeConfig)
                    injectPageScripts(view, ctx, runtimeConfig, "PAGE_FINISHED")
                    (ctx as? WebViewActivity)?.maybeStartAmapAssistantLocation(view, url)
                    onPageFinishedInActivity(view, url)
                }

                onLoadingStateChanged(false)
                onNavigationStateChanged()
                if (view != null && !url.isNullOrBlank() && WebViewRuntime.isWebUrl(url)) {
                    onPageCommitted(url, view.title)
                }
            }

            override fun onPageCommitVisible(view: WebView?, url: String?) {
                super.onPageCommitVisible(view, url)
                if (isPendingPopupTransport) {
                    if (view != null && !url.isNullOrBlank()) {
                        onPendingMainFrameCommit?.invoke(view, url)
                    }
                }
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                super.onReceivedError(view, request, error)
                if (isPendingPopupTransport) return
                if (request?.isForMainFrame == true) {
                    Log.w(
                        "ChildKioskWebView",
                        "Main frame error: ${error?.errorCode}, ${error?.description}, " +
                            "url=${redactWebUrlForLog(request.url?.toString())}"
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
                if (isPendingPopupTransport) return
                if (request?.isForMainFrame == true) {
                    val code = errorResponse?.statusCode ?: 200
                    if (code >= 400) {
                        Log.w(
                            "ChildKioskWebView",
                            "Main frame HTTP error: HTTP $code, " +
                                "url=${redactWebUrlForLog(request.url?.toString())}"
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
                if (view != null && request.isForMainFrame &&
                    onPendingMainFrameNavigation?.invoke(view, urlStr) == true
                ) {
                    return true
                }
                if (request.isForMainFrame &&
                    evaluateRegisteredPopupTarget(urlStr)?.shouldBlock == true
                ) {
                    onBlocked(urlStr)
                    return true
                }

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

                val activity = ctx as? WebViewActivity
                val latestConfig = activity?.latestRuntimeConfig() ?: runtimeConfig
                if (latestConfig.limitAdBlock && request.isForMainFrame) {
                    val handle = activity?.currentWebViewFilterHandle()
                    val siteOverride = handle?.let {
                        FilterRepository.siteOverrideFor(it.snapshot, WebViewRuntime.hostOf(currentTopUrl.get()))
                    }
                    val cleanedUrl = handle?.engine?.cleanUrlForNavigation(
                        url = urlStr,
                        topLevelUrl = currentTopUrl.get(),
                        method = request.method.orEmpty(),
                        isMainFrame = true,
                        siteOverride = siteOverride
                    )
                    if (!cleanedUrl.isNullOrBlank() && cleanedUrl != urlStr) {
                        Log.d(
                            "ChildKioskWebView",
                            "Cleaned tracking params: ${redactWebUrlForLog(urlStr)} -> " +
                                redactWebUrlForLog(cleanedUrl)
                        )
                        view?.loadUrl(cleanedUrl)
                        return true
                    }
                }

                if (latestConfig.limitUrlRedirect) {
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
                val activity = ctx as? WebViewActivity
                val latestConfig = activity?.latestRuntimeConfig() ?: runtimeConfig
                if (latestConfig.limitAdBlock) {
                    val topLevelUrl = currentTopUrl.get()
                    val handle = activity?.currentWebViewFilterHandle()
                    val popupDecision = if (request?.isForMainFrame == true) {
                        evaluateRegisteredPopupTarget(request.url.toString())?.decision
                    } else {
                        null
                    }
                    if (popupDecision?.action == FilterAction.BLOCK) {
                        return AdBlocker.emptyResponse(FilterResourceType.DOCUMENT)
                    }
                    val decision = if (handle != null) {
                        AdBlocker.shouldBlock(ctx, request, topLevelUrl, handle)
                    } else {
                        FilterDecision.ALLOW
                    }
                    if (decision.action == FilterAction.BLOCK) {
                        val requestUrl = request?.url?.toString().orEmpty()
                        if (FilterBlockLogLimiter.shouldLog()) {
                            Log.d(
                                "ChildKioskWebView",
                                "Blocked filter request: ${redactWebUrlForLog(requestUrl)}, " +
                                    "source=${decision.rule?.sourceName.orEmpty().take(128)}, " +
                                    "matchType=${decision.rule?.matchType?.name.orEmpty()}"
                            )
                        }
                        val resourceType = FilterResourceType.infer(
                            url = requestUrl,
                            acceptHeader = request?.requestHeaders?.get("Accept"),
                            isMainFrame = request?.isForMainFrame == true,
                            requestHeaders = request?.requestHeaders.orEmpty(),
                            method = request?.method.orEmpty()
                        )
                        activity?.recordCurrentPageNetworkFilterBlock(
                            webView = view,
                            topLevelUrl = topLevelUrl,
                            requestUrl = requestUrl,
                            resourceType = resourceType,
                            decision = decision
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
                if (isPendingPopupTransport) {
                    handler?.cancel()
                    view?.let { pendingView ->
                        Handler(Looper.getMainLooper()).post {
                            destroyWebViewSafely(pendingView)
                        }
                    }
                    return
                }
                if (runtimeConfig.limitSslCheck) {
                    Log.w(
                        "ChildKioskWebView",
                        "SSL error blocked: ${redactWebUrlForLog(error?.url)}"
                    )
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
                if (isPendingPopupTransport) {
                    view?.let(::destroyWebViewSafely)
                    return true
                }
                view?.let {
                    (ctx as? WebViewActivity)?.clearNativeLocationBridgeRequests(it)
                    destroyWebViewSafely(it)
                }
                Toast.makeText(ctx, "网页渲染进程异常退出，正在尝试重构页面", Toast.LENGTH_SHORT).show()
                return true
            }
        }

        webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                super.onProgressChanged(view, newProgress)
                if (isPendingPopupTransport) return
                onProgressUpdate(newProgress)
                onNavigationStateChanged()
                if (newProgress >= 100) {
                    view?.postDelayed({
                        injectNativeLocationBridgeIfNeeded(view, runtimeConfig)
                        injectPageScripts(view, ctx, runtimeConfig, "BOTH")
                        onLoadingStateChanged(false)
                        onNavigationStateChanged()
                    }, 250)
                }
            }

            override fun onPermissionRequest(request: PermissionRequest?) {
                if (request == null) return
                if (isPendingPopupTransport) {
                    request.deny()
                    return
                }
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
                if (isPendingPopupTransport) {
                    callback?.invoke(origin, false, false)
                    return
                }
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
                if (isPendingPopupTransport) {
                    filePathCallback?.onReceiveValue(null)
                    return true
                }
                return filePathCallback?.let { onShowFileChooser(it, fileChooserParams) } ?: false
            }

            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                if (isPendingPopupTransport) {
                    callback?.onCustomViewHidden()
                    return
                }
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
                if (isPendingPopupTransport) return false
                if (resultMsg == null) return false
                val activity = ctx as? WebViewActivity
                val openerUrl = view?.url.orEmpty()
                val shouldGateTarget = activity != null
                val popupResolved = AtomicBoolean(false)
                val pendingNavigationGate = if (shouldGateTarget && activity != null) {
                    popupGate@{ popupWebView: WebView, targetUrl: String ->
                        if (popupResolved.get()) return@popupGate true
                        val latestConfig = activity.latestRuntimeConfig()
                        val handle = activity.currentWebViewFilterHandle()
                        val disposition = PopupFilterGate.evaluateUncommittedNavigation(
                            targetUrl = targetUrl,
                            openerUrl = openerUrl,
                            hasGesture = isUserGesture,
                            engine = handle.engine,
                            snapshot = handle.snapshot.copy(enabled = latestConfig.limitAdBlock)
                        ).disposition
                        when (disposition) {
                            PopupFilterDisposition.WAIT_FOR_TARGET ->
                                !PopupFilterGate.canLoadWhilePending(targetUrl)
                            PopupFilterDisposition.BLOCK -> {
                                if (activity.claimPendingPopup(popupWebView, popupResolved)) {
                                    if (FilterBlockLogLimiter.shouldLog()) {
                                        Log.d(
                                            "ChildKioskFilter",
                                            "Blocked popup targetHost=${WebViewRuntime.hostOf(targetUrl)}, " +
                                                "openerHost=${WebViewRuntime.hostOf(openerUrl)}, gesture=$isUserGesture"
                                        )
                                    }
                                    Handler(Looper.getMainLooper()).post {
                                        destroyWebViewSafely(popupWebView)
                                    }
                                }
                                true
                            }
                            // Uncommitted navigation evaluation deliberately never returns ALLOW.
                            PopupFilterDisposition.ALLOW -> false
                        }
                    }
                } else {
                    null
                }
                val pendingCommitGate = if (pendingNavigationGate != null && activity != null) {
                    popupCommit@{ popupWebView: WebView, targetUrl: String ->
                        if (popupResolved.get()) return@popupCommit
                        val latestConfig = activity.latestRuntimeConfig()
                        val handle = activity.currentWebViewFilterHandle()
                        val disposition = PopupFilterGate.evaluate(
                            targetUrl = targetUrl,
                            openerUrl = openerUrl,
                            hasGesture = isUserGesture,
                            engine = handle.engine,
                            snapshot = handle.snapshot.copy(enabled = latestConfig.limitAdBlock)
                        ).disposition
                        when (disposition) {
                            PopupFilterDisposition.WAIT_FOR_TARGET -> Unit
                            PopupFilterDisposition.BLOCK -> {
                                if (activity.claimPendingPopup(popupWebView, popupResolved)) {
                                    Handler(Looper.getMainLooper()).post {
                                        destroyWebViewSafely(popupWebView)
                                    }
                                }
                            }
                            PopupFilterDisposition.ALLOW -> {
                                if (activity.claimPendingPopup(popupWebView, popupResolved)) {
                                    onCreateWindow(
                                        popupWebView,
                                        targetUrl,
                                        PopupFilterContext(openerUrl, isUserGesture)
                                    )
                                    // Reload after the normal secured client and page injections are installed.
                                    activity.scheduleRegisteredPopupReload(popupWebView, targetUrl)
                                }
                            }
                        }
                    }
                } else {
                    null
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
                    onPageStartedInActivity = onPageStartedInActivity,
                    onPageFinishedInActivity = onPageFinishedInActivity,
                    onError = onError,
                    runtimeConfig = runtimeConfig,
                    onShowFileChooser = onShowFileChooser,
                    onCreateWindow = onCreateWindow,
                    onPendingMainFrameNavigation = pendingNavigationGate,
                    onPendingMainFrameCommit = pendingCommitGate,
                    isPendingPopupTransport = pendingNavigationGate != null
                )
                if (pendingNavigationGate != null && activity != null) {
                    if (!activity.registerPendingPopup(newWebView, popupResolved)) {
                        Log.w("ChildKioskFilter", "Blocked popup: pending target limit reached")
                        destroyWebViewSafely(newWebView)
                        return false
                    }
                } else {
                    onCreateWindow(newWebView, "", null)
                }
                val transport = resultMsg.obj as WebView.WebViewTransport
                transport.webView = newWebView
                resultMsg.sendToTarget()
                return true
            }
        }

        setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            if (isPendingPopupTransport) return@setDownloadListener
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
                "Initial load after layout: ${webView.width}x${webView.height}, " +
                    "url=${redactWebUrlForLog(targetUrl)}"
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
            "context=$contextName, progress=${webView.progress}, " +
            "url=${redactWebUrlForLog(webView.url)}"
    )
}

private fun clearInitialBlankHistory(webView: WebView, currentUrl: String) {
    if (!WebViewRuntime.isWebUrl(currentUrl)) return
    webView.clearHistory()
    Log.d(
        "ChildKioskWebView",
        "Cleared initial blank history for warm WebView: ${redactWebUrlForLog(currentUrl)}"
    )
}

private fun redactWebUrlForLog(value: String?): String {
    if (value.isNullOrBlank()) return "-"
    return runCatching {
        val uri = Uri.parse(value)
        val scheme = uri.scheme?.lowercase(Locale.US)
        val host = uri.host.orEmpty().lowercase(Locale.US)
        if ((scheme != "http" && scheme != "https") || host.isBlank()) {
            return@runCatching "${scheme ?: "unknown"}:<redacted>"
        }
        buildString {
            append(scheme)
            append("://")
            append(host)
            if (uri.port >= 0) append(":${uri.port}")
            append(uri.encodedPath.orEmpty().take(MAX_LOG_URL_PATH_CHARS))
            if (!uri.encodedQuery.isNullOrBlank()) append("?<redacted>")
            if (!uri.encodedFragment.isNullOrBlank()) append("#<redacted>")
        }
    }.getOrElse {
        value.substringBefore('?').substringBefore('#').take(MAX_LOG_URL_PATH_CHARS)
    }
}

private const val MAX_LOG_URL_PATH_CHARS = 256

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

private fun installNativeLocationBridgeIfNeeded(
    webView: WebView,
    context: Context,
    config: WebViewRuntimeConfig
) {
    if (!config.nativeLocationOptimizationEnabled || !config.nativeLocationBridgeEnabled || config.limitGeolocation) {
        runCatching { WebViewCompat.removeWebMessageListener(webView, "ChildKioskNativeLocation") }
        removeNativeLocationDocumentScript(webView)
        return
    }
    val activity = context as? WebViewActivity ?: return
    if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
        Log.w("ChildKioskLocation", "Native location bridge disabled: WebMessage listener unsupported")
        return
    }
    runCatching {
        WebViewCompat.removeWebMessageListener(webView, "ChildKioskNativeLocation")
        WebViewCompat.addWebMessageListener(
            webView,
            "ChildKioskNativeLocation",
            setOf("*")
        ) { sourceWebView, message, sourceOrigin, isMainFrame, _ ->
            activity.handleNativeLocationBridgeMessage(
                webView = sourceWebView,
                rawMessage = message.data,
                sourceOrigin = sourceOrigin,
                isMainFrame = isMainFrame
            )
        }
    }.onFailure { e ->
        Log.w("ChildKioskLocation", "Install native location bridge failed", e)
        return
    }
    installNativeLocationDocumentScriptIfSupported(webView)
}

private fun injectNativeLocationBridgeIfNeeded(
    webView: WebView,
    config: WebViewRuntimeConfig
) {
    if (!config.nativeLocationOptimizationEnabled || !config.nativeLocationBridgeEnabled || config.limitGeolocation) {
        return
    }
    val pageUrl = webView.url.orEmpty()
    if (!WebViewRuntime.isWebUrl(pageUrl)) return
    webView.evaluateJavascript(nativeLocationBridgeScript(), null)
}

private fun installNativeLocationDocumentScriptIfSupported(webView: WebView) {
    if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return
    if (nativeLocationDocumentScripts.containsKey(webView)) return
    runCatching {
        WebViewCompat.addDocumentStartJavaScript(
            webView,
            nativeLocationBridgeScript(),
            setOf("*")
        )
    }.onSuccess { handler ->
        nativeLocationDocumentScripts[webView] = handler
    }.onFailure { e ->
        Log.w("ChildKioskLocation", "Install document-start native location script failed", e)
    }
}

private fun removeNativeLocationDocumentScript(webView: WebView) {
    nativeLocationDocumentScripts.remove(webView)?.let { handler ->
        runCatching { handler.remove() }
    }
}

private fun nativeLocationBridgeScript(): String {
    val js = """
        (function() {
            if (window.__ChildKioskNativeLocation && window.__ChildKioskNativeLocation.installed) return 'already';
            var bridge = window.ChildKioskNativeLocation;
            if (!bridge || !bridge.postMessage) return 'missing_bridge';
            var original = navigator.geolocation || {};
            var callbacks = {};
            var watches = {};
            var seq = 1;

            function post(type, payload) {
                payload = payload || {};
                payload.type = type;
                bridge.postMessage(JSON.stringify(payload));
            }

            function normalizeOptions(options) {
                options = options || {};
                var timeout = Number(options.timeout);
                var maximumAge = Number(options.maximumAge);
                return {
                    timeout: isFinite(timeout) && timeout > 0 ? Math.floor(timeout) : null,
                    maximumAge: isFinite(maximumAge) && maximumAge >= 0 ? Math.floor(maximumAge) : null
                };
            }

            function makePosition(data) {
                var coords = data.coords || {};
                return {
                    coords: {
                        latitude: Number(coords.latitude),
                        longitude: Number(coords.longitude),
                        accuracy: Number(coords.accuracy || 0),
                        altitude: coords.altitude === null || typeof coords.altitude === 'undefined' ? null : Number(coords.altitude),
                        altitudeAccuracy: coords.altitudeAccuracy === null || typeof coords.altitudeAccuracy === 'undefined' ? null : Number(coords.altitudeAccuracy),
                        heading: coords.heading === null || typeof coords.heading === 'undefined' ? null : Number(coords.heading),
                        speed: coords.speed === null || typeof coords.speed === 'undefined' ? null : Number(coords.speed)
                    },
                    timestamp: Number(data.timestamp || Date.now())
                };
            }

            function makeError(data) {
                var err = {
                    code: Number(data.errorCode || 2),
                    message: String(data.message || '定位失败')
                };
                err.PERMISSION_DENIED = 1;
                err.POSITION_UNAVAILABLE = 2;
                err.TIMEOUT = 3;
                return err;
            }

            window.__ChildKioskNativeLocation = {
                installed: true,
                dispatch: function(payload) {
                    try {
                        var data = typeof payload === 'string' ? JSON.parse(payload) : payload;
                        if (!data || !data.id) return;
                        var store = data.watch ? watches : callbacks;
                        var entry = store[data.id];
                        if (!entry) return;
                        if (!data.watch) delete store[data.id];
                        if (data.success) {
                            entry.success(makePosition(data));
                        } else {
                            entry.error(makeError(data));
                        }
                    } catch (e) {
                        if (window.console && console.warn) console.warn('Native location dispatch failed', e);
                    }
                },
                fallback: original
            };

            var geolocation = {
                getCurrentPosition: function(success, error, options) {
                    var id = 'g' + (seq++);
                    callbacks[id] = {
                        success: typeof success === 'function' ? success : function() {},
                        error: typeof error === 'function' ? error : function() {}
                    };
                    var opts = normalizeOptions(options);
                    post('getCurrentPosition', {
                        id: id,
                        origin: String(location.origin || ''),
                        timeout: opts.timeout,
                        maximumAge: opts.maximumAge
                    });
                },
                watchPosition: function(success, error, options) {
                    var id = 'w' + (seq++);
                    watches[id] = {
                        success: typeof success === 'function' ? success : function() {},
                        error: typeof error === 'function' ? error : function() {}
                    };
                    post('watchPosition', {
                        id: id,
                        origin: String(location.origin || '')
                    });
                    return id;
                },
                clearWatch: function(id) {
                    id = String(id || '');
                    if (!id) return;
                    delete watches[id];
                    post('clearWatch', {
                        id: id,
                        origin: String(location.origin || '')
                    });
                }
            };
            try {
                Object.defineProperty(navigator, 'geolocation', {
                    configurable: true,
                    enumerable: true,
                    get: function() { return geolocation; }
                });
            } catch (defineError) {
                try {
                    if (navigator.geolocation) {
                        navigator.geolocation.getCurrentPosition = geolocation.getCurrentPosition;
                        navigator.geolocation.watchPosition = geolocation.watchPosition;
                        navigator.geolocation.clearWatch = geolocation.clearWatch;
                    } else {
                        navigator.geolocation = geolocation;
                    }
                } catch (patchError) {
                    if (window.console && console.warn) console.warn('Native location patch failed', patchError);
                    return 'patch_failed';
                }
            }
            return 'installed';
        })();
    """.trimIndent()
    return js
}

private data class NativeLocationBridgeRequest(
    val type: String,
    val id: String,
    val origin: String,
    val timeoutMs: Long?,
    val maximumAgeMs: Long?
)

private fun JSONObject.optLongOrNull(name: String): Long? {
    if (!has(name) || isNull(name)) return null
    val value = optLong(name, Long.MIN_VALUE)
    return value.takeIf { it != Long.MIN_VALUE }
}

private fun injectCosmeticCssIfNeeded(
    webView: WebView,
    config: WebViewRuntimeConfig
) {
    val activity = webView.context as? WebViewActivity
    val latestConfig = activity?.latestRuntimeConfig() ?: config
    if (!latestConfig.limitAdBlock) return
    val handle = activity?.currentWebViewFilterHandle() ?: return
    if (!handle.snapshot.enabled || handle.snapshot.preset == "LIGHT") return
    val pageUrl = webView.url ?: return
    val host = WebViewRuntime.hostOf(pageUrl)
    if (host.isBlank()) return
    val siteOverride = FilterRepository.siteOverrideFor(handle.snapshot, host)
    val engine = handle.engine
    val cosmeticMatches = engine.cosmeticMatchesFor(host, siteOverride)
    activity.recordCurrentPageCosmeticFilterCandidates(
        webView = webView,
        pageUrl = pageUrl,
        candidateCount = cosmeticMatches.size
    )
    FilterRepository.maybeRecordPerfSnapshot(webView.context, handle.snapshot, engine)
    val effectiveMatches = WebViewFilterInjector.selectMatchesWithinBudget(cosmeticMatches)
    WebViewFilterInjector.inject(webView, effectiveMatches) { rawResult ->
        if (webView.url != pageUrl) return@inject
        val hitMatches = parseCosmeticHitIndexes(rawResult)
            .mapNotNull { index -> effectiveMatches.getOrNull(index) }
            .take(COSMETIC_HIT_TEST_LIMIT)
        activity.recordCurrentPageCosmeticFilterHits(webView, pageUrl, hitMatches)
    }
}

private fun parseCosmeticHitIndexes(rawResult: String?): List<Int> {
    if (rawResult.isNullOrBlank() || rawResult == "null") return emptyList()
    val jsonText = runCatching {
        org.json.JSONTokener(rawResult).nextValue() as? String
    }.getOrNull() ?: rawResult
    return runCatching {
        val array = org.json.JSONArray(jsonText)
        buildList {
            for (i in 0 until array.length()) {
                val index = array.optInt(i, -1)
                if (index >= 0) add(index)
            }
        }
    }.getOrDefault(emptyList())
}

private fun injectFilterScriptletsIfNeeded(
    webView: WebView,
    config: WebViewRuntimeConfig
) {
    val activity = webView.context as? WebViewActivity
    val latestConfig = activity?.latestRuntimeConfig() ?: config
    if (!latestConfig.limitAdBlock) return
    val handle = activity?.currentWebViewFilterHandle() ?: return
    if (!handle.snapshot.enabled || handle.snapshot.preset == "LIGHT") return
    val pageUrl = webView.url ?: return
    val host = WebViewRuntime.hostOf(pageUrl)
    if (host.isBlank()) return
    val siteOverride = FilterRepository.siteOverrideFor(handle.snapshot, host)
    val engine = handle.engine
    val scriptlets = engine.scriptletJsFor(host, siteOverride)
    FilterRepository.maybeRecordPerfSnapshot(webView.context, handle.snapshot, engine)
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

private fun destroyWebViewSafely(webView: WebView) {
    // Renderer-gone WebViews can throw from any method. Keep teardown granular so an early
    // failure never prevents detaching and destroying the unusable view.
    runCatching { (webView.context as? WebViewActivity)?.cancelPendingPopup(webView) }
    runCatching { (webView.context as? WebViewActivity)?.stopAmapAssistantLocation(webView) }
    runCatching { webView.stopLoading() }
    runCatching { webView.webChromeClient = null }
    runCatching { webView.webViewClient = WebViewClient() }
    runCatching { webView.removeJavascriptInterface("ChildKioskDebugBridge") }
    runCatching { webView.removeJavascriptInterface("ChildKioskNativeLocationBridge") }
    runCatching { WebViewCompat.removeWebMessageListener(webView, "ChildKioskNativeLocation") }
    runCatching { removeNativeLocationDocumentScript(webView) }
    runCatching { webView.loadUrl("about:blank") }
    runCatching { webView.clearHistory() }
    runCatching { (webView.parent as? ViewGroup)?.removeView(webView) }
    runCatching { webView.removeAllViews() }
    runCatching { webView.destroy() }
}

private fun parseStringArrayJavascriptResult(rawResult: String?): List<String> {
    if (rawResult.isNullOrBlank() || rawResult == "null") return emptyList()
    val jsonText = runCatching {
        org.json.JSONTokener(rawResult).nextValue() as? String
    }.getOrNull() ?: rawResult
    return runCatching {
        val array = org.json.JSONArray(jsonText)
        buildList {
            for (i in 0 until array.length()) {
                val value = array.optString(i, "").trim().lowercase(Locale.US)
                if (value.isNotBlank()) add(value)
            }
        }
    }.getOrDefault(emptyList())
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
private fun CurrentPageFilterDiagnosticsDialog(
    snapshot: CurrentPageFilterSnapshot,
    onDismiss: () -> Unit
) {
    val host = WebViewRuntime.hostOf(snapshot.pageUrl).ifBlank { "当前页面" }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.5f))
            .clickable(enabled = true, onClick = onDismiss),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .heightIn(max = 560.dp)
                .padding(12.dp)
                .clickable(enabled = false, onClick = {}),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(18.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = null,
                        tint = if (snapshot.totalCount > 0) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.size(26.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "当前页过滤记录",
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = host,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "关闭")
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterCountChip(
                        label = "网络",
                        count = snapshot.networkCount,
                        modifier = Modifier.weight(1f)
                    )
                    FilterCountChip(
                        label = "元素命中",
                        count = snapshot.cosmeticCount,
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))
                Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.14f))
                Spacer(modifier = Modifier.height(12.dp))
                if (
                    snapshot.networkCount > snapshot.networkEvents.size ||
                    snapshot.cosmeticCount > snapshot.cosmeticEvents.size
                ) {
                    Text(
                        text = "列表显示当前页面最近 ${snapshot.networkEvents.size} 条网络拦截和前 ${snapshot.cosmeticEvents.size} 条元素隐藏命中。",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 16.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (snapshot.totalCount == 0) {
                        val message = if (snapshot.cosmeticCandidateCount > 0) {
                            "当前页面暂无网络拦截，元素隐藏候选 ${snapshot.cosmeticCandidateCount} 条，但轻量探测未发现实际命中的页面元素。"
                        } else {
                            "当前页面暂无过滤拦截记录。"
                        }
                        Text(
                            text = message,
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                                    RoundedCornerShape(10.dp)
                                )
                                .padding(12.dp)
                        )
                    }

                    if (snapshot.networkEvents.isNotEmpty()) {
                        FilterSectionTitle("网络请求拦截")
                        snapshot.networkEvents.forEach { event ->
                            NetworkFilterEventRow(event)
                        }
                    }

                    if (snapshot.cosmeticEvents.isNotEmpty()) {
                        FilterSectionTitle("元素隐藏命中")
                        Text(
                            text = "以下为本页实际隐藏到 DOM 元素的选择器，最多显示前 $COSMETIC_HIT_TEST_LIMIT 条命中规则。",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = 16.sp
                        )
                        snapshot.cosmeticEvents.forEach { event ->
                            CosmeticFilterEventRow(event)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("关闭")
                }
            }
        }
    }
}

@Composable
private fun FilterCountChip(label: String, count: Int, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f), RoundedCornerShape(10.dp))
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.12f), RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Text(
            text = label,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = count.toString(),
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
private fun FilterSectionTitle(title: String) {
    Text(
        text = title,
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface
    )
}

@Composable
private fun NetworkFilterEventRow(event: CurrentPageNetworkFilterEvent) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f), RoundedCornerShape(10.dp))
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.1f), RoundedCornerShape(10.dp))
            .padding(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = event.resourceType.ifBlank { "other" },
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 6.dp, vertical = 3.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = formatFilterEventTime(event.timestamp),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = event.requestUrl,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurface,
            lineHeight = 17.sp,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis
        )
        FilterEventMetaLine("规则", event.ruleText.ifBlank { "未知规则" })
        if (event.sourceName.isNotBlank()) {
            FilterEventMetaLine("来源", event.sourceName)
        }
        if (event.reason.isNotBlank()) {
            FilterEventMetaLine("原因", event.reason)
        }
        val diagnostics = buildList {
            if (event.matchType.isNotBlank()) add("匹配: ${event.matchType}")
            if (event.cacheStatus.isNotBlank()) add("缓存: ${event.cacheStatus}")
            if (event.candidateCount > 0) add("候选: ${event.candidateCount}")
        }.joinToString(" | ")
        if (diagnostics.isNotBlank()) {
            FilterEventMetaLine("诊断", diagnostics)
        }
    }
}

@Composable
private fun CosmeticFilterEventRow(event: CurrentPageCosmeticFilterEvent) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.1f), RoundedCornerShape(10.dp))
            .padding(10.dp)
    ) {
        Text(
            text = event.selector,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            lineHeight = 17.sp,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis
        )
        FilterEventMetaLine("规则", event.ruleText.ifBlank { event.selector })
        if (event.sourceName.isNotBlank()) {
            FilterEventMetaLine("来源", event.sourceName)
        }
    }
}

@Composable
private fun FilterEventMetaLine(label: String, value: String) {
    Spacer(modifier = Modifier.height(4.dp))
    Text(
        text = "$label：$value",
        fontSize = 11.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        lineHeight = 15.sp,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis
    )
}

private fun formatFilterEventTime(timestamp: Long): String {
    if (timestamp <= 0L) return ""
    return runCatching {
        SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(timestamp))
    }.getOrDefault("")
}

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
    initialNativeLocationBridgeEnabled: Boolean,
    initialGeoBlacklist: Set<String>,
    initialNativeLocationAllowedOrigins: Set<String>,
    initialCameraBlacklist: Set<String>,
    initialMicrophoneBlacklist: Set<String>,
    initialFileChooserBlacklist: Set<String>,
    initialSchemeBlacklist: Set<String>,
    onDismiss: () -> Unit,
    onClearData: () -> Unit,
    onUpdateGeoBlacklist: (Set<String>) -> Unit,
    onUpdateNativeLocationAllowedOrigins: (Set<String>) -> Unit,
    onUpdateCameraBlacklist: (Set<String>) -> Unit,
    onUpdateMicrophoneBlacklist: (Set<String>) -> Unit,
    onUpdateFileChooserBlacklist: (Set<String>) -> Unit,
    onUpdateSchemeBlacklist: (Set<String>) -> Unit
) {
    var geoBlacklist by remember { mutableStateOf(initialGeoBlacklist) }
    var nativeLocationAllowedOrigins by remember { mutableStateOf(initialNativeLocationAllowedOrigins) }
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

                SiteAllowlistSwitchRow(
                    title = "原生定位托管",
                    allowedText = "允许本站使用 LocationManager 托管定位",
                    blockedText = "未加入托管允许列表",
                    globallyBlockedText = "原生定位托管未在后台启用",
                    origin = permissionOrigin,
                    isGloballyBlocked = !initialNativeLocationBridgeEnabled || initialLimitGeolocation,
                    allowlist = nativeLocationAllowedOrigins,
                    onUpdateAllowlist = {
                        nativeLocationAllowedOrigins = it
                        onUpdateNativeLocationAllowedOrigins(it)
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
            text = globallyBlockedText,
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

@Composable
private fun SiteAllowlistSwitchRow(
    title: String,
    allowedText: String,
    blockedText: String,
    globallyBlockedText: String,
    origin: String,
    isGloballyBlocked: Boolean,
    allowlist: Set<String>,
    onUpdateAllowlist: (Set<String>) -> Unit
) {
    val normalizedOrigin = KioskPrefs.normalizeOriginKey(origin)
    val isAllowed = normalizedOrigin.isNotBlank() && allowlist.contains(normalizedOrigin)

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
            text = if (isAllowed) allowedText else blockedText,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Switch(
            checked = isAllowed,
            enabled = normalizedOrigin.isNotBlank(),
            onCheckedChange = { checked ->
                val newSet = allowlist.toMutableSet()
                if (checked) {
                    newSet.add(normalizedOrigin)
                } else {
                    newSet.remove(normalizedOrigin)
                }
                onUpdateAllowlist(newSet)
            }
        )
    }
}

private data class QuadrupleInfo<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)
