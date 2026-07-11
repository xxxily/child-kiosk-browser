package site.anzz.childkiosk.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import site.anzz.childkiosk.performance.HighPerformanceActivityState
import site.anzz.childkiosk.performance.HighPerformanceForegroundServiceState
import site.anzz.childkiosk.performance.HighPerformanceRendererPolicy
import site.anzz.childkiosk.performance.HighPerformanceRuntimeStatusReadResult
import site.anzz.childkiosk.performance.HighPerformanceSessionStatus
import site.anzz.childkiosk.performance.HighPerformanceWakeLockState

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun HighPerformanceSessionsCard(
    runtimeStatus: HighPerformanceRuntimeStatusReadResult,
    busy: Boolean,
    onStopAll: () -> Unit
) {
    val status = runtimeStatus.status?.takeUnless { runtimeStatus.stale }
    HighPerformanceCard {
        Text("当前高性能会话", fontSize = 16.sp, fontWeight = FontWeight.Bold)
        if (status?.sessions.isNullOrEmpty()) {
            Text(
                if (runtimeStatus.stale && runtimeStatus.status != null) {
                    "上次运行状态已过期，当前无可确认会话。"
                } else {
                    "当前没有匹配网页。"
                },
                fontSize = 12.sp,
                lineHeight = 18.sp
            )
        } else {
            val activeStatus = checkNotNull(status)
            val now = System.currentTimeMillis().coerceAtLeast(activeStatus.updatedAt)
            activeStatus.foregroundServiceError?.let { error ->
                RuntimeErrorText(prefix = "前台服务最近错误", error = error)
            }
            activeStatus.wakeLockError?.let { error ->
                RuntimeErrorText(prefix = "CPU 唤醒锁最近错误", error = error)
            }
            activeStatus.sessions.forEach { session ->
                HighPerformanceSessionItem(
                    session = session,
                    screenInteractive = activeStatus.screenInteractive,
                    foregroundServiceState = activeStatus.foregroundServiceState,
                    wakeLockState = activeStatus.wakeLockState,
                    now = now
                )
            }
        }
        Button(
            modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp),
            enabled = !busy && !status?.sessions.isNullOrEmpty(),
            onClick = onStopAll
        ) {
            Text("停止全部高性能运行")
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HighPerformanceSessionItem(
    session: HighPerformanceSessionStatus,
    screenInteractive: Boolean,
    foregroundServiceState: HighPerformanceForegroundServiceState,
    wakeLockState: HighPerformanceWakeLockState,
    now: Long
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val title = session.displayName?.takeIf(String::isNotBlank) ?: session.origin
            Text(
                title,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                fontWeight = FontWeight.SemiBold,
                lineHeight = 20.sp
            )
            if (title != session.origin) {
                Text(
                    session.origin,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    fontSize = 11.sp,
                    lineHeight = 17.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                RuntimeStatusPill(
                    label = if (session.fullSystemProtection) "完整系统保护" else "降级保护",
                    tone = if (session.fullSystemProtection) StatusTone.POSITIVE else StatusTone.WARNING
                )
                RuntimeStatusPill(
                    label = if (session.visible) "页面可见" else "页面在后台",
                    tone = if (session.visible) StatusTone.POSITIVE else StatusTone.NEUTRAL
                )
                RuntimeStatusPill(
                    label = "Activity：${activityStateLabel(session.activityState)}",
                    tone = when (session.activityState) {
                        HighPerformanceActivityState.RESUMED,
                        HighPerformanceActivityState.STARTED -> StatusTone.POSITIVE
                        HighPerformanceActivityState.CREATED -> StatusTone.NEUTRAL
                        HighPerformanceActivityState.STOPPED,
                        HighPerformanceActivityState.DESTROYED -> StatusTone.WARNING
                    }
                )
                RuntimeStatusPill(
                    label = if (screenInteractive) "屏幕：点亮" else "屏幕：熄灭",
                    tone = StatusTone.NEUTRAL
                )
            }
            Text(
                "已运行 ${formatElapsedDuration(session.startedAt, now)} · 开始于 ${formatTimestamp(session.startedAt)}",
                fontSize = 11.sp,
                lineHeight = 17.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text("运行组件", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                RuntimeStatusPill(
                    label = "FGS：${foregroundServiceStateLabel(foregroundServiceState)}",
                    tone = when (foregroundServiceState) {
                        HighPerformanceForegroundServiceState.RUNNING -> StatusTone.POSITIVE
                        HighPerformanceForegroundServiceState.STARTING -> StatusTone.WARNING
                        HighPerformanceForegroundServiceState.STOPPED -> StatusTone.WARNING
                        HighPerformanceForegroundServiceState.FAILED -> StatusTone.ERROR
                    }
                )
                RuntimeStatusPill(
                    label = "WakeLock：${wakeLockStateLabel(wakeLockState)}",
                    tone = when (wakeLockState) {
                        HighPerformanceWakeLockState.HELD -> StatusTone.POSITIVE
                        HighPerformanceWakeLockState.NOT_HELD -> StatusTone.WARNING
                        HighPerformanceWakeLockState.FAILED -> StatusTone.ERROR
                    }
                )
                RuntimeStatusPill(
                    label = "Renderer：${rendererPolicyLabel(session.rendererPolicy)}",
                    tone = if (
                        session.rendererPolicy ==
                        HighPerformanceRendererPolicy.HIGH_PERFORMANCE_IMPORTANT_NOT_WAIVED
                    ) {
                        StatusTone.POSITIVE
                    } else {
                        StatusTone.WARNING
                    }
                )
            }
            Text(
                "最近页面回调：${formatTimestamp(session.lastPageCallbackAt)} · " +
                    formatRelativeAge(session.lastPageCallbackAt, now),
                fontSize = 11.sp,
                lineHeight = 17.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "页面回调只表示容器最近收到导航或可见性事件，不代表网页 JavaScript 持续运行。",
                fontSize = 10.sp,
                lineHeight = 15.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun RuntimeStatusPill(label: String, tone: StatusTone) {
    val containerColor = when (tone) {
        StatusTone.NEUTRAL -> MaterialTheme.colorScheme.secondaryContainer
        StatusTone.POSITIVE -> MaterialTheme.colorScheme.primaryContainer
        StatusTone.WARNING -> MaterialTheme.colorScheme.tertiaryContainer
        StatusTone.ERROR -> MaterialTheme.colorScheme.errorContainer
    }
    val contentColor = when (tone) {
        StatusTone.NEUTRAL -> MaterialTheme.colorScheme.onSecondaryContainer
        StatusTone.POSITIVE -> MaterialTheme.colorScheme.onPrimaryContainer
        StatusTone.WARNING -> MaterialTheme.colorScheme.onTertiaryContainer
        StatusTone.ERROR -> MaterialTheme.colorScheme.onErrorContainer
    }
    Surface(shape = RoundedCornerShape(999.dp), color = containerColor) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            fontSize = 10.sp,
            lineHeight = 14.sp,
            color = contentColor
        )
    }
}

@Composable
private fun RuntimeErrorText(prefix: String, error: String) {
    Text(
        "$prefix：$error",
        fontSize = 11.sp,
        lineHeight = 17.sp,
        color = MaterialTheme.colorScheme.error
    )
}

private fun formatElapsedDuration(startedAt: Long, now: Long): String {
    if (startedAt <= 0L) return "未知"
    val totalSeconds = ((now - startedAt).coerceAtLeast(0L) / 1_000L)
    if (totalSeconds == 0L) return "不足 1 秒"
    val days = totalSeconds / 86_400L
    val hours = totalSeconds % 86_400L / 3_600L
    val minutes = totalSeconds % 3_600L / 60L
    val seconds = totalSeconds % 60L
    return when {
        days > 0L -> "${days}天 ${hours}小时"
        hours > 0L -> "${hours}小时 ${minutes}分"
        minutes > 0L -> "${minutes}分 ${seconds}秒"
        else -> "${seconds}秒"
    }
}

private fun formatRelativeAge(timestamp: Long, now: Long): String {
    return if (timestamp <= 0L) "无有效回调时间" else "${formatElapsedDuration(timestamp, now)}前"
}

private fun activityStateLabel(state: HighPerformanceActivityState): String = when (state) {
    HighPerformanceActivityState.CREATED -> "已创建"
    HighPerformanceActivityState.STARTED -> "已启动"
    HighPerformanceActivityState.RESUMED -> "前台交互"
    HighPerformanceActivityState.STOPPED -> "已停止"
    HighPerformanceActivityState.DESTROYED -> "已销毁"
}

private fun foregroundServiceStateLabel(state: HighPerformanceForegroundServiceState): String = when (state) {
    HighPerformanceForegroundServiceState.STOPPED -> "未运行"
    HighPerformanceForegroundServiceState.STARTING -> "启动中"
    HighPerformanceForegroundServiceState.RUNNING -> "运行中"
    HighPerformanceForegroundServiceState.FAILED -> "失败"
}

private fun wakeLockStateLabel(state: HighPerformanceWakeLockState): String = when (state) {
    HighPerformanceWakeLockState.NOT_HELD -> "未持有"
    HighPerformanceWakeLockState.HELD -> "已持有"
    HighPerformanceWakeLockState.FAILED -> "失败"
}

private fun rendererPolicyLabel(policy: HighPerformanceRendererPolicy): String = when (policy) {
    HighPerformanceRendererPolicy.BASELINE_IMPORTANT_WAIVED -> "基线，可见性豁免"
    HighPerformanceRendererPolicy.HIGH_PERFORMANCE_IMPORTANT_NOT_WAIVED -> "高优先级，不豁免"
}

private enum class StatusTone {
    NEUTRAL,
    POSITIVE,
    WARNING,
    ERROR
}
