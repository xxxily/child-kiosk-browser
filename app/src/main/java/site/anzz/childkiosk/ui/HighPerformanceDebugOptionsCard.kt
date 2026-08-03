package site.anzz.childkiosk.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import site.anzz.childkiosk.performance.ExperimentalCdpTimingProfile

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun HighPerformanceDebugOptionsCard(
    verboseDiagnosticsEnabled: Boolean,
    timingProfile: ExperimentalCdpTimingProfile,
    busy: Boolean,
    onVerboseDiagnosticsChange: (Boolean) -> Unit,
    onTimingProfileChange: (ExperimentalCdpTimingProfile) -> Unit
) {
    HighPerformanceCard {
        Text("调试与兼容性选项", fontWeight = FontWeight.Bold)
        Text(
            "详细日志只输出脱敏的心跳间隔、候选筛选和 CDP 阶段，不记录页面正文、Cookie 或请求头。",
            fontSize = 11.sp,
            lineHeight = 17.sp
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("详细诊断日志", fontSize = 12.sp)
            Switch(
                checked = verboseDiagnosticsEnabled,
                enabled = !busy,
                onCheckedChange = onVerboseDiagnosticsChange
            )
        }
        Text("实验续行时序策略", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        Text(
            "保守策略更适合响应慢的厂商；激进策略暴露窗口更短，但更容易错过目标。",
            fontSize = 11.sp,
            lineHeight = 17.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            ExperimentalCdpTimingProfile.values().forEach { profile ->
                FilterChip(
                    selected = profile == timingProfile,
                    onClick = { if (!busy) onTimingProfileChange(profile) },
                    enabled = !busy,
                    label = { Text(experimentalCdpTimingProfileLabel(profile)) }
                )
            }
        }
    }
}

internal fun experimentalCdpTimingProfileLabel(profile: ExperimentalCdpTimingProfile): String =
    when (profile) {
        ExperimentalCdpTimingProfile.CONSERVATIVE -> "保守"
        ExperimentalCdpTimingProfile.BALANCED -> "均衡"
        ExperimentalCdpTimingProfile.AGGRESSIVE -> "激进"
    }
