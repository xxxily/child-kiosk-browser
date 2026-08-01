package site.anzz.childkiosk.performance

import android.content.Context
import android.webkit.WebView
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import android.os.Looper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HighPerformanceSessionControllerTest {
    private lateinit var context: Context
    private lateinit var webView: WebView

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        HighPerformanceSessionController.resetForTests()
        webView = WebView(context)
        HighPerformanceSessionController.initialize(context, trustedSnapshot())
        HighPerformanceSessionController.onActivityStateChanged(
            OWNER_ID,
            HighPerformanceActivityState.RESUMED
        )
        HighPerformanceSessionController.registerWebView(
            context = context,
            ownerId = OWNER_ID,
            tabId = TAB_ID,
            webView = webView,
            initialCommittedUrl = TRUSTED_URL,
            visible = true
        )
    }

    @After
    fun tearDown() {
        HighPerformanceSessionController.resetForTests()
        webView.destroy()
    }

    @Test
    fun foregroundServiceFailureRetainsLogicalSessionAsDegraded() {
        HighPerformanceSessionController.onForegroundServiceStartFailed("test_failure")

        val state = HighPerformanceSessionController.debugStateForTests()
        assertEquals(1, state.registrationCount)
        assertEquals(1, state.activeSessionCount)
        assertEquals(0, state.suppressedRegistrationCount)
        assertEquals(HighPerformanceForegroundServiceState.FAILED, state.foregroundServiceState)
        assertEquals("test_failure", state.foregroundServiceError)
    }

    @Test
    fun unexpectedServiceStopRetainsLogicalSession() {
        HighPerformanceSessionController.onForegroundServiceRunning()

        HighPerformanceSessionController.onForegroundServiceStopped(
            expected = false,
            reason = "test_unexpected_stop"
        )

        val state = HighPerformanceSessionController.debugStateForTests()
        assertEquals(1, state.activeSessionCount)
        assertEquals(0, state.suppressedRegistrationCount)
        assertEquals(HighPerformanceForegroundServiceState.FAILED, state.foregroundServiceState)
        assertEquals("service_stopped_unexpectedly", state.foregroundServiceError)
    }

    @Test
    fun stoppedActivityRetainsSessionButDoesNotStartMissingService() {
        HighPerformanceSessionController.onActivityStateChanged(
            OWNER_ID,
            HighPerformanceActivityState.STOPPED
        )
        HighPerformanceSessionController.onForegroundServiceStartFailed("test_background_failure")

        val state = HighPerformanceSessionController.debugStateForTests()
        assertEquals(1, state.activeSessionCount)
        assertEquals(0, state.suppressedRegistrationCount)
        assertEquals(1, state.stoppedRegistrationCount)
        assertEquals(HighPerformanceForegroundServiceState.FAILED, state.foregroundServiceState)
        assertEquals(
            true,
            shouldDeferForegroundServiceStart(
                serviceAlreadyActive = false,
                ownerInForeground = false
            )
        )
    }

    @Test
    fun hiddenOrStoppedProtectedPageKeepsItsLogicalSession() {
        HighPerformanceSessionController.onVisibilityChanged(webView, visible = false)
        HighPerformanceSessionController.onActivityStateChanged(
            OWNER_ID,
            HighPerformanceActivityState.STOPPED
        )

        val state = HighPerformanceSessionController.debugStateForTests()

        assertEquals(1, state.activeSessionCount)
        assertEquals(1, state.stoppedRegistrationCount)
    }

    @Test
    fun onlyANewBackgroundServiceStartIsDeferredForStoppedOwner() {
        assertTrue(
            shouldDeferForegroundServiceStart(
                serviceAlreadyActive = false,
                ownerInForeground = false
            )
        )
        assertFalse(
            shouldDeferForegroundServiceStart(
                serviceAlreadyActive = true,
                ownerInForeground = false
            )
        )
    }

    @Test
    fun explicitStopStillSuppressesAndRemovesSession() {
        HighPerformanceSessionController.stopAll(
            source = "test_explicit_stop",
            suppressCurrentWebViews = true
        )

        val state = HighPerformanceSessionController.debugStateForTests()
        assertEquals(0, state.activeSessionCount)
        assertEquals(1, state.suppressedRegistrationCount)
    }

    @Test
    fun onlySuppressingStopsUninstallPageRuntime() {
        assertEquals(true, shouldUninstallPageRuntimeForStop(suppressCurrentWebViews = true))
        assertEquals(false, shouldUninstallPageRuntimeForStop(suppressCurrentWebViews = false))
    }

    @Test
    @LooperMode(LooperMode.Mode.LEGACY)
    fun repeatedAlarmHealthChecksKeepOneHeartbeatChain() {
        val scheduler = shadowOf(Looper.getMainLooper()).scheduler
        scheduler.reset()

        HighPerformanceSessionController.triggerAlarmHealthCheck()
        val scheduledAfterFirstAlarm = scheduler.size()
        HighPerformanceSessionController.triggerAlarmHealthCheck()

        assertEquals(1, scheduledAfterFirstAlarm)
        assertEquals(1, scheduler.size())
    }

    @Test
    fun javascriptHeartbeatRequiresMatchingSessionTokenAndMainTick() {
        val token = HighPerformanceSessionController.prepareJavascriptHeartbeat(webView)
        requireNotNull(token)

        assertEquals(
            false,
            HighPerformanceSessionController.onJavascriptHeartbeat(
                webView,
                "wrong-token",
                HighPerformanceProbeType.MAIN,
                1L
            )
        )
        assertEquals(
            true,
            HighPerformanceSessionController.onJavascriptHeartbeat(
                webView,
                token,
                HighPerformanceProbeType.INIT,
                1L
            )
        )
        assertEquals(
            HighPerformanceJavascriptState.AWAITING_FIRST_HEARTBEAT,
            HighPerformanceSessionController.javascriptStateForTests(webView)
        )
        assertEquals(
            true,
            HighPerformanceSessionController.onJavascriptHeartbeat(
                webView,
                token,
                HighPerformanceProbeType.MAIN,
                2L
            )
        )
        assertEquals(
            HighPerformanceJavascriptState.RESPONSIVE,
            HighPerformanceSessionController.javascriptStateForTests(webView)
        )
    }

    @Test
    fun freezeSignalUnfreezesProtectedWebView() {
        val token = HighPerformanceSessionController.prepareJavascriptHeartbeat(webView)
        requireNotNull(token)
        val before = HighPerformanceSessionController.debugStateForTests().unfreezeCount

        HighPerformanceSessionController.onPageProbe(
            webView,
            token,
            HighPerformanceProbeSignal(
                type = HighPerformanceProbeType.FREEZE,
                pageTimestamp = System.currentTimeMillis(),
                token = token
            )
        )

        assertEquals(before + 1, HighPerformanceSessionController.debugStateForTests().unfreezeCount)
    }

    private fun trustedSnapshot(): HighPerformanceRuntimeSnapshot {
        return HighPerformanceRuntimeSnapshot(
            configVersion = 1,
            enabled = true,
            generatedAt = 1,
            rules = listOf(
                HighPerformanceRuntimeRule(
                    id = "trusted",
                    origin = "https://trusted.example",
                    enabled = true,
                    includeSubdomains = false,
                    displayName = "Trusted",
                    updatedAt = 1
                )
            )
        )
    }

    private companion object {
        const val OWNER_ID = "owner"
        const val TAB_ID = "tab"
        const val TRUSTED_URL = "https://trusted.example/app"
    }
}
