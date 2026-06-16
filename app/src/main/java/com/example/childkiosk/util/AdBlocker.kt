package com.example.childkiosk.util

import android.content.Context
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import com.example.childkiosk.util.filter.FilterAction
import com.example.childkiosk.util.filter.FilterDecision
import com.example.childkiosk.util.filter.FilterEvent
import com.example.childkiosk.util.filter.FilterRepository
import com.example.childkiosk.util.filter.FilterRequestContext
import com.example.childkiosk.util.filter.FilterResourceType
import com.example.childkiosk.util.filter.FilterRuntimeSnapshot
import java.io.ByteArrayInputStream

object AdBlocker {

    fun shouldBlock(
        context: Context,
        request: WebResourceRequest?,
        topLevelUrl: String,
        snapshot: FilterRuntimeSnapshot
    ): FilterDecision {
        if (!snapshot.enabled || request?.url == null) return FilterDecision.ALLOW
        val requestUrl = request.url.toString()
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
        val siteOverride = FilterRepository.siteOverrideFor(snapshot, requestContext.topLevelHost)
        val decision = FilterRepository.getEngine(context, snapshot).decide(requestContext, siteOverride)
        if (decision.action != FilterAction.ALLOW) {
            FilterRepository.recordEvent(
                context,
                FilterEvent(
                    timestamp = System.currentTimeMillis(),
                    action = decision.action.name,
                    url = requestUrl,
                    topLevelUrl = topLevelUrl,
                    resourceType = requestContext.resourceType.optionName,
                    ruleText = decision.rule?.rawText.orEmpty(),
                    sourceName = decision.rule?.sourceName.orEmpty(),
                    reason = decision.reason
                )
            )
        }
        return decision
    }

    fun emptyResponse(resourceType: FilterResourceType): WebResourceResponse {
        val mimeType = when (resourceType) {
            FilterResourceType.SCRIPT -> "application/javascript"
            FilterResourceType.STYLESHEET -> "text/css"
            FilterResourceType.IMAGE -> "image/gif"
            FilterResourceType.FONT -> "font/woff2"
            FilterResourceType.MEDIA -> "video/mp4"
            else -> "text/plain"
        }
        return WebResourceResponse(
            mimeType,
            "utf-8",
            204,
            "No Content",
            mapOf("Cache-Control" to "no-store"),
            ByteArrayInputStream(ByteArray(0))
        )
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
