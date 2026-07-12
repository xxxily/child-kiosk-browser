package site.anzz.childkiosk.ui

import android.app.role.RoleManager
import android.content.Context
import android.webkit.URLUtil
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.border
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.activity.compose.BackHandler
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import site.anzz.childkiosk.BuildConfig
import site.anzz.childkiosk.data.AppDatabase
import site.anzz.childkiosk.data.SystemConfigEntity
import site.anzz.childkiosk.data.WebAppEntity
import site.anzz.childkiosk.util.HashUtils
import site.anzz.childkiosk.util.KioskPrefs
import site.anzz.childkiosk.util.WebAppIconCache
import site.anzz.childkiosk.util.WebDataManager
import site.anzz.childkiosk.util.WebDataStats
import site.anzz.childkiosk.util.WebViewProviderDiagnostics
import site.anzz.childkiosk.util.WebViewProviderSnapshot
import site.anzz.childkiosk.util.WebViewRuntime
import site.anzz.childkiosk.util.WebViewPool
import site.anzz.childkiosk.util.WebIconCandidate
import site.anzz.childkiosk.util.WebIconDiscovery
import site.anzz.childkiosk.util.WhitelistSubscriptionRepository
import site.anzz.childkiosk.util.filter.FilterBuildReport
import site.anzz.childkiosk.util.filter.FilterEvent
import site.anzz.childkiosk.util.filter.FilterIndexStats
import site.anzz.childkiosk.util.filter.FilterPerfDiagnosticSnapshot
import site.anzz.childkiosk.util.filter.FilterPerfSampleStats
import site.anzz.childkiosk.util.filter.FilterPerfSnapshot
import site.anzz.childkiosk.util.filter.FilterPreset
import site.anzz.childkiosk.util.filter.FilterRepository
import site.anzz.childkiosk.util.filter.FilterSlowShouldBlockSample
import site.anzz.childkiosk.util.filter.SiteFilterOverride
import site.anzz.childkiosk.util.filter.normalizeHost
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import site.anzz.childkiosk.WebViewActivity
import site.anzz.childkiosk.data.BrowserHistoryEntity

private const val CAPABILITY_ENHANCEMENTS = "CAPABILITY_ENHANCEMENTS"
private const val CAPABILITY_LOCATION = "CAPABILITY_LOCATION"
private const val CAPABILITY_HIGH_PERFORMANCE = "CAPABILITY_HIGH_PERFORMANCE"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminConsoleScreen(
    config: SystemConfigEntity?,
    isDeviceOwner: Boolean,
    onBack: () -> Unit,
    onExitKiosk: () -> Unit,
    onGoToHomeSettings: () -> Unit,
    onProtectionModeChanged: (String) -> Unit = {},
    onSandboxLimitsChanged: () -> Unit = {},
    normalSystemBars: Boolean = false
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val db = remember { AppDatabase.getInstance(context) }

    var webApps by remember { mutableStateOf<List<WebAppEntity>>(emptyList()) }
    var browserHistory by remember { mutableStateOf<List<BrowserHistoryEntity>>(emptyList()) }
    var showAddDialog by remember { mutableStateOf(false) }
    var addingInitialWebApp by remember { mutableStateOf<WebAppEntity?>(null) }
    var editingWebApp by remember { mutableStateOf<WebAppEntity?>(null) }
    var whitelistSubscriptionUrl by remember { mutableStateOf(KioskPrefs.getWhitelistSubscriptionUrl(context)) }
    var whitelistAutoRefresh by remember {
        mutableStateOf(KioskPrefs.isWhitelistSubscriptionAutoRefreshEnabled(context))
    }
    var whitelistRefreshIntervalText by remember {
        mutableStateOf(KioskPrefs.getWhitelistSubscriptionRefreshIntervalHours(context).toString())
    }
    var whitelistSubscriptionTitle by remember { mutableStateOf(KioskPrefs.getWhitelistSubscriptionTitle(context)) }
    var whitelistLastSuccessAt by remember { mutableStateOf(KioskPrefs.getWhitelistSubscriptionLastSuccessAt(context)) }
    var whitelistImportedCount by remember { mutableStateOf(KioskPrefs.getWhitelistSubscriptionImportedCount(context)) }
    var whitelistLastError by remember { mutableStateOf(KioskPrefs.getWhitelistSubscriptionLastError(context)) }
    var isRefreshingWhitelistSubscription by remember { mutableStateOf(false) }

    // 配置状态
    var timeLimit by remember { mutableStateOf(config?.timeLimitMinutes ?: 0) }
    var dailyLimit by remember { mutableStateOf(config?.dailyLimitMinutes ?: 0) }
    var verificationMode by remember { mutableStateOf(config?.verificationMode ?: "MATH") }

    // 非 Device Owner 场景下的防护等级（屏幕固定软锁 / 无系统级锁定）
    var protectionMode by remember { mutableStateOf(KioskPrefs.getProtectionMode(context)) }
    var quickMode by remember { mutableStateOf(KioskPrefs.getQuickMode(context)) }

    var showPinSetupDialog by remember { mutableStateOf(false) }

    // 检查更新与诊断相关状态
    var showUpdateDialog by remember { mutableStateOf(false) }
    var latestReleaseInfo by remember { mutableStateOf<ReleaseInfo?>(null) }
    var isCheckingUpdate by remember { mutableStateOf(false) }

    // 当前后台路由：null 代表后台首页；能力增强详情页拥有独立的第三级返回层级。
    var currentSubPage by rememberSaveable { mutableStateOf<String?>(null) }

    fun navigateUp() {
        when (currentSubPage) {
            CAPABILITY_LOCATION,
            CAPABILITY_HIGH_PERFORMANCE -> currentSubPage = CAPABILITY_ENHANCEMENTS
            null -> onBack()
            else -> currentSubPage = null
        }
    }

    BackHandler(enabled = true) { navigateUp() }

    val currentVersion = remember {
        try {
            val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            pInfo.versionName ?: "未知"
        } catch (e: Exception) {
            "未知"
        }
    }
    val currentDistribution = remember { currentDistributionLabel() }
    val currentSigningIdentity = remember { readCurrentAppSigningIdentity(context) }

    var webViewSnapshot by remember { mutableStateOf(WebViewProviderDiagnostics.collect(context)) }

    fun refreshWebViewSnapshot() {
        webViewSnapshot = WebViewProviderDiagnostics.collect(context)
    }

    fun openUri(uri: String, errorMessage: String) {
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)))
        }.onFailure {
            Toast.makeText(context, errorMessage, Toast.LENGTH_SHORT).show()
        }
    }

    fun openWebViewMarket(packageName: String) {
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$packageName")))
        }.onFailure {
            openUri(
                "https://play.google.com/store/apps/details?id=$packageName",
                "无法打开应用商店，请手动搜索 $packageName"
            )
        }
    }

    fun openWebViewSettings() {
        val intents = listOf(
            Intent(Settings.ACTION_WEBVIEW_SETTINGS),
            Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS),
            Intent(Settings.ACTION_SETTINGS)
        )
        val opened = intents.any { intent ->
            runCatching {
                context.startActivity(intent)
                true
            }.getOrDefault(false)
        }
        if (!opened) {
            Toast.makeText(context, "无法打开系统 WebView 设置", Toast.LENGTH_SHORT).show()
        }
    }

    fun openDefaultBrowserSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = context.getSystemService(RoleManager::class.java)
            if (roleManager?.isRoleAvailable(RoleManager.ROLE_BROWSER) == true) {
                runCatching {
                    context.startActivity(roleManager.createRequestRoleIntent(RoleManager.ROLE_BROWSER))
                    return
                }
            }
        }
        val intents = listOf(
            Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS),
            Intent(Settings.ACTION_SETTINGS)
        )
        val opened = intents.any { intent ->
            runCatching {
                context.startActivity(intent)
                true
            }.getOrDefault(false)
        }
        if (!opened) {
            Toast.makeText(context, "无法打开默认浏览器设置，请在系统默认应用中选择本应用", Toast.LENGTH_LONG).show()
        }
    }

    fun reloadWhitelistSubscriptionState() {
        whitelistSubscriptionUrl = KioskPrefs.getWhitelistSubscriptionUrl(context)
        whitelistAutoRefresh = KioskPrefs.isWhitelistSubscriptionAutoRefreshEnabled(context)
        whitelistRefreshIntervalText = KioskPrefs.getWhitelistSubscriptionRefreshIntervalHours(context).toString()
        whitelistSubscriptionTitle = KioskPrefs.getWhitelistSubscriptionTitle(context)
        whitelistLastSuccessAt = KioskPrefs.getWhitelistSubscriptionLastSuccessAt(context)
        whitelistImportedCount = KioskPrefs.getWhitelistSubscriptionImportedCount(context)
        whitelistLastError = KioskPrefs.getWhitelistSubscriptionLastError(context)
    }

    fun saveWhitelistSubscriptionSettings(): Boolean {
        val url = whitelistSubscriptionUrl.trim()
        if (url.isNotBlank() && !url.startsWith("https://", ignoreCase = true)) {
            Toast.makeText(context, "白名单订阅地址必须使用 HTTPS", Toast.LENGTH_SHORT).show()
            return false
        }
        val intervalHours = whitelistRefreshIntervalText.toIntOrNull()?.coerceIn(1, 168)
        if (intervalHours == null) {
            Toast.makeText(context, "刷新间隔需为 1-168 小时", Toast.LENGTH_SHORT).show()
            return false
        }
        KioskPrefs.setWhitelistSubscriptionUrl(context, url)
        KioskPrefs.setWhitelistSubscriptionAutoRefreshEnabled(context, whitelistAutoRefresh)
        KioskPrefs.setWhitelistSubscriptionRefreshIntervalHours(context, intervalHours)
        whitelistRefreshIntervalText = intervalHours.toString()
        return true
    }

    val protectionLevel = remember(isDeviceOwner) {
        if (isDeviceOwner) "企业级完全锁定 (Device Owner)" else "普通锁定"
    }

    // 同步设置数据
    LaunchedEffect(config) {
        config?.let {
            timeLimit = it.timeLimitMinutes
            dailyLimit = it.dailyLimitMinutes
            verificationMode = it.verificationMode
        }
    }

    // 载入应用列表
    LaunchedEffect(Unit) {
        db.webAppDao().getAllWebAppsFlow().collect { list ->
            webApps = list
        }
    }

    LaunchedEffect(Unit) {
        db.browserHistoryDao().getRecentHistoryFlow(200).collect { list ->
            browserHistory = list
        }
    }

    LaunchedEffect(currentSubPage) {
        quickMode = KioskPrefs.getQuickMode(context)
        protectionMode = KioskPrefs.getProtectionMode(context)
        if (currentSubPage == "WHITELIST") {
            reloadWhitelistSubscriptionState()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    val titleText = when (currentSubPage) {
                        "QUICK_MODE" -> "快速切换模式"
                        "PROTECTION" -> "安全防护等级"
                        "TIME_LIMIT" -> "健康时间限制"
                         "VERIFICATION" -> "认证设置"
                        "INTERFACE" -> "界面与显示配置"
                        "WEB_FILTERING" -> "网页过滤管理"
                        CAPABILITY_ENHANCEMENTS -> "能力增强"
                        CAPABILITY_LOCATION -> "网页定位增强"
                        CAPABILITY_HIGH_PERFORMANCE -> "高性能持续运行"
                        "SANDBOX_LIMITS" -> "安全沙箱与限制"
                        "PERFORMANCE" -> "网页性能优化"
                        "WEBVIEW_PROVIDER" -> "WebView 内核环境"
                        "WHITELIST" -> "应用白名单管理"
                        "HISTORY" -> "浏览历史记录"
                        else -> "配置后台"
                    }
                    Text(titleText, fontWeight = FontWeight.Bold)
                },
                navigationIcon = {
                    IconButton(onClick = ::navigateUp) {
                        Icon(imageVector = Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        },
        floatingActionButton = {
            // 只有在应用白名单二级页面下才显示“添加应用” FloatingActionButton
            if (currentSubPage == "WHITELIST") {
                FloatingActionButton(
                    onClick = { showAddDialog = true },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ) {
                    Icon(imageVector = Icons.Default.Add, contentDescription = "添加应用")
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (currentSubPage) {
                null -> {
                    // 主菜单列表
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        item {
                            Card(
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                            ) {
                                Column {
                                    AdminMenuItem(
                                        icon = Icons.Default.AutoFixHigh,
                                        title = "快速切换模式",
                                        summary = "当前：${quickModeLabel(quickMode)}",
                                        onClick = { currentSubPage = "QUICK_MODE" }
                                    )
                                    Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f))
                                    AdminMenuItem(
                                        icon = Icons.Default.Lock,
                                        title = "安全防护与锁定",
                                        summary = if (isDeviceOwner) "企业级完全锁定已生效" else "当前处于普通锁定（可配置软锁）",
                                        onClick = { currentSubPage = "PROTECTION" }
                                    )
                                    Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f))
                                    AdminMenuItem(
                                        icon = Icons.Default.DateRange,
                                        title = "健康限时管理",
                                        summary = "每次可用: ${if (timeLimit > 0) "${timeLimit}分钟" else "不限"} | 每日累计: ${if (dailyLimit > 0) "${dailyLimit}分钟" else "不限"}",
                                        onClick = { currentSubPage = "TIME_LIMIT" }
                                    )
                                    Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f))
                                    AdminMenuItem(
                                        icon = Icons.Default.VerifiedUser,
                                        title = "身份认证",
                                        summary = if (verificationMode == "MATH") "当前使用动态口算题验证" else "当前使用数字 PIN 密码验证",
                                        onClick = { currentSubPage = "VERIFICATION" }
                                    )
                                    Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f))
                                    AdminMenuItem(
                                        icon = Icons.Default.Settings,
                                        title = "界面与显示配置",
                                        summary = "屏幕方向、首屏图标大小、隐藏标题及管理锁图标等",
                                        onClick = { currentSubPage = "INTERFACE" }
                                    )
                                    Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f))
                                    AdminMenuItem(
                                        icon = Icons.Default.FilterAlt,
                                        title = "网页过滤管理",
                                        summary = "订阅 Adblock 规则、管理自定义规则、站点例外和过滤日志",
                                        onClick = { currentSubPage = "WEB_FILTERING" }
                                    )
                                    Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f))
                                    AdminMenuItem(
                                        icon = Icons.Default.Extension,
                                        title = "能力增强",
                                        summary = "定位增强${if (KioskPrefs.isNativeLocationOptimizationEnabled(context)) "已开启" else "未开启"}，后续增强能力统一在这里配置",
                                        onClick = { currentSubPage = CAPABILITY_ENHANCEMENTS }
                                    )
                                    Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f))
                                    AdminMenuItem(
                                        icon = Icons.Default.Lock,
                                        title = "安全沙箱与限制",
                                        summary = "系统防逃逸、物理按键控制及非过滤类网页沙箱配置",
                                        onClick = { currentSubPage = "SANDBOX_LIMITS" }
                                    )
                                    Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f))
                                    AdminMenuItem(
                                        icon = Icons.Default.Build,
                                        title = "网页缓存与秒开优化",
                                        summary = "预加载常用网站，清理网页缓存及 Cookie 数据",
                                        onClick = { currentSubPage = "PERFORMANCE" }
                                    )
                                    Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f))
                                    AdminMenuItem(
                                        icon = Icons.Default.Language,
                                        title = "WebView 内核环境",
                                        summary = "${webViewSnapshot.providerSummary} | ${webViewSnapshot.status.label}",
                                        onClick = {
                                            refreshWebViewSnapshot()
                                            currentSubPage = "WEBVIEW_PROVIDER"
                                        }
                                    )
                                    Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f))
                                    AdminMenuItem(
                                        icon = Icons.Default.List,
                                        title = "应用白名单管理",
                                        summary = "管理并分类展示允许访问的应用（共 ${webApps.size} 个应用）",
                                        onClick = { currentSubPage = "WHITELIST" }
                                    )
                                    Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f))
                                    AdminMenuItem(
                                        icon = Icons.Default.History,
                                        title = "浏览历史记录",
                                        summary = "查看最近访问的网站，支持恢复访问或加入白名单（共 ${browserHistory.size} 条）",
                                        onClick = { currentSubPage = "HISTORY" }
                                    )
                                }
                            }
                        }

                        // 关于与系统诊断卡片 (保留在首页下方)
                        item {
                            AboutAndSystemCard(
                                currentVersion = currentVersion,
                                currentDistribution = currentDistribution,
                                webViewInfo = webViewSnapshot.providerSummary,
                                deviceInfo = webViewSnapshot.deviceModel,
                                androidVersion = webViewSnapshot.androidVersion,
                                protectionLevel = protectionLevel,
                                isCheckingUpdate = isCheckingUpdate,
                                onCheckUpdate = {
                                    isCheckingUpdate = true
                                    scope.launch {
                                        val release = fetchLatestRelease(BuildConfig.DISTRIBUTION)
                                        isCheckingUpdate = false
                                        if (release != null) {
                                            latestReleaseInfo = release
                                            if (isNewerVersion(currentVersion, release.version)) {
                                                showUpdateDialog = true
                                            } else {
                                                Toast.makeText(context, "当前已是最新版本！", Toast.LENGTH_SHORT).show()
                                            }
                                        } else {
                                            Toast.makeText(context, "检查更新失败，请检查网络连接！", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                },
                                onCopyUrl = {
                                    clipboardManager.setText(AnnotatedString("https://github.com/xxxily/child-kiosk-browser"))
                                    Toast.makeText(context, "项目地址已复制到剪贴板！", Toast.LENGTH_SHORT).show()
                                },
                                onOpenUrl = {
                                    try {
                                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/xxxily/child-kiosk-browser"))
                                        context.startActivity(intent)
                                    } catch (e: Exception) {
                                        Toast.makeText(context, "无法打开浏览器，请手动复制地址", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            )
                        }

                        // 返回系统桌面 (保留在首页底部)
                        item {
                            Spacer(modifier = Modifier.height(8.dp))
                            QButton(
                                onClick = onExitKiosk,
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.errorContainer,
                                    contentColor = MaterialTheme.colorScheme.onErrorContainer
                                )
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Home,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("返回系统桌面", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
                "QUICK_MODE" -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        QuickModeCard(
                            quickMode = quickMode,
                            onModeSelected = { mode ->
                                KioskPrefs.applyQuickMode(context, mode)
                                quickMode = KioskPrefs.getQuickMode(context)
                                protectionMode = KioskPrefs.getProtectionMode(context)
                                WebViewPool.clear()
                                onProtectionModeChanged(protectionMode)
                                onSandboxLimitsChanged()
                                val label = quickModeLabel(quickMode)
                                Toast.makeText(context, "已切换为$label", Toast.LENGTH_SHORT).show()
                            }
                        )
                    }
                }
                "PROTECTION" -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        ProtectionLevelCard(
                            isDeviceOwner = isDeviceOwner,
                            protectionMode = protectionMode,
                            onModeChange = { mode ->
                                protectionMode = mode
                                KioskPrefs.setProtectionMode(context, mode)
                                onProtectionModeChanged(mode)
                            },
                            onCopyScript = {
                                clipboardManager.setText(
                                    AnnotatedString("adb shell dpm set-device-owner site.anzz.childkiosk/.MyDeviceAdminReceiver")
                                )
                                Toast.makeText(context, "ADB 激活脚本已复制到剪贴板！", Toast.LENGTH_SHORT).show()
                            },
                            onGoToHomeSettings = onGoToHomeSettings
                        )
                    }
                }
                "TIME_LIMIT" -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Card(
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(bottom = 12.dp)
                                ) {
                                    Icon(imageVector = Icons.Default.DateRange, contentDescription = "限时", tint = MaterialTheme.colorScheme.primary)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("儿童健康使用时长限制", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                                }

                                // 单次使用时间限制
                                Text("每次持续可用时长：${if (timeLimit > 0) "${timeLimit}分钟" else "不限"}", fontSize = 14.sp)
                                Slider(
                                    value = timeLimit.toFloat(),
                                    onValueChange = { timeLimit = it.toInt() },
                                    valueRange = 0f..120f,
                                    steps = 7, // 0, 15, 30, 45, 60, 75, 90, 105, 120
                                    onValueChangeFinished = {
                                        scope.launch(Dispatchers.IO) {
                                            val current = db.systemConfigDao().getSystemConfig() ?: SystemConfigEntity()
                                            db.systemConfigDao().insertOrUpdateConfig(current.copy(timeLimitMinutes = timeLimit))
                                        }
                                    }
                                )

                                // 每日累计限制
                                Text("每日累计可用时长：${if (dailyLimit > 0) "${dailyLimit}分钟" else "不限"}", fontSize = 14.sp)
                                Slider(
                                    value = dailyLimit.toFloat(),
                                    onValueChange = { dailyLimit = it.toInt() },
                                    valueRange = 0f..240f,
                                    steps = 7, // 0, 30, 60, 90, 120, 150, 180, 210, 240
                                    onValueChangeFinished = {
                                        scope.launch(Dispatchers.IO) {
                                            val current = db.systemConfigDao().getSystemConfig() ?: SystemConfigEntity()
                                            db.systemConfigDao().insertOrUpdateConfig(current.copy(dailyLimitMinutes = dailyLimit))
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
                "VERIFICATION" -> {
                    var verifyAdminActions by remember { mutableStateOf(KioskPrefs.getVerifyAdminActions(context)) }
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Card(
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(bottom = 12.dp)
                                ) {
                                    Icon(imageVector = Icons.Default.VerifiedUser, contentDescription = "验证", tint = MaterialTheme.colorScheme.primary)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("身份认证配置", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        RadioButton(
                                            selected = verificationMode == "MATH",
                                            onClick = {
                                                verificationMode = "MATH"
                                                scope.launch(Dispatchers.IO) {
                                                    val current = db.systemConfigDao().getSystemConfig() ?: SystemConfigEntity()
                                                    db.systemConfigDao().insertOrUpdateConfig(current.copy(verificationMode = "MATH"))
                                                }
                                            }
                                        )
                                        Text("动态口算题")
                                    }

                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        RadioButton(
                                            selected = verificationMode == "PIN",
                                            onClick = {
                                                if (config?.pinHash.isNullOrEmpty()) {
                                                    // 必须先配置 PIN 才能切换
                                                    showPinSetupDialog = true
                                                } else {
                                                    verificationMode = "PIN"
                                                    scope.launch(Dispatchers.IO) {
                                                        val current = db.systemConfigDao().getSystemConfig() ?: SystemConfigEntity()
                                                        db.systemConfigDao().insertOrUpdateConfig(current.copy(verificationMode = "PIN"))
                                                    }
                                                }
                                            }
                                        )
                                        Text("数字 PIN 码")
                                    }
                                }

                                if (!config?.pinHash.isNullOrEmpty()) {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    TextButton(onClick = { showPinSetupDialog = true }) {
                                        Icon(imageVector = Icons.Default.Settings, contentDescription = "修改")
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("修改数字 PIN 码")
                                    }
                                }
                            }
                        }

                        Card(
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                        ) {
                            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(imageVector = Icons.Default.Security, contentDescription = "验证安全", tint = MaterialTheme.colorScheme.primary)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("认证限制开关", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("启用认证限制", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                        Text("若关闭，则进入后台或退出时免除验证，极大提升开发配置效率", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Switch(
                                        checked = verifyAdminActions,
                                        onCheckedChange = {
                                            verifyAdminActions = it
                                            KioskPrefs.setVerifyAdminActions(context, it)
                                        }
                                    )
                                }

                                if (!verifyAdminActions) {
                                    Card(
                                        shape = RoundedCornerShape(8.dp),
                                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text(
                                            text = "⚠️ 警告：关闭验证后，任何人多次点击管理锁图标均可直接进入配置后台或退出应用，防逃逸安全机制将失效。建议仅在开发调试配置阶段临时关闭，日常使用请务必开启！",
                                            fontSize = 12.sp,
                                            color = MaterialTheme.colorScheme.onErrorContainer,
                                            modifier = Modifier.padding(12.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                "INTERFACE" -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        // 屏幕显示方向配置
                        Card(
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(bottom = 12.dp)
                                ) {
                                    Icon(imageVector = Icons.Default.Refresh, contentDescription = "方向", tint = MaterialTheme.colorScheme.primary)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("屏幕显示方向", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                                }

                                var orientationMode by remember { mutableStateOf(KioskPrefs.getOrientationMode(context)) }
                                fun applyOrientationMode(mode: String) {
                                    orientationMode = mode
                                    KioskPrefs.setOrientationMode(context, mode)
                                    (context as? android.app.Activity)?.requestedOrientation =
                                        KioskPrefs.requestedOrientationForMode(mode)
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        RadioButton(
                                            selected = orientationMode == KioskPrefs.ORIENTATION_AUTO,
                                            onClick = {
                                                applyOrientationMode(KioskPrefs.ORIENTATION_AUTO)
                                            }
                                        )
                                        Text("自适应")
                                    }

                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        RadioButton(
                                            selected = orientationMode == KioskPrefs.ORIENTATION_LANDSCAPE,
                                            onClick = {
                                                applyOrientationMode(KioskPrefs.ORIENTATION_LANDSCAPE)
                                            }
                                        )
                                        Text("横屏")
                                    }

                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        RadioButton(
                                            selected = orientationMode == KioskPrefs.ORIENTATION_PORTRAIT,
                                            onClick = {
                                                applyOrientationMode(KioskPrefs.ORIENTATION_PORTRAIT)
                                            }
                                        )
                                        Text("竖屏")
                                    }
                                }
                            }
                        }

                        // 首屏图标显示大小配置
                        Card(
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(bottom = 12.dp)
                                ) {
                                    Icon(imageVector = Icons.Default.Menu, contentDescription = "图标", tint = MaterialTheme.colorScheme.primary)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("首屏图标显示大小", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                                }

                                var iconSizeMode by remember { mutableStateOf(KioskPrefs.getIconSizeMode(context)) }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        RadioButton(
                                            selected = iconSizeMode == KioskPrefs.ICON_SIZE_SMALL,
                                            onClick = {
                                                iconSizeMode = KioskPrefs.ICON_SIZE_SMALL
                                                KioskPrefs.setIconSizeMode(context, KioskPrefs.ICON_SIZE_SMALL)
                                            }
                                        )
                                        Text("小")
                                    }

                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        RadioButton(
                                            selected = iconSizeMode == KioskPrefs.ICON_SIZE_MEDIUM,
                                            onClick = {
                                                iconSizeMode = KioskPrefs.ICON_SIZE_MEDIUM
                                                KioskPrefs.setIconSizeMode(context, KioskPrefs.ICON_SIZE_MEDIUM)
                                            }
                                        )
                                        Text("中")
                                    }

                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        RadioButton(
                                            selected = iconSizeMode == KioskPrefs.ICON_SIZE_LARGE,
                                            onClick = {
                                                iconSizeMode = KioskPrefs.ICON_SIZE_LARGE
                                                KioskPrefs.setIconSizeMode(context, KioskPrefs.ICON_SIZE_LARGE)
                                            }
                                        )
                                        Text("大")
                                    }
                                }
                            }
                        }

                        // 首屏壁纸与配色配置
                        Card(
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(bottom = 12.dp)
                                ) {
                                    Icon(imageVector = Icons.Default.Palette, contentDescription = "壁纸", tint = MaterialTheme.colorScheme.primary)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("首屏壁纸与配色配置", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                                }

                                var currentPreset by remember { mutableStateOf(KioskPrefs.getWallpaperPreset(context)) }

                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(rememberScrollState())
                                        .padding(vertical = 4.dp),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    WallpaperPresets.forEach { preset ->
                                        val isSelected = currentPreset == preset.id
                                        Column(
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            modifier = Modifier
                                                .clickable {
                                                    currentPreset = preset.id
                                                    KioskPrefs.setWallpaperPreset(context, preset.id)
                                                }
                                                .padding(4.dp)
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(48.dp)
                                                    .clip(CircleShape)
                                                    .then(
                                                        if (preset.brush != null) {
                                                            Modifier.background(preset.brush)
                                                        } else if (preset.color != null) {
                                                            Modifier.background(preset.color)
                                                        } else {
                                                            Modifier.background(Color.Gray)
                                                        }
                                                    )
                                                    .border(
                                                        width = if (isSelected) 3.dp else 1.dp,
                                                        color = if (isSelected) MaterialTheme.colorScheme.primary else Color.LightGray.copy(alpha = 0.5f),
                                                        shape = CircleShape
                                                    ),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                if (isSelected) {
                                                    Icon(
                                                        imageVector = Icons.Default.Check,
                                                        contentDescription = "selected",
                                                        tint = if (preset.isDark) Color.White else Color.Black,
                                                        modifier = Modifier.size(20.dp)
                                                    )
                                                }
                                            }
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Text(
                                                text = preset.label.split(" ").firstOrNull() ?: "",
                                                fontSize = 11.sp,
                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        // 界面配置卡片
                        Card(
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                        ) {
                            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(imageVector = Icons.Default.Settings, contentDescription = "界面配置", tint = MaterialTheme.colorScheme.primary)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("主页界面与网站退出行为配置", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                                }

                                // 选项 1: 退出网站时需要认证
                                var verifyOnExit by remember { mutableStateOf(KioskPrefs.getVerifyOnWebExit(context)) }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("退出网站时需要验证", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                        Text("关闭后按返回键可直接退回主页，开启则需完成认证", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Switch(
                                        checked = verifyOnExit,
                                        onCheckedChange = {
                                            verifyOnExit = it
                                            KioskPrefs.setVerifyOnWebExit(context, it)
                                        }
                                    )
                                }

                                Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))

                                // 选项 2: 隐藏右上角管理锁图标
                                var hideAdminIcon by remember { mutableStateOf(KioskPrefs.getHideAdminIcon(context)) }
                                var adminQuickOpen by remember { mutableStateOf(KioskPrefs.getAdminQuickOpen(context)) }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("单击管理入口直接打开菜单", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                        Text("关闭后需要在右上角区域 2 秒内连续点击 5 次才会打开菜单", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Switch(
                                        checked = adminQuickOpen,
                                        onCheckedChange = {
                                            adminQuickOpen = it
                                            KioskPrefs.setAdminQuickOpen(context, it)
                                        }
                                    )
                                }

                                Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("隐藏右上角管理锁图标", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                        Text("隐藏后，主页右上角锁头将消失，只能通过快速盲点击该区域 5 次进入后台", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Switch(
                                        checked = hideAdminIcon,
                                        onCheckedChange = {
                                            hideAdminIcon = it
                                            KioskPrefs.setHideAdminIcon(context, it)
                                        }
                                    )
                                }

                                if (!hideAdminIcon) {
                                    Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                                    var adminIconAlpha by remember { mutableStateOf(KioskPrefs.getAdminIconAlpha(context)) }
                                    Column(
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                        verticalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text("管理锁图标透明度", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                            Text("${(adminIconAlpha * 100).toInt()}%", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                        }
                                        Slider(
                                            value = adminIconAlpha,
                                            onValueChange = {
                                                adminIconAlpha = it
                                                KioskPrefs.setAdminIconAlpha(context, it)
                                            },
                                            valueRange = 0.1f..1.0f,
                                            steps = 8
                                        )
                                    }
                                }

                                Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))

                                // 选项 3: 标题配置
                                var mainTitleText by remember { mutableStateOf(KioskPrefs.getMainTitleText(context)) }
                                var hideMainTitle by remember { mutableStateOf(KioskPrefs.getHideMainTitle(context)) }

                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text("隐藏主页标题文字", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                        Switch(
                                            checked = hideMainTitle,
                                            onCheckedChange = {
                                                hideMainTitle = it
                                                KioskPrefs.setHideMainTitle(context, it)
                                            }
                                        )
                                    }

                                    if (!hideMainTitle) {
                                        OutlinedTextField(
                                            value = mainTitleText,
                                            onValueChange = {
                                                mainTitleText = it
                                                KioskPrefs.setMainTitleText(context, it)
                                            },
                                            label = { Text("自定义主页标题文本") },
                                            singleLine = true,
                                            shape = RoundedCornerShape(8.dp),
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                "PERFORMANCE" -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Card(
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                        ) {
                            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(imageVector = Icons.Default.Build, contentDescription = "性能配置", tint = MaterialTheme.colorScheme.primary)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("网页缓存与性能优化", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                                }

                                var cacheStats by remember { mutableStateOf<WebDataStats?>(null) }
                                var poolSnapshot by remember { mutableStateOf(WebViewPool.snapshot()) }
                                var isCacheStatsLoading by remember { mutableStateOf(false) }
                                var isClearingCache by remember { mutableStateOf(false) }
                                var lastClearSummary by remember { mutableStateOf<String?>(null) }
                                var showClearCacheConfirm by remember { mutableStateOf(false) }
                                var webViewWarmPoolEnabled by remember { mutableStateOf(KioskPrefs.getWebViewWarmPoolEnabled(context)) }
                                var webViewRenderMode by remember { mutableStateOf(KioskPrefs.getWebViewRenderMode(context)) }
                                var webViewTopProgressEnabled by remember {
                                    mutableStateOf(KioskPrefs.isWebViewTopProgressEnabled(context))
                                }

                                fun refreshCacheStats() {
                                    isCacheStatsLoading = true
                                    poolSnapshot = WebViewPool.snapshot()
                                    scope.launch {
                                        cacheStats = withContext(Dispatchers.IO) {
                                            WebDataManager.collectStats(context)
                                        }
                                        poolSnapshot = WebViewPool.snapshot()
                                        isCacheStatsLoading = false
                                    }
                                }

                                fun clearWebCache() {
                                    if (isClearingCache) return
                                    isClearingCache = true
                                    val beforeBytes = cacheStats?.totalBytes
                                    WebViewPool.clear()
                                    scope.launch {
                                        runCatching {
                                            withContext(Dispatchers.IO) {
                                                WebDataManager.clearKnownWebCacheFiles(context)
                                            }
                                        }.onSuccess {
                                            if (webViewWarmPoolEnabled) {
                                                WebViewPool.warmupBlank()
                                            }
                                            val after = withContext(Dispatchers.IO) {
                                                WebDataManager.collectStats(context)
                                            }
                                            cacheStats = after
                                            poolSnapshot = WebViewPool.snapshot()
                                            KioskPrefs.setLastCacheClearTime(context, System.currentTimeMillis())
                                            val released = beforeBytes?.let { (it - after.totalBytes).coerceAtLeast(0L) }
                                            lastClearSummary = if (released != null) {
                                                "本次清理释放约 ${WebDataManager.formatBytes(released)}"
                                            } else {
                                                "清理完成，当前合计 ${WebDataManager.formatBytes(after.totalBytes)}"
                                            }
                                            Toast.makeText(context, "网页缓存和 Cookie 已清理", Toast.LENGTH_SHORT).show()
                                        }.onFailure { e ->
                                            Toast.makeText(context, "清理失败：${e.message}", Toast.LENGTH_SHORT).show()
                                        }
                                        isClearingCache = false
                                    }
                                }

                                LaunchedEffect(Unit) {
                                    refreshCacheStats()
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("网页顶部进度条", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                        Text("默认关闭。开启后仅在网页顶部显示细进度条，不使用全屏加载遮罩", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Switch(
                                        checked = webViewTopProgressEnabled,
                                        onCheckedChange = {
                                            webViewTopProgressEnabled = it
                                            KioskPrefs.setWebViewTopProgressEnabled(context, it)
                                            WebViewPool.clear()
                                            poolSnapshot = WebViewPool.snapshot()
                                        }
                                    )
                                }

                                Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))

                                // 选项 1: WebView 热备开关
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("保留 1 个空白 WebView 热备", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                        Text("默认关闭。WebView 已运行在独立进程；该热备只在 WebView 进程内生效，设备内存充足且追求打开速度时再开启", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Switch(
                                        checked = webViewWarmPoolEnabled,
                                        onCheckedChange = {
                                            webViewWarmPoolEnabled = it
                                            KioskPrefs.setWebViewWarmPoolEnabled(context, it)
                                            if (it) {
                                                WebViewPool.warmupBlank()
                                            } else {
                                                WebViewPool.clear()
                                            }
                                            poolSnapshot = WebViewPool.snapshot()
                                        }
                                    )
                                }

                                Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))

                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("WebView 渲染模式", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                    Text("默认保持系统硬件合成路径，贴近手机浏览器。通常无需调整；遇到厂商 WebView/驱动兼容问题时再对比测试", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .horizontalScroll(rememberScrollState()),
                                        horizontalArrangement = Arrangement.spacedBy(20.dp)
                                    ) {
                                        listOf(
                                            KioskPrefs.WEBVIEW_RENDER_MODE_AUTO to "自动默认",
                                            KioskPrefs.WEBVIEW_RENDER_MODE_HARDWARE to "硬件默认"
                                        ).forEach { (mode, label) ->
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                modifier = Modifier
                                                    .widthIn(min = 112.dp)
                                                    .clickable {
                                                        webViewRenderMode = mode
                                                        KioskPrefs.setWebViewRenderMode(context, mode)
                                                        WebViewPool.clear()
                                                        if (webViewWarmPoolEnabled) {
                                                            WebViewPool.warmupBlank()
                                                        }
                                                        poolSnapshot = WebViewPool.snapshot()
                                                    }
                                            ) {
                                                RadioButton(
                                                    selected = webViewRenderMode == mode,
                                                    onClick = {
                                                        webViewRenderMode = mode
                                                        KioskPrefs.setWebViewRenderMode(context, mode)
                                                        WebViewPool.clear()
                                                        if (webViewWarmPoolEnabled) {
                                                            WebViewPool.warmupBlank()
                                                        }
                                                        poolSnapshot = WebViewPool.snapshot()
                                                    }
                                                )
                                                Text(label, fontSize = 13.sp)
                                            }
                                        }
                                    }
                                }

                                Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))

                                // 选项 2: 网页预加载开关
                                var webPreloadEnabled by remember { mutableStateOf(KioskPrefs.getWebPreloadEnabled(context)) }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("网页后台预加载", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                        Text("默认关闭。会提前准备常用网页，设备内存充足且追求打开速度时再开启", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Switch(
                                        checked = webPreloadEnabled,
                                        onCheckedChange = {
                                            webPreloadEnabled = it
                                            KioskPrefs.setWebPreloadEnabled(context, it)
                                            if (!it) {
                                                WebViewPool.clear()
                                                if (webViewWarmPoolEnabled) {
                                                    WebViewPool.warmupBlank()
                                                }
                                            }
                                            poolSnapshot = WebViewPool.snapshot()
                                        }
                                    )
                                }

                                Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))

                                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text("网页缓存与本地数据", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                            Text("统计 WebView 数据目录、HTTP 缓存与代码缓存，便于清理前后对比", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                        TextButton(
                                            enabled = !isCacheStatsLoading && !isClearingCache,
                                            onClick = { refreshCacheStats() }
                                        ) {
                                            Icon(imageVector = Icons.Default.Refresh, contentDescription = "刷新")
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text(if (isCacheStatsLoading) "统计中" else "刷新")
                                        }
                                    }

                                    val stats = cacheStats
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.65f))
                                            .padding(12.dp),
                                        verticalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Text(
                                            text = "合计：${stats?.let { WebDataManager.formatBytes(it.totalBytes) } ?: if (isCacheStatsLoading) "统计中..." else "未统计"}",
                                            fontSize = 16.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                        Text(
                                            text = "WebView 数据：${stats?.let { WebDataManager.formatBytes(it.webViewDataBytes) } ?: "-"}",
                                            fontSize = 12.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Text(
                                            text = "HTTP 缓存：${stats?.let { WebDataManager.formatBytes(it.httpCacheBytes) } ?: "-"} | 代码缓存：${stats?.let { WebDataManager.formatBytes(it.codeCacheBytes) } ?: "-"}",
                                            fontSize = 12.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Text(
                                            text = "WebView 池：$poolSnapshot",
                                            fontSize = 12.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        lastClearSummary?.let {
                                            Text(
                                                text = it,
                                                fontSize = 12.sp,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                    }

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.End,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        TextButton(
                                            enabled = !isClearingCache,
                                            onClick = { showClearCacheConfirm = true }
                                        ) {
                                            Icon(imageVector = Icons.Default.Delete, contentDescription = "清理")
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text(if (isClearingCache) "清理中" else "清理缓存与 Cookie")
                                        }
                                    }
                                }

                                if (showClearCacheConfirm) {
                                    val currentCacheText = cacheStats?.let {
                                        WebDataManager.formatBytes(it.totalBytes)
                                    } ?: "尚未统计"
                                    AlertDialog(
                                        onDismissRequest = {
                                            if (!isClearingCache) showClearCacheConfirm = false
                                        },
                                        title = { Text("确认清理网页缓存？") },
                                        text = {
                                            Text(
                                                "当前统计缓存约 $currentCacheText。确认后会清理 WebView 缓存、本地数据和 Cookie，已登录的网站可能需要重新登录。"
                                            )
                                        },
                                        confirmButton = {
                                            TextButton(
                                                enabled = !isClearingCache,
                                                onClick = {
                                                    showClearCacheConfirm = false
                                                    clearWebCache()
                                                }
                                            ) {
                                                Text(if (isClearingCache) "清理中" else "确认清理")
                                            }
                                        },
                                        dismissButton = {
                                            TextButton(
                                                enabled = !isClearingCache,
                                                onClick = { showClearCacheConfirm = false }
                                            ) {
                                                Text("取消")
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
                "WEB_FILTERING" -> {
                    WebFilteringSettingsScreen(
                        onFilteringChanged = {
                            quickMode = KioskPrefs.getQuickMode(context)
                            WebViewPool.clear()
                            onSandboxLimitsChanged()
                        }
                    )
                }
                CAPABILITY_ENHANCEMENTS -> {
                    CapabilityEnhancementsOverviewScreen(
                        onOpenLocationEnhancement = { currentSubPage = CAPABILITY_LOCATION },
                        onOpenHighPerformance = { currentSubPage = CAPABILITY_HIGH_PERFORMANCE }
                    )
                }
                CAPABILITY_LOCATION -> {
                    LocationEnhancementDetailScreen(
                        currentSigningIdentity = currentSigningIdentity,
                        onCapabilityChanged = { recreateWebViews ->
                            quickMode = KioskPrefs.getQuickMode(context)
                            if (recreateWebViews) WebViewPool.clear()
                            onSandboxLimitsChanged()
                        }
                    )
                }
                CAPABILITY_HIGH_PERFORMANCE -> {
                    HighPerformanceDetailScreen(
                        webApps = webApps,
                        onCapabilityChanged = { recreateWebViews ->
                            quickMode = KioskPrefs.getQuickMode(context)
                            if (recreateWebViews) WebViewPool.clear()
                            onSandboxLimitsChanged()
                        }
                    )
                }
                "WEBVIEW_PROVIDER" -> {
                    WebViewProviderScreen(
                        snapshot = webViewSnapshot,
                        onRefresh = {
                            refreshWebViewSnapshot()
                            Toast.makeText(context, "WebView 内核信息已重新检测", Toast.LENGTH_SHORT).show()
                        },
                        onCopyDiagnostics = {
                            clipboardManager.setText(AnnotatedString(webViewSnapshot.diagnosticText()))
                            Toast.makeText(context, "WebView 诊断信息已复制", Toast.LENGTH_SHORT).show()
                        },
                        onOpenWebViewUpdate = {
                            openWebViewMarket("com.google.android.webview")
                        },
                        onOpenChromeUpdate = {
                            openWebViewMarket("com.android.chrome")
                        },
                        onOpenSystemSettings = {
                            openWebViewSettings()
                        }
                    )
                }
                "SANDBOX_LIMITS" -> {
                    // 统一提升 State，方便联动与修改
                    var limitAdb by remember { mutableStateOf(KioskPrefs.isLimitAdbEnabled(context)) }
                    var limitSafeBoot by remember { mutableStateOf(KioskPrefs.isLimitSafeBootEnabled(context)) }
                    var limitFactoryReset by remember { mutableStateOf(KioskPrefs.isLimitFactoryResetEnabled(context)) }
                    var limitAddUser by remember { mutableStateOf(KioskPrefs.isLimitAddUserEnabled(context)) }
                    var limitUsbTransfer by remember { mutableStateOf(KioskPrefs.isLimitUsbTransferEnabled(context)) }
                    var limitScreenshot by remember { mutableStateOf(KioskPrefs.isLimitScreenshotEnabled(context)) }
                    var limitStatusBar by remember { mutableStateOf(KioskPrefs.isLimitStatusBarEnabled(context)) }
                    var limitKeyguard by remember { mutableStateOf(KioskPrefs.isLimitKeyguardEnabled(context)) }
                    var limitVoiceAssistants by remember { mutableStateOf(KioskPrefs.isLimitVoiceAssistantsEnabled(context)) }
                    var limitUnknownSources by remember { mutableStateOf(KioskPrefs.isLimitUnknownSourcesEnabled(context)) }

                    var limitFlagSecure by remember { mutableStateOf(KioskPrefs.isLimitFlagSecureEnabled(context)) }
                    var limitVolumeKeys by remember { mutableStateOf(KioskPrefs.isLimitVolumeKeysEnabled(context)) }

                    var limitAdBlock by remember { mutableStateOf(KioskPrefs.isLimitAdBlockEnabled(context)) }
                    var limitDownload by remember { mutableStateOf(KioskPrefs.isLimitDownloadEnabled(context)) }
                    var limitLongClick by remember { mutableStateOf(KioskPrefs.isLimitLongClickEnabled(context)) }
                    var limitImeInput by remember { mutableStateOf(KioskPrefs.isLimitImeInputEnabled(context)) }
                    var limitUrlRedirect by remember { mutableStateOf(KioskPrefs.isLimitUrlRedirectEnabled(context)) }
                    var pullToRefreshEnabled by remember { mutableStateOf(KioskPrefs.isPullToRefreshEnabled(context)) }
                    var floatingBrowserControlsEnabled by remember {
                        mutableStateOf(KioskPrefs.isFloatingBrowserControlsEnabled(context))
                    }
                    var limitGeolocation by remember { mutableStateOf(KioskPrefs.isLimitGeolocationEnabled(context)) }
                    var limitCustomScheme by remember { mutableStateOf(KioskPrefs.isLimitCustomSchemeEnabled(context)) }
                    var schemeBlacklist by remember { mutableStateOf(KioskPrefs.getSchemeBlacklist(context)) }
                    var geolocationBlacklist by remember { mutableStateOf(KioskPrefs.getGeolocationBlacklist(context)) }
                    var limitSslCheck by remember { mutableStateOf(KioskPrefs.isLimitSslCheckEnabled(context)) }
                    var limitMultiWindow by remember { mutableStateOf(KioskPrefs.isLimitMultiWindowEnabled(context)) }
                    var limitFileAccess by remember { mutableStateOf(KioskPrefs.isLimitFileAccessEnabled(context)) }
                    var limitCameraCapture by remember { mutableStateOf(KioskPrefs.isLimitCameraCaptureEnabled(context)) }
                    var limitMicrophoneCapture by remember { mutableStateOf(KioskPrefs.isLimitMicrophoneCaptureEnabled(context)) }
                    var limitFileChooser by remember { mutableStateOf(KioskPrefs.isLimitFileChooserEnabled(context)) }
                    var limitFullscreenVideo by remember { mutableStateOf(KioskPrefs.isLimitFullscreenVideoEnabled(context)) }
                    var cameraBlacklist by remember { mutableStateOf(KioskPrefs.getCameraBlacklist(context)) }
                    var microphoneBlacklist by remember { mutableStateOf(KioskPrefs.getMicrophoneBlacklist(context)) }
                    var fileChooserBlacklist by remember { mutableStateOf(KioskPrefs.getFileChooserBlacklist(context)) }
                    var thirdPartyCookies by remember { mutableStateOf(KioskPrefs.isThirdPartyCookiesEnabled(context)) }
                    var strictMixedContent by remember { mutableStateOf(KioskPrefs.isStrictMixedContentEnabled(context)) }
                    var useBrowserUserAgent by remember { mutableStateOf(KioskPrefs.isUseBrowserUserAgentEnabled(context)) }
                    var customUserAgent by remember { mutableStateOf(KioskPrefs.getCustomUserAgent(context)) }

                    var chromeInspect by remember { mutableStateOf(KioskPrefs.isChromeInspectEnabled(context)) }
                    var debugTool by remember { mutableStateOf(KioskPrefs.getWebDebugTool(context)) }
                    var timingMode by remember { mutableStateOf(KioskPrefs.getInjectTimingMode(context)) }

                    var customJsInjectEnabled by remember { mutableStateOf(KioskPrefs.isCustomJsInjectEnabled(context)) }
                    var customJsInjectTiming by remember { mutableStateOf(KioskPrefs.getCustomJsInjectTiming(context)) }
                    var customJsInjectUrl by remember { mutableStateOf(KioskPrefs.getCustomJsInjectUrl(context)) }
                    var customJsInjectCode by remember { mutableStateOf(KioskPrefs.getCustomJsInjectCode(context)) }

                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        // 1. 🛡️ 系统安全防逃逸限制 (Device Owner 专享)
                        Card(
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                        ) {
                            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(imageVector = Icons.Default.Lock, contentDescription = "系统加固", tint = MaterialTheme.colorScheme.primary)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("🛡️ 系统安全防逃逸限制 (需 Device Owner 激活)", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                                }

                                if (!isDeviceOwner) {
                                    Card(
                                        shape = RoundedCornerShape(8.dp),
                                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.6f)),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text(
                                            text = "⚠️ 设备当前未激活 Device Owner，以下系统级加固选项暂不生效。请使用电脑 ADB 激活企业模式后再行配置。",
                                            fontSize = 12.sp,
                                            color = MaterialTheme.colorScheme.onErrorContainer,
                                            modifier = Modifier.padding(12.dp)
                                        )
                                    }
                                }

                                // 选项 1: 安全模式限制
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("禁用安全模式启动", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                        Text("阻断通过重启设备时长按音量键进入系统「安全模式」逃逸本锁定", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Switch(
                                        checked = limitSafeBoot,
                                        onCheckedChange = {
                                            limitSafeBoot = it
                                            KioskPrefs.setLimitSafeBootEnabled(context, it)
                                            onSandboxLimitsChanged()
                                        }
                                    )
                                }

                                Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))

                                // 选项 3: 恢复出厂限制
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("禁用系统恢复出厂设置", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                        Text("防止通过系统重置来擦除应用及管控配置", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Switch(
                                        checked = limitFactoryReset,
                                        onCheckedChange = {
                                            limitFactoryReset = it
                                            KioskPrefs.setLimitFactoryResetEnabled(context, it)
                                            onSandboxLimitsChanged()
                                        }
                                    )
                                }

                                Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))

                                // 选项 4: 多用户限制
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("禁用创建及切换多用户", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                        Text("防止通过系统访客模式或副账号体系逃离本儿童桌面", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Switch(
                                        checked = limitAddUser,
                                        onCheckedChange = {
                                            limitAddUser = it
                                            KioskPrefs.setLimitAddUserEnabled(context, it)
                                            onSandboxLimitsChanged()
                                        }
                                    )
                                }

                                Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))

                                // 选项 5: USB 文件传输限制
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("禁用 USB 数据及文件传输", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                        Text("阻断通过 USB 连接电脑侧载安装任意其他 APK 的途径", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Switch(
                                        checked = limitUsbTransfer,
                                        onCheckedChange = {
                                            limitUsbTransfer = it
                                            KioskPrefs.setLimitUsbTransferEnabled(context, it)
                                            onSandboxLimitsChanged()
                                        }
                                    )
                                }

                                Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))

                                // 选项 6: 截图限制
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("禁用系统屏幕截图与录屏", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                        Text("禁止使用系统电源键+音量键的硬性截图或录制当前屏幕", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Switch(
                                        checked = limitScreenshot,
                                        onCheckedChange = {
                                            limitScreenshot = it
                                            KioskPrefs.setLimitScreenshotEnabled(context, it)
                                            onSandboxLimitsChanged()
                                        }
                                    )
                                }

                                Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))

                                // 选项 7: 下拉状态栏
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("完全屏蔽下拉系统状态栏", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                        Text("禁止下拉顶栏展开快捷图标，阻断通过通知和设置入口逃逸", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Switch(
                                        checked = limitStatusBar,
                                        onCheckedChange = {
                                            limitStatusBar = it
                                            KioskPrefs.setLimitStatusBarEnabled(context, it)
                                            onSandboxLimitsChanged()
                                        }
                                    )
                                }

                                Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))

                                // 选项 7: 锁屏限制
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("禁用系统锁屏键盘锁 (Keyguard)", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                        Text("使按电源键唤醒设备时直达本主屏幕，免受系统级锁屏干扰", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Switch(
                                        checked = limitKeyguard,
                                        onCheckedChange = {
                                            limitKeyguard = it
                                            KioskPrefs.setLimitKeyguardEnabled(context, it)
                                            onSandboxLimitsChanged()
                                        }
                                    )
                                }

                                Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("禁用语音助手入口", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                        Text("阻断通过系统语音助手、快捷唤醒等入口离开儿童桌面", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Switch(
                                        checked = limitVoiceAssistants,
                                        onCheckedChange = {
                                            limitVoiceAssistants = it
                                            KioskPrefs.setLimitVoiceAssistantsEnabled(context, it)
                                            onSandboxLimitsChanged()
                                        }
                                    )
                                }

                                Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("禁止安装未知来源应用", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                        Text("阻止通过浏览器下载、文件管理器或第三方渠道安装未授权 APK", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Switch(
                                        checked = limitUnknownSources,
                                        onCheckedChange = {
                                            limitUnknownSources = it
                                            KioskPrefs.setLimitUnknownSourcesEnabled(context, it)
                                            onSandboxLimitsChanged()
                                        }
                                    )
                                }

                                Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))

                                // 选项 8: ADB 限制 (USB 调试)
                                val isAdbDisabledByInspect = chromeInspect // 若开启远程 Inspect 调试，强制 ADB 放开限制
                                val finalAdbChecked = if (isAdbDisabledByInspect) false else limitAdb
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("禁用 USB 调试 (ADB)", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                        val summaryText = if (isAdbDisabledByInspect) {
                                            "已因下方启用「USB 远程调试 (Chrome Inspect)」而强制允许连接"
                                        } else {
                                            "防止通过电脑连接 ADB 调试修改或强行停用、卸载本软件"
                                        }
                                        Text(summaryText, fontSize = 11.sp, color = if (isAdbDisabledByInspect) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Switch(
                                        enabled = !isAdbDisabledByInspect,
                                        checked = finalAdbChecked,
                                        onCheckedChange = {
                                            limitAdb = it
                                            KioskPrefs.setLimitAdbEnabled(context, it)
                                            onSandboxLimitsChanged()
                                        }
                                    )
                                }
                            }
                        }

                                                // 2. 🔧 网页调试与开发配置
                        Card(
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                        ) {
                            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(imageVector = Icons.Default.Build, contentDescription = "网页调试", tint = MaterialTheme.colorScheme.primary)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("🔧 网页调试与开发配置", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                                }

                                // 选项 1: USB 远程调试
                                val isInspectDisabledByAdb = limitAdb // 若启用了禁用 ADB，强制不允许开启远程调试
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("启用 USB 远程调试 (Chrome Inspect)", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                        val summaryText = if (isInspectDisabledByAdb) {
                                            "已因上方启用「禁用 USB 调试 (ADB)」而强制关闭"
                                        } else {
                                            "开启后，可连接电脑在 Chrome 输入 chrome://inspect 进行深度网页审查与断点调试"
                                        }
                                        Text(summaryText, fontSize = 11.sp, color = if (isInspectDisabledByAdb) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Switch(
                                        enabled = !limitAdb,
                                        checked = if (limitAdb) false else chromeInspect,
                                        onCheckedChange = {
                                            chromeInspect = it
                                            KioskPrefs.setChromeInspectEnabled(context, it)
                                            if (it) {
                                                // 冲突关联：启用 Inspect 必须放开系统级 ADB 限制
                                                limitAdb = false
                                                KioskPrefs.setLimitAdbEnabled(context, false)
                                            }
                                            onSandboxLimitsChanged()
                                        }
                                    )
                                }

                                Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))

                                // 选项 2: 网页内置调试面板
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text("网页内置调试面板", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                    Text("在移动端屏幕网页中直接显示控制台按钮，方便脱离电脑直接审查网页报错", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(24.dp)
                                    ) {
                                        listOf("NONE" to "无", "VCONSOLE" to "vConsole", "ERUDA" to "Eruda").forEach { (key, label) ->
                                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable {
                                                debugTool = key
                                                KioskPrefs.setWebDebugTool(context, key)
                                                onSandboxLimitsChanged()
                                            }) {
                                                RadioButton(
                                                    selected = debugTool == key,
                                                    onClick = {
                                                        debugTool = key
                                                        KioskPrefs.setWebDebugTool(context, key)
                                                        onSandboxLimitsChanged()
                                                    }
                                                )
                                                Text(label, fontSize = 13.sp)
                                            }
                                        }
                                    }
                                }

                                // 动态展示内置调试面板输入配置
                                if (debugTool != "NONE") {
                                    Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))

                                    // 选项 3: 内置调试工具注入时机
                                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text("调试面板注入时机", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .horizontalScroll(rememberScrollState()),
                                            horizontalArrangement = Arrangement.spacedBy(24.dp)
                                        ) {
                                            listOf(
                                                "BOTH" to "自动兜底 (推荐)",
                                                "PAGE_STARTED" to "页面开始加载",
                                                "PAGE_FINISHED" to "页面加载完成"
                                            ).forEach { (key, label) ->
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    modifier = Modifier
                                                        .widthIn(min = 132.dp)
                                                        .clickable {
                                                            timingMode = key
                                                            KioskPrefs.setInjectTimingMode(context, key)
                                                            onSandboxLimitsChanged()
                                                        }
                                                ) {
                                                    RadioButton(
                                                        selected = timingMode == key,
                                                        onClick = {
                                                            timingMode = key
                                                            KioskPrefs.setInjectTimingMode(context, key)
                                                            onSandboxLimitsChanged()
                                                        }
                                                    )
                                                    Text(label, fontSize = 13.sp)
                                                }
                                            }
                                        }
                                    }

                                    when (debugTool) {
                                        "VCONSOLE" -> {
                                            Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                                            var vconsoleUrl by remember { mutableStateOf(KioskPrefs.getVConsoleCdnUrl(context)) }
                                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                                Text("vConsole CDN 资源地址", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                                OutlinedTextField(
                                                    value = vconsoleUrl,
                                                    onValueChange = {
                                                        vconsoleUrl = it
                                                        KioskPrefs.setVConsoleCdnUrl(context, it)
                                                    },
                                                    modifier = Modifier.fillMaxWidth(),
                                                    singleLine = true,
                                                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                                                )
                                                TextButton(
                                                    onClick = {
                                                        val defaultUrl = "https://unpkg.com/vconsole@latest/dist/vconsole.min.js"
                                                        vconsoleUrl = defaultUrl
                                                        KioskPrefs.setVConsoleCdnUrl(context, defaultUrl)
                                                    },
                                                    modifier = Modifier.align(Alignment.End)
                                                ) {
                                                    Text("重置为默认 CDN 地址", fontSize = 12.sp)
                                                }
                                            }
                                        }
                                        "ERUDA" -> {
                                            Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                                            var erudaUrl by remember { mutableStateOf(KioskPrefs.getErudaCdnUrl(context)) }
                                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                                Text("Eruda CDN 资源地址", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                                OutlinedTextField(
                                                    value = erudaUrl,
                                                    onValueChange = {
                                                        erudaUrl = it
                                                        KioskPrefs.setErudaCdnUrl(context, it)
                                                    },
                                                    modifier = Modifier.fillMaxWidth(),
                                                    singleLine = true,
                                                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                                                )
                                                TextButton(
                                                    onClick = {
                                                        val defaultUrl = "https://cdn.jsdelivr.net/npm/eruda"
                                                        erudaUrl = defaultUrl
                                                        KioskPrefs.setErudaCdnUrl(context, defaultUrl)
                                                    },
                                                    modifier = Modifier.align(Alignment.End)
                                                ) {
                                                    Text("重置为默认 CDN 地址", fontSize = 12.sp)
                                                }
                                            }
                                        }
                                    }
                                }

                                Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))

                                // 选项 5: 自定义独立 JS 注入开关
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("注入自定义开发脚本", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                        Text("允许在页面加载时注入管理员自定义编写的 JS 代码或外部 JS 链接，可与 vConsole/Eruda 共存", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Switch(
                                        checked = customJsInjectEnabled,
                                        onCheckedChange = {
                                            customJsInjectEnabled = it
                                            KioskPrefs.setCustomJsInjectEnabled(context, it)
                                            onSandboxLimitsChanged()
                                        }
                                    )
                                }

                                if (customJsInjectEnabled) {
                                    // 自定义脚本的注入时机
                                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text("自定义脚本注入时机", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .horizontalScroll(rememberScrollState()),
                                            horizontalArrangement = Arrangement.spacedBy(24.dp)
                                        ) {
                                            listOf(
                                                "BOTH" to "自动兜底 (推荐)",
                                                "PAGE_STARTED" to "页面开始加载",
                                                "PAGE_FINISHED" to "页面加载完成"
                                            ).forEach { (key, label) ->
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    modifier = Modifier
                                                        .widthIn(min = 132.dp)
                                                        .clickable {
                                                            customJsInjectTiming = key
                                                            KioskPrefs.setCustomJsInjectTiming(context, key)
                                                            onSandboxLimitsChanged()
                                                        }
                                                ) {
                                                    RadioButton(
                                                        selected = customJsInjectTiming == key,
                                                        onClick = {
                                                            customJsInjectTiming = key
                                                            KioskPrefs.setCustomJsInjectTiming(context, key)
                                                            onSandboxLimitsChanged()
                                                        }
                                                    )
                                                    Text(label, fontSize = 13.sp)
                                                }
                                            }
                                        }
                                    }

                                    // 外部 JS 链接 (URL)
                                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text("自定义外部 JS 脚本链接 (URL)", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                        Text("非空时将自动动态载入该外链 JS。若无需载入外部脚本请留空", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        OutlinedTextField(
                                            value = customJsInjectUrl,
                                            onValueChange = {
                                                customJsInjectUrl = it
                                                KioskPrefs.setCustomJsInjectUrl(context, it)
                                                onSandboxLimitsChanged()
                                            },
                                            modifier = Modifier.fillMaxWidth(),
                                            singleLine = true,
                                            placeholder = { Text("https://assets.anzz.site/custom.js", fontSize = 12.sp) },
                                            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                                        )
                                    }

                                    // 自定义 JS 代码段
                                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text("自定义注入 JavaScript 代码", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                        Text("将在设定注入时机时，作为自执行函数注入并执行。如果在上方配置了外部 JS 链接，则会等待外链脚本加载成功后 (onload) 再执行本代码段", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        OutlinedTextField(
                                            value = customJsInjectCode,
                                            onValueChange = {
                                                customJsInjectCode = it
                                                KioskPrefs.setCustomJsInjectCode(context, it)
                                                onSandboxLimitsChanged()
                                            },
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(140.dp),
                                            placeholder = { Text("// 在此输入您的 Javascript 代码...", fontSize = 12.sp) },
                                            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                                        )
                                    }
                                }
                            }
                        }

                                                // 3. 📱 界面与物理按键限制
                        Card(
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                        ) {
                            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(imageVector = Icons.Default.Settings, contentDescription = "物理与界面限制", tint = MaterialTheme.colorScheme.primary)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("📱 界面与物理按键限制", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                                }

                                // 选项 1: FLAG_SECURE 防截屏/录像
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("防止截屏显示 (FLAG_SECURE)", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                        Text("将窗口标志设为安全，使系统级别的截屏或录屏输出为黑色画布", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Switch(
                                        checked = limitFlagSecure,
                                        onCheckedChange = {
                                            limitFlagSecure = it
                                            KioskPrefs.setLimitFlagSecureEnabled(context, it)
                                            onSandboxLimitsChanged()
                                        }
                                    )
                                }

                                Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))

                                // 选项 2: 拦截声音物理按键
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("物理音量加减按键锁定", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                        Text("拦截并消费物理音量键，防止儿童在主页及网页误触把声音调过大或静音", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Switch(
                                        checked = limitVolumeKeys,
                                        onCheckedChange = {
                                            limitVolumeKeys = it
                                            KioskPrefs.setLimitVolumeKeysEnabled(context, it)
                                            onSandboxLimitsChanged()
                                        }
                                    )
                                }
                            }
                        }

                                                // 4. 🌐 网页浏览器沙箱限制
                        Card(
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                        ) {
                            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(imageVector = Icons.Default.Build, contentDescription = "浏览器沙箱", tint = MaterialTheme.colorScheme.primary)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("🌐 网页浏览器沙箱限制", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                                }

                                Text(
                                    "默认按移动浏览器兼容基线放开网页能力，优先保证页面渲染与交互正常；如需更严格的儿童安全限制，可在这里逐项开启。",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )

                                Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("注册并设置为默认浏览器", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                        Text("本应用已声明可打开 http/https 链接。点击后进入系统默认浏览器设置，系统会决定是否允许设为默认浏览器", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    OutlinedButton(
                                        onClick = { openDefaultBrowserSettings() },
                                        shape = RoundedCornerShape(12.dp)
                                    ) {
                                        Icon(imageVector = Icons.Default.OpenInBrowser, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("去设置", fontSize = 12.sp)
                                    }
                                }

                                Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("网页悬浮球操作入口", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                        Text("正常模式默认开启。打开后网页内可通过悬浮球输入网址、查看当前 URL、后退、前进、刷新或停止加载", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Switch(
                                        checked = floatingBrowserControlsEnabled,
                                        onCheckedChange = {
                                            floatingBrowserControlsEnabled = it
                                            KioskPrefs.setFloatingBrowserControlsEnabled(context, it)
                                            quickMode = KioskPrefs.getQuickMode(context)
                                            Toast.makeText(context, "新打开的网站生效", Toast.LENGTH_SHORT).show()
                                        }
                                    )
                                }

                                Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("页面顶部下拉刷新", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                        Text("默认关闭。开启后在网页已到顶部时继续下拉并松手刷新；全屏网页或复杂 Web 应用建议保持关闭", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Switch(
                                        checked = pullToRefreshEnabled,
                                        onCheckedChange = {
                                            pullToRefreshEnabled = it
                                            KioskPrefs.setPullToRefreshEnabled(context, it)
                                            onSandboxLimitsChanged()
                                            Toast.makeText(context, "新打开的网站生效", Toast.LENGTH_SHORT).show()
                                        }
                                    )
                                }

                                Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))

                                // 选项 1: 广告过滤
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("网页广告与弹窗过滤", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                        Text("默认关闭以避免误拦截脚本、样式、字体等子资源；开启后会在网络拦截层阻断疑似广告与弹窗请求", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Switch(
                                        checked = limitAdBlock,
                                        onCheckedChange = {
                                            limitAdBlock = it
                                            KioskPrefs.setLimitAdBlockEnabled(context, it)
                                            onSandboxLimitsChanged()
                                        }
                                    )
                                }

                                Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))

                                // 选项 2: 下载限制
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("完全禁用网页文件下载", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                        Text("默认允许并交给系统下载管理器处理；开启后阻断网页下载，防止儿童保存未知文件或安装包", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Switch(
                                        checked = limitDownload,
                                        onCheckedChange = {
                                            limitDownload = it
                                            KioskPrefs.setLimitDownloadEnabled(context, it)
                                            onSandboxLimitsChanged()
                                        }
                                    )
                                }

                                Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))

                                // 选项 3: 长按限制
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("禁用长按文本选择与复制", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                        Text("默认允许，保持阅读、复制、图片保存等浏览器交互；开启后阻断系统长按工具条", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Switch(
                                        checked = limitLongClick,
                                        onCheckedChange = {
                                            limitLongClick = it
                                            KioskPrefs.setLimitLongClickEnabled(context, it)
                                            onSandboxLimitsChanged()
                                        }
                                    )
                                }

                                Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))

                                // 选项: 输入法限制
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("限制输入法调起", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                        Text("默认允许输入框调起系统输入法；开启后阻止网页输入框触发键盘弹起", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Switch(
                                        checked = limitImeInput,
                                        onCheckedChange = {
                                            limitImeInput = it
                                            KioskPrefs.setLimitImeInputEnabled(context, it)
                                            onSandboxLimitsChanged()
                                        }
                                    )
                                }

                                Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))

                                // 选项 4: 域名限制跳转
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("仅允许白名单域名跳转", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                        Text("默认允许正常跨域导航、CDN 与 OAuth 跳转；开启后把主页面跳转限制在当前域名及其子域名内", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Switch(
                                        checked = limitUrlRedirect,
                                        onCheckedChange = {
                                            limitUrlRedirect = it
                                            KioskPrefs.setLimitUrlRedirectEnabled(context, it)
                                            onSandboxLimitsChanged()
                                        }
                                    )
                                }

                                Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))

                                // 选项 5: 地理位置限制
                                Column(modifier = Modifier.fillMaxWidth()) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text("禁用网页定位 (Geolocation)", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                            Text("默认按浏览器能力允许网页申请定位；开启后直接拒绝网页地理位置权限，保护儿童隐私", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                        Switch(
                                            checked = limitGeolocation,
                                            onCheckedChange = {
                                                limitGeolocation = it
                                                KioskPrefs.setLimitGeolocationEnabled(context, it)
                                                if (it) WebViewPool.clear()
                                                onSandboxLimitsChanged()
                                            }
                                        )
                                    }

                                    if (!limitGeolocation && geolocationBlacklist.isNotEmpty()) {
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Text("定位黑名单 (已彻底禁止获取位置的域名)：", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                                        Spacer(modifier = Modifier.height(6.dp))
                                        Column(
                                            verticalArrangement = Arrangement.spacedBy(6.dp),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            geolocationBlacklist.chunked(2).forEach { rowItems ->
                                                Row(
                                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                                    modifier = Modifier.fillMaxWidth()
                                                ) {
                                                     rowItems.forEach { item ->
                                                         Row(
                                                             modifier = Modifier
                                                                 .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f), shape = RoundedCornerShape(8.dp))
                                                                 .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f), RoundedCornerShape(8.dp))
                                                                 .padding(horizontal = 10.dp, vertical = 5.dp)
                                                                 .weight(1f),
                                                             verticalAlignment = Alignment.CenterVertically,
                                                             horizontalArrangement = Arrangement.SpaceBetween
                                                         ) {
                                                             Text(
                                                                 text = item,
                                                                 fontSize = 11.sp,
                                                                 color = MaterialTheme.colorScheme.onSurface,
                                                                 maxLines = 1,
                                                                 overflow = TextOverflow.Ellipsis,
                                                                 modifier = Modifier.weight(1f)
                                                             )
                                                             Spacer(modifier = Modifier.width(4.dp))
                                                             Icon(
                                                                 imageVector = Icons.Default.Close,
                                                                 contentDescription = "移除",
                                                                 tint = MaterialTheme.colorScheme.error,
                                                                 modifier = Modifier
                                                                     .size(16.dp)
                                                                     .clickable {
                                                                         val newList = geolocationBlacklist.toMutableSet()
                                                                         newList.remove(item)
                                                                         geolocationBlacklist = newList
                                                                         KioskPrefs.setGeolocationBlacklist(context, newList)
                                                                         onSandboxLimitsChanged()
                                                                     }
                                                             )
                                                         }
                                                     }
                                                     if (rowItems.size < 2) {
                                                         Spacer(modifier = Modifier.weight(1f))
                                                     }
                                                }
                                            }
                                        }
                                    }

                                }

                                Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))

                                // 选项 5-2: 禁用自定义 Scheme 协议
                                Column(modifier = Modifier.fillMaxWidth()) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text("禁用自定义 Scheme 调起外部 App", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                            Text("开启后强行静默拦截所有非 Web 协议（如 weixin://, alipays:// 等）；关闭后允许询问跳转或根据黑名单过滤", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                        Switch(
                                            checked = limitCustomScheme,
                                            onCheckedChange = {
                                                limitCustomScheme = it
                                                KioskPrefs.setLimitCustomSchemeEnabled(context, it)
                                                onSandboxLimitsChanged()
                                            }
                                        )
                                    }

                                    if (!limitCustomScheme && schemeBlacklist.isNotEmpty()) {
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Text("Scheme 协议黑名单 (已彻底拒绝调起的协议)：", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                                        Spacer(modifier = Modifier.height(6.dp))
                                        Column(
                                            verticalArrangement = Arrangement.spacedBy(6.dp),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            schemeBlacklist.chunked(3).forEach { rowItems ->
                                                Row(
                                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                                    modifier = Modifier.fillMaxWidth()
                                                ) {
                                                     rowItems.forEach { item ->
                                                         Row(
                                                             modifier = Modifier
                                                                 .background(MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(8.dp))
                                                                 .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f), RoundedCornerShape(8.dp))
                                                                 .padding(horizontal = 8.dp, vertical = 5.dp)
                                                                 .weight(1f),
                                                             verticalAlignment = Alignment.CenterVertically,
                                                             horizontalArrangement = Arrangement.SpaceBetween
                                                         ) {
                                                             Text(
                                                                 text = "$item://",
                                                                 fontSize = 11.sp,
                                                                 color = MaterialTheme.colorScheme.onSurface,
                                                                 maxLines = 1,
                                                                 overflow = TextOverflow.Ellipsis,
                                                                 modifier = Modifier.weight(1f)
                                                             )
                                                             Spacer(modifier = Modifier.width(4.dp))
                                                             Icon(
                                                                 imageVector = Icons.Default.Close,
                                                                 contentDescription = "移除",
                                                                 tint = MaterialTheme.colorScheme.error,
                                                                 modifier = Modifier
                                                                     .size(16.dp)
                                                                     .clickable {
                                                                         val newList = schemeBlacklist.toMutableSet()
                                                                         newList.remove(item)
                                                                         schemeBlacklist = newList
                                                                         KioskPrefs.setSchemeBlacklist(context, newList)
                                                                         onSandboxLimitsChanged()
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
                                }

                                Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))

                                // 选项 6: 摄像头限制
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("禁用网页摄像头", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                        Text("默认允许可信网页在用户确认后申请摄像头；开启后拒绝 WebRTC 视频、拍照、扫码等摄像头请求", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Switch(
                                        checked = limitCameraCapture,
                                        onCheckedChange = {
                                            limitCameraCapture = it
                                            KioskPrefs.setLimitCameraCaptureEnabled(context, it)
                                            onSandboxLimitsChanged()
                                        }
                                    )
                                }

                                if (!limitCameraCapture && cameraBlacklist.isNotEmpty()) {
                                    PermissionBlacklistChips(
                                        title = "摄像头黑名单：",
                                        items = cameraBlacklist,
                                        columns = 2,
                                        onRemove = { item ->
                                            val newList = cameraBlacklist.toMutableSet()
                                            newList.remove(item)
                                            cameraBlacklist = newList
                                            KioskPrefs.setCameraBlacklist(context, newList)
                                            onSandboxLimitsChanged()
                                        }
                                    )
                                }

                                Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("禁用网页麦克风", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                        Text("默认允许可信网页在用户确认后申请麦克风；开启后拒绝 WebRTC 语音、录音、语音输入等请求", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Switch(
                                        checked = limitMicrophoneCapture,
                                        onCheckedChange = {
                                            limitMicrophoneCapture = it
                                            KioskPrefs.setLimitMicrophoneCaptureEnabled(context, it)
                                            onSandboxLimitsChanged()
                                        }
                                    )
                                }

                                if (!limitMicrophoneCapture && microphoneBlacklist.isNotEmpty()) {
                                    PermissionBlacklistChips(
                                        title = "麦克风黑名单：",
                                        items = microphoneBlacklist,
                                        columns = 2,
                                        onRemove = { item ->
                                            val newList = microphoneBlacklist.toMutableSet()
                                            newList.remove(item)
                                            microphoneBlacklist = newList
                                            KioskPrefs.setMicrophoneBlacklist(context, newList)
                                            onSandboxLimitsChanged()
                                        }
                                    )
                                }

                                Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("禁用网页文件选择/上传", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                        Text("默认允许可信网页在用户确认后打开系统文件选择器；开启后拒绝图片、视频或文件上传入口", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Switch(
                                        checked = limitFileChooser,
                                        onCheckedChange = {
                                            limitFileChooser = it
                                            KioskPrefs.setLimitFileChooserEnabled(context, it)
                                            onSandboxLimitsChanged()
                                        }
                                    )
                                }

                                if (!limitFileChooser && fileChooserBlacklist.isNotEmpty()) {
                                    PermissionBlacklistChips(
                                        title = "文件选择黑名单：",
                                        items = fileChooserBlacklist,
                                        columns = 2,
                                        onRemove = { item ->
                                            val newList = fileChooserBlacklist.toMutableSet()
                                            newList.remove(item)
                                            fileChooserBlacklist = newList
                                            KioskPrefs.setFileChooserBlacklist(context, newList)
                                            onSandboxLimitsChanged()
                                        }
                                    )
                                }

                                Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("禁用网页全屏视频", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                        Text("默认允许网页视频进入全屏播放；开启后拒绝网页自定义全屏视图，防止覆盖浏览控制", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Switch(
                                        checked = limitFullscreenVideo,
                                        onCheckedChange = {
                                            limitFullscreenVideo = it
                                            KioskPrefs.setLimitFullscreenVideoEnabled(context, it)
                                            onSandboxLimitsChanged()
                                        }
                                    )
                                }

                                Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))

                                // 选项 7: 强制 SSL 校验
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("强制网页 SSL 连接安全校验", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                        Text("证书异常时直接强制安全阻断，不提供「忽略并继续访问」的逃逸入口", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Switch(
                                        checked = limitSslCheck,
                                        onCheckedChange = {
                                            limitSslCheck = it
                                            KioskPrefs.setLimitSslCheckEnabled(context, it)
                                            onSandboxLimitsChanged()
                                        }
                                    )
                                }

                                Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))

                                // 选项 8: 禁止弹窗新窗口
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("禁止网页自动弹出新窗口", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                        Text("默认允许并在 App 内 WebView 栈承载新窗口；开启后拦截 window.open() 与 target=_blank 等新开请求", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Switch(
                                        checked = limitMultiWindow,
                                        onCheckedChange = {
                                            limitMultiWindow = it
                                            KioskPrefs.setLimitMultiWindowEnabled(context, it)
                                            onSandboxLimitsChanged()
                                        }
                                    )
                                }

                                Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))

                                // 选项 9: 禁止文件系统访问
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("禁止网页读取本地文件系统", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                        Text("默认允许 WebView 标准 file/content 能力；开启后限制 file:// 与 content:// 访问，可能影响本地文件预览或上传", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Switch(
                                        checked = limitFileAccess,
                                        onCheckedChange = {
                                            limitFileAccess = it
                                            KioskPrefs.setLimitFileAccessEnabled(context, it)
                                            onSandboxLimitsChanged()
                                        }
                                    )
                                }

                                Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("允许第三方 Cookie", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                        Text("提升登录、嵌入组件、跨域资源鉴权等现代网页兼容性；关闭后部分页面可能只能渲染局部内容", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Switch(
                                        checked = thirdPartyCookies,
                                        onCheckedChange = {
                                            thirdPartyCookies = it
                                            KioskPrefs.setThirdPartyCookiesEnabled(context, it)
                                            onSandboxLimitsChanged()
                                        }
                                    )
                                }

                                Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("严格阻止 HTTPS 页面混合内容", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                        Text("开启后 HTTPS 页面会阻止 HTTP 子资源；为兼容旧网页默认使用 WebView 兼容模式", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Switch(
                                        checked = strictMixedContent,
                                        onCheckedChange = {
                                            strictMixedContent = it
                                            KioskPrefs.setStrictMixedContentEnabled(context, it)
                                            onSandboxLimitsChanged()
                                        }
                                    )
                                }

                                Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))

                                val defaultUserAgent = remember {
                                    runCatching { android.webkit.WebSettings.getDefaultUserAgent(context) }
                                        .getOrDefault("")
                                }
                                val effectiveUserAgent = remember(useBrowserUserAgent, customUserAgent, defaultUserAgent) {
                                    WebViewRuntime.resolveUserAgent(
                                        defaultUserAgent,
                                        KioskPrefs.getWebViewRuntimeConfig(context).copy(
                                            useBrowserUserAgent = useBrowserUserAgent,
                                            customUserAgent = customUserAgent
                                        )
                                    )
                                }
                                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text("使用手机浏览器 User-Agent", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                            Text("移除 WebView 专属标识；如填写自定义 UA，将优先使用自定义内容", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                        Switch(
                                            checked = useBrowserUserAgent,
                                            onCheckedChange = {
                                                useBrowserUserAgent = it
                                                KioskPrefs.setUseBrowserUserAgentEnabled(context, it)
                                                onSandboxLimitsChanged()
                                            }
                                        )
                                    }

                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.65f))
                                            .padding(12.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Text("当前实际 UA", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                        Text(
                                            text = effectiveUserAgent.ifBlank { "无法读取" },
                                            fontSize = 11.sp,
                                            fontFamily = FontFamily.Monospace,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f))
                                        Text("系统默认 WebView UA", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                        Text(
                                            text = defaultUserAgent.ifBlank { "无法读取" },
                                            fontSize = 11.sp,
                                            fontFamily = FontFamily.Monospace,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }

                                    OutlinedTextField(
                                        value = customUserAgent,
                                        onValueChange = {
                                            customUserAgent = it
                                            KioskPrefs.setCustomUserAgent(context, it)
                                            onSandboxLimitsChanged()
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                        label = { Text("自定义 User-Agent（可选）") },
                                        placeholder = { Text("留空则按上方开关自动生成", fontSize = 12.sp) },
                                        minLines = 2,
                                        maxLines = 4,
                                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                                    )
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.End
                                    ) {
                                        TextButton(
                                            enabled = customUserAgent.isNotBlank(),
                                            onClick = {
                                                customUserAgent = ""
                                                KioskPrefs.setCustomUserAgent(context, "")
                                                onSandboxLimitsChanged()
                                            }
                                        ) {
                                            Text("清空自定义 UA", fontSize = 12.sp)
                                        }
                                    }
                                }
                            }
                        }


                    }
                }
                "WHITELIST" -> {
                    // 应用白名单二级单独页面，带分类 Tab 过滤和展示
                    var selectedSubCategory by remember { mutableStateOf("ALL") }
                    val categories = listOf(
                        "ALL" to "全部",
                        WebAppEntity.CATEGORY_GAME to "游戏",
                        WebAppEntity.CATEGORY_VIDEO to "视频",
                        WebAppEntity.CATEGORY_BOOK to "绘本",
                        WebAppEntity.CATEGORY_STUDY to "学习",
                        WebAppEntity.CATEGORY_TOOL to "工具",
                        WebAppEntity.CATEGORY_OTHER to "其他"
                    )

                    val filteredApps = remember(webApps, selectedSubCategory) {
                        if (selectedSubCategory == "ALL") {
                            webApps
                        } else {
                            webApps.filter { it.category == selectedSubCategory }
                        }
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        WhitelistSubscriptionCard(
                            url = whitelistSubscriptionUrl,
                            onUrlChange = { whitelistSubscriptionUrl = it },
                            autoRefresh = whitelistAutoRefresh,
                            onAutoRefreshChange = { whitelistAutoRefresh = it },
                            intervalHours = whitelistRefreshIntervalText,
                            onIntervalHoursChange = { input ->
                                whitelistRefreshIntervalText = input.filter { it.isDigit() }.take(3)
                            },
                            title = whitelistSubscriptionTitle,
                            lastSuccessAt = whitelistLastSuccessAt,
                            importedCount = whitelistImportedCount,
                            subscribedRowCount = webApps.count { it.sourceType == WebAppEntity.SOURCE_SUBSCRIPTION },
                            lastError = whitelistLastError,
                            isRefreshing = isRefreshingWhitelistSubscription,
                            onSave = {
                                if (saveWhitelistSubscriptionSettings()) {
                                    Toast.makeText(context, "白名单订阅设置已保存", Toast.LENGTH_SHORT).show()
                                }
                            },
                            onRefresh = {
                                if (saveWhitelistSubscriptionSettings()) {
                                    isRefreshingWhitelistSubscription = true
                                    scope.launch {
                                        val result = runCatching {
                                            withContext(Dispatchers.IO) {
                                                WhitelistSubscriptionRepository.refreshNow(context)
                                            }
                                        }
                                        isRefreshingWhitelistSubscription = false
                                        reloadWhitelistSubscriptionState()
                                        result.onSuccess {
                                            Toast.makeText(
                                                context,
                                                "订阅已刷新：导入 ${it.importedCount} 个，跳过 ${it.skippedCount} 个",
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        }.onFailure {
                                            Toast.makeText(
                                                context,
                                                it.message ?: "白名单订阅刷新失败",
                                                Toast.LENGTH_LONG
                                            ).show()
                                        }
                                    }
                                }
                            }
                        )

                        ScrollableTabRow(
                            selectedTabIndex = categories.indexOfFirst { it.first == selectedSubCategory }.coerceAtLeast(0),
                            edgePadding = 0.dp,
                            containerColor = Color.Transparent,
                            divider = {}
                        ) {
                            categories.forEach { (catKey, catName) ->
                                Tab(
                                    selected = selectedSubCategory == catKey,
                                    onClick = { selectedSubCategory = catKey },
                                    text = { Text(catName, fontWeight = FontWeight.Bold) }
                                )
                            }
                        }

                        if (filteredApps.isEmpty()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 160.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("此分类下暂无应用，点击右下角按钮添加！", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        } else {
                            filteredApps.forEach { app ->
                                WebAppCard(
                                    app = app,
                                    canEdit = app.sourceType != WebAppEntity.SOURCE_SUBSCRIPTION,
                                    canDelete = app.sourceType != WebAppEntity.SOURCE_SUBSCRIPTION,
                                    onEdit = {
                                        if (app.sourceType == WebAppEntity.SOURCE_SUBSCRIPTION) {
                                            Toast.makeText(context, "订阅网站请在订阅源中编辑", Toast.LENGTH_SHORT).show()
                                        } else {
                                            editingWebApp = app
                                        }
                                    },
                                    onDelete = {
                                        if (app.sourceType == WebAppEntity.SOURCE_SUBSCRIPTION) {
                                            Toast.makeText(context, "订阅网站请在订阅源中移除", Toast.LENGTH_SHORT).show()
                                        } else {
                                            scope.launch(Dispatchers.IO) {
                                                db.webAppDao().deleteWebApp(app)
                                            }
                                            Toast.makeText(context, "已删除应用", Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    onToggleEnabled = { enabled ->
                                        scope.launch(Dispatchers.IO) {
                                            db.webAppDao().updateWebApp(app.copy(isEnabled = enabled))
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
                "HISTORY" -> {
                    BrowserHistoryScreen(
                        history = browserHistory,
                        onOpen = { item ->
                            val intent = Intent(context, WebViewActivity::class.java).apply {
                                putExtra(WebViewActivity.EXTRA_CUSTOM_URL, item.url)
                                putExtra(WebViewActivity.EXTRA_ALLOW_HIGH_PERFORMANCE_RESOURCE_RESTART, true)
                                putExtra(WebViewActivity.EXTRA_ORIENTATION_MODE, KioskPrefs.getOrientationMode(context))
                                KioskPrefs.putWebViewRuntimeConfig(this, context, normalSystemBars)
                            }
                            context.startActivity(intent)
                        },
                        onAddToWhitelist = { item ->
                            editingWebApp = null
                            showAddDialog = false
                            addingInitialWebApp = null
                            scope.launch {
                                val existing = withContext(Dispatchers.IO) {
                                    db.webAppDao().getAllWebApps().firstOrNull { app ->
                                        normalizeHistoryUrl(app.url) == normalizeHistoryUrl(item.url)
                                    }
                                }
                                if (existing != null) {
                                    editingWebApp = existing
                                } else {
                                    addingInitialWebApp = WebAppEntity(
                                        title = item.title,
                                        url = item.url,
                                        iconPath = null,
                                        isPreset = false,
                                        isEnabled = true,
                                        category = WebAppEntity.CATEGORY_OTHER,
                                        sourceType = WebAppEntity.SOURCE_LOCAL
                                    )
                                }
                            }
                        },
                        onDelete = { item ->
                            scope.launch(Dispatchers.IO) {
                                db.browserHistoryDao().deleteById(item.id)
                            }
                        },
                        onClearAll = {
                            scope.launch(Dispatchers.IO) {
                                db.browserHistoryDao().clearAll()
                            }
                            Toast.makeText(context, "浏览历史已清空", Toast.LENGTH_SHORT).show()
                        }
                    )
                }
            }
        }
    }

    // 添加 / 编辑 Web 应用 Dialog
    if (showAddDialog || editingWebApp != null || addingInitialWebApp != null) {
        val appToEdit = editingWebApp
        val initialApp = addingInitialWebApp
        AddEditWebAppDialog(
            app = appToEdit,
            initialTitle = initialApp?.title.orEmpty(),
            initialUrl = initialApp?.url.orEmpty(),
            initialCategory = initialApp?.category ?: WebAppEntity.CATEGORY_GAME,
            onDismiss = {
                showAddDialog = false
                addingInitialWebApp = null
                editingWebApp = null
            },
            onSave = { title, url, icon, category ->
                scope.launch(Dispatchers.IO) {
                    val frozenIcon = WebAppIconCache.freezeNetworkIcon(context, icon, url)
                    if (appToEdit == null) {
                        db.webAppDao().insertWebApp(
                            WebAppEntity(title = title, url = url, iconPath = frozenIcon, isPreset = false, category = category)
                        )
                    } else {
                        db.webAppDao().updateWebApp(
                            appToEdit.copy(title = title, url = url, iconPath = frozenIcon, category = category)
                        )
                    }
                }
                showAddDialog = false
                addingInitialWebApp = null
                editingWebApp = null
            }
        )
    }

    // 设置 PIN 码对话框
    if (showPinSetupDialog) {
        PinSetupDialog(
            onDismiss = { showPinSetupDialog = false },
            onSave = { newPin ->
                scope.launch(Dispatchers.IO) {
                    val current = db.systemConfigDao().getSystemConfig() ?: SystemConfigEntity()
                    db.systemConfigDao().insertOrUpdateConfig(
                        current.copy(
                            pinHash = HashUtils.sha256(newPin),
                            verificationMode = "PIN" // 自动切换
                        )
                    )
                }
                showPinSetupDialog = false
                verificationMode = "PIN"
                Toast.makeText(context, "密码设置成功！已自动切换为 PIN 码模式", Toast.LENGTH_SHORT).show()
            }
        )
    }

    // 检查更新对话框
    if (showUpdateDialog && latestReleaseInfo != null) {
        UpdateDialog(
            releaseInfo = latestReleaseInfo!!,
            onDismiss = { showUpdateDialog = false }
        )
    }
}

/**
 * 快速模式选择卡片。
 */
@Composable
fun QuickModeCard(
    quickMode: String,
    onModeSelected: (String) -> Unit
) {
    val options = listOf(
        KioskPrefs.QUICK_MODE_NORMAL to Triple(
            "正常模式",
            "单击打开管理菜单，无认证无软锁，网页保持浏览器兼容默认。",
            Icons.Default.Home
        ),
        KioskPrefs.QUICK_MODE_CHILD to Triple(
            "儿童模式",
            "隐藏管理入口，启用认证、软锁和网页/系统限制。",
            Icons.Default.ChildCare
        ),
        KioskPrefs.QUICK_MODE_DEBUG to Triple(
            "调试模式",
            "放开限制并开启 Chrome Inspect 与内置调试面板。",
            Icons.Default.BugReport
        ),
        KioskPrefs.QUICK_MODE_CUSTOM to Triple(
            "自定义模式",
            "保留当前细项配置，不批量重置任何选项。",
            Icons.Default.Tune
        )
    )

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f))
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.AutoFixHigh,
                    contentDescription = "快速模式",
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("快速模式", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Text(
                        "当前：${quickModeLabel(quickMode)}",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Text(
                "模式只批量调整锁定、认证、沙箱和调试相关选项，不会改变屏幕方向、图标大小、标题或白名单。",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            options.forEach { (mode, meta) ->
                QuickModeOption(
                    selected = quickMode == mode,
                    title = meta.first,
                    desc = meta.second,
                    icon = meta.third,
                    onClick = { onModeSelected(mode) }
                )
            }
        }
    }
}

@Composable
private fun QuickModeOption(
    selected: Boolean,
    title: String,
    desc: String,
    icon: ImageVector,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (selected) {
                    MaterialTheme.colorScheme.surface.copy(alpha = 0.75f)
                } else {
                    Color.Transparent
                }
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Top
    ) {
        RadioButton(
            selected = selected,
            onClick = onClick,
            modifier = Modifier.padding(top = 2.dp)
        )
        Icon(
            imageVector = icon,
            contentDescription = title,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .padding(top = 8.dp, end = 8.dp)
                .size(20.dp)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Text(desc, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun quickModeLabel(mode: String): String {
    return when (mode) {
        KioskPrefs.QUICK_MODE_CHILD -> "儿童模式"
        KioskPrefs.QUICK_MODE_DEBUG -> "调试模式"
        KioskPrefs.QUICK_MODE_CUSTOM -> "自定义模式"
        else -> "正常模式"
    }
}

@Composable
private fun WebFilteringSettingsScreen(
    onFilteringChanged: () -> Unit
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    var settingsVersion by remember { mutableStateOf(0) }
      var diagnosticsRefreshVersion by remember { mutableStateOf(0) }
      val settings = remember(settingsVersion) { FilterRepository.getSettings(context) }
      val filteringEnabled = remember(settingsVersion) {
          KioskPrefs.getOrMigrateLimitAdBlockEnabled(context, settings.enabled)
      }
      val runtimeSnapshot = remember(settingsVersion, filteringEnabled) {
          settings.toRuntimeSnapshot().copy(enabled = filteringEnabled)
      }
      val engineUiState by rememberWebFilteringEngineUiState(context, runtimeSnapshot, settingsVersion)
      val engine = engineUiState.engine
      val report = engine.report
      val localPerfSnapshot = remember(engine, diagnosticsRefreshVersion) {
          engine.perfSnapshot()
      }
    val webViewPerfSnapshot = remember(settingsVersion, diagnosticsRefreshVersion) {
        FilterRepository.getLatestPerfSnapshot(context, runtimeSnapshot)
    }
    val perfSnapshot = webViewPerfSnapshot?.snapshot ?: localPerfSnapshot
    val perfSourceLabel = if (webViewPerfSnapshot != null) {
        "WebView 进程"
    } else {
        "后台进程"
      }
      var customRules by remember(settings.customRules) { mutableStateOf(settings.customRules) }
      val customRuleValidation by rememberCustomRuleValidationUiState(customRules)
      val customRuleReport = customRuleValidation.report
      val customRulesByteCount = remember(customRules) { customRules.toByteArray(Charsets.UTF_8).size }
      val customRulesTooLarge = customRulesByteCount > MAX_CUSTOM_FILTER_RULE_BYTES
    var newOverrideHost by remember { mutableStateOf("") }
    var customSubscriptionTitle by remember { mutableStateOf("") }
    var customSubscriptionUrl by remember { mutableStateOf("") }
    var updatingSubscriptionId by remember { mutableStateOf<String?>(null) }
    var diagnosticsExpanded by remember { mutableStateOf(false) }
    var showDiagnosticPercentiles by remember { mutableStateOf(true) }
    var showDiagnosticIndexes by remember { mutableStateOf(true) }
    var showDiagnosticEvents by remember { mutableStateOf(true) }
    var showResetDiagnosticsDialog by remember { mutableStateOf(false) }
    val events = remember(settingsVersion, diagnosticsRefreshVersion) { FilterRepository.getRecentEvents(context) }
    val scope = rememberCoroutineScope()

    fun refresh() {
        settingsVersion++
        onFilteringChanged()
    }

    fun resetDiagnostics() {
        FilterRepository.resetDiagnostics(context)
        diagnosticsRefreshVersion++
        Toast.makeText(context, "已清空过滤诊断统计、最近日志和本进程缓存", Toast.LENGTH_SHORT).show()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.FilterAlt, contentDescription = "网页过滤", tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("网页过滤总控", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                      Text(
                          "支持经验证的 ABP/EasyList、uBO 与 AdGuard 安全子集；未支持语法会明确跳过。",
                          fontSize = 11.sp,
                          color = MaterialTheme.colorScheme.onSurfaceVariant
                      )
                    }
                    Switch(
                        checked = filteringEnabled,
                        onCheckedChange = { enabled ->
                            KioskPrefs.setLimitAdBlockEnabled(context, enabled)
                            refresh()
                            Toast.makeText(context, "新打开的网站生效", Toast.LENGTH_SHORT).show()
                        }
                    )
                }

                  Text(
                      "已编译规则 ${report.enabledRuleCount}/${report.ruleCount} 条：网络 ${report.networkRuleCount}、元素隐藏 ${report.cosmeticRuleCount}、scriptlet ${report.scriptletRuleCount}；不支持语法 ${report.unsupportedRuleCount} 条。",
                      fontSize = 12.sp,
                      color = MaterialTheme.colorScheme.onSurfaceVariant
                  )
                  if (engineUiState.isLoading) {
                      LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                      Text("正在后台读取并编译过滤规则…", fontSize = 11.sp)
                  }
                  if (engineUiState.errorMessage.isNotBlank()) {
                      Text(
                          "过滤引擎降级：${engineUiState.errorMessage}",
                          fontSize = 11.sp,
                          color = MaterialTheme.colorScheme.error
                      )
                  }

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("过滤强度", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    FilterPreset.entries.forEach { preset ->
                        FilterPresetOption(
                            selected = settings.preset == preset,
                            preset = preset,
                            onClick = {
                                runCatching {
                                    FilterRepository.setPreset(context, preset)
                                }.onSuccess {
                                    refresh()
                                }.onFailure { error ->
                                    Toast.makeText(
                                        context,
                                        error.message ?: "切换过滤强度失败",
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                            }
                        )
                    }
                }
            }
        }

        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.ListAlt, contentDescription = "订阅", tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text("内置订阅目录", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = customSubscriptionTitle,
                        onValueChange = { customSubscriptionTitle = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("自定义订阅名称") }
                    )
                    OutlinedTextField(
                        value = customSubscriptionUrl,
                        onValueChange = { customSubscriptionUrl = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("HTTPS 订阅 URL") }
                    )
                    Button(
                        onClick = {
                            runCatching {
                                FilterRepository.addCustomSubscription(context, customSubscriptionTitle, customSubscriptionUrl)
                            }.onSuccess {
                                customSubscriptionTitle = ""
                                customSubscriptionUrl = ""
                                refresh()
                                Toast.makeText(context, "自定义订阅已添加，可点击更新拉取规则", Toast.LENGTH_SHORT).show()
                            }.onFailure {
                                Toast.makeText(context, it.message ?: "添加失败", Toast.LENGTH_LONG).show()
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("添加自定义订阅")
                    }
                }
                settings.subscriptions.forEach { subscription ->
                    val sourceReport = report.sourceReports.firstOrNull { it.sourceId == subscription.id }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(subscription.title, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            Text(
                                "${subscription.category} | ${subscription.subscriptionUrl}",
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (sourceReport != null) {
                                Text(
                                    "启用 ${sourceReport.enabledRules}/${sourceReport.totalLines}，网络 ${sourceReport.networkRules}，隐藏 ${sourceReport.cosmeticRules}，scriptlet ${sourceReport.scriptletRules}，不支持 ${sourceReport.unsupportedRules}",
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            TextButton(
                                enabled = updatingSubscriptionId == null && subscription.subscriptionUrl.startsWith("https://"),
                                onClick = {
                                    updatingSubscriptionId = subscription.id
                                    scope.launch {
                                        val result = withContext(Dispatchers.IO) {
                                            runCatching { FilterRepository.updateSubscription(context, subscription.id) }
                                        }
                                        updatingSubscriptionId = null
                                        result.onSuccess {
                                            refresh()
                                            Toast.makeText(context, "订阅已更新：${subscription.title}", Toast.LENGTH_SHORT).show()
                                        }.onFailure {
                                            Toast.makeText(context, it.message ?: "订阅更新失败", Toast.LENGTH_LONG).show()
                                        }
                                    }
                                }
                            ) {
                                Text(if (updatingSubscriptionId == subscription.id) "更新中" else "更新")
                            }
                            Switch(
                                checked = subscription.enabled,
                                onCheckedChange = { enabled ->
                                    runCatching {
                                        FilterRepository.setSubscriptionEnabled(context, subscription.id, enabled)
                                    }.onSuccess {
                                        refresh()
                                    }.onFailure { error ->
                                        Toast.makeText(
                                            context,
                                            error.message ?: "更新订阅状态失败",
                                            Toast.LENGTH_LONG
                                        ).show()
                                    }
                                }
                            )
                        }
                    }
                    if (subscription.category == "自定义订阅") {
                        TextButton(onClick = {
                            FilterRepository.removeCustomSubscription(context, subscription.id)
                            refresh()
                        }) {
                            Text("删除自定义订阅")
                        }
                    }
                    if (subscription.lastUpdatedAt > 0L || subscription.lastError.isNotBlank()) {
                        Text(
                            "更新时间：${formatTimestamp(subscription.lastUpdatedAt)}${if (subscription.lastError.isNotBlank()) " | ${subscription.lastError}" else ""}",
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.14f))
                }
            }
        }

        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Edit, contentDescription = "自定义规则", tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text("自定义 Adblock 规则", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
                  Text(
                      "支持经过回归验证的域名锚、例外、资源类型、party/domain、元素隐藏和安全 scriptlet 子集；WebSocket、Service Worker 与未识别扩展语法不宣称完整支持。",
                      fontSize = 11.sp,
                      color = MaterialTheme.colorScheme.onSurfaceVariant
                  )
                OutlinedTextField(
                      value = customRules,
                      onValueChange = {
                          customRules = it
                      },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 140.dp, max = 240.dp),
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp, fontFamily = FontFamily.Monospace),
                    placeholder = { Text("||ads.demo.invalid^\n@@||demo.invalid/allowed.js${'$'}script") }
                )
                  Text(
                      when {
                          customRulesTooLarge -> "规则文本过大：${customRulesByteCount / 1024}KB，最大 ${MAX_CUSTOM_FILTER_RULE_BYTES / 1024}KB"
                          customRuleValidation.isValidating -> "正在后台校验规则…"
                          customRuleValidation.errorMessage.isNotBlank() -> "校验失败：${customRuleValidation.errorMessage}"
                          else -> "校验：启用 ${customRuleReport.enabledRuleCount}/${customRuleReport.ruleCount}，不支持 ${customRuleReport.unsupportedRuleCount}"
                      },
                      fontSize = 12.sp,
                      color = if (customRulesTooLarge || customRuleValidation.errorMessage.isNotBlank()) {
                          MaterialTheme.colorScheme.error
                      } else {
                          MaterialTheme.colorScheme.onSurfaceVariant
                      }
                  )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                      Button(
                          enabled = !customRulesTooLarge && !customRuleValidation.isValidating,
                          onClick = {
                              FilterRepository.setCustomRules(context, customRules)
                            refresh()
                            Toast.makeText(context, "自定义规则已保存，新打开的网站生效", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("保存规则")
                    }
                      OutlinedButton(
                          onClick = {
                              customRules = ""
                              FilterRepository.setCustomRules(context, "")
                            refresh()
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("清空")
                    }
                }
            }
        }

        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Public, contentDescription = "站点例外", tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text("站点例外", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = newOverrideHost,
                        onValueChange = { newOverrideHost = it },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        label = { Text("域名") },
                        placeholder = { Text("demo.invalid") }
                    )
                    Button(
                        onClick = {
                            val host = newOverrideHost.normalizeHost()
                            if (host.isBlank()) {
                                Toast.makeText(context, "请输入有效域名", Toast.LENGTH_SHORT).show()
                            } else {
                                FilterRepository.setSiteOverride(
                                    context,
                                    SiteFilterOverride(host = host, networkDisabled = true)
                                )
                                newOverrideHost = ""
                                refresh()
                            }
                        }
                    ) {
                        Text("放行")
                    }
                }
                if (settings.siteOverrides.isEmpty()) {
                    Text("暂无站点例外。", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    settings.siteOverrides.forEach { override ->
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(override.host, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            Text(
                                "网络：${if (override.networkDisabled) "关闭" else "开启"} | 元素隐藏：${if (override.cosmeticDisabled) "关闭" else "开启"} | 脚本：${if (override.scriptletDisabled) "关闭" else "开启"}",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                TextButton(onClick = {
                                    FilterRepository.setSiteOverride(
                                        context,
                                        override.copy(cosmeticDisabled = !override.cosmeticDisabled)
                                    )
                                    refresh()
                                }) {
                                    Text(if (override.cosmeticDisabled) "启用隐藏" else "停用隐藏")
                                }
                                TextButton(onClick = {
                                    FilterRepository.setSiteOverride(
                                        context,
                                        override.copy(scriptletDisabled = !override.scriptletDisabled)
                                    )
                                    refresh()
                                }) {
                                    Text(if (override.scriptletDisabled) "启用脚本" else "停用脚本")
                                }
                                TextButton(onClick = {
                                    FilterRepository.setSiteOverride(
                                        context,
                                        override.copy(
                                            networkDisabled = false,
                                            temporaryAllowUntil = System.currentTimeMillis() + 15 * 60 * 1000L
                                        )
                                    )
                                    refresh()
                                }) {
                                    Text("临时放行15分钟")
                                }
                                TextButton(onClick = {
                                    FilterRepository.removeSiteOverride(context, override.host)
                                    refresh()
                                }) {
                                    Text("删除")
                                }
                            }
                        }
                    }
                }
            }
        }

        FilterPerformanceDiagnosticsCard(
            filteringEnabled = filteringEnabled,
            report = report,
            snapshot = perfSnapshot,
            persistedSnapshot = webViewPerfSnapshot,
            sourceLabel = perfSourceLabel,
            events = events,
            expanded = diagnosticsExpanded,
            showPercentiles = showDiagnosticPercentiles,
            showIndexes = showDiagnosticIndexes,
            showEvents = showDiagnosticEvents,
            onExpandedChange = { diagnosticsExpanded = it },
            onShowPercentilesChange = { showDiagnosticPercentiles = it },
            onShowIndexesChange = { showDiagnosticIndexes = it },
            onShowEventsChange = { showDiagnosticEvents = it },
            onRefresh = { diagnosticsRefreshVersion++ },
            onReset = {
                showResetDiagnosticsDialog = true
            },
            onCopy = {
                val text = buildFilterDiagnosticsText(
                    settingsEnabled = filteringEnabled,
                    report = report,
                    snapshot = perfSnapshot,
                    sourceLabel = perfSourceLabel,
                    persistedSnapshot = webViewPerfSnapshot,
                    events = events
                )
                clipboardManager.setText(AnnotatedString(text))
                Toast.makeText(context, "过滤性能诊断信息已复制", Toast.LENGTH_SHORT).show()
            }
        )

        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.ReceiptLong, contentDescription = "日志", tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text("过滤日志", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = {
                        FilterRepository.clearEvents(context)
                        refresh()
                    }) {
                        Text("清空")
                    }
                }
                if (events.isEmpty()) {
                    Text("暂无拦截或例外事件。", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    events.take(20).forEach { event ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.8f))
                                .border(
                                    width = 1.dp,
                                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f),
                                    shape = RoundedCornerShape(12.dp)
                                )
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                val actionBgColor = when (event.action) {
                                    "BLOCK" -> MaterialTheme.colorScheme.errorContainer
                                    "ALLOW" -> Color(0xFFE8F5E9)
                                    "EXCEPTION" -> Color(0xFFE3F2FD)
                                    else -> MaterialTheme.colorScheme.surfaceVariant
                                }
                                val actionTextColor = when (event.action) {
                                    "BLOCK" -> MaterialTheme.colorScheme.onErrorContainer
                                    "ALLOW" -> Color(0xFF2E7D32)
                                    "EXCEPTION" -> Color(0xFF1565C0)
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                }
                                val actionLabel = when (event.action) {
                                    "BLOCK" -> "已拦截"
                                    "ALLOW" -> "已允许"
                                    "EXCEPTION" -> "放行例外"
                                    else -> event.action
                                }
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(actionBgColor)
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = actionLabel,
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = actionTextColor
                                    )
                                }

                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = event.resourceType,
                                        fontSize = 9.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }

                                Spacer(modifier = Modifier.weight(1f))

                                Text(
                                    text = formatEventTime(event.timestamp),
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            if (event.topLevelUrl.isNotBlank()) {
                                Text(
                                    text = "网页: ${event.topLevelUrl}",
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                )
                            }

                            Text(
                                text = "请求: ${event.url}",
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 2,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                            )

                            if (event.ruleText.isNotBlank()) {
                                Text(
                                    text = "规则: [${event.sourceName}] ${event.ruleText}",
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.primary,
                                    maxLines = 2,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                )
                            }

                            val meta = listOfNotNull(
                                event.sourceId.takeIf { it.isNotBlank() }?.let { "sourceId=$it" },
                                event.matchType.takeIf { it.isNotBlank() }?.let { "type=$it" },
                                event.indexKey.takeIf { it.isNotBlank() }?.let { "key=$it" },
                                event.cacheStatus.takeIf { it.isNotBlank() }?.let { "cache=$it" },
                                event.candidateCount.takeIf { it > 0 }?.let { "candidates=$it" }
                            ).joinToString("  ")
                            if (meta.isNotBlank()) {
                                Text(
                                    text = meta,
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showResetDiagnosticsDialog) {
        AlertDialog(
            onDismissRequest = { showResetDiagnosticsDialog = false },
            icon = {
                Icon(Icons.Default.RestartAlt, contentDescription = "确认重置过滤诊断")
            },
            title = { Text("确认清空过滤诊断？") },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "此操作会清空当前采集到的过滤性能统计、最近过滤日志、过滤判定缓存和 WebView 进程快照。",
                        fontSize = 13.sp
                    )
                    Text(
                        "清空后无法恢复。建议只在开始新一轮广告过滤测试前执行。",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showResetDiagnosticsDialog = false
                        resetDiagnostics()
                    },
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("确认清空")
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetDiagnosticsDialog = false }) {
                    Text("取消")
                }
            }
        )
    }
}

@Composable
private fun FilterPerformanceDiagnosticsCard(
    filteringEnabled: Boolean,
    report: FilterBuildReport,
    snapshot: FilterPerfSnapshot,
    persistedSnapshot: FilterPerfDiagnosticSnapshot?,
    sourceLabel: String,
    events: List<FilterEvent>,
    expanded: Boolean,
    showPercentiles: Boolean,
    showIndexes: Boolean,
    showEvents: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onShowPercentilesChange: (Boolean) -> Unit,
    onShowIndexesChange: (Boolean) -> Unit,
    onShowEventsChange: (Boolean) -> Unit,
    onRefresh: () -> Unit,
    onReset: () -> Unit,
    onCopy: () -> Unit
) {
    val cacheHitRate = formatRatio(snapshot.cacheHitCount, snapshot.decisionCount)
    val averageCandidates = if (snapshot.decisionCount > 0L) {
        formatDecimal(snapshot.candidateEvaluationCount.toDouble() / snapshot.decisionCount.toDouble(), 2)
    } else {
        "0"
    }
    val blockEvents = events.count { it.action == "BLOCK" }
    val exceptionEvents = events.count { it.action == "EXCEPTION" }

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.BugReport, contentDescription = "过滤性能诊断", tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("过滤性能诊断", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Text(
                        "查看规则编译、命中缓存、候选规则评估和注入资源的实时快照；复制内容会脱敏 URL 查询参数。",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AssistChip(
                    onClick = onRefresh,
                    label = { Text("刷新") },
                    leadingIcon = {
                        Icon(Icons.Default.Refresh, contentDescription = "刷新", modifier = Modifier.size(18.dp))
                    }
                )
                AssistChip(
                    onClick = onCopy,
                    label = { Text("复制") },
                    leadingIcon = {
                        Icon(Icons.Default.ContentCopy, contentDescription = "复制", modifier = Modifier.size(18.dp))
                    }
                )
                AssistChip(
                    onClick = onReset,
                    label = { Text("重置") },
                    leadingIcon = {
                        Icon(Icons.Default.RestartAlt, contentDescription = "重置", modifier = Modifier.size(18.dp))
                    }
                )
                AssistChip(
                    onClick = { onExpandedChange(!expanded) },
                    label = { Text(if (expanded) "收起" else "展开") },
                    leadingIcon = {
                        Icon(
                            imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = if (expanded) "收起" else "展开",
                            modifier = Modifier.size(18.dp)
                        )
                    }
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.72f))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                DiagnosticItem(label = "指标来源", value = sourceLabel)
                DiagnosticItem(
                    label = "更新时间",
                    value = persistedSnapshot?.updatedAt?.takeIf { it > 0L }?.let { formatTimestamp(it) } ?: "暂无 WebView 运行快照"
                )
                DiagnosticItem(label = "规则编译", value = "${snapshot.buildDurationMs} ms")
                DiagnosticItem(label = "启用规则", value = "${report.enabledRuleCount}/${report.ruleCount}")
                DiagnosticItem(label = "判定次数", value = snapshot.decisionCount.toString())
                DiagnosticItem(label = "缓存命中", value = "${snapshot.cacheHitCount}/${snapshot.decisionCount} ($cacheHitRate)")
                DiagnosticItem(label = "归一缓存", value = "命中 ${snapshot.normalizedCacheHitCount}，存储 ${snapshot.normalizedCacheStoreCount}")
            }

            if (!expanded) {
                Text(
                    if (persistedSnapshot == null) {
                        "尚未收到 WebView 进程运行快照。请打开网页产生请求后回到此处点击刷新。"
                    } else {
                        "已折叠详细指标，点击“展开”查看分位数、索引、注入资源和最近事件。"
                    },
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                if (!filteringEnabled) {
                    Text(
                        "网页过滤当前关闭。诊断区仍显示最近一次规则编译快照，打开过滤并访问网页后会产生运行指标。",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.72f))
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("运行摘要", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    DiagnosticItem(label = "平均候选", value = averageCandidates)
                    DiagnosticItem(label = "候选评估", value = snapshot.candidateEvaluationCount.toString())
                    DiagnosticItem(label = "正则评估", value = snapshot.regexEvaluationCount.toString())
                    DiagnosticItem(label = "归一缓存绕过", value = snapshot.normalizedCacheBypassCount.toString())
                }

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterDiagnosticsSwitchRow(
                        title = "显示 P50/P95/P99/Max",
                        subtitle = "用于判断网页卡顿是否来自单次极慢判定。",
                        checked = showPercentiles,
                        onCheckedChange = onShowPercentilesChange
                    )
                    FilterDiagnosticsSwitchRow(
                        title = "显示索引结构摘要",
                        subtitle = "用于观察 token 桶、索引规则和兜底规则规模。",
                        checked = showIndexes,
                        onCheckedChange = onShowIndexesChange
                    )
                    FilterDiagnosticsSwitchRow(
                        title = "包含最近日志摘要",
                        subtitle = "用于把性能和真实拦截事件放在一起排查。",
                        checked = showEvents,
                        onCheckedChange = onShowEventsChange
                    )
                    FilterDiagnosticsSwitchRow(
                        title = "自动采样刷新",
                        subtitle = "预留：后续接入周期采样、慢请求追踪和阈值报警。",
                        checked = false,
                        onCheckedChange = {},
                        enabled = false
                    )
                }

                if (showPercentiles) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.72f))
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("耗时分布", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        FilterPerfStatsRow("WebView shouldBlock", snapshot.shouldBlockDurationMicros, "us")
                        FilterPerfStatsRow("shouldBlock 解析", snapshot.shouldBlockParseDurationMicros, "us")
                        FilterPerfStatsRow("shouldBlock 判定", snapshot.shouldBlockEngineDurationMicros, "us")
                        FilterPerfStatsRow("shouldBlock 事件", snapshot.shouldBlockEventDurationMicros, "us")
                        FilterPerfStatsRow("shouldBlock 快照", snapshot.shouldBlockSnapshotDurationMicros, "us")
                        FilterPerfStatsRow("规则判定", snapshot.decisionDurationMicros, "us")
                        FilterPerfStatsRow("候选评估/次", snapshot.candidateEvaluationsPerDecision, "条")
                        FilterPerfStatsRow("元素隐藏", snapshot.cosmeticDurationMicros, "us")
                        FilterPerfStatsRow("Scriptlet", snapshot.scriptletDurationMicros, "us")
                    }
                }

                if (snapshot.slowShouldBlockSamples.isNotEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.72f))
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("慢 shouldBlock 样本", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        snapshot.slowShouldBlockSamples.take(10).forEachIndexed { index, sample ->
                            Text(
                                text = "#${index + 1} ${formatSlowShouldBlockSample(sample)}",
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.72f))
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("页面注入与资源", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    DiagnosticItem(label = "元素隐藏调用", value = snapshot.cosmeticCallCount.toString())
                    DiagnosticItem(label = "Scriptlet 调用", value = snapshot.scriptletCallCount.toString())
                    DiagnosticItem(label = "生成 CSS", value = formatBytes(snapshot.generatedCssBytes))
                    DiagnosticItem(label = "生成 JS", value = formatBytes(snapshot.generatedScriptletBytes))
                }

                if (showIndexes) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.72f))
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("索引摘要", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        DiagnosticItem(label = "important", value = formatIndexStats(snapshot.importantIndex))
                        DiagnosticItem(label = "exception", value = formatIndexStats(snapshot.exceptionIndex))
                        DiagnosticItem(label = "blocking", value = formatIndexStats(snapshot.blockingIndex))
                        DiagnosticItem(label = "removeparam", value = formatIndexStats(snapshot.removeParamIndex))
                    }
                }

                if (showEvents) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.72f))
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("最近事件摘要", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        DiagnosticItem(label = "事件缓存", value = "${events.size} 条")
                        DiagnosticItem(label = "拦截/例外", value = "$blockEvents / $exceptionEvents")
                        val latestEvent = events.maxByOrNull { it.timestamp }
                        DiagnosticItem(label = "最近事件", value = latestEvent?.let { "${formatTimestamp(it.timestamp)} ${it.action}" } ?: "暂无")
                    }
                }

                Button(
                    onClick = onCopy,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = "复制诊断", modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("复制完整诊断信息")
                }

                OutlinedButton(
                    onClick = onReset,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.RestartAlt, contentDescription = "重置诊断", modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("清空统计、日志和过滤缓存")
                }
            }
        }
    }
}

@Composable
private fun FilterDiagnosticsSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(subtitle, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled
        )
    }
}

@Composable
private fun FilterPerfStatsRow(
    label: String,
    stats: FilterPerfSampleStats,
    unit: String
) {
    DiagnosticItem(label = label, value = formatSampleStats(stats, unit))
}

@Composable
private fun FilterPresetOption(
    selected: Boolean,
    preset: FilterPreset,
    onClick: () -> Unit
) {
      val description = when (preset) {
          FilterPreset.LIGHT -> "仅轻量本地高置信规则，优先兼容性和低性能设备。"
          FilterPreset.STANDARD_CHILD -> "儿童模式默认，启用 EasyList 与本地儿童补充；隐私、中文和移动列表可手动启用。"
          FilterPreset.STRONG -> "叠加弹窗、scriptlet 和 URL 参数清理规则，误伤概率更高，适合家长手动开启。"
        FilterPreset.CUSTOM -> "保留当前订阅、自定义规则和例外设置。"
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) MaterialTheme.colorScheme.surface.copy(alpha = 0.8f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 6.dp),
        verticalAlignment = Alignment.Top
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column(modifier = Modifier.weight(1f)) {
            Text(preset.label, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Text(description, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * 带有图标和箭头的层级管理列表项
 */
@Composable
fun AdminMenuItem(
    icon: ImageVector,
    title: String,
    summary: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                tint = MaterialTheme.colorScheme.primary
            )
        }
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
            Spacer(modifier = Modifier.height(2.dp))
            Text(summary, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(
            imageVector = Icons.Default.KeyboardArrowRight,
            contentDescription = "详情",
            tint = MaterialTheme.colorScheme.outline
        )
    }
}

/**
 * 防护等级卡片。
 *
 * - 已是 Device Owner：展示「企业级完全锁定已生效」状态，不提供降级开关
 *   （降级需通过 ADB 移除 Device Owner，属高风险操作，不在应用内提供）。
 * - 非 Device Owner：提供「屏幕固定软锁 / 无系统级锁定」二选一，
 *   并附 Device Owner 升级引导（复制 ADB 脚本 + 跳转默认桌面设置）。
 */
@Composable
fun ProtectionLevelCard(
    isDeviceOwner: Boolean,
    protectionMode: String,
    onModeChange: (String) -> Unit,
    onCopyScript: () -> Unit,
    onGoToHomeSettings: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(bottom = 12.dp)
            ) {
                Icon(imageVector = Icons.Default.Lock, contentDescription = "防护", tint = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.width(8.dp))
                Text("防护等级", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }

            if (isDeviceOwner) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = Color(0xFF2E7D32),
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        "企业级完全锁定已生效（Device Owner）",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF2E7D32)
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "Home / 最近任务 / 返回键、状态栏、语音助手、出厂重置等逃逸路径均已封堵。" +
                        "如需彻底解除，请通过 ADB 执行 dpm remove-active-admin。",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                return@Column
            }

            // 非 Device Owner：可切换的软防护等级
            Text(
                "当前设备未取得 Device Owner 权限，可在以下两种基础防护中选择：",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            ProtectionOption(
                selected = protectionMode == KioskPrefs.MODE_SOFT_LOCK,
                title = "屏幕固定软锁（推荐）",
                desc = "进入系统「屏幕固定」，拦截 Home / 最近任务键。可被长按返回+最近任务解除，首次可能弹出系统确认框。",
                onClick = { onModeChange(KioskPrefs.MODE_SOFT_LOCK) }
            )
            ProtectionOption(
                selected = protectionMode == KioskPrefs.MODE_NONE,
                title = "无系统级锁定",
                desc = "仅沉浸式全屏 + 认证退出，不调用屏幕固定。适合开发调试或仅需防误触的场景。",
                onClick = { onModeChange(KioskPrefs.MODE_NONE) }
            )

            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "切换后需退出本应用并重新进入（或重启设备）方可生效。",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Divider(modifier = Modifier.padding(vertical = 16.dp))

            // Device Owner 升级引导
            Text(
                "想要企业级完全锁定？",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "在「未绑定账号、已恢复出厂」的设备上，通过电脑 ADB 执行下方脚本，可升级为不可逃逸的 Device Owner 模式：",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(12.dp)
            ) {
                Text(
                    text = "adb shell dpm set-device-owner site.anzz.childkiosk/.MyDeviceAdminReceiver",
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = onCopyScript,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("复制激活脚本", fontSize = 13.sp)
                }
                Button(
                    onClick = onGoToHomeSettings,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("设为默认主屏幕", fontSize = 13.sp)
                }
            }
        }
    }
}

@Composable
private fun ProtectionOption(
    selected: Boolean,
    title: String,
    desc: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.Top
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column(modifier = Modifier.padding(start = 4.dp, top = 4.dp)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(2.dp))
            Text(desc, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun WhitelistSubscriptionCard(
    url: String,
    onUrlChange: (String) -> Unit,
    autoRefresh: Boolean,
    onAutoRefreshChange: (Boolean) -> Unit,
    intervalHours: String,
    onIntervalHoursChange: (String) -> Unit,
    title: String,
    lastSuccessAt: Long,
    importedCount: Int,
    subscribedRowCount: Int,
    lastError: String,
    isRefreshing: Boolean,
    onSave: () -> Unit,
    onRefresh: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.CloudSync,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("订阅白名单", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }

            OutlinedTextField(
                value = url,
                onValueChange = onUrlChange,
                label = { Text("HTTPS 订阅地址") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            )

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Switch(
                        checked = autoRefresh,
                        onCheckedChange = onAutoRefreshChange
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("自动刷新", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                }
                OutlinedTextField(
                    value = intervalHours,
                    onValueChange = onIntervalHoursChange,
                    label = { Text("间隔(小时)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(max = 180.dp),
                    shape = RoundedCornerShape(12.dp)
                )
            }

            Text(
                text = buildString {
                    if (title.isNotBlank()) append("$title | ")
                    append("已导入 $importedCount 个")
                    append(" | 当前订阅项 $subscribedRowCount 个")
                    append(" | 最近成功：${formatTimestamp(lastSuccessAt)}")
                },
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (lastError.isNotBlank()) {
                Text(
                    text = "最近错误：$lastError",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.error
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = onSave,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    enabled = !isRefreshing
                ) {
                    Text("保存设置")
                }
                Button(
                    onClick = onRefresh,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    enabled = !isRefreshing && url.isNotBlank()
                ) {
                    Text(if (isRefreshing) "刷新中" else "立即刷新")
                }
            }
        }
    }
}

private fun buildFilterDiagnosticsText(
    settingsEnabled: Boolean,
    report: FilterBuildReport,
    snapshot: FilterPerfSnapshot,
    sourceLabel: String,
    persistedSnapshot: FilterPerfDiagnosticSnapshot?,
    events: List<FilterEvent>
): String {
    return buildString {
        appendLine("Child Kiosk Browser - Filter Performance Diagnostics")
        appendLine("Generated at: ${formatTimestamp(System.currentTimeMillis())}")
        appendLine("Filtering enabled: ${if (settingsEnabled) "yes" else "no"}")
        appendLine("Metric source: $sourceLabel")
        appendLine("Metric updated at: ${persistedSnapshot?.updatedAt?.takeIf { it > 0L }?.let { formatTimestamp(it) } ?: "not available"}")
        if (!persistedSnapshot?.processName.isNullOrBlank()) {
            appendLine("Metric process: ${persistedSnapshot?.processName}")
        }
        appendLine()
        appendLine("[Build]")
        appendLine("buildDurationMs=${snapshot.buildDurationMs}")
        appendLine("rules=${report.enabledRuleCount}/${report.ruleCount}")
        appendLine("networkRules=${report.networkRuleCount}")
        appendLine("cosmeticRules=${report.cosmeticRuleCount}")
        appendLine("scriptletRules=${report.scriptletRuleCount}")
        appendLine("unsupportedRules=${report.unsupportedRuleCount}")
        if (report.errors.isNotEmpty()) {
            appendLine("errors=${report.errors.joinToString(" | ")}")
        }
        appendLine()
        appendLine("[Runtime]")
        appendLine("decisionCount=${snapshot.decisionCount}")
        appendLine("cacheHitCount=${snapshot.cacheHitCount}")
        appendLine("cacheMissCount=${snapshot.cacheMissCount}")
        appendLine("cacheHitRate=${formatRatio(snapshot.cacheHitCount, snapshot.decisionCount)}")
        appendLine("normalizedCacheHitCount=${snapshot.normalizedCacheHitCount}")
        appendLine("normalizedCacheStoreCount=${snapshot.normalizedCacheStoreCount}")
        appendLine("normalizedCacheBypassCount=${snapshot.normalizedCacheBypassCount}")
        appendLine("candidateEvaluationCount=${snapshot.candidateEvaluationCount}")
        appendLine("regexEvaluationCount=${snapshot.regexEvaluationCount}")
        appendLine("cosmeticCallCount=${snapshot.cosmeticCallCount}")
        appendLine("scriptletCallCount=${snapshot.scriptletCallCount}")
        appendLine("generatedCssBytes=${snapshot.generatedCssBytes}")
        appendLine("generatedScriptletBytes=${snapshot.generatedScriptletBytes}")
        appendLine()
        appendLine("[Samples]")
        appendLine("shouldBlock=${formatSampleStats(snapshot.shouldBlockDurationMicros, "us")}")
        appendLine("shouldBlockParse=${formatSampleStats(snapshot.shouldBlockParseDurationMicros, "us")}")
        appendLine("shouldBlockEngine=${formatSampleStats(snapshot.shouldBlockEngineDurationMicros, "us")}")
        appendLine("shouldBlockEvent=${formatSampleStats(snapshot.shouldBlockEventDurationMicros, "us")}")
        appendLine("shouldBlockSnapshot=${formatSampleStats(snapshot.shouldBlockSnapshotDurationMicros, "us")}")
        appendLine("decision=${formatSampleStats(snapshot.decisionDurationMicros, "us")}")
        appendLine("candidatesPerDecision=${formatSampleStats(snapshot.candidateEvaluationsPerDecision, "rules")}")
        appendLine("cosmetic=${formatSampleStats(snapshot.cosmeticDurationMicros, "us")}")
        appendLine("scriptlet=${formatSampleStats(snapshot.scriptletDurationMicros, "us")}")
        appendLine()
        appendLine("[Slow ShouldBlock Samples]")
        if (snapshot.slowShouldBlockSamples.isEmpty()) {
            appendLine("暂无超过 20 ms 的 shouldBlock 样本")
        } else {
            snapshot.slowShouldBlockSamples.take(10).forEachIndexed { index, sample ->
                appendLine("#${index + 1} ${formatSlowShouldBlockSample(sample)}")
            }
        }
        appendLine()
        appendLine("[Indexes]")
        appendLine("important=${formatIndexStats(snapshot.importantIndex)}")
        appendLine("exception=${formatIndexStats(snapshot.exceptionIndex)}")
        appendLine("blocking=${formatIndexStats(snapshot.blockingIndex)}")
        appendLine("removeparam=${formatIndexStats(snapshot.removeParamIndex)}")
        appendLine()
        appendLine("[Recent Events]")
        appendLine("total=${events.size}")
        appendLine("block=${events.count { it.action == "BLOCK" }}")
        appendLine("allow=${events.count { it.action == "ALLOW" }}")
        appendLine("exception=${events.count { it.action == "EXCEPTION" }}")
        events.take(10).forEachIndexed { index, event ->
            appendLine(
                "#${index + 1} ${formatTimestamp(event.timestamp)} ${event.action} " +
                    "${event.resourceType} ${redactFilterDiagnosticUrl(event.url)} " +
                    "source=${event.sourceName.ifBlank { "-" }} " +
                    "type=${event.matchType.ifBlank { "-" }} " +
                    "key=${event.indexKey.ifBlank { "-" }} " +
                    "candidates=${event.candidateCount} " +
                    "cache=${event.cacheStatus.ifBlank { "-" }} " +
                    "rule=${event.ruleText.ifBlank { "-" }}"
            )
        }
        val topRules = events
            .filter { it.ruleText.isNotBlank() }
            .groupingBy { "${it.sourceName.ifBlank { "-" }} | ${it.ruleText}" }
            .eachCount()
            .entries
            .sortedByDescending { it.value }
            .take(10)
        if (topRules.isNotEmpty()) {
            appendLine()
            appendLine("[Recent Rule Summary]")
            topRules.forEachIndexed { index, entry ->
                appendLine("#${index + 1} count=${entry.value} ${entry.key}")
            }
        }
    }
}

private fun formatSampleStats(stats: FilterPerfSampleStats, unit: String): String {
    if (stats.sampleCount <= 0) return "暂无样本"
    return "n=${stats.sampleCount}, " +
        "p50=${formatSampleValue(stats.p50, unit)}, " +
        "p95=${formatSampleValue(stats.p95, unit)}, " +
        "p99=${formatSampleValue(stats.p99, unit)}, " +
        "max=${formatSampleValue(stats.max, unit)}"
}

private fun formatSampleValue(value: Long, unit: String): String {
    return if (unit == "us") formatMicros(value) else "$value $unit"
}

private fun formatMicros(value: Long): String {
    return if (value >= 1_000L) {
        "${formatDecimal(value.toDouble() / 1_000.0, 2)} ms"
    } else {
        "$value us"
    }
}

private fun formatSlowShouldBlockSample(sample: FilterSlowShouldBlockSample): String {
    return buildString {
        append(formatTimestamp(sample.timestamp))
        append(" total=${formatMicros(sample.durationMicros)}")
        append(" parse=${formatMicros(sample.parseMicros)}")
        append(" engine=${formatMicros(sample.engineMicros)}")
        append(" event=${formatMicros(sample.eventMicros)}")
        append(" snapshot=${formatMicros(sample.snapshotMicros)}")
        append(" resource=${sample.resourceType.ifBlank { "-" }}")
        append(" action=${sample.action.ifBlank { "-" }}")
        append(" cache=${sample.cacheStatus.ifBlank { "-" }}")
        append(" candidates=${sample.candidateCount}")
        append(" url=${sample.url.takeIf { it.isNotBlank() }?.let(::redactFilterDiagnosticUrl) ?: "-"}")
        if (sample.ruleText.isNotBlank()) {
            append(" rule=${sample.ruleText}")
        }
    }
}

private fun redactFilterDiagnosticUrl(value: String): String {
    if (value.isBlank()) return "-"
    return runCatching {
        val uri = android.net.Uri.parse(value)
        val scheme = uri.scheme?.lowercase(Locale.US)
        val host = uri.host
        if ((scheme != "http" && scheme != "https") || host.isNullOrBlank()) {
            return@runCatching value.substringBefore('?').substringBefore('#').take(256)
        }
        buildString {
            append(scheme)
            append("://")
            append(host)
            if (uri.port >= 0) append(":${uri.port}")
            append(uri.encodedPath.orEmpty().take(256))
            if (!uri.encodedQuery.isNullOrBlank()) append("?<redacted>")
            if (!uri.encodedFragment.isNullOrBlank()) append("#<redacted>")
        }
    }.getOrElse {
        value.substringBefore('?').substringBefore('#').take(256)
    }
}

private fun formatIndexStats(stats: FilterIndexStats): String {
    return "桶 ${stats.tokenBucketCount}，索引 ${stats.indexedRuleCount}，兜底 ${stats.universalRuleCount}"
}

private fun formatRatio(numerator: Long, denominator: Long): String {
    if (denominator <= 0L) return "0%"
    return "${formatDecimal(numerator.toDouble() * 100.0 / denominator.toDouble(), 1)}%"
}

private fun formatBytes(bytes: Long): String {
    val safeBytes = bytes.coerceAtLeast(0L)
    return when {
        safeBytes >= 1024L * 1024L -> "${formatDecimal(safeBytes.toDouble() / (1024.0 * 1024.0), 2)} MB"
        safeBytes >= 1024L -> "${formatDecimal(safeBytes.toDouble() / 1024.0, 1)} KB"
        else -> "$safeBytes B"
    }
}

private fun formatDecimal(value: Double, digits: Int): String {
    return String.format(Locale.US, "%.${digits}f", value)
}

private fun formatEventTime(timestamp: Long): String {
    if (timestamp <= 0L) return ""
    return runCatching {
        SimpleDateFormat("HH:mm:ss", Locale.CHINA).format(Date(timestamp))
    }.getOrDefault("")
}

@Composable
fun WebAppCard(
    app: WebAppEntity,
    canEdit: Boolean = true,
    canDelete: Boolean = true,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit
) {
    val isSubscribed = app.sourceType == WebAppEntity.SOURCE_SUBSCRIPTION
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp, horizontal = 12.dp),
            verticalAlignment = Alignment.Top
        ) {
            // 左边显示图标
            val iconPath = app.iconPath ?: ""
            val isImageIcon = WebAppIconCache.isNetworkIconUrl(iconPath) ||
                WebAppIconCache.isCachedIconPath(iconPath)
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                if (isImageIcon) {
                    NetworkWebIcon(
                        url = iconPath,
                        contentDescription = app.title,
                        referer = app.url,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                        allowNetwork = !WebAppIconCache.isNetworkIconUrl(iconPath)
                    )
                } else {
                    val iconVector = getIconVector(iconPath)
                    Icon(
                        imageVector = iconVector,
                        contentDescription = app.title,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            // 右边上下布局
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // 上面文字和 url
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = app.title,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = app.url,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                }

                // 下面显示操作按钮和分类标签
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    // 左侧标签群：分类与订阅状态
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "${WebAppEntity.getCategoryEmoji(app.category)} ${WebAppEntity.getCategoryDisplayName(app.category)}",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                        if (isSubscribed) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.8f))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = "订阅",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                            }
                        }
                    }

                    // 右侧开关和操作按钮
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Switch(
                            checked = app.isEnabled,
                            onCheckedChange = onToggleEnabled,
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = MaterialTheme.colorScheme.primary,
                                checkedTrackColor = MaterialTheme.colorScheme.primaryContainer,
                                uncheckedThumbColor = MaterialTheme.colorScheme.outline,
                                uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant
                            ),
                            modifier = Modifier.scale(0.72f).padding(end = 4.dp)
                        )
                        if (canEdit) {
                            IconButton(
                                onClick = onEdit,
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Edit,
                                    contentDescription = "编辑",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                        if (canDelete) {
                            IconButton(
                                onClick = onDelete,
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = "删除",
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BrowserHistoryScreen(
    history: List<BrowserHistoryEntity>,
    onOpen: (BrowserHistoryEntity) -> Unit,
    onAddToWhitelist: (BrowserHistoryEntity) -> Unit,
    onDelete: (BrowserHistoryEntity) -> Unit,
    onClearAll: () -> Unit
) {
    var showClearConfirm by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("最近浏览", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        Text(
                            text = "保留最近 90 天访问记录，当前显示最近 ${history.size} 条",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    OutlinedButton(
                        enabled = history.isNotEmpty(),
                        onClick = { showClearConfirm = true },
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(imageVector = Icons.Default.DeleteSweep, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("清空", fontSize = 12.sp)
                    }
                }
            }
        }

        if (history.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 220.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "暂无浏览历史",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 14.sp
                )
            }
        } else {
            history.forEach { item ->
                BrowserHistoryCard(
                    item = item,
                    onOpen = { onOpen(item) },
                    onAddToWhitelist = { onAddToWhitelist(item) },
                    onDelete = { onDelete(item) }
                )
            }
        }
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            icon = {
                Icon(imageVector = Icons.Default.DeleteSweep, contentDescription = null)
            },
            title = { Text("确认清空浏览历史？") },
            text = {
                Text("将删除当前保存的全部浏览历史记录，清空后无法恢复。")
            },
            confirmButton = {
                Button(
                    onClick = {
                        showClearConfirm = false
                        onClearAll()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("确认清空")
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) {
                    Text("取消")
                }
            }
        )
    }
}

@Composable
private fun BrowserHistoryCard(
    item: BrowserHistoryEntity,
    onOpen: () -> Unit,
    onAddToWhitelist: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = item.host.take(1).uppercase(Locale.getDefault()).ifBlank { "W" },
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        fontWeight = FontWeight.Bold
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.title.ifBlank { item.host },
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                    Text(
                        text = item.url,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                    Text(
                        text = formatHistoryTime(item.visitedAt),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onOpen) {
                    Icon(imageVector = Icons.Default.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("打开", fontSize = 12.sp)
                }
                TextButton(onClick = onAddToWhitelist) {
                    Icon(imageVector = Icons.Default.PlaylistAdd, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("加入白名单", fontSize = 12.sp)
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "删除历史",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

private fun formatHistoryTime(timestamp: Long): String {
    return SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(timestamp))
}

private fun normalizeHistoryUrl(url: String): String {
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

private fun isValidUrl(url: String): Boolean {
    val trimmed = url.trim()
    val hasProtocol = trimmed.startsWith("http://", ignoreCase = true) ||
                      trimmed.startsWith("https://", ignoreCase = true)
    val urlToCheck = if (hasProtocol) trimmed else "https://$trimmed"
    return android.util.Patterns.WEB_URL.matcher(urlToCheck).matches()
}

private fun formatUrl(url: String): String {
    return WebIconDiscovery.normalizeWebUrl(url)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddEditWebAppDialog(
    app: WebAppEntity?,
    initialTitle: String = "",
    initialUrl: String = "",
    initialCategory: String = WebAppEntity.CATEGORY_GAME,
    onDismiss: () -> Unit,
    onSave: (title: String, url: String, icon: String, category: String) -> Unit
) {
    var title by remember { mutableStateOf(app?.title ?: initialTitle) }
    var urlInput by remember { mutableStateOf(app?.url ?: initialUrl) }
    var category by remember { mutableStateOf(app?.category ?: initialCategory) }
    
    val initialIconPath = app?.iconPath.orEmpty()
    // 已冻结到本地的自定义图标不暴露内部缓存路径，仍保留 selectedIcon 作为真实保存值。
    var customIconUrl by remember {
        mutableStateOf(if (WebAppIconCache.isNetworkIconUrl(initialIconPath)) initialIconPath else "")
    }
    var selectedIcon by remember { mutableStateOf(app?.iconPath ?: "icon_gamepad") }
    var discoveredIcons by remember { mutableStateOf<List<WebIconCandidate>>(emptyList()) }
    var failedIconUrls by remember { mutableStateOf<Set<String>>(emptySet()) }
    var isDiscoveringIcons by remember { mutableStateOf(false) }
    var iconDiscoveryMessage by remember { mutableStateOf<String?>(null) }
    var lastAutoIconUrl by remember { mutableStateOf(if (WebAppIconCache.isNetworkIconUrl(app?.iconPath)) app?.iconPath.orEmpty() else "") }
    
    var isCheckingUrl by remember { mutableStateOf(false) }
    var urlError by remember { mutableStateOf<String?>(null) }
    var pingFailedOnce by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    fun selectNetworkIcon(iconUrl: String) {
        selectedIcon = iconUrl
        customIconUrl = iconUrl
        lastAutoIconUrl = iconUrl
    }

    fun markCandidateLoadFailed(candidate: WebIconCandidate) {
        val newFailed = failedIconUrls + candidate.url
        failedIconUrls = newFailed
        if (app == null && selectedIcon == candidate.url) {
            val replacement = discoveredIcons.firstOrNull { it.url !in newFailed }
            if (replacement != null) {
                selectNetworkIcon(replacement.url)
            } else {
                selectedIcon = "icon_gamepad"
                if (customIconUrl == candidate.url) customIconUrl = ""
            }
        }
    }

    // 自动发现网站图标：解析 HTML/Manifest 声明图标，并补充根目录常见 fallback。
    LaunchedEffect(urlInput, app?.id) {
        val trimmed = urlInput.trim()
        discoveredIcons = emptyList()
        failedIconUrls = emptySet()
        iconDiscoveryMessage = null
        if (trimmed.isBlank() || !isValidUrl(trimmed)) return@LaunchedEffect

        delay(350)
        val formatted = formatUrl(trimmed)
        val previousAutoIconUrl = lastAutoIconUrl
        val canAutoReplace = app == null &&
            (customIconUrl == previousAutoIconUrl || selectedIcon == previousAutoIconUrl)

        isDiscoveringIcons = true
        val candidates = WebIconDiscovery.discover(context, formatted)
        isDiscoveringIcons = false
        discoveredIcons = candidates

        val best = candidates.firstOrNull()
        if (best != null) {
            iconDiscoveryMessage = "已找到 ${candidates.size} 个网站图标"
            if (canAutoReplace) {
                selectNetworkIcon(best.url)
            }
        } else {
            iconDiscoveryMessage = "未找到可用网站图标，可手动填写图标地址"
        }
    }


    suspend fun pingUrl(urlStr: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val u = URL(urlStr)
            val conn = u.openConnection() as HttpURLConnection
            conn.requestMethod = "HEAD"
            conn.connectTimeout = 3000
            conn.readTimeout = 3000
            val code = conn.responseCode
            code in 200..399
        }.getOrDefault(false)
    }

    val visibleDiscoveredIcons = discoveredIcons.filterNot { it.url in failedIconUrls }
    val isCustomSelected = WebAppIconCache.isNetworkIconUrl(selectedIcon) ||
        WebAppIconCache.isCachedIconPath(selectedIcon)
    val previewIconPath = customIconUrl.trim().ifBlank {
        selectedIcon.takeIf { WebAppIconCache.isCachedIconPath(it) }.orEmpty()
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (app == null) "添加应用" else "编辑应用",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "设置名称、链接、分类和图标",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(imageVector = Icons.Default.Close, contentDescription = "关闭")
                    }
                }

                Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it },
                        label = { Text("应用名称") },
                        shape = RoundedCornerShape(12.dp),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = urlInput,
                        onValueChange = {
                            urlInput = it
                            urlError = null
                            pingFailedOnce = false
                        },
                        label = { Text("应用链接") },
                        shape = RoundedCornerShape(12.dp),
                        isError = urlError != null,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    if (urlInput.trim().startsWith("http://", ignoreCase = true)) {
                        Text(
                            text = "当前添加的是未加密的 HTTP 网站。在公共网络中可能会有被监听或劫持的风险，建议使用 HTTPS。",
                            color = Color(0xFFE65100),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(horizontal = 4.dp)
                        )
                    }

                    if (urlError != null) {
                        Text(
                            text = urlError ?: "",
                            color = if (pingFailedOnce) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error,
                            fontSize = 12.sp
                        )
                    }

                    Text("选择应用分类：", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(bottom = 4.dp)
                    ) {
                        val categories = listOf(
                            WebAppEntity.CATEGORY_GAME to "游戏",
                            WebAppEntity.CATEGORY_VIDEO to "视频",
                            WebAppEntity.CATEGORY_BOOK to "绘本",
                            WebAppEntity.CATEGORY_STUDY to "学习",
                            WebAppEntity.CATEGORY_TOOL to "工具",
                            WebAppEntity.CATEGORY_OTHER to "其他"
                        )
                        categories.forEach { (catKey, catName) ->
                            val isSelected = category == catKey
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .widthIn(min = 78.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(
                                        if (isSelected) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.surfaceVariant
                                    )
                                    .clickable { category = catKey }
                                    .padding(vertical = 8.dp)
                            ) {
                                Text(
                                    text = catName,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    Text("选择代表图标：", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(bottom = 4.dp)
                    ) {
                        BuiltInWebAppIcons.forEach { icon ->
                            val selected = selectedIcon == icon.id
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(52.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(
                                        if (selected) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.surfaceVariant
                                    )
                                    .clickable { selectedIcon = icon.id }
                            ) {
                                Icon(
                                    imageVector = icon.vector,
                                    contentDescription = icon.label,
                                    tint = if (selected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (isCustomSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.15f)
                                             else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                        ),
                        border = androidx.compose.foundation.BorderStroke(
                            width = 1.dp,
                            color = if (isCustomSelected) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
                        )
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        if (customIconUrl.trim().isNotEmpty()) {
                                            selectedIcon = customIconUrl.trim()
                                        } else if (WebAppIconCache.isCachedIconPath(selectedIcon)) {
                                            selectedIcon = selectedIcon
                                        } else {
                                            val fallbackIcon = WebIconDiscovery.defaultFaviconUrl(urlInput)
                                                ?: "https://assets.anzz.site/favicon.ico"
                                            selectedIcon = fallbackIcon
                                            customIconUrl = fallbackIcon
                                        }
                                    }
                            ) {
                                RadioButton(
                                    selected = isCustomSelected,
                                    onClick = {
                                        if (customIconUrl.trim().isNotEmpty()) {
                                            selectedIcon = customIconUrl.trim()
                                        } else if (WebAppIconCache.isCachedIconPath(selectedIcon)) {
                                            selectedIcon = selectedIcon
                                        } else {
                                            val fallbackIcon = WebIconDiscovery.defaultFaviconUrl(urlInput)
                                                ?: "https://assets.anzz.site/favicon.ico"
                                            selectedIcon = fallbackIcon
                                            customIconUrl = fallbackIcon
                                        }
                                    }
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    "使用自定义网络图标",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isCustomSelected) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.onSurface
                                )
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    if (isDiscoveringIcons) {
                                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                        Text("正在读取网站图标...", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    } else {
                                        Text(
                                            text = when {
                                                discoveredIcons.isNotEmpty() && visibleDiscoveredIcons.isEmpty() ->
                                                    "读取到的图标无法显示，已自动过滤"
                                                else -> iconDiscoveryMessage ?: "输入网址后自动读取 HTML / Manifest 图标"
                                            },
                                            fontSize = 12.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                TextButton(
                                    enabled = urlInput.trim().isNotBlank() && isValidUrl(urlInput) && !isDiscoveringIcons,
                                    onClick = {
                                        val formatted = formatUrl(urlInput)
                                        scope.launch {
                                            isDiscoveringIcons = true
                                            failedIconUrls = emptySet()
                                            iconDiscoveryMessage = null
                                            val candidates = WebIconDiscovery.discover(context, formatted, forceRefresh = true)
                                            isDiscoveringIcons = false
                                            discoveredIcons = candidates
                                            val best = candidates.firstOrNull()
                                            if (best != null) {
                                                selectNetworkIcon(best.url)
                                                iconDiscoveryMessage = "已重新读取 ${candidates.size} 个网站图标"
                                            } else {
                                                iconDiscoveryMessage = "未找到可用网站图标"
                                            }
                                        }
                                    }
                                ) {
                                    Text("重新读取", fontSize = 12.sp)
                                }
                            }

                            if (visibleDiscoveredIcons.isNotEmpty()) {
                                Text("网站图标候选：", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(rememberScrollState())
                                ) {
                                    visibleDiscoveredIcons.forEach { candidate ->
                                        val selected = selectedIcon == candidate.url
                                        Column(
                                            modifier = Modifier.width(76.dp),
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            verticalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(60.dp)
                                                    .clip(RoundedCornerShape(12.dp))
                                                    .background(MaterialTheme.colorScheme.surface)
                                                    .border(
                                                        width = if (selected) 2.dp else 1.dp,
                                                        color = if (selected) MaterialTheme.colorScheme.primary
                                                            else MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
                                                        shape = RoundedCornerShape(12.dp)
                                                    )
                                                    .clickable { selectNetworkIcon(candidate.url) },
                                                contentAlignment = Alignment.Center
                                            ) {
                                                NetworkWebIcon(
                                                    url = candidate.url,
                                                    contentDescription = candidate.label,
                                                    referer = candidate.referer ?: urlInput,
                                                    modifier = Modifier
                                                        .fillMaxSize()
                                                        .padding(4.dp),
                                                    contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                                                    onError = { markCandidateLoadFailed(candidate) }
                                                )
                                            }
                                            Text(
                                                text = candidate.sizeHint ?: candidate.source,
                                                fontSize = 10.sp,
                                                maxLines = 2,
                                                textAlign = TextAlign.Center,
                                                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(56.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(MaterialTheme.colorScheme.surface),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (previewIconPath.isNotEmpty()) {
                                        NetworkWebIcon(
                                            url = previewIconPath,
                                            contentDescription = "预览",
                                            referer = urlInput,
                                            modifier = Modifier.fillMaxSize(),
                                            contentScale = androidx.compose.ui.layout.ContentScale.Crop
                                        )
                                    } else {
                                        Icon(
                                            imageVector = Icons.Default.Star,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.outline
                                        )
                                    }
                                }

                                OutlinedTextField(
                                    value = customIconUrl,
                                    onValueChange = {
                                        customIconUrl = it
                                        selectedIcon = it.trim()
                                    },
                                    label = { Text("网站图标") },
                                    shape = RoundedCornerShape(10.dp),
                                    singleLine = true,
                                    modifier = Modifier.weight(1f),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedContainerColor = MaterialTheme.colorScheme.surface,
                                        unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                                        disabledContainerColor = MaterialTheme.colorScheme.surface,
                                        errorContainerColor = MaterialTheme.colorScheme.surface,
                                        unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                                    )
                                )
                            }
                        }
                    }
                }

                Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("取消")
                    }

                    Button(
                        onClick = {
                            if (title.isBlank()) {
                                urlError = "名称不能为空"
                                return@Button
                            }
                            if (!isValidUrl(urlInput)) {
                                urlError = "请输入合法的 URL 格式"
                                return@Button
                            }

                            val formattedUrl = formatUrl(urlInput)
                            val iconToSave = if (WebIconDiscovery.isNetworkIconUrl(selectedIcon)) {
                                selectedIcon.trim().ifBlank { customIconUrl.trim() }
                            } else {
                                selectedIcon
                            }.ifBlank { "icon_gamepad" }

                            if (pingFailedOnce) {
                                // 第二次点击：强行保存
                                onSave(title, formattedUrl, iconToSave, category)
                                return@Button
                            }

                            isCheckingUrl = true
                            urlError = "正在检测网络连通性..."

                            scope.launch {
                                val isOk = pingUrl(formattedUrl)
                                isCheckingUrl = false
                                if (!isOk) {
                                    pingFailedOnce = true
                                    urlError = "警告：目标链接可能无法访问，再次点击将直接保存。"
                                } else {
                                    onSave(title, formattedUrl, iconToSave, category)
                                }
                            }
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        enabled = !isCheckingUrl
                    ) {
                        Text(if (pingFailedOnce) "强行保存" else "保存")
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PinSetupDialog(
    onDismiss: () -> Unit,
    onSave: (pin: String) -> Unit
) {
    var pin1 by remember { mutableStateOf("") }
    var pin2 by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.fillMaxWidth().padding(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text("设置 4 位数字 PIN 码", fontSize = 18.sp, fontWeight = FontWeight.Bold)

                OutlinedTextField(
                    value = pin1,
                    onValueChange = {
                        if (it.length <= 4 && it.all { c -> c.isDigit() }) {
                            pin1 = it
                            error = null
                        }
                    },
                    label = { Text("输入 4 位数字密码") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = pin2,
                    onValueChange = {
                        if (it.length <= 4 && it.all { c -> c.isDigit() }) {
                            pin2 = it
                            error = null
                        }
                    },
                    label = { Text("再次输入以确认密码") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                if (error != null) {
                    Text(text = error ?: "", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("取消")
                    }

                    Button(
                        onClick = {
                            if (pin1.length != 4) {
                                error = "密码长度必须是 4 位"
                                return@Button
                            }
                            if (pin1 != pin2) {
                                error = "两次输入不一致"
                                return@Button
                            }
                            onSave(pin1)
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("确定")
                    }
                }
            }
        }
    }
}

data class ReleaseInfo(
    val version: String,
    val downloadUrl: String,
    val assetName: String,
    val distribution: String,
    val changelog: String,
    val releasePageUrl: String
)

suspend fun fetchLatestRelease(distribution: String): ReleaseInfo? = withContext(Dispatchers.IO) {
    runCatching {
        val url = URL("https://api.github.com/repos/xxxily/child-kiosk-browser/releases/latest")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = 5000
        conn.readTimeout = 5000
        conn.setRequestProperty("Accept", "application/vnd.github.v3+json")
        conn.setRequestProperty("User-Agent", "child-kiosk-browser")
        
        if (conn.responseCode == 200) {
            val jsonText = conn.inputStream.bufferedReader().use { it.readText() }
            val jsonObj = org.json.JSONObject(jsonText)
            val tagName = jsonObj.optString("tag_name", "")
            val htmlUrl = jsonObj.optString("html_url", "")
            val body = jsonObj.optString("body", "")
            
            val assets = jsonObj.optJSONArray("assets")
            var apkUrl = ""
            var apkName = ""
            var bestAssetScore = Int.MIN_VALUE
            val preferredDistribution = distribution.lowercase(Locale.US)
            if (assets != null && assets.length() > 0) {
                for (i in 0 until assets.length()) {
                    val asset = assets.getJSONObject(i)
                    val name = asset.optString("name", "")
                    val assetUrl = asset.optString("browser_download_url", "")
                    if (
                        name.endsWith(".apk", ignoreCase = true) &&
                        !name.contains("debug", ignoreCase = true) &&
                        assetUrl.isNotBlank()
                    ) {
                        val score = releaseApkAssetScore(name, preferredDistribution)
                        if (score > bestAssetScore) {
                            bestAssetScore = score
                            apkUrl = assetUrl
                            apkName = name
                        }
                    }
                }
            }
            if (apkUrl.isEmpty()) {
                apkUrl = htmlUrl
            }
            
            ReleaseInfo(
                version = tagName.trimStart('v'),
                downloadUrl = apkUrl,
                assetName = apkName,
                distribution = preferredDistribution,
                changelog = body,
                releasePageUrl = htmlUrl
            )
        } else {
            null
        }
    }.getOrNull()
}

private fun releaseApkAssetScore(name: String, preferredDistribution: String): Int {
    val lower = name.lowercase(Locale.US)
    var score = 0
    if (lower.endsWith(".apk")) score += 20
    if ("release" in lower) score += 100
    if ("debug" in lower) score -= 100
    if ("unsigned" in lower) score -= 30
    if (preferredDistribution in lower) score += 1_000
    if ("standard" in lower && preferredDistribution != "standard") score -= 300
    if ("enhanced" in lower && preferredDistribution != "enhanced") score -= 300
    return score
}

private fun currentDistributionLabel(): String {
    return if (BuildConfig.AMAP_LOCATION_SDK_INCLUDED) {
        "enhanced / 增强版"
    } else {
        "standard / 标准版"
    }
}

fun isNewerVersion(current: String, latest: String): Boolean {
    val currentParts = current.split(".").mapNotNull { it.toIntOrNull() }
    val latestParts = latest.split(".").mapNotNull { it.toIntOrNull() }
    val length = maxOf(currentParts.size, latestParts.size)
    for (i in 0 until length) {
        val currVal = currentParts.getOrElse(i) { 0 }
        val latVal = latestParts.getOrElse(i) { 0 }
        if (latVal > currVal) return true
        if (latVal < currVal) return false
    }
    return false
}

private data class UpdateDownloadProgress(
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = -1L,
    val speedBytesPerSecond: Long = 0L
) {
    val fraction: Float
        get() = if (totalBytes > 0L) {
            (downloadedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
        } else {
            0f
        }
}

private class UpdateDownloadCancelled : CancellationException("Update download cancelled")

private suspend fun downloadUpdateApk(
    context: Context,
    url: String,
    onProgress: (UpdateDownloadProgress) -> Unit
): File = withContext(Dispatchers.IO) {
    val uri = Uri.parse(url)
    if (!uri.lastPathSegment.orEmpty().endsWith(".apk", ignoreCase = true)) {
        throw IllegalArgumentException("未找到可直接下载的 APK 文件")
    }
    val fileName = URLUtil.guessFileName(url, null, "application/vnd.android.package-archive")
        .takeIf { it.endsWith(".apk", ignoreCase = true) }
        ?: "child-kiosk-browser-update.apk"
    val updateDir = File(context.cacheDir, "updates").apply { mkdirs() }
    updateDir.listFiles()?.forEach { file ->
        if (file.extension.equals("apk", ignoreCase = true)) {
            runCatching { file.delete() }
        }
    }
    val outputFile = File(updateDir, fileName)
    val connection = (URL(url).openConnection() as HttpURLConnection).apply {
        instanceFollowRedirects = true
        connectTimeout = 15_000
        readTimeout = 20_000
        requestMethod = "GET"
        setRequestProperty("User-Agent", "child-kiosk-browser-updater")
    }

    try {
        val responseCode = connection.responseCode
        if (responseCode !in 200..299) {
            throw IllegalStateException("下载失败：HTTP $responseCode")
        }
        val totalBytes = connection.contentLengthLong
        var downloadedBytes = 0L
        var lastProgressAt = System.currentTimeMillis()
        var lastProgressBytes = 0L
        connection.inputStream.use { input ->
            outputFile.outputStream().use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = input.read(buffer)
                    if (read == -1) break
                    output.write(buffer, 0, read)
                    downloadedBytes += read
                    val now = System.currentTimeMillis()
                    if (now - lastProgressAt >= 250L || downloadedBytes == totalBytes) {
                        val elapsedMs = (now - lastProgressAt).coerceAtLeast(1L)
                        val speed = ((downloadedBytes - lastProgressBytes) * 1000L) / elapsedMs
                        lastProgressAt = now
                        lastProgressBytes = downloadedBytes
                        withContext(Dispatchers.Main) {
                            onProgress(
                                UpdateDownloadProgress(
                                    downloadedBytes = downloadedBytes,
                                    totalBytes = totalBytes,
                                    speedBytesPerSecond = speed
                                )
                            )
                        }
                    }
                }
            }
        }
        if (downloadedBytes <= 0L || !outputFile.exists()) {
            throw IllegalStateException("下载文件为空")
        }
        withContext(Dispatchers.Main) {
            onProgress(
                UpdateDownloadProgress(
                    downloadedBytes = downloadedBytes,
                    totalBytes = totalBytes.takeIf { it > 0L } ?: downloadedBytes,
                    speedBytesPerSecond = 0L
                )
            )
        }
        outputFile
    } catch (e: CancellationException) {
        runCatching { outputFile.delete() }
        throw UpdateDownloadCancelled()
    } catch (e: Exception) {
        runCatching { outputFile.delete() }
        throw e
    } finally {
        connection.disconnect()
    }
}

private fun openApkInstaller(context: Context, apkFile: File): Boolean {
    if (!apkFile.exists()) return false
    return runCatching {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        true
    }.getOrDefault(false)
}

private fun openInstallUnknownAppsSettings(context: Context) {
    val uri = Uri.parse("package:${context.packageName}")
    val intents = listOf(
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, uri),
        Intent(Settings.ACTION_SECURITY_SETTINGS),
        Intent(Settings.ACTION_SETTINGS)
    )
    val opened = intents.any { intent ->
        runCatching {
            context.startActivity(intent)
            true
        }.getOrDefault(false)
    }
    if (!opened) {
        Toast.makeText(context, "无法打开安装权限设置", Toast.LENGTH_SHORT).show()
    }
}

@Composable
fun AboutAndSystemCard(
    currentVersion: String,
    currentDistribution: String,
    webViewInfo: String,
    deviceInfo: String,
    androidVersion: String,
    protectionLevel: String,
    isCheckingUpdate: Boolean,
    onCheckUpdate: () -> Unit,
    onCopyUrl: () -> Unit,
    onOpenUrl: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = "关于与系统诊断",
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("关于与系统诊断", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "关于项目",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("项目地址: https://github.com/xxxily/child-kiosk-browser", fontSize = 12.sp)
                        Text("作者: Blaze", fontSize = 12.sp)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        IconButton(onClick = onCopyUrl) {
                            Icon(
                                imageVector = Icons.Default.ContentCopy,
                                contentDescription = "复制地址",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        IconButton(onClick = onOpenUrl) {
                            Icon(
                                imageVector = Icons.Default.OpenInBrowser,
                                contentDescription = "浏览器打开",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }

            Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "系统与诊断信息",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                DiagnosticItem(label = "应用版本", value = currentVersion)
                DiagnosticItem(label = "安装版本", value = currentDistribution)
                DiagnosticItem(label = "WebView 内核", value = webViewInfo)
                DiagnosticItem(label = "设备型号", value = deviceInfo)
                DiagnosticItem(label = "安卓版本", value = "Android $androidVersion")
                DiagnosticItem(label = "防护等级", value = protectionLevel)
            }

            Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))

            Button(
                onClick = onCheckUpdate,
                enabled = !isCheckingUpdate,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                if (isCheckingUpdate) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("正在检查更新...")
                } else {
                    Icon(
                        imageVector = Icons.Default.SystemUpdate,
                        contentDescription = "检查更新",
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("检查最新版本")
                }
            }
        }
    }
}

@Composable
fun WebViewProviderScreen(
    snapshot: WebViewProviderSnapshot,
    onRefresh: () -> Unit,
    onCopyDiagnostics: () -> Unit,
    onOpenWebViewUpdate: () -> Unit,
    onOpenChromeUpdate: () -> Unit,
    onOpenSystemSettings: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Language,
                        contentDescription = "WebView 内核环境",
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("WebView 内核运行环境", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top
                ) {
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(snapshot.status.label, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        Text(snapshot.status.description, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    AssistChip(
                        onClick = onRefresh,
                        label = { Text("重新检测") },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "重新检测",
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    )
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.65f))
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    DiagnosticItem(label = "Provider 包名", value = snapshot.providerPackageName ?: "无法读取")
                    DiagnosticItem(label = "Provider 版本", value = snapshot.providerVersionName ?: "无法读取")
                    DiagnosticItem(label = "versionCode", value = snapshot.providerVersionCode?.toString() ?: "无法读取")
                    DiagnosticItem(label = "Chromium", value = snapshot.chromiumVersion ?: "无法解析")
                    DiagnosticItem(label = "Android", value = "${snapshot.androidVersion} (SDK ${snapshot.androidSdk})")
                    DiagnosticItem(label = "设备型号", value = snapshot.deviceModel)
                    DiagnosticItem(label = "进程", value = snapshot.processName)
                    DiagnosticItem(label = "独立 WebView 进程", value = if (snapshot.isWebViewProcess) "是" else "否")
                    DiagnosticItem(label = "渲染路径", value = "WebViewActivity -> FrameLayout -> WebView")
                    snapshot.readError?.let {
                        DiagnosticItem(label = "读取错误", value = it)
                    }
                }
            }
        }

        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Tune,
                        contentDescription = "兼容性开关",
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("关键 WebView 配置", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }

                val config = snapshot.runtimeConfig
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.65f))
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    DiagnosticItem(label = "Chrome Inspect", value = enabledLabel(config.chromeInspectEnabled))
                    DiagnosticItem(label = "手机浏览器 UA", value = enabledLabel(config.useBrowserUserAgent))
                    DiagnosticItem(label = "自定义 UA", value = if (config.customUserAgent.isBlank()) "未设置" else "已设置")
                    DiagnosticItem(label = "第三方 Cookie", value = enabledLabel(config.thirdPartyCookiesEnabled))
                    DiagnosticItem(label = "严格混合内容", value = enabledLabel(config.strictMixedContent))
                    DiagnosticItem(label = "热备 WebView", value = enabledLabel(config.webViewWarmPoolEnabled))
                    DiagnosticItem(label = "后台预加载", value = enabledLabel(config.webPreloadEnabled))
                    DiagnosticItem(label = "顶部进度条", value = enabledLabel(config.webViewTopProgressEnabled))
                }
            }
        }

        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.SystemUpdate,
                        contentDescription = "更新引导",
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("升级与设置入口", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }

                Text(
                    "新 WebView 是否生效由 Android 系统决定。如果系统不允许切换 WebView 实现，本应用无法单独替换内核。升级或切换后请完全关闭并重新打开网页，必要时重启应用或设备。",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = onCopyDiagnostics,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(imageVector = Icons.Default.ContentCopy, contentDescription = "复制诊断")
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("复制诊断信息")
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedButton(
                            onClick = onOpenWebViewUpdate,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(imageVector = Icons.Default.SystemUpdate, contentDescription = "更新 WebView")
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("WebView")
                        }
                        OutlinedButton(
                            onClick = onOpenChromeUpdate,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(imageVector = Icons.Default.OpenInBrowser, contentDescription = "更新 Chrome")
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Chrome")
                        }
                    }

                    OutlinedButton(
                        onClick = onOpenSystemSettings,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(imageVector = Icons.Default.Settings, contentDescription = "系统设置")
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("打开系统 WebView 设置")
                    }
                }
            }
        }

        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("系统默认 WebView UA", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                Text(
                    text = snapshot.defaultUserAgent.ifBlank { "无法读取" },
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private fun enabledLabel(enabled: Boolean): String = if (enabled) "开启" else "关闭"

@Composable
private fun PermissionBlacklistChips(
    title: String,
    items: Set<String>,
    columns: Int,
    onRemove: (String) -> Unit
) {
    if (items.isEmpty()) return
    Spacer(modifier = Modifier.height(8.dp))
    Text(
        title,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary
    )
    Spacer(modifier = Modifier.height(6.dp))
    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        items.chunked(columns).forEach { rowItems ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                rowItems.forEach { item ->
                    Row(
                        modifier = Modifier
                            .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.28f), shape = RoundedCornerShape(8.dp))
                            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f), RoundedCornerShape(8.dp))
                            .padding(horizontal = 10.dp, vertical = 5.dp)
                            .weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = item,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "移除",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier
                                .size(16.dp)
                                .clickable { onRemove(item) }
                        )
                    }
                }
                repeat(columns - rowItems.size) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpdateDialog(
    releaseInfo: ReleaseInfo,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var downloadJob by remember { mutableStateOf<Job?>(null) }
    var downloadProgress by remember { mutableStateOf(UpdateDownloadProgress()) }
    var downloadedApk by remember { mutableStateOf<File?>(null) }
    var downloadError by remember { mutableStateOf<String?>(null) }
    val isDownloading = downloadJob?.isActive == true
    val canInstallPackages =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }

    DisposableEffect(Unit) {
        onDispose {
            downloadJob?.cancel(UpdateDownloadCancelled())
        }
    }

    fun startDownload() {
        if (isDownloading) return
        downloadError = null
        downloadedApk = null
        downloadProgress = UpdateDownloadProgress()
        downloadJob = scope.launch {
            try {
                val apk = downloadUpdateApk(context, releaseInfo.downloadUrl) { progress ->
                    downloadProgress = progress
                }
                downloadedApk = apk
                downloadJob = null
                val canInstallNow = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.packageManager.canRequestPackageInstalls()
                } else {
                    true
                }
                if (!canInstallNow) {
                    Toast.makeText(context, "下载完成，请先允许本应用安装更新", Toast.LENGTH_LONG).show()
                } else if (openApkInstaller(context, apk)) {
                    Toast.makeText(context, "下载完成，已打开安装器", Toast.LENGTH_SHORT).show()
                } else {
                    downloadError = "下载完成，但无法自动打开安装器"
                }
            } catch (e: UpdateDownloadCancelled) {
                downloadJob = null
                downloadError = "下载已取消"
            } catch (e: Exception) {
                downloadJob = null
                downloadError = e.message ?: "下载失败"
            }
        }
    }
    
    Dialog(onDismissRequest = { if (!isDownloading) onDismiss() }) {
        Card(
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Column(
                modifier = Modifier
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = "发现新版本 v${releaseInfo.version}",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )

                Text(
                    text = buildString {
                        append("当前安装：")
                        append(currentDistributionLabel())
                        if (releaseInfo.assetName.isNotBlank()) {
                            append("\n将下载：")
                            append(releaseInfo.assetName)
                        }
                    },
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Text(
                    text = "更新日志：",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 200.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                        .padding(12.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        text = if (releaseInfo.changelog.isNullOrEmpty()) "暂无更新日志说明。" else releaseInfo.changelog,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                if (isDownloading || downloadProgress.downloadedBytes > 0L || downloadedApk != null || downloadError != null) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f), RoundedCornerShape(12.dp))
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val totalText = if (downloadProgress.totalBytes > 0L) {
                            " / ${formatBytes(downloadProgress.totalBytes)}"
                        } else {
                            ""
                        }
                        val statusText = when {
                            isDownloading -> "正在下载：${formatBytes(downloadProgress.downloadedBytes)}$totalText"
                            downloadedApk != null -> "下载完成：${downloadedApk?.name.orEmpty()}"
                            downloadError != null -> downloadError.orEmpty()
                            else -> ""
                        }
                        Text(
                            text = statusText,
                            fontSize = 12.sp,
                            color = if (downloadError != null && !isDownloading && downloadedApk == null) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                            fontWeight = FontWeight.SemiBold
                        )
                        if (isDownloading) {
                            if (downloadProgress.totalBytes > 0L) {
                                LinearProgressIndicator(
                                    progress = downloadProgress.fraction,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            } else {
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            }
                            Text(
                                text = "速度：${formatBytes(downloadProgress.speedBytesPerSecond)}/s",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (!canInstallPackages && downloadedApk != null) {
                            Text(
                                text = "系统未允许本应用安装未知来源应用，请授权后再安装。",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            if (isDownloading) {
                                downloadJob?.cancel(UpdateDownloadCancelled())
                            } else {
                                onDismiss()
                            }
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(if (isDownloading) "取消下载" else "稍后")
                    }

                    Button(
                        onClick = {
                            clipboardManager.setText(AnnotatedString(releaseInfo.downloadUrl))
                            if (KioskPrefs.isLimitDownloadEnabled(context)) {
                                Toast.makeText(context, "下载链接已复制；当前已禁用应用内下载能力", Toast.LENGTH_LONG).show()
                                return@Button
                            }
                            val apk = downloadedApk
                            when {
                                apk != null && !canInstallPackages ->
                                    openInstallUnknownAppsSettings(context)
                                apk != null && !openApkInstaller(context, apk) ->
                                    Toast.makeText(context, "无法打开安装器，请检查系统安装权限", Toast.LENGTH_LONG).show()
                                !isDownloading -> startDownload()
                            }
                        },
                        modifier = Modifier.weight(1.5f),
                        shape = RoundedCornerShape(12.dp),
                        enabled = !isDownloading
                    ) {
                        val text = when {
                            downloadedApk != null && !canInstallPackages -> "授权并安装"
                            downloadedApk != null -> "重新打开安装"
                            downloadError != null -> "重新下载"
                            else -> "下载并安装"
                        }
                        Text(text)
                    }
                }
            }
        }
    }
}
