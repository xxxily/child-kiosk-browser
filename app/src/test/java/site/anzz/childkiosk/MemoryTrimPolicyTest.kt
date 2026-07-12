package site.anzz.childkiosk

import android.content.ComponentCallbacks2
import org.junit.Assert.assertEquals
import org.junit.Test

class MemoryTrimPolicyTest {
    @Test
    fun `running moderate trims pool to one`() {
        assertDecision(ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE, "RUNNING_MODERATE", WebViewPoolTrimAction.TRIM_TO_ONE)
    }

    @Test
    fun `running low and critical clear pool`() {
        assertDecision(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW, "RUNNING_LOW", WebViewPoolTrimAction.CLEAR)
        assertDecision(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL, "RUNNING_CRITICAL", WebViewPoolTrimAction.CLEAR)
    }

    @Test
    fun `background levels keep their Android meanings and clear only pool`() {
        assertDecision(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN, "UI_HIDDEN", WebViewPoolTrimAction.CLEAR)
        assertDecision(ComponentCallbacks2.TRIM_MEMORY_BACKGROUND, "BACKGROUND", WebViewPoolTrimAction.CLEAR)
        assertDecision(ComponentCallbacks2.TRIM_MEMORY_MODERATE, "MODERATE", WebViewPoolTrimAction.CLEAR)
        assertDecision(ComponentCallbacks2.TRIM_MEMORY_COMPLETE, "COMPLETE", WebViewPoolTrimAction.CLEAR)
    }

    @Test
    fun `unknown level performs no pool operation`() {
        assertDecision(99, "UNKNOWN_99", WebViewPoolTrimAction.NONE)
    }

    private fun assertDecision(level: Int, name: String, action: WebViewPoolTrimAction) {
        assertEquals(MemoryTrimDecision(name, action), memoryTrimDecision(level))
    }
}
