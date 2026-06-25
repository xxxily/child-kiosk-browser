package site.anzz.childkiosk.util

import android.content.Context
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import site.anzz.childkiosk.util.filter.FilterAction
import site.anzz.childkiosk.util.filter.FilterDecision
import site.anzz.childkiosk.util.filter.FilterEvent
import site.anzz.childkiosk.util.filter.FilterRepository
import site.anzz.childkiosk.util.filter.FilterRequestContext
import site.anzz.childkiosk.util.filter.FilterResourceType
import site.anzz.childkiosk.util.filter.FilterRuntimeSnapshot
import java.io.ByteArrayInputStream

object AdBlocker {

    fun shouldBlock(
        context: Context,
        request: WebResourceRequest?,
        topLevelUrl: String,
        snapshot: FilterRuntimeSnapshot
    ): FilterDecision {
        if (!snapshot.enabled || request?.url == null) return FilterDecision.ALLOW
        val scheme = request.url.scheme?.lowercase(java.util.Locale.US)
        if (scheme != "http" && scheme != "https") return FilterDecision.ALLOW
        val requestUrl = request.url.toString()
        if (requestUrl.length > 2048) return FilterDecision.ALLOW
        val requestContext = FilterRequestContext(
            requestUrl = requestUrl,
            topLevelUrl = topLevelUrl,
            resourceType = FilterResourceType.infer(
                url = requestUrl,
                acceptHeader = request.requestHeaders?.get("Accept"),
                isMainFrame = request.isForMainFrame
            ),
            isMainFrame = request.isForMainFrame,
            method = request.method.orEmpty(),
            hasGesture = request.hasGesture()
        )
        val engine = FilterRepository.getCachedEngine(snapshot) ?: return FilterDecision.ALLOW
        val siteOverride = FilterRepository.siteOverrideFor(snapshot, requestContext.topLevelHost)
        val decision = engine.decide(requestContext, siteOverride)
        if (decision.action != FilterAction.ALLOW) {
            val event = FilterEvent(
                timestamp = System.currentTimeMillis(),
                action = decision.action.name,
                url = requestUrl,
                topLevelUrl = topLevelUrl,
                resourceType = requestContext.resourceType.optionName,
                ruleText = decision.rule?.rawText.orEmpty(),
                sourceName = decision.rule?.sourceName.orEmpty(),
                reason = decision.reason
            )
            if (isWebviewProcess(context)) {
                sendFilterEventBroadcast(context, event)
            } else {
                FilterRepository.recordEvent(context, event)
            }
        }
        return decision
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
        val intent = android.content.Intent("site.anzz.childkiosk.action.RECORD_FILTER_EVENT").apply {
            putExtra("event_json", event.toJson().toString())
            setPackage(context.packageName)
        }
        context.sendBroadcast(intent)
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
