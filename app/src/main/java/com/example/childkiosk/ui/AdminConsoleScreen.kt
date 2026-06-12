package com.example.childkiosk.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import com.example.childkiosk.data.AppDatabase
import com.example.childkiosk.data.SystemConfigEntity
import com.example.childkiosk.data.WebAppEntity
import com.example.childkiosk.util.HashUtils
import com.example.childkiosk.util.KioskPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminConsoleScreen(
    config: SystemConfigEntity?,
    isDeviceOwner: Boolean,
    onBack: () -> Unit,
    onExitKiosk: () -> Unit,
    onGoToHomeSettings: () -> Unit
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val db = remember { AppDatabase.getInstance(context) }

    var webApps by remember { mutableStateOf<List<WebAppEntity>>(emptyList()) }
    var showAddDialog by remember { mutableStateOf(false) }
    var editingWebApp by remember { mutableStateOf<WebAppEntity?>(null) }

    // 家长设置状态
    var timeLimit by remember { mutableIntStateOf(config?.timeLimitMinutes ?: 0) }
    var dailyLimit by remember { mutableIntStateOf(config?.dailyLimitMinutes ?: 0) }
    var verificationMode by remember { mutableStateOf(config?.verificationMode ?: "MATH") }

    // 非 Device Owner 场景下的防护等级（屏幕固定软锁 / 无系统级锁定）
    var protectionMode by remember { mutableStateOf(KioskPrefs.getProtectionMode(context)) }

    var showPinSetupDialog by remember { mutableStateOf(false) }

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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("家长管理后台", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
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
            FloatingActionButton(
                onClick = { showAddDialog = true },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            ) {
                Icon(imageVector = Icons.Default.Add, contentDescription = "添加应用")
            }
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // Section 0: 防护等级（仅在非 Device Owner 时展示可切换项与激活引导）
            item {
                ProtectionLevelCard(
                    isDeviceOwner = isDeviceOwner,
                    protectionMode = protectionMode,
                    onModeChange = { mode ->
                        protectionMode = mode
                        KioskPrefs.setProtectionMode(context, mode)
                    },
                    onCopyScript = {
                        clipboardManager.setText(
                            AnnotatedString("adb shell dpm set-device-owner com.example.childkiosk/.MyDeviceAdminReceiver")
                        )
                        Toast.makeText(context, "ADB 激活脚本已复制到剪贴板！", Toast.LENGTH_SHORT).show()
                    },
                    onGoToHomeSettings = onGoToHomeSettings
                )
            }

            // Section 1: 健康使用时长管理
            item {
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

            // Section 2: 安全退出验证设置
            item {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(bottom = 12.dp)
                        ) {
                            Icon(imageVector = Icons.Default.Lock, contentDescription = "验证", tint = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("家长身份验证配置", fontSize = 16.sp, fontWeight = FontWeight.Bold)
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
                                Text("修改家长数字 PIN 码")
                            }
                        }
                    }
                }
            }

            // Section 3: Web 应用列表管理
            item {
                Text(
                    text = "应用白名单列表",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }

            items(webApps) { app ->
                WebAppCard(
                    app = app,
                    onEdit = { editingWebApp = app },
                    onDelete = {
                        scope.launch(Dispatchers.IO) {
                            db.webAppDao().deleteWebApp(app)
                        }
                        Toast.makeText(context, "已删除应用", Toast.LENGTH_SHORT).show()
                    }
                )
            }

            // Section 4: 退出并解锁
            item {
                Spacer(modifier = Modifier.height(8.dp))
                QButton(
                    onClick = onExitKiosk,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("退出并安全解锁（返回系统桌面）", fontSize = 15.sp, fontWeight = FontWeight.Bold)
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
            onSave = { title, url, icon ->
                scope.launch(Dispatchers.IO) {
                    if (appToEdit == null) {
                        db.webAppDao().insertWebApp(
                            WebAppEntity(title = title, url = url, iconPath = icon, isPreset = false)
                        )
                    } else {
                        db.webAppDao().updateWebApp(
                            appToEdit.copy(title = title, url = url, iconPath = icon)
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
                desc = "仅沉浸式全屏 + 家长验证退出，不调用屏幕固定。适合开发调试或仅需防误触的场景。",
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
                    text = "adb shell dpm set-device-owner com.example.childkiosk/.MyDeviceAdminReceiver",
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
fun WebAppCard(
    app: WebAppEntity,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                // 图标
                val iconVector = getIconVector(app.iconPath)
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = iconVector,
                        contentDescription = app.title,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(28.dp)
                    )
                }
                Spacer(modifier = Modifier.width(16.dp))

                Column {
                    Text(
                        text = app.title,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = app.url,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
            }

            Row {
                IconButton(onClick = onEdit) {
                    Icon(imageVector = Icons.Default.Edit, contentDescription = "编辑", tint = MaterialTheme.colorScheme.primary)
                }
                // 预设应用不允许删除以确保开箱可用
                if (!app.isPreset) {
                    IconButton(onClick = onDelete) {
                        Icon(imageVector = Icons.Default.Delete, contentDescription = "删除", tint = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}

fun getIconVector(iconName: String?): ImageVector {
    return when (iconName) {
        "icon_gamepad" -> Icons.Default.PlayArrow
        "icon_rocket" -> Icons.Default.Star
        "icon_puzzle" -> Icons.Default.Face
        "icon_book" -> Icons.Default.Home
        "icon_paint" -> Icons.Default.Build
        else -> Icons.Default.Star
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddEditWebAppDialog(
    app: WebAppEntity?,
    onDismiss: () -> Unit,
    onSave: (title: String, url: String, icon: String) -> Unit
) {
    var title by remember { mutableStateOf(app?.title ?: "") }
    var urlInput by remember { mutableStateOf(app?.url ?: "") }
    var selectedIcon by remember { mutableStateOf(app?.iconPath ?: "icon_gamepad") }
    
    var isCheckingUrl by remember { mutableStateOf(false) }
    var urlError by remember { mutableStateOf<String?>(null) }
    var pingFailedOnce by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()
    
    fun isValidUrl(url: String): Boolean {
        val pattern = "^(https?://)?([a-zA-Z0-9-]+\\.)+[a-zA-Z]{2,}(:\\d+)?(/.*)?$".toRegex()
        return pattern.matches(url)
    }

    fun formatUrl(url: String): String {
        val trimmed = url.trim()
        return when {
            trimmed.startsWith("http://", ignoreCase = true) -> trimmed.replaceFirst("http://", "https://", ignoreCase = true)
            trimmed.startsWith("https://", ignoreCase = true) -> trimmed
            else -> "https://$trimmed"
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
                modifier = Modifier.padding(20.dp),
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

                if (urlError != null) {
                    Text(
                        text = urlError ?: "",
                        color = if (pingFailedOnce) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error,
                        fontSize = 12.sp
                    )
                }

                // 图标选择
                Text("选择代表图标：", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    val icons = listOf(
                        "icon_gamepad" to Icons.Default.PlayArrow,
                        "icon_rocket" to Icons.Default.Star,
                        "icon_puzzle" to Icons.Default.Face,
                        "icon_book" to Icons.Default.Home,
                        "icon_paint" to Icons.Default.Build
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
                                onSave(title, formattedUrl, selectedIcon)
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
                                    onSave(title, formattedUrl, selectedIcon)
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
                Text("设置家长 4 位数字密码", fontSize = 18.sp, fontWeight = FontWeight.Bold)

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
