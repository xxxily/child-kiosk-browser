package site.anzz.childkiosk.util.filter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebViewFilterInjectorTest {

    @Test
    fun selectorIsSerializedAsDataAndNeverInterpolatedIntoCss() {
        val selector = "div[data-marker=\"quotes-and-<tag>\"]"
        val script = WebViewFilterInjector.buildCosmeticInjectionScript(
            listOf(match(selector))
        )

        assertTrue(script.contains("document.querySelectorAll(selectors[selectorIndex])"))
        assertTrue(script.contains("quotes-and-<tag>"))
        assertTrue(
            script.contains(
                "style.textContent = '.${WebViewFilterInjector.HIDDEN_CLASS} { display: none !important; visibility: hidden !important; }';"
            )
        )
        assertFalse(script.contains("$selector { display: none"))
        assertEquals(1, Regex("style\\.textContent\\s*=").findAll(script).count())
    }

    @Test
    fun selectorBudgetIsAppliedBeforeScriptConstruction() {
        val matches = (0..WebViewFilterInjector.MAX_SELECTORS_PER_PAGE + 50).map { index ->
            match(".ad-$index")
        }
        val script = WebViewFilterInjector.buildCosmeticInjectionScript(matches)

        assertTrue(script.contains(".ad-${WebViewFilterInjector.MAX_SELECTORS_PER_PAGE - 1}"))
        assertFalse(script.contains(".ad-${WebViewFilterInjector.MAX_SELECTORS_PER_PAGE}"))
    }

    @Test
    fun aggregateCostBudgetStopsBeforeLaterSelectors() {
        val expensiveSelector = "main section article div > [class*=\"sponsor\"]"
        val perSelectorCost = requireNotNull(CssSelectorPolicy.estimatedCost(expensiveSelector))
        val expectedCount = minOf(
            WebViewFilterInjector.MAX_SELECTORS_PER_PAGE,
            WebViewFilterInjector.MAX_SELECTOR_COST_PER_PAGE / perSelectorCost
        )
        val selected = WebViewFilterInjector.selectMatchesWithinBudget(
            (0..expectedCount + 5).map { index -> match("$expensiveSelector.ad-$index") }
        )

        assertEquals(expectedCount, selected.size)
        assertFalse(selected.any { it.selector.endsWith(".ad-$expectedCount") })
    }

    @Test
    fun scriptEnforcesRuntimeAndNodeBudgets() {
        val script = WebViewFilterInjector.buildCosmeticInjectionScript(listOf(match(".ad")))

        assertTrue(script.contains("var startedAt = performance.now();"))
        assertTrue(
            script.contains(
                "performance.now() - startedAt >= ${WebViewFilterInjector.MAX_INJECTION_DURATION_MS}"
            )
        )
        assertTrue(script.contains("oldIndex < ${WebViewFilterInjector.MAX_HIDDEN_NODES_PER_PAGE}"))
        assertTrue(script.contains("selectorHidden >= ${WebViewFilterInjector.MAX_NODES_PER_SELECTOR}"))
    }

    @Test
    fun unsupportedSelectorFailsClosedBeforeLaterSelectors() {
        val script = WebViewFilterInjector.buildCosmeticInjectionScript(
            listOf(match(".safe"), match("*:not(.content)"), match(".after"))
        )

        assertTrue(script.contains(".safe"))
        assertFalse(script.contains("*:not(.content)"))
        assertFalse(script.contains(".after"))
    }

    @Test
    fun duplicateSelectorsAreRemovedWithoutChangingFirstOccurrenceOrder() {
        val script = WebViewFilterInjector.buildCosmeticInjectionScript(
            listOf(match(".first"), match(".first"), match(".second"))
        )

        assertEquals(1, Regex(Regex.escape(".first")).findAll(script).count())
        assertTrue(script.indexOf(".first") < script.indexOf(".second"))
    }

    @Test
    fun selectorPolicyRejectsHighCostStructuresButKeepsCommonSelectors() {
        assertTrue(CssSelectorPolicy.isAllowed("div.ad > [class*=\"sponsor\"]"))
        assertTrue(CssSelectorPolicy.isAllowed(".modal:not(.trusted)"))
        assertFalse(CssSelectorPolicy.isAllowed("*:not(.content)"))
        assertFalse(CssSelectorPolicy.isAllowed(".ad::before"))
        assertFalse(CssSelectorPolicy.isAllowed("div div div div div div div div div div"))
        assertFalse(CssSelectorPolicy.isAllowed(".a,.b,.c,.d,.e"))
        assertFalse(CssSelectorPolicy.isAllowed("[a][b][c][d][e]"))
    }

    private fun match(selector: String): CosmeticFilterMatch {
        return CosmeticFilterMatch(
            selector = selector,
            rawText = "##$selector",
            sourceId = "test",
            sourceName = "test"
        )
    }
}
