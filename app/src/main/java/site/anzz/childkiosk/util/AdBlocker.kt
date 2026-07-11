package site.anzz.childkiosk.util

import android.content.Context
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import site.anzz.childkiosk.util.filter.FilterAction
import site.anzz.childkiosk.util.filter.FilterDecision
import site.anzz.childkiosk.util.filter.FilterEngine
import site.anzz.childkiosk.util.filter.FilterEvent
import site.anzz.childkiosk.util.filter.FilterRepository
import site.anzz.childkiosk.util.filter.FilterRequestContext
import site.anzz.childkiosk.util.filter.FilterResourceType
import site.anzz.childkiosk.util.filter.FilterRuntimeSnapshot
import site.anzz.childkiosk.util.filter.WebViewFilterEngineHandle
import site.anzz.childkiosk.util.filter.normalizeHost
import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

object AdBlocker {
    private const val MAX_FILTER_EVENTS_PER_SECOND = 20
    internal const val MAX_EVENT_URL_CHARS = 2_048
    internal const val MAX_EVENT_TOP_LEVEL_URL_CHARS = 1_024
    internal const val MAX_EVENT_RULE_CHARS = 1_024
    internal const val MAX_EVENT_METADATA_CHARS = 256
    private const val MAX_EVENT_JSON_BYTES = 48 * 1024
    private val eventWindowStartMs = AtomicLong(0L)
    private val eventWindowCount = AtomicInteger(0)

    fun shouldBlock(
        context: Context,
        request: WebResourceRequest?,
        topLevelUrl: String,
        handle: WebViewFilterEngineHandle
    ): FilterDecision {
        return shouldBlock(
            context = context,
            request = request,
            topLevelUrl = topLevelUrl,
            snapshot = handle.snapshot,
            engine = handle.engine
        )
    }

    fun shouldBlock(
        context: Context,
        request: WebResourceRequest?,
        topLevelUrl: String,
        snapshot: FilterRuntimeSnapshot,
        engine: FilterEngine? = null
    ): FilterDecision {
        val startedAt = System.nanoTime()
        var engineForStats: site.anzz.childkiosk.util.filter.FilterEngine? = null
        var parseNanos = 0L
        var engineNanos = 0L
        var eventNanos = 0L
        var snapshotNanos = 0L
        var requestUrlForStats = ""
        var resourceTypeForStats: FilterResourceType? = null
        var decisionForStats: FilterDecision? = null
        try {
            if (!snapshot.enabled || request?.url == null) return FilterDecision.ALLOW
            val parseStartedAt = System.nanoTime()
            val scheme = request.url.scheme?.lowercase(java.util.Locale.US)
            if (scheme != "http" && scheme != "https") {
                parseNanos += System.nanoTime() - parseStartedAt
                return FilterDecision.ALLOW
            }
            val requestUrl = request.url.toString()
            requestUrlForStats = boundEventText(requestUrl, MAX_EVENT_URL_CHARS)
            val requestUrlLower = requestUrl.lowercase(java.util.Locale.US)
            val requestHost = request.url.host.orEmpty().normalizeHost()
            val topLevelHost = WebViewRuntime.hostOf(topLevelUrl)
            val requestHeaders = request.requestHeaders.orEmpty()
            val resourceType = FilterResourceType.infer(
                url = requestUrl,
                acceptHeader = requestHeaders.headerValue("Accept"),
                isMainFrame = request.isForMainFrame,
                requestHeaders = requestHeaders,
                method = request.method.orEmpty()
            )
            resourceTypeForStats = resourceType
            val requestContext = FilterRequestContext(
                requestUrl = requestUrl,
                topLevelUrl = topLevelUrl,
                resourceType = resourceType,
                isMainFrame = request.isForMainFrame,
                method = request.method.orEmpty(),
                hasGesture = request.hasGesture(),
                requestHostHint = requestHost,
                topLevelHostHint = topLevelHost,
                requestUrlLowerHint = requestUrlLower
            )
            parseNanos += System.nanoTime() - parseStartedAt
            val activeEngine = engine ?: FilterRepository.getCachedEngine(snapshot) ?: return FilterDecision.ALLOW
            engineForStats = activeEngine
            val siteOverride = FilterRepository.siteOverrideFor(snapshot, requestContext.topLevelHost)
            val engineStartedAt = System.nanoTime()
            val decision = activeEngine.decide(requestContext, siteOverride)
            engineNanos += System.nanoTime() - engineStartedAt
            decisionForStats = decision
            if (decision.action != FilterAction.ALLOW) {
                val eventStartedAt = System.nanoTime()
                if (shouldRecordFilterEvent()) {
                    val diagnostics = decision.diagnostics
                    val event = boundEvent(
                        FilterEvent(
                            timestamp = System.currentTimeMillis(),
                            action = decision.action.name,
                            url = requestUrl,
                            topLevelUrl = topLevelUrl,
                            resourceType = requestContext.resourceType.optionName,
                            ruleText = decision.rule?.rawText.orEmpty(),
                            sourceName = decision.rule?.sourceName.orEmpty(),
                            reason = decision.reason,
                            sourceId = decision.rule?.sourceId.orEmpty(),
                            matchType = diagnostics?.ruleMatchType.orEmpty(),
                            indexKey = diagnostics?.ruleIndexKey.orEmpty(),
                            candidateCount = diagnostics?.candidateCount ?: 0,
                            cacheStatus = diagnostics?.cacheStatus.orEmpty()
                        )
                    )
                    if (isWebviewProcess(context)) {
                        sendFilterEventBroadcast(context, event)
                    } else {
                        FilterRepository.recordEvent(context, event)
                    }
                }
                eventNanos += System.nanoTime() - eventStartedAt
            }
            return decision
        } finally {
            engineForStats?.let { engine ->
                val snapshotStartedAt = System.nanoTime()
                FilterRepository.maybeRecordPerfSnapshot(context, snapshot, engine)
                snapshotNanos += System.nanoTime() - snapshotStartedAt
                val diagnostics = decisionForStats?.diagnostics
                engine.recordShouldBlockDuration(
                    totalNanos = System.nanoTime() - startedAt,
                    parseNanos = parseNanos,
                    engineNanos = engineNanos,
                    eventNanos = eventNanos,
                    snapshotNanos = snapshotNanos,
                    resourceType = resourceTypeForStats,
                    action = decisionForStats?.action,
                    url = requestUrlForStats,
                    ruleText = boundEventText(
                        decisionForStats?.rule?.rawText.orEmpty(),
                        MAX_EVENT_RULE_CHARS
                    ),
                    cacheStatus = diagnostics?.cacheStatus.orEmpty(),
                    candidateCount = diagnostics?.candidateCount ?: 0
                )
            }
        }
    }

    private val EMPTY_GIF = byteArrayOf(
        0x47, 0x49, 0x46, 0x38, 0x39, 0x61, 0x01, 0x00, 0x01, 0x00,
        0x80.toByte(), 0x00, 0x00, 0x00, 0x00, 0x00, 0xff.toByte(),
        0xff.toByte(), 0xff.toByte(), 0x21, 0xf9.toByte(), 0x04, 0x01,
        0x00, 0x00, 0x00, 0x00, 0x2c, 0x00, 0x00, 0x00, 0x00, 0x01,
        0x00, 0x01, 0x00, 0x00, 0x02, 0x02, 0x4c, 0x01, 0x00, 0x3b
    )

    fun emptyResponse(resourceType: FilterResourceType): WebResourceResponse {
        val mimeType = when (resourceType) {
            FilterResourceType.SCRIPT -> "application/javascript"
            FilterResourceType.STYLESHEET -> "text/css"
            FilterResourceType.IMAGE -> "image/gif"
            FilterResourceType.FONT -> "font/woff2"
            FilterResourceType.MEDIA -> "video/mp4"
            else -> "text/plain"
        }
        val dataStream = if (resourceType == FilterResourceType.IMAGE) {
            ByteArrayInputStream(EMPTY_GIF)
        } else {
            ByteArrayInputStream(ByteArray(0))
        }
        return WebResourceResponse(
            mimeType,
            "utf-8",
            200,
            "OK",
            mapOf(
                "Cache-Control" to "no-store",
                "Access-Control-Allow-Origin" to "*"
            ),
            dataStream
        )
    }

    private fun isWebviewProcess(context: Context): Boolean {
        val processName = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            android.app.Application.getProcessName()
        } else {
            val pid = android.os.Process.myPid()
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
            am?.runningAppProcesses?.firstOrNull { it.pid == pid }?.processName
        }
        return processName != null && processName.endsWith(":webview")
    }

    private fun sendFilterEventBroadcast(context: Context, event: FilterEvent) {
        val eventJson = event.toJson().toString()
        if (eventJson.toByteArray(Charsets.UTF_8).size > MAX_EVENT_JSON_BYTES) return
        val intent = android.content.Intent("site.anzz.childkiosk.action.RECORD_FILTER_EVENT").apply {
            putExtra("event_json", eventJson)
            setPackage(context.packageName)
        }
        context.sendBroadcast(intent)
    }

    private fun shouldRecordFilterEvent(nowMs: Long = System.currentTimeMillis()): Boolean {
        val windowStart = eventWindowStartMs.get()
        if (nowMs - windowStart >= 1_000L && eventWindowStartMs.compareAndSet(windowStart, nowMs)) {
            eventWindowCount.set(0)
        }
        return eventWindowCount.incrementAndGet() <= MAX_FILTER_EVENTS_PER_SECOND
    }

    internal fun boundEvent(event: FilterEvent): FilterEvent {
        return event.copy(
            action = boundEventText(event.action, 32),
            url = boundEventText(event.url, MAX_EVENT_URL_CHARS),
            topLevelUrl = boundEventText(event.topLevelUrl, MAX_EVENT_TOP_LEVEL_URL_CHARS),
            resourceType = boundEventText(event.resourceType, 48),
            ruleText = boundEventText(event.ruleText, MAX_EVENT_RULE_CHARS),
            sourceName = boundEventText(event.sourceName, 128),
            reason = boundEventText(event.reason, MAX_EVENT_METADATA_CHARS),
            sourceId = boundEventText(event.sourceId, 128),
            matchType = boundEventText(event.matchType, 48),
            indexKey = boundEventText(event.indexKey, 128),
            candidateCount = event.candidateCount.coerceIn(0, 1_000_000),
            cacheStatus = boundEventText(event.cacheStatus, 48)
        )
    }

    internal fun boundEventText(value: String, maxChars: Int): String {
        if (maxChars <= 0 || value.isEmpty()) return ""
        val sanitized = buildString(minOf(value.length, maxChars)) {
            for (char in value) {
                if (length >= maxChars) break
                append(if (char.isISOControl()) ' ' else char)
            }
        }
        return if (sanitized.lastOrNull()?.isHighSurrogate() == true) {
            sanitized.dropLast(1)
        } else {
            sanitized
        }
    }

    fun isAdRequest(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val snapshot = FilterRuntimeSnapshot.default().copy(enabled = true)
        val requestContext = FilterRequestContext(
            requestUrl = url,
            topLevelUrl = url,
            resourceType = FilterResourceType.OTHER,
            isMainFrame = false,
            method = "GET",
            hasGesture = false
        )
        return FilterRepository.getEngine(snapshot).decide(requestContext).action == FilterAction.BLOCK
    }
}

private fun Map<String, String>.headerValue(name: String): String? {
    return entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
}
