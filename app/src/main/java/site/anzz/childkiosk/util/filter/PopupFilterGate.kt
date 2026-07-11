package site.anzz.childkiosk.util.filter

enum class PopupFilterDisposition {
    WAIT_FOR_TARGET,
    ALLOW,
    BLOCK
}

data class PopupFilterResult(
    val decision: FilterDecision,
    val targetUrl: String,
    val openerUrl: String,
    val hasGesture: Boolean,
    val disposition: PopupFilterDisposition
) {
    val shouldBlock: Boolean
        get() = disposition == PopupFilterDisposition.BLOCK

    val shouldWaitForTarget: Boolean
        get() = disposition == PopupFilterDisposition.WAIT_FOR_TARGET
}

/**
 * Evaluates a popup only after its first target URL is known. Callers must keep the temporary
 * WebView detached until this gate allows it; evaluating the opener URL cannot enforce $popup.
 */
object PopupFilterGate {
    /**
     * Checks a navigation candidate without resolving the temporary popup. HTTP redirects can
     * expose several individually allowed URLs before reaching a blocked destination, so ALLOW
     * is only terminal after WebView reports that the target was committed.
     */
    fun evaluateUncommittedNavigation(
        targetUrl: String,
        openerUrl: String,
        hasGesture: Boolean,
        engine: FilterEngine,
        snapshot: FilterRuntimeSnapshot
    ): PopupFilterResult {
        val result = evaluate(targetUrl, openerUrl, hasGesture, engine, snapshot)
        return if (result.disposition == PopupFilterDisposition.ALLOW) {
            result.copy(disposition = PopupFilterDisposition.WAIT_FOR_TARGET)
        } else {
            result
        }
    }

    /** Only web targets and inert about: placeholders may load while the popup is unregistered. */
    fun canLoadWhilePending(targetUrl: String): Boolean {
        return isHttpTarget(targetUrl) || targetUrl.trim().startsWith("about:", ignoreCase = true)
    }

    fun evaluate(
        targetUrl: String,
        openerUrl: String,
        hasGesture: Boolean,
        handle: WebViewFilterEngineHandle
    ): PopupFilterResult {
        return evaluate(
            targetUrl = targetUrl,
            openerUrl = openerUrl,
            hasGesture = hasGesture,
            engine = handle.engine,
            snapshot = handle.snapshot
        )
    }

    fun evaluate(
        targetUrl: String,
        openerUrl: String,
        hasGesture: Boolean,
        engine: FilterEngine,
        snapshot: FilterRuntimeSnapshot
    ): PopupFilterResult {
        if (!isHttpTarget(targetUrl)) {
            return PopupFilterResult(
                decision = FilterDecision.ALLOW,
                targetUrl = targetUrl,
                openerUrl = openerUrl,
                hasGesture = hasGesture,
                disposition = PopupFilterDisposition.WAIT_FOR_TARGET
            )
        }
        if (!snapshot.enabled) {
            return PopupFilterResult(
                decision = FilterDecision.ALLOW,
                targetUrl = targetUrl,
                openerUrl = openerUrl,
                hasGesture = hasGesture,
                disposition = PopupFilterDisposition.ALLOW
            )
        }
        val context = requestContext(
            targetUrl = targetUrl,
            openerUrl = openerUrl,
            hasGesture = hasGesture
        )
        val siteOverride = FilterRepository.siteOverrideFor(snapshot, context.topLevelHost)
        val decision = engine.decide(context, siteOverride)
        return PopupFilterResult(
            decision = decision,
            targetUrl = targetUrl,
            openerUrl = openerUrl,
            hasGesture = hasGesture,
            disposition = if (decision.action == FilterAction.BLOCK) {
                PopupFilterDisposition.BLOCK
            } else {
                PopupFilterDisposition.ALLOW
            }
        )
    }

    internal fun requestContext(
        targetUrl: String,
        openerUrl: String,
        hasGesture: Boolean
    ): FilterRequestContext {
        return FilterRequestContext(
            requestUrl = targetUrl,
            topLevelUrl = openerUrl.ifBlank { targetUrl },
            resourceType = FilterResourceType.POPUP,
            isMainFrame = true,
            method = "GET",
            hasGesture = hasGesture
        )
    }

    private fun isHttpTarget(targetUrl: String): Boolean {
        val normalized = targetUrl.trim()
        return normalized.startsWith("https://", ignoreCase = true) ||
            normalized.startsWith("http://", ignoreCase = true)
    }
}
