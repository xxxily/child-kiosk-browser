package site.anzz.childkiosk.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import site.anzz.childkiosk.BuildConfig
import site.anzz.childkiosk.performance.HighPerformanceCompositeState
import site.anzz.childkiosk.performance.HighPerformanceConfigRepository
import site.anzz.childkiosk.performance.HighPerformanceRuntimeStatusStore
import site.anzz.childkiosk.performance.HighPerformanceSystemStatusReader
import site.anzz.childkiosk.util.KioskPrefs

@Composable
internal fun CapabilityEnhancementsOverviewScreen(
    onOpenLocationEnhancement: () -> Unit,
    onOpenHighPerformance: () -> Unit
) {
    val context = LocalContext.current
    val highPerformanceRepository = remember { HighPerformanceConfigRepository(context) }
    var highPerformanceEnabled by remember { mutableStateOf(false) }
    var highPerformanceRuleCount by remember { mutableStateOf(0) }
    var highPerformanceRuntimeState by remember { mutableStateOf<HighPerformanceCompositeState?>(null) }
    var highPerformanceActiveCount by remember { mutableStateOf(0) }
    var highPerformanceSystemStatus by remember {
        mutableStateOf(HighPerformanceSystemStatusReader.read(context))
    }

    LaunchedEffect(highPerformanceRepository) {
        highPerformanceRepository.observePersistedState().collect { state ->
            highPerformanceEnabled = state.enabled
            highPerformanceRuleCount = state.rules.count { it.enabled }
        }
    }
    LaunchedEffect(Unit) {
        while (true) {
            val runtime = withContext(Dispatchers.IO) {
                HighPerformanceRuntimeStatusStore.read(context)
            }
            highPerformanceSystemStatus = HighPerformanceSystemStatusReader.read(context)
            val current = runtime.status?.takeUnless { runtime.stale }
            highPerformanceRuntimeState = current?.compositeState
            highPerformanceActiveCount = current?.sessions?.size ?: 0
            delay(3_000L)
        }
    }
    val locationEnabled = KioskPrefs.isNativeLocationOptimizationEnabled(context)
    val locationBlockedBySandbox = KioskPrefs.isLimitGeolocationEnabled(context)
    val locationConfigurationIncomplete = BuildConfig.AMAP_LOCATION_SDK_INCLUDED &&
        KioskPrefs.isAmapLocationEnabled(context) &&
        (KioskPrefs.getAmapLocationApiKey(context).isBlank() ||
            !KioskPrefs.isAmapLocationPrivacyAgreed(context))
    val locationOriginCount = KioskPrefs.getNativeLocationBridgeAllowedOrigins(context).size
    val locationStatus = when {
        locationBlockedBySandbox -> "已被安全限制"
        !locationEnabled -> "未开启"
        locationConfigurationIncomplete -> "配置不完整"
        else -> "已开启"
    }
    val locationSupportingText = if (locationOriginCount > 0) {
        "$locationOriginCount 个可信网站"
    } else {
        null
    }
    val highPerformanceState = highPerformanceRuntimeState ?: when {
        !highPerformanceEnabled -> HighPerformanceCompositeState.DISABLED
        highPerformanceRuleCount == 0 -> HighPerformanceCompositeState.NO_RULES
        !highPerformanceSystemStatus.notificationsGranted ->
            HighPerformanceCompositeState.NEEDS_NOTIFICATION_PERMISSION
        !highPerformanceSystemStatus.batteryOptimizationIgnored ->
            HighPerformanceCompositeState.NEEDS_BATTERY_SETUP
        else -> HighPerformanceCompositeState.READY
    }
    val highPerformanceSupportingText = when {
        highPerformanceActiveCount > 0 -> "$highPerformanceActiveCount 个网页运行中"
        highPerformanceRuleCount > 0 -> "$highPerformanceRuleCount 个 Origin"
        else -> "效果受系统和 WebView 限制"
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        CapabilityEnhancementCard(
            icon = Icons.Default.LocationOn,
            title = "网页定位增强",
            summary = "为可信网页提供系统或高德定位。",
            status = locationStatus,
            supportingText = locationSupportingText,
            onClick = onOpenLocationEnhancement
        )

        CapabilityEnhancementCard(
            icon = Icons.Default.Bolt,
            title = "高性能运行",
            summary = "提高指定网站在后台或息屏时的运行稳定性。",
            status = compositeStateLabel(highPerformanceState),
            supportingText = highPerformanceSupportingText,
            onClick = onOpenHighPerformance
        )
    }
}
