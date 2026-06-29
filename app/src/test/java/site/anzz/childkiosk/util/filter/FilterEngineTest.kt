package site.anzz.childkiosk.util.filter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
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

    @Test
    fun cachedEngineOnlyReturnsPrewarmedInstance() {
        val snapshot = FilterRuntimeSnapshot(
            enabled = true,
            preset = FilterPreset.CUSTOM.storageValue,
            customRules = "||ads.example^",
            enabledSubscriptionIds = emptyList(),
            siteOverrides = emptyList()
        )

        FilterRepository.invalidate()
        try {
            assertNull(FilterRepository.getCachedEngine(snapshot))
            val engine = FilterRepository.getEngine(snapshot)
            assertSame(engine, FilterRepository.getCachedEngine(snapshot))
            FilterRepository.invalidate()
            assertNull(FilterRepository.getCachedEngine(snapshot))
        } finally {
            FilterRepository.invalidate()
        }
    }

    @Test
    fun tokenIndexDoesNotMissRulesWithMixedTypes() {
        val engine = FilterEngine.build(
            listOf(
                FilterRuleSource(
                    id = "test",
                    name = "test",
                    rulesText = """
                        ||ads.example.com^
                        ||tracker.test/pixel${'$'}image,third-party
                        /banner*ad${'$'}script
                        @@||cdn.example.com^
                        ||important.ad^${'$'}important
                    """.trimIndent()
                )
            )
        )

        assertEquals(
            FilterAction.BLOCK,
            engine.decide(context("https://ads.example.com/x.js", "https://page.com")).action
        )
        assertEquals(
            FilterAction.EXCEPTION,
            engine.decide(context("https://cdn.example.com/lib.js", "https://page.com")).action
        )
        assertEquals(
            FilterAction.BLOCK,
            engine.decide(context("https://important.ad/x", "https://page.com")).action
        )
        assertEquals(
            FilterAction.ALLOW,
            engine.decide(context("https://safe-site.com/page.html", "https://page.com")).action
        )
    }

    @Test
    fun universalRulesStillMatchWithoutToken() {
        val engine = FilterEngine.build(
            listOf(
                FilterRuleSource("test", "test", "*${'$'}third-party,image")
            )
        )
        val decision = engine.decide(
            context(
                url = "https://any-domain.com/photo.png",
                topLevelUrl = "https://other-site.com/page",
                type = FilterResourceType.IMAGE
            )
        )
        assertEquals(FilterAction.BLOCK, decision.action)
    }

    @Test
    fun subdomainBlockingWorksWithIndex() {
        val engine = FilterEngine.build(
            listOf(
                FilterRuleSource("test", "test", "||example.com^")
            )
        )
        assertEquals(
            FilterAction.BLOCK,
            engine.decide(context("https://sub.example.com/path", "https://page.com")).action
        )
        assertEquals(
            FilterAction.BLOCK,
            engine.decide(context("https://deep.sub.example.com/path", "https://page.com")).action
        )
        assertEquals(
            FilterAction.ALLOW,
            engine.decide(context("https://notexample.com/path", "https://page.com")).action
        )
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
