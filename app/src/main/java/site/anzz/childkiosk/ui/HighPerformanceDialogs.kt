package site.anzz.childkiosk.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun HighPerformanceRiskConfirmationDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    var acknowledged by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("启用高性能运行？") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("• 息屏后网页仍可能联网和运行 JavaScript。")
                Text("• 会增加耗电、流量和发热。")
                Text("• 会显示常驻通知。")
                Text("• 系统或 WebView 仍可能中断网页。")
                Text("• 仅为可信网站开启。")
                Row {
                    Checkbox(checked = acknowledged, onCheckedChange = { acknowledged = it })
                    Text(
                        "我确认仅添加可信网站",
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        },
        confirmButton = { TextButton(enabled = acknowledged, onClick = onConfirm) { Text("确认启用") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
internal fun ExperimentalCdpContinuityWarningDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    var acknowledged by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("启用实验性续行？") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 380.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("• 仅对可信 Origin 生效。")
                Text("• 后台时短暂开放 DevTools，随后关闭。")
                Text("• 端口开放期间，已授权 ADB 可检查或修改页面。")
                Text("• WebView 或 Android 更新后可能失效。")
                Text("• 不能避免定时器、Worker 和网络降频。")
                Row {
                    Checkbox(checked = acknowledged, onCheckedChange = { acknowledged = it })
                    Text(
                        "我确认仅用于可信网站",
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(enabled = acknowledged, onClick = onConfirm) { Text("确认启用") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
internal fun HighPerformanceHttpWarningDialog(
    originOrUrl: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("HTTP 连接不安全") },
        text = {
            Column(Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState())) {
                Text(originOrUrl)
                Text("内容可能被第三方篡改。仅在信任的网络或网站上继续。")
            }
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("仍然添加") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
internal fun HighPerformanceClearRulesDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("清空全部可信规则？") },
        text = { Text("当前会话将停止，规则无法自动恢复。") },
        confirmButton = { TextButton(onClick = onConfirm) { Text("确认清空") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
internal fun HighPerformanceClearDiagnosticsDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("清空最近运行记录？") },
        text = { Text("只清空诊断事件，不改规则或停止网页。") },
        confirmButton = { TextButton(onClick = onConfirm) { Text("确认清空") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
