package site.anzz.childkiosk.performance

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

enum class HighPerformanceCompositeState {
    DISABLED,
    NO_RULES,
    NEEDS_NOTIFICATION_PERMISSION,
    NEEDS_BATTERY_SETUP,
    READY,
    ACTIVE,
    BACKGROUND_THROTTLED,
    DEGRADED,
    INTERRUPTED,
    ERROR
}

enum class HighPerformanceForegroundServiceState {
    STOPPED,
    STARTING,
    RUNNING,
    FAILED
}

enum class HighPerformanceWakeLockState {
    NOT_HELD,
    HELD,
    FAILED
}

enum class HighPerformanceRendererPolicy {
    BASELINE_IMPORTANT_WAIVED,
    HIGH_PERFORMANCE_IMPORTANT_NOT_WAIVED
}

enum class HighPerformanceJavascriptState {
    UNKNOWN,
    AWAITING_FIRST_HEARTBEAT,
    RESPONSIVE,
    LOW_FREQUENCY_RESPONSIVE,
    STALE
}

/**
 * What the runtime can actually prove about a page while its host is not foreground.
 *
 * This is deliberately derived from the page's real Page Visibility signal and the native
 * heartbeat. Activity.onStop() alone is not evidence that Chromium marked the document hidden.
 */
enum class HighPerformanceContinuityState {
    UNKNOWN,
    FOREGROUND_RESPONSIVE,
    BACKGROUND_VISIBLE_CONTINUITY,
    SCREEN_OFF_VISIBLE_CONTINUITY,
    HIDDEN_LOW_FREQUENCY_CONTINUITY,
    HIDDEN_DEGRADED,
    STALE
}

internal fun classifyContinuityState(
    screenInteractive: Boolean,
    activityState: HighPerformanceActivityState,
    javascriptState: HighPerformanceJavascriptState,
    documentHidden: Boolean?
): HighPerformanceContinuityState {
    if (javascriptState == HighPerformanceJavascriptState.STALE) {
        return HighPerformanceContinuityState.STALE
    }
    if (javascriptState == HighPerformanceJavascriptState.LOW_FREQUENCY_RESPONSIVE) {
        return if (activityState == HighPerformanceActivityState.STOPPED && documentHidden == true) {
            HighPerformanceContinuityState.HIDDEN_LOW_FREQUENCY_CONTINUITY
        } else {
            HighPerformanceContinuityState.UNKNOWN
        }
    }
    if (javascriptState != HighPerformanceJavascriptState.RESPONSIVE) {
        return HighPerformanceContinuityState.UNKNOWN
    }
    if (activityState == HighPerformanceActivityState.RESUMED ||
        activityState == HighPerformanceActivityState.STARTED
    ) {
        return HighPerformanceContinuityState.FOREGROUND_RESPONSIVE
    }
    return when (documentHidden) {
        true -> HighPerformanceContinuityState.HIDDEN_DEGRADED
        false -> if (screenInteractive) {
            HighPerformanceContinuityState.BACKGROUND_VISIBLE_CONTINUITY
        } else {
            HighPerformanceContinuityState.SCREEN_OFF_VISIBLE_CONTINUITY
        }
        null -> HighPerformanceContinuityState.UNKNOWN
    }
}

internal fun classifyJavascriptHeartbeat(
    now: Long,
    installedAt: Long?,
    lastMainHeartbeatAt: Long?,
    lastWorkerHeartbeatAt: Long? = null,
    backgrounded: Boolean = false,
    visible: Boolean = true,
    staleAfterMs: Long = HIGH_PERFORMANCE_JS_HEARTBEAT_STALE_AFTER_MS
): HighPerformanceJavascriptState = when {
    installedAt == null || installedAt <= 0L -> HighPerformanceJavascriptState.UNKNOWN
    lastMainHeartbeatAt == null && now - installedAt in 0L..staleAfterMs ->
        HighPerformanceJavascriptState.AWAITING_FIRST_HEARTBEAT
    !visible && lastMainHeartbeatAt == null && lastWorkerHeartbeatAt == null ->
        HighPerformanceJavascriptState.AWAITING_FIRST_HEARTBEAT
    lastMainHeartbeatAt != null && lastMainHeartbeatAt > 0L &&
        now - lastMainHeartbeatAt in 0L..staleAfterMs ->
        HighPerformanceJavascriptState.RESPONSIVE
    backgrounded && lastMainHeartbeatAt != null && lastMainHeartbeatAt in 1L..now &&
        lastWorkerHeartbeatAt != null && lastWorkerHeartbeatAt > 0L &&
        now - lastWorkerHeartbeatAt in 0L..HIGH_PERFORMANCE_BACKGROUND_WORKER_STALE_AFTER_MS ->
        HighPerformanceJavascriptState.LOW_FREQUENCY_RESPONSIVE
    else -> HighPerformanceJavascriptState.STALE
}

internal fun contributesToHighPerformanceComposite(session: HighPerformanceSessionStatus): Boolean =
    session.visible ||
        session.lastJsHeartbeatAt != null ||
        session.lastVisibilityProbeAt != null ||
        session.pageLoadId != null

internal fun refreshHeartbeatDerivedState(
    status: HighPerformanceRuntimeStatus,
    now: Long
): HighPerformanceRuntimeStatus {
    val sessions = status.sessions.map { session ->
        val javascriptState = classifyJavascriptHeartbeat(
            now = now,
            installedAt = session.jsHeartbeatInstalledAt,
            lastMainHeartbeatAt = session.lastMainJsHeartbeatAt,
            lastWorkerHeartbeatAt = session.lastWorkerJsHeartbeatAt,
            backgrounded = session.activityState == HighPerformanceActivityState.STOPPED,
            visible = session.visible
        )
        session.copy(
            javascriptState = javascriptState,
            continuityState = classifyContinuityState(
                screenInteractive = status.screenInteractive,
                activityState = session.activityState,
                javascriptState = javascriptState,
                documentHidden = session.documentHidden
            )
        )
    }
    val assessedSessions = sessions.filter(::contributesToHighPerformanceComposite)
    val canReclassifyActiveRuntime = status.compositeState == HighPerformanceCompositeState.ACTIVE ||
        status.compositeState == HighPerformanceCompositeState.BACKGROUND_THROTTLED
    val compositeState = when {
        canReclassifyActiveRuntime && assessedSessions.isEmpty() ->
            HighPerformanceCompositeState.READY
        canReclassifyActiveRuntime &&
            assessedSessions.any { it.javascriptState == HighPerformanceJavascriptState.STALE } ->
            HighPerformanceCompositeState.DEGRADED
        canReclassifyActiveRuntime && assessedSessions.any {
            it.javascriptState == HighPerformanceJavascriptState.LOW_FREQUENCY_RESPONSIVE ||
                it.continuityState == HighPerformanceContinuityState.HIDDEN_LOW_FREQUENCY_CONTINUITY
        } && assessedSessions.none {
            it.continuityState == HighPerformanceContinuityState.HIDDEN_DEGRADED
        } -> HighPerformanceCompositeState.BACKGROUND_THROTTLED
        canReclassifyActiveRuntime && assessedSessions.any {
            it.continuityState == HighPerformanceContinuityState.HIDDEN_DEGRADED
        } -> HighPerformanceCompositeState.DEGRADED
        status.compositeState == HighPerformanceCompositeState.BACKGROUND_THROTTLED &&
            assessedSessions.isNotEmpty() &&
            assessedSessions.all { it.javascriptState == HighPerformanceJavascriptState.RESPONSIVE } &&
            assessedSessions.none { it.continuityState == HighPerformanceContinuityState.HIDDEN_DEGRADED } ->
            HighPerformanceCompositeState.ACTIVE
        status.compositeState == HighPerformanceCompositeState.ACTIVE && assessedSessions.any {
            it.javascriptState != HighPerformanceJavascriptState.RESPONSIVE ||
                it.continuityState == HighPerformanceContinuityState.HIDDEN_DEGRADED
        } -> HighPerformanceCompositeState.DEGRADED
        else -> status.compositeState
    }
    return status.copy(sessions = sessions, compositeState = compositeState)
}

data class HighPerformanceSessionStatus(
    val tokenId: String,
    val tabId: String,
    val displayName: String?,
    val origin: String,
    val startedAt: Long,
    val lastPageCallbackAt: Long,
    val jsHeartbeatInstalledAt: Long?,
    val lastJsHeartbeatAt: Long?,
    val lastMainJsHeartbeatAt: Long?,
    val lastWorkerJsHeartbeatAt: Long?,
    val javascriptState: HighPerformanceJavascriptState,
    val visible: Boolean,
    val activityState: HighPerformanceActivityState,
    val rendererPolicy: HighPerformanceRendererPolicy,
    val fullSystemProtection: Boolean,
    val documentHidden: Boolean? = null,
    val documentVisibilityState: String? = null,
    val lastVisibilityProbeAt: Long? = null,
    val pageLoadId: String? = null,
    val continuityState: HighPerformanceContinuityState = HighPerformanceContinuityState.UNKNOWN
) {
    internal fun toJson(): JSONObject = JSONObject()
        .put("tokenId", tokenId)
        .put("tabId", tabId)
        .putNullable("displayName", displayName)
        .put("origin", origin)
        .put("startedAt", startedAt)
        .put("lastPageCallbackAt", lastPageCallbackAt)
        .putNullable("jsHeartbeatInstalledAt", jsHeartbeatInstalledAt)
        .putNullable("lastJsHeartbeatAt", lastJsHeartbeatAt)
        .putNullable("lastMainJsHeartbeatAt", lastMainJsHeartbeatAt)
        .putNullable("lastWorkerJsHeartbeatAt", lastWorkerJsHeartbeatAt)
        .put("javascriptState", javascriptState.name)
        .put("visible", visible)
        .put("activityState", activityState.name)
        .put("rendererPolicy", rendererPolicy.name)
        .put("fullSystemProtection", fullSystemProtection)
        .putNullable("documentHidden", documentHidden)
        .putNullable("documentVisibilityState", documentVisibilityState)
        .putNullable("lastVisibilityProbeAt", lastVisibilityProbeAt)
        .putNullable("pageLoadId", pageLoadId)
        .put("continuityState", continuityState.name)

    companion object {
        private val VALID_VISIBILITY_STATES = setOf("visible", "hidden", "prerender", "unloaded")
        private const val MAX_PAGE_LOAD_ID_LENGTH = 96

        internal fun fromJson(json: JSONObject): HighPerformanceSessionStatus? {
            val tokenId = json.optString("tokenId").takeIf { it.isNotBlank() } ?: return null
            val tabId = json.optString("tabId").takeIf { it.isNotBlank() } ?: return null
            val origin = HighPerformanceDiagnostics.originOnly(json.optString("origin")) ?: return null
            return HighPerformanceSessionStatus(
                tokenId = tokenId,
                tabId = tabId,
                displayName = json.optNullableString("displayName")?.take(
                    HighPerformanceRuntimeRule.MAX_DISPLAY_NAME_LENGTH
                ),
                origin = origin,
                startedAt = json.optLong("startedAt", 0L),
                lastPageCallbackAt = json.optLong("lastPageCallbackAt", 0L),
                jsHeartbeatInstalledAt = json.optNullableLong("jsHeartbeatInstalledAt"),
                lastJsHeartbeatAt = json.optNullableLong("lastJsHeartbeatAt"),
                lastMainJsHeartbeatAt = json.optNullableLong("lastMainJsHeartbeatAt"),
                lastWorkerJsHeartbeatAt = json.optNullableLong("lastWorkerJsHeartbeatAt"),
                javascriptState = enumValueOrDefault(
                    json.optString("javascriptState"),
                    HighPerformanceJavascriptState.UNKNOWN
                ),
                visible = json.optBoolean("visible", false),
                activityState = enumValueOrDefault(
                    json.optString("activityState"),
                    HighPerformanceActivityState.STOPPED
                ),
                rendererPolicy = enumValueOrDefault(
                    json.optString("rendererPolicy"),
                    HighPerformanceRendererPolicy.BASELINE_IMPORTANT_WAIVED
                ),
                fullSystemProtection = json.optBoolean("fullSystemProtection", false),
                documentHidden = json.optNullableBoolean("documentHidden"),
                documentVisibilityState = json.optNullableString("documentVisibilityState")
                    ?.takeIf { it in VALID_VISIBILITY_STATES },
                lastVisibilityProbeAt = json.optNullableLong("lastVisibilityProbeAt"),
                pageLoadId = json.optNullableString("pageLoadId")?.take(MAX_PAGE_LOAD_ID_LENGTH),
                continuityState = enumValueOrDefault(
                    json.optString("continuityState"),
                    HighPerformanceContinuityState.UNKNOWN
                )
            )
        }
    }
}

data class HighPerformanceRuntimeStatus(
    val schemaVersion: Int = STATUS_SCHEMA_VERSION,
    val processInstanceId: String,
    val processName: String,
    val pid: Int,
    val processStartedAt: Long,
    val appVersionName: String,
    val appVersionCode: Long,
    val androidRelease: String,
    val androidSdkInt: Int,
    val manufacturer: String,
    val model: String,
    val webViewPackageName: String?,
    val webViewVersionName: String?,
    val updatedAt: Long,
    val nativeHeartbeatAt: Long,
    val appliedConfigVersion: Long,
    val configuredRuleCount: Int,
    val experimentalCdpContinuityEnabled: Boolean = false,
    val experimentalCdpTimingProfile: ExperimentalCdpTimingProfile =
        ExperimentalCdpTimingProfile.BALANCED,
    val verboseDiagnosticsEnabled: Boolean = false,
    val compositeState: HighPerformanceCompositeState,
    val notificationPermissionGranted: Boolean,
    val notificationsVisible: Boolean,
    val ignoringBatteryOptimizations: Boolean,
    val screenInteractive: Boolean,
    val keyguardShowing: Boolean = false,
    val keyguardSecure: Boolean = false,
    val keyguardReadyForScreenOff: Boolean = false,
    val foregroundServiceDeclared: Boolean,
    val specialUseTypeDeclared: Boolean,
    val foregroundServiceState: HighPerformanceForegroundServiceState,
    val foregroundServiceError: String?,
    val foregroundServiceStartedAt: Long?,
    val wakeLockState: HighPerformanceWakeLockState,
    val wakeLockAcquiredAt: Long?,
    val wakeLockLastReleasedAt: Long?,
    val wakeLockError: String?,
    val lastSessionStartedAt: Long?,
    val lastSessionStoppedAt: Long?,
    val lastInterruptionAt: Long?,
    val sessions: List<HighPerformanceSessionStatus>,
    val recentEvents: List<HighPerformanceAuditEvent>
) {
    internal fun toJson(): JSONObject = JSONObject()
        .put("schemaVersion", schemaVersion)
        .put("processInstanceId", processInstanceId)
        .put("processName", processName)
        .put("pid", pid)
        .put("processStartedAt", processStartedAt)
        .put("appVersionName", appVersionName)
        .put("appVersionCode", appVersionCode)
        .put("androidRelease", androidRelease)
        .put("androidSdkInt", androidSdkInt)
        .put("manufacturer", manufacturer)
        .put("model", model)
        .putNullable("webViewPackageName", webViewPackageName)
        .putNullable("webViewVersionName", webViewVersionName)
        .put("updatedAt", updatedAt)
        .put("nativeHeartbeatAt", nativeHeartbeatAt)
        .put("appliedConfigVersion", appliedConfigVersion)
        .put("configuredRuleCount", configuredRuleCount)
        .put("experimentalCdpContinuityEnabled", experimentalCdpContinuityEnabled)
        .put("experimentalCdpTimingProfile", experimentalCdpTimingProfile.name)
        .put("verboseDiagnosticsEnabled", verboseDiagnosticsEnabled)
        .put("compositeState", compositeState.name)
        .put("notificationPermissionGranted", notificationPermissionGranted)
        .put("notificationsVisible", notificationsVisible)
        .put("ignoringBatteryOptimizations", ignoringBatteryOptimizations)
        .put("screenInteractive", screenInteractive)
        .put("keyguardShowing", keyguardShowing)
        .put("keyguardSecure", keyguardSecure)
        .put("keyguardReadyForScreenOff", keyguardReadyForScreenOff)
        .put("foregroundServiceDeclared", foregroundServiceDeclared)
        .put("specialUseTypeDeclared", specialUseTypeDeclared)
        .put("foregroundServiceState", foregroundServiceState.name)
        .putNullable("foregroundServiceError", foregroundServiceError)
        .putNullable("foregroundServiceStartedAt", foregroundServiceStartedAt)
        .put("wakeLockState", wakeLockState.name)
        .putNullable("wakeLockAcquiredAt", wakeLockAcquiredAt)
        .putNullable("wakeLockLastReleasedAt", wakeLockLastReleasedAt)
        .putNullable("wakeLockError", wakeLockError)
        .putNullable("lastSessionStartedAt", lastSessionStartedAt)
        .putNullable("lastSessionStoppedAt", lastSessionStoppedAt)
        .putNullable("lastInterruptionAt", lastInterruptionAt)
        .put("sessions", JSONArray().apply {
            sessions.take(MAX_PERSISTED_SESSIONS).forEach { put(it.toJson()) }
        })
        .put("recentEvents", JSONArray().apply {
            recentEvents.takeLast(MAX_PERSISTED_EVENTS).forEach { put(it.toJson()) }
        })

    companion object {
        const val STATUS_SCHEMA_VERSION = 6
        private const val MIN_SUPPORTED_STATUS_SCHEMA_VERSION = 3
        private const val MAX_PERSISTED_SESSIONS = 32
        private const val MAX_PERSISTED_EVENTS = 80

        internal fun fromJson(json: JSONObject): HighPerformanceRuntimeStatus? {
            val schemaVersion = json.optInt("schemaVersion", -1)
            if (schemaVersion !in MIN_SUPPORTED_STATUS_SCHEMA_VERSION..STATUS_SCHEMA_VERSION) {
                return null
            }
            val processInstanceId = json.optString("processInstanceId").takeIf { it.isNotBlank() }
                ?: return null
            val processName = json.optString("processName").takeIf { it.isNotBlank() } ?: return null
            val sessions = buildList {
                val array = json.optJSONArray("sessions") ?: JSONArray()
                for (index in 0 until minOf(array.length(), MAX_PERSISTED_SESSIONS)) {
                    array.optJSONObject(index)?.let(HighPerformanceSessionStatus::fromJson)?.let(::add)
                }
            }
            val events = buildList {
                val array = json.optJSONArray("recentEvents") ?: JSONArray()
                for (index in 0 until minOf(array.length(), MAX_PERSISTED_EVENTS)) {
                    array.optJSONObject(index)?.let(HighPerformanceAuditEvent::fromJson)?.let(::add)
                }
            }
            return HighPerformanceRuntimeStatus(
                processInstanceId = processInstanceId,
                processName = processName,
                pid = json.optInt("pid", -1),
                processStartedAt = json.optLong("processStartedAt", 0L),
                appVersionName = json.optString("appVersionName", "unknown").ifBlank { "unknown" },
                appVersionCode = json.optLong("appVersionCode", 0L),
                androidRelease = json.optString("androidRelease", "unknown").ifBlank { "unknown" },
                androidSdkInt = json.optInt("androidSdkInt", -1),
                manufacturer = json.optString("manufacturer", "unknown").ifBlank { "unknown" },
                model = json.optString("model", "unknown").ifBlank { "unknown" },
                webViewPackageName = json.optNullableString("webViewPackageName"),
                webViewVersionName = json.optNullableString("webViewVersionName"),
                updatedAt = json.optLong("updatedAt", 0L),
                nativeHeartbeatAt = json.optLong("nativeHeartbeatAt", 0L),
                appliedConfigVersion = json.optLong("appliedConfigVersion", 0L),
                configuredRuleCount = json.optInt("configuredRuleCount", 0),
                experimentalCdpContinuityEnabled = json.optBoolean(
                    "experimentalCdpContinuityEnabled",
                    false
                ),
                experimentalCdpTimingProfile = enumValueOrDefault(
                    json.optString("experimentalCdpTimingProfile"),
                    ExperimentalCdpTimingProfile.BALANCED
                ),
                verboseDiagnosticsEnabled = json.optBoolean("verboseDiagnosticsEnabled", false),
                compositeState = enumValueOrDefault(
                    json.optString("compositeState"),
                    HighPerformanceCompositeState.ERROR
                ),
                notificationPermissionGranted = json.optBoolean("notificationPermissionGranted", false),
                notificationsVisible = json.optBoolean("notificationsVisible", false),
                ignoringBatteryOptimizations = json.optBoolean("ignoringBatteryOptimizations", false),
                screenInteractive = json.optBoolean("screenInteractive", true),
                keyguardShowing = json.optBoolean("keyguardShowing", false),
                keyguardSecure = json.optBoolean("keyguardSecure", false),
                keyguardReadyForScreenOff = json.optBoolean("keyguardReadyForScreenOff", false),
                foregroundServiceDeclared = json.optBoolean("foregroundServiceDeclared", false),
                specialUseTypeDeclared = json.optBoolean("specialUseTypeDeclared", false),
                foregroundServiceState = enumValueOrDefault(
                    json.optString("foregroundServiceState"),
                    HighPerformanceForegroundServiceState.STOPPED
                ),
                foregroundServiceError = json.optNullableString("foregroundServiceError"),
                foregroundServiceStartedAt = json.optNullableLong("foregroundServiceStartedAt"),
                wakeLockState = enumValueOrDefault(
                    json.optString("wakeLockState"),
                    HighPerformanceWakeLockState.NOT_HELD
                ),
                wakeLockAcquiredAt = json.optNullableLong("wakeLockAcquiredAt"),
                wakeLockLastReleasedAt = json.optNullableLong("wakeLockLastReleasedAt"),
                wakeLockError = json.optNullableString("wakeLockError"),
                lastSessionStartedAt = json.optNullableLong("lastSessionStartedAt"),
                lastSessionStoppedAt = json.optNullableLong("lastSessionStoppedAt"),
                lastInterruptionAt = json.optNullableLong("lastInterruptionAt"),
                sessions = sessions,
                recentEvents = events
            )
        }
    }
}

data class HighPerformanceRuntimeStatusReadResult(
    val status: HighPerformanceRuntimeStatus?,
    val stale: Boolean,
    val reason: String?
)

/**
 * Cross-process status transport. The :webview process is the only normal writer; the main process
 * reads this file instead of consulting process-local singleton state.
 */
object HighPerformanceRuntimeStatusStore {
    private const val ACTIVE_STATUS_STALE_AFTER_MS = 90_000L
    private const val IDLE_STATUS_STALE_AFTER_MS = 10 * 60_000L
    private val writer = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "high-performance-status-writer").apply { isDaemon = true }
    }

    fun publish(context: Context, status: HighPerformanceRuntimeStatus) {
        val appContext = context.applicationContext
        writer.execute {
            runCatching { writeNow(appContext, status) }
                .onFailure {
                    HighPerformanceDiagnostics.record(
                        type = "runtime_status_write_failed",
                        result = "failed",
                        reason = it.javaClass.simpleName
                    )
                }
        }
    }

    /** Call from an IO dispatcher when used by UI. */
    fun read(context: Context, now: Long = System.currentTimeMillis()): HighPerformanceRuntimeStatusReadResult {
        val persistedStatus = runCatching {
            val atomicFile = atomicFile(context.applicationContext)
            if (!atomicFile.baseFile.exists()) return@runCatching null
            val json = JSONObject(String(atomicFile.readFully(), Charsets.UTF_8))
            HighPerformanceRuntimeStatus.fromJson(json)
        }.getOrNull()
            ?: return HighPerformanceRuntimeStatusReadResult(null, stale = true, reason = "missing_or_invalid")
        val status = refreshHeartbeatDerivedState(persistedStatus, now)

        val claimsActiveRuntime = status.sessions.isNotEmpty() ||
            status.foregroundServiceState == HighPerformanceForegroundServiceState.RUNNING ||
            status.wakeLockState == HighPerformanceWakeLockState.HELD
        val staleAfter = if (claimsActiveRuntime) ACTIVE_STATUS_STALE_AFTER_MS else IDLE_STATUS_STALE_AFTER_MS
        val age = (now - status.updatedAt).coerceAtLeast(0L)
        val processMissing = claimsActiveRuntime &&
            !HighPerformanceProcessState.isRecordedProcessAlive(context, status)
        val stale = status.updatedAt <= 0L || age > staleAfter || processMissing
        val reason = when {
            processMissing -> "process_not_running"
            age > staleAfter -> "heartbeat_stale"
            status.updatedAt <= 0L -> "invalid_timestamp"
            else -> null
        }
        return HighPerformanceRuntimeStatusReadResult(status, stale, reason)
    }

    /**
     * Deletes status after all previously queued writes and does not return until deletion finishes.
     * This keeps an immediate UI refresh from racing a stale writer when :webview is not running.
     */
    fun clear(context: Context): Boolean {
        val appContext = context.applicationContext
        return runCatching {
            writer.submit<Boolean> {
                val file = atomicFile(appContext)
                file.delete()
                !file.baseFile.exists()
            }.get()
        }.getOrDefault(false)
    }

    private fun writeNow(context: Context, status: HighPerformanceRuntimeStatus) {
        val file = atomicFile(context)
        file.baseFile.parentFile?.mkdirs()
        val stream = file.startWrite()
        try {
            stream.write(status.toJson().toString().toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
        } catch (error: Throwable) {
            file.failWrite(stream)
            throw error
        }
    }

    private fun atomicFile(context: Context): AtomicFile =
        AtomicFile(File(context.filesDir, HIGH_PERFORMANCE_RUNTIME_STATUS_FILE_NAME))
}

internal const val HIGH_PERFORMANCE_RUNTIME_STATUS_FILE_NAME =
    "high_performance_runtime_status.json"

internal const val HIGH_PERFORMANCE_JS_HEARTBEAT_STALE_AFTER_MS = 20_000L
internal const val HIGH_PERFORMANCE_BACKGROUND_WORKER_STALE_AFTER_MS = 90_000L

private fun JSONObject.putNullable(name: String, value: Any?): JSONObject =
    put(name, value ?: JSONObject.NULL)

private fun JSONObject.optNullableLong(name: String): Long? {
    if (!has(name) || isNull(name)) return null
    return optLong(name)
}

private fun JSONObject.optNullableBoolean(name: String): Boolean? {
    if (!has(name) || isNull(name)) return null
    return if (opt(name) is Boolean) optBoolean(name) else null
}

private fun JSONObject.optNullableString(name: String): String? {
    if (!has(name) || isNull(name)) return null
    return optString(name).takeIf { it.isNotBlank() }
}

private inline fun <reified T : Enum<T>> enumValueOrDefault(raw: String, fallback: T): T =
    enumValues<T>().firstOrNull { it.name == raw } ?: fallback
