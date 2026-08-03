package site.anzz.childkiosk.performance.cdp

import android.os.Handler
import android.os.Looper
import android.os.Process
import site.anzz.childkiosk.performance.HighPerformanceContinuityCandidate
import site.anzz.childkiosk.performance.HighPerformanceDiagnostics
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Experimental hidden-page continuity edge verified on Android 16 / WebView 150.
 *
 * It does not provide foreground-level scheduling. The DevTools endpoint is leased only long enough
 * to verify the protected page token, send frozen -> active, and restore the persistent preference.
 */
internal object ExperimentalCdpContinuityController {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "experimental-cdp-continuity").apply { isDaemon = true }
    }
    private val generation = AtomicLong(0L)
    private var delayedStart: Runnable? = null
    private var leaseTimeout: Runnable? = null
    private var leaseForcedClose: Runnable? = null
    private var pending: Future<*>? = null
    private var activeLeaseId: String? = null

    fun updatePersistentDebuggingPreference(enabled: Boolean) {
        ensureMainThread()
        WebViewDebuggingGate.applyPersistentPreference(enabled)
            .onFailure { failure ->
                recordFailure(
                    "experimental_cdp_debugging_restore_failed",
                    failure,
                    "preference_update"
                )
            }
    }

    fun schedule(
        candidate: HighPerformanceContinuityCandidate?,
        enabled: Boolean
    ) {
        ensureMainThread()
        cancel(reason = "rescheduled")
        if (!enabled) return
        if (candidate == null) {
            HighPerformanceDiagnostics.record(
                type = "experimental_cdp_edge_skipped",
                result = "inactive",
                reason = "no_visible_protected_page"
            )
            return
        }
        val scheduledGeneration = generation.get()
        lateinit var start: Runnable
        start = Runnable {
            if (generation.get() != scheduledGeneration) {
                if (delayedStart === start) delayedStart = null
                return@Runnable
            }
            if (activeLeaseId != null || pending?.isDone == false) {
                mainHandler.postDelayed(start, RETRY_ACTIVE_LEASE_DELAY_MS)
                return@Runnable
            }
            if (delayedStart === start) delayedStart = null
            startLeaseAndRun(
                candidate = candidate,
                scheduledGeneration = scheduledGeneration
            )
        }
        delayedStart = start
        mainHandler.postDelayed(start, START_DELAY_MS)
        HighPerformanceDiagnostics.record(
            type = "experimental_cdp_edge_scheduled",
            result = "ok",
            originOrUrl = candidate.origin,
            sessionId = candidate.sessionId,
            reason = "delay_${START_DELAY_MS}ms"
        )
    }

    fun cancel(reason: String) {
        ensureMainThread()
        generation.incrementAndGet()
        delayedStart?.let(mainHandler::removeCallbacks)
        delayedStart = null
        if (activeLeaseId == null) {
            leaseTimeout?.let(mainHandler::removeCallbacks)
            leaseTimeout = null
            leaseForcedClose?.let(mainHandler::removeCallbacks)
            leaseForcedClose = null
            if (pending?.isDone != false) pending = null
            WebViewDebuggingGate.forceRestorePersistent()
                .onFailure { failure ->
                    recordFailure("experimental_cdp_debugging_restore_failed", failure, reason)
                }
        }
    }

    private fun startLeaseAndRun(
        candidate: HighPerformanceContinuityCandidate,
        scheduledGeneration: Long
    ) {
        val leaseId = UUID.randomUUID().toString()
        val lease = WebViewDebuggingGate.acquireTemporary(leaseId).getOrElse { failure ->
            recordFailure("experimental_cdp_debugging_enable_failed", failure, "lease_acquire")
            return
        }
        activeLeaseId = leaseId
        lateinit var timeout: Runnable
        timeout = Runnable leaseTimeout@{
            if (activeLeaseId != leaseId) return@leaseTimeout
            if (leaseTimeout === timeout) leaseTimeout = null
            generation.compareAndSet(scheduledGeneration, scheduledGeneration + 1L)
            HighPerformanceDiagnostics.record(
                type = "experimental_cdp_debugging_lease_expired",
                result = "timeout",
                originOrUrl = candidate.origin,
                sessionId = candidate.sessionId,
                reason = "closing_grace_${DEBUGGING_FORCE_CLOSE_GRACE_MS}ms"
            )
            lateinit var forcedClose: Runnable
            forcedClose = Runnable forcedClose@{
                if (activeLeaseId != leaseId) return@forcedClose
                if (leaseForcedClose === forcedClose) leaseForcedClose = null
                WebViewDebuggingGate.releaseTemporary(leaseId)
                    .onSuccess { release ->
                        if (activeLeaseId == leaseId) activeLeaseId = null
                        HighPerformanceDiagnostics.record(
                            type = if (release.debuggingEnabled) {
                                "experimental_cdp_debugging_restored"
                            } else {
                                "experimental_cdp_debugging_forced_closed"
                            },
                            result = "timeout",
                            originOrUrl = candidate.origin,
                            sessionId = candidate.sessionId,
                            reason = if (release.persistentDebuggingEnabled) {
                                "chrome_inspect_enabled"
                            } else {
                                "lease_${MAX_DEBUGGING_LEASE_MS + DEBUGGING_FORCE_CLOSE_GRACE_MS}ms"
                            }
                        )
                    }
                    .onFailure { failure ->
                        if (activeLeaseId == leaseId) activeLeaseId = null
                        recordFailure(
                            "experimental_cdp_debugging_restore_failed",
                            failure,
                            "forced_close"
                        )
                    }
                if (pending?.isDone == true) pending = null
            }
            leaseForcedClose = forcedClose
            mainHandler.postDelayed(forcedClose, DEBUGGING_FORCE_CLOSE_GRACE_MS)
        }
        leaseTimeout = timeout
        mainHandler.postDelayed(timeout, MAX_DEBUGGING_LEASE_MS)
        val openedAt = System.currentTimeMillis()
        HighPerformanceDiagnostics.record(
            type = if (lease.temporarilyEnabled) {
                "experimental_cdp_debugging_enabled"
            } else {
                "experimental_cdp_debugging_reused"
            },
            result = "ok",
            originOrUrl = candidate.origin,
            sessionId = candidate.sessionId,
            reason = if (lease.temporarilyEnabled) "temporary_lease" else "chrome_inspect_enabled"
        )
        pending = executor.submit {
            runEdge(
                candidate = candidate,
                lease = lease,
                openedAt = openedAt,
                scheduledGeneration = scheduledGeneration
            )
        }
    }

    private fun runEdge(
        candidate: HighPerformanceContinuityCandidate,
        lease: WebViewDebuggingLease,
        openedAt: Long,
        scheduledGeneration: Long
    ) {
        val client = RestrictedDevToolsClient("webview_devtools_remote_${Process.myPid()}")
        val startedAt = System.currentTimeMillis()
        HighPerformanceDiagnostics.record(
            type = "experimental_cdp_edge_started",
            result = "ok",
            originOrUrl = candidate.origin,
            sessionId = candidate.sessionId
        )
        try {
            val target = client.discoverSessionTarget(
                expectedOrigin = candidate.origin,
                heartbeatToken = candidate.heartbeatToken,
                timeoutMs = TARGET_DISCOVERY_TIMEOUT_MS,
                shouldContinue = { generation.get() == scheduledGeneration }
            )
            check(generation.get() == scheduledGeneration) { "cancelled_before_edge" }
            when (
                client.sendLifecycleEdgeWhenHidden(
                    target = target,
                    expectedOrigin = candidate.origin,
                    heartbeatToken = candidate.heartbeatToken,
                    edgeDelayMs = EDGE_DELAY_MS,
                    hiddenConfirmationTimeoutMs = HIDDEN_CONFIRMATION_TIMEOUT_MS,
                    shouldContinue = { generation.get() == scheduledGeneration }
                )
            ) {
                RestrictedLifecycleEdgeOutcome.SENT -> HighPerformanceDiagnostics.record(
                    type = "experimental_cdp_edge_succeeded",
                    result = "low_frequency_continuity",
                    originOrUrl = candidate.origin,
                    sessionId = candidate.sessionId,
                    reason = "duration_${System.currentTimeMillis() - startedAt}ms"
                )
                RestrictedLifecycleEdgeOutcome.PAGE_STILL_VISIBLE -> HighPerformanceDiagnostics.record(
                    type = "experimental_cdp_edge_skipped",
                    result = "ok",
                    originOrUrl = candidate.origin,
                    sessionId = candidate.sessionId,
                    reason = "page_still_visible"
                )
                RestrictedLifecycleEdgeOutcome.SESSION_CHANGED -> HighPerformanceDiagnostics.record(
                    type = "experimental_cdp_edge_skipped",
                    result = "stale",
                    originOrUrl = candidate.origin,
                    sessionId = candidate.sessionId,
                    reason = "session_changed"
                )
            }
        } catch (failure: Throwable) {
            val cancelled = generation.get() != scheduledGeneration ||
                failure is InterruptedException ||
                failure.message?.startsWith("cancelled_") == true
            HighPerformanceDiagnostics.record(
                type = if (cancelled) "experimental_cdp_edge_cancelled" else "experimental_cdp_edge_failed",
                result = if (cancelled) "cancelled" else "degraded",
                originOrUrl = candidate.origin,
                sessionId = candidate.sessionId,
                reason = safeFailureReason(failure)
            )
        } finally {
            restoreDebugging(client, candidate, lease, openedAt)
        }
    }

    private fun restoreDebugging(
        client: RestrictedDevToolsClient,
        candidate: HighPerformanceContinuityCandidate,
        lease: WebViewDebuggingLease,
        openedAt: Long
    ) {
        val release = runOnMainAndWait {
            if (activeLeaseId == lease.id) {
                leaseTimeout?.let(mainHandler::removeCallbacks)
                leaseTimeout = null
                leaseForcedClose?.let(mainHandler::removeCallbacks)
                leaseForcedClose = null
                activeLeaseId = null
            }
            WebViewDebuggingGate.releaseTemporary(lease.id).getOrThrow()
        }.getOrElse { failure ->
            HighPerformanceDiagnostics.record(
                type = "experimental_cdp_debugging_restore_failed",
                result = "failed",
                originOrUrl = candidate.origin,
                sessionId = candidate.sessionId,
                reason = safeFailureReason(failure)
            )
            clearPendingAfterEdge()
            return
        }
        if (!release.released) {
            HighPerformanceDiagnostics.record(
                type = "experimental_cdp_debugging_restore_superseded",
                result = "ok",
                originOrUrl = candidate.origin,
                sessionId = candidate.sessionId,
                reason = "lease_already_released"
            )
        } else {
            HighPerformanceDiagnostics.record(
                type = "experimental_cdp_debugging_restored",
                result = "ok",
                originOrUrl = candidate.origin,
                sessionId = candidate.sessionId,
                reason = if (release.persistentDebuggingEnabled) {
                    "chrome_inspect_enabled"
                } else if (lease.temporarilyEnabled) {
                    "temporary_exposure_${System.currentTimeMillis() - openedAt}ms"
                } else {
                    "chrome_inspect_disabled_during_lease"
                }
            )
        }
        if (!release.debuggingEnabled) {
            val closed = client.waitUntilEndpointClosed(DEBUG_SOCKET_CLOSE_TIMEOUT_MS)
            HighPerformanceDiagnostics.record(
                type = if (closed) {
                    "experimental_cdp_debug_socket_closed"
                } else {
                    "experimental_cdp_debug_socket_open"
                },
                result = if (closed) "ok" else "failed",
                originOrUrl = candidate.origin,
                sessionId = candidate.sessionId,
                reason = if (lease.temporarilyEnabled) {
                    "exposure_${System.currentTimeMillis() - openedAt}ms"
                } else {
                    "preference_disabled_after_${System.currentTimeMillis() - openedAt}ms"
                }
            )
        }
        clearPendingAfterEdge()
    }

    private fun clearPendingAfterEdge() {
        mainHandler.post {
            if (activeLeaseId == null) pending = null
        }
    }

    private fun <T> runOnMainAndWait(block: () -> T): Result<T> {
        val completion = CountDownLatch(1)
        val result = AtomicReference<Result<T>?>(null)
        val action = Runnable {
            result.set(runCatching(block))
            completion.countDown()
        }
        mainHandler.post(action)
        if (!completion.await(MAIN_THREAD_TOGGLE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            mainHandler.removeCallbacks(action)
            return Result.failure(TimeoutException("main_thread_timeout"))
        }
        return result.get() ?: Result.failure(IllegalStateException("main_thread_result_missing"))
    }

    private fun recordFailure(type: String, failure: Throwable, reason: String) {
        HighPerformanceDiagnostics.record(
            type = type,
            result = "failed",
            reason = "${safeFailureReason(failure)}_$reason"
        )
    }

    private fun safeFailureReason(failure: Throwable): String {
        return failure.message
            ?.takeIf { it.matches(Regex("[A-Za-z0-9_:-]{1,80}")) }
            ?: failure.javaClass.simpleName.take(80)
    }

    private fun ensureMainThread() {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "Experimental CDP continuity must be controlled on the main thread"
        }
    }

    private const val START_DELAY_MS = 600L
    private const val RETRY_ACTIVE_LEASE_DELAY_MS = 250L
    private const val TARGET_DISCOVERY_TIMEOUT_MS = 2_500L
    private const val HIDDEN_CONFIRMATION_TIMEOUT_MS = 2_500L
    private const val EDGE_DELAY_MS = 500L
    private const val MAIN_THREAD_TOGGLE_TIMEOUT_MS = 2_000L
    private const val DEBUG_SOCKET_CLOSE_TIMEOUT_MS = 2_000L
    private const val MAX_DEBUGGING_LEASE_MS = 8_000L
    private const val DEBUGGING_FORCE_CLOSE_GRACE_MS = 5_000L
}
