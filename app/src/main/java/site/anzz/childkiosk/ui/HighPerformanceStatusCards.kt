package site.anzz.childkiosk.ui

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import site.anzz.childkiosk.performance.HighPerformanceCompositeState
import site.anzz.childkiosk.performance.HighPerformancePersistedState
import site.anzz.childkiosk.performance.HighPerformanceRuntimeStatusReadResult
import site.anzz.childkiosk.performance.HighPerformanceSystemStatus

@Composable
internal fun HighPerformanceLoadingCard() {
    Card(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        Text("正在读取高性能配置…", modifier = Modifier.padding(20.dp))
    }
}

@Composable
internal fun HighPerformanceStatusSummaryCard(
    persistedState: HighPerformancePersistedState,
    systemStatus: HighPerformanceSystemStatus,
    runtimeStatus: HighPerformanceRuntimeStatusReadResult,
    onRefresh: () -> Unit
) {
    val activeStatus = runtimeStatus.status?.takeUnless { runtimeStatus.stale }
    val status = activeStatus?.compositeState ?: when {
        !persistedState.enabled -> HighPerformanceCompositeState.DISABLED
        persistedState.rules.none { it.enabled } -> HighPerformanceCompositeState.NO_RULES
        !systemStatus.notificationsGranted -> HighPerformanceCompositeState.NEEDS_NOTIFICATION_PERMISSION
        !systemStatus.batteryOptimizationIgnored -> HighPerformanceCompositeState.NEEDS_BATTERY_SETUP
        else -> HighPerformanceCompositeState.READY
    }
    HighPerformanceCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Bolt, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(8.dp))
            Text("运行状态", fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
        AssistChip(onClick = onRefresh, label = { Text(compositeStateLabel(status)) }, leadingIcon = {
            Icon(Icons.Default.Refresh, contentDescription = "刷新")
        })
        Text(
            "可信 Origin ${persistedState.rules.count { it.enabled }} 个 · 活动会话 ${activeStatus?.sessions?.size ?: 0} 个",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            "最近启动：${formatTimestamp(activeStatus?.lastSessionStartedAt ?: 0L)}；最近停止：${formatTimestamp(activeStatus?.lastSessionStoppedAt ?: 0L)}",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            "提高后台持续运行可靠性，不保证系统、厂商策略或 WebView/Chromium 内核永不中断。",
            fontSize = 12.sp,
            lineHeight = 18.sp,
            color = MaterialTheme.colorScheme.error
        )
    }
}

@Composable
internal fun HighPerformanceEnableCard(
    enabled: Boolean,
    busy: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onClearRules: () -> Unit
) {
    HighPerformanceCard {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val compact = maxWidth < 360.dp
            if (compact) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column {
                        Text("启用高性能持续运行", fontWeight = FontWeight.Bold)
                        Text(
                            "关闭后立即停止保护，但保留可信网站规则。",
                            fontSize = 11.sp,
                            lineHeight = 17.sp
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(if (enabled) "当前已启用" else "当前未启用", fontSize = 12.sp)
                        Switch(checked = enabled, enabled = !busy, onCheckedChange = onEnabledChange)
                    }
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("启用高性能持续运行", fontWeight = FontWeight.Bold)
                        Text("关闭后立即停止保护，但保留可信网站规则。", fontSize = 11.sp)
                    }
                    Switch(checked = enabled, enabled = !busy, onCheckedChange = onEnabledChange)
                }
            }
        }
        OutlinedButton(
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            onClick = onClearRules,
            enabled = !busy
        ) {
            Text("清空全部规则")
        }
    }
}

@Composable
internal fun HighPerformanceSetupChecklist(
    status: HighPerformanceSystemStatus,
    runtimeStatus: HighPerformanceRuntimeStatusReadResult,
    onRequestNotifications: () -> Unit,
    onOpenBatterySettings: () -> Unit,
    onOpenManufacturerSettings: () -> Unit
) {
    HighPerformanceCard {
        Text("系统运行条件", fontSize = 16.sp, fontWeight = FontWeight.Bold)
        SetupRow(
            title = "通知权限",
            status = when {
                Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU -> "系统无需运行时授权"
                status.notificationsGranted -> "已允许"
                else -> "未授权，仅 renderer 基础增强"
            },
            action = "去授权",
            onClick = onRequestNotifications
        )
        SetupRow(
            title = "电池优化",
            status = if (status.batteryOptimizationIgnored) "系统确认已忽略" else "仍受系统省电策略影响",
            action = "去设置",
            onClick = onOpenBatterySettings
        )
        SetupRow(
            title = "前台服务",
            status = runtimeStatus.status?.takeUnless { runtimeStatus.stale }?.let {
                "${it.foregroundServiceState}" + (it.foregroundServiceError?.let { error -> "：$error" } ?: "")
            } ?: if (status.canStartFullForegroundProtection) "清单声明可用，当前未运行" else "清单或通知条件缺失",
            action = null,
            onClick = {}
        )
        SetupRow(
            title = "CPU 唤醒锁",
            status = runtimeStatus.status?.takeUnless { runtimeStatus.stale }?.let {
                "${it.wakeLockState}" + (it.wakeLockError?.let { error -> "：$error" } ?: "")
            } ?: if (status.wakeLockPermissionDeclared) "由浏览器在活动会话中自动管理" else "清单声明缺失",
            action = null,
            onClick = {}
        )
        SetupRow(
            title = "${status.manufacturer} 厂商后台策略",
            status = "状态无法可靠读取，请人工确认",
            action = "应用详情",
            onClick = onOpenManufacturerSettings
        )
    }
}

@Composable
private fun SetupRow(title: String, status: String, action: String?, onClick: () -> Unit) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val compact = maxWidth < 420.dp
        if (compact) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SetupText(title = title, status = status)
                if (action != null) {
                    OutlinedButton(
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        onClick = onClick
                    ) {
                        Text(action)
                    }
                }
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Column(Modifier.weight(1f)) {
                    SetupText(title = title, status = status)
                }
                if (action != null) {
                    OutlinedButton(
                        modifier = Modifier.heightIn(min = 48.dp),
                        onClick = onClick
                    ) {
                        Text(action)
                    }
                }
            }
        }
    }
}

@Composable
private fun SetupText(title: String, status: String) {
    Text(title, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
    Text(
        status,
        fontSize = 11.sp,
        lineHeight = 17.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
internal fun HighPerformanceCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val compact = maxWidth < 360.dp
            Column(
                modifier = Modifier.padding(if (compact) 12.dp else 16.dp),
                verticalArrangement = Arrangement.spacedBy(if (compact) 10.dp else 12.dp),
                content = content
            )
        }
    }
}

internal fun compositeStateLabel(state: HighPerformanceCompositeState): String = when (state) {
    HighPerformanceCompositeState.DISABLED -> "未开启"
    HighPerformanceCompositeState.NO_RULES -> "待配置"
    HighPerformanceCompositeState.NEEDS_NOTIFICATION_PERMISSION -> "待授权通知"
    HighPerformanceCompositeState.NEEDS_BATTERY_SETUP -> "待完成电池设置"
    HighPerformanceCompositeState.READY -> "已就绪"
    HighPerformanceCompositeState.ACTIVE -> "运行中"
    HighPerformanceCompositeState.DEGRADED -> "降级运行"
    HighPerformanceCompositeState.INTERRUPTED -> "最近发生中断"
    HighPerformanceCompositeState.ERROR -> "异常"
}
