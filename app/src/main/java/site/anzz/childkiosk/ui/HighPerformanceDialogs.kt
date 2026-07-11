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
        title = { Text("确认启用高性能持续运行") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("• 灭屏后，可信网页仍可能联网、执行 JavaScript 或处理业务。")
                Text("• 会明显增加耗电、流量、发热和设备磨损。")
                Text("• 活动会话会显示常驻前台服务通知。")
                Text("• Android、厂商后台策略和 WebView 内核仍可能中断网页。")
                Text("• 仅应为完全信任的网站开启。")
                Row {
                    Checkbox(checked = acknowledged, onCheckedChange = { acknowledged = it })
                    Text(
                        "我理解风险并确认仅添加可信网站",
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
internal fun HighPerformanceHttpWarningDialog(
    originOrUrl: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("不安全 HTTP 连接") },
        text = {
            Column(Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState())) {
                Text(originOrUrl)
                Text("HTTP 内容可能被网络中的第三方篡改。仅为完全信任的局域网或明确业务站点继续。")
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
        text = { Text("这只清空高性能诊断事件，不会修改可信规则或停止当前网页。") },
        confirmButton = { TextButton(onClick = onConfirm) { Text("确认清空") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
