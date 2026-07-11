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

data class HighPerformanceSessionStatus(
    val tokenId: String,
    val tabId: String,
    val displayName: String?,
    val origin: String,
    val startedAt: Long,
    val lastPageCallbackAt: Long,
    val visible: Boolean,
    val activityState: HighPerformanceActivityState,
    val rendererPolicy: HighPerformanceRendererPolicy,
    val fullSystemProtection: Boolean
) {
    internal fun toJson(): JSONObject = JSONObject()
        .put("tokenId", tokenId)
        .put("tabId", tabId)
        .putNullable("displayName", displayName)
        .put("origin", origin)
        .put("startedAt", startedAt)
        .put("lastPageCallbackAt", lastPageCallbackAt)
        .put("visible", visible)
        .put("activityState", activityState.name)
        .put("rendererPolicy", rendererPolicy.name)
        .put("fullSystemProtection", fullSystemProtection)

    companion object {
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
                visible = json.optBoolean("visible", false),
                activityState = enumValueOrDefault(
                    json.optString("activityState"),
                    HighPerformanceActivityState.STOPPED
                ),
                rendererPolicy = enumValueOrDefault(
                    json.optString("rendererPolicy"),
                    HighPerformanceRendererPolicy.BASELINE_IMPORTANT_WAIVED
                ),
                fullSystemProtection = json.optBoolean("fullSystemProtection", false)
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
    val appliedConfigVersion: Long,
    val configuredRuleCount: Int,
    val compositeState: HighPerformanceCompositeState,
    val notificationPermissionGranted: Boolean,
    val notificationsVisible: Boolean,
    val ignoringBatteryOptimizations: Boolean,
    val screenInteractive: Boolean,
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
        .put("appliedConfigVersion", appliedConfigVersion)
        .put("configuredRuleCount", configuredRuleCount)
        .put("compositeState", compositeState.name)
        .put("notificationPermissionGranted", notificationPermissionGranted)
        .put("notificationsVisible", notificationsVisible)
        .put("ignoringBatteryOptimizations", ignoringBatteryOptimizations)
        .put("screenInteractive", screenInteractive)
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
        const val STATUS_SCHEMA_VERSION = 2
        private const val MAX_PERSISTED_SESSIONS = 32
        private const val MAX_PERSISTED_EVENTS = 80

        internal fun fromJson(json: JSONObject): HighPerformanceRuntimeStatus? {
            if (json.optInt("schemaVersion", -1) != STATUS_SCHEMA_VERSION) return null
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
                appliedConfigVersion = json.optLong("appliedConfigVersion", 0L),
                configuredRuleCount = json.optInt("configuredRuleCount", 0),
                compositeState = enumValueOrDefault(
                    json.optString("compositeState"),
                    HighPerformanceCompositeState.ERROR
                ),
                notificationPermissionGranted = json.optBoolean("notificationPermissionGranted", false),
                notificationsVisible = json.optBoolean("notificationsVisible", false),
                ignoringBatteryOptimizations = json.optBoolean("ignoringBatteryOptimizations", false),
                screenInteractive = json.optBoolean("screenInteractive", true),
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
        val status = runCatching {
            val atomicFile = atomicFile(context.applicationContext)
            if (!atomicFile.baseFile.exists()) return@runCatching null
            val json = JSONObject(String(atomicFile.readFully(), Charsets.UTF_8))
            HighPerformanceRuntimeStatus.fromJson(json)
        }.getOrNull()
            ?: return HighPerformanceRuntimeStatusReadResult(null, stale = true, reason = "missing_or_invalid")

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

private fun JSONObject.putNullable(name: String, value: Any?): JSONObject =
    put(name, value ?: JSONObject.NULL)

private fun JSONObject.optNullableLong(name: String): Long? {
    if (!has(name) || isNull(name)) return null
    return optLong(name)
}

private fun JSONObject.optNullableString(name: String): String? {
    if (!has(name) || isNull(name)) return null
    return optString(name).takeIf { it.isNotBlank() }
}

private inline fun <reified T : Enum<T>> enumValueOrDefault(raw: String, fallback: T): T =
    enumValues<T>().firstOrNull { it.name == raw } ?: fallback
