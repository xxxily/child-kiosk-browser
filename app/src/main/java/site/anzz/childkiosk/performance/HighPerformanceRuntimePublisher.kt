package site.anzz.childkiosk.performance

import android.content.Context
import android.content.Intent
import android.util.AtomicFile
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal const val ACTION_HIGH_PERFORMANCE_CONFIG_UPDATED =
    "site.anzz.childkiosk.action.HIGH_PERFORMANCE_CONFIG_UPDATED"
internal const val ACTION_HIGH_PERFORMANCE_STOP_ALL =
    "site.anzz.childkiosk.action.HIGH_PERFORMANCE_STOP_ALL"
internal const val ACTION_HIGH_PERFORMANCE_CLEAR_DIAGNOSTICS =
    "site.anzz.childkiosk.action.HIGH_PERFORMANCE_CLEAR_DIAGNOSTICS"
internal const val EXTRA_HIGH_PERFORMANCE_CONFIG_VERSION = "high_performance_config_version"
internal const val EXTRA_HIGH_PERFORMANCE_STOP_REASON = "high_performance_stop_reason"
internal const val HIGH_PERFORMANCE_PUBLICATION_FAILED_REASON = "publication_failed"

internal data class HighPerformancePublicationResult(
    val snapshotWriteAttempted: Boolean,
    val snapshotWritten: Boolean,
    val configUpdateBroadcastSent: Boolean,
    val stopBroadcastSent: Boolean,
    val errors: List<String>
) {
    val succeeded: Boolean get() = errors.isEmpty()
}

internal interface HighPerformanceSnapshotPublisher {
    suspend fun publish(
        snapshot: HighPerformanceRuntimeSnapshot,
        stopReason: String? = null
    ): HighPerformancePublicationResult

    suspend fun requestStop(configVersion: Long, reason: String): HighPerformancePublicationResult
}

internal interface HighPerformanceSnapshotStore {
    fun write(snapshot: HighPerformanceRuntimeSnapshot)
    fun invalidate()
}

/**
 * Publishes configuration using one-writer AtomicFile storage followed by package-scoped signals.
 * Broadcasts contain only a version/reason; receivers always read and validate the AtomicFile.
 */
internal class HighPerformanceRuntimePublisher(
    context: Context,
    private val store: HighPerformanceSnapshotStore =
        HighPerformanceRuntimeStore(context.applicationContext),
    private val broadcastSender: ((Intent) -> Boolean)? = null
) : HighPerformanceSnapshotPublisher {
    private val appContext = context.applicationContext

    override suspend fun publish(
        snapshot: HighPerformanceRuntimeSnapshot,
        stopReason: String?
    ): HighPerformancePublicationResult = withContext(Dispatchers.IO) {
        val errors = mutableListOf<String>()
        val snapshotWritten = runCatching {
            store.write(snapshot)
            true
        }.getOrElse { error ->
            errors += "snapshot_write:${error.safeFailureName()}"
            runCatching { store.invalidate() }
                .onFailure { invalidationError ->
                    errors += "snapshot_invalidate:${invalidationError.safeFailureName()}"
                }
            cacheSnapshot(
                HighPerformanceRuntimeSnapshot.disabled(
                    configVersion = snapshot.configVersion,
                    generatedAt = snapshot.generatedAt
                )
            )
            false
        }

        val updateSent = if (snapshotWritten) {
            sendPackageBroadcast(
                Intent(ACTION_HIGH_PERFORMANCE_CONFIG_UPDATED)
                    .putExtra(EXTRA_HIGH_PERFORMANCE_CONFIG_VERSION, snapshot.configVersion)
            ).also { sent ->
                if (!sent) errors += "config_broadcast:failed"
            }
        } else {
            false
        }

        if (snapshotWritten && updateSent) {
            cacheSnapshot(snapshot)
        } else if (snapshotWritten) {
            val disabledTombstone = HighPerformanceRuntimeSnapshot.disabled(
                configVersion = snapshot.configVersion,
                generatedAt = snapshot.generatedAt
            )
            runCatching { store.write(disabledTombstone) }
                .onFailure { error ->
                    errors += "tombstone_write:${error.safeFailureName()}"
                    runCatching { store.invalidate() }
                        .onFailure { invalidationError ->
                            errors += "tombstone_invalidate:${invalidationError.safeFailureName()}"
                        }
                }
            // The process-local launch path must remain fail-closed even if disk invalidation fails.
            cacheSnapshot(disabledTombstone)
        }

        val effectiveStopReason = when {
            !snapshotWritten || !updateSent -> HIGH_PERFORMANCE_PUBLICATION_FAILED_REASON
            stopReason != null -> stopReason
            else -> null
        }
        val stopSent = if (effectiveStopReason != null) {
            sendStopBroadcast(snapshot.configVersion, effectiveStopReason).also { sent ->
                if (!sent) errors += "stop_broadcast:failed"
            }
        } else {
            false
        }

        HighPerformancePublicationResult(
            snapshotWriteAttempted = true,
            snapshotWritten = snapshotWritten,
            configUpdateBroadcastSent = updateSent,
            stopBroadcastSent = stopSent,
            errors = errors.toList()
        )
    }

    override suspend fun requestStop(
        configVersion: Long,
        reason: String
    ): HighPerformancePublicationResult = withContext(Dispatchers.IO) {
        val sent = sendStopBroadcast(configVersion, reason)
        HighPerformancePublicationResult(
            snapshotWriteAttempted = false,
            snapshotWritten = false,
            configUpdateBroadcastSent = false,
            stopBroadcastSent = sent,
            errors = if (sent) emptyList() else listOf("stop_broadcast:failed")
        )
    }

    private fun sendStopBroadcast(configVersion: Long, reason: String): Boolean {
        require(configVersion >= 0L) { "Invalid high-performance config version" }
        val safeReason = reason.trim()
        require(safeReason.matches(SAFE_REASON_PATTERN)) { "Invalid high-performance stop reason" }
        return sendPackageBroadcast(
            Intent(ACTION_HIGH_PERFORMANCE_STOP_ALL)
                .putExtra(EXTRA_HIGH_PERFORMANCE_CONFIG_VERSION, configVersion)
                .putExtra(EXTRA_HIGH_PERFORMANCE_STOP_REASON, safeReason)
        )
    }

    private fun sendPackageBroadcast(intent: Intent): Boolean {
        return runCatching {
            val packagedIntent = intent.setPackage(appContext.packageName)
            broadcastSender?.invoke(packagedIntent) ?: run {
                appContext.sendBroadcast(packagedIntent)
                true
            }
        }.getOrDefault(false)
    }

    companion object {
        @Volatile
        private var cachedSnapshot: HighPerformanceRuntimeSnapshot? = null

        /** Synchronous bounded read for launch-Intent snapshot construction. */
        fun readPublishedSnapshot(
            context: Context,
            minimumConfigVersion: Long = 0L
        ): HighPerformanceRuntimeSnapshot {
            val cached = cachedSnapshot
            if (cached != null && cached.configVersion >= minimumConfigVersion) return cached
            return HighPerformanceRuntimeStore(context.applicationContext)
                .read(minimumConfigVersion)
                .also(::cacheSnapshot)
        }

        /** Disk-only read intended for an IO-thread receiver in the WebView process. */
        fun readPublishedSnapshotFromDisk(
            context: Context,
            minimumConfigVersion: Long = 0L
        ): HighPerformanceRuntimeSnapshot {
            return HighPerformanceRuntimeStore(context.applicationContext)
                .read(minimumConfigVersion)
                .also(::cacheSnapshot)
        }

        private fun cacheSnapshot(snapshot: HighPerformanceRuntimeSnapshot) {
            synchronized(this) {
                val current = cachedSnapshot
                if (current == null || snapshot.configVersion >= current.configVersion) {
                    cachedSnapshot = snapshot
                }
            }
        }

        internal fun clearCachedSnapshotForTests() {
            cachedSnapshot = null
        }

        fun requestClearDiagnostics(context: Context): Boolean {
            val appContext = context.applicationContext
            val statusCleared = HighPerformanceRuntimeStatusStore.clear(appContext)
            val broadcastSent = runCatching {
                appContext.sendBroadcast(
                    Intent(ACTION_HIGH_PERFORMANCE_CLEAR_DIAGNOSTICS)
                        .setPackage(appContext.packageName)
                )
                true
            }.getOrDefault(false)
            return statusCleared && broadcastSent
        }
    }
}

internal class HighPerformanceRuntimeStore(context: Context) : HighPerformanceSnapshotStore {
    private val atomicFile = AtomicFile(File(context.filesDir, RUNTIME_CONFIG_FILE_NAME))

    override fun write(snapshot: HighPerformanceRuntimeSnapshot) {
        val bytes = snapshot.toJsonString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= HighPerformanceRuntimeSnapshot.MAX_SERIALIZED_BYTES) {
            "High-performance runtime snapshot is too large"
        }

        atomicFile.baseFile.parentFile?.mkdirs()
        val stream = atomicFile.startWrite()
        try {
            stream.write(bytes)
            stream.flush()
            atomicFile.finishWrite(stream)
        } catch (error: Throwable) {
            atomicFile.failWrite(stream)
            throw IOException("Unable to publish high-performance runtime snapshot", error)
        }
    }

    override fun invalidate() {
        atomicFile.delete()
        check(!atomicFile.baseFile.exists()) {
            "Unable to invalidate high-performance runtime snapshot"
        }
    }

    fun read(minimumConfigVersion: Long = 0L): HighPerformanceRuntimeSnapshot {
        if (!atomicFile.baseFile.exists()) {
            return HighPerformanceRuntimeSnapshot.disabled(minimumConfigVersion)
        }
        val raw = runCatching {
            val bytes = atomicFile.readFully()
            require(bytes.size <= HighPerformanceRuntimeSnapshot.MAX_SERIALIZED_BYTES)
            String(bytes, Charsets.UTF_8)
        }.getOrNull()
        return HighPerformanceRuntimeSnapshot.parseOrDisabled(raw, minimumConfigVersion)
    }

    companion object {
        const val RUNTIME_CONFIG_FILE_NAME = "high_performance_runtime_config.json"
    }
}

private val SAFE_REASON_PATTERN = Regex("[a-z0-9_.-]{1,64}")

private fun Throwable.safeFailureName(): String = javaClass.simpleName.ifBlank { "failure" }
