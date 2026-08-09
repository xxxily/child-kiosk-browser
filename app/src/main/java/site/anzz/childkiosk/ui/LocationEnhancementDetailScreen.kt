package site.anzz.childkiosk.ui

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.webkit.WebViewFeature
import site.anzz.childkiosk.BuildConfig
import site.anzz.childkiosk.util.KioskPrefs
import site.anzz.childkiosk.util.NativeLocationManager
import site.anzz.childkiosk.util.NativeLocationResult

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LocationEnhancementDetailScreen(
    currentSigningIdentity: AppSigningIdentity,
    onCapabilityChanged: (recreateWebViews: Boolean) -> Unit
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    var limitGeolocation by remember { mutableStateOf(KioskPrefs.isLimitGeolocationEnabled(context)) }
    var nativeLocationOptimizationEnabled by remember {
        mutableStateOf(KioskPrefs.isNativeLocationOptimizationEnabled(context))
    }
    var nativeLocationWarmupEnabled by remember {
        mutableStateOf(KioskPrefs.isNativeLocationWarmupEnabled(context))
    }
    var nativeLocationBridgeEnabled by remember {
        mutableStateOf(KioskPrefs.isNativeLocationBridgeEnabled(context))
    }
    var nativeLocationMode by remember { mutableStateOf(KioskPrefs.getNativeLocationMode(context)) }
    var nativeLocationWarmupTimeoutMs by remember {
        mutableStateOf(KioskPrefs.getNativeLocationWarmupTimeoutMs(context))
    }
    var nativeLocationRequestTimeoutMs by remember {
        mutableStateOf(KioskPrefs.getNativeLocationRequestTimeoutMs(context))
    }
    var nativeLocationMaxCacheAgeMs by remember {
        mutableStateOf(KioskPrefs.getNativeLocationMaxCacheAgeMs(context))
    }
    var nativeLocationWatchMaxDurationMs by remember {
        mutableStateOf(KioskPrefs.getNativeLocationWatchMaxDurationMs(context))
    }
    var nativeLocationAllowedOrigins by remember {
        mutableStateOf(KioskPrefs.getNativeLocationBridgeAllowedOrigins(context))
    }
    var nativeLocationOriginInput by rememberSaveable { mutableStateOf("") }
    var amapLocationEnabled by remember { mutableStateOf(KioskPrefs.isAmapLocationEnabled(context)) }
    var amapLocationApiKey by remember { mutableStateOf(KioskPrefs.getAmapLocationApiKey(context)) }
    var amapLocationPrivacyAgreed by remember {
        mutableStateOf(KioskPrefs.isAmapLocationPrivacyAgreed(context))
    }
    var amapLocationProviderStrategy by remember {
        mutableStateOf(KioskPrefs.getAmapLocationProviderStrategy(context))
    }
    var amapLocationH5AssistantEnabled by remember {
        mutableStateOf(KioskPrefs.isAmapLocationH5AssistantEnabled(context))
    }
    var amapLocationH5AssistantAllowedOrigins by remember {
        mutableStateOf(KioskPrefs.getAmapLocationH5AssistantAllowedOrigins(context))
    }
    var nativeLocationCoordinateMode by remember {
        mutableStateOf(KioskPrefs.getNativeLocationCoordinateMode(context))
    }
    var nativeLocationGcj02AllowedOrigins by remember {
        mutableStateOf(KioskPrefs.getNativeLocationGcj02AllowedOrigins(context))
    }
    var amapH5OriginInput by rememberSaveable { mutableStateOf("") }
    var nativeLocationGcj02OriginInput by rememberSaveable { mutableStateOf("") }
    val nativeLocationManager = remember { NativeLocationManager(context) }
    var nativeLocationDiagnostics by remember { mutableStateOf(nativeLocationManager.diagnosticSummary()) }
    var nativeLocationAuditRecords by remember { mutableStateOf(nativeLocationManager.auditRecords()) }
    var showNativeLocationDiagnosticsDialog by remember { mutableStateOf(false) }
    var nativeLocationTesting by remember { mutableStateOf(false) }
    val nativeLocationBridgeRuntimeReady = remember {
        WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) &&
            WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
    }

    fun refreshNativeLocationDiagnostics() {
        limitGeolocation = KioskPrefs.isLimitGeolocationEnabled(context)
        nativeLocationDiagnostics = nativeLocationManager.diagnosticSummary()
        nativeLocationAuditRecords = nativeLocationManager.auditRecords()
    }

    fun clearNativeLocationAuditRecords() {
        nativeLocationManager.clearAuditRecords()
        nativeLocationAuditRecords = emptyList()
        nativeLocationDiagnostics = nativeLocationManager.diagnosticSummary()
        Toast.makeText(context, "定位记录已清空", Toast.LENGTH_SHORT).show()
    }

    fun onNativeLocationConfigChanged(recreateWebViews: Boolean = true) {
        refreshNativeLocationDiagnostics()
        onCapabilityChanged(recreateWebViews)
        Toast.makeText(context, "新打开的网站生效", Toast.LENGTH_SHORT).show()
    }

    fun runNativeLocationTest() {
        if (nativeLocationTesting) return
        nativeLocationTesting = true
        nativeLocationManager.requestSingleLocation(
            config = KioskPrefs.getWebViewRuntimeConfig(context).copy(
                nativeLocationOptimizationEnabled = true,
                amapLocationEnabled = amapLocationEnabled,
                amapLocationApiKey = amapLocationApiKey,
                amapLocationPrivacyAgreed = amapLocationPrivacyAgreed,
                amapLocationProviderStrategy = amapLocationProviderStrategy,
                amapLocationH5AssistantEnabled = amapLocationH5AssistantEnabled,
                amapLocationH5AssistantAllowedOrigins = amapLocationH5AssistantAllowedOrigins,
                nativeLocationCoordinateMode = nativeLocationCoordinateMode,
                nativeLocationGcj02AllowedOrigins = nativeLocationGcj02AllowedOrigins
            ),
            allowCached = true,
            purpose = "admin_test"
        ) { result: NativeLocationResult ->
            nativeLocationTesting = false
            refreshNativeLocationDiagnostics()
            nativeLocationDiagnostics = result.toDiagnosticLine(redactCoordinates = true) +
                "\n\n" + nativeLocationDiagnostics
        }
    }

    val nativeLocationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted =
            permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true ||
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            runNativeLocationTest()
        } else {
            nativeLocationTesting = false
            nativeLocationDiagnostics = "缺少系统定位权限，无法测试。"
        }
    }

    fun testNativeLocationWithPermission() {
        val granted =
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            runNativeLocationTest()
        } else {
            nativeLocationTesting = true
            nativeLocationPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
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
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(imageVector = Icons.Default.Extension, contentDescription = "能力增强", tint = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("能力增强", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.65f))
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.LocationOn,
                        contentDescription = "网页定位增强",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("网页定位增强", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        Text(
                            if (nativeLocationOptimizationEnabled) {
                                "已开启 · ${if (BuildConfig.AMAP_LOCATION_SDK_INCLUDED) "系统/高德" else "系统定位"}"
                            } else {
                                "未开启"
                            },
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
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
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("启用定位增强", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        Text("为可信网页提供系统或高德定位", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(
                        checked = nativeLocationOptimizationEnabled,
                        enabled = !limitGeolocation,
                        onCheckedChange = {
                            nativeLocationOptimizationEnabled = it
                            KioskPrefs.setNativeLocationOptimizationEnabled(context, it)
                            onNativeLocationConfigChanged()
                        }
                    )
                }

                if (limitGeolocation) {
                    Text(
                        "网页定位已被安全限制禁用。",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.65f))
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("高德定位 SDK", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Text(
                        if (BuildConfig.AMAP_LOCATION_SDK_INCLUDED) {
                            "enhanced · 高德定位 SDK ${BuildConfig.AMAP_LOCATION_SDK_VERSION}"
                        } else {
                            "standard · 仅系统定位；高德定位需 enhanced APK"
                        },
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    if (BuildConfig.AMAP_LOCATION_SDK_INCLUDED) {
                        OutlinedTextField(
                            value = amapLocationApiKey,
                            onValueChange = {
                                amapLocationApiKey = it.trim()
                                KioskPrefs.setAmapLocationApiKey(context, amapLocationApiKey)
                                refreshNativeLocationDiagnostics()
                            },
                            enabled = !limitGeolocation,
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("高德 Android SDK Key") },
                            placeholder = { Text("在高德开放平台申请", fontSize = 12.sp) },
                            singleLine = true,
                            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp)
                        )
                        Text(
                            "Key：${KioskPrefs.maskedAmapLocationApiKey(context)} · 新网站生效",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        AmapKeyBindingInfo(
                            identity = currentSigningIdentity,
                            onCopy = { text, label ->
                                clipboardManager.setText(AnnotatedString(text))
                                Toast.makeText(context, "$label 已复制", Toast.LENGTH_SHORT).show()
                            }
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("高德隐私合规已确认", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                Text("确认已披露 SDK 信息采集和隐私政策", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(
                                checked = amapLocationPrivacyAgreed,
                                enabled = !limitGeolocation,
                                onCheckedChange = {
                                    amapLocationPrivacyAgreed = it
                                    KioskPrefs.setAmapLocationPrivacyAgreed(context, it)
                                    onNativeLocationConfigChanged()
                                }
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("启用高德定位", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                Text("需填写 Key 并确认隐私，仅用于允许列表", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(
                                checked = amapLocationEnabled,
                                enabled = !limitGeolocation && nativeLocationOptimizationEnabled &&
                                    amapLocationApiKey.isNotBlank() &&
                                    amapLocationPrivacyAgreed,
                                onCheckedChange = {
                                    amapLocationEnabled = it
                                    KioskPrefs.setAmapLocationEnabled(context, it)
                                    onNativeLocationConfigChanged()
                                }
                            )
                        }

                        Text("定位来源", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            listOf(
                                KioskPrefs.NATIVE_LOCATION_PROVIDER_AMAP_FIRST to "高德优先",
                                KioskPrefs.NATIVE_LOCATION_PROVIDER_SYSTEM to "系统原生",
                                KioskPrefs.NATIVE_LOCATION_PROVIDER_AMAP_ONLY to "仅高德"
                            ).forEach { (strategy, label) ->
                                FilterChip(
                                    selected = amapLocationProviderStrategy == strategy,
                                    enabled = nativeLocationOptimizationEnabled && !limitGeolocation,
                                    onClick = {
                                        amapLocationProviderStrategy = strategy
                                        KioskPrefs.setAmapLocationProviderStrategy(context, strategy)
                                        onNativeLocationConfigChanged(recreateWebViews = false)
                                    },
                                    label = { Text(label, fontSize = 12.sp) }
                                )
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("预热定位缓存", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Text("页面加载时提前获取位置，供网页复用", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(
                        checked = nativeLocationWarmupEnabled,
                        enabled = !limitGeolocation && nativeLocationOptimizationEnabled,
                        onCheckedChange = {
                            nativeLocationWarmupEnabled = it
                            KioskPrefs.setNativeLocationWarmupEnabled(context, it)
                            onNativeLocationConfigChanged()
                        }
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("托管 Geolocation", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Text("为允许列表注入定位桥接", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(
                        checked = nativeLocationBridgeEnabled,
                        enabled = !limitGeolocation && nativeLocationOptimizationEnabled,
                        onCheckedChange = {
                            nativeLocationBridgeEnabled = it
                            KioskPrefs.setNativeLocationBridgeEnabled(context, it)
                            onNativeLocationConfigChanged()
                        }
                    )
                }

                if (!nativeLocationBridgeRuntimeReady) {
                    Text(
                        "当前 WebView 不完整支持所需接口，托管定位可能不可用。可使用预热模式。",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                Text("定位模式", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(
                        KioskPrefs.NATIVE_LOCATION_MODE_COMPAT to "兼容",
                        KioskPrefs.NATIVE_LOCATION_MODE_HIGH_ACCURACY to "高精度",
                        KioskPrefs.NATIVE_LOCATION_MODE_LOW_POWER to "低功耗"
                    ).forEach { (mode, label) ->
                        FilterChip(
                            selected = nativeLocationMode == mode,
                            enabled = nativeLocationOptimizationEnabled && !limitGeolocation,
                            onClick = {
                                nativeLocationMode = mode
                                KioskPrefs.setNativeLocationMode(context, mode)
                                onNativeLocationConfigChanged(recreateWebViews = false)
                            },
                            label = { Text(label, fontSize = 12.sp) }
                        )
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    LocationValueChip(
                        label = "请求超时",
                        value = "${nativeLocationRequestTimeoutMs / 1000}s",
                        enabled = nativeLocationOptimizationEnabled && !limitGeolocation,
                        onMinus = {
                            nativeLocationRequestTimeoutMs = (nativeLocationRequestTimeoutMs - 1000L).coerceAtLeast(3_000L)
                            KioskPrefs.setNativeLocationRequestTimeoutMs(context, nativeLocationRequestTimeoutMs)
                            onNativeLocationConfigChanged(recreateWebViews = false)
                        },
                        onPlus = {
                            nativeLocationRequestTimeoutMs = (nativeLocationRequestTimeoutMs + 1000L).coerceAtMost(30_000L)
                            KioskPrefs.setNativeLocationRequestTimeoutMs(context, nativeLocationRequestTimeoutMs)
                            onNativeLocationConfigChanged(recreateWebViews = false)
                        }
                    )
                    LocationValueChip(
                        label = "预热超时",
                        value = "${nativeLocationWarmupTimeoutMs / 1000}s",
                        enabled = nativeLocationOptimizationEnabled && nativeLocationWarmupEnabled && !limitGeolocation,
                        onMinus = {
                            nativeLocationWarmupTimeoutMs = (nativeLocationWarmupTimeoutMs - 1000L).coerceAtLeast(3_000L)
                            KioskPrefs.setNativeLocationWarmupTimeoutMs(context, nativeLocationWarmupTimeoutMs)
                            onNativeLocationConfigChanged(recreateWebViews = false)
                        },
                        onPlus = {
                            nativeLocationWarmupTimeoutMs = (nativeLocationWarmupTimeoutMs + 1000L).coerceAtMost(15_000L)
                            KioskPrefs.setNativeLocationWarmupTimeoutMs(context, nativeLocationWarmupTimeoutMs)
                            onNativeLocationConfigChanged(recreateWebViews = false)
                        }
                    )
                    LocationValueChip(
                        label = "缓存年龄",
                        value = "${nativeLocationMaxCacheAgeMs / 1000}s",
                        enabled = nativeLocationOptimizationEnabled && !limitGeolocation,
                        onMinus = {
                            nativeLocationMaxCacheAgeMs = (nativeLocationMaxCacheAgeMs - 10_000L).coerceAtLeast(0L)
                            KioskPrefs.setNativeLocationMaxCacheAgeMs(context, nativeLocationMaxCacheAgeMs)
                            onNativeLocationConfigChanged(recreateWebViews = false)
                        },
                        onPlus = {
                            nativeLocationMaxCacheAgeMs = (nativeLocationMaxCacheAgeMs + 10_000L).coerceAtMost(10 * 60_000L)
                            KioskPrefs.setNativeLocationMaxCacheAgeMs(context, nativeLocationMaxCacheAgeMs)
                            onNativeLocationConfigChanged(recreateWebViews = false)
                        }
                    )
                    LocationValueChip(
                        label = "Watch 最长",
                        value = "${nativeLocationWatchMaxDurationMs / 60_000L}m",
                        enabled = nativeLocationOptimizationEnabled && nativeLocationBridgeEnabled && !limitGeolocation,
                        onMinus = {
                            nativeLocationWatchMaxDurationMs = (nativeLocationWatchMaxDurationMs - 60_000L).coerceAtLeast(60_000L)
                            KioskPrefs.setNativeLocationWatchMaxDurationMs(context, nativeLocationWatchMaxDurationMs)
                            onNativeLocationConfigChanged(recreateWebViews = false)
                        },
                        onPlus = {
                            nativeLocationWatchMaxDurationMs = (nativeLocationWatchMaxDurationMs + 60_000L).coerceAtMost(60 * 60_000L)
                            KioskPrefs.setNativeLocationWatchMaxDurationMs(context, nativeLocationWatchMaxDurationMs)
                            onNativeLocationConfigChanged(recreateWebViews = false)
                        }
                    )
                }

                OutlinedTextField(
                    value = nativeLocationOriginInput,
                    onValueChange = { nativeLocationOriginInput = it },
                    enabled = nativeLocationOptimizationEnabled && nativeLocationBridgeEnabled && !limitGeolocation,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("托管允许 Origin") },
                    placeholder = { Text("https://example.com", fontSize = 12.sp) },
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp)
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    OutlinedButton(
                        enabled = nativeLocationOriginInput.isNotBlank() &&
                            nativeLocationOptimizationEnabled &&
                            nativeLocationBridgeEnabled &&
                            !limitGeolocation,
                        onClick = {
                            val normalized = KioskPrefs.normalizeOriginKey(nativeLocationOriginInput)
                            if (normalized.isBlank()) {
                                Toast.makeText(context, "请输入 http/https Origin", Toast.LENGTH_SHORT).show()
                            } else {
                                val newSet = nativeLocationAllowedOrigins.toMutableSet()
                                newSet.add(normalized)
                                nativeLocationAllowedOrigins = newSet
                                nativeLocationOriginInput = ""
                                KioskPrefs.setNativeLocationBridgeAllowedOrigins(context, newSet)
                                onNativeLocationConfigChanged()
                            }
                        },
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(imageVector = Icons.Default.Add, contentDescription = "添加", modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("添加", fontSize = 12.sp)
                    }
                }

                OriginChipGrid(
                    title = "托管允许列表",
                    origins = nativeLocationAllowedOrigins,
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
                    onRemove = { item ->
                        val newSet = nativeLocationAllowedOrigins.toMutableSet()
                        newSet.remove(item)
                        nativeLocationAllowedOrigins = newSet
                        KioskPrefs.setNativeLocationBridgeAllowedOrigins(context, newSet)
                        onNativeLocationConfigChanged()
                    }
                )

                Text("返回坐标系", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(
                        KioskPrefs.NATIVE_LOCATION_COORDINATE_WGS84 to "标准 WGS84",
                        KioskPrefs.NATIVE_LOCATION_COORDINATE_GCJ02_PER_SITE to "GCJ-02 按站点"
                    ).forEach { (mode, label) ->
                        FilterChip(
                            selected = nativeLocationCoordinateMode == mode,
                            enabled = nativeLocationOptimizationEnabled && nativeLocationBridgeEnabled && !limitGeolocation,
                            onClick = {
                                nativeLocationCoordinateMode = mode
                                KioskPrefs.setNativeLocationCoordinateMode(context, mode)
                                onNativeLocationConfigChanged(recreateWebViews = false)
                            },
                            label = { Text(label, fontSize = 12.sp) }
                        )
                    }
                }
                Text(
                    "默认返回 WGS84；仅为明确需要国内地图坐标的网站启用 GCJ-02。",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                OutlinedTextField(
                    value = nativeLocationGcj02OriginInput,
                    onValueChange = { nativeLocationGcj02OriginInput = it },
                    enabled = nativeLocationOptimizationEnabled &&
                        nativeLocationBridgeEnabled &&
                        nativeLocationCoordinateMode == KioskPrefs.NATIVE_LOCATION_COORDINATE_GCJ02_PER_SITE &&
                        !limitGeolocation,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("GCJ-02 兼容 Origin") },
                    placeholder = { Text("https://amap-web.example", fontSize = 12.sp) },
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp)
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    OutlinedButton(
                        enabled = nativeLocationGcj02OriginInput.isNotBlank() &&
                            nativeLocationOptimizationEnabled &&
                            nativeLocationBridgeEnabled &&
                            nativeLocationCoordinateMode == KioskPrefs.NATIVE_LOCATION_COORDINATE_GCJ02_PER_SITE &&
                            !limitGeolocation,
                        onClick = {
                            val normalized = KioskPrefs.normalizeOriginKey(nativeLocationGcj02OriginInput)
                            if (normalized.isBlank()) {
                                Toast.makeText(context, "请输入 http/https Origin", Toast.LENGTH_SHORT).show()
                            } else {
                                val newSet = nativeLocationGcj02AllowedOrigins.toMutableSet()
                                newSet.add(normalized)
                                nativeLocationGcj02AllowedOrigins = newSet
                                nativeLocationGcj02OriginInput = ""
                                KioskPrefs.setNativeLocationGcj02AllowedOrigins(context, newSet)
                                onNativeLocationConfigChanged(recreateWebViews = false)
                            }
                        },
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(imageVector = Icons.Default.Add, contentDescription = "添加", modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("添加", fontSize = 12.sp)
                    }
                }

                OriginChipGrid(
                    title = "GCJ-02 兼容列表",
                    origins = nativeLocationGcj02AllowedOrigins,
                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.35f),
                    onRemove = { item ->
                        val newSet = nativeLocationGcj02AllowedOrigins.toMutableSet()
                        newSet.remove(item)
                        nativeLocationGcj02AllowedOrigins = newSet
                        KioskPrefs.setNativeLocationGcj02AllowedOrigins(context, newSet)
                        onNativeLocationConfigChanged(recreateWebViews = false)
                    }
                )

                if (BuildConfig.AMAP_LOCATION_SDK_INCLUDED) {
                    Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("高德 H5 定位", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            Text("仅用于高德 JS API 的 useNative 页面", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(
                            checked = amapLocationH5AssistantEnabled,
                            enabled = !limitGeolocation &&
                                nativeLocationOptimizationEnabled &&
                                amapLocationEnabled &&
                                amapLocationApiKey.isNotBlank() &&
                                amapLocationPrivacyAgreed,
                            onCheckedChange = {
                                amapLocationH5AssistantEnabled = it
                                KioskPrefs.setAmapLocationH5AssistantEnabled(context, it)
                                onNativeLocationConfigChanged()
                            }
                        )
                    }

                    OutlinedTextField(
                        value = amapH5OriginInput,
                        onValueChange = { amapH5OriginInput = it },
                        enabled = nativeLocationOptimizationEnabled &&
                            amapLocationH5AssistantEnabled &&
                            !limitGeolocation,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("高德 H5 允许 Origin") },
                        placeholder = { Text("https://example.com", fontSize = 12.sp) },
                        singleLine = true,
                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp)
                    )
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        OutlinedButton(
                            enabled = amapH5OriginInput.isNotBlank() &&
                                nativeLocationOptimizationEnabled &&
                                amapLocationH5AssistantEnabled &&
                                !limitGeolocation,
                            onClick = {
                                val normalized = KioskPrefs.normalizeOriginKey(amapH5OriginInput)
                                if (normalized.isBlank()) {
                                    Toast.makeText(context, "请输入 http/https Origin", Toast.LENGTH_SHORT).show()
                                } else {
                                    val newSet = amapLocationH5AssistantAllowedOrigins.toMutableSet()
                                    newSet.add(normalized)
                                    amapLocationH5AssistantAllowedOrigins = newSet
                                    amapH5OriginInput = ""
                                    KioskPrefs.setAmapLocationH5AssistantAllowedOrigins(context, newSet)
                                    onNativeLocationConfigChanged()
                                }
                            },
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(imageVector = Icons.Default.Add, contentDescription = "添加", modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("添加", fontSize = 12.sp)
                        }
                    }

                    OriginChipGrid(
                        title = "高德 H5 允许列表",
                        origins = amapLocationH5AssistantAllowedOrigins,
                        color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.35f),
                        onRemove = { item ->
                            val newSet = amapLocationH5AssistantAllowedOrigins.toMutableSet()
                            newSet.remove(item)
                            amapLocationH5AssistantAllowedOrigins = newSet
                            KioskPrefs.setAmapLocationH5AssistantAllowedOrigins(context, newSet)
                            onNativeLocationConfigChanged()
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
                    Text("系统定位诊断", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Text(
                        text = nativeLocationDiagnostics.lineSequence().firstOrNull().orEmpty()
                            .ifBlank { "暂无定位诊断" },
                        fontSize = 11.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "${nativeLocationAuditRecords.size} 条记录",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        AssistChip(
                            onClick = {
                                refreshNativeLocationDiagnostics()
                                showNativeLocationDiagnosticsDialog = true
                            },
                            label = { Text("详情") },
                            leadingIcon = {
                                Icon(Icons.Default.OpenInFull, contentDescription = "查看定位诊断", modifier = Modifier.size(18.dp))
                            }
                        )
                    }
                }
            }
        }

        if (showNativeLocationDiagnosticsDialog) {
            NativeLocationDiagnosticsDialog(
                diagnostics = nativeLocationDiagnostics,
                records = nativeLocationAuditRecords,
                testing = nativeLocationTesting,
                onRefresh = { refreshNativeLocationDiagnostics() },
                onTest = { testNativeLocationWithPermission() },
                onClear = { clearNativeLocationAuditRecords() },
                onCopy = {
                    clipboardManager.setText(AnnotatedString(nativeLocationDiagnostics))
                    Toast.makeText(context, "定位诊断已复制", Toast.LENGTH_SHORT).show()
                },
                onDismiss = { showNativeLocationDiagnosticsDialog = false }
            )
        }
    }
}
