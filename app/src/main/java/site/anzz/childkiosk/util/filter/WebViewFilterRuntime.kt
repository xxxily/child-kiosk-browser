package site.anzz.childkiosk.util.filter

import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

enum class WebViewFilterRuntimeStatus {
    PREPARING,
    READY,
    DEGRADED_LKG,
    DEGRADED_BUNDLED
}

data class WebViewFilterEngineHandle(
    /** Snapshot used to build [engine]; these two values must always remain paired. */
    val snapshot: FilterRuntimeSnapshot,
    val engine: FilterEngine,
    val status: WebViewFilterRuntimeStatus,
    val generation: Long,
    val reason: String = "",
    /** New policy being prepared when it differs from the currently served handle. */
    val requestedSnapshot: FilterRuntimeSnapshot = snapshot
)

fun interface WebViewFilterEngineLoader {
    fun load(snapshot: FilterRuntimeSnapshot): FilterEngine
}

/**
 * Owns a strong engine reference for a WebView session. Loads always run off the caller thread,
 * timeout fallback is bounded, and a stale build cannot replace a newer generation.
 */
class WebViewFilterRuntime(
    private val engineLoader: WebViewFilterEngineLoader,
    bundledSnapshot: FilterRuntimeSnapshot,
    bundledEngine: FilterEngine,
    private val buildExecutor: Executor = DEFAULT_BUILD_EXECUTOR,
    private val scheduler: ScheduledExecutorService = DEFAULT_SCHEDULER,
    private val timeoutMs: Long = DEFAULT_PREPARE_TIMEOUT_MS
) {
    init {
        require(timeoutMs > 0L) { "timeoutMs must be positive" }
    }

    private val requestedGeneration = AtomicLong(0L)
    private val closed = AtomicBoolean(false)
    private val publicationLock = Any()
    private val bundled = WebViewFilterEngineHandle(
        snapshot = bundledSnapshot,
        engine = bundledEngine,
        status = WebViewFilterRuntimeStatus.DEGRADED_BUNDLED,
        generation = 0L,
        reason = "bundled fallback"
    )
    private val current = AtomicReference(bundled)
    private val lastKnownGood = AtomicReference<WebViewFilterEngineHandle?>(null)

    fun currentHandle(): WebViewFilterEngineHandle = current.get()

    fun prepare(
        snapshot: FilterRuntimeSnapshot,
        onChanged: (WebViewFilterEngineHandle) -> Unit = {}
    ): Long {
        check(!closed.get()) { "WebViewFilterRuntime is closed" }
        val (generation, preparing) = synchronized(publicationLock) {
            check(!closed.get()) { "WebViewFilterRuntime is closed" }
            val nextGeneration = requestedGeneration.incrementAndGet()
            val nextHandle = fallbackHandle(nextGeneration, snapshot, "preparing")
                .copy(status = WebViewFilterRuntimeStatus.PREPARING)
            current.set(nextHandle)
            nextGeneration to nextHandle
        }
        onChanged(preparing)

        val completed = AtomicBoolean(false)
        var timeoutFuture: ScheduledFuture<*>? = null

        try {
            timeoutFuture = scheduler.schedule(
                {
                    if (completed.compareAndSet(false, true)) {
                        publishFallback(
                            generation,
                            snapshot,
                            TimeoutException("filter engine preparation timed out"),
                            onChanged
                        )
                    }
                },
                timeoutMs,
                TimeUnit.MILLISECONDS
            )
            buildExecutor.execute {
                if (completed.get()) return@execute
                try {
                    val engine = engineLoader.load(snapshot)
                    if (completed.compareAndSet(false, true)) {
                        timeoutFuture?.cancel(false)
                        val ready = WebViewFilterEngineHandle(
                            snapshot = snapshot,
                            engine = engine,
                            status = WebViewFilterRuntimeStatus.READY,
                            generation = generation
                        )
                        publishReadyIfCurrent(generation, ready, onChanged)
                    }
                } catch (error: Throwable) {
                    if (completed.compareAndSet(false, true)) {
                        timeoutFuture?.cancel(false)
                        publishFallback(generation, snapshot, error, onChanged)
                    }
                }
            }
        } catch (error: Throwable) {
            if (completed.compareAndSet(false, true)) {
                timeoutFuture?.cancel(false)
                publishFallback(generation, snapshot, error, onChanged)
            }
        }
        return generation
    }

    fun close() {
        synchronized(publicationLock) {
            if (closed.compareAndSet(false, true)) {
                requestedGeneration.incrementAndGet()
            }
        }
    }

    private fun publishFallback(
        generation: Long,
        requestedSnapshot: FilterRuntimeSnapshot,
        error: Throwable,
        onChanged: (WebViewFilterEngineHandle) -> Unit
    ) {
        publishIfCurrent(
            generation,
            fallbackHandle(generation, requestedSnapshot, boundedReason(error)),
            onChanged
        )
    }

    private fun publishReadyIfCurrent(
        generation: Long,
        handle: WebViewFilterEngineHandle,
        onChanged: (WebViewFilterEngineHandle) -> Unit
    ) {
        val published = synchronized(publicationLock) {
            if (closed.get() || requestedGeneration.get() != generation) {
                false
            } else {
                lastKnownGood.set(handle)
                current.set(handle)
                true
            }
        }
        if (published) onChanged(handle)
    }

    private fun fallbackHandle(
        generation: Long,
        requestedSnapshot: FilterRuntimeSnapshot,
        reason: String
    ): WebViewFilterEngineHandle {
        val lkg = lastKnownGood.get()
        return if (lkg != null) {
            lkg.copy(
                snapshot = servingSnapshotWithRequestedPolicy(lkg.snapshot, requestedSnapshot),
                status = WebViewFilterRuntimeStatus.DEGRADED_LKG,
                generation = generation,
                reason = reason,
                requestedSnapshot = requestedSnapshot
            )
        } else {
            bundled.copy(
                snapshot = servingSnapshotWithRequestedPolicy(bundled.snapshot, requestedSnapshot),
                generation = generation,
                reason = reason,
                requestedSnapshot = requestedSnapshot
            )
        }
    }

    private fun servingSnapshotWithRequestedPolicy(
        servingSnapshot: FilterRuntimeSnapshot,
        requestedSnapshot: FilterRuntimeSnapshot
    ): FilterRuntimeSnapshot {
        // enabled and site overrides are per-request policy, not compiled rule identity. Applying
        // them to an LKG/bundled engine keeps settings responsive without mixing rule generations.
        return servingSnapshot.copy(
            enabled = requestedSnapshot.enabled,
            siteOverrides = requestedSnapshot.siteOverrides
        )
    }

    private fun publishIfCurrent(
        generation: Long,
        handle: WebViewFilterEngineHandle,
        onChanged: (WebViewFilterEngineHandle) -> Unit
    ): Boolean {
        val published = synchronized(publicationLock) {
            if (closed.get() || requestedGeneration.get() != generation) {
                false
            } else {
                current.set(handle)
                true
            }
        }
        if (published) onChanged(handle)
        return published
    }

    private fun boundedReason(error: Throwable): String {
        val message = error.message.orEmpty().replace(Regex("[\\r\\n\\t]"), " ").take(160)
        return if (message.isBlank()) error.javaClass.simpleName.take(80) else message
    }

    companion object {
        const val DEFAULT_PREPARE_TIMEOUT_MS = 15_000L

        private val DEFAULT_BUILD_EXECUTOR: Executor = Executors.newSingleThreadExecutor { task ->
            Thread(task, "ChildKioskFilterBuild").apply { isDaemon = true }
        }
        private val DEFAULT_SCHEDULER: ScheduledExecutorService =
            Executors.newSingleThreadScheduledExecutor { task ->
                Thread(task, "ChildKioskFilterTimeout").apply { isDaemon = true }
            }
    }
}
