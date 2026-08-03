package site.anzz.childkiosk.performance.cdp

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WebViewDebuggingGateTest {
    @Before
    fun setUp() {
        WebViewDebuggingGate.resetForTests()
    }

    @After
    fun tearDown() {
        WebViewDebuggingGate.resetForTests()
    }

    @Test
    fun temporaryLeaseRestoresDisabledPersistentPreference() {
        WebViewDebuggingGate.applyPersistentPreference(false).getOrThrow()

        val lease = WebViewDebuggingGate.acquireTemporary("temporary").getOrThrow()

        assertTrue(lease.temporarilyEnabled)
        assertTrue(WebViewDebuggingGate.desiredDebuggingEnabledForTests())
        assertTrue(WebViewDebuggingGate.releaseTemporary("temporary").getOrThrow().released)
        assertFalse(WebViewDebuggingGate.desiredDebuggingEnabledForTests())
    }

    @Test
    fun temporaryLeaseCannotDisablePersistentChromeInspect() {
        WebViewDebuggingGate.applyPersistentPreference(true).getOrThrow()

        val lease = WebViewDebuggingGate.acquireTemporary("inspect").getOrThrow()

        assertFalse(lease.temporarilyEnabled)
        assertTrue(lease.persistentDebuggingEnabled)
        assertTrue(WebViewDebuggingGate.releaseTemporary("inspect").getOrThrow().released)
        assertTrue(WebViewDebuggingGate.persistentPreferenceForTests())
        assertTrue(WebViewDebuggingGate.desiredDebuggingEnabledForTests())
    }

    @Test
    fun persistentPreferenceChangedDuringLeaseWinsAfterRelease() {
        WebViewDebuggingGate.applyPersistentPreference(false).getOrThrow()
        WebViewDebuggingGate.acquireTemporary("changing").getOrThrow()

        WebViewDebuggingGate.applyPersistentPreference(true).getOrThrow()
        WebViewDebuggingGate.releaseTemporary("changing").getOrThrow()

        assertTrue(WebViewDebuggingGate.persistentPreferenceForTests())
        assertTrue(WebViewDebuggingGate.desiredDebuggingEnabledForTests())
    }

    @Test
    fun staleReleaseStillReappliesPersistentPreference() {
        WebViewDebuggingGate.applyPersistentPreference(false).getOrThrow()

        val release = WebViewDebuggingGate.releaseTemporary("already-released").getOrThrow()

        assertFalse(release.released)
        assertFalse(release.persistentDebuggingEnabled)
        assertFalse(release.debuggingEnabled)
    }
}
