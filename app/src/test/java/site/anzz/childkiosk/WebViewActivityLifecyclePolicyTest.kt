package site.anzz.childkiosk

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebViewActivityLifecyclePolicyTest {
    @Test
    fun `protected session retains native location during ordinary background stop`() {
        assertTrue(
            shouldRetainProtectedNativeLocationRequestsOnStop(
                hasProtectedSession = true,
                isFinishing = false,
                isChangingConfigurations = false
            )
        )
    }

    @Test
    fun `stop without protected session releases native location`() {
        assertFalse(
            shouldRetainProtectedNativeLocationRequestsOnStop(
                hasProtectedSession = false,
                isFinishing = false,
                isChangingConfigurations = false
            )
        )
    }

    @Test
    fun `finishing activity releases protected native location`() {
        assertFalse(
            shouldRetainProtectedNativeLocationRequestsOnStop(
                hasProtectedSession = true,
                isFinishing = true,
                isChangingConfigurations = false
            )
        )
    }

    @Test
    fun `configuration replacement releases protected native location`() {
        assertFalse(
            shouldRetainProtectedNativeLocationRequestsOnStop(
                hasProtectedSession = true,
                isFinishing = false,
                isChangingConfigurations = true
            )
        )
    }
}
