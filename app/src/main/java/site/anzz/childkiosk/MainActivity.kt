package site.anzz.childkiosk

import android.app.ActivityManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.UserManager
import android.provider.Settings
import android.content.res.Configuration
import android.util.Log
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import site.anzz.childkiosk.data.AppDatabase
import site.anzz.childkiosk.data.WebAppEntity
import site.anzz.childkiosk.ui.AddEditWebAppDialog
import site.anzz.childkiosk.ui.AdminConsoleScreen
import site.anzz.childkiosk.ui.KioskMainScreen
import site.anzz.childkiosk.ui.theme.ChildKioskTheme
import site.anzz.childkiosk.util.KioskPrefs
import site.anzz.childkiosk.util.SystemUiHelper
import site.anzz.childkiosk.util.WebAppIconCache
import site.anzz.childkiosk.util.WebViewPool
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import site.anzz.childkiosk.ui.browser.FloatingBrowserControlsOverlay
import site.anzz.childkiosk.ui.browser.FloatingBrowserControlsCallbacks
import site.anzz.childkiosk.ui.browser.FloatingBrowserControlsState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch


class MainActivity : ComponentActivity() {

    private lateinit var dpm: DevicePolicyManager
    private lateinit var adminComponent: ComponentName
    private var isSoftLockDeferred = false
    private var filterEventReceiver: site.anzz.childkiosk.util.filter.FilterEventReceiver? = null
    private var pendingEditRequest by mutableStateOf<PendingWebAppEdit?>(null)
    private var currentScreen by mutableStateOf(SCREEN_MAIN)

    private data class PendingWebAppEdit(
        val app: WebAppEntity?,
        val initialTitle: String = "",
        val initialUrl: String = "",
        val initialCategory: String = WebAppEntity.CATEGORY_OTHER
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        // 0. 早期屏幕方向设置，避免启动闪烁
        applyRequestedOrientation()

        super.onCreate(savedInstanceState)

        dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        adminComponent = ComponentName(this, MyDeviceAdminReceiver::class.java)
        currentScreen = savedInstanceState
            ?.getString(STATE_CURRENT_SCREEN)
            ?.takeIf { it == SCREEN_MAIN || it == SCREEN_ADMIN }
            ?: SCREEN_MAIN
        consumeSafeHomeIntent(intent)

        // 1. 设置 FLAG_SECURE 防截屏逃逸 (根据配置)
        if (KioskPrefs.isLimitFlagSecureEnabled(this)) {
            window.setFlags(
                WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE
            )
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        // 2. 系统栏策略：正常模式像普通应用一样显示系统栏；锁定态保持沉浸或受控系统信息。
        applySystemUiMode()

        // 3. 监听 System UI / Window 边距变化，防止状态栏灰色半透明条卡死，3秒自动收回
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.decorView.setOnApplyWindowInsetsListener { view, insets ->
                val shouldReapply = when {
                    shouldUseNormalSystemBars() -> {
                        !shouldShowNormalStatusBar() &&
                            insets.isVisible(android.view.WindowInsets.Type.statusBars())
                    }
                    shouldShowSecureSystemInfo() ->
                        insets.isVisible(android.view.WindowInsets.Type.navigationBars())
                    else ->
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

        val db = AppDatabase.getInstance(this)

        setContent {
            ChildKioskTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val systemConfig by db.systemConfigDao()
                        .getSystemConfigFlow()
                        .collectAsState(initial = null)
                    val scope = rememberCoroutineScope()

                    val iconSizeMode = remember(currentScreen) {
                        KioskPrefs.getIconSizeMode(this@MainActivity)
                    }
                    val wallpaperPreset = remember(currentScreen) {
                        KioskPrefs.getWallpaperPreset(this@MainActivity)
                    }

                    when (currentScreen) {
                        SCREEN_MAIN -> {
                            val context = LocalContext.current
                            val floatingEnabled = remember(currentScreen) {
                                KioskPrefs.isFloatingBrowserControlsEnabled(context)
                            }
                            Box(modifier = Modifier.fillMaxSize()) {
                                KioskMainScreen(
                                    config = systemConfig,
                                    iconSizeMode = iconSizeMode,
                                    wallpaperPreset = wallpaperPreset,
                                    normalSystemBars = isNormalSystemUiMode(),
                                    allowWebAppEdit = isNormalSystemUiMode(),
                                    onEditWebApp = { app ->
                                        pendingEditRequest = PendingWebAppEdit(app = app)
                                    },
                                    onEnterAdmin = { currentScreen = SCREEN_ADMIN },
                                    onExitKiosk = { stopLockTaskMode() }
                                )
                                if (floatingEnabled) {
                                    AndroidView(
                                        factory = { ctx ->
                                            FloatingBrowserControlsOverlay(ctx).apply {
                                                setCallbacks(FloatingBrowserControlsCallbacks(
                                                    onNavigateToUrl = { url ->
                                                        val intent = WebViewActivityLauncher.createIntent(ctx).apply {
                                                            putExtra(WebViewActivity.EXTRA_CUSTOM_URL, url)
                                                            putExtra(WebViewActivity.EXTRA_ALLOW_HIGH_PERFORMANCE_RESOURCE_RESTART, true)
                                                            val orientationMode = KioskPrefs.getOrientationMode(ctx)
                                                            putExtra(WebViewActivity.EXTRA_ORIENTATION_MODE, orientationMode)
                                                            KioskPrefs.putWebViewRuntimeConfig(this, ctx)
                                                        }
                                                        ctx.startActivity(intent)
                                                    },
                                                    onNewTab = {
                                                        val intent = WebViewActivityLauncher.createIntent(ctx).apply {
                                                            putExtra(WebViewActivity.EXTRA_CUSTOM_URL, "about:blank")
                                                            putExtra(WebViewActivity.EXTRA_ALLOW_HIGH_PERFORMANCE_RESOURCE_RESTART, true)
                                                            val orientationMode = KioskPrefs.getOrientationMode(ctx)
                                                            putExtra(WebViewActivity.EXTRA_ORIENTATION_MODE, orientationMode)
                                                            KioskPrefs.putWebViewRuntimeConfig(this, ctx)
                                                        }
                                                        ctx.startActivity(intent)
                                                    },
                                                    onOpenWebApp = { webApp ->
                                                        val intent = WebViewActivityLauncher.createIntent(ctx).apply {
                                                            putExtra(WebViewActivity.EXTRA_WEB_APP_ID, webApp.id)
                                                            putExtra(WebViewActivity.EXTRA_ALLOW_HIGH_PERFORMANCE_RESOURCE_RESTART, true)
                                                            val orientationMode = KioskPrefs.getOrientationMode(ctx)
                                                            putExtra(WebViewActivity.EXTRA_ORIENTATION_MODE, orientationMode)
                                                            KioskPrefs.putWebViewRuntimeConfig(this, ctx)
                                                        }
                                                        ctx.startActivity(intent)
                                                    },
                                                    onSwitchTab = { tabId ->
                                                        val intent = WebViewActivityLauncher.createIntent(ctx).apply {
                                                            putExtra(WebViewActivity.EXTRA_SWITCH_TAB_ID, tabId)
                                                            putExtra(WebViewActivity.EXTRA_ALLOW_HIGH_PERFORMANCE_RESOURCE_RESTART, true)
                                                            val orientationMode = KioskPrefs.getOrientationMode(ctx)
                                                            putExtra(WebViewActivity.EXTRA_ORIENTATION_MODE, orientationMode)
                                                            KioskPrefs.putWebViewRuntimeConfig(this, ctx)
                                                        }
                                                        ctx.startActivity(intent)
                                                    },
                                                    onCloseTab = { tabId ->
                                                        val intent = WebViewActivityLauncher.createIntent(ctx).apply {
                                                            putExtra(WebViewActivity.EXTRA_CLOSE_TAB_ID, tabId)
                                                            putExtra(WebViewActivity.EXTRA_ALLOW_HIGH_PERFORMANCE_RESOURCE_RESTART, false)
                                                            val orientationMode = KioskPrefs.getOrientationMode(ctx)
                                                            putExtra(WebViewActivity.EXTRA_ORIENTATION_MODE, orientationMode)
                                                            KioskPrefs.putWebViewRuntimeConfig(this, ctx)
                                                        }
                                                        ctx.startActivity(intent)
                                                    }
                                                ))
                                                updateState(FloatingBrowserControlsState(isHomeScreen = true))
                                            }
                                        },
                                        modifier = Modifier.fillMaxSize()
                                    )
                                }
                            }
                        }
                        SCREEN_ADMIN -> AdminConsoleScreen(
                            config = systemConfig,
                            isDeviceOwner = dpm.isDeviceOwnerApp(packageName),
                            onBack = { currentScreen = SCREEN_MAIN },
                            onExitKiosk = { stopLockTaskMode() },
                            onGoToHomeSettings = { openHomeSettings() },
                            onProtectionModeChanged = { mode ->
                                if (!dpm.isDeviceOwnerApp(packageName)) {
                                    if (mode == KioskPrefs.MODE_SOFT_LOCK) {
                                        isSoftLockDeferred = false
                                        triggerKioskIfNeeded()
                                    } else {
                                        isSoftLockDeferred = true
                                        runCatching { stopLockTask() }
                                        applySystemUiMode()
                                    }
                                }
                            },
                            onSandboxLimitsChanged = {
                                applySandboxLimits()
                            },
                            normalSystemBars = isNormalSystemUiMode()
                        )
                    }

                    pendingEditRequest?.let { request ->
                        AddEditWebAppDialog(
                            app = request.app,
                            initialTitle = request.initialTitle,
                            initialUrl = request.initialUrl,
                            initialCategory = request.initialCategory,
                            onDismiss = {
                                pendingEditRequest = null
                            },
                            onSave = { title, url, icon, category ->
                                val existingApp = request.app
                                scope.launch(Dispatchers.IO) {
                                    val frozenIcon = WebAppIconCache.freezeNetworkIcon(this@MainActivity, icon, url)
                                    if (existingApp == null) {
                                        val existing = db.webAppDao().getAllWebApps().firstOrNull { app ->
                                            normalizeWebUrlForCompare(app.url) == normalizeWebUrlForCompare(url)
                                        }
                                        if (existing == null) {
                                            db.webAppDao().insertWebApp(
                                                WebAppEntity(
                                                    title = title,
                                                    url = url,
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
                                                    title = title,
                                                    url = url,
                                                    iconPath = frozenIcon,
                                                    category = category,
                                                    isEnabled = true
                                                )
                                            )
                                        }
                                    } else {
                                        db.webAppDao().updateWebApp(
                                            existingApp.copy(title = title, url = url, iconPath = frozenIcon, category = category)
                                        )
                                    }
                                }
                                Toast.makeText(
                                    this@MainActivity,
                                    if (existingApp == null) "已添加到应用白名单" else "应用已更新",
                                    Toast.LENGTH_SHORT
                                ).show()
                                pendingEditRequest = null
                            }
                        )
                    }
                }
            }
        }

        // 注册多进程过滤日志广播接收器
        val receiver = site.anzz.childkiosk.util.filter.FilterEventReceiver()
        filterEventReceiver = receiver
        val filter = android.content.IntentFilter("site.anzz.childkiosk.action.RECORD_FILTER_EVENT")
        ContextCompat.registerReceiver(
            this,
            receiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onResume() {
        super.onResume()
        consumeSafeHomeIntent(intent)
        consumePendingWebAppEditIntent(intent)
        applyRequestedOrientation()
        applySystemUiMode()
        WebViewPool.warmupBlank()
        triggerKioskIfNeeded()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        logLifecycleIntent("onNewIntent", intent)
        consumeSafeHomeIntent(intent)
        consumePendingWebAppEditIntent(intent)
    }

    private fun logLifecycleIntent(event: String, intent: Intent?) {
        Log.i(
            TAG,
            "$event taskId=$taskId flags=0x${intent?.flags?.toUInt()?.toString(16) ?: "0"} " +
                "isTaskRoot=$isTaskRoot isFinishing=$isFinishing"
        )
    }

    private fun consumeSafeHomeIntent(intent: Intent?) {
        intent ?: return
        if (intent.getBooleanExtra(EXTRA_FORCE_SAFE_HOME, false)) {
            currentScreen = SCREEN_MAIN
            pendingEditRequest = null
            intent.removeExtra(EXTRA_FORCE_SAFE_HOME)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(STATE_CURRENT_SCREEN, currentScreen)
        super.onSaveInstanceState(outState)
    }

    private fun consumePendingWebAppEditIntent(intent: Intent?) {
        intent ?: return
        if (intent.getBooleanExtra(EXTRA_EDIT_WEB_APP_REQUEST, false)) {
            val title = intent.getStringExtra(EXTRA_EDIT_WEB_APP_TITLE).orEmpty()
            val url = intent.getStringExtra(EXTRA_EDIT_WEB_APP_URL).orEmpty()
            if (url.isNotBlank()) {
                pendingEditRequest = PendingWebAppEdit(
                    app = null,
                    initialTitle = title.ifBlank { "收藏网站" },
                    initialUrl = url,
                    initialCategory = WebAppEntity.CATEGORY_OTHER
                )
            }
            intent.removeExtra(EXTRA_EDIT_WEB_APP_REQUEST)
            intent.removeExtra(EXTRA_EDIT_WEB_APP_TITLE)
            intent.removeExtra(EXTRA_EDIT_WEB_APP_URL)
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applySystemUiMode()
    }

    private fun applyRequestedOrientation() {
        requestedOrientation = KioskPrefs.getRequestedOrientation(this)
    }

    private fun triggerKioskIfNeeded() {
        when {
            // Tier 1：Device Owner，企业级完全锁定
            dpm.isDeviceOwnerApp(packageName) -> setupAndStartKiosk()
            // Tier 2：无 Device Owner，按配置进入屏幕固定软锁
            KioskPrefs.getProtectionMode(this) == KioskPrefs.MODE_SOFT_LOCK -> {
                if (!isSoftLockDeferred && !isInLockTaskMode()) {
                    startSoftLock()
                }
            }
            // Tier 3：正常系统栏，无系统级锁定
            else -> { /* 仅依赖自定义 Launcher + 认证退出 */ }
        }
    }

    private fun isInLockTaskMode(): Boolean {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            am.lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE
        } else {
            @Suppress("DEPRECATION")
            am.isInLockTaskMode
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            applySystemUiMode()
            // 如果重新获得焦点，且当前需要软锁锁定，但实际上未进入锁定状态，且并非 Device Owner，
            // 说明用户点击了“不用了”或者主动解除了屏幕固定。
            // 此时我们设置 isSoftLockDeferred = true 避免循环弹窗提示。
            if (KioskPrefs.getProtectionMode(this) == KioskPrefs.MODE_SOFT_LOCK &&
                !dpm.isDeviceOwnerApp(packageName) &&
                !isInLockTaskMode()) {
                isSoftLockDeferred = true
            }
        }
    }

    private fun applySandboxLimits() {
        WebViewPool.clear()
        WebViewPool.warmupBlank()
        applySystemUiMode()

        WebViewSystemUiPolicyBridge.publish(
            this,
            WebViewSystemUiPolicy(
                normalSystemBars = isNormalSystemUiMode()
            )
        )

        if (KioskPrefs.isLimitFlagSecureEnabled(this)) {
            window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }

        if (!dpm.isDeviceOwnerApp(packageName)) {
            return
        }

        val admin = adminComponent
        applyUserRestriction(admin, UserManager.DISALLOW_DEBUGGING_FEATURES, KioskPrefs.isLimitAdbEnabled(this))
        applyUserRestriction(admin, UserManager.DISALLOW_SAFE_BOOT, KioskPrefs.isLimitSafeBootEnabled(this))
        applyUserRestriction(admin, UserManager.DISALLOW_FACTORY_RESET, KioskPrefs.isLimitFactoryResetEnabled(this))
        applyUserRestriction(admin, UserManager.DISALLOW_ADD_USER, KioskPrefs.isLimitAddUserEnabled(this))
        applyUserRestriction(admin, UserManager.DISALLOW_USB_FILE_TRANSFER, KioskPrefs.isLimitUsbTransferEnabled(this))
        applyUserRestriction(admin, UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES, KioskPrefs.isLimitUnknownSourcesEnabled(this))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            applyUserRestriction(admin, "no_voice_assistants", KioskPrefs.isLimitVoiceAssistantsEnabled(this))
        }

        runCatching {
            dpm.setScreenCaptureDisabled(admin, KioskPrefs.isLimitScreenshotEnabled(this))
        }
        runCatching {
            // Lock Task 的 SYSTEM_INFO 只放出时间/电量等系统信息，不开放通知下拉。
            // 继续全局禁用 StatusBar 会在部分刘海屏/OEM 上把系统信息区也压成黑条。
            dpm.setStatusBarDisabled(admin, false)
        }
        runCatching {
            dpm.setKeyguardDisabled(admin, KioskPrefs.isLimitKeyguardEnabled(this))
        }
    }

    private fun applySystemUiMode() {
        when {
            shouldUseNormalSystemBars() -> {
                SystemUiHelper.enterNormal(
                    this,
                    showStatusBar = shouldShowNormalStatusBar()
                )
            }
            shouldShowSecureSystemInfo() -> SystemUiHelper.enterSecureSystemInfo(this)
            else -> SystemUiHelper.enterImmersive(this)
        }
    }

    private fun isNormalSystemUiMode(): Boolean {
        return ::dpm.isInitialized &&
            !dpm.isDeviceOwnerApp(packageName) &&
            KioskPrefs.getProtectionMode(this) == KioskPrefs.MODE_NONE &&
            !KioskPrefs.isLimitStatusBarEnabled(this)
    }

    private fun shouldUseNormalSystemBars(): Boolean {
        return isNormalSystemUiMode()
    }

    private fun shouldShowNormalStatusBar(): Boolean {
        return resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE
    }

    private fun shouldShowSecureSystemInfo(): Boolean {
        return ::dpm.isInitialized &&
            dpm.isDeviceOwnerApp(packageName) &&
            KioskPrefs.isLimitStatusBarEnabled(this) &&
            resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE
    }

    private fun applyUserRestriction(admin: ComponentName, restriction: String, enabled: Boolean) {
        runCatching {
            if (enabled) {
                dpm.addUserRestriction(admin, restriction)
            } else {
                dpm.clearUserRestriction(admin, restriction)
            }
        }
    }

    /**
     * 激活 Kiosk 模式 (Lock Task Mode) 并在 Device Owner 模式下进行多维度安全限制加固
     */
    private fun setupAndStartKiosk() {
        try {
            dpm.setLockTaskPackages(adminComponent, arrayOf(packageName))

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                dpm.setLockTaskFeatures(
                    adminComponent,
                    DevicePolicyManager.LOCK_TASK_FEATURE_SYSTEM_INFO
                )
            }

            // 应用可配置的沙箱限制
            applySandboxLimits()

            startLockTask()
            applySystemUiMode()
        } catch (e: IllegalStateException) {
            Log.w(TAG, "已经处于 Lock Task 状态，无需重复启动: ${e.message}")
            applySystemUiMode()
        } catch (e: SecurityException) {
            Toast.makeText(this, "权限不足，部分系统加固未生效: ${e.message}", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Kiosk 锁定启动失败: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Tier 2：非 Device Owner 时的「屏幕固定」软锁。
     *
     * 普通应用调用 [startLockTask] 会触发系统级 Screen Pinning（屏幕固定），
     * 拦截 Home / 最近任务键。首次在部分 OEM 上可能弹出系统确认框，这是预期行为。
     * 软锁可被「长按 返回+最近任务」等系统手势解除，因此防护强度低于 Device Owner，
     * 但无需恢复出厂即可让普通侧载用户开箱使用。
     */
    private fun startSoftLock() {
        try {
            startLockTask()
        } catch (e: IllegalStateException) {
            Log.w(TAG, "已处于屏幕固定状态，无需重复启动: ${e.message}")
        } catch (e: Exception) {
            Log.w(TAG, "屏幕固定软锁启动失败（设备可能不支持）: ${e.message}")
        }
    }

    /**
     * 退出锁定并重置限制，使用户能够切回原生桌面。
     * Device Owner 与屏幕固定软锁两种场景统一走此流程；非 Device Owner 时
     * 用户限制相关调用会因无权限被 runCatching 静默忽略。
     */
    private fun stopLockTaskMode() {
        try {
            runCatching { stopLockTask() }

            if (dpm.isDeviceOwnerApp(packageName)) {
                runCatching { dpm.clearUserRestriction(adminComponent, "no_voice_assistants") }
                runCatching { dpm.clearUserRestriction(adminComponent, UserManager.DISALLOW_DEBUGGING_FEATURES) }
                runCatching { dpm.clearUserRestriction(adminComponent, UserManager.DISALLOW_SAFE_BOOT) }
                runCatching { dpm.clearUserRestriction(adminComponent, UserManager.DISALLOW_FACTORY_RESET) }
                runCatching { dpm.clearUserRestriction(adminComponent, UserManager.DISALLOW_ADD_USER) }
                runCatching { dpm.clearUserRestriction(adminComponent, UserManager.DISALLOW_USB_FILE_TRANSFER) }
                runCatching { dpm.clearUserRestriction(adminComponent, UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES) }
                runCatching { dpm.setScreenCaptureDisabled(adminComponent, false) }
                runCatching { dpm.setStatusBarDisabled(adminComponent, false) }
                runCatching { dpm.setKeyguardDisabled(adminComponent, false) }
            }

            Toast.makeText(this, "锁定已安全解除", Toast.LENGTH_SHORT).show()

            val intent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "解锁退出失败: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun openHomeSettings() {
        try {
            val intent = Intent(Settings.ACTION_HOME_SETTINGS)
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "无法自动打开设置，请手动将本应用设为默认桌面", Toast.LENGTH_LONG).show()
        }
    }



    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (KioskPrefs.isLimitVolumeKeysEnabled(this)) {
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
        Log.i(
            TAG,
            "onDestroy taskId=$taskId isTaskRoot=$isTaskRoot " +
                "isFinishing=$isFinishing changingConfigurations=$isChangingConfigurations"
        )
        filterEventReceiver?.let {
            unregisterReceiver(it)
            filterEventReceiver = null
        }
        super.onDestroy()
    }

    companion object {
        private const val TAG = "MainActivity"
        private const val STATE_CURRENT_SCREEN = "main_activity.current_screen"
        private const val SCREEN_MAIN = "MAIN"
        private const val SCREEN_ADMIN = "ADMIN"
        const val EXTRA_EDIT_WEB_APP_REQUEST = "site.anzz.childkiosk.extra.EDIT_WEB_APP_REQUEST"
        const val EXTRA_EDIT_WEB_APP_TITLE = "site.anzz.childkiosk.extra.EDIT_WEB_APP_TITLE"
        const val EXTRA_EDIT_WEB_APP_URL = "site.anzz.childkiosk.extra.EDIT_WEB_APP_URL"
        const val EXTRA_FORCE_SAFE_HOME = "site.anzz.childkiosk.extra.FORCE_SAFE_HOME"
    }

    private fun normalizeWebUrlForCompare(url: String): String {
        return runCatching {
            val uri = android.net.Uri.parse(url.trim())
            val scheme = uri.scheme?.lowercase() ?: return@runCatching ""
            val host = uri.host?.lowercase() ?: return@runCatching ""
            if (scheme != "http" && scheme != "https") return@runCatching ""
            val port = if (uri.port >= 0) ":${uri.port}" else ""
            val path = uri.encodedPath?.takeIf { it.isNotBlank() } ?: "/"
            val query = uri.encodedQuery?.let { "?$it" }.orEmpty()
            "$scheme://$host$port$path$query"
        }.getOrDefault("")
    }
}
