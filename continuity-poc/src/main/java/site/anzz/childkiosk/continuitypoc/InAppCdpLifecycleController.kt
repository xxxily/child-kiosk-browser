package site.anzz.childkiosk.continuitypoc

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.webkit.WebView
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Experimental same-UID DevTools controller. It is intentionally isolated from the production app. */
internal object InAppCdpLifecycleController {
    private val executor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "continuity-in-app-cdp").apply { isDaemon = true }
    }
    private val mainHandler = Handler(Looper.getMainLooper())
    private val generation = AtomicLong(0L)
    @Volatile private var pending: Future<*>? = null

    fun enableDebugging(context: Context): Boolean {
        return runCatching {
            WebView.setWebContentsDebuggingEnabled(true)
        }.fold(
            onSuccess = {
                ProbeLog.append(
                    context,
                    "temporary_webview_debugging_enabled",
                    mapOf("processPid" to Process.myPid())
                )
                true
            },
            onFailure = { failure ->
                ProbeLog.append(
                    context,
                    "temporary_webview_debugging_failed",
                    mapOf(
                        "enabled" to true,
                        "error" to failure.javaClass.simpleName,
                        "message" to failure.message.orEmpty().take(300)
                    )
                )
                false
            }
        )
    }

    fun disableDebugging(context: Context, reason: String) {
        setDebuggingDisabledOnMainThread(context.applicationContext, reason, await = false)
    }

    fun schedule(context: Context, disableDebuggingAfterEdge: Boolean) {
        cancel()
        val scheduledGeneration = generation.get()
        val appContext = context.applicationContext
        val debuggingOpenedAtMs = System.currentTimeMillis()
        ProbeLog.append(
            appContext,
            "in_app_cdp_edge_scheduled",
            mapOf(
                "delayMs" to START_DELAY_MS,
                "processPid" to Process.myPid(),
                "disableDebuggingAfterEdge" to disableDebuggingAfterEdge
            )
        )
        pending = executor.schedule(
            task@{
                if (generation.get() != scheduledGeneration) return@task
                runEdge(
                    context = appContext,
                    scheduledGeneration = scheduledGeneration,
                    disableDebuggingAfterEdge = disableDebuggingAfterEdge,
                    debuggingOpenedAtMs = debuggingOpenedAtMs
                )
            },
            START_DELAY_MS,
            TimeUnit.MILLISECONDS
        )
    }

    fun cancel() {
        generation.incrementAndGet()
        pending?.cancel(true)
        pending = null
    }

    private fun runEdge(
        context: Context,
        scheduledGeneration: Long,
        disableDebuggingAfterEdge: Boolean,
        debuggingOpenedAtMs: Long
    ) {
        val startedAt = System.currentTimeMillis()
        ProbeLog.append(context, "in_app_cdp_edge_started")
        val client = LocalDevToolsClient(
            socketName = "webview_devtools_remote_${Process.myPid()}",
            targetUrlHint = TARGET_URL_HINT
        )
        try {
            runCatching {
            val target = client.discoverTarget(TARGET_DISCOVERY_TIMEOUT_MS) {
                generation.get() == scheduledGeneration
            }
            check(generation.get() == scheduledGeneration) { "cancelled_before_connect" }
            val outcome = client.sendLifecycleEdgeWhenHidden(
                target = target,
                edgeDelayMs = EDGE_DELAY_MS,
                hiddenConfirmationTimeoutMs = HIDDEN_CONFIRMATION_TIMEOUT_MS,
                shouldContinue = { generation.get() == scheduledGeneration }
            )
            when (outcome) {
                LifecycleEdgeOutcome.SENT -> ProbeLog.append(
                    context,
                    "in_app_cdp_edge_succeeded",
                    mapOf(
                        "durationMs" to (System.currentTimeMillis() - startedAt),
                        "targetId" to target.id,
                        "targetUrl" to target.url
                    )
                )
                LifecycleEdgeOutcome.PAGE_STILL_VISIBLE -> ProbeLog.append(
                    context,
                    "in_app_cdp_edge_skipped",
                    mapOf(
                        "durationMs" to (System.currentTimeMillis() - startedAt),
                        "reason" to "page_still_visible",
                        "targetId" to target.id,
                        "targetUrl" to target.url
                    )
                )
            }
            }.onFailure { failure ->
                ProbeLog.append(
                    context,
                    "in_app_cdp_edge_failed",
                    mapOf(
                        "durationMs" to (System.currentTimeMillis() - startedAt),
                        "error" to failure.javaClass.simpleName,
                        "message" to failure.message.orEmpty().take(300)
                    )
                )
            }
        } finally {
            if (disableDebuggingAfterEdge) {
                val disabled = setDebuggingDisabledOnMainThread(
                    context = context,
                    reason = "cdp_edge_finished",
                    await = true
                )
                val socketClosed = disabled && client.waitUntilEndpointClosed(
                    timeoutMs = DEBUG_SOCKET_CLOSE_TIMEOUT_MS
                )
                ProbeLog.append(
                    context,
                    if (socketClosed) {
                        "temporary_webview_debug_socket_closed"
                    } else {
                        "temporary_webview_debug_socket_open"
                    },
                    mapOf(
                        "exposureMs" to (System.currentTimeMillis() - debuggingOpenedAtMs),
                        "processPid" to Process.myPid()
                    )
                )
            }
        }
    }

    private fun setDebuggingDisabledOnMainThread(
        context: Context,
        reason: String,
        await: Boolean
    ): Boolean {
        val result = AtomicReference<Result<Unit>?>(null)
        val completion = CountDownLatch(1)
        val action = Runnable {
            result.set(runCatching { WebView.setWebContentsDebuggingEnabled(false) })
            val failure = result.get()?.exceptionOrNull()
            ProbeLog.append(
                context,
                if (failure == null) {
                    "temporary_webview_debugging_disabled"
                } else {
                    "temporary_webview_debugging_failed"
                },
                buildMap {
                    put("enabled", false)
                    put("reason", reason)
                    if (failure != null) {
                        put("error", failure.javaClass.simpleName)
                        put("message", failure.message.orEmpty().take(300))
                    }
                }
            )
            completion.countDown()
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action.run()
        } else {
            mainHandler.post(action)
        }
        if (await && !completion.await(DEBUGGING_TOGGLE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            ProbeLog.append(
                context,
                "temporary_webview_debugging_failed",
                mapOf("enabled" to false, "reason" to "main_thread_timeout")
            )
            return false
        }
        return !await || result.get()?.isSuccess == true
    }

    private const val TARGET_URL_HINT = "continuity_probe.html"
    private const val START_DELAY_MS = 1_000L
    private const val EDGE_DELAY_MS = 500L
    private const val HIDDEN_CONFIRMATION_TIMEOUT_MS = 3_000L
    private const val TARGET_DISCOVERY_TIMEOUT_MS = 15_000L
    private const val DEBUGGING_TOGGLE_TIMEOUT_MS = 3_000L
    private const val DEBUG_SOCKET_CLOSE_TIMEOUT_MS = 3_000L
}
