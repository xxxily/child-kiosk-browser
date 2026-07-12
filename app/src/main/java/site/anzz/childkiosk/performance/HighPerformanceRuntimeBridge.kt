package site.anzz.childkiosk.performance

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import site.anzz.childkiosk.util.ProcessUtils

/** Receives version-only runtime signals and applies the validated AtomicFile snapshot in :webview. */
internal object HighPerformanceRuntimeBridge {
    private val registered = AtomicBoolean(false)
    private val ioExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "high-performance-runtime-reader").apply { isDaemon = true }
    }
    private val mainHandler = Handler(Looper.getMainLooper())
    private var appContext: Context? = null
    private var snapshotListener: SnapshotListener? = null

    fun interface SnapshotListener {
        fun onSnapshotApplied(snapshot: HighPerformanceRuntimeSnapshot)
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_HIGH_PERFORMANCE_CONFIG_UPDATED -> readAndApply(
                    context = context,
                    requestedVersion = intent.getLongExtra(EXTRA_HIGH_PERFORMANCE_CONFIG_VERSION, 0L),
                    source = "config_broadcast"
                )

                ACTION_HIGH_PERFORMANCE_STOP_ALL -> {
                    val configVersion = intent.getLongExtra(
                        EXTRA_HIGH_PERFORMANCE_CONFIG_VERSION,
                        0L
                    ).coerceAtLeast(0L)
                    val reason = intent.getStringExtra(EXTRA_HIGH_PERFORMANCE_STOP_REASON)
                        .orEmpty()
                        .ifBlank { "admin_stop" }
                    mainHandler.post {
                        HighPerformanceSessionController.stopAllFromPublishedSignal(
                            configVersion = configVersion,
                            source = reason,
                        )
                    }
                }

                ACTION_HIGH_PERFORMANCE_CLEAR_DIAGNOSTICS -> mainHandler.post {
                    HighPerformanceSessionController.clearDiagnostics("admin_clear")
                }
            }
        }
    }

    fun register(context: Context) {
        val applicationContext = context.applicationContext
        if (!ProcessUtils.isWebViewProcess(applicationContext)) return
        appContext = applicationContext
        if (registered.compareAndSet(false, true)) {
            val filter = IntentFilter().apply {
                addAction(ACTION_HIGH_PERFORMANCE_CONFIG_UPDATED)
                addAction(ACTION_HIGH_PERFORMANCE_STOP_ALL)
                addAction(ACTION_HIGH_PERFORMANCE_CLEAR_DIAGNOSTICS)
            }
            ContextCompat.registerReceiver(
                applicationContext,
                receiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
        }
        readAndApply(applicationContext, requestedVersion = 0L, source = "process_start")
    }

    fun unregister() {
        val context = appContext ?: return
        if (registered.compareAndSet(true, false)) {
            runCatching { context.unregisterReceiver(receiver) }
        }
        appContext = null
        clearSnapshotAppliedListener()
    }

    /** Keeps Activity-owned launch config aligned with snapshots received after process start. */
    fun setSnapshotAppliedListener(
        owner: Any,
        listener: (HighPerformanceRuntimeSnapshot) -> Unit
    ) {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "High-performance snapshot listeners must be changed on the main thread"
        }
        snapshotListener = OwnedSnapshotListener(owner, listener)
    }

    fun clearSnapshotAppliedListener(owner: Any? = null) {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "High-performance snapshot listeners must be changed on the main thread"
        }
        val current = snapshotListener
        if (owner != null && (current as? OwnedSnapshotListener)?.owner !== owner) return
        snapshotListener = null
    }

    private fun readAndApply(context: Context, requestedVersion: Long, source: String) {
        val safeMinimum = requestedVersion.coerceAtLeast(0L)
        ioExecutor.execute {
            val snapshot = HighPerformanceRuntimePublisher.readPublishedSnapshotFromDisk(
                context = context.applicationContext,
                minimumConfigVersion = safeMinimum
            )
            mainHandler.post {
                if (HighPerformanceSessionController.applySnapshot(snapshot, source)) {
                    snapshotListener?.onSnapshotApplied(snapshot)
                }
            }
        }
    }

    private class OwnedSnapshotListener(
        val owner: Any,
        private val listener: (HighPerformanceRuntimeSnapshot) -> Unit
    ) : SnapshotListener {
        override fun onSnapshotApplied(snapshot: HighPerformanceRuntimeSnapshot) = listener(snapshot)
    }
}
