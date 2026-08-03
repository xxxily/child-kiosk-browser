package site.anzz.childkiosk.performance

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HighPerformanceDiagnosticsFormatterTest {
    @Test
    fun missingStatusProducesBoundedExplanation() {
        val formatted = HighPerformanceDiagnosticsFormatter.format(
            HighPerformanceRuntimeStatusReadResult(null, stale = true, reason = "missing_or_invalid")
        )

        assertTrue(formatted.contains("runtimeStatus=missing"))
        assertTrue(formatted.contains("reason=missing_or_invalid"))
    }

    @Test
    fun diagnosticSanitizerRemovesUrlsAndControlCharacters() {
        val safe = HighPerformanceDiagnostics.safeReason(
            "failed at https://trusted.example/path?secret=1\nnext"
        )

        assertFalse(safe.contains("secret=1"))
        assertFalse(safe.contains('\n'))
        assertTrue(safe.contains("[url]"))
    }
}
