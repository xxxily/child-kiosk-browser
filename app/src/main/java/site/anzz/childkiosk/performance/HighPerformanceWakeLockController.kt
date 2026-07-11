package site.anzz.childkiosk.performance

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.PowerManager

data class HighPerformanceWakeLockSnapshot(
    val state: HighPerformanceWakeLockState,
    val required: Boolean,
    val acquiredAt: Long?,
    val lastRenewedAt: Long?,
    val lastReleasedAt: Long?,
    val leaseMs: Long,
    val error: String?
)

/**
 * Owns the single non-reference-counted PARTIAL_WAKE_LOCK used by the foreground service.
 * Every acquisition has a finite lease; a health tick renews it only while a valid session still
 * requires system-resource protection.
 */
internal class HighPerformanceWakeLockController(
    context: Context,
    private val onStateChanged: (HighPerformanceWakeLockSnapshot) -> Unit
) {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val powerManager = appContext.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val wakeLock: PowerManager.WakeLock by lazy {
        powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "${appContext.packageName}:HighPerformanceWebSession"
        ).apply { setReferenceCounted(false) }
    }

    private var required = false
    private var leaseMs = DEFAULT_LEASE_MS
    private var acquiredAt: Long? = null
    private var lastRenewedAt: Long? = null
    private var lastReleasedAt: Long? = null
    private var error: String? = null
    private var consecutiveFailures = 0
    private var renewalScheduled = false

    private val renewRunnable = Runnable {
        renewalScheduled = false
        if (required) acquireLease(renewal = true, reason = "health_check")
    }

    fun setRequired(required: Boolean, requestedLeaseMs: Long, reason: String) {
        runOnMain {
            this.required = required
            leaseMs = requestedLeaseMs.coerceIn(MIN_LEASE_MS, MAX_LEASE_MS)
            if (required) {
                if (!isHeld()) {
                    if (!renewalScheduled && consecutiveFailures <= RETRY_DELAYS_MS.size) {
                        acquireLease(renewal = false, reason = reason)
                    } else {
                        notifyState()
                    }
                } else {
                    scheduleRenewal()
                    notifyState()
                }
            } else {
                release(reason)
            }
        }
    }

    fun release(reason: String) {
        runOnMain {
            required = false
            mainHandler.removeCallbacks(renewRunnable)
            renewalScheduled = false
            if (isHeld()) {
                runCatching { wakeLock.release() }
                    .onSuccess {
                        lastReleasedAt = System.currentTimeMillis()
                        acquiredAt = null
                        lastRenewedAt = null
                        HighPerformanceDiagnostics.record(
                            type = "wake_lock_released",
                            reason = reason
                        )
                    }
                    .onFailure { failure ->
                        error = failure.javaClass.simpleName
                        HighPerformanceDiagnostics.record(
                            type = "wake_lock_release_failed",
                            result = "failed",
                            reason = failure.javaClass.simpleName
                        )
                    }
            }
            consecutiveFailures = 0
            notifyState()
        }
    }

    fun destroy(reason: String) {
        release(reason)
    }

    fun snapshot(): HighPerformanceWakeLockSnapshot = HighPerformanceWakeLockSnapshot(
        state = when {
            isHeld() -> HighPerformanceWakeLockState.HELD
            error != null && required -> HighPerformanceWakeLockState.FAILED
            else -> HighPerformanceWakeLockState.NOT_HELD
        },
        required = required,
        acquiredAt = acquiredAt,
        lastRenewedAt = lastRenewedAt,
        lastReleasedAt = lastReleasedAt,
        leaseMs = leaseMs,
        error = error
    )

    private fun acquireLease(renewal: Boolean, reason: String) {
        mainHandler.removeCallbacks(renewRunnable)
        renewalScheduled = false
        runCatching {
            wakeLock.acquire(leaseMs)
        }.onSuccess {
            val now = System.currentTimeMillis()
            if (acquiredAt == null) acquiredAt = now
            lastRenewedAt = now
            error = null
            consecutiveFailures = 0
            HighPerformanceDiagnostics.record(
                type = if (renewal) "wake_lock_renewed" else "wake_lock_acquired",
                reason = reason
            )
            scheduleRenewal()
        }.onFailure { failure ->
            error = failure.javaClass.simpleName
            consecutiveFailures += 1
            HighPerformanceDiagnostics.record(
                type = "wake_lock_acquire_failed",
                result = "failed",
                reason = failure.javaClass.simpleName
            )
            if (required && consecutiveFailures <= RETRY_DELAYS_MS.size) {
                mainHandler.postDelayed(
                    renewRunnable,
                    RETRY_DELAYS_MS[consecutiveFailures - 1]
                )
                renewalScheduled = true
            }
        }
        notifyState()
    }

    private fun scheduleRenewal() {
        if (required && !renewalScheduled) {
            mainHandler.postDelayed(renewRunnable, (leaseMs / 2L).coerceAtLeast(MIN_RENEW_INTERVAL_MS))
            renewalScheduled = true
        }
    }

    private fun isHeld(): Boolean = runCatching { wakeLock.isHeld }.getOrDefault(false)

    private fun notifyState() {
        onStateChanged(snapshot())
    }

    private inline fun runOnMain(crossinline action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action()
        } else {
            mainHandler.post { action() }
        }
    }

    companion object {
        private const val DEFAULT_LEASE_MS = 10 * 60_000L
        private const val MIN_LEASE_MS = 60_000L
        private const val MAX_LEASE_MS = 15 * 60_000L
        private const val MIN_RENEW_INTERVAL_MS = 30_000L
        private val RETRY_DELAYS_MS = longArrayOf(10_000L, 30_000L, 120_000L)
    }
}
