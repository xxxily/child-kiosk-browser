package site.anzz.childkiosk.continuitypoc

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager

class ContinuityForegroundService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    private val heartbeat = object : Runnable {
        override fun run() {
            val powerManager = getSystemService(PowerManager::class.java)
            ProbeLog.append(
                this@ContinuityForegroundService,
                "native_heartbeat",
                mapOf(
                    "interactive" to powerManager.isInteractive,
                    "wakeLockHeld" to (wakeLock?.isHeld == true),
                    "wifiLockHeld" to (wifiLock?.isHeld == true)
                )
            )
            handler.postDelayed(this, HEARTBEAT_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        acquireLocks()
        handler.post(heartbeat)
        ProbeLog.append(this, "service_created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ProbeLog.append(
            this,
            "service_started",
            mapOf("startId" to startId, "flags" to flags)
        )
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (wakeLock?.isHeld == true) wakeLock?.release()
        if (wifiLock?.isHeld == true) wifiLock?.release()
        ProbeLog.append(this, "service_destroyed")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun acquireLocks() {
        val powerManager = getSystemService(PowerManager::class.java)
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "$packageName:continuity-poc"
        ).apply {
            setReferenceCounted(false)
            acquire(MAX_LOCK_DURATION_MS)
        }
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        @Suppress("DEPRECATION")
        wifiLock = wifiManager.createWifiLock(
            WifiManager.WIFI_MODE_FULL_HIGH_PERF,
            "$packageName:continuity-poc-wifi"
        ).apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "WebView continuity research",
            NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification() = android.app.Notification.Builder(this, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.stat_notify_sync)
        .setContentTitle("WebView continuity PoC")
        .setContentText("Recording native, main-thread and Worker heartbeats")
        .setOngoing(true)
        .setContentIntent(
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, ContinuityProbeActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        )
        .build()

    companion object {
        private const val CHANNEL_ID = "continuity_poc"
        private const val NOTIFICATION_ID = 1401
        private const val HEARTBEAT_INTERVAL_MS = 5_000L
        private const val MAX_LOCK_DURATION_MS = 2L * 60L * 60L * 1_000L
    }
}
