package site.anzz.childkiosk.ui

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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import site.anzz.childkiosk.data.AppDatabase
import site.anzz.childkiosk.data.SystemConfigEntity
import site.anzz.childkiosk.data.WebAppEntity
import site.anzz.childkiosk.util.HashUtils
import site.anzz.childkiosk.util.KioskPrefs
import site.anzz.childkiosk.util.WebDataManager
import site.anzz.childkiosk.util.WebDataStats
import site.anzz.childkiosk.util.WebViewProviderDiagnostics
import site.anzz.childkiosk.util.WebViewProviderSnapshot
import site.anzz.childkiosk.util.WebViewRuntime
import site.anzz.childkiosk.util.WebViewPool
import site.anzz.childkiosk.util.WhitelistSubscriptionRepository
import site.anzz.childkiosk.util.filter.FilterPreset
import site.anzz.childkiosk.util.filter.FilterRepository
import site.anzz.childkiosk.util.filter.SiteFilterOverride
import site.anzz.childkiosk.util.filter.normalizeHost
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminConsoleScreen(
    config: SystemConfigEntity?,
    isDeviceOwner: Boolean,
    onBack: () -> Unit,
    onExitKiosk: () -> Unit,
    onGoToHomeSettings: () -> Unit,
    onProtectionModeChanged: (String) -> Unit = {},
    onSandboxLimitsChanged: () -> Unit = {}
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val db = remember { AppDatabase.getInstance(context) }

    var webApps by remember { mutableStateOf<List<WebAppEntity>>(emptyList()) }
    var showAddDialog by remember { mutableStateOf(false) }
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

    // 当前二级子页面导航状态：null 代表首页目录
    var currentSubPage by remember { mutableStateOf<String?>(null) }

    // 接管返回键/手势：如果在二级子页面则返回后台主页，如果在后台主页则退出后台
    BackHandler(enabled = true) {
        if (currentSubPage != null) {
            currentSubPage = null
        } else {
            onBack()
        }
    }

    val currentVersion = remember {
        try {
            val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            pInfo.versionName ?: "未知"
        } catch (e: Exception) {
            "未知"
        }
    }

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
                        "SANDBOX_LIMITS" -> "安全沙箱与限制"
                        "PERFORMANCE" -> "网页性能优化"
                        "WEBVIEW_PROVIDER" -> "WebView 内核环境"
                        "WHITELIST" -> "应用白名单管理"
                        else -> "配置后台"
                    }
                    Text(titleText, fontWeight = FontWeight.Bold)
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (currentSubPage != null) {
                            currentSubPage = null
                        } else {
                            onBack()
                        }
                    }) {
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
                                }
                            }
                        }

                        // 关于与系统诊断卡片 (保留在首页下方)
                        item {
                            AboutAndSystemCard(
                                currentVersion = currentVersion,
                                webViewInfo = webViewSnapshot.providerSummary,
                                deviceInfo = webViewSnapshot.deviceModel,
                                androidVersion = webViewSnapshot.androidVersion,
                                protectionLevel = protectionLevel,
                                isCheckingUpdate = isCheckingUpdate,
                                onCheckUpdate = {
                                    isCheckingUpdate = true
                                    scope.launch {
                                        val release = fetchLatestRelease()
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
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                            ) {
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
                    var limitUrlRedirect by remember { mutableStateOf(KioskPrefs.isLimitUrlRedirectEnabled(context)) }
                    var floatingBrowserControlsEnabled by remember {
                        mutableStateOf(KioskPrefs.isFloatingBrowserControlsEnabled(context))
                    }
                    var limitGeolocation by remember { mutableStateOf(KioskPrefs.isLimitGeolocationEnabled(context)) }
                    var limitSslCheck by remember { mutableStateOf(KioskPrefs.isLimitSslCheckEnabled(context)) }
                    var limitMultiWindow by remember { mutableStateOf(KioskPrefs.isLimitMultiWindowEnabled(context)) }
                    var limitFileAccess by remember { mutableStateOf(KioskPrefs.isLimitFileAccessEnabled(context)) }
                    var limitMediaCapture by remember { mutableStateOf(KioskPrefs.isLimitMediaCaptureEnabled(context)) }
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
                                            placeholder = { Text("https://example.com/custom.js", fontSize = 12.sp) },
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
                                            onSandboxLimitsChanged()
                                        }
                                    )
                                }

                                Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))

                                // 选项 6: 摄像头/麦克风限制
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("禁用网页摄像头与麦克风", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                        Text("默认允许网页按需申请媒体采集能力；开启后拒绝 WebRTC、拍照、录音、扫码等摄像头/麦克风请求", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Switch(
                                        checked = limitMediaCapture,
                                        onCheckedChange = {
                                            limitMediaCapture = it
                                            KioskPrefs.setLimitMediaCaptureEnabled(context, it)
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
            }
        }
    }

    // 添加 / 编辑 Web 应用 Dialog
    if (showAddDialog || editingWebApp != null) {
        val appToEdit = editingWebApp
        AddEditWebAppDialog(
            app = appToEdit,
            onDismiss = {
                showAddDialog = false
                editingWebApp = null
            },
            onSave = { title, url, icon, category ->
                scope.launch(Dispatchers.IO) {
                    if (appToEdit == null) {
                        db.webAppDao().insertWebApp(
                            WebAppEntity(title = title, url = url, iconPath = icon, isPreset = false, category = category)
                        )
                    } else {
                        db.webAppDao().updateWebApp(
                            appToEdit.copy(title = title, url = url, iconPath = icon, category = category)
                        )
                    }
                }
                showAddDialog = false
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
    var settingsVersion by remember { mutableStateOf(0) }
    val settings = remember(settingsVersion) { FilterRepository.getSettings(context) }
    val report = remember(settingsVersion) {
        FilterRepository.getEngine(context, settings.toRuntimeSnapshot()).report
    }
    var customRules by remember(settings.customRules) { mutableStateOf(settings.customRules) }
    var customRuleReport by remember { mutableStateOf(FilterRepository.validateCustomRules(customRules)) }
    var newOverrideHost by remember { mutableStateOf("") }
    var customSubscriptionTitle by remember { mutableStateOf("") }
    var customSubscriptionUrl by remember { mutableStateOf("") }
    var updatingSubscriptionId by remember { mutableStateOf<String?>(null) }
    val events = remember(settingsVersion) { FilterRepository.getRecentEvents(context) }
    val scope = rememberCoroutineScope()

    fun refresh() {
        settingsVersion++
        onFilteringChanged()
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
                            "兼容 ABP/EasyList、uBlock Origin 静态规则和 AdGuard 常用规则语法。",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = settings.enabled,
                        onCheckedChange = { enabled ->
                            KioskPrefs.setLimitAdBlockEnabled(context, enabled)
                            FilterRepository.setEnabled(context, enabled)
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

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("过滤强度", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    FilterPreset.entries.forEach { preset ->
                        FilterPresetOption(
                            selected = settings.preset == preset,
                            preset = preset,
                            onClick = {
                                FilterRepository.setPreset(context, preset)
                                refresh()
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
                                    FilterRepository.setSubscriptionEnabled(context, subscription.id, enabled)
                                    refresh()
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
                            "更新时间：${subscription.lastUpdatedAt}${if (subscription.lastError.isNotBlank()) " | ${subscription.lastError}" else ""}",
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
                    "支持 `||domain^`、`@@`、`${'$'}script`、`${'$'}image`、`${'$'}popup`、`${'$'}third-party`、`${'$'}domain=`、`##`、`#@#` 等核心语法。",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = customRules,
                    onValueChange = {
                        customRules = it
                        customRuleReport = FilterRepository.validateCustomRules(it)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 140.dp, max = 240.dp),
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp, fontFamily = FontFamily.Monospace),
                    placeholder = { Text("||example-ad.com^\n@@||example.com/allowed.js${'$'}script") }
                )
                Text(
                    "校验：启用 ${customRuleReport.enabledRuleCount}/${customRuleReport.ruleCount}，不支持 ${customRuleReport.unsupportedRuleCount}",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
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
                            customRuleReport = FilterRepository.validateCustomRules("")
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
                        placeholder = { Text("example.com") }
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
                                        override.copy(temporaryAllowUntil = System.currentTimeMillis() + 15 * 60 * 1000L)
                                    )
                                    refresh()
                                }) {
                                    Text("放行15分钟")
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
                                .clip(RoundedCornerShape(10.dp))
                                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.7f))
                                .padding(10.dp)
                        ) {
                            Text("${event.action} | ${event.resourceType}", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            Text(event.url, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                            if (event.ruleText.isNotBlank()) {
                                Text("${event.sourceName}: ${event.ruleText}", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FilterPresetOption(
    selected: Boolean,
    preset: FilterPreset,
    onClick: () -> Unit
) {
    val description = when (preset) {
        FilterPreset.LIGHT -> "仅轻量本地高置信规则，优先兼容性和低性能设备。"
        FilterPreset.STANDARD_CHILD -> "儿童模式默认，启用 EasyList、EasyPrivacy、中文和移动广告规则。"
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

private fun formatTimestamp(timestamp: Long): String {
    if (timestamp <= 0L) return "从未"
    return runCatching {
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date(timestamp))
    }.getOrDefault(timestamp.toString())
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
            val isNetworkIcon = iconPath.startsWith("http://", ignoreCase = true) || 
                                iconPath.startsWith("https://", ignoreCase = true)
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                if (isNetworkIcon) {
                    coil.compose.AsyncImage(
                        model = iconPath,
                        contentDescription = app.title,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                        error = androidx.compose.ui.graphics.vector.rememberVectorPainter(Icons.Default.Star)
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

fun getIconVector(iconName: String?): ImageVector {
    return when (iconName) {
        "icon_gamepad" -> Icons.Default.SportsEsports
        "icon_rocket" -> Icons.Default.Star
        "icon_puzzle" -> Icons.Default.Extension
        "icon_book" -> Icons.Default.MenuBook
        "icon_paint" -> Icons.Default.Palette
        "icon_pet" -> Icons.Default.Pets
        "icon_music" -> Icons.Default.MusicNote
        "icon_school" -> Icons.Default.School
        "icon_lightbulb" -> Icons.Default.Lightbulb
        "icon_toy" -> Icons.Default.Face
        "icon_gift" -> Icons.Default.Favorite
        "icon_home" -> Icons.Default.Home
        else -> Icons.Default.Star
    }
}

private fun isValidUrl(url: String): Boolean {
    val trimmed = url.trim()
    val hasProtocol = trimmed.startsWith("http://", ignoreCase = true) || 
                      trimmed.startsWith("https://", ignoreCase = true)
    val urlToCheck = if (hasProtocol) trimmed else "https://$trimmed"
    return android.util.Patterns.WEB_URL.matcher(urlToCheck).matches()
}

private fun formatUrl(url: String): String {
    val trimmed = url.trim()
    return when {
        trimmed.startsWith("http://", ignoreCase = true) -> trimmed
        trimmed.startsWith("https://", ignoreCase = true) -> trimmed
        else -> "https://$trimmed"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddEditWebAppDialog(
    app: WebAppEntity?,
    onDismiss: () -> Unit,
    onSave: (title: String, url: String, icon: String, category: String) -> Unit
) {
    var title by remember { mutableStateOf(app?.title ?: "") }
    var urlInput by remember { mutableStateOf(app?.url ?: "") }
    var category by remember { mutableStateOf(app?.category ?: WebAppEntity.CATEGORY_GAME) }
    
    // 如果已有应用且 iconPath 是网络地址，初始化 customIconUrl，否则为空
    var customIconUrl by remember { 
        mutableStateOf(if (app?.iconPath?.startsWith("http", ignoreCase = true) == true) app.iconPath else "") 
    }
    var selectedIcon by remember { mutableStateOf(app?.iconPath ?: "icon_gamepad") }
    
    var isCheckingUrl by remember { mutableStateOf(false) }
    var urlError by remember { mutableStateOf<String?>(null) }
    var pingFailedOnce by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()

    // 自动推导网站默认的 favicon.ico
    LaunchedEffect(urlInput) {
        val trimmed = urlInput.trim()
        if (trimmed.isNotEmpty() && isValidUrl(trimmed)) {
            val formatted = formatUrl(trimmed)
            try {
                val uri = java.net.URI(formatted)
                val host = uri.host
                val scheme = uri.scheme ?: "https"
                val port = if (uri.port != -1) ":${uri.port}" else ""
                if (host != null && host.isNotEmpty()) {
                    val inferredFavicon = "$scheme://$host$port/favicon.ico"
                    // 只有在 customIconUrl 为空时才进行初始化自动填充
                    if (customIconUrl.isEmpty()) {
                        customIconUrl = inferredFavicon
                        // 如果是新应用，且没有改过内置图标，默认直接勾选这个推导出的 Favicon
                        if (app == null && selectedIcon == "icon_gamepad") {
                            selectedIcon = inferredFavicon
                        }
                    }
                }
            } catch (e: Exception) {
                // 忽略解析异常
            }
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

    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.fillMaxWidth().padding(16.dp)
        ) {
            Column(
                modifier = Modifier
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = if (app == null) "添加应用" else "编辑应用",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )

                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("应用名称 (如 Scratch)") },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = urlInput,
                    onValueChange = {
                        urlInput = it
                        urlError = null
                    },
                    label = { Text("应用链接 (如 scratch.mit.edu)") },
                    shape = RoundedCornerShape(12.dp),
                    isError = urlError != null,
                    modifier = Modifier.fillMaxWidth()
                )

                if (urlInput.trim().startsWith("http://", ignoreCase = true)) {
                    Text(
                        text = "⚠️ 警告：当前添加的是未加密的 HTTP 网站。在公共网络中可能会有被监听或劫持的风险，建议使用 HTTPS。",
                        color = Color(0xFFE65100), // 橙色警告
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

                // 分类选择
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

                // 图标选择
                Text("选择代表图标：", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(bottom = 4.dp)
                ) {
                    val icons = listOf(
                        "icon_gamepad" to Icons.Default.SportsEsports,
                        "icon_rocket" to Icons.Default.Star,
                        "icon_puzzle" to Icons.Default.Extension,
                        "icon_book" to Icons.Default.MenuBook,
                        "icon_paint" to Icons.Default.Palette,
                        "icon_pet" to Icons.Default.Pets,
                        "icon_music" to Icons.Default.MusicNote,
                        "icon_school" to Icons.Default.School,
                        "icon_lightbulb" to Icons.Default.Lightbulb,
                        "icon_toy" to Icons.Default.Face,
                        "icon_gift" to Icons.Default.Favorite,
                        "icon_home" to Icons.Default.Home
                    )

                    icons.forEach { (name, vec) ->
                        val selected = selectedIcon == name
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(
                                    if (selected) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.surfaceVariant
                                )
                                .clickable { selectedIcon = name }
                        ) {
                            Icon(
                                imageVector = vec,
                                contentDescription = name,
                                tint = if (selected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                // 自定义网络图片/Favicon 图标
                val isCustomSelected = selectedIcon.startsWith("http://", ignoreCase = true) || 
                                       selectedIcon.startsWith("https://", ignoreCase = true)
                
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
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
                                    } else {
                                        selectedIcon = "https://example.com/favicon.ico"
                                        customIconUrl = "https://example.com/favicon.ico"
                                    }
                                }
                        ) {
                            RadioButton(
                                selected = isCustomSelected,
                                onClick = {
                                    if (customIconUrl.trim().isNotEmpty()) {
                                        selectedIcon = customIconUrl.trim()
                                    } else {
                                        selectedIcon = "https://example.com/favicon.ico"
                                        customIconUrl = "https://example.com/favicon.ico"
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
                                if (customIconUrl.trim().startsWith("http", ignoreCase = true)) {
                                    coil.compose.AsyncImage(
                                        model = customIconUrl.trim(),
                                        contentDescription = "预览",
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                        error = androidx.compose.ui.graphics.vector.rememberVectorPainter(Icons.Default.Warning)
                                    )
                                } else {
                                    Icon(
                                        imageVector = Icons.Default.Warning,
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
                                placeholder = { Text("图标网址，例如 example.com/logo.png") },
                                shape = RoundedCornerShape(10.dp),
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                                colors = TextFieldDefaults.outlinedTextFieldColors(
                                    containerColor = MaterialTheme.colorScheme.surface,
                                    unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                                )
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

                            if (pingFailedOnce) {
                                // 第二次点击：强行保存
                                onSave(title, formattedUrl, selectedIcon, category)
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
                                    onSave(title, formattedUrl, selectedIcon, category)
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
    val changelog: String,
    val releasePageUrl: String
)

suspend fun fetchLatestRelease(): ReleaseInfo? = withContext(Dispatchers.IO) {
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
            if (assets != null && assets.length() > 0) {
                for (i in 0 until assets.length()) {
                    val asset = assets.getJSONObject(i)
                    val name = asset.optString("name", "")
                    if (name.endsWith(".apk")) {
                        apkUrl = asset.optString("browser_download_url", "")
                        break
                    }
                }
            }
            if (apkUrl.isEmpty()) {
                apkUrl = htmlUrl
            }
            
            ReleaseInfo(
                version = tagName.trimStart('v'),
                downloadUrl = apkUrl,
                changelog = body,
                releasePageUrl = htmlUrl
            )
        } else {
            null
        }
    }.getOrNull()
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

@Composable
fun AboutAndSystemCard(
    currentVersion: String,
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
fun DiagnosticItem(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        Text(
            label,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.widthIn(min = 72.dp, max = 120.dp)
        )
        Text(
            value,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .padding(start = 16.dp)
                .weight(1f)
        )
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
    
    Dialog(onDismissRequest = onDismiss) {
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

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("稍后再说")
                    }

                    Button(
                        onClick = {
                            clipboardManager.setText(AnnotatedString(releaseInfo.downloadUrl))
                            Toast.makeText(context, "下载链接已复制到剪贴板！", Toast.LENGTH_SHORT).show()
                            
                            try {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(releaseInfo.releasePageUrl))
                                context.startActivity(intent)
                            } catch (e: Exception) {
                                Toast.makeText(context, "无法打开浏览器，请手动粘贴链接下载", Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier.weight(1.5f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("去下载更新")
                    }
                }
            }
        }
    }
}
