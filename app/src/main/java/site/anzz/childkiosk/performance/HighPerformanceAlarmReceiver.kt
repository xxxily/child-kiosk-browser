package site.anzz.childkiosk.performance

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Receives AlarmManager wake-ups to renew the high-performance WakeLock and trigger a health check.
 *
 * This receiver is critical for surviving Doze/suspend on aggressive OEM devices (e.g. OnePlus)
 * where Handler.postDelayed callbacks stop firing despite a held PARTIAL_WAKE_LOCK.
 *
 * The system holds a WakeLock for the duration of onReceive(), ensuring the CPU stays awake
 * long enough to renew the main WakeLock and run the health check.
 *
 * Registered in the :webview process to match the FGS and SessionController.
 */
class HighPerformanceAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_RENEW_WAKE_LOCK) return

        Log.d(TAG, "Alarm received — triggering WakeLock renewal and health check")

        // 1. Renew the WakeLock via the static callback in WakeLockController.
        //    This runs on the main thread (manifest receivers default to main thread).
        HighPerformanceWakeLockController.triggerAlarmRenewal(reason = "alarm_renewal")

        // 2. Trigger a SessionController health check to sync system resources,
        //    prune lost WebViews, and reschedule the Handler-based heartbeat.
        HighPerformanceSessionController.triggerAlarmHealthCheck()
    }

    companion object {
        private const val TAG = "HighPerfAlarmReceiver"
        const val ACTION_RENEW_WAKE_LOCK = "site.anzz.childkiosk.performance.action.RENEW_WAKE_LOCK_ALARM"
    }
}
