package site.anzz.childkiosk.performance

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HighPerformancePageRuntimeTest {
    @Test
    fun allowedOriginsFollowEnabledExactAndSubdomainRules() {
        val snapshot = snapshot(
            rules = listOf(
                rule("exact", "https://exact.example"),
                rule("tree", "https://tree.example", includeSubdomains = true),
                rule("off", "https://off.example", enabled = false)
            )
        )

        assertEquals(
            setOf(
                "https://exact.example",
                "https://tree.example",
                "https://*.tree.example"
            ),
            HighPerformancePageRuntime.allowedOriginRules(snapshot)
        )
        assertTrue(
            HighPerformancePageRuntime.allowedOriginRules(snapshot.copy(enabled = false)).isEmpty()
        )
    }

    @Test
    fun probeProtocolRejectsMalformedUntrustedAndStaleMessages() {
        val now = System.currentTimeMillis()
        val valid = JSONObject()
            .put("v", 1)
            .put("type", "main")
            .put("ts", now)
            .put("token", "session-token")
            .toString()
        assertNotNull(HighPerformanceProbeProtocol.parse(valid))

        assertNull(HighPerformanceProbeProtocol.parse(null))
        assertNull(HighPerformanceProbeProtocol.parse("x".repeat(513)))
        assertNull(HighPerformanceProbeProtocol.parse(JSONObject(valid).put("extra", true).toString()))
        assertNull(HighPerformanceProbeProtocol.parse(JSONObject(valid).put("v", 2).toString()))
        assertNull(HighPerformanceProbeProtocol.parse(JSONObject(valid).put("type", "unknown").toString()))
        assertNull(HighPerformanceProbeProtocol.parse(JSONObject(valid).put("token", "").toString()))
        assertNull(HighPerformanceProbeProtocol.parse(JSONObject(valid).put("token", "x".repeat(129)).toString()))
        assertNull(
            HighPerformanceProbeProtocol.parse(
                JSONObject(valid).put("ts", now - 24L * 60L * 60L * 1_000L - 1L).toString()
            )
        )
    }

    @Test
    fun documentStartScriptProtectsVisibilityWithoutBlockingPageHide() {
        val script = HighPerformancePageRuntime.lifecycleScript()

        assertTrue(script.contains("defineVisible('visibilityState', 'visible')"))
        assertTrue(script.contains("listen(document, 'freeze', 'freeze', true)"))
        assertTrue(script.contains("listen(window, 'pagehide', 'page_hide', false)"))
        assertFalse(script.contains("listen(window, 'pagehide', 'page_hide', true)"))
        assertTrue(script.contains("new Worker(blobUrl)"))
    }

    private fun snapshot(rules: List<HighPerformanceRuntimeRule>) =
        HighPerformanceRuntimeSnapshot(
            configVersion = 1,
            enabled = true,
            generatedAt = 1,
            rules = rules
        )

    private fun rule(
        id: String,
        origin: String,
        enabled: Boolean = true,
        includeSubdomains: Boolean = false
    ) = HighPerformanceRuntimeRule(
        id = id,
        origin = origin,
        enabled = enabled,
        includeSubdomains = includeSubdomains,
        displayName = null,
        updatedAt = 1
    )
}
