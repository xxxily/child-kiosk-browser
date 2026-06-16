package com.example.childkiosk.util.filter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FilterEngineTest {

    @Test
    fun blocksDomainAnchorsAndAllowsExceptions() {
        val engine = FilterEngine.build(
            listOf(
                FilterRuleSource(
                    id = "test",
                    name = "test",
                    rulesText = """
                        ||ads.example.com^
                        @@||ads.example.com/allowed.js${'$'}script
                    """.trimIndent()
                )
            )
        )

        val blocked = engine.decide(
            context(
                url = "https://ads.example.com/banner.js",
                topLevelUrl = "https://site.example/page",
                type = FilterResourceType.SCRIPT
            )
        )
        assertEquals(FilterAction.BLOCK, blocked.action)

        val allowed = engine.decide(
            context(
                url = "https://ads.example.com/allowed.js",
                topLevelUrl = "https://site.example/page",
                type = FilterResourceType.SCRIPT
            )
        )
        assertEquals(FilterAction.EXCEPTION, allowed.action)
    }

    @Test
    fun respectsThirdPartyAndDomainOptions() {
        val engine = FilterEngine.build(
            listOf(
                FilterRuleSource(
                    id = "test",
                    name = "test",
                    rulesText = "||tracker.example^${'$'}third-party,domain=site.example|~safe.site.example"
                )
            )
        )

        assertEquals(
            FilterAction.BLOCK,
            engine.decide(context("https://tracker.example/pixel", "https://site.example/home")).action
        )
        assertEquals(
            FilterAction.ALLOW,
            engine.decide(context("https://tracker.example/pixel", "https://safe.site.example/home")).action
        )
        assertEquals(
            FilterAction.ALLOW,
            engine.decide(context("https://tracker.example/pixel", "https://tracker.example/home")).action
        )
    }

    @Test
    fun generatesCosmeticCssWithExceptions() {
        val engine = FilterEngine.build(
            listOf(
                FilterRuleSource(
                    id = "test",
                    name = "test",
                    rulesText = """
                        ##.ad
                        example.com##.sponsor
                        example.com#@#.ad
                    """.trimIndent()
                )
            )
        )

        val css = engine.cosmeticCssFor("www.example.com")
        assertFalse(css.contains(".ad"))
        assertTrue(css.contains(".sponsor"))
    }

    @Test
    fun cleansTrackingParamsOnlyWhenRuleMatches() {
        val engine = FilterEngine.build(
            listOf(
                FilterRuleSource(
                    id = "test",
                    name = "test",
                    rulesText = "*${'$'}removeparam=utm_source|fbclid"
                )
            )
        )

        val cleaned = engine.cleanUrlForNavigation(
            "https://example.com/page?utm_source=x&keep=1&fbclid=y",
            "https://example.com/"
        )
        assertEquals("https://example.com/page?keep=1", cleaned)
    }

    @Test
    fun scriptletAllowlistGeneratesKnownScriptsOnly() {
        val engine = FilterEngine.build(
            listOf(
                FilterRuleSource(
                    id = "test",
                    name = "test",
                    rulesText = """
                        example.com##+js(no-window-open-if)
                        example.com##+js(unknown-scriptlet, foo)
                    """.trimIndent()
                )
            )
        )

        val js = engine.scriptletJsFor("example.com")
        assertTrue(js.contains("window.open"))
        assertFalse(js.contains("unknown-scriptlet"))
        assertEquals(1, engine.report.scriptletRuleCount)
        assertEquals(1, engine.report.unsupportedRuleCount)
    }

    @Test
    fun reportsUnsupportedSyntax() {
        val report = FilterEngine.build(
            listOf(FilterRuleSource("test", "test", "||example.com^${'$'}csp=script-src 'none'"))
        ).report

        assertTrue(report.unsupportedRuleCount > 0)
        assertNotNull(report.sourceReports.firstOrNull())
    }

    @Test
    fun badfilterDisablesMatchingRuleWithOptions() {
        val engine = FilterEngine.build(
            listOf(
                FilterRuleSource(
                    id = "test",
                    name = "test",
                    rulesText = """
                        ||ads.example.com^${'$'}script,third-party
                        ||ads.example.com^${'$'}script,third-party,badfilter
                    """.trimIndent()
                )
            )
        )

        val decision = engine.decide(
            context(
                url = "https://ads.example.com/banner.js",
                topLevelUrl = "https://site.example/page",
                type = FilterResourceType.SCRIPT
            )
        )
        assertEquals(FilterAction.ALLOW, decision.action)
    }

    @Test
    fun runtimeSnapshotCarriesCustomSubscriptionMetadata() {
        val subscription = FilterCatalog.customSubscription(
            title = "Family list",
            url = "https://filters.example.test/family.txt"
        )
        val snapshot = FilterSettings(
            enabled = true,
            preset = FilterPreset.CUSTOM,
            customRules = "",
            subscriptions = listOf(subscription),
            siteOverrides = emptyList()
        ).toRuntimeSnapshot()

        assertEquals(listOf(subscription.id), snapshot.enabledSubscriptionIds)
        assertEquals(subscription.id, snapshot.subscriptions.single().id)
        assertEquals(subscription.subscriptionUrl, snapshot.subscriptions.single().subscriptionUrl)
    }

    private fun context(
        url: String,
        topLevelUrl: String,
        type: FilterResourceType = FilterResourceType.OTHER
    ): FilterRequestContext {
        return FilterRequestContext(
            requestUrl = url,
            topLevelUrl = topLevelUrl,
            resourceType = type,
            isMainFrame = type == FilterResourceType.DOCUMENT,
            method = "GET",
            hasGesture = false
        )
    }
}
