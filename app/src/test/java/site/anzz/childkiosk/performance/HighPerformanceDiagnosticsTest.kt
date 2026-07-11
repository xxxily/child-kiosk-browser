package site.anzz.childkiosk.performance

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class HighPerformanceDiagnosticsTest {
    @Test
    fun persistedEventIsSanitizedAgainOnRead() {
        val event = HighPerformanceAuditEvent.fromJson(
            JSONObject()
                .put("timestamp", 123L)
                .put("type", "renderer gone\nsecret")
                .put("result", "failed\tbad")
                .put("origin", "https://Example.COM:443/account?token=secret#private")
                .put("sessionId", "tab\nprivate")
                .put("reason", "failed at https://example.com/path?token=secret\u0000 next\nline")
        ) ?: error("Expected valid event")

        assertEquals("renderer_gone_secret", event.type)
        assertEquals("failed_bad", event.result)
        assertEquals("https://example.com", event.origin)
        assertEquals("tab_private", event.sessionId)
        assertFalse(event.reason.orEmpty().contains("secret"))
        assertFalse(event.reason.orEmpty().any(Char::isISOControl))
    }

    @Test
    fun sanitizerRejectsNonWebOriginsAndBoundsReason() {
        val sanitized = HighPerformanceDiagnostics.sanitize(
            HighPerformanceAuditEvent(
                timestamp = 123L,
                type = "copy event",
                result = "ok",
                origin = "javascript://evil.test/secret",
                sessionId = null,
                reason = "x".repeat(HighPerformanceDiagnostics.MAX_REASON_LENGTH + 20)
            )
        )

        assertEquals("copy_event", sanitized.type)
        assertNull(sanitized.origin)
        assertEquals(HighPerformanceDiagnostics.MAX_REASON_LENGTH, sanitized.reason?.length)
    }

    @Test
    fun reasonSanitizerRedactsUrlsAndAllControlCharacters() {
        val sanitized = HighPerformanceDiagnostics.safeReason(
            "before https://example.com/private?token=secret\u0007 after\r\nend"
        )

        assertTrue(sanitized.contains("[url]"))
        assertFalse(sanitized.contains("example.com"))
        assertFalse(sanitized.any(Char::isISOControl))
    }
}
