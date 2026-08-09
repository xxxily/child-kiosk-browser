package site.anzz.childkiosk.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import site.anzz.childkiosk.util.AmapLocationDebug
import site.anzz.childkiosk.util.NativeLocationAuditRecord

@Composable
internal fun NativeLocationDiagnosticsDialog(
    diagnostics: String,
    records: List<NativeLocationAuditRecord>,
    testing: Boolean,
    onRefresh: () -> Unit,
    onTest: () -> Unit,
    onClear: () -> Unit,
    onCopy: () -> Unit,
    onDismiss: () -> Unit
) {
    var rawDiagnosticsExpanded by remember { mutableStateOf(false) }
    var confirmClearVisible by remember { mutableStateOf(false) }
    val latest = records.firstOrNull()
    val successCount = records.count { it.success }
    val amapCount = records.count { it.provider == "amap" }
    val failedCount = records.size - successCount
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
                        Icon(Icons.Default.LocationOn, contentDescription = "定位诊断", tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("定位诊断详情", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "关闭定位诊断")
                    }
                }

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("状态摘要", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        DiagnosticItem(label = "最近结果", value = latest?.let { if (it.success) "成功" else "失败" } ?: "暂无")
                        DiagnosticItem(label = "最近来源", value = latest?.origin ?: "暂无")
                        DiagnosticItem(label = "最近 provider", value = latest?.provider ?: "暂无")
                        DiagnosticItem(
                            label = "高德定位类型",
                            value = latest
                                ?.message
                                ?.let { AmapLocationDebug.locationTypeFromMessage(it) }
                                ?.let { AmapLocationDebug.locationTypeDisplay(it) }
                                ?: "暂无"
                        )
                        DiagnosticItem(label = "最近精度", value = latest?.accuracyMeters?.let { "${it}m" } ?: "未知")
                        DiagnosticItem(label = "记录统计", value = "共 ${records.size} 条，成功 $successCount，失败 $failedCount，高德 $amapCount")
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.75f))
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text("最近定位记录", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        if (records.isEmpty()) {
                            Text("暂无定位记录", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            records.take(30).forEachIndexed { index, record ->
                                NativeLocationAuditRecordItem(index + 1, record)
                            }
                        }
                    }

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
                                Text("复制时包含完整原文", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                                    text = diagnostics.ifBlank { "暂无定位诊断" },
                                    fontSize = 12.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                }

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
                        Icon(Icons.Default.Refresh, contentDescription = "刷新定位诊断", modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("刷新")
                    }
                    OutlinedButton(
                        onClick = onTest,
                        enabled = !testing,
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.LocationOn, contentDescription = "测试定位", modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(if (testing) "测试中" else "测试定位")
                    }
                    OutlinedButton(
                        onClick = onCopy,
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "复制定位诊断", modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("复制")
                    }
                    OutlinedButton(
                        onClick = { confirmClearVisible = true },
                        enabled = records.isNotEmpty(),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) {
                        Icon(Icons.Default.DeleteSweep, contentDescription = "清空定位记录", modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("清空记录")
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
                    Text("清空定位记录？", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Text(
                        "清空后无法恢复。",
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
private fun NativeLocationAuditRecordItem(index: Int, record: NativeLocationAuditRecord) {
    val statusColor = if (record.success) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
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
                    "#$index ${record.purpose}",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    record.origin,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                if (record.success) "成功" else "失败",
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
            NativeLocationRecordChip(formatTimestamp(record.timestamp))
            NativeLocationRecordChip(record.provider)
            NativeLocationRecordChip(record.accuracyMeters?.let { "${it}m" } ?: "精度未知")
            NativeLocationRecordChip("${record.elapsedMs}ms")
            NativeLocationRecordChip(if (record.cached) "缓存" else "实时")
        }
        if (record.error != "无" || record.message.isNotBlank()) {
            Text(
                text = listOfNotNull(
                    record.error.takeIf { it != "无" }?.let { "错误=$it" },
                    conciseLocationMessage(record.message).takeIf { it.isNotBlank() }
                ).joinToString("；"),
                fontSize = 11.sp,
                color = if (record.error != "无") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun conciseLocationMessage(message: String): String {
    return AmapLocationDebug.humanizeLocationTypeInMessage(message)
        .substringBefore("；SDK调试:")
        .substringBefore("；高德调试:")
        .replace("；主进程定位代理", "")
        .trim()
        .ifBlank { message.take(80).trim() }
}

@Composable
private fun NativeLocationRecordChip(text: String) {
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
