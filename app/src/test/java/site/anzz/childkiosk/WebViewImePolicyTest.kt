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
class WebViewImePolicyTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `live policy survives normal child normal round trip`() {
        val normal = WebViewImePolicy(limitImeInput = false, normalSystemBars = true)
        val child = WebViewImePolicy(limitImeInput = false, normalSystemBars = false)

        assertEquals(normal, roundTrip(normal))
        assertEquals(child, roundTrip(child))
        assertEquals(normal, roundTrip(normal))
    }

    @Test
    fun `unrelated or incomplete broadcasts are rejected`() {
        assertNull(WebViewImePolicyBridge.read(Intent("other.action")))
        assertNull(WebViewImePolicyBridge.read(Intent()))
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

    private fun roundTrip(policy: WebViewImePolicy): WebViewImePolicy? {
        return WebViewImePolicyBridge.read(WebViewImePolicyBridge.createIntent(context, policy))
    }
}
