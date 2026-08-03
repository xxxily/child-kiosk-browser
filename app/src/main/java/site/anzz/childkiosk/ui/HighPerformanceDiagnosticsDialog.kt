package site.anzz.childkiosk.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import site.anzz.childkiosk.performance.HighPerformanceDiagnostics
import site.anzz.childkiosk.performance.HighPerformanceDiagnosticsFormatter
import site.anzz.childkiosk.performance.HighPerformanceRuntimeStatusReadResult

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun HighPerformanceDiagnosticsCard(
    runtimeStatus: HighPerformanceRuntimeStatusReadResult,
    onRefresh: () -> Unit,
    onOpenDetails: () -> Unit,
    onExport: () -> Unit
) {
    HighPerformanceCard {
        Text("运行诊断", fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Text(diagnosticSummary(runtimeStatus), fontSize = 11.sp, maxLines = 4)
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = onRefresh,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.heightIn(min = 36.dp)
            ) {
                Text("刷新")
            }
            OutlinedButton(
                onClick = onOpenDetails,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.heightIn(min = 36.dp)
            ) {
                Icon(Icons.Default.OpenInFull, contentDescription = null, modifier = Modifier.size(14.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("查看详情")
            }
            OutlinedButton(
                onClick = onExport,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.heightIn(min = 36.dp)
            ) {
                Text("导出脱敏包")
            }
        }
    }
}

@Composable
internal fun HighPerformanceDiagnosticsDialog(
    runtimeStatus: HighPerformanceRuntimeStatusReadResult,
    onRefresh: () -> Unit,
    onClear: () -> Unit,
    onExport: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var rawDiagnosticsExpanded by remember { mutableStateOf(false) }
    var confirmClearVisible by remember { mutableStateOf(false) }
    val rawText = diagnosticText(runtimeStatus)
    val status = runtimeStatus.status

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            shape = RoundedCornerShape(0.dp),
            color = MaterialTheme.colorScheme.background,
            modifier = Modifier.fillMaxSize()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Info, contentDescription = "高性能运行诊断", tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("高性能运行诊断详情", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "关闭诊断")
                    }
                }

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (status == null) {
                        Text(
                            "暂无 :webview 运行状态。请打开已允许的网页，然后刷新。",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 16.dp)
                        )
                    } else {
                        // 状态摘要 Card
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text("状态摘要", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            DiagnosticItem(label = "运行进程", value = "${status.processName} (PID: ${status.pid})")
                            DiagnosticItem(
                                label = "原生诊断心跳",
                                value = formatTimestamp(status.nativeHeartbeatAt)
                            )
                            DiagnosticItem(label = "综合状态", value = compositeStateLabel(status.compositeState) + if (runtimeStatus.stale) " (状态已过期)" else "")
                            DiagnosticItem(label = "应用版本", value = "${status.appVersionName} (${status.appVersionCode})")
                            DiagnosticItem(label = "前台服务 (FGS)", value = "${status.foregroundServiceState}" + (status.foregroundServiceError?.let { "：$it" } ?: ""))
                            DiagnosticItem(label = "CPU 唤醒锁 (WakeLock)", value = "${status.wakeLockState}" + (status.wakeLockError?.let { "：$it" } ?: ""))
                            DiagnosticItem(label = "忽略电池优化", value = if (status.ignoringBatteryOptimizations) "是" else "否")
                            DiagnosticItem(label = "通知权限 (系统/FGS)", value = "${if (status.notificationPermissionGranted) "已允许" else "未允许"} / ${if (status.notificationsVisible) "已显示" else "未显示"}")
                            DiagnosticItem(label = "屏幕状态 (亮屏)", value = if (status.screenInteractive) "是" else "否")
                            DiagnosticItem(
                                label = "Keyguard",
                                value = "显示=${status.keyguardShowing} / 安全=${status.keyguardSecure} / " +
                                    "息屏前提就绪=${status.keyguardReadyForScreenOff}"
                            )
                            DiagnosticItem(label = "运行规则配置", value = "v${status.appliedConfigVersion} (共 ${status.configuredRuleCount} 条规则)")
                        }

                        // 活动会话 Card (如果有会话)
                        if (status.sessions.isNotEmpty()) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.75f))
                                    .padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Text("当前活动会话 (${status.sessions.size})", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                status.sessions.forEachIndexed { idx, session ->
                                    val title = session.displayName?.takeIf { it.isNotBlank() } ?: session.origin
                                    Card(
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(8.dp),
                                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                                    ) {
                                        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                            Text("#${idx + 1} $title", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                            if (title != session.origin) {
                                                Text(session.origin, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            }
                                            Row(
                                                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                HighPerformanceRecordChip("可见: ${if (session.visible) "是" else "否"}")
                                                HighPerformanceRecordChip(
                                                    "Document: ${session.documentVisibilityState ?: "未知"} / hidden=${session.documentHidden}"
                                                )
                                                HighPerformanceRecordChip("连续性: ${session.continuityState}")
                                                HighPerformanceRecordChip("Activity: ${session.activityState}")
                                                HighPerformanceRecordChip("内核特权: ${if (session.rendererPolicy.name.contains("HIGH_PERFORMANCE")) "高" else "默认"}")
                                                HighPerformanceRecordChip("JS: ${session.javascriptState}")
                                            }
                                            Text(
                                                "JS 主线程心跳: ${session.lastMainJsHeartbeatAt?.let(::formatTimestamp) ?: "尚未收到"}；" +
                                                    "Worker: ${session.lastWorkerJsHeartbeatAt?.let(::formatTimestamp) ?: "尚未收到"}",
                                                fontSize = 11.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                            Text(
                                                "页面 load ID: ${session.pageLoadId ?: "尚未收到"}；" +
                                                    "可见性采样: ${session.lastVisibilityProbeAt?.let(::formatTimestamp) ?: "尚未收到"}",
                                                fontSize = 11.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        // 最近运行记录 Card
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.75f))
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text("最近运行诊断事件", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            if (status.recentEvents.isEmpty()) {
                                Text("暂无诊断事件记录", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            } else {
                                status.recentEvents.take(30).map(HighPerformanceDiagnostics::sanitize).forEachIndexed { index, event ->
                                    HighPerformanceEventItem(index + 1, event)
                                }
                            }
                        }
                    }

                    // 原始诊断 Card (可折叠)
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("原始诊断", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                Text("包含调试信息，复制按钮仍复制完整原文", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            IconButton(onClick = { rawDiagnosticsExpanded = !rawDiagnosticsExpanded }) {
                                Icon(
                                    if (rawDiagnosticsExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                    contentDescription = if (rawDiagnosticsExpanded) "折叠原始诊断" else "展开原始诊断"
                                )
                            }
                        }
                        if (rawDiagnosticsExpanded) {
                            SelectionContainer {
                                Text(
                                    text = rawText.ifBlank { "暂无诊断信息" },
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                }

                // 底部操作栏
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = onRefresh,
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = "刷新诊断", modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("刷新")
                    }
                    OutlinedButton(
                        onClick = { confirmClearVisible = true },
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) {
                        Icon(Icons.Default.DeleteSweep, contentDescription = "清空事件", modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("清空")
                    }
                    OutlinedButton(
                        onClick = {
                            onExport()
                        },
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "导出脱敏诊断", modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("导出脱敏包")
                    }
                    OutlinedButton(
                        onClick = {
                            clipboard.setText(AnnotatedString(rawText))
                            Toast.makeText(context, "诊断已复制", Toast.LENGTH_SHORT).show()
                        },
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("复制")
                    }
                    Button(
                        onClick = onDismiss,
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("关闭")
                    }
                }
            }
        }
    }

    if (confirmClearVisible) {
        Dialog(
            onDismissRequest = { confirmClearVisible = false }
        ) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 6.dp
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Text("清空最近运行记录？", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Text(
                        "这只清空高性能诊断事件，不会修改可信规则或停止当前网页。",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = { confirmClearVisible = false }) {
                            Text("取消")
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = {
                                confirmClearVisible = false
                                onClear()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                        ) {
                            Text("确认清空")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HighPerformanceEventItem(index: Int, event: site.anzz.childkiosk.performance.HighPerformanceAuditEvent) {
    val statusColor = if (event.result == "ok" || event.result == "available" || event.result == "ignored") {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.error
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.16f), RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.22f))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "#$index ${event.type}",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (!event.origin.isNullOrBlank()) {
                    Text(
                        event.origin,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Text(
                event.result.uppercase(),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = statusColor
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            HighPerformanceRecordChip(formatTimestamp(event.timestamp))
            if (!event.reason.isNullOrBlank()) {
                HighPerformanceRecordChip("Reason: ${event.reason}")
            }
        }
    }
}

@Composable
private fun HighPerformanceRecordChip(text: String) {
    Text(
        text = text,
        fontSize = 10.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.75f))
            .padding(horizontal = 8.dp, vertical = 3.dp)
    )
}

private fun diagnosticSummary(result: HighPerformanceRuntimeStatusReadResult): String {
    val status = result.status ?: return "暂无 :webview 运行状态；打开匹配网站后再刷新。"
    return "${status.processName} / PID ${status.pid} / config ${status.appliedConfigVersion} / " +
        "${compositeStateLabel(status.compositeState)}${if (result.stale) "（状态已过期）" else ""}"
}

private fun diagnosticText(result: HighPerformanceRuntimeStatusReadResult): String {
    return HighPerformanceDiagnosticsFormatter.format(result)
}
