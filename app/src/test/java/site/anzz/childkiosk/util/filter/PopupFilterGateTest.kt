package site.anzz.childkiosk.util.filter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PopupFilterGateTest {

    @Test
    fun evaluatesPopupTargetInsteadOfOpener() {
        val engine = engine("||ads.example^${'$'}popup")
        val snapshot = enabledSnapshot()

        val blocked = PopupFilterGate.evaluate(
            targetUrl = "https://ads.example/popup",
            openerUrl = "https://school.example/lesson",
            hasGesture = false,
            engine = engine,
            snapshot = snapshot
        )
        val allowed = PopupFilterGate.evaluate(
            targetUrl = "https://school.example/next",
            openerUrl = "https://ads.example/opener",
            hasGesture = false,
            engine = engine,
            snapshot = snapshot
        )

        assertTrue(blocked.shouldBlock)
        assertFalse(allowed.shouldBlock)
    }

    @Test
    fun carriesOpenerAndRealGestureIntoPopupRequestContext() {
        val context = PopupFilterGate.requestContext(
            targetUrl = "https://target.example/popup",
            openerUrl = "https://opener.example/page",
            hasGesture = true
        )

        assertEquals("https://target.example/popup", context.requestUrl)
        assertEquals("https://opener.example/page", context.topLevelUrl)
        assertEquals(FilterResourceType.POPUP, context.resourceType)
        assertTrue(context.isMainFrame)
        assertTrue(context.hasGesture)
    }

    @Test
    fun waitsForRealTargetBeforeAllowingTemporaryPopup() {
        val result = PopupFilterGate.evaluate(
            targetUrl = "about:blank",
            openerUrl = "https://school.example/lesson",
            hasGesture = true,
            engine = engine("||ads.example^${'$'}popup"),
            snapshot = enabledSnapshot()
        )

        assertTrue(result.shouldWaitForTarget)
        assertFalse(result.shouldBlock)
        assertEquals(PopupFilterDisposition.WAIT_FOR_TARGET, result.disposition)
    }

    @Test
    fun allNonHttpTargetsRemainPendingAndCannotRegisterPopup() {
        val engine = engine("||ads.example^${'$'}popup")
        val snapshot = enabledSnapshot()
        val targets = listOf(
            "about:blank#fragment",
            "data:text/html,redirect",
            "blob:https://school.example/id",
            "javascript:location='https://ads.example'"
        )

        targets.forEach { target ->
            val result = PopupFilterGate.evaluate(
                targetUrl = target,
                openerUrl = "https://school.example/lesson",
                hasGesture = true,
                engine = engine,
                snapshot = snapshot
            )
            assertEquals(target, PopupFilterDisposition.WAIT_FOR_TARGET, result.disposition)
        }
        assertTrue(PopupFilterGate.canLoadWhilePending("about:blank#fragment"))
        assertFalse(PopupFilterGate.canLoadWhilePending("data:text/html,redirect"))
    }

    @Test
    fun allowedRedirectCandidateDoesNotResolveBeforeBlockedFinalTarget() {
        val engine = engine("||ads.example^${'$'}popup")
        val snapshot = enabledSnapshot()

        val redirect = PopupFilterGate.evaluateUncommittedNavigation(
            targetUrl = "https://redirect.example/out",
            openerUrl = "https://school.example/lesson",
            hasGesture = false,
            engine = engine,
            snapshot = snapshot
        )
        val finalTarget = PopupFilterGate.evaluateUncommittedNavigation(
            targetUrl = "https://ads.example/popup",
            openerUrl = "https://school.example/lesson",
            hasGesture = false,
            engine = engine,
            snapshot = snapshot
        )

        assertEquals(PopupFilterDisposition.WAIT_FOR_TARGET, redirect.disposition)
        assertEquals(PopupFilterDisposition.BLOCK, finalTarget.disposition)
    }

    @Test
    fun allowedHttpTargetOnlyBecomesTerminalAfterCommitEvaluation() {
        val engine = engine("||ads.example^${'$'}popup")
        val snapshot = enabledSnapshot()
        val target = "https://school.example/next"

        val navigation = PopupFilterGate.evaluateUncommittedNavigation(
            target, "https://school.example/lesson", true, engine, snapshot
        )
        val committed = PopupFilterGate.evaluate(
            target, "https://school.example/lesson", true, engine, snapshot
        )

        assertEquals(PopupFilterDisposition.WAIT_FOR_TARGET, navigation.disposition)
        assertEquals(PopupFilterDisposition.ALLOW, committed.disposition)
    }

    @Test
    fun openerSiteOverrideCanAllowPopupTarget() {
        val engine = engine("||ads.example^${'$'}popup")
        val snapshot = enabledSnapshot().copy(
            siteOverrides = listOf(
                SiteFilterOverride(host = "school.example", networkDisabled = true)
            )
        )

        val result = PopupFilterGate.evaluate(
            targetUrl = "https://ads.example/popup",
            openerUrl = "https://school.example/lesson",
            hasGesture = true,
            engine = engine,
            snapshot = snapshot
        )

        assertEquals(FilterAction.EXCEPTION, result.decision.action)
        assertFalse(result.shouldBlock)
    }

    private fun engine(rules: String): FilterEngine {
        return FilterEngine.build(listOf(FilterRuleSource("test", "test", rules)))
    }

    private fun enabledSnapshot(): FilterRuntimeSnapshot {
        return FilterRuntimeSnapshot.default().copy(
            enabled = true,
            preset = "TEST",
            enabledSubscriptionIds = emptyList(),
            subscriptions = emptyList()
        )
    }
}
