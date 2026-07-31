package site.anzz.childkiosk.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import site.anzz.childkiosk.data.WebAppEntity
import site.anzz.childkiosk.performance.HighPerformanceConfigRepository
import site.anzz.childkiosk.performance.HighPerformanceOriginParser
import site.anzz.childkiosk.performance.HighPerformancePersistedRule
import site.anzz.childkiosk.performance.HighPerformancePersistedState
import site.anzz.childkiosk.performance.HighPerformancePublicationException
import site.anzz.childkiosk.performance.HighPerformanceRuntimeStatusReadResult
import site.anzz.childkiosk.performance.HighPerformanceRuntimeStatusStore
import site.anzz.childkiosk.performance.HighPerformanceRuntimePublisher
import site.anzz.childkiosk.performance.HighPerformanceSystemStatus
import site.anzz.childkiosk.performance.HighPerformanceSystemStatusReader

@Composable
internal fun HighPerformanceDetailScreen(
    webApps: List<WebAppEntity>,
    onCapabilityChanged: (recreateWebViews: Boolean) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val repository = remember { HighPerformanceConfigRepository(context) }
    var persistedState by remember { mutableStateOf<HighPerformancePersistedState?>(null) }
    var systemStatus by remember { mutableStateOf(HighPerformanceSystemStatusReader.read(context)) }
    var runtimeStatus by remember {
        mutableStateOf(HighPerformanceRuntimeStatusReadResult(null, stale = true, reason = "loading"))
    }
    var busy by remember { mutableStateOf(false) }
    var showRiskDialog by remember { mutableStateOf(false) }
    var showHttpWarning by remember { mutableStateOf<PendingRule?>(null) }
    var showClearRulesDialog by remember { mutableStateOf(false) }
    var showDiagnosticsDialog by remember { mutableStateOf(false) }
    var showClearDiagnosticsDialog by remember { mutableStateOf(false) }
    var notificationPermissionRequested by rememberSaveable { mutableStateOf(false) }

    fun refreshRuntimeFacts() {
        systemStatus = HighPerformanceSystemStatusReader.read(context)
        scope.launch {
            runtimeStatus = withContext(Dispatchers.IO) {
                HighPerformanceRuntimeStatusStore.read(context)
            }
        }
    }

    fun mutate(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { block() } }
                .onSuccess {
                    refreshRuntimeFacts()
                    onCapabilityChanged(false)
                }
                .onFailure { error ->
                    if (error is HighPerformancePublicationException) {
                        refreshRuntimeFacts()
                        onCapabilityChanged(false)
                    }
                    Toast.makeText(
                        context,
                        error.message?.take(120) ?: "保存高性能设置失败",
                        Toast.LENGTH_LONG
                    ).show()
                }
            busy = false
        }
    }

    LaunchedEffect(repository) {
        repository.observePersistedState().collect { state ->
            persistedState = state
        }
    }
    LaunchedEffect(Unit) {
        while (true) {
            runtimeStatus = withContext(Dispatchers.IO) {
                HighPerformanceRuntimeStatusStore.read(context)
            }
            delay(3_000L)
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refreshRuntimeFacts()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        notificationPermissionRequested = true
        refreshRuntimeFacts()
    }

    fun launchSystemIntent(primary: Intent, fallback: Intent? = null) {
        val activity = context as? Activity
        val opened = runCatching {
            activity?.startActivity(primary) ?: context.startActivity(primary.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        }.getOrDefault(false)
        if (!opened && fallback != null) {
            runCatching {
                activity?.startActivity(fallback)
                    ?: context.startActivity(fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.onFailure {
                Toast.makeText(context, "无法打开系统设置，请手动检查", Toast.LENGTH_LONG).show()
            }
        }
    }

    val state = persistedState
    if (state == null) {
        HighPerformanceLoadingCard()
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        HighPerformanceStatusSummaryCard(
            persistedState = state,
            systemStatus = systemStatus,
            runtimeStatus = runtimeStatus,
            onRefresh = {
                refreshRuntimeFacts()
                mutate { repository.publishCurrent() }
            }
        )

        HighPerformanceEnableCard(
            enabled = state.enabled,
            busy = busy,
            onEnabledChange = { enabled ->
                if (enabled && !state.hasAcknowledgedRisk) {
                    showRiskDialog = true
                } else {
                    mutate { repository.setEnabled(enabled) }
                }
            },
            onClearRules = { showClearRulesDialog = true }
        )

        HighPerformanceSetupChecklist(
            status = systemStatus,
            runtimeStatus = runtimeStatus,
            onRequestNotifications = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                    PackageManager.PERMISSION_GRANTED &&
                    !notificationPermissionRequested
                ) {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    launchSystemIntent(HighPerformanceSystemStatusReader.notificationSettingsIntent(context))
                }
            },
            onOpenBatterySettings = {
                launchSystemIntent(
                    HighPerformanceSystemStatusReader.batteryOptimizationSettingsIntent(),
                    HighPerformanceSystemStatusReader.applicationDetailsSettingsIntent(context)
                )
            },
            onOpenManufacturerSettings = {
                launchSystemIntent(HighPerformanceSystemStatusReader.applicationDetailsSettingsIntent(context))
            }
        )

        HighPerformanceOriginRulesCard(
            rules = state.rules,
            webApps = webApps.filter(WebAppEntity::isEnabled),
            busy = busy,
            onAddManual = { origin, includeSubdomains ->
                val pending = runCatching {
                    val parsed = HighPerformanceOriginParser.parseRuleOrigin(origin)
                    PendingRule(origin, null, includeSubdomains, parsed.scheme == "http")
                }.getOrElse { error ->
                    Toast.makeText(context, error.message ?: "请输入完整 http/https Origin", Toast.LENGTH_LONG).show()
                    return@HighPerformanceOriginRulesCard false
                }
                if (pending.requiresHttpConfirmation) {
                    showHttpWarning = pending
                    false
                } else {
                    mutate {
                        repository.addOrUpdateManualRule(pending.value, includeSubdomains = includeSubdomains)
                    }
                    true
                }
            },
            onAddWebApp = { webApp ->
                val pending = runCatching {
                    val parsed = HighPerformanceOriginParser.extractFromUrl(webApp.url)
                    PendingRule(webApp.url, webApp.title, false, parsed.scheme == "http", fromWebApp = true)
                }.getOrElse { error ->
                    Toast.makeText(context, error.message ?: "网站地址无法提取 Origin", Toast.LENGTH_LONG).show()
                    return@HighPerformanceOriginRulesCard
                }
                if (pending.requiresHttpConfirmation) showHttpWarning = pending
                else mutate { repository.addOrUpdateWebAppRule(webApp.url, webApp.title) }
            },
            onSetEnabled = { rule, enabled -> mutate { repository.setRuleEnabled(rule.id, enabled) } },
            onSetIncludeSubdomains = { rule, include ->
                mutate { repository.setRuleIncludeSubdomains(rule.id, include) }
            },
            onRemove = { rule -> mutate { repository.removeRule(rule.id) } }
        )

        HighPerformanceSessionsCard(
            runtimeStatus = runtimeStatus,
            busy = busy,
            onStopAll = { mutate { repository.requestStopAll() } }
        )

        HighPerformanceDiagnosticsCard(
            runtimeStatus = runtimeStatus,
            onRefresh = ::refreshRuntimeFacts,
            onOpenDetails = { showDiagnosticsDialog = true }
        )
    }

    if (showRiskDialog) {
        HighPerformanceRiskConfirmationDialog(
            onDismiss = { showRiskDialog = false },
            onConfirm = {
                showRiskDialog = false
                mutate { repository.acknowledgeRiskAndEnable() }
            }
        )
    }

    showHttpWarning?.let { pending ->
        HighPerformanceHttpWarningDialog(
            originOrUrl = pending.value,
            onDismiss = { showHttpWarning = null },
            onConfirm = {
                showHttpWarning = null
                mutate {
                    if (pending.fromWebApp) {
                        repository.addOrUpdateWebAppRule(
                            webAppUrl = pending.value,
                            displayName = pending.displayName,
                            includeSubdomains = pending.includeSubdomains,
                            insecureHttpConfirmed = true
                        )
                    } else {
                        repository.addOrUpdateManualRule(
                            rawOrigin = pending.value,
                            displayName = pending.displayName,
                            includeSubdomains = pending.includeSubdomains,
                            insecureHttpConfirmed = true
                        )
                    }
                }
            }
        )
    }

    if (showClearRulesDialog) {
        HighPerformanceClearRulesDialog(
            onDismiss = { showClearRulesDialog = false },
            onConfirm = {
                showClearRulesDialog = false
                mutate { repository.clearRules() }
            }
        )
    }

    if (showDiagnosticsDialog) {
        HighPerformanceDiagnosticsDialog(
            runtimeStatus = runtimeStatus,
            onRefresh = ::refreshRuntimeFacts,
            onClear = {
                showDiagnosticsDialog = false
                showClearDiagnosticsDialog = true
            },
            onDismiss = { showDiagnosticsDialog = false }
        )
    }

    if (showClearDiagnosticsDialog) {
        HighPerformanceClearDiagnosticsDialog(
            onDismiss = { showClearDiagnosticsDialog = false },
            onConfirm = {
                showClearDiagnosticsDialog = false
                scope.launch {
                    val sent = withContext(Dispatchers.IO) {
                        HighPerformanceRuntimePublisher.requestClearDiagnostics(context)
                    }
                    Toast.makeText(
                        context,
                        if (sent) "已清空高性能运行记录" else "清空请求发送失败",
                        Toast.LENGTH_SHORT
                    ).show()
                    refreshRuntimeFacts()
                }
            }
        )
    }
}

private data class PendingRule(
    val value: String,
    val displayName: String?,
    val includeSubdomains: Boolean,
    val requiresHttpConfirmation: Boolean,
    val fromWebApp: Boolean = false
)
