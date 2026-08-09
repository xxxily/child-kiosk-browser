package site.anzz.childkiosk.performance

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import site.anzz.childkiosk.R
import site.anzz.childkiosk.WebViewActivityLauncher
import site.anzz.childkiosk.WebViewHostRuntime

/**
 * Foreground-resource owner for already-existing trusted WebView sessions.
 *
 * This service intentionally never creates a WebView. The process-local session controller is the
 * authority for whether resource protection is still required.
 */
internal class HighPerformanceForegroundService : Service() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var wakeLockController: HighPerformanceWakeLockController
    private var foregroundEstablished = false
    private var stoppingExpectedly = false
    private var controllerStopReported = false
    private var firstCommandHandled = false

    private val refreshRunnable = object : Runnable {
        override fun run() {
            synchronizeResources(source = "service_health_check")
            if (!stoppingExpectedly) mainHandler.postDelayed(this, SERVICE_HEALTH_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        HighPerformanceSessionController.initialize(this)
        createNotificationChannel()
        wakeLockController = HighPerformanceWakeLockController(this) { snapshot ->
            HighPerformanceSessionController.onWakeLockStateChanged(snapshot)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!firstCommandHandled) {
            firstCommandHandled = true
            val initialRequest = HighPerformanceSessionController.currentResourceRequest()
            if (initialRequest.eligibleSessionCount > 0) {
                val foregroundResult = establishForeground(createNotification(initialRequest))
                if (foregroundResult.isFailure) {
                    val failure = foregroundResult.exceptionOrNull()
                    HighPerformanceSessionController.onForegroundServiceStartFailed(
                        failure?.javaClass?.simpleName ?: "start_foreground_failed"
                    )
                    controllerStopReported = true
                    stopServiceResources("start_foreground_failed")
                    return START_NOT_STICKY
                }
                foregroundEstablished = true
            }
        }
        when (intent?.action) {
            ACTION_STOP_ALL -> {
                HighPerformanceSessionController.stopAll(
                    source = intent.getStringExtra(EXTRA_SOURCE).orEmpty().ifBlank { "notification_action" },
                    suppressCurrentWebViews = true
                )
                stopServiceResources("notification_action")
                return START_NOT_STICKY
            }

            ACTION_SYNC, null -> synchronizeResources(
                source = intent?.getStringExtra(EXTRA_SOURCE).orEmpty().ifBlank { "session_sync" }
            )

            else -> synchronizeResources(source = "unknown_action")
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        HighPerformanceDiagnostics.record(
            type = "fgs_task_removed",
            reason = "recent_task_removed"
        )
        synchronizeResources(source = "task_removed")
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(refreshRunnable)
        if (::wakeLockController.isInitialized) {
            wakeLockController.destroy("service_destroy")
        }
        if (!controllerStopReported) {
            HighPerformanceSessionController.onForegroundServiceStopped(
                expected = stoppingExpectedly,
                reason = if (stoppingExpectedly) "service_stop_requested" else "service_destroyed_unexpectedly"
            )
            controllerStopReported = true
        }
        super.onDestroy()
    }

    private fun synchronizeResources(source: String) {
        if (stoppingExpectedly) return
        val request = HighPerformanceSessionController.currentResourceRequest()
        if (request.eligibleSessionCount <= 0) {
            stopServiceResources("no_eligible_sessions")
            return
        }
        if (!HighPerformanceProcessState.notificationPermissionGranted(this) ||
            !HighPerformanceProcessState.collect(this).notificationsVisible
        ) {
            HighPerformanceDiagnostics.record(
                type = "fgs_start_failed",
                result = "notification_unavailable",
                reason = source
            )
            HighPerformanceSessionController.onForegroundServiceStartFailed("notification_unavailable")
            controllerStopReported = true
            stopServiceResources("notification_unavailable")
            return
        }

        val notification = createNotification(request)
        val foregroundResult = if (!foregroundEstablished) {
            establishForeground(notification)
        } else {
            runCatching {
                getSystemService(NotificationManager::class.java)
                    .notify(NOTIFICATION_ID, notification)
            }
        }
        foregroundResult.onFailure { failure ->
            HighPerformanceSessionController.onForegroundServiceStartFailed(failure.javaClass.simpleName)
            controllerStopReported = true
            stopServiceResources("start_foreground_failed")
            return
        }

        foregroundEstablished = true
        HighPerformanceSessionController.onForegroundServiceRunning()
        wakeLockController.setRequired(
            required = true,
            requestedLeaseMs = request.wakeLockLeaseMs,
            reason = source
        )
        mainHandler.removeCallbacks(refreshRunnable)
        mainHandler.postDelayed(refreshRunnable, SERVICE_HEALTH_INTERVAL_MS)
    }

    @SuppressLint("ForegroundServiceType")
    private fun establishForeground(notification: Notification): Result<Unit> {
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                    } else {
                        0
                    }
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        }
    }

    private fun stopServiceResources(reason: String) {
        if (stoppingExpectedly) return
        stoppingExpectedly = true
        mainHandler.removeCallbacks(refreshRunnable)
        if (::wakeLockController.isInitialized) {
            wakeLockController.release(reason)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        if (!controllerStopReported) {
            HighPerformanceSessionController.onForegroundServiceStopped(
                expected = true,
                reason = reason
            )
            controllerStopReported = true
        }
        stopSelf()
    }

    private fun createNotification(request: HighPerformanceResourceRequest): Notification {
        val contentIntent = WebViewHostRuntime.currentHostMode()?.let { hostMode ->
            PendingIntent.getActivity(
                this,
                REQUEST_CODE_OPEN_BROWSER,
                WebViewActivityLauncher.createResumeIntent(this, hostMode),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
        val stopIntent = PendingIntent.getService(
            this,
            REQUEST_CODE_STOP,
            Intent(this, HighPerformanceForegroundService::class.java).apply {
                action = ACTION_STOP_ALL
                putExtra(EXTRA_SOURCE, "notification_action")
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val countText = "${request.siteCount} 个网站运行中"
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_browser_secure_24)
            .setContentTitle("网页持续运行")
            .setContentText(countText)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$countText；息屏后仍可能联网"))
            .apply { contentIntent?.let(::setContentIntent) }
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setWhen(request.oldestSessionStartedAt ?: System.currentTimeMillis())
            .setUsesChronometer(true)
            .addAction(0, "停止", stopIntent)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            "高性能网页运行",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "显示授权网页的运行状态"
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val NOTIFICATION_CHANNEL_ID = "high_performance_web_runtime"
        private const val NOTIFICATION_ID = 4107
        private const val REQUEST_CODE_OPEN_BROWSER = 4108
        private const val REQUEST_CODE_STOP = 4109
        private const val SERVICE_HEALTH_INTERVAL_MS = 30_000L
        private const val EXTRA_SOURCE = "source"
        private const val ACTION_SYNC = "site.anzz.childkiosk.performance.action.SYNC_SERVICE"
        private const val ACTION_STOP_ALL = "site.anzz.childkiosk.performance.action.STOP_ALL"

        fun requestSync(
            context: Context,
            serviceAlreadyRunning: Boolean,
            source: String
        ): Result<Unit> {
            val intent = Intent(context, HighPerformanceForegroundService::class.java).apply {
                action = ACTION_SYNC
                putExtra(EXTRA_SOURCE, source)
            }
            return runCatching {
                if (serviceAlreadyRunning) {
                    context.startService(intent)
                } else {
                    ContextCompat.startForegroundService(context, intent)
                }
            }.map { Unit }
        }
    }
}
