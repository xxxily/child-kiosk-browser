package site.anzz.childkiosk.performance

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HighPerformanceRestartPolicyTest {
    private val owner = "owner"
    private val otherOwner = "other-owner"
    private val tabA = HighPerformanceTabKey(owner, "a")
    private val tabB = HighPerformanceTabKey(owner, "b")
    private val popup = HighPerformanceTabKey(owner, "popup")
    private val otherTab = HighPerformanceTabKey(otherOwner, "a")

    @Test
    fun authorizingOneStoppedTabDoesNotAuthorizeAnotherRebuiltTab() {
        val policy = HighPerformanceRestartPolicy()
        policy.suppressAll(listOf(tabA, tabB))

        assertFalse(policy.shouldSuppress(tabA, allowRestart = true))
        assertFalse(policy.shouldSuppress(tabA, allowRestart = false))
        assertTrue(policy.shouldSuppress(tabB, allowRestart = false))
        assertTrue(policy.shouldSuppress(popup, allowRestart = false))
    }

    @Test
    fun explicitEnableClearsDefaultAndPerTabSuppression() {
        val policy = HighPerformanceRestartPolicy()
        policy.suppressAll(listOf(tabA, tabB))

        policy.clearSuppression()

        assertFalse(policy.shouldSuppress(tabA, allowRestart = false))
        assertFalse(policy.shouldSuppress(tabB, allowRestart = false))
        assertFalse(policy.shouldSuppress(popup, allowRestart = false))
    }

    @Test
    fun forgottenAuthorizedTabDoesNotAuthorizeAReusedIdentity() {
        val policy = HighPerformanceRestartPolicy()
        policy.suppressAll(listOf(tabA))
        assertFalse(policy.shouldSuppress(tabA, allowRestart = true))

        policy.forget(tabA)

        assertTrue(policy.shouldSuppress(tabA, allowRestart = false))
    }

    @Test
    fun parentAuthorizationRequirementIsOwnerScopedAndIncludesFutureTabs() {
        val policy = HighPerformanceRestartPolicy()

        policy.requireParentAuthorization(owner)

        assertTrue(policy.shouldSuppress(tabA, allowRestart = false))
        assertTrue(policy.shouldSuppress(popup, allowRestart = false))
        assertFalse(policy.shouldSuppress(otherTab, allowRestart = false))
    }

    @Test
    fun ordinaryNavigationCannotBypassParentAuthorizationRequirement() {
        val policy = HighPerformanceRestartPolicy()
        policy.requireParentAuthorization(owner)

        assertFalse(policy.authorize(tabA))
        assertTrue(policy.shouldSuppress(tabA, allowRestart = true))

        policy.clearParentAuthorizationRequirement(owner)

        assertFalse(policy.shouldSuppress(tabA, allowRestart = false))
    }

    @Test
    fun clearingOwnerRequirementDoesNotClearGlobalStop() {
        val policy = HighPerformanceRestartPolicy()
        policy.suppressAll(listOf(tabA, otherTab))
        policy.requireParentAuthorization(owner)

        policy.clearParentAuthorizationRequirement(owner)

        assertTrue(policy.shouldSuppress(tabA, allowRestart = false))
        assertTrue(policy.shouldSuppress(otherTab, allowRestart = false))
    }

    @Test
    fun clearingGlobalStopDoesNotClearOwnerHealthLimit() {
        val policy = HighPerformanceRestartPolicy()
        policy.requireParentAuthorization(owner)
        policy.suppressAll(listOf(tabA, otherTab))

        policy.clearSuppression()

        assertTrue(policy.shouldSuppress(tabA, allowRestart = false))
        assertFalse(policy.shouldSuppress(otherTab, allowRestart = false))
    }
}
