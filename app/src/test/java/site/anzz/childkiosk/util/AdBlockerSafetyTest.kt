package site.anzz.childkiosk.util

import android.content.Context
import android.net.Uri
import android.webkit.WebResourceRequest
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import site.anzz.childkiosk.util.filter.FilterAction
import site.anzz.childkiosk.util.filter.FilterEngine
import site.anzz.childkiosk.util.filter.FilterEvent
import site.anzz.childkiosk.util.filter.FilterRuleSource
import site.anzz.childkiosk.util.filter.FilterRuntimeSnapshot
import site.anzz.childkiosk.util.filter.WebViewFilterEngineHandle
import site.anzz.childkiosk.util.filter.WebViewFilterRuntimeStatus

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AdBlockerSafetyTest {

    @Test
    fun oversizedUrlStillUsesExplicitEngineHandle() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val snapshot = enabledSnapshot()
        val engine = FilterEngine.build(
            listOf(FilterRuleSource("test", "test", "||tracker.example^"))
        )
        val handle = WebViewFilterEngineHandle(
            snapshot = snapshot,
            engine = engine,
            status = WebViewFilterRuntimeStatus.READY,
            generation = 1L
        )
        val oversizedUrl = "https://tracker.example/pixel?pad=" + "x".repeat(8_192)

        val decision = AdBlocker.shouldBlock(
            context = context,
            request = FakeWebResourceRequest(oversizedUrl),
            topLevelUrl = "https://school.example/lesson",
            handle = handle
        )

        assertEquals(FilterAction.BLOCK, decision.action)
    }

    @Test
    fun requestHeaderSignalsReachResourceInference() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val snapshot = enabledSnapshot()
        val engine = FilterEngine.build(
            listOf(FilterRuleSource("test", "test", "||telemetry.example^${'$'}ping"))
        )
        val request = FakeWebResourceRequest(
            url = "https://telemetry.example/collect",
            method = "POST",
            headers = mapOf("Purpose" to "ping")
        )

        val decision = AdBlocker.shouldBlock(
            context = context,
            request = request,
            topLevelUrl = "https://school.example/lesson",
            snapshot = snapshot,
            engine = engine
        )

        assertEquals(FilterAction.BLOCK, decision.action)
    }

    @Test
    fun eventFieldsAreSanitizedAndBounded() {
        val bounded = AdBlocker.boundEvent(
            FilterEvent(
                timestamp = 1L,
                action = "BLOCK\u0000EXTRA",
                url = "u".repeat(AdBlocker.MAX_EVENT_URL_CHARS + 100),
                topLevelUrl = "t".repeat(AdBlocker.MAX_EVENT_TOP_LEVEL_URL_CHARS + 100),
                resourceType = "script",
                ruleText = "r".repeat(AdBlocker.MAX_EVENT_RULE_CHARS + 100),
                sourceName = "source",
                reason = "bad\nreason",
                sourceId = "id",
                matchType = "DOMAIN_ANCHOR",
                indexKey = "tracker.example",
                candidateCount = Int.MAX_VALUE,
                cacheStatus = "cache-miss"
            )
        )

        assertEquals(AdBlocker.MAX_EVENT_URL_CHARS, bounded.url.length)
        assertEquals(AdBlocker.MAX_EVENT_TOP_LEVEL_URL_CHARS, bounded.topLevelUrl.length)
        assertEquals(AdBlocker.MAX_EVENT_RULE_CHARS, bounded.ruleText.length)
        assertFalse(bounded.action.any(Char::isISOControl))
        assertFalse(bounded.reason.any(Char::isISOControl))
        assertEquals(1_000_000, bounded.candidateCount)
    }

    @Test
    fun textBoundDoesNotLeaveDanglingHighSurrogate() {
        val value = "abc\uD83D\uDE00"
        val bounded = AdBlocker.boundEventText(value, 4)

        assertEquals("abc", bounded)
        assertTrue(bounded.last().isLetter())
    }

    private fun enabledSnapshot(): FilterRuntimeSnapshot {
        return FilterRuntimeSnapshot.default().copy(
            enabled = true,
            preset = "TEST",
            enabledSubscriptionIds = emptyList(),
            subscriptions = emptyList()
        )
    }

    private class FakeWebResourceRequest(
        url: String,
        private val method: String = "GET",
        private val headers: Map<String, String> = emptyMap()
    ) : WebResourceRequest {
        private val requestUri = Uri.parse(url)

        override fun getUrl(): Uri = requestUri

        override fun isForMainFrame(): Boolean = false

        override fun isRedirect(): Boolean = false

        override fun hasGesture(): Boolean = false

        override fun getMethod(): String = method

        override fun getRequestHeaders(): Map<String, String> = headers
    }
}
