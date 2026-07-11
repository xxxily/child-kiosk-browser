package site.anzz.childkiosk.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import site.anzz.childkiosk.performance.HighPerformanceDiagnostics
import site.anzz.childkiosk.performance.HighPerformanceRuntimeStatusReadResult

@Composable
internal fun HighPerformanceDiagnosticsCard(
    runtimeStatus: HighPerformanceRuntimeStatusReadResult,
    onRefresh: () -> Unit,
    onOpenDetails: () -> Unit
) {
    HighPerformanceCard {
        Text("运行诊断", fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Text(diagnosticSummary(runtimeStatus), fontSize = 11.sp, maxLines = 4)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AssistChip(onClick = onRefresh, label = { Text("刷新") })
            AssistChip(
                onClick = onOpenDetails,
                label = { Text("查看详情") },
                leadingIcon = { Icon(Icons.Default.OpenInFull, contentDescription = null) }
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun HighPerformanceDiagnosticsDialog(
    runtimeStatus: HighPerformanceRuntimeStatusReadResult,
    onRefresh: () -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val text = diagnosticText(runtimeStatus)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("高性能运行诊断") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(text, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
        dismissButton = {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onRefresh) { Text("刷新") }
                TextButton(onClick = onClear) { Text("清空") }
                TextButton(onClick = {
                    clipboard.setText(AnnotatedString(text))
                    Toast.makeText(context, "诊断已复制", Toast.LENGTH_SHORT).show()
                }) { Text("复制") }
            }
        }
    )
}

private fun diagnosticSummary(result: HighPerformanceRuntimeStatusReadResult): String {
    val status = result.status ?: return "暂无 :webview 运行状态；打开匹配网站后再刷新。"
    return "${status.processName} / PID ${status.pid} / config ${status.appliedConfigVersion} / " +
        "${compositeStateLabel(status.compositeState)}${if (result.stale) "（状态已过期）" else ""}"
}

private fun diagnosticText(result: HighPerformanceRuntimeStatusReadResult): String {
    val status = result.status ?: return "runtimeStatus=missing\nstale=true\nreason=${result.reason.orEmpty()}"
    return buildString {
        appendLine("appVersion=${status.appVersionName} (${status.appVersionCode})")
        appendLine("android=${status.androidRelease} sdk=${status.androidSdkInt}")
        appendLine("device=${status.manufacturer} ${status.model}")
        appendLine(
            "webViewProvider=${status.webViewPackageName.orEmpty()} " +
                "version=${status.webViewVersionName.orEmpty()}"
        )
        appendLine("process=${status.processName}")
        appendLine("pid=${status.pid}")
        appendLine("processInstance=${status.processInstanceId}")
        appendLine("updatedAt=${formatTimestamp(status.updatedAt)}")
        appendLine("stale=${result.stale} reason=${result.reason.orEmpty()}")
        appendLine("configVersion=${status.appliedConfigVersion} rules=${status.configuredRuleCount}")
        appendLine("state=${status.compositeState}")
        appendLine("notification=${status.notificationPermissionGranted}/${status.notificationsVisible}")
        appendLine("batteryIgnored=${status.ignoringBatteryOptimizations}")
        appendLine("screenInteractive=${status.screenInteractive}")
        appendLine(
            "fgsManifest=${status.foregroundServiceDeclared} " +
                "specialUse=${status.specialUseTypeDeclared}"
        )
        appendLine("fgs=${status.foregroundServiceState} error=${status.foregroundServiceError.orEmpty()}")
        appendLine("wakeLock=${status.wakeLockState} error=${status.wakeLockError.orEmpty()}")
        appendLine("sessions=${status.sessions.size}")
        status.sessions.forEach { session ->
            appendLine(
                "  ${session.origin} visible=${session.visible} activity=${session.activityState} " +
                    "renderer=${session.rendererPolicy} full=${session.fullSystemProtection} " +
                    "lastCallback=${formatTimestamp(session.lastPageCallbackAt)}"
            )
        }
        appendLine("recentEvents=${status.recentEvents.size}")
        status.recentEvents.map(HighPerformanceDiagnostics::sanitize).forEach { event ->
            appendLine(
                "  ${formatTimestamp(event.timestamp)} ${event.type}/${event.result} " +
                    "origin=${event.origin.orEmpty()} reason=${event.reason.orEmpty()}"
            )
        }
    }
}
