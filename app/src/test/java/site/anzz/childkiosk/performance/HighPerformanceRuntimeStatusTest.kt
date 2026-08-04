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
    fun javascriptHeartbeatClassificationUsesWorkerOnlyForBackgroundLowFrequencyEvidence() {
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
                now = 40_000L,
                installedAt = installedAt,
                lastMainHeartbeatAt = 9_000L,
                lastWorkerHeartbeatAt = 39_000L,
                backgrounded = false
            )
        )
        assertEquals(
            HighPerformanceJavascriptState.LOW_FREQUENCY_RESPONSIVE,
            classifyJavascriptHeartbeat(
                now = 40_000L,
                installedAt = installedAt,
                lastMainHeartbeatAt = 9_000L,
                lastWorkerHeartbeatAt = 39_000L,
                backgrounded = true
            )
        )
        assertEquals(
            HighPerformanceJavascriptState.STALE,
            classifyJavascriptHeartbeat(
                now = 100_000L,
                installedAt = installedAt,
                lastMainHeartbeatAt = null,
                lastWorkerHeartbeatAt = 99_000L,
                backgrounded = true
            )
        )
        assertEquals(
            HighPerformanceJavascriptState.AWAITING_FIRST_HEARTBEAT,
            classifyJavascriptHeartbeat(
                now = 100_000L,
                installedAt = installedAt,
                lastMainHeartbeatAt = null,
                lastWorkerHeartbeatAt = null,
                backgrounded = true,
                visible = false
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
            fullSystemProtection = true,
            documentHidden = false,
            documentVisibilityState = "visible",
            lastVisibilityProbeAt = 4_000L,
            pageLoadId = "load-1",
            continuityState = HighPerformanceContinuityState.SCREEN_OFF_VISIBLE_CONTINUITY
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
    fun experimentalContinuityStatusRoundTripsAndOlderSchemaDefaultsOff() {
        val current = runtimeStatus(
            compositeState = HighPerformanceCompositeState.ACTIVE,
            sessionState = HighPerformanceJavascriptState.RESPONSIVE,
            installedAt = 1_000L,
            lastMainAt = 2_000L
        ).copy(experimentalCdpContinuityEnabled = true)

        assertEquals(
            true,
            HighPerformanceRuntimeStatus.fromJson(current.toJson())
                ?.experimentalCdpContinuityEnabled
        )

        val previous = current.toJson()
            .put("schemaVersion", 4)
            .apply { remove("experimentalCdpContinuityEnabled") }
        assertEquals(
            false,
            HighPerformanceRuntimeStatus.fromJson(previous)
                ?.experimentalCdpContinuityEnabled
        )
        assertEquals(
            ExperimentalCdpTimingProfile.BALANCED,
            HighPerformanceRuntimeStatus.fromJson(previous)?.experimentalCdpTimingProfile
        )
        assertEquals(false, HighPerformanceRuntimeStatus.fromJson(previous)?.verboseDiagnosticsEnabled)
    }

    @Test
    fun previousRuntimeSchemaRemainsReadableWithUnknownContinuity() {
        val json = runtimeStatus(
            compositeState = HighPerformanceCompositeState.ACTIVE,
            sessionState = HighPerformanceJavascriptState.RESPONSIVE,
            installedAt = 1_000L,
            lastMainAt = 2_000L
        ).toJson().put("schemaVersion", 3)
        val session = json.getJSONArray("sessions").getJSONObject(0)
        session.remove("documentHidden")
        session.remove("documentVisibilityState")
        session.remove("lastVisibilityProbeAt")
        session.remove("pageLoadId")
        session.remove("continuityState")

        val restored = HighPerformanceRuntimeStatus.fromJson(json)

        assertEquals(HighPerformanceContinuityState.UNKNOWN, restored?.sessions?.single()?.continuityState)
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
            HighPerformanceCompositeState.DEGRADED,
            highPerformanceCompositeStateForActiveSessions(
                resourceProtectionReady = true,
                javascriptStates = listOf(HighPerformanceJavascriptState.RESPONSIVE),
                continuityStates = listOf(HighPerformanceContinuityState.HIDDEN_DEGRADED)
            )
        )
        assertEquals(
            HighPerformanceCompositeState.ACTIVE,
            highPerformanceCompositeStateForActiveSessions(
                resourceProtectionReady = true,
                javascriptStates = listOf(HighPerformanceJavascriptState.RESPONSIVE)
            )
        )
        assertEquals(
            HighPerformanceCompositeState.BACKGROUND_THROTTLED,
            highPerformanceCompositeStateForActiveSessions(
                resourceProtectionReady = true,
                javascriptStates = listOf(HighPerformanceJavascriptState.LOW_FREQUENCY_RESPONSIVE),
                continuityStates = listOf(
                    HighPerformanceContinuityState.HIDDEN_LOW_FREQUENCY_CONTINUITY
                )
            )
        )
    }

    @Test
    fun screenOffContinuityRequiresRealVisibleDocumentAndResponsiveMainTimer() {
        assertEquals(
            HighPerformanceContinuityState.SCREEN_OFF_VISIBLE_CONTINUITY,
            classifyContinuityState(
                screenInteractive = false,
                activityState = HighPerformanceActivityState.STOPPED,
                javascriptState = HighPerformanceJavascriptState.RESPONSIVE,
                documentHidden = false
            )
        )
        assertEquals(
            HighPerformanceContinuityState.HIDDEN_DEGRADED,
            classifyContinuityState(
                screenInteractive = false,
                activityState = HighPerformanceActivityState.STOPPED,
                javascriptState = HighPerformanceJavascriptState.RESPONSIVE,
                documentHidden = true
            )
        )
        assertEquals(
            HighPerformanceContinuityState.STALE,
            classifyContinuityState(
                screenInteractive = false,
                activityState = HighPerformanceActivityState.STOPPED,
                javascriptState = HighPerformanceJavascriptState.STALE,
                documentHidden = false
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

    @Test
    fun persistedHiddenWorkerHeartbeatIsReclassifiedAsBackgroundThrottled() {
        val persisted = runtimeStatus(
            compositeState = HighPerformanceCompositeState.ACTIVE,
            sessionState = HighPerformanceJavascriptState.RESPONSIVE,
            installedAt = 1_000L,
            lastMainAt = 10_000L
        ).let { status ->
            status.copy(
                sessions = status.sessions.map { session ->
                    session.copy(
                        lastWorkerJsHeartbeatAt = 39_000L,
                        documentHidden = true,
                        documentVisibilityState = "hidden"
                    )
                }
            )
        }

        val refreshed = refreshHeartbeatDerivedState(persisted, now = 40_001L)

        assertEquals(
            HighPerformanceJavascriptState.LOW_FREQUENCY_RESPONSIVE,
            refreshed.sessions.single().javascriptState
        )
        assertEquals(
            HighPerformanceContinuityState.HIDDEN_LOW_FREQUENCY_CONTINUITY,
            refreshed.sessions.single().continuityState
        )
        assertEquals(HighPerformanceCompositeState.BACKGROUND_THROTTLED, refreshed.compositeState)
    }

    @Test
    fun persistedHiddenSessionCannotRemainActiveJustBecauseHeartbeatIsResponsive() {
        val persisted = runtimeStatus(
            compositeState = HighPerformanceCompositeState.ACTIVE,
            sessionState = HighPerformanceJavascriptState.RESPONSIVE,
            installedAt = 1_000L,
            lastMainAt = 39_000L
        ).let { status ->
            status.copy(
                sessions = status.sessions.map { session ->
                    session.copy(
                        documentHidden = true,
                        documentVisibilityState = "hidden",
                        continuityState = HighPerformanceContinuityState.HIDDEN_DEGRADED
                    )
                }
            )
        }

        val refreshed = refreshHeartbeatDerivedState(persisted, now = 40_000L)

        assertEquals(HighPerformanceJavascriptState.RESPONSIVE, refreshed.sessions.single().javascriptState)
        assertEquals(HighPerformanceContinuityState.HIDDEN_DEGRADED, refreshed.sessions.single().continuityState)
        assertEquals(HighPerformanceCompositeState.DEGRADED, refreshed.compositeState)
    }

    @Test
    fun unobservedHiddenTabDoesNotDowngradeVerifiedBackgroundContinuity() {
        val persisted = runtimeStatus(
            compositeState = HighPerformanceCompositeState.BACKGROUND_THROTTLED,
            sessionState = HighPerformanceJavascriptState.LOW_FREQUENCY_RESPONSIVE,
            installedAt = 1_000L,
            lastMainAt = 10_000L
        ).let { status ->
            val verified = status.sessions.single().copy(
                visible = true,
                lastWorkerJsHeartbeatAt = 39_000L,
                documentHidden = true,
                documentVisibilityState = "hidden",
                lastVisibilityProbeAt = 39_000L,
                continuityState = HighPerformanceContinuityState.HIDDEN_LOW_FREQUENCY_CONTINUITY
            )
            val pendingHidden = verified.copy(
                tokenId = "pending-session",
                tabId = "pending-tab",
                jsHeartbeatInstalledAt = 1_000L,
                lastJsHeartbeatAt = null,
                lastMainJsHeartbeatAt = null,
                lastWorkerJsHeartbeatAt = null,
                javascriptState = HighPerformanceJavascriptState.STALE,
                visible = false,
                documentHidden = null,
                documentVisibilityState = null,
                lastVisibilityProbeAt = null,
                pageLoadId = null,
                continuityState = HighPerformanceContinuityState.STALE
            )
            status.copy(sessions = listOf(verified, pendingHidden))
        }

        val refreshed = refreshHeartbeatDerivedState(persisted, now = 40_000L)

        assertEquals(
            HighPerformanceJavascriptState.AWAITING_FIRST_HEARTBEAT,
            refreshed.sessions[1].javascriptState
        )
        assertEquals(HighPerformanceContinuityState.UNKNOWN, refreshed.sessions[1].continuityState)
        assertEquals(HighPerformanceCompositeState.BACKGROUND_THROTTLED, refreshed.compositeState)
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
        keyguardShowing = false,
        keyguardSecure = false,
        keyguardReadyForScreenOff = true,
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
                fullSystemProtection = true,
                documentHidden = false,
                documentVisibilityState = "visible",
                lastVisibilityProbeAt = lastMainAt,
                pageLoadId = "load-1",
                continuityState = HighPerformanceContinuityState.SCREEN_OFF_VISIBLE_CONTINUITY
            )
        ),
        recentEvents = emptyList()
    )
}
