package site.anzz.childkiosk

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class WebViewDebuggingPolicyTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun chromeInspectPolicyRoundTripsBothStates() {
        listOf(false, true).forEach { enabled ->
            val policy = WebViewDebuggingPolicy(enabled)
            assertEquals(
                policy,
                WebViewDebuggingPolicyBridge.read(
                    WebViewDebuggingPolicyBridge.createIntent(context, policy)
                )
            )
        }
    }

    @Test
    fun unrelatedOrIncompleteBroadcastsAreRejected() {
        assertNull(WebViewDebuggingPolicyBridge.read(Intent("other.action")))
        assertNull(WebViewDebuggingPolicyBridge.read(Intent()))
    }
}
