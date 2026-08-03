package site.anzz.childkiosk.performance

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HighPerformanceRuntimeSnapshotTest {
    @Test
    fun roundTripsCanonicalVersionedSnapshot() {
        val snapshot = HighPerformanceRuntimeSnapshot(
            configVersion = 42L,
            enabled = true,
            experimentalCdpContinuityEnabled = true,
            experimentalCdpTimingProfile = ExperimentalCdpTimingProfile.CONSERVATIVE,
            verboseDiagnosticsEnabled = true,
            generatedAt = 100L,
            rules = listOf(
                rule(id = "b", origin = "https://b.example", updatedAt = 3L),
                rule(id = "a", origin = "https://EXAMPLE.com:443/", updatedAt = 2L)
            )
        )

        val parsed = HighPerformanceRuntimeSnapshot.parseOrDisabled(snapshot.toJsonString())

        assertTrue(parsed.enabled)
        assertTrue(parsed.experimentalCdpContinuityEnabled)
        assertEquals(ExperimentalCdpTimingProfile.CONSERVATIVE, parsed.experimentalCdpTimingProfile)
        assertTrue(parsed.verboseDiagnosticsEnabled)
        assertEquals(42L, parsed.configVersion)
        assertEquals(listOf("https://b.example", "https://example.com"), parsed.rules.map { it.origin })
    }

    @Test
    fun snapshotWithoutExperimentalFieldDefaultsToDisabled() {
        val json = validJson().apply {
            remove("experimentalCdpContinuityEnabled")
        }

        val parsed = HighPerformanceRuntimeSnapshot.parseOrDisabled(json.toString())

        assertTrue(parsed.enabled)
        assertFalse(parsed.experimentalCdpContinuityEnabled)
        assertEquals(ExperimentalCdpTimingProfile.BALANCED, parsed.experimentalCdpTimingProfile)
        assertFalse(parsed.verboseDiagnosticsEnabled)
    }

    @Test
    fun previousRuntimeSchemaDefaultsNewDebugControls() {
        val json = validJson().apply {
            put("schemaVersion", 1)
            remove("experimentalCdpTimingProfile")
            remove("verboseDiagnosticsEnabled")
        }

        val parsed = HighPerformanceRuntimeSnapshot.parseOrDisabled(json.toString())

        assertTrue(parsed.enabled)
        assertEquals(ExperimentalCdpTimingProfile.BALANCED, parsed.experimentalCdpTimingProfile)
        assertFalse(parsed.verboseDiagnosticsEnabled)
    }

    @Test
    fun malformedOrUnknownSchemaFailsClosedAndKeepsObservedVersion() {
        val unknown = validJson(configVersion = 9L).put("schemaVersion", 99).toString()
        val malformedRule = validJson(configVersion = 12L).apply {
            getJSONArray("rules").getJSONObject(0).put("origin", "javascript://evil.test")
        }.toString()

        listOf(
            HighPerformanceRuntimeSnapshot.parseOrDisabled(unknown),
            HighPerformanceRuntimeSnapshot.parseOrDisabled(malformedRule),
            HighPerformanceRuntimeSnapshot.parseOrDisabled("{broken", minimumConfigVersion = 7L)
        ).forEach { parsed ->
            assertFalse(parsed.enabled)
            assertTrue(parsed.rules.isEmpty())
        }
        assertEquals(9L, HighPerformanceRuntimeSnapshot.parseOrDisabled(unknown).configVersion)
        assertEquals(12L, HighPerformanceRuntimeSnapshot.parseOrDisabled(malformedRule).configVersion)
        assertEquals(7L, HighPerformanceRuntimeSnapshot.parseOrDisabled("{broken", 7L).configVersion)
    }

    @Test
    fun duplicateOriginsAndIllegalSubdomainInheritanceFailClosed() {
        val duplicate = validJson().apply {
            val first = getJSONArray("rules").getJSONObject(0)
            getJSONArray("rules").put(JSONObject(first.toString()).put("id", "second"))
        }.toString()
        val publicSuffix = validJson().apply {
            getJSONArray("rules").getJSONObject(0)
                .put("origin", "https://co.uk")
                .put("includeSubdomains", true)
        }.toString()

        assertFalse(HighPerformanceRuntimeSnapshot.parseOrDisabled(duplicate).enabled)
        assertFalse(HighPerformanceRuntimeSnapshot.parseOrDisabled(publicSuffix).enabled)
    }

    @Test
    fun wrongJsonTypesFailClosedInsteadOfCoercing() {
        val json = validJson().put("enabled", "true").toString()
        val parsed = HighPerformanceRuntimeSnapshot.parseOrDisabled(json)

        assertFalse(parsed.enabled)
        assertTrue(parsed.rules.isEmpty())
    }

    @Test
    fun staleEnabledSnapshotCannotBePromotedToRequestedVersion() {
        val parsed = HighPerformanceRuntimeSnapshot.parseOrDisabled(
            raw = validJson(configVersion = 4L).toString(),
            minimumConfigVersion = 5L
        )

        assertEquals(5L, parsed.configVersion)
        assertFalse(parsed.enabled)
        assertTrue(parsed.rules.isEmpty())
    }

    @Test
    fun negativeSerializedVersionFailsClosed() {
        val parsed = HighPerformanceRuntimeSnapshot.parseOrDisabled(
            validJson(configVersion = 1L).put("configVersion", -1L).toString()
        )

        assertEquals(0L, parsed.configVersion)
        assertFalse(parsed.enabled)
        assertTrue(parsed.rules.isEmpty())
    }

    private fun validJson(configVersion: Long = 1L): JSONObject {
        return HighPerformanceRuntimeSnapshot(
            configVersion = configVersion,
            enabled = true,
            generatedAt = 2L,
            rules = listOf(rule(id = "one", origin = "https://example.com", updatedAt = 2L))
        ).toJson()
    }

    private fun rule(id: String, origin: String, updatedAt: Long): HighPerformanceRuntimeRule {
        return HighPerformanceRuntimeRule(
            id = id,
            origin = origin,
            enabled = true,
            includeSubdomains = false,
            displayName = null,
            updatedAt = updatedAt
        )
    }
}
