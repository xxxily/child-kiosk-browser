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
    fun removeParamRulesAreTransformOnlyAndExceptionsSuppressOnlyCleanup() {
        val engine = FilterEngine.build(
            listOf(
                FilterRuleSource(
                    id = "test",
                    name = "test",
                    rulesText = """
                        *${'$'}removeparam=utm_source|fbclid
                        @@||safe.example^${'$'}removeparam=utm_source
                    """.trimIndent()
                )
            )
        )

        assertEquals(
            FilterAction.ALLOW,
            engine.decide(context("https://other.example/pixel?utm_source=x", "https://top.example")).action
        )
        assertEquals(0, engine.report.networkRuleCount)
        assertEquals(
            "https://other.example/page?keep=1",
            engine.cleanUrlForNavigation(
                "https://other.example/page?utm_source=x&fbclid=y&keep=1",
                "https://other.example/"
            )
        )
        assertEquals(
            "https://safe.example/page?utm_source=x&keep=1",
            engine.cleanUrlForNavigation(
                "https://safe.example/page?utm_source=x&fbclid=y&keep=1",
                "https://safe.example/"
            )
        )
    }

    @Test
    fun removeParamCleanupPreservesUntouchedRawBytesAndSkipsSensitiveNavigations() {
        val engine = FilterEngine.build(
            listOf(FilterRuleSource("test", "test", "*${'$'}removeparam=utm_source"))
        )
        val rawUrl =
            "https://example.com/a%2Fb%20c/%25?utm_source=x&keep=%2F%20%25&empty=&repeat=1&repeat=2#frag%2F%20%25"

        assertEquals(
            "https://example.com/a%2Fb%20c/%25?keep=%2F%20%25&empty=&repeat=1&repeat=2#frag%2F%20%25",
            engine.cleanUrlForNavigation(rawUrl, "https://example.com/")
        )
        assertNull(engine.cleanUrlForNavigation(rawUrl, "https://example.com/", method = "POST"))
        assertNull(
            engine.cleanUrlForNavigation(
                rawUrl,
                "https://example.com/",
                isMainFrame = false
            )
        )
        assertNull(
            engine.cleanUrlForNavigation(
                rawUrl,
                "https://example.com/",
                siteOverride = SiteFilterOverride(host = "example.com", networkDisabled = true)
            )
        )
        assertNull(
            engine.cleanUrlForNavigation(
                rawUrl,
                "https://example.com/",
                siteOverride = SiteFilterOverride(
                    host = "example.com",
                    temporaryAllowUntil = System.currentTimeMillis() + 60_000L
                )
            )
        )
        assertNull(
            engine.cleanUrlForNavigation(
                "https://example.com/callback?utm_source=x&code=secret&keep=1",
                "https://example.com/"
            )
        )
        assertNull(
            engine.cleanUrlForNavigation(
                "https://storage.example/file?utm_source=x&X-Amz-Signature=secret",
                "https://storage.example/"
            )
        )
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
    fun badfilterCanonicalizationIgnoresCaseAndOptionOrDomainOrdering() {
        val engine = FilterEngine.build(
            listOf(
                FilterRuleSource(
                    id = "test",
                    name = "test",
                    rulesText = """
                        ||ADS.EXAMPLE.COM^${'$'}script,third-party,domain=b.example|a.example
                        ||ads.example.com^${'$'}DOMAIN=a.example|B.EXAMPLE,THIRD-PARTY,SCRIPT,BADFILTER
                    """.trimIndent()
                )
            )
        )

        assertEquals(
            FilterAction.ALLOW,
            engine.decide(
                context(
                    "https://ads.example.com/banner.js",
                    "https://a.example/page",
                    FilterResourceType.SCRIPT
                )
            ).action
        )
        assertEquals(0, engine.report.networkRuleCount)
    }

    @Test
    fun parentAndSubscriptionExceptionsPrecedeImportantBlocks() {
        val engine = FilterEngine.build(
            listOf(
                FilterRuleSource(
                    id = "subscription",
                    name = "subscription",
                    rulesText = """
                        ||priority.example^${'$'}important
                        @@||priority.example/subscription^
                    """.trimIndent()
                ),
                FilterRuleSource(
                    id = "custom",
                    name = "parent",
                    rulesText = "@@||priority.example^"
                )
            )
        )

        val parentDecision = engine.decide(
            context("https://priority.example/subscription/file.js", "https://top.example")
        )
        assertEquals(FilterAction.EXCEPTION, parentDecision.action)
        assertEquals("custom", parentDecision.rule?.sourceId)

        val subscriptionOnly = FilterEngine.build(
            listOf(
                FilterRuleSource(
                    id = "subscription",
                    name = "subscription",
                    rulesText = """
                        ||priority.example^${'$'}important
                        @@||priority.example^${'$'}script
                    """.trimIndent()
                )
            )
        )
        assertEquals(
            FilterAction.EXCEPTION,
            subscriptionOnly.decide(
                context(
                    "https://priority.example/file.js",
                    "https://top.example",
                    FilterResourceType.SCRIPT
                )
            ).action
        )
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
                        adserver*file.js${'$'}script
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
                        tracker/pixel${'$'}image,third-party
                        adserver*file.js${'$'}script
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
                appendLine("track$index/pixel${'$'}image,third-party")
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
    fun strictHostsParsingDoesNotConsumeAdblockNetworkRules() {
        val engine = FilterEngine.build(
            listOf(
                FilterRuleSource(
                    id = "test",
                    name = "test",
                    rulesText = """
                        0.0.0.0 mapped-host.example
                        standalone-host.example
                        .ads.controller.js${'$'}script
                        -ad-sidebar.${'$'}image
                    """.trimIndent()
                )
            )
        )

        assertEquals(
            FilterAction.BLOCK,
            engine.decide(
                context(
                    "https://cdn.example/assets/.ads.controller.js",
                    "https://top.example",
                    FilterResourceType.SCRIPT
                )
            ).action
        )
        assertEquals(
            FilterAction.BLOCK,
            engine.decide(
                context(
                    "https://cdn.example/images/-ad-sidebar.png",
                    "https://top.example",
                    FilterResourceType.IMAGE
                )
            ).action
        )
        assertEquals(
            FilterAction.BLOCK,
            engine.decide(context("https://mapped-host.example/file", "https://top.example")).action
        )
        assertEquals(4, engine.report.networkRuleCount)
    }

    @Test
    fun unknownCosmeticMarkerFamiliesNeverFallThroughToNetworkParsing() {
        val engine = FilterEngine.build(
            listOf(
                FilterRuleSource(
                    id = "test",
                    name = "test",
                    rulesText = """
                        example.com#?#.ad
                        example.com#@?#.ad
                        example.com#${'$'}#.ad
                        example.com#@${'$'}#.ad
                        example.com#%#.ad
                        example.com##+js
                    """.trimIndent()
                )
            )
        )

        assertEquals(0, engine.report.networkRuleCount)
        assertEquals(0, engine.report.cosmeticRuleCount)
        assertEquals(6, engine.report.unsupportedRuleCount)
    }

    @Test
    fun anchorsWildcardsAndSeparatorsRetainIndependentSemantics() {
        val exact = FilterEngine.build(
            listOf(FilterRuleSource("exact", "exact", "|https://cdn.test/foo|"))
        )
        assertEquals(
            FilterAction.BLOCK,
            exact.decide(context("https://cdn.test/foo", "https://top.test")).action
        )
        assertEquals(
            FilterAction.ALLOW,
            exact.decide(context("https://cdn.test/foo?x=1", "https://top.test")).action
        )

        val domainPath = FilterEngine.build(
            listOf(FilterRuleSource("domain", "domain", "||example.com/foo^"))
        )
        assertEquals(
            FilterAction.BLOCK,
            domainPath.decide(context("https://sub.example.com/foo?x=1", "https://top.test")).action
        )
        assertEquals(
            FilterAction.ALLOW,
            domainPath.decide(context("https://example.com/?next=/foo", "https://top.test")).action
        )
        assertEquals(
            FilterAction.ALLOW,
            domainPath.decide(context("https://example.com/foobar", "https://top.test")).action
        )

        val domainWildcard = FilterEngine.build(
            listOf(FilterRuleSource("wildcard", "wildcard", "||example.com/ad*banner^"))
        )
        assertEquals(
            FilterAction.BLOCK,
            domainWildcard.decide(
                context("https://example.com/ad/path/banner?x=1", "https://top.test")
            ).action
        )
        assertEquals(
            FilterAction.ALLOW,
            domainWildcard.decide(context("https://example.com/ad/path/content", "https://top.test")).action
        )

        val schemeWildcard = FilterEngine.build(
            listOf(FilterRuleSource("scheme", "scheme", "|http://*example.net^"))
        )
        assertEquals(
            FilterAction.BLOCK,
            schemeWildcard.decide(context("http://sub.example.net/path", "https://top.test")).action
        )
        assertEquals(
            FilterAction.ALLOW,
            schemeWildcard.decide(context("https://sub.example.net/path", "https://top.test")).action
        )

        val idnDomain = FilterEngine.build(
            listOf(FilterRuleSource("idn", "idn", "||bücher.de^"))
        )
        assertEquals(
            FilterAction.BLOCK,
            idnDomain.decide(context("https://xn--bcher-kva.de/path", "https://top.test")).action
        )
    }

    @Test
    fun rawRegexRulesAreExplicitlyUnsupportedWithoutRe2j() {
        val engine = FilterEngine.build(
            listOf(
                FilterRuleSource(
                    id = "test",
                    name = "test",
                    rulesText = "/foo${'$'}/"
                )
            )
        )

        assertEquals(
            FilterAction.ALLOW,
            engine.decide(context("https://cdn.example.com/static/app.js", "https://site.example", FilterResourceType.SCRIPT)).action
        )
        assertEquals(0L, engine.perfSnapshot().regexEvaluationCount)
        assertEquals(0, engine.report.networkRuleCount)
        assertEquals(1, engine.report.unsupportedRuleCount)
        assertEquals(
            FilterAction.ALLOW,
            engine.decide(context("https://cdn.example.com/foo", "https://site.example", FilterResourceType.SCRIPT)).action
        )
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
    fun resourceTypeInferenceIgnoresQueryAndFragmentForFileExtensions() {
        assertEquals(
            FilterResourceType.SCRIPT,
            FilterResourceType.infer("https://cdn.example.com/app.js?client=1", null, false)
        )
        assertEquals(
            FilterResourceType.STYLESHEET,
            FilterResourceType.infer("https://cdn.example.com/app.css?v=1#top", null, false)
        )
        assertEquals(
            FilterResourceType.IMAGE,
            FilterResourceType.infer("https://cdn.example.com/banner.png?t=1", null, false)
        )
        assertEquals(
            FilterResourceType.FONT,
            FilterResourceType.infer("https://cdn.example.com/font.woff2?v=1", null, false)
        )
        assertEquals(
            FilterResourceType.MEDIA,
            FilterResourceType.infer("https://cdn.example.com/video.m3u8?token=1", null, false)
        )
    }

    @Test
    fun resourceTypeInferenceUsesFetchHeadersMethodAndFrameSignals() {
        assertEquals(
            FilterResourceType.SUBDOCUMENT,
            FilterResourceType.infer(
                url = "https://frame.example/page",
                acceptHeader = "text/html",
                isMainFrame = false,
                requestHeaders = mapOf("Sec-Fetch-Dest" to "iframe")
            )
        )
        assertEquals(
            FilterResourceType.XMLHTTPREQUEST,
            FilterResourceType.infer(
                url = "https://api.example/data",
                acceptHeader = "application/json",
                isMainFrame = false,
                requestHeaders = mapOf(
                    "Sec-Fetch-Dest" to "empty",
                    "Sec-Fetch-Mode" to "cors"
                )
            )
        )
        assertEquals(
            FilterResourceType.PING,
            FilterResourceType.infer(
                url = "https://metrics.example/collect",
                acceptHeader = "*/*",
                isMainFrame = false,
                requestHeaders = mapOf("Ping-To" to "https://metrics.example/collect"),
                method = "POST"
            )
        )
        assertEquals(
            FilterResourceType.DOCUMENT,
            FilterResourceType.infer(
                url = "https://main.example/",
                acceptHeader = "text/html",
                isMainFrame = true,
                requestHeaders = mapOf("Sec-Fetch-Dest" to "iframe")
            )
        )
    }

    @Test
    fun shouldBlockPerfRecordsSegmentedTimingsAndSlowSamples() {
        val engine = FilterEngine.build(
            listOf(FilterRuleSource("test", "test", "||ads.example.com^${'$'}script"))
        )

        engine.recordShouldBlockDuration(
            totalNanos = 25_000_000L,
            parseNanos = 2_000_000L,
            engineNanos = 12_000_000L,
            eventNanos = 3_000_000L,
            snapshotNanos = 8_000_000L,
            resourceType = FilterResourceType.SCRIPT,
            action = FilterAction.BLOCK,
            url = "https://ads.example.com/banner.js?x=1",
            ruleText = "||ads.example.com^",
            cacheStatus = "cache-miss",
            candidateCount = 7
        )

        val snapshot = engine.perfSnapshot()
        assertEquals(1, snapshot.shouldBlockDurationMicros.sampleCount)
        assertEquals(1, snapshot.shouldBlockParseDurationMicros.sampleCount)
        assertEquals(1, snapshot.shouldBlockEngineDurationMicros.sampleCount)
        assertEquals(1, snapshot.shouldBlockEventDurationMicros.sampleCount)
        assertEquals(1, snapshot.shouldBlockSnapshotDurationMicros.sampleCount)
        assertEquals(25_000L, snapshot.shouldBlockDurationMicros.max)
        assertEquals(12_000L, snapshot.shouldBlockEngineDurationMicros.max)
        assertEquals(1, snapshot.slowShouldBlockSamples.size)
        val sample = snapshot.slowShouldBlockSamples.single()
        assertEquals(25_000L, sample.durationMicros)
        assertEquals(2_000L, sample.parseMicros)
        assertEquals(12_000L, sample.engineMicros)
        assertEquals(3_000L, sample.eventMicros)
        assertEquals(8_000L, sample.snapshotMicros)
        assertEquals("script", sample.resourceType)
        assertEquals("BLOCK", sample.action)
        assertEquals("cache-miss", sample.cacheStatus)
        assertEquals(7, sample.candidateCount)
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

    @Test
    fun cosmeticSelectorPolicyRejectsStylesheetEscapesAndCountsThemUnsupported() {
        val unsafeSelectors = listOf(
            ".bad{}",
            "@import url(https://collector.test/a)",
            ".bad[url(https://collector.test/b)]",
            ".bad/*comment*/",
            ".bad\\7b color:red",
            "div:has(.ad)",
            ".bad\u0001control"
        )
        unsafeSelectors.forEach { selector ->
            assertFalse("selector should be rejected: $selector", CssSelectorPolicy.isAllowed(selector))
        }
        assertTrue(CssSelectorPolicy.isAllowed("div.ad > [class*=\"sponsor\"]"))

        val rules = buildString {
            appendLine("##.safe-ad")
            unsafeSelectors.forEach { selector -> appendLine("##$selector") }
        }
        val engine = FilterEngine.build(listOf(FilterRuleSource("test", "test", rules)))
        val matches = engine.cosmeticMatchesFor("example.com")

        assertEquals(listOf(".safe-ad"), matches.map { it.selector })
        assertEquals(unsafeSelectors.size, engine.report.unsupportedRuleCount)
    }

    @Test
    fun cosmeticSelectorLimitKeepsSiteRulesAheadOfGlobalRules() {
        val rules = buildString {
            repeat(805) { index -> appendLine("##.global-$index") }
            appendLine("example.com##.site-priority")
        }
        val engine = FilterEngine.build(listOf(FilterRuleSource("test", "test", rules)))

        val matches = engine.cosmeticMatchesFor("www.example.com")
        assertEquals(800, matches.size)
        assertEquals(".site-priority", matches.first().selector)
        assertTrue(matches.any { it.selector == ".site-priority" })
    }

    @Test
    fun partyClassificationUsesPslIdnAndExactLocalHostSemantics() {
        assertFalse(isThirdPartyHost("cdn.family.co.jp", "www.family.co.jp"))
        assertTrue(isThirdPartyHost("cdn.family.co.jp", "www.other.co.jp"))

        assertFalse(isThirdPartyHost("assets.child.github.io", "child.github.io"))
        assertTrue(isThirdPartyHost("child.github.io", "other.github.io"))
        assertFalse(isThirdPartyHost("cdn.child.appspot.com", "child.appspot.com"))
        assertTrue(isThirdPartyHost("child.appspot.com", "other.appspot.com"))

        assertFalse(isThirdPartyHost("cdn.bücher.de", "www.xn--bcher-kva.de"))
        assertFalse(isThirdPartyHost("127.0.0.1", "127.0.0.1"))
        assertTrue(isThirdPartyHost("127.0.0.1", "127.0.0.2"))
        assertFalse(isThirdPartyHost("localhost", "localhost"))
        assertTrue(isThirdPartyHost("child.localhost", "localhost"))
    }

    @Test
    fun longUrlsStillUseDomainExceptionImportantAndBlockingRulesWithoutCachingWholeUrl() {
        val engine = FilterEngine.build(
            listOf(
                FilterRuleSource(
                    id = "test",
                    name = "test",
                    rulesText = """
                        ||important-long.example^${'$'}important
                        @@||allowed-long.example^
                        ||blocked-long.example^
                    """.trimIndent()
                )
            )
        )

        val tenKiB = "x".repeat(10 * 1024)
        val hundredKiB = "y".repeat(100 * 1024)
        val important = engine.decide(
            context("https://important-long.example/path?padding=$tenKiB", "https://top.example")
        )
        val exception = engine.decide(
            context("https://allowed-long.example/path?padding=$hundredKiB", "https://top.example")
        )
        val blocked = engine.decide(
            context("https://blocked-long.example/path?padding=$hundredKiB", "https://top.example")
        )

        assertEquals(FilterAction.BLOCK, important.action)
        assertEquals(FilterAction.EXCEPTION, exception.action)
        assertEquals(FilterAction.BLOCK, blocked.action)
        assertEquals("oversized-url-partial", important.diagnostics?.cacheStatus)
        assertEquals("oversized-url-partial", exception.diagnostics?.cacheStatus)
        assertEquals("oversized-url-partial", blocked.diagnostics?.cacheStatus)
        assertEquals(0L, engine.perfSnapshot().cacheHitCount)
    }

    @Test
    fun generatedMatcherStopsAtExplicitCharacterBudget() {
        val budget = AdblockMatchBudget(maxSteps = 64)

        val result = AdblockPatternMatcher.matches(
            target = "https://example.test/" + "a".repeat(8 * 1024),
            pattern = "aaaaab*aaaaac",
            startAnchored = false,
            endAnchored = false,
            budget = budget
        )

        assertEquals(AdblockMatchResult.BUDGET_EXHAUSTED, result)
        assertEquals(64, budget.consumedSteps)
    }

    @Test
    fun adversarialSharedTokenCorpusFailsSafeWithinDecisionBudget() {
        val rules = buildString {
            repeat(2_100) { index ->
                appendLine("share*${"a".repeat(480)}z$index${'$'}script")
            }
        }
        val engine = FilterEngine.build(
            listOf(FilterRuleSource("adversarial", "adversarial", rules))
        )

        val decision = engine.decide(
            context(
                url = "https://cdn.example.test/shared/" + "a".repeat(7 * 1024),
                topLevelUrl = "https://page.example.test",
                type = FilterResourceType.SCRIPT
            )
        )

        assertEquals(FilterAction.BLOCK, decision.action)
        assertTrue(decision.reason.contains("filter match budget exhausted"))
        assertTrue(decision.diagnostics?.matchedStage.orEmpty().endsWith("budget-exhausted"))
        assertTrue(decision.diagnostics?.candidateCount.orZero() <= 2_048)
        assertEquals(1L, engine.perfSnapshot().matchBudgetExhaustionCount)
    }

    @Test
    fun parserRejectsExcessiveSingleRuleMatcherComplexity() {
        val overlong = "token" + "a".repeat(508)
        val wildcardHeavy = "token" + "*a".repeat(17)
        val engine = FilterEngine.build(
            listOf(
                FilterRuleSource(
                    "complexity",
                    "complexity",
                    "$overlong\n$wildcardHeavy"
                )
            )
        )

        assertEquals(0, engine.report.enabledRuleCount)
        assertEquals(2, engine.report.unsupportedRuleCount)
    }

    @Test
    fun parserRejectsExcessiveOptionAndDomainScopes() {
        val tooManyOptions = "||ads.example^${'$'}" +
            (0 until 33).joinToString(",") { "unsupported-$it" }
        val tooManyDomains = "||ads.example^${'$'}domain=" +
            (0 until 65).joinToString("|") { "d$it.example" }
        val tooManyCosmeticDomains =
            (0 until 65).joinToString(",") { "c$it.example" } + "##.ad"
        val engine = FilterEngine.build(
            listOf(
                FilterRuleSource(
                    "option-limits",
                    "option-limits",
                    "$tooManyOptions\n$tooManyDomains\n$tooManyCosmeticDomains"
                )
            )
        )

        assertEquals(0, engine.report.enabledRuleCount)
        assertEquals(3, engine.report.unsupportedRuleCount)
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

    private fun Int?.orZero(): Int = this ?: 0
}
