package site.anzz.childkiosk.util.filter

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FilterEventReceiverTest {

    private val now = 1_700_000_000_000L

    @Test
    fun acceptsBoundedCurrentFilterEvent() {
        val parsed = FilterEventReceiver.parseValidatedFilterEvent(eventJson().toString(), now)

        assertEquals("BLOCK", parsed?.action)
        assertEquals("https://tracker.example/pixel", parsed?.url)
    }

    @Test
    fun rejectsUnknownActionStaleTimestampAndOversizedFields() {
        assertNull(
            FilterEventReceiver.parseValidatedFilterEvent(
                eventJson().put("action", "FORGED").toString(),
                now
            )
        )
        assertNull(
            FilterEventReceiver.parseValidatedFilterEvent(
                eventJson().put("timestamp", now - 2 * 24 * 60 * 60 * 1_000L).toString(),
                now
            )
        )
        assertNull(
            FilterEventReceiver.parseValidatedFilterEvent(
                eventJson().put("ruleText", "x".repeat(1_025)).toString(),
                now
            )
        )
        assertNull(
            FilterEventReceiver.parseValidatedFilterEvent(
                eventJson().put("url", "file:///data/local/tmp/private").toString(),
                now
            )
        )
    }

    @Test
    fun rejectsOversizedJsonBeforeParsing() {
        val oversized = JSONObject()
            .put("padding", "x".repeat(33 * 1_024))
            .toString()

        assertNull(FilterEventReceiver.parseValidatedFilterEvent(oversized, now))
    }

    private fun eventJson(): JSONObject {
        return JSONObject()
            .put("timestamp", now)
            .put("action", "BLOCK")
            .put("url", "https://tracker.example/pixel")
            .put("topLevelUrl", "https://site.example/")
            .put("resourceType", "image")
            .put("ruleText", "||tracker.example^")
            .put("sourceName", "test")
            .put("reason", "blocking rule")
            .put("sourceId", "test")
            .put("matchType", "DOMAIN_ANCHOR")
            .put("indexKey", "tracker.example")
            .put("candidateCount", 1)
            .put("cacheStatus", "cache-miss")
    }
}
