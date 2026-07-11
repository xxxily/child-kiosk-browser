package site.anzz.childkiosk.util.filter

import java.util.Locale
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicLongArray

data class FilterBuildReport(
    val ruleCount: Int,
    val enabledRuleCount: Int,
    val unsupportedRuleCount: Int,
    val networkRuleCount: Int = 0,
    val cosmeticRuleCount: Int = 0,
    val scriptletRuleCount: Int = 0,
    val sourceReports: List<FilterSourceReport>,
    val errors: List<String>
)

data class FilterSourceReport(
    val sourceId: String,
    val sourceName: String,
    val totalLines: Int,
    val enabledRules: Int,
    val unsupportedRules: Int,
    val networkRules: Int = 0,
    val cosmeticRules: Int = 0,
    val scriptletRules: Int = 0,
    val errors: List<String>
)

data class FilterIndexStats(
    val tokenBucketCount: Int,
    val indexedRuleCount: Int,
    val universalRuleCount: Int
)

data class FilterPerfSampleStats(
    val sampleCount: Int,
    val p50: Long,
    val p95: Long,
    val p99: Long,
    val max: Long
)

data class FilterPerfSnapshot(
    val buildDurationMs: Long,
    val decisionCount: Long,
    val cacheHitCount: Long,
    val cacheMissCount: Long,
    val normalizedCacheHitCount: Long,
    val normalizedCacheStoreCount: Long,
    val normalizedCacheBypassCount: Long,
    val candidateEvaluationCount: Long,
    val regexEvaluationCount: Long,
    val matchBudgetExhaustionCount: Long,
    val cosmeticCallCount: Long,
    val scriptletCallCount: Long,
    val generatedCssBytes: Long,
    val generatedScriptletBytes: Long,
    val shouldBlockDurationMicros: FilterPerfSampleStats,
    val shouldBlockParseDurationMicros: FilterPerfSampleStats,
    val shouldBlockEngineDurationMicros: FilterPerfSampleStats,
    val shouldBlockEventDurationMicros: FilterPerfSampleStats,
    val shouldBlockSnapshotDurationMicros: FilterPerfSampleStats,
    val decisionDurationMicros: FilterPerfSampleStats,
    val candidateEvaluationsPerDecision: FilterPerfSampleStats,
    val cosmeticDurationMicros: FilterPerfSampleStats,
    val scriptletDurationMicros: FilterPerfSampleStats,
    val importantIndex: FilterIndexStats,
    val exceptionIndex: FilterIndexStats,
    val blockingIndex: FilterIndexStats,
    val removeParamIndex: FilterIndexStats,
    val slowShouldBlockSamples: List<FilterSlowShouldBlockSample>
)

data class FilterSlowShouldBlockSample(
    val timestamp: Long,
    val durationMicros: Long,
    val parseMicros: Long,
    val engineMicros: Long,
    val eventMicros: Long,
    val snapshotMicros: Long,
    val resourceType: String,
    val action: String,
    val url: String,
    val ruleText: String,
    val cacheStatus: String,
    val candidateCount: Int
)

class FilterEngine private constructor(
    private val importantIndex: TokenIndex,
    private val exceptionIndex: TokenIndex,
    private val blockingIndex: TokenIndex,
    private val removeParamIndex: TokenIndex,
    private val removeParamExceptionIndex: TokenIndex,
    private val cosmeticRules: List<CosmeticFilterRule>,
    private val scriptletRules: List<ScriptletFilterRule>,
    val report: FilterBuildReport,
    private val buildDurationMs: Long,
    private val hasQuerySensitiveNetworkRules: Boolean
) {
    private val decisionCache = ConcurrentHashMap<String, FilterDecision>(2048, 0.75f, 4)
    private val normalizedDecisionCache = ConcurrentHashMap<String, FilterDecision>(1024, 0.75f, 4)
    private val cosmeticCssCache = ConcurrentHashMap<String, String>(64)
    private val cosmeticMatchesCache = ConcurrentHashMap<String, List<CosmeticFilterMatch>>(64)
    private val scriptletJsCache = ConcurrentHashMap<String, String>(64)
    private val maxCacheSize = 4096
    private val maxNormalizedCacheSize = 2048
    private val maxHostCacheSize = 256
    private val perf = FilterPerfTracker(buildDurationMs)
    private val cosmeticIndex = CosmeticIndex(cosmeticRules)
    private val scriptletIndex = ScriptletIndex(scriptletRules)

    private fun cacheDecision(key: String?, decision: FilterDecision): FilterDecision {
        if (key == null) return decision
        if (decisionCache.size > maxCacheSize) decisionCache.clear()
        decisionCache[key] = decision
        return decision
    }

    private fun cacheDecision(
        key: String?,
        normalizedKey: String?,
        decision: FilterDecision
    ): FilterDecision {
        cacheDecision(key, decision)
        if (normalizedKey != null && canStoreInNormalizedCache(decision)) {
            if (normalizedDecisionCache.size > maxNormalizedCacheSize) normalizedDecisionCache.clear()
            normalizedDecisionCache[normalizedKey] = decision
            perf.recordNormalizedCacheStore()
        }
        return decision
    }

    fun resetDiagnostics() {
        decisionCache.clear()
        normalizedDecisionCache.clear()
        cosmeticCssCache.clear()
        cosmeticMatchesCache.clear()
        scriptletJsCache.clear()
        perf.reset()
    }

    fun perfSnapshot(): FilterPerfSnapshot {
        return perf.snapshot(
            importantIndex = importantIndex.stats(),
            exceptionIndex = exceptionIndex.stats(),
            blockingIndex = blockingIndex.stats(),
            removeParamIndex = removeParamIndex.stats()
        )
    }

    fun recordShouldBlockDuration(
        totalNanos: Long,
        parseNanos: Long = 0L,
        engineNanos: Long = 0L,
        eventNanos: Long = 0L,
        snapshotNanos: Long = 0L,
        resourceType: FilterResourceType? = null,
        action: FilterAction? = null,
        url: String = "",
        ruleText: String = "",
        cacheStatus: String = "",
        candidateCount: Int = 0
    ) {
        perf.recordShouldBlock(
            totalNanos = totalNanos,
            parseNanos = parseNanos,
            engineNanos = engineNanos,
            eventNanos = eventNanos,
            snapshotNanos = snapshotNanos,
            resourceType = resourceType,
            action = action,
            url = url,
            ruleText = ruleText,
            cacheStatus = cacheStatus,
            candidateCount = candidateCount
        )
    }

    internal fun decideLinearForTesting(
        context: FilterRequestContext,
        siteOverride: SiteFilterOverride? = null
    ): FilterDecision {
        if (siteOverride?.isTemporarilyAllowed() == true || siteOverride?.networkDisabled == true) {
            return FilterDecision(FilterAction.EXCEPTION, reason = "site override")
        }
        if (context.requestUrl.isBlank()) return FilterDecision.ALLOW

        val exception = exceptionIndex.linearFirstMatching(context)
        if (exception != null) {
            return FilterDecision(FilterAction.EXCEPTION, exception.rule, "exception rule")
        }

        val importantBlock = importantIndex.linearFirstMatching(context)
        if (importantBlock != null) {
            return FilterDecision(FilterAction.BLOCK, importantBlock.rule, "important rule")
        }

        val block = blockingIndex.linearFirstMatching(context)
        return if (block != null) {
            FilterDecision(FilterAction.BLOCK, block.rule, "blocking rule")
        } else {
            FilterDecision.ALLOW
        }
    }

    fun cosmeticCssFor(host: String, siteOverride: SiteFilterOverride? = null): String {
        val startedAt = System.nanoTime()
        var result = ""
        try {
            if (siteOverride?.isTemporarilyAllowed() == true || siteOverride?.cosmeticDisabled == true) return result
            val normalizedHost = host.normalizeHost()
            if (normalizedHost.isBlank()) return result
            cosmeticCssCache[normalizedHost]?.let {
                result = it
                return result
            }
            result = cosmeticCssFromMatches(cachedCosmeticMatchesFor(normalizedHost))
            if (cosmeticCssCache.size > maxHostCacheSize) cosmeticCssCache.clear()
            cosmeticCssCache[normalizedHost] = result
            return result
        } finally {
            perf.recordCosmetic(System.nanoTime() - startedAt, result.length)
        }
    }

    fun cosmeticMatchesFor(
        host: String,
        siteOverride: SiteFilterOverride? = null
    ): List<CosmeticFilterMatch> {
        val startedAt = System.nanoTime()
        var result: List<CosmeticFilterMatch> = emptyList()
        try {
            if (siteOverride?.isTemporarilyAllowed() == true || siteOverride?.cosmeticDisabled == true) return result
            val normalizedHost = host.normalizeHost()
            if (normalizedHost.isBlank()) return result
            cosmeticMatchesCache[normalizedHost]?.let {
                result = it
                return result
            }
            result = cachedCosmeticMatchesFor(normalizedHost)
            return result
        } finally {
            perf.recordCosmetic(System.nanoTime() - startedAt, result.sumOf { it.selector.length })
        }
    }

    private fun cachedCosmeticMatchesFor(normalizedHost: String): List<CosmeticFilterMatch> {
        cosmeticMatchesCache[normalizedHost]?.let { return it }
        val matches = cosmeticIndex.matchesFor(normalizedHost)
        if (cosmeticMatchesCache.size > maxHostCacheSize) cosmeticMatchesCache.clear()
        cosmeticMatchesCache[normalizedHost] = matches
        return matches
    }

    private fun cosmeticCssFromMatches(matches: List<CosmeticFilterMatch>): String {
        if (matches.isEmpty()) return ""
        return matches.joinToString(",\n") { it.selector } +
            " { display: none !important; visibility: hidden !important; }"
    }

    fun scriptletJsFor(host: String, siteOverride: SiteFilterOverride? = null): String {
        val startedAt = System.nanoTime()
        var result = ""
        try {
            if (siteOverride?.isTemporarilyAllowed() == true || siteOverride?.scriptletDisabled == true) return result
            val normalizedHost = host.normalizeHost()
            if (normalizedHost.isBlank()) return result
            scriptletJsCache[normalizedHost]?.let {
                result = it
                return result
            }
            result = scriptletIndex.jsFor(normalizedHost)
            if (scriptletJsCache.size > maxHostCacheSize) scriptletJsCache.clear()
            scriptletJsCache[normalizedHost] = result
            return result
        } finally {
            perf.recordScriptlet(System.nanoTime() - startedAt, result.length)
        }
    }

    fun cleanUrlForNavigation(
        url: String,
        topLevelUrl: String,
        method: String = "GET",
        isMainFrame: Boolean = true,
        siteOverride: SiteFilterOverride? = null
    ): String? {
        if (siteOverride?.isTemporarilyAllowed() == true || siteOverride?.networkDisabled == true) return null
        if (!isMainFrame || !method.equals("GET", ignoreCase = true)) return null
        if (
            url.isBlank() ||
            !(url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true))
        ) {
            return null
        }
        val rawQuery = FilterUrlTransform.parse(url) ?: return null
        if (rawQuery.hasSensitiveAuthenticationParameter()) return null
        val context = FilterRequestContext(
            requestUrl = url,
            topLevelUrl = topLevelUrl.ifBlank { url },
            resourceType = FilterResourceType.DOCUMENT,
            isMainFrame = true,
            method = method.uppercase(Locale.US),
            hasGesture = false
        )
        val oversized = url.length > MAX_EXPENSIVE_URL_LENGTH
        val budget = FilterDecisionMatchBudget()
        val removeResult = removeParamIndex.matchingRemoveParams(
            url = url,
            host = context.requestHost,
            context = context,
            perf = perf,
            budget = budget,
            domainOnly = oversized
        )
        if (removeResult.limit != null) {
            perf.recordMatchBudgetExhaustion()
            return null
        }
        val exceptionResult = removeParamExceptionIndex.matchingRemoveParams(
            url = url,
            host = context.requestHost,
            context = context,
            perf = perf,
            budget = budget,
            domainOnly = oversized
        )
        if (exceptionResult.limit != null) {
            perf.recordMatchBudgetExhaustion()
            return null
        }
        val paramsToRemove = removeResult.params
        if (paramsToRemove.isEmpty()) return null
        val exceptedParams = exceptionResult.params
        return rawQuery.removeParams(
            includedParams = paramsToRemove,
            exceptedParams = exceptedParams
        )
    }

    fun decide(context: FilterRequestContext, siteOverride: SiteFilterOverride? = null): FilterDecision {
        val startedAt = System.nanoTime()
        var evaluatedCandidates = 0
        try {
            if (siteOverride?.isTemporarilyAllowed() == true || siteOverride?.networkDisabled == true) {
                return FilterDecision(FilterAction.EXCEPTION, reason = "site override")
            }
            if (context.requestUrl.isBlank()) return FilterDecision.ALLOW
            val oversized = context.requestUrl.length > MAX_EXPENSIVE_URL_LENGTH
            val cacheKey = if (oversized) {
                null
            } else {
                "${context.requestUrl}|${context.topLevelHost}|${context.resourceType}|${context.isThirdParty}"
            }
            if (cacheKey != null) {
                decisionCache[cacheKey]?.let {
                    perf.recordCacheHit()
                    return it.withCacheStatus("full-cache-hit")
                }
            }
            val normalizedCacheKey = if (oversized) null else normalizedDecisionCacheKey(context)
            if (normalizedCacheKey == null) {
                perf.recordNormalizedCacheBypass()
            } else {
                normalizedDecisionCache[normalizedCacheKey]?.let {
                    perf.recordCacheHit()
                    perf.recordNormalizedCacheHit()
                    return it.withCacheStatus("normalized-cache-hit")
                }
            }
            perf.recordCacheMiss()

            val url = context.requestUrl
            val host = context.requestHost
            val cacheStatus = if (oversized) "oversized-url-partial" else "cache-miss"
            val matchBudget = FilterDecisionMatchBudget()

            val exceptionMatch = exceptionIndex.firstMatching(
                url = url,
                host = host,
                context = context,
                perf = perf,
                budget = matchBudget,
                domainOnly = oversized
            )
            evaluatedCandidates += exceptionMatch.evaluatedCount
            val exception = exceptionMatch.rule
            if (exception != null) {
                return cacheDecision(
                    cacheKey,
                    normalizedCacheKey,
                    FilterDecision(
                        FilterAction.EXCEPTION,
                        exception.rule,
                        "exception rule",
                        diagnostics = diagnosticsFor(
                            stage = "exception",
                            candidateCount = evaluatedCandidates,
                            cacheStatus = cacheStatus,
                            rule = exception
                        )
                    )
                )
            }
            if (exceptionMatch.limit != null) {
                return cacheDecision(
                    cacheKey,
                    normalizedCacheKey,
                    budgetExhaustedDecision("exception", evaluatedCandidates, exceptionMatch.limit)
                )
            }

            val importantMatch = importantIndex.firstMatching(
                url = url,
                host = host,
                context = context,
                perf = perf,
                budget = matchBudget,
                domainOnly = oversized
            )
            evaluatedCandidates += importantMatch.evaluatedCount
            val importantBlock = importantMatch.rule
            if (importantBlock != null) {
                return cacheDecision(
                    cacheKey,
                    normalizedCacheKey,
                    FilterDecision(
                        FilterAction.BLOCK,
                        importantBlock.rule,
                        "important rule",
                        diagnostics = diagnosticsFor(
                            stage = "important",
                            candidateCount = evaluatedCandidates,
                            cacheStatus = cacheStatus,
                            rule = importantBlock
                        )
                    )
                )
            }
            if (importantMatch.limit != null) {
                return cacheDecision(
                    cacheKey,
                    normalizedCacheKey,
                    budgetExhaustedDecision("important", evaluatedCandidates, importantMatch.limit)
                )
            }

            val blockMatch = blockingIndex.firstMatching(
                url = url,
                host = host,
                context = context,
                perf = perf,
                budget = matchBudget,
                domainOnly = oversized
            )
            evaluatedCandidates += blockMatch.evaluatedCount
            val block = blockMatch.rule
            val decision = if (blockMatch.limit != null) {
                budgetExhaustedDecision("blocking", evaluatedCandidates, blockMatch.limit)
            } else if (block != null) {
                FilterDecision(
                    FilterAction.BLOCK,
                    block.rule,
                    "blocking rule",
                    diagnostics = diagnosticsFor(
                        stage = "blocking",
                        candidateCount = evaluatedCandidates,
                        cacheStatus = cacheStatus,
                        rule = block
                    )
                )
            } else {
                FilterDecision(
                    FilterAction.ALLOW,
                    diagnostics = diagnosticsFor(
                        stage = "allow",
                        candidateCount = evaluatedCandidates,
                        cacheStatus = cacheStatus,
                        rule = null
                    )
                )
            }
            return cacheDecision(cacheKey, normalizedCacheKey, decision)
        } finally {
            perf.recordDecision(System.nanoTime() - startedAt, evaluatedCandidates)
        }
    }

    private fun diagnosticsFor(
        stage: String,
        candidateCount: Int,
        cacheStatus: String,
        rule: CompiledRule?
    ): FilterDecisionDiagnostics {
        return FilterDecisionDiagnostics(
            candidateCount = candidateCount,
            matchedStage = stage,
            cacheStatus = cacheStatus,
            ruleMatchType = rule?.rule?.matchType?.name.orEmpty(),
            ruleIndexKey = rule?.indexKey.orEmpty()
        )
    }

    private fun budgetExhaustedDecision(
        stage: String,
        candidateCount: Int,
        limit: MatchLimit
    ): FilterDecision {
        perf.recordMatchBudgetExhaustion()
        return FilterDecision(
            action = FilterAction.BLOCK,
            reason = "filter match budget exhausted: ${limit.diagnosticValue}",
            diagnostics = FilterDecisionDiagnostics(
                candidateCount = candidateCount,
                matchedStage = "$stage-budget-exhausted",
                cacheStatus = "budget-exhausted-${limit.diagnosticValue}"
            )
        )
    }

    private fun FilterDecision.withCacheStatus(cacheStatus: String): FilterDecision {
        val nextDiagnostics = diagnostics?.copy(cacheStatus = cacheStatus)
            ?: FilterDecisionDiagnostics(cacheStatus = cacheStatus)
        return copy(diagnostics = nextDiagnostics)
    }

    private fun canStoreInNormalizedCache(decision: FilterDecision): Boolean {
        if (hasQuerySensitiveNetworkRules) return false
        val pattern = decision.rule?.pattern.orEmpty()
        return pattern.indexOf('?') < 0 && pattern.indexOf('&') < 0 && pattern.indexOf('=') < 0
    }

    private fun normalizedDecisionCacheKey(context: FilterRequestContext): String? {
        if (hasQuerySensitiveNetworkRules) return null
        if (context.resourceType !in NORMALIZED_CACHE_RESOURCE_TYPES) return null
        val normalizedUrl = normalizeCacheBustingUrl(context.requestUrl) ?: return null
        return "$normalizedUrl|${context.topLevelHost}|${context.resourceType}|${context.isThirdParty}"
    }

    companion object {
        val EMPTY = FilterEngine(
            TokenIndex(emptyList()), TokenIndex(emptyList()), TokenIndex(emptyList()), TokenIndex(emptyList()),
            TokenIndex(emptyList()),
            emptyList(), emptyList(),
            FilterBuildReport(0, 0, 0, 0, 0, 0, emptyList(), emptyList()),
            buildDurationMs = 0L,
            hasQuerySensitiveNetworkRules = false
        )

        fun build(sources: List<FilterRuleSource>): FilterEngine {
            val startedAt = System.nanoTime()
            val compiled = mutableListOf<CompiledRule>()
            val cosmetic = mutableListOf<CosmeticFilterRule>()
            val scriptlets = mutableListOf<ScriptletFilterRule>()
            val reports = mutableListOf<FilterSourceReport>()
            val errors = mutableListOf<String>()
            val badFilters = mutableSetOf<String>()

            sources.forEach { source ->
                val parseResult = FilterRuleParser.parse(
                    text = source.rulesText,
                    sourceId = source.id,
                    sourceName = source.name,
                    sourceTier = source.sourceTier
                )
                reports += FilterSourceReport(
                    sourceId = source.id,
                    sourceName = source.name,
                    totalLines = parseResult.totalLines,
                    enabledRules = parseResult.enabledRules,
                    unsupportedRules = parseResult.unsupportedRules,
                    networkRules = parseResult.rules.count { !it.badFilter && it.removeParams.isEmpty() },
                    cosmeticRules = parseResult.cosmeticRules.count { !it.isException },
                    scriptletRules = parseResult.scriptletRules.count { it.supported },
                    errors = parseResult.errors
                )
                errors += parseResult.errors
                cosmetic += parseResult.cosmeticRules
                scriptlets += parseResult.scriptletRules.filter { it.supported }
                parseResult.rules.forEach { rule ->
                    if (rule.badFilter) {
                        badFilters += rule.canonicalBadFilterTarget()
                    } else {
                        CompiledRule.from(rule)?.let { compiledRule ->
                            compiled += compiledRule
                        } ?: run {
                            errors += "规则编译失败: ${rule.rawText}"
                        }
                    }
                }
            }

            val activeCompiled = if (badFilters.isEmpty()) {
                compiled
            } else {
                compiled.filterNot { it.rule.rawText in badFilters || it.rule.canonicalBadFilterTarget() in badFilters }
            }

            val transformRules = activeCompiled.filter { it.rule.removeParams.isNotEmpty() }
            val networkRules = activeCompiled.filter { it.rule.removeParams.isEmpty() }
            val important = networkRules.filter { !it.rule.isException && it.rule.important }
            val exceptions = networkRules.filter { it.rule.isException }
            val blocking = networkRules.filter { !it.rule.isException && !it.rule.important }
            val removeParam = transformRules.filter { !it.rule.isException }
            val removeParamExceptions = transformRules.filter { it.rule.isException }
            val hasQuerySensitiveNetworkRules = networkRules.any { it.rule.isQuerySensitive() }
            val enabledRuleCount = activeCompiled.size
            val unsupportedRuleCount = reports.sumOf { it.unsupportedRules }
            val buildDurationMs = (System.nanoTime() - startedAt) / 1_000_000L
            return FilterEngine(
                importantIndex = TokenIndex(important.sortedByDescending { it.weight }),
                exceptionIndex = TokenIndex(
                    exceptions.sortedWith(
                        compareByDescending<CompiledRule> {
                            it.rule.sourceTier == FilterRuleSourceTier.PARENT_CUSTOM
                        }.thenByDescending { it.weight }
                    )
                ),
                blockingIndex = TokenIndex(blocking.sortedByDescending { it.weight }),
                removeParamIndex = TokenIndex(removeParam.sortedByDescending { it.weight }),
                removeParamExceptionIndex = TokenIndex(removeParamExceptions.sortedByDescending { it.weight }),
                cosmeticRules = cosmetic,
                scriptletRules = scriptlets,
                report = FilterBuildReport(
                    ruleCount = reports.sumOf { it.totalLines },
                    enabledRuleCount = enabledRuleCount,
                    unsupportedRuleCount = unsupportedRuleCount,
                    networkRuleCount = networkRules.size,
                    cosmeticRuleCount = cosmetic.count { !it.isException },
                    scriptletRuleCount = scriptlets.count { it.supported },
                    sourceReports = reports,
                    errors = errors.take(30)
                ),
                buildDurationMs = buildDurationMs,
                hasQuerySensitiveNetworkRules = hasQuerySensitiveNetworkRules
            )
        }
    }
}

private fun FilterRule.isQuerySensitive(): Boolean {
    return pattern.any { it == '?' || it == '&' || it == '=' } || removeParams.isNotEmpty()
}

private fun CosmeticFilterRule.matchesHost(host: String): Boolean {
    if (excludedDomains.any { isSameOrSubdomain(host, it) }) return false
    return domains.isEmpty() || domains.any { isSameOrSubdomain(host, it) }
}

private fun ScriptletFilterRule.matchesHost(host: String): Boolean {
    if (excludedDomains.any { isSameOrSubdomain(host, it) }) return false
    return domains.isEmpty() || domains.any { isSameOrSubdomain(host, it) }
}

private class CosmeticIndex(rules: List<CosmeticFilterRule>) {
    private val globalRules = rules.filter { !it.isException && it.domains.isEmpty() }
    private val globalExceptions = rules.filter { it.isException && it.domains.isEmpty() }
    private val domainRules = HashMap<String, MutableList<CosmeticFilterRule>>()
    private val domainExceptions = HashMap<String, MutableList<CosmeticFilterRule>>()

    init {
        rules.filter { it.domains.isNotEmpty() }.forEach { rule ->
            val target = if (rule.isException) domainExceptions else domainRules
            rule.domains.forEach { domain ->
                target.getOrPut(domain) { mutableListOf() }.add(rule)
            }
        }
    }

    fun cssFor(host: String): String {
        val selectors = matchesFor(host).map { it.selector }
        if (selectors.isEmpty()) return ""
        return selectors.joinToString(",\n") + " { display: none !important; visibility: hidden !important; }"
    }

    fun matchesFor(host: String): List<CosmeticFilterMatch> {
        val exceptions = matchingRules(host, globalExceptions, domainExceptions)
            .map { it.selector }
            .toSet()
        return matchingRules(host, globalRules, domainRules)
            .asSequence()
            .filterNot { it.selector in exceptions }
            .filter { CssSelectorPolicy.isAllowed(it.selector) }
            .distinctBy { it.selector }
            .take(800)
            .map { rule ->
                CosmeticFilterMatch(
                    selector = rule.selector,
                    rawText = rule.rawText,
                    sourceId = rule.sourceId,
                    sourceName = rule.sourceName
                )
            }
            .toList()
    }

    private fun matchingRules(
        host: String,
        global: List<CosmeticFilterRule>,
        domainMap: Map<String, List<CosmeticFilterRule>>
    ): List<CosmeticFilterRule> {
        val result = mutableListOf<CosmeticFilterRule>()
        // Exact/most-specific site rules are intentionally appended first so the hard selector
        // limit cannot starve them behind a large global list.
        forEachHostSuffix(host) { suffix ->
            domainMap[suffix]?.filterTo(result) { it.matchesHost(host) }
        }
        global.filterTo(result) { rule -> rule.excludedDomains.none { isSameOrSubdomain(host, it) } }
        return result
    }
}

private class ScriptletIndex(rules: List<ScriptletFilterRule>) {
    private val globalRules = rules.filter { it.domains.isEmpty() }
    private val domainRules = HashMap<String, MutableList<ScriptletFilterRule>>()

    init {
        rules.filter { it.domains.isNotEmpty() }.forEach { rule ->
            rule.domains.forEach { domain ->
                domainRules.getOrPut(domain) { mutableListOf() }.add(rule)
            }
        }
    }

    fun jsFor(host: String): String {
        val rules = mutableListOf<ScriptletFilterRule>()
        globalRules.filterTo(rules) { rule -> rule.excludedDomains.none { isSameOrSubdomain(host, it) } }
        forEachHostSuffix(host) { suffix ->
            domainRules[suffix]?.filterTo(rules) { it.matchesHost(host) }
        }
        return rules
            .asSequence()
            .mapNotNull { scriptletToJs(it) }
            .take(80)
            .joinToString("\n")
    }
}

private inline fun forEachHostSuffix(host: String, onSuffix: (String) -> Unit) {
    if (host.isBlank()) return
    onSuffix(host)
    var dotIdx = host.indexOf('.')
    while (dotIdx > 0) {
        onSuffix(host.substring(dotIdx + 1))
        dotIdx = host.indexOf('.', dotIdx + 1)
    }
}

private fun FilterRule.canonicalBadFilterTarget(): String {
    return buildString {
        append(if (isException) "exception|" else "block|")
        append(if (domainAnchored) "domain|" else "")
        append(if (startAnchored) "start|" else "")
        append(if (endAnchored) "end|" else "")
        append(pattern.lowercase(Locale.US))
        append("|types=")
        append(resourceTypes.map { it.optionName }.sorted().joinToString(","))
        append("|excludedTypes=")
        append(excludedResourceTypes.map { it.optionName }.sorted().joinToString(","))
        append("|party=")
        append(thirdParty?.toString().orEmpty())
        append("|domains=")
        append(domains.map { it.lowercase(Locale.US) }.sorted().joinToString(","))
        append("|excludedDomains=")
        append(excludedDomains.map { it.lowercase(Locale.US) }.sorted().joinToString(","))
        append("|important=")
        append(important)
        append("|removeParams=")
        append(removeParams.map { it.lowercase(Locale.US) }.sorted().joinToString(","))
    }
}

private fun scriptletToJs(rule: ScriptletFilterRule): String? {
    val normalized = rule.name.removeSuffix(".js").lowercase(Locale.US)
    return when (normalized) {
        "no-window-open-if", "nowoif" -> """
            try {
                if (!window.__ck_no_window_open_if__) {
                    window.__ck_no_window_open_if__ = true;
                    window.open = function() { return null; };
                }
            } catch(e) {}
        """.trimIndent()
        "abort-on-property-read", "aopr" -> {
            val property = rule.args.firstOrNull()?.takeIf { isSafePropertyPath(it) } ?: return null
            val propertyJson = org.json.JSONObject.quote(property)
            """
                try {
                    var path = $propertyJson.split('.');
                    var owner = window;
                    for (var i = 0; i < path.length - 1; i++) {
                        owner = owner[path[i]] = owner[path[i]] || {};
                    }
                    Object.defineProperty(owner, path[path.length - 1], {
                        get: function() { throw new ReferenceError('blocked'); },
                        configurable: true
                    });
                } catch(e) {}
            """.trimIndent()
        }
        "set-constant", "set" -> {
            val property = rule.args.getOrNull(0)?.takeIf { isSafePropertyPath(it) } ?: return null
            val value = when (rule.args.getOrNull(1)?.lowercase(Locale.US)) {
                "true" -> "true"
                "false" -> "false"
                "undefined" -> "undefined"
                "null" -> "null"
                "0" -> "0"
                "1" -> "1"
                else -> "undefined"
            }
            val propertyJson = org.json.JSONObject.quote(property)
            """
                try {
                    var path = $propertyJson.split('.');
                    var owner = window;
                    for (var i = 0; i < path.length - 1; i++) {
                        owner = owner[path[i]] = owner[path[i]] || {};
                    }
                    Object.defineProperty(owner, path[path.length - 1], {
                        value: $value,
                        writable: false,
                        configurable: true
                    });
                } catch(e) {}
            """.trimIndent()
        }
        else -> null
    }
}

private fun isSafePropertyPath(value: String): Boolean {
    if (value.isBlank() || value.length > 120) return false
    return value.split('.').all { part ->
        part.isNotBlank() && part.all { it.isLetterOrDigit() || it == '_' || it == '$' }
    }
}

private val NORMALIZED_CACHE_RESOURCE_TYPES = setOf(
    FilterResourceType.IMAGE,
    FilterResourceType.SCRIPT,
    FilterResourceType.STYLESHEET,
    FilterResourceType.FONT,
    FilterResourceType.MEDIA
)

private val CACHE_BUSTING_QUERY_PARAMS = setOf(
    "t",
    "ts",
    "time",
    "timestamp",
    "_",
    "rnd",
    "random",
    "cache",
    "cachebuster",
    "cb",
    "v",
    "ver",
    "version"
)

private fun normalizeCacheBustingUrl(url: String): String? {
    val uri = runCatching { URI(url) }.getOrNull() ?: return null
    val rawQuery = uri.rawQuery ?: return null
    val pairs = rawQuery.split("&").filter { it.isNotBlank() }
    if (pairs.isEmpty()) return null
    val allCacheBusting = pairs.all { pair ->
        val name = pair.substringBefore("=", pair).lowercase(Locale.US)
        name in CACHE_BUSTING_QUERY_PARAMS
    }
    if (!allCacheBusting) return null
    return URI(
        uri.scheme,
        uri.authority,
        uri.path,
        null,
        uri.fragment
    ).toString().takeIf { it != url }
}

data class FilterRuleSource(
    val id: String,
    val name: String,
    val rulesText: String,
    val sourceTier: FilterRuleSourceTier = if (id == "custom") {
        FilterRuleSourceTier.PARENT_CUSTOM
    } else {
        FilterRuleSourceTier.SUBSCRIPTION
    }
)

private class FilterPerfTracker(private val buildDurationMs: Long) {
    private val decisionCount = AtomicLong(0L)
    private val cacheHitCount = AtomicLong(0L)
    private val cacheMissCount = AtomicLong(0L)
    private val normalizedCacheHitCount = AtomicLong(0L)
    private val normalizedCacheStoreCount = AtomicLong(0L)
    private val normalizedCacheBypassCount = AtomicLong(0L)
    private val candidateEvaluationCount = AtomicLong(0L)
    private val regexEvaluationCount = AtomicLong(0L)
    private val matchBudgetExhaustionCount = AtomicLong(0L)
    private val cosmeticCallCount = AtomicLong(0L)
    private val scriptletCallCount = AtomicLong(0L)
    private val generatedCssBytes = AtomicLong(0L)
    private val generatedScriptletBytes = AtomicLong(0L)

    private val shouldBlockDurations = AtomicLongSampler()
    private val shouldBlockParseDurations = AtomicLongSampler()
    private val shouldBlockEngineDurations = AtomicLongSampler()
    private val shouldBlockEventDurations = AtomicLongSampler()
    private val shouldBlockSnapshotDurations = AtomicLongSampler()
    private val decisionDurations = AtomicLongSampler()
    private val candidateCounts = AtomicLongSampler()
    private val cosmeticDurations = AtomicLongSampler()
    private val scriptletDurations = AtomicLongSampler()
    private val slowShouldBlockSamples = SlowShouldBlockSampler()

    fun recordShouldBlock(
        totalNanos: Long,
        parseNanos: Long,
        engineNanos: Long,
        eventNanos: Long,
        snapshotNanos: Long,
        resourceType: FilterResourceType?,
        action: FilterAction?,
        url: String,
        ruleText: String,
        cacheStatus: String,
        candidateCount: Int
    ) {
        val totalMicros = nanosToMicros(totalNanos)
        val parseMicros = nanosToMicros(parseNanos)
        val engineMicros = nanosToMicros(engineNanos)
        val eventMicros = nanosToMicros(eventNanos)
        val snapshotMicros = nanosToMicros(snapshotNanos)
        shouldBlockDurations.record(totalMicros)
        shouldBlockParseDurations.record(parseMicros)
        shouldBlockEngineDurations.record(engineMicros)
        shouldBlockEventDurations.record(eventMicros)
        shouldBlockSnapshotDurations.record(snapshotMicros)
        if (totalMicros >= SLOW_SHOULD_BLOCK_THRESHOLD_MICROS) {
            slowShouldBlockSamples.record(
                FilterSlowShouldBlockSample(
                    timestamp = System.currentTimeMillis(),
                    durationMicros = totalMicros,
                    parseMicros = parseMicros,
                    engineMicros = engineMicros,
                    eventMicros = eventMicros,
                    snapshotMicros = snapshotMicros,
                    resourceType = resourceType?.optionName.orEmpty(),
                    action = action?.name.orEmpty(),
                    url = url.take(240),
                    ruleText = ruleText.take(160),
                    cacheStatus = cacheStatus,
                    candidateCount = candidateCount
                )
            )
        }
    }

    fun recordCacheHit() {
        cacheHitCount.incrementAndGet()
    }

    fun recordCacheMiss() {
        cacheMissCount.incrementAndGet()
    }

    fun recordNormalizedCacheHit() {
        normalizedCacheHitCount.incrementAndGet()
    }

    fun recordNormalizedCacheStore() {
        normalizedCacheStoreCount.incrementAndGet()
    }

    fun recordNormalizedCacheBypass() {
        normalizedCacheBypassCount.incrementAndGet()
    }

    fun recordDecision(durationNanos: Long, evaluatedCandidates: Int) {
        decisionCount.incrementAndGet()
        candidateEvaluationCount.addAndGet(evaluatedCandidates.toLong())
        decisionDurations.record(nanosToMicros(durationNanos))
        candidateCounts.record(evaluatedCandidates.toLong())
    }

    fun recordRegexEvaluation() {
        regexEvaluationCount.incrementAndGet()
    }

    fun recordMatchBudgetExhaustion() {
        matchBudgetExhaustionCount.incrementAndGet()
    }

    fun recordCosmetic(durationNanos: Long, generatedBytes: Int) {
        cosmeticCallCount.incrementAndGet()
        generatedCssBytes.addAndGet(generatedBytes.toLong())
        cosmeticDurations.record(nanosToMicros(durationNanos))
    }

    fun recordScriptlet(durationNanos: Long, generatedBytes: Int) {
        scriptletCallCount.incrementAndGet()
        generatedScriptletBytes.addAndGet(generatedBytes.toLong())
        scriptletDurations.record(nanosToMicros(durationNanos))
    }

    fun snapshot(
        importantIndex: FilterIndexStats,
        exceptionIndex: FilterIndexStats,
        blockingIndex: FilterIndexStats,
        removeParamIndex: FilterIndexStats
    ): FilterPerfSnapshot {
        return FilterPerfSnapshot(
            buildDurationMs = buildDurationMs,
            decisionCount = decisionCount.get(),
            cacheHitCount = cacheHitCount.get(),
            cacheMissCount = cacheMissCount.get(),
            normalizedCacheHitCount = normalizedCacheHitCount.get(),
            normalizedCacheStoreCount = normalizedCacheStoreCount.get(),
            normalizedCacheBypassCount = normalizedCacheBypassCount.get(),
            candidateEvaluationCount = candidateEvaluationCount.get(),
            regexEvaluationCount = regexEvaluationCount.get(),
            matchBudgetExhaustionCount = matchBudgetExhaustionCount.get(),
            cosmeticCallCount = cosmeticCallCount.get(),
            scriptletCallCount = scriptletCallCount.get(),
            generatedCssBytes = generatedCssBytes.get(),
            generatedScriptletBytes = generatedScriptletBytes.get(),
            shouldBlockDurationMicros = shouldBlockDurations.snapshot(),
            shouldBlockParseDurationMicros = shouldBlockParseDurations.snapshot(),
            shouldBlockEngineDurationMicros = shouldBlockEngineDurations.snapshot(),
            shouldBlockEventDurationMicros = shouldBlockEventDurations.snapshot(),
            shouldBlockSnapshotDurationMicros = shouldBlockSnapshotDurations.snapshot(),
            decisionDurationMicros = decisionDurations.snapshot(),
            candidateEvaluationsPerDecision = candidateCounts.snapshot(),
            cosmeticDurationMicros = cosmeticDurations.snapshot(),
            scriptletDurationMicros = scriptletDurations.snapshot(),
            importantIndex = importantIndex,
            exceptionIndex = exceptionIndex,
            blockingIndex = blockingIndex,
            removeParamIndex = removeParamIndex,
            slowShouldBlockSamples = slowShouldBlockSamples.snapshot()
        )
    }

    fun reset() {
        decisionCount.set(0L)
        cacheHitCount.set(0L)
        cacheMissCount.set(0L)
        normalizedCacheHitCount.set(0L)
        normalizedCacheStoreCount.set(0L)
        normalizedCacheBypassCount.set(0L)
        candidateEvaluationCount.set(0L)
        regexEvaluationCount.set(0L)
        matchBudgetExhaustionCount.set(0L)
        cosmeticCallCount.set(0L)
        scriptletCallCount.set(0L)
        generatedCssBytes.set(0L)
        generatedScriptletBytes.set(0L)
        shouldBlockDurations.reset()
        shouldBlockParseDurations.reset()
        shouldBlockEngineDurations.reset()
        shouldBlockEventDurations.reset()
        shouldBlockSnapshotDurations.reset()
        decisionDurations.reset()
        candidateCounts.reset()
        cosmeticDurations.reset()
        scriptletDurations.reset()
        slowShouldBlockSamples.reset()
    }

    private fun nanosToMicros(durationNanos: Long): Long {
        return (durationNanos / 1_000L).coerceAtLeast(0L)
    }

    companion object {
        private const val SLOW_SHOULD_BLOCK_THRESHOLD_MICROS = 20_000L
    }
}

private class AtomicLongSampler(private val capacity: Int = 1024) {
    private val values = AtomicLongArray(capacity)
    private val nextIndex = AtomicInteger(0)
    private val totalCount = AtomicLong(0L)

    fun record(value: Long) {
        val slot = (nextIndex.getAndIncrement() and Int.MAX_VALUE) % capacity
        values.set(slot, value.coerceAtLeast(0L))
        totalCount.incrementAndGet()
    }

    fun reset() {
        for (i in 0 until capacity) {
            values.set(i, 0L)
        }
        nextIndex.set(0)
        totalCount.set(0L)
    }

    fun snapshot(): FilterPerfSampleStats {
        val count = totalCount.get().coerceAtMost(capacity.toLong()).toInt()
        if (count <= 0) {
            return FilterPerfSampleStats(sampleCount = 0, p50 = 0L, p95 = 0L, p99 = 0L, max = 0L)
        }
        val snapshot = LongArray(count)
        for (i in 0 until count) {
            snapshot[i] = values.get(i)
        }
        snapshot.sort()
        return FilterPerfSampleStats(
            sampleCount = count,
            p50 = snapshot.percentile(0.50),
            p95 = snapshot.percentile(0.95),
            p99 = snapshot.percentile(0.99),
            max = snapshot.last()
        )
    }
}

private class SlowShouldBlockSampler(private val capacity: Int = 20) {
    private val lock = Any()
    private val samples = ArrayDeque<FilterSlowShouldBlockSample>(capacity)

    fun record(sample: FilterSlowShouldBlockSample) {
        synchronized(lock) {
            if (samples.size >= capacity) samples.removeFirst()
            samples.addLast(sample)
        }
    }

    fun snapshot(): List<FilterSlowShouldBlockSample> {
        return synchronized(lock) {
            samples.toList().sortedByDescending { it.timestamp }
        }
    }

    fun reset() {
        synchronized(lock) {
            samples.clear()
        }
    }
}

private fun LongArray.percentile(percentile: Double): Long {
    if (isEmpty()) return 0L
    val index = ((size - 1) * percentile).toInt().coerceIn(0, size - 1)
    return this[index]
}

/**
 * Token-based reverse index for fast rule lookup.
 * Rules are bucketed by their best token; queries extract tokens from the URL
 * and only check matching buckets + universal (token-less) rules.
 */
private class TokenIndex(rules: List<CompiledRule>) {
    private val orderedRules: List<CompiledRule> = rules
    private val domainMap: HashMap<String, MutableList<CompiledRule>>
    private val tokenMap: HashMap<String, MutableList<CompiledRule>>
    private val universalRules: List<CompiledRule>
    private val indexedRuleCount: Int
    private val ruleOrder: Map<CompiledRule, Int>

    init {
        val domains = HashMap<String, MutableList<CompiledRule>>(rules.size / 2 + 1)
        val tokens = HashMap<String, MutableList<CompiledRule>>(rules.size / 2 + 1)
        val universal = mutableListOf<CompiledRule>()
        var indexed = 0
        for (rule in rules) {
            when {
                rule.rule.domainAnchored && rule.anchorHost.isNotBlank() -> {
                    domains.getOrPut(rule.anchorHost) { mutableListOf() }.add(rule)
                    indexed++
                }
                rule.indexKey.isNotEmpty() -> {
                    tokens.getOrPut(rule.indexKey) { mutableListOf() }.add(rule)
                    indexed++
                }
                else -> {
                    universal.add(rule)
                }
            }
        }
        domainMap = domains
        tokenMap = tokens
        universalRules = universal
        indexedRuleCount = indexed
        ruleOrder = rules.withIndex().associate { it.value to it.index }
    }

    fun candidates(url: String, host: String, domainOnly: Boolean = false): CandidateSet {
        val seen = HashSet<CompiledRule>()
        val candidates = mutableListOf<CompiledRule>()
        var overflowed = false

        fun addRules(rules: List<CompiledRule>?) {
            if (rules == null || overflowed) return
            for (rule in rules) {
                if (seen.add(rule)) {
                    if (candidates.size >= MAX_CANDIDATES_PER_INDEX_STAGE) {
                        overflowed = true
                        return
                    }
                    candidates += rule
                }
            }
        }

        // 1. Exact host and parent-domain suffixes for domain-anchor rules.
        addRules(domainMap[host])
        var dotIdx = host.indexOf('.')
        while (dotIdx > 0) {
            val parent = host.substring(dotIdx + 1)
            addRules(domainMap[parent])
            dotIdx = host.indexOf('.', dotIdx + 1)
        }

        if (domainOnly) {
            candidates.sortBy { ruleOrder[it] ?: Int.MAX_VALUE }
            return CandidateSet(candidates, overflowed)
        }

        // 2. URL literal gram keys. A rule indexed by "adsby" can match
        // "adsbygoogle.js" without requiring exact URL-token equality.
        val seenKeys = HashSet<String>()
        val indexedUrl = if (url.length > MAX_EXPENSIVE_URL_LENGTH) {
            url.substring(0, MAX_EXPENSIVE_URL_LENGTH)
        } else {
            url
        }
        extractUrlIndexKeys(indexedUrl.lowercase(Locale.US)) { key ->
            if (seenKeys.add(key)) {
                addRules(tokenMap[key])
            }
        }

        // 3. Universal rules (no safe extractable key).
        addRules(universalRules)

        candidates.sortBy { ruleOrder[it] ?: Int.MAX_VALUE }
        return CandidateSet(candidates, overflowed)
    }

    fun stats(): FilterIndexStats {
        return FilterIndexStats(
            tokenBucketCount = domainMap.size + tokenMap.size,
            indexedRuleCount = indexedRuleCount,
            universalRuleCount = universalRules.size
        )
    }

    fun linearFirstMatching(context: FilterRequestContext): CompiledRule? {
        val budget = FilterDecisionMatchBudget(maxCandidates = Int.MAX_VALUE)
        return orderedRules.firstOrNull { it.matches(context, budget = budget) == RuleMatchResult.MATCH }
    }
}

private data class CandidateSet(
    val rules: List<CompiledRule>,
    val overflowed: Boolean
)

private enum class MatchLimit(val diagnosticValue: String) {
    CANDIDATE_LIMIT("candidate-limit"),
    CHARACTER_BUDGET("character-budget")
}

private enum class RuleMatchResult {
    MATCH,
    NO_MATCH,
    BUDGET_EXHAUSTED
}

private class FilterDecisionMatchBudget(
    maxCandidates: Int = MAX_CANDIDATES_PER_DECISION,
    maxCharacterSteps: Int = MAX_MATCH_STEPS_PER_DECISION
) {
    private var remainingCandidates = maxCandidates
    val characterBudget = AdblockMatchBudget(maxCharacterSteps)

    fun tryConsumeCandidate(): Boolean {
        if (remainingCandidates <= 0) return false
        remainingCandidates--
        return true
    }
}

private data class CandidateMatch(
    val rule: CompiledRule?,
    val evaluatedCount: Int,
    val limit: MatchLimit? = null
)

private data class RemoveParamMatch(
    val params: Set<String>,
    val evaluatedCount: Int,
    val limit: MatchLimit? = null
)

private fun TokenIndex.firstMatching(
    url: String,
    host: String,
    context: FilterRequestContext,
    perf: FilterPerfTracker,
    budget: FilterDecisionMatchBudget,
    domainOnly: Boolean = false
): CandidateMatch {
    val candidateSet = candidates(url, host, domainOnly)
    var evaluated = 0
    for (rule in candidateSet.rules) {
        if (!budget.tryConsumeCandidate()) {
            return CandidateMatch(null, evaluated, MatchLimit.CANDIDATE_LIMIT)
        }
        evaluated++
        when (rule.matches(context, perf, budget, matchExpensiveUrlPartially = domainOnly)) {
            RuleMatchResult.MATCH -> return CandidateMatch(rule = rule, evaluatedCount = evaluated)
            RuleMatchResult.BUDGET_EXHAUSTED -> {
                return CandidateMatch(null, evaluated, MatchLimit.CHARACTER_BUDGET)
            }
            RuleMatchResult.NO_MATCH -> Unit
        }
    }
    return CandidateMatch(
        rule = null,
        evaluatedCount = evaluated,
        limit = if (candidateSet.overflowed) MatchLimit.CANDIDATE_LIMIT else null
    )
}

private fun TokenIndex.matchingRemoveParams(
    url: String,
    host: String,
    context: FilterRequestContext,
    perf: FilterPerfTracker,
    budget: FilterDecisionMatchBudget,
    domainOnly: Boolean
): RemoveParamMatch {
    val candidateSet = candidates(url, host, domainOnly)
    val params = linkedSetOf<String>()
    var evaluated = 0
    for (rule in candidateSet.rules) {
        if (rule.rule.removeParams.isEmpty()) continue
        if (!budget.tryConsumeCandidate()) {
            return RemoveParamMatch(params, evaluated, MatchLimit.CANDIDATE_LIMIT)
        }
        evaluated++
        when (rule.matches(context, perf, budget, matchExpensiveUrlPartially = domainOnly)) {
            RuleMatchResult.MATCH -> params += rule.rule.removeParams
            RuleMatchResult.BUDGET_EXHAUSTED -> {
                return RemoveParamMatch(params, evaluated, MatchLimit.CHARACTER_BUDGET)
            }
            RuleMatchResult.NO_MATCH -> Unit
        }
    }
    return RemoveParamMatch(
        params = params,
        evaluatedCount = evaluated,
        limit = if (candidateSet.overflowed) MatchLimit.CANDIDATE_LIMIT else null
    )
}

private const val URL_INDEX_GRAM_LENGTH = 5
private const val MAX_EXPENSIVE_URL_LENGTH = 8 * 1024
private const val MAX_CANDIDATES_PER_INDEX_STAGE = 2_048
private const val MAX_CANDIDATES_PER_DECISION = 4_096
internal const val MAX_MATCH_STEPS_PER_DECISION = 262_144

private inline fun extractUrlIndexKeys(urlLower: String, onKey: (String) -> Unit) {
    var start = -1
    for (i in urlLower.indices) {
        val c = urlLower[i]
        if (c.isLetterOrDigit() || c == '.' || c == '-') {
            if (start < 0) start = i
        } else {
            if (start >= 0) {
                emitIndexKeys(urlLower, start, i, onKey)
            }
            start = -1
        }
    }
    if (start >= 0) {
        emitIndexKeys(urlLower, start, urlLower.length, onKey)
    }
}

private inline fun emitIndexKeys(value: String, start: Int, end: Int, onKey: (String) -> Unit) {
    val length = end - start
    if (length < 3) return
    if (length <= URL_INDEX_GRAM_LENGTH) {
        onKey(value.substring(start, end))
        return
    }
    var index = start
    val lastStart = end - URL_INDEX_GRAM_LENGTH
    while (index <= lastStart) {
        onKey(value.substring(index, index + URL_INDEX_GRAM_LENGTH))
        index++
    }
}

private class CompiledRule(
    val rule: FilterRule,
    private val usesGeneratedMatcher: Boolean = false
) {
    val weight: Int = when {
        rule.domainAnchored -> 100
        rule.startAnchored && rule.endAnchored -> 90
        rule.startAnchored || rule.endAnchored -> 60
        else -> 20
    } + if (rule.domains.isNotEmpty()) 20 else 0

    // ---- Precomputed fields (computed once at construction) ----
    val patternLower: String = rule.pattern.lowercase(Locale.US)

    // DOMAIN_ANCHOR only: precomputed host from pattern
    val anchorHost: String

    // Token for reverse index bucketing
    val bestToken: String
    val indexKey: String

    init {
        anchorHost = if (rule.domainAnchored) domainAnchorHost(rule.pattern).orEmpty() else ""
        bestToken = extractBestToken(rule)
        indexKey = indexKeyFor(bestToken)
    }

    fun matches(
        context: FilterRequestContext,
        perf: FilterPerfTracker? = null,
        budget: FilterDecisionMatchBudget,
        matchExpensiveUrlPartially: Boolean = false
    ): RuleMatchResult {
        if (!matchesOptions(context)) return RuleMatchResult.NO_MATCH
        if (rule.matchType == FilterMatchType.REGEX) return RuleMatchResult.NO_MATCH
        if (rule.domainAnchored) {
            return matchesDomainAnchor(context, perf, budget, matchExpensiveUrlPartially)
        }
        val target = if (matchExpensiveUrlPartially && context.requestUrlLower.length > MAX_EXPENSIVE_URL_LENGTH) {
            context.requestUrlLower.substring(0, MAX_EXPENSIVE_URL_LENGTH)
        } else {
            context.requestUrlLower
        }
        if (usesGeneratedMatcher) {
            perf?.recordRegexEvaluation()
            return AdblockPatternMatcher.matches(
                target = target,
                pattern = patternLower,
                startAnchored = rule.startAnchored,
                endAnchored = rule.endAnchored,
                budget = budget.characterBudget
            ).toRuleMatchResult()
        }
        return literalContains(target, patternLower, budget.characterBudget)
    }

    private fun matchesOptions(context: FilterRequestContext): Boolean {
        if (rule.resourceTypes.isNotEmpty() && context.resourceType !in rule.resourceTypes) return false
        if (context.resourceType in rule.excludedResourceTypes) return false
        if (rule.thirdParty != null && context.isThirdParty != rule.thirdParty) return false
        if (rule.excludedDomains.any { isSameOrSubdomain(context.topLevelHost, it) }) return false
        if (rule.domains.isNotEmpty() && rule.domains.none { isSameOrSubdomain(context.topLevelHost, it) }) return false
        return true
    }

    private fun matchesDomainAnchor(
        context: FilterRequestContext,
        perf: FilterPerfTracker?,
        budget: FilterDecisionMatchBudget,
        matchExpensiveUrlPartially: Boolean
    ): RuleMatchResult {
        if (anchorHost.isBlank()) return RuleMatchResult.NO_MATCH
        if (!isSameOrSubdomain(context.requestHost, anchorHost)) return RuleMatchResult.NO_MATCH
        if (
            rule.pattern.equals(anchorHost, ignoreCase = true) &&
            !rule.endAnchored &&
            !rule.hasWildcard &&
            !rule.hasSeparator
        ) {
            return RuleMatchResult.MATCH
        }
        val target = AdblockPatternMatcher.domainTarget(
            urlLower = context.requestUrlLower,
            anchorHost = anchorHost,
            maxLength = if (matchExpensiveUrlPartially) MAX_EXPENSIVE_URL_LENGTH else Int.MAX_VALUE
        ) ?: return RuleMatchResult.NO_MATCH
        perf?.recordRegexEvaluation()
        if (!usesGeneratedMatcher) return RuleMatchResult.NO_MATCH
        return AdblockPatternMatcher.matches(
            target = target,
            pattern = patternLower,
            startAnchored = true,
            endAnchored = rule.endAnchored,
            budget = budget.characterBudget
        ).toRuleMatchResult()
    }

    companion object {
        fun from(rule: FilterRule): CompiledRule? {
            if (rule.matchType == FilterMatchType.REGEX) return null
            val needsGeneratedPattern = rule.domainAnchored ||
                rule.startAnchored ||
                rule.endAnchored ||
                rule.hasWildcard ||
                rule.hasSeparator
            return CompiledRule(rule, needsGeneratedPattern)
        }

        private val TOKEN_REGEX = Regex("[a-zA-Z0-9][a-zA-Z0-9.\\-]{2,}")

        private fun extractBestToken(rule: FilterRule): String {
            // For DOMAIN_ANCHOR, prefer the host part as token
            if (rule.domainAnchored) {
                val host = domainAnchorHost(rule.pattern).orEmpty()
                if (host.length >= 3) return host
            }
            // Extract longest alphanumeric sequence from pattern
            return TOKEN_REGEX.findAll(rule.pattern)
                .map { it.value.lowercase(Locale.US) }
                .maxByOrNull { it.length }
                ?: ""
        }

        private fun indexKeyFor(token: String): String {
            val normalized = token.lowercase(Locale.US)
            if (normalized.length < 3) return ""
            return if (normalized.length <= URL_INDEX_GRAM_LENGTH) {
                normalized
            } else {
                normalized.take(URL_INDEX_GRAM_LENGTH)
            }
        }
    }
}

private fun AdblockMatchResult.toRuleMatchResult(): RuleMatchResult {
    return when (this) {
        AdblockMatchResult.MATCH -> RuleMatchResult.MATCH
        AdblockMatchResult.NO_MATCH -> RuleMatchResult.NO_MATCH
        AdblockMatchResult.BUDGET_EXHAUSTED -> RuleMatchResult.BUDGET_EXHAUSTED
    }
}

private fun literalContains(
    target: String,
    pattern: String,
    budget: AdblockMatchBudget
): RuleMatchResult {
    if (pattern.isEmpty()) return RuleMatchResult.MATCH
    if (pattern.length > target.length) return RuleMatchResult.NO_MATCH
    val lastStart = target.length - pattern.length
    for (start in 0..lastStart) {
        var patternIndex = 0
        while (patternIndex < pattern.length) {
            if (!budget.tryConsume()) return RuleMatchResult.BUDGET_EXHAUSTED
            if (target[start + patternIndex] != pattern[patternIndex]) break
            patternIndex++
        }
        if (patternIndex == pattern.length) return RuleMatchResult.MATCH
    }
    return RuleMatchResult.NO_MATCH
}
