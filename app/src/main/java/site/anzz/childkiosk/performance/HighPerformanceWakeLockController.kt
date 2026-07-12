package site.anzz.childkiosk.performance

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log

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
 *
 * Renewal is scheduled via TWO independent mechanisms to survive OEM aggressive battery management:
 * 1. Handler.postDelayed – fast path when the CPU is already awake.
 * 2. AlarmManager.setAndAllowWhileIdle – guaranteed wake from Doze/suspend even on aggressive OEMs
 *    where Handler callbacks stop firing despite a held WakeLock.
 */
internal class HighPerformanceWakeLockController(
    context: Context,
    private val onStateChanged: (HighPerformanceWakeLockSnapshot) -> Unit
) {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val powerManager = appContext.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val alarmManager = appContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    private val wakeLock: PowerManager.WakeLock by lazy {
        powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "${appContext.packageName}:HighPerformanceWebSession"
        ).apply { setReferenceCounted(false) }
    }

    private val renewAlarmPendingIntent by lazy {
        PendingIntent.getBroadcast(
            appContext,
            ALARM_REQUEST_CODE,
            Intent(appContext, HighPerformanceAlarmReceiver::class.java).apply {
                action = HighPerformanceAlarmReceiver.ACTION_RENEW_WAKE_LOCK
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private var required = false
    private var leaseMs = DEFAULT_LEASE_MS
    private var acquiredAt: Long? = null
    private var lastRenewedAt: Long? = null
    private var lastReleasedAt: Long? = null
    private var error: String? = null
    private var consecutiveFailures = 0
    private var renewalScheduled = false
    private var lastAlarmRenewalAt: Long? = null

    init {
        // Register the static callback so the alarm receiver can trigger renewal.
        renewalCallback = { reason -> renewFromAlarm(reason) }
    }

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
            cancelRenewalAlarm()
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
        renewalCallback = null
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
        cancelRenewalAlarm()
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
            val intervalMs = (leaseMs / RENEWAL_INTERVAL_DIVISOR).coerceAtLeast(MIN_RENEW_INTERVAL_MS)
            // Fast path: Handler-based renewal fires when the CPU is already awake.
            mainHandler.postDelayed(renewRunnable, intervalMs)
            // Guaranteed path: AlarmManager wakes the CPU from Doze/suspend even on aggressive OEMs.
            scheduleRenewalAlarm(intervalMs)
            renewalScheduled = true
        }
    }

    private fun scheduleRenewalAlarm(intervalMs: Long) {
        runCatching {
            val triggerAt = SystemClock.elapsedRealtime() + intervalMs
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                triggerAt,
                renewAlarmPendingIntent
            )
        }.onFailure { failure ->
            Log.w("HighPerformanceWakeLock", "Failed to schedule renewal alarm: ${failure.javaClass.simpleName}")
            HighPerformanceDiagnostics.record(
                type = "wake_lock_alarm_schedule_failed",
                result = "failed",
                reason = failure.javaClass.simpleName
            )
        }
    }

    private fun cancelRenewalAlarm() {
        runCatching {
            alarmManager.cancel(renewAlarmPendingIntent)
        }
    }

    /**
     * Called by [HighPerformanceAlarmReceiver] when the AlarmManager fires.
     * Renews the WakeLock if still required, ensuring CPU stays awake during Doze/suspend.
     */
    private fun renewFromAlarm(reason: String) {
        runOnMain {
            lastAlarmRenewalAt = System.currentTimeMillis()
            if (required) {
                Log.d("HighPerformanceWakeLock", "Alarm-triggered renewal: $reason")
                acquireLease(renewal = true, reason = reason)
            } else {
                // WakeLock no longer required; ensure no stray alarm remains.
                cancelRenewalAlarm()
            }
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
        private const val RENEWAL_INTERVAL_DIVISOR = 3L
        private const val ALARM_REQUEST_CODE = 4110

        private val RETRY_DELAYS_MS = longArrayOf(10_000L, 30_000L, 120_000L)

        @Volatile
        private var renewalCallback: ((String) -> Unit)? = null

        /**
         * Called by [HighPerformanceAlarmReceiver] when the AlarmManager fires.
         * If the controller is active, it renews the WakeLock on the main thread.
         */
        internal fun triggerAlarmRenewal(reason: String = "alarm_renewal") {
            renewalCallback?.invoke(reason)
        }
    }
}
