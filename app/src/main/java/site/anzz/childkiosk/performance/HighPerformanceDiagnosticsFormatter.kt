package site.anzz.childkiosk.performance

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object HighPerformanceDiagnosticsFormatter {
    fun format(result: HighPerformanceRuntimeStatusReadResult): String {
        val status = result.status
            ?: return "runtimeStatus=missing\nstale=true\nreason=${result.reason.orEmpty()}"
        val now = System.currentTimeMillis().coerceAtLeast(status.updatedAt)
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
            appendLine("nativeHeartbeatAt=${formatTimestamp(status.nativeHeartbeatAt)}")
            appendLine("stale=${result.stale} reason=${result.reason.orEmpty()}")
            appendLine(
                "configVersion=${status.appliedConfigVersion} rules=${status.configuredRuleCount} " +
                    "experimentalCdpContinuity=${status.experimentalCdpContinuityEnabled} " +
                    "cdpTimingProfile=${status.experimentalCdpTimingProfile} " +
                    "verboseDiagnostics=${status.verboseDiagnosticsEnabled}"
            )
            appendLine("state=${status.compositeState}")
            appendLine("notification=${status.notificationPermissionGranted}/${status.notificationsVisible}")
            appendLine("batteryIgnored=${status.ignoringBatteryOptimizations}")
            appendLine("screenInteractive=${status.screenInteractive}")
            appendLine(
                "keyguardShowing=${status.keyguardShowing} keyguardSecure=${status.keyguardSecure} " +
                    "keyguardReadyForScreenOff=${status.keyguardReadyForScreenOff}"
            )
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
                        "lastCallback=${formatTimestamp(session.lastPageCallbackAt)} " +
                        "js=${session.javascriptState} documentHidden=${session.documentHidden} " +
                        "documentVisibility=${session.documentVisibilityState.orEmpty()} " +
                        "continuity=${session.continuityState} loadId=${session.pageLoadId.orEmpty()} " +
                        "jsInstalled=${session.jsHeartbeatInstalledAt?.let(::formatTimestamp).orEmpty()} " +
                        "jsLast=${session.lastJsHeartbeatAt?.let(::formatTimestamp).orEmpty()} " +
                        "jsMain=${session.lastMainJsHeartbeatAt?.let(::formatTimestamp).orEmpty()} " +
                        "jsWorker=${session.lastWorkerJsHeartbeatAt?.let(::formatTimestamp).orEmpty()} " +
                        "mainAgeMs=${ageMs(now, session.lastMainJsHeartbeatAt)} " +
                        "workerAgeMs=${ageMs(now, session.lastWorkerJsHeartbeatAt)}"
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

    private fun ageMs(now: Long, timestamp: Long?): Long =
        timestamp?.takeIf { it > 0L }?.let { (now - it).coerceAtLeast(0L) } ?: -1L

    private fun formatTimestamp(timestamp: Long): String {
        if (timestamp <= 0L) return "unknown"
        return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(timestamp))
    }
}
