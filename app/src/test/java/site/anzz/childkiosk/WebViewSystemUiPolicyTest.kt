package site.anzz.childkiosk

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class WebViewSystemUiPolicyTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `live system ui policy survives normal child normal round trip`() {
        val normal = WebViewSystemUiPolicy(normalSystemBars = true)
        val child = WebViewSystemUiPolicy(normalSystemBars = false)

        assertEquals(normal, roundTrip(normal))
        assertEquals(child, roundTrip(child))
        assertEquals(normal, roundTrip(normal))
    }

    @Test
    fun `unrelated or incomplete broadcasts are rejected`() {
        assertNull(WebViewSystemUiPolicyBridge.read(Intent("other.action")))
        assertNull(WebViewSystemUiPolicyBridge.read(Intent()))
    }

    @Test
    fun `ime visibility always blocks delayed immersive recovery`() {
        assertFalse(
            shouldRecoverSystemUiFromInsets(
                normalSystemBars = false,
                showNormalStatusBar = false,
                imeVisible = true,
                statusBarsVisible = true,
                navigationBarsVisible = true
            )
        )
        assertTrue(
            shouldRecoverSystemUiFromInsets(
                normalSystemBars = false,
                showNormalStatusBar = false,
                imeVisible = false,
                statusBarsVisible = false,
                navigationBarsVisible = true
            )
        )
    }

    private fun roundTrip(policy: WebViewSystemUiPolicy): WebViewSystemUiPolicy? {
        return WebViewSystemUiPolicyBridge.read(
            WebViewSystemUiPolicyBridge.createIntent(context, policy)
        )
    }
}
