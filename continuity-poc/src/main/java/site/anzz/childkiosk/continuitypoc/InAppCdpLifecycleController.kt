package site.anzz.childkiosk.continuitypoc

import android.content.Context
import android.os.Process
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Experimental same-UID DevTools controller. It is intentionally isolated from the production app. */
internal object InAppCdpLifecycleController {
    private val executor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "continuity-in-app-cdp").apply { isDaemon = true }
    }
    private val generation = AtomicLong(0L)
    @Volatile private var pending: Future<*>? = null

    fun schedule(context: Context) {
        cancel()
        val scheduledGeneration = generation.get()
        val appContext = context.applicationContext
        ProbeLog.append(
            appContext,
            "in_app_cdp_edge_scheduled",
            mapOf("delayMs" to START_DELAY_MS, "processPid" to Process.myPid())
        )
        pending = executor.schedule(
            task@{
                if (generation.get() != scheduledGeneration) return@task
                runEdge(appContext, scheduledGeneration)
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

    private fun runEdge(context: Context, scheduledGeneration: Long) {
        val startedAt = System.currentTimeMillis()
        ProbeLog.append(context, "in_app_cdp_edge_started")
        runCatching {
            val client = LocalDevToolsClient(
                socketName = "webview_devtools_remote_${Process.myPid()}",
                targetUrlHint = TARGET_URL_HINT
            )
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
    }

    private const val TARGET_URL_HINT = "continuity_probe.html"
    private const val START_DELAY_MS = 1_000L
    private const val EDGE_DELAY_MS = 500L
    private const val HIDDEN_CONFIRMATION_TIMEOUT_MS = 3_000L
    private const val TARGET_DISCOVERY_TIMEOUT_MS = 15_000L
}
