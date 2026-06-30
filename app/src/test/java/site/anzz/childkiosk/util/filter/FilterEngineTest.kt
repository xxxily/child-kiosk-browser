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
        assertTrue(engine.perfSnapshot().removeParamIndex.universalRuleCount >= 1)
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

    @Test
    fun perfSnapshotRecordsHotPathCounters() {
        val engine = FilterEngine.build(
            listOf(
                FilterRuleSource(
                    id = "test",
                    name = "test",
                    rulesText = """
                        /adserver[0-9]+/${'$'}script
                        ##.ad
                        example.com##+js(no-window-open-if)
                    """.trimIndent()
                )
            )
        )

        val request = context(
            url = "https://cdn.example.net/adserver12/file.js",
            topLevelUrl = "https://example.com",
            type = FilterResourceType.SCRIPT
        )
        assertEquals(FilterAction.BLOCK, engine.decide(request).action)
        assertEquals(FilterAction.BLOCK, engine.decide(request).action)
        assertTrue(engine.cosmeticCssFor("example.com").contains(".ad"))
        assertTrue(engine.scriptletJsFor("example.com").contains("window.open"))

        val snapshot = engine.perfSnapshot()
        assertTrue(snapshot.buildDurationMs >= 0L)
        assertEquals(2L, snapshot.decisionCount)
        assertTrue(snapshot.cacheMissCount >= 1L)
        assertTrue(snapshot.cacheHitCount >= 1L)
        assertTrue(snapshot.candidateEvaluationCount >= 1L)
        assertTrue(snapshot.regexEvaluationCount >= 1L)
        assertEquals(1L, snapshot.cosmeticCallCount)
        assertEquals(1L, snapshot.scriptletCallCount)
        assertTrue(snapshot.generatedCssBytes > 0L)
        assertTrue(snapshot.generatedScriptletBytes > 0L)
        assertTrue(snapshot.decisionDurationMicros.sampleCount >= 2)
        assertTrue(snapshot.candidateEvaluationsPerDecision.sampleCount >= 2)
    }

    @Test
    fun indexedDecisionMatchesLinearReferenceForCoreCases() {
        val engine = FilterEngine.build(
            listOf(
                FilterRuleSource(
                    id = "test",
                    name = "test",
                    rulesText = """
                        ||ads.example.com^
                        ||important.example.com^${'$'}important
                        @@||ads.example.com/allowed.js${'$'}script
                        /tracker/${'$'}image,third-party
                        /adserver[0-9]+/${'$'}script
                    """.trimIndent()
                )
            )
        )

        listOf(
            context("https://ads.example.com/banner.js", "https://page.example", FilterResourceType.SCRIPT),
            context("https://ads.example.com/allowed.js", "https://page.example", FilterResourceType.SCRIPT),
            context("https://important.example.com/file.js", "https://page.example", FilterResourceType.SCRIPT),
            context("https://cdn.example.net/tracker/pixel.png", "https://page.example", FilterResourceType.IMAGE),
            context("https://cdn.example.net/adserver12/file.js", "https://page.example", FilterResourceType.SCRIPT),
            context("https://cdn.example.net/content/file.css", "https://page.example", FilterResourceType.STYLESHEET)
        ).forEach { request ->
            assertIndexedMatchesLinear(engine, request)
        }
    }

    @Test
    fun generatedIndexedDecisionsMatchLinearReference() {
        val generatedRules = buildString {
            repeat(120) { index ->
                appendLine("||ads$index.example.test^${'$'}script,third-party")
                appendLine("@@||ads$index.example.test/allowed.js${'$'}script")
                appendLine("/track$index/${'$'}image,third-party")
            }
        }
        val engine = FilterEngine.build(
            listOf(FilterRuleSource("generated", "generated", generatedRules))
        )

        repeat(10_000) { index ->
            val bucket = index % 120
            val request = when (index % 5) {
                0 -> context(
                    "https://ads$bucket.example.test/banner.js?seq=$index",
                    "https://page.example",
                    FilterResourceType.SCRIPT
                )
                1 -> context(
                    "https://ads$bucket.example.test/allowed.js?seq=$index",
                    "https://page.example",
                    FilterResourceType.SCRIPT
                )
                2 -> context(
                    "https://cdn.example.test/track$bucket/pixel.png?seq=$index",
                    "https://page.example",
                    FilterResourceType.IMAGE
                )
                3 -> context(
                    "https://cdn.example.test/track$bucket/pixel.png?seq=$index",
                    "https://cdn.example.test/home",
                    FilterResourceType.IMAGE
                )
                else -> context(
                    "https://cdn.example.test/static/file$index.css",
                    "https://page.example",
                    FilterResourceType.STYLESHEET
                )
            }
            assertIndexedMatchesLinear(engine, request)
        }
    }

    @Test
    fun embeddedLiteralRulesMatchThroughIndexV2() {
        val engine = FilterEngine.build(
            listOf(
                FilterRuleSource(
                    id = "test",
                    name = "test",
                    rulesText = """
                        adsbygoogle${'$'}script
                        /banner*ad${'$'}script
                        /ads/*${'$'}image,script
                        ||googlesyndication.com^
                    """.trimIndent()
                )
            )
        )

        listOf(
            context(
                "https://pagead2.googlesyndication.com/pagead/js/adsbygoogle.js",
                "https://example.com",
                FilterResourceType.SCRIPT
            ),
            context(
                "https://cdn.example.com/static/banner123ad.js",
                "https://example.com",
                FilterResourceType.SCRIPT
            ),
            context(
                "https://cdn.example.com/assets/ads/banner.png",
                "https://example.com",
                FilterResourceType.IMAGE
            )
        ).forEach { request ->
            assertEquals(FilterAction.BLOCK, engine.decide(request).action)
            assertIndexedMatchesLinear(engine, request)
        }

        val stats = engine.perfSnapshot().blockingIndex
        assertTrue(stats.indexedRuleCount >= 4)
        assertEquals(0, stats.universalRuleCount)
    }

    @Test
    fun skipsWeakUnrestrictedSubstringRulesFromCustomLists() {
        val engine = FilterEngine.build(
            listOf(
                FilterRuleSource(
                    id = "anti-ad",
                    name = "anti-AD",
                    rulesText = """
                        Search
                        hidden
                        adsbygoogle${'$'}script
                    """.trimIndent()
                )
            )
        )

        assertEquals(
            FilterAction.ALLOW,
            engine.decide(
                context(
                    "https://static-t.720static.com/render/_next/static/images/searchBtn.png",
                    "https://www.720yun.com/",
                    FilterResourceType.IMAGE
                )
            ).action
        )
        assertEquals(
            FilterAction.ALLOW,
            engine.decide(
                context(
                    "https://qiyukf.com/script/widget.js?hidden=1",
                    "https://www.720yun.com/",
                    FilterResourceType.SCRIPT
                )
            ).action
        )
        assertEquals(
            FilterAction.BLOCK,
            engine.decide(
                context(
                    "https://pagead2.googlesyndication.com/pagead/js/adsbygoogle.js",
                    "https://example.com/",
                    FilterResourceType.SCRIPT
                )
            ).action
        )
    }

    @Test
    fun skipsRulesWithUnsupportedDnsOptionsInsteadOfIgnoringThem() {
        val engine = FilterEngine.build(
            listOf(
                FilterRuleSource(
                    id = "anti-ad",
                    name = "anti-AD",
                    rulesText = """
                        /^(\S+\.)?analytics(\-|\.)/${'$'}dnstype=A
                        ||ads.example.com^${'$'}denyallow=example.org
                    """.trimIndent()
                )
            )
        )

        assertEquals(
            FilterAction.ALLOW,
            engine.decide(context("https://analytics.example.com/pixel.gif", "https://site.example")).action
        )
        assertEquals(
            FilterAction.ALLOW,
            engine.decide(context("https://ads.example.com/banner.js", "https://site.example")).action
        )
        assertEquals(0, engine.report.networkRuleCount)
        assertTrue(engine.report.unsupportedRuleCount >= 2)
    }

    @Test
    fun regexRulesUseLiteralPrefilterWhenSafe() {
        val engine = FilterEngine.build(
            listOf(
                FilterRuleSource(
                    id = "test",
                    name = "test",
                    rulesText = "/adserver[0-9]+/${'$'}script"
                )
            )
        )

        assertEquals(
            FilterAction.ALLOW,
            engine.decide(context("https://cdn.example.com/static/app.js", "https://site.example", FilterResourceType.SCRIPT)).action
        )
        assertEquals(0L, engine.perfSnapshot().regexEvaluationCount)
        assertEquals(0, engine.perfSnapshot().blockingIndex.universalRuleCount)

        assertEquals(
            FilterAction.BLOCK,
            engine.decide(context("https://cdn.example.com/adserver12/file.js", "https://site.example", FilterResourceType.SCRIPT)).action
        )
        assertTrue(engine.perfSnapshot().regexEvaluationCount >= 1L)
    }

    @Test
    fun normalizedCacheHandlesStaticCacheBustingUrls() {
        val engine = FilterEngine.build(
            listOf(FilterRuleSource("test", "test", "||ads.example.com^${'$'}image"))
        )

        assertEquals(
            FilterAction.BLOCK,
            engine.decide(context("https://ads.example.com/banner.png?t=1", "https://site.example", FilterResourceType.IMAGE)).action
        )
        assertEquals(
            FilterAction.BLOCK,
            engine.decide(context("https://ads.example.com/banner.png?t=2", "https://site.example", FilterResourceType.IMAGE)).action
        )

        val snapshot = engine.perfSnapshot()
        assertTrue(snapshot.normalizedCacheStoreCount >= 1L)
        assertTrue(snapshot.normalizedCacheHitCount >= 1L)
        assertTrue(snapshot.cacheHitCount >= 1L)
    }

    @Test
    fun resetDiagnosticsClearsCountersAndDecisionCaches() {
        val engine = FilterEngine.build(
            listOf(FilterRuleSource("test", "test", "||ads.example.com^${'$'}image"))
        )
        val request = context("https://ads.example.com/banner.png?t=1", "https://site.example", FilterResourceType.IMAGE)

        assertEquals(FilterAction.BLOCK, engine.decide(request).action)
        assertEquals(FilterAction.BLOCK, engine.decide(request).action)
        assertTrue(engine.perfSnapshot().decisionCount > 0L)
        assertTrue(engine.perfSnapshot().cacheHitCount > 0L)

        engine.resetDiagnostics()

        val snapshot = engine.perfSnapshot()
        assertEquals(0L, snapshot.decisionCount)
        assertEquals(0L, snapshot.cacheHitCount)
        assertEquals(0L, snapshot.cacheMissCount)
        assertEquals(0L, snapshot.normalizedCacheHitCount)
        assertEquals(0L, snapshot.candidateEvaluationCount)
    }

    @Test
    fun normalizesGithubBlobSubscriptionUrlsToRawTextUrls() {
        assertEquals(
            "https://raw.githubusercontent.com/privacy-protection-tools/anti-AD/master/anti-ad-easylist.txt",
            FilterRepository.normalizeSubscriptionUrl(
                "https://github.com/privacy-protection-tools/anti-AD/blob/master/anti-ad-easylist.txt"
            )
        )
        assertEquals(
            "https://anti-ad.net/easylist.txt",
            FilterRepository.normalizeSubscriptionUrl("https://anti-ad.net/easylist.txt")
        )
    }

    @Test
    fun cosmeticAndScriptletIndexesRespectHostSuffixAndExclusions() {
        val engine = FilterEngine.build(
            listOf(
                FilterRuleSource(
                    id = "test",
                    name = "test",
                    rulesText = """
                        ##.global-ad
                        example.com##.domain-ad
                        skip.example.com#@#.global-ad
                        ~skip.example.com,example.com##.not-on-skip
                        ~skip.example.com,example.com##+js(no-window-open-if)
                    """.trimIndent()
                )
            )
        )

        val exampleCss = engine.cosmeticCssFor("www.example.com")
        assertTrue(exampleCss.contains(".global-ad"))
        assertTrue(exampleCss.contains(".domain-ad"))
        assertTrue(exampleCss.contains(".not-on-skip"))
        assertEquals(exampleCss, engine.cosmeticCssFor("www.example.com"))

        val skipCss = engine.cosmeticCssFor("skip.example.com")
        assertFalse(skipCss.contains(".global-ad"))
        assertTrue(skipCss.contains(".domain-ad"))
        assertFalse(skipCss.contains(".not-on-skip"))

        val exampleJs = engine.scriptletJsFor("www.example.com")
        assertTrue(exampleJs.contains("window.open"))
        assertEquals(exampleJs, engine.scriptletJsFor("www.example.com"))

        val skipJs = engine.scriptletJsFor("skip.example.com")
        assertFalse(skipJs.contains("window.open"))
    }

    private fun assertIndexedMatchesLinear(
        engine: FilterEngine,
        request: FilterRequestContext
    ) {
        val indexed = engine.decide(request)
        val linear = engine.decideLinearForTesting(request)
        assertEquals("action differs for ${request.requestUrl}", linear.action, indexed.action)
        assertEquals(
            "rule differs for ${request.requestUrl}",
            linear.rule?.rawText,
            indexed.rule?.rawText
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
