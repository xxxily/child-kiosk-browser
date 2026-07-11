package site.anzz.childkiosk.performance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HighPerformanceTabMemoryPolicyTest {
    @Test
    fun protectedTabsAreNeverSelectedForAutomaticFreezing() {
        val decision = HighPerformanceTabMemoryPolicy.decide(
            backgroundTabs = listOf(
                candidate("protected-old", lastActive = 1L, protected = true),
                candidate("ordinary-old", lastActive = 2L, protected = false),
                candidate("protected-new", lastActive = 3L, protected = true)
            ),
            maxBackgroundWebViews = 1
        )

        assertEquals(listOf("ordinary-old"), decision.tabIdsToFreeze)
    }

    @Test
    fun ordinaryTabsAreFrozenOldestFirstUntilTheNormalCapIsMet() {
        val decision = HighPerformanceTabMemoryPolicy.decide(
            backgroundTabs = listOf(
                candidate("new", lastActive = 30L, protected = false),
                candidate("old", lastActive = 10L, protected = false),
                candidate("middle", lastActive = 20L, protected = false)
            ),
            maxBackgroundWebViews = 1
        )

        assertEquals(listOf("old", "middle"), decision.tabIdsToFreeze)
    }

    @Test
    fun allProtectedTabsRemainAliveWhenTheyExceedTheNormalCap() {
        val decision = HighPerformanceTabMemoryPolicy.decide(
            backgroundTabs = listOf(
                candidate("one", lastActive = 1L, protected = true),
                candidate("two", lastActive = 2L, protected = true)
            ),
            maxBackgroundWebViews = 0
        )

        assertTrue(decision.tabIdsToFreeze.isEmpty())
    }

    private fun candidate(
        id: String,
        lastActive: Long,
        protected: Boolean
    ): HighPerformanceTabMemoryCandidate = HighPerformanceTabMemoryCandidate(
        tabId = id,
        lastActiveTimeMs = lastActive,
        protected = protected
    )
}
