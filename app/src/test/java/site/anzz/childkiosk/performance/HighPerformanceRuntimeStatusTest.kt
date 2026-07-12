package site.anzz.childkiosk.performance

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HighPerformanceRuntimeStatusTest {
    @Test
    fun javascriptHeartbeatClassificationRequiresRealMainTimerTick() {
        val installedAt = 1_000L

        assertEquals(
            HighPerformanceJavascriptState.UNKNOWN,
            classifyJavascriptHeartbeat(1_000L, null, null)
        )
        assertEquals(
            HighPerformanceJavascriptState.AWAITING_FIRST_HEARTBEAT,
            classifyJavascriptHeartbeat(5_000L, installedAt, null)
        )
        assertEquals(
            HighPerformanceJavascriptState.RESPONSIVE,
            classifyJavascriptHeartbeat(10_000L, installedAt, 9_000L)
        )
        assertEquals(
            HighPerformanceJavascriptState.STALE,
            classifyJavascriptHeartbeat(
                now = 40_000L,
                installedAt = installedAt,
                lastMainHeartbeatAt = 9_000L
            )
        )
        assertEquals(
            HighPerformanceJavascriptState.STALE,
            classifyJavascriptHeartbeat(
                now = 1_000L,
                installedAt = installedAt,
                lastMainHeartbeatAt = 2_000L
            )
        )
    }

    @Test
    fun sessionHeartbeatFieldsRoundTripWithoutPersistingProbeToken() {
        val original = HighPerformanceSessionStatus(
            tokenId = "session",
            tabId = "tab",
            displayName = "Trusted",
            origin = "https://trusted.example",
            startedAt = 1_000L,
            lastPageCallbackAt = 2_000L,
            jsHeartbeatInstalledAt = 3_000L,
            lastJsHeartbeatAt = 4_000L,
            lastMainJsHeartbeatAt = 4_000L,
            lastWorkerJsHeartbeatAt = 3_500L,
            javascriptState = HighPerformanceJavascriptState.RESPONSIVE,
            visible = false,
            activityState = HighPerformanceActivityState.STOPPED,
            rendererPolicy = HighPerformanceRendererPolicy.HIGH_PERFORMANCE_IMPORTANT_NOT_WAIVED,
            fullSystemProtection = true
        )

        val json = original.toJson()
        val restored = HighPerformanceSessionStatus.fromJson(JSONObject(json.toString()))

        assertEquals(original, restored)
        assertNull(json.opt("jsHeartbeatToken"))
    }

    @Test
    fun olderRuntimeSchemaIsRejectedInsteadOfInventingHeartbeatHealth() {
        val json = JSONObject()
            .put("schemaVersion", 2)
            .put("processInstanceId", "process")
            .put("processName", "site.anzz.childkiosk:webview")

        assertNull(HighPerformanceRuntimeStatus.fromJson(json))
    }

    @Test
    fun resourceReadinessCannotClaimActiveBeforeJavascriptIsResponsive() {
        assertEquals(
            HighPerformanceCompositeState.DEGRADED,
            highPerformanceCompositeStateForActiveSessions(
                resourceProtectionReady = true,
                javascriptStates = listOf(HighPerformanceJavascriptState.AWAITING_FIRST_HEARTBEAT)
            )
        )
        assertEquals(
            HighPerformanceCompositeState.DEGRADED,
            highPerformanceCompositeStateForActiveSessions(
                resourceProtectionReady = true,
                javascriptStates = listOf(
                    HighPerformanceJavascriptState.RESPONSIVE,
                    HighPerformanceJavascriptState.STALE
                )
            )
        )
        assertEquals(
            HighPerformanceCompositeState.ACTIVE,
            highPerformanceCompositeStateForActiveSessions(
                resourceProtectionReady = true,
                javascriptStates = listOf(HighPerformanceJavascriptState.RESPONSIVE)
            )
        )
    }

    @Test
    fun persistedResponsiveSessionIsReclassifiedAtReadTime() {
        val persisted = runtimeStatus(
            compositeState = HighPerformanceCompositeState.ACTIVE,
            sessionState = HighPerformanceJavascriptState.RESPONSIVE,
            installedAt = 1_000L,
            lastMainAt = 10_000L
        )

        val refreshed = refreshHeartbeatDerivedState(persisted, now = 40_001L)

        assertEquals(HighPerformanceJavascriptState.STALE, refreshed.sessions.single().javascriptState)
        assertEquals(HighPerformanceCompositeState.DEGRADED, refreshed.compositeState)
        assertEquals(10_000L, refreshed.sessions.single().lastMainJsHeartbeatAt)
    }

    private fun runtimeStatus(
        compositeState: HighPerformanceCompositeState,
        sessionState: HighPerformanceJavascriptState,
        installedAt: Long,
        lastMainAt: Long
    ) = HighPerformanceRuntimeStatus(
        processInstanceId = "process",
        processName = "site.anzz.childkiosk:webview",
        pid = 1,
        processStartedAt = 1L,
        appVersionName = "test",
        appVersionCode = 1L,
        androidRelease = "16",
        androidSdkInt = 36,
        manufacturer = "test",
        model = "test",
        webViewPackageName = "test.webview",
        webViewVersionName = "1",
        updatedAt = 20_000L,
        nativeHeartbeatAt = 20_000L,
        appliedConfigVersion = 1L,
        configuredRuleCount = 1,
        compositeState = compositeState,
        notificationPermissionGranted = true,
        notificationsVisible = true,
        ignoringBatteryOptimizations = true,
        screenInteractive = false,
        foregroundServiceDeclared = true,
        specialUseTypeDeclared = true,
        foregroundServiceState = HighPerformanceForegroundServiceState.RUNNING,
        foregroundServiceError = null,
        foregroundServiceStartedAt = 1L,
        wakeLockState = HighPerformanceWakeLockState.HELD,
        wakeLockAcquiredAt = 1L,
        wakeLockLastReleasedAt = null,
        wakeLockError = null,
        lastSessionStartedAt = 1L,
        lastSessionStoppedAt = null,
        lastInterruptionAt = null,
        sessions = listOf(
            HighPerformanceSessionStatus(
                tokenId = "session",
                tabId = "tab",
                displayName = null,
                origin = "https://trusted.example",
                startedAt = 1L,
                lastPageCallbackAt = 1L,
                jsHeartbeatInstalledAt = installedAt,
                lastJsHeartbeatAt = lastMainAt,
                lastMainJsHeartbeatAt = lastMainAt,
                lastWorkerJsHeartbeatAt = null,
                javascriptState = sessionState,
                visible = false,
                activityState = HighPerformanceActivityState.STOPPED,
                rendererPolicy = HighPerformanceRendererPolicy.HIGH_PERFORMANCE_IMPORTANT_NOT_WAIVED,
                fullSystemProtection = true
            )
        ),
        recentEvents = emptyList()
    )
}
