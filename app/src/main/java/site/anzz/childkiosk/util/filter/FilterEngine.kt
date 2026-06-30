package site.anzz.childkiosk.util.filter

import java.util.Locale
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicLongArray
import java.util.regex.Pattern

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
    val candidateEvaluationCount: Long,
    val regexEvaluationCount: Long,
    val cosmeticCallCount: Long,
    val scriptletCallCount: Long,
    val generatedCssBytes: Long,
    val generatedScriptletBytes: Long,
    val shouldBlockDurationMicros: FilterPerfSampleStats,
    val decisionDurationMicros: FilterPerfSampleStats,
    val candidateEvaluationsPerDecision: FilterPerfSampleStats,
    val cosmeticDurationMicros: FilterPerfSampleStats,
    val scriptletDurationMicros: FilterPerfSampleStats,
    val importantIndex: FilterIndexStats,
    val exceptionIndex: FilterIndexStats,
    val blockingIndex: FilterIndexStats,
    val removeParamIndex: FilterIndexStats
)

class FilterEngine private constructor(
    private val importantIndex: TokenIndex,
    private val exceptionIndex: TokenIndex,
    private val blockingIndex: TokenIndex,
    private val removeParamIndex: TokenIndex,
    private val cosmeticRules: List<CosmeticFilterRule>,
    private val scriptletRules: List<ScriptletFilterRule>,
    val report: FilterBuildReport,
    private val buildDurationMs: Long
) {
    private val decisionCache = ConcurrentHashMap<String, FilterDecision>(2048, 0.75f, 4)
    private val cosmeticCssCache = ConcurrentHashMap<String, String>(64)
    private val scriptletJsCache = ConcurrentHashMap<String, String>(64)
    private val maxCacheSize = 4096
    private val maxHostCacheSize = 256
    private val perf = FilterPerfTracker(buildDurationMs)
    private val cosmeticIndex = CosmeticIndex(cosmeticRules)
    private val scriptletIndex = ScriptletIndex(scriptletRules)

    private fun cacheDecision(key: String, decision: FilterDecision): FilterDecision {
        if (decisionCache.size > maxCacheSize) decisionCache.clear()
        decisionCache[key] = decision
        return decision
    }

    fun perfSnapshot(): FilterPerfSnapshot {
        return perf.snapshot(
            importantIndex = importantIndex.stats(),
            exceptionIndex = exceptionIndex.stats(),
            blockingIndex = blockingIndex.stats(),
            removeParamIndex = removeParamIndex.stats()
        )
    }

    fun recordShouldBlockDuration(durationNanos: Long) {
        perf.recordShouldBlock(durationNanos)
    }

    internal fun decideLinearForTesting(
        context: FilterRequestContext,
        siteOverride: SiteFilterOverride? = null
    ): FilterDecision {
        if (siteOverride?.isTemporarilyAllowed() == true || siteOverride?.networkDisabled == true) {
            return FilterDecision(FilterAction.EXCEPTION, reason = "site override")
        }
        if (context.requestUrl.isBlank()) return FilterDecision.ALLOW

        val importantBlock = importantIndex.linearFirstMatching(context)
        if (importantBlock != null) {
            return FilterDecision(FilterAction.BLOCK, importantBlock.rule, "important rule")
        }

        val exception = exceptionIndex.linearFirstMatching(context)
        if (exception != null) {
            return FilterDecision(FilterAction.EXCEPTION, exception.rule, "exception rule")
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
            result = cosmeticIndex.cssFor(normalizedHost)
            if (cosmeticCssCache.size > maxHostCacheSize) cosmeticCssCache.clear()
            cosmeticCssCache[normalizedHost] = result
            return result
        } finally {
            perf.recordCosmetic(System.nanoTime() - startedAt, result.length)
        }
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

    fun cleanUrlForNavigation(url: String, topLevelUrl: String): String? {
        if (url.isBlank() || !url.startsWith("http")) return null
        val context = FilterRequestContext(
            requestUrl = url,
            topLevelUrl = topLevelUrl.ifBlank { url },
            resourceType = FilterResourceType.DOCUMENT,
            isMainFrame = true,
            method = "GET",
            hasGesture = false
        )
        val paramsToRemove = removeParamIndex.candidates(url, context.requestHost)
            .filter { it.rule.removeParams.isNotEmpty() }
            .filter { it.matches(context) }
            .flatMap { it.rule.removeParams.asSequence() }
            .toSet()
        if (paramsToRemove.isEmpty()) return null
        return removeParamsFromUrl(url, paramsToRemove)
    }

    fun decide(context: FilterRequestContext, siteOverride: SiteFilterOverride? = null): FilterDecision {
        val startedAt = System.nanoTime()
        var evaluatedCandidates = 0
        try {
            if (siteOverride?.isTemporarilyAllowed() == true || siteOverride?.networkDisabled == true) {
                return FilterDecision(FilterAction.EXCEPTION, reason = "site override")
            }
            if (context.requestUrl.isBlank()) return FilterDecision.ALLOW
            val cacheKey = "${context.requestUrl}|${context.topLevelHost}|${context.resourceType}|${context.isThirdParty}"
            decisionCache[cacheKey]?.let {
                perf.recordCacheHit()
                return it
            }
            perf.recordCacheMiss()

            val url = context.requestUrl
            val host = context.requestHost

            val importantMatch = importantIndex.firstMatching(url, host, context, perf)
            evaluatedCandidates += importantMatch.evaluatedCount
            val importantBlock = importantMatch.rule
            if (importantBlock != null) {
                return cacheDecision(cacheKey,
                    FilterDecision(FilterAction.BLOCK, importantBlock.rule, "important rule"))
            }

            val exceptionMatch = exceptionIndex.firstMatching(url, host, context, perf)
            evaluatedCandidates += exceptionMatch.evaluatedCount
            val exception = exceptionMatch.rule
            if (exception != null) {
                return cacheDecision(cacheKey,
                    FilterDecision(FilterAction.EXCEPTION, exception.rule, "exception rule"))
            }

            val blockMatch = blockingIndex.firstMatching(url, host, context, perf)
            evaluatedCandidates += blockMatch.evaluatedCount
            val block = blockMatch.rule
            val decision = if (block != null) {
                FilterDecision(FilterAction.BLOCK, block.rule, "blocking rule")
            } else {
                FilterDecision.ALLOW
            }
            return cacheDecision(cacheKey, decision)
        } finally {
            perf.recordDecision(System.nanoTime() - startedAt, evaluatedCandidates)
        }
    }

    companion object {
        val EMPTY = FilterEngine(
            TokenIndex(emptyList()), TokenIndex(emptyList()), TokenIndex(emptyList()), TokenIndex(emptyList()),
            emptyList(), emptyList(),
            FilterBuildReport(0, 0, 0, 0, 0, 0, emptyList(), emptyList()),
            buildDurationMs = 0L
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
                val parseResult = FilterRuleParser.parse(source.rulesText, source.id, source.name)
                reports += FilterSourceReport(
                    sourceId = source.id,
                    sourceName = source.name,
                    totalLines = parseResult.totalLines,
                    enabledRules = parseResult.enabledRules,
                    unsupportedRules = parseResult.unsupportedRules,
                    networkRules = parseResult.rules.count { !it.badFilter },
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

            val important = activeCompiled.filter { !it.rule.isException && it.rule.important }
            val exceptions = activeCompiled.filter { it.rule.isException }
            val blocking = activeCompiled.filter { !it.rule.isException && !it.rule.important }
            val removeParam = blocking.filter { it.rule.removeParams.isNotEmpty() }
            val enabledRuleCount = activeCompiled.size
            val unsupportedRuleCount = reports.sumOf { it.unsupportedRules }
            val buildDurationMs = (System.nanoTime() - startedAt) / 1_000_000L
            return FilterEngine(
                importantIndex = TokenIndex(important.sortedByDescending { it.weight }),
                exceptionIndex = TokenIndex(exceptions.sortedByDescending { it.weight }),
                blockingIndex = TokenIndex(blocking.sortedByDescending { it.weight }),
                removeParamIndex = TokenIndex(removeParam.sortedByDescending { it.weight }),
                cosmeticRules = cosmetic,
                scriptletRules = scriptlets,
                report = FilterBuildReport(
                    ruleCount = reports.sumOf { it.totalLines },
                    enabledRuleCount = enabledRuleCount,
                    unsupportedRuleCount = unsupportedRuleCount,
                    networkRuleCount = activeCompiled.size,
                    cosmeticRuleCount = cosmetic.count { !it.isException },
                    scriptletRuleCount = scriptlets.count { it.supported },
                    sourceReports = reports,
                    errors = errors.take(30)
                ),
                buildDurationMs = buildDurationMs
            )
        }
    }
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
        val exceptions = matchingRules(host, globalExceptions, domainExceptions)
            .map { it.selector }
            .toSet()
        val selectors = matchingRules(host, globalRules, domainRules)
            .asSequence()
            .map { it.selector }
            .filterNot { it in exceptions }
            .filter { isSafeCssSelector(it) }
            .take(800)
            .toList()
        if (selectors.isEmpty()) return ""
        return selectors.joinToString(",\n") + " { display: none !important; visibility: hidden !important; }"
    }

    private fun matchingRules(
        host: String,
        global: List<CosmeticFilterRule>,
        domainMap: Map<String, List<CosmeticFilterRule>>
    ): List<CosmeticFilterRule> {
        val result = mutableListOf<CosmeticFilterRule>()
        global.filterTo(result) { rule -> rule.excludedDomains.none { isSameOrSubdomain(host, it) } }
        forEachHostSuffix(host) { suffix ->
            domainMap[suffix]?.filterTo(result) { it.matchesHost(host) }
        }
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
    val raw = rawText.trim()
    val optionIndex = raw.indexOf('$')
    if (optionIndex < 0) return raw
    val pattern = raw.substring(0, optionIndex)
    val options = raw.substring(optionIndex + 1)
        .split(',')
        .map { it.trim() }
        .filter { it.isNotBlank() && !it.equals("badfilter", ignoreCase = true) }
    return if (options.isEmpty()) {
        pattern
    } else {
        pattern + "$" + options.joinToString(",")
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

private fun removeParamsFromUrl(url: String, params: Set<String>): String? {
    val uri = URI(url)
    val rawQuery = uri.rawQuery ?: return null
    val pairs = rawQuery.split("&")
        .filter { it.isNotBlank() }
        .map { item ->
            val name = item.substringBefore("=")
            val value = item.substringAfter("=", missingDelimiterValue = "")
            name to value
        }
    val toRemove = pairs.map { it.first }.filter { name ->
        params.any { param ->
            if (param.endsWith("*")) name.startsWith(param.removeSuffix("*")) else name == param
        }
    }.toSet()
    if (toRemove.isEmpty()) return null
    val newQuery = pairs
        .filterNot { it.first in toRemove }
        .joinToString("&") { (name, value) ->
            if (value.isBlank()) name else "$name=$value"
        }
    return URI(
        uri.scheme,
        uri.authority,
        uri.path,
        newQuery.ifBlank { null },
        uri.fragment
    ).toString()
}

private fun isSafeCssSelector(selector: String): Boolean {
    if (selector.length !in 1..300) return false
    val unsupported = listOf(":-abp-", ":has-text", ":contains(", ":matches-css", ":xpath", "##", "#@#")
    return unsupported.none { selector.contains(it, ignoreCase = true) }
}

data class FilterRuleSource(
    val id: String,
    val name: String,
    val rulesText: String
)

private class FilterPerfTracker(private val buildDurationMs: Long) {
    private val decisionCount = AtomicLong(0L)
    private val cacheHitCount = AtomicLong(0L)
    private val cacheMissCount = AtomicLong(0L)
    private val candidateEvaluationCount = AtomicLong(0L)
    private val regexEvaluationCount = AtomicLong(0L)
    private val cosmeticCallCount = AtomicLong(0L)
    private val scriptletCallCount = AtomicLong(0L)
    private val generatedCssBytes = AtomicLong(0L)
    private val generatedScriptletBytes = AtomicLong(0L)

    private val shouldBlockDurations = AtomicLongSampler()
    private val decisionDurations = AtomicLongSampler()
    private val candidateCounts = AtomicLongSampler()
    private val cosmeticDurations = AtomicLongSampler()
    private val scriptletDurations = AtomicLongSampler()

    fun recordShouldBlock(durationNanos: Long) {
        shouldBlockDurations.record(nanosToMicros(durationNanos))
    }

    fun recordCacheHit() {
        cacheHitCount.incrementAndGet()
    }

    fun recordCacheMiss() {
        cacheMissCount.incrementAndGet()
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
            candidateEvaluationCount = candidateEvaluationCount.get(),
            regexEvaluationCount = regexEvaluationCount.get(),
            cosmeticCallCount = cosmeticCallCount.get(),
            scriptletCallCount = scriptletCallCount.get(),
            generatedCssBytes = generatedCssBytes.get(),
            generatedScriptletBytes = generatedScriptletBytes.get(),
            shouldBlockDurationMicros = shouldBlockDurations.snapshot(),
            decisionDurationMicros = decisionDurations.snapshot(),
            candidateEvaluationsPerDecision = candidateCounts.snapshot(),
            cosmeticDurationMicros = cosmeticDurations.snapshot(),
            scriptletDurationMicros = scriptletDurations.snapshot(),
            importantIndex = importantIndex,
            exceptionIndex = exceptionIndex,
            blockingIndex = blockingIndex,
            removeParamIndex = removeParamIndex
        )
    }

    private fun nanosToMicros(durationNanos: Long): Long {
        return (durationNanos / 1_000L).coerceAtLeast(0L)
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
                rule.rule.matchType == FilterMatchType.DOMAIN_ANCHOR && rule.anchorHost.isNotBlank() -> {
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

    fun candidates(url: String, host: String): List<CompiledRule> {
        val seen = HashSet<CompiledRule>()
        val candidates = mutableListOf<CompiledRule>()

        fun addRules(rules: List<CompiledRule>?) {
            if (rules == null) return
            for (rule in rules) {
                if (seen.add(rule)) candidates += rule
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

        // 2. URL literal gram keys. A rule indexed by "adsby" can match
        // "adsbygoogle.js" without requiring exact URL-token equality.
        val seenKeys = HashSet<String>()
        extractUrlIndexKeys(url.lowercase(Locale.US)) { key ->
            if (seenKeys.add(key)) {
                addRules(tokenMap[key])
            }
        }

        // 3. Universal rules (no safe extractable key).
        addRules(universalRules)

        candidates.sortBy { ruleOrder[it] ?: Int.MAX_VALUE }
        return candidates
    }

    fun stats(): FilterIndexStats {
        return FilterIndexStats(
            tokenBucketCount = domainMap.size + tokenMap.size,
            indexedRuleCount = indexedRuleCount,
            universalRuleCount = universalRules.size
        )
    }

    fun linearFirstMatching(context: FilterRequestContext): CompiledRule? {
        return orderedRules.firstOrNull { it.matches(context) }
    }
}

private data class CandidateMatch(
    val rule: CompiledRule?,
    val evaluatedCount: Int
)

private fun TokenIndex.firstMatching(
    url: String,
    host: String,
    context: FilterRequestContext,
    perf: FilterPerfTracker
): CandidateMatch {
    var evaluated = 0
    for (rule in candidates(url, host)) {
        evaluated++
        if (rule.matches(context, perf)) {
            return CandidateMatch(rule = rule, evaluatedCount = evaluated)
        }
    }
    return CandidateMatch(rule = null, evaluatedCount = evaluated)
}

private const val URL_INDEX_GRAM_LENGTH = 5

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
    private val regex: Pattern? = null,
    private val wildcardRegex: Pattern? = null
) {
    val weight: Int = when (rule.matchType) {
        FilterMatchType.DOMAIN_ANCHOR -> 100
        FilterMatchType.REGEX -> 80
        FilterMatchType.STARTS_WITH,
        FilterMatchType.ENDS_WITH -> 60
        FilterMatchType.SUBSTRING -> 20
    } + if (rule.domains.isNotEmpty()) 20 else 0

    // ---- Precomputed fields (computed once at construction) ----
    val patternLower: String = rule.pattern.lowercase(Locale.US)

    // DOMAIN_ANCHOR only: precomputed host and path from pattern
    val anchorHost: String
    val anchorPath: String

    // Token for reverse index bucketing
    val bestToken: String
    val indexKey: String

    init {
        val rawForAnchor = rule.pattern
            .removePrefix("http://")
            .removePrefix("https://")
        anchorHost = rawForAnchor.substringBefore("^").substringBefore("/").normalizeHost()
        anchorPath = rawForAnchor.substringAfter("/", missingDelimiterValue = "")
            .substringBefore("^").lowercase(Locale.US)
        bestToken = extractBestToken(rule)
        indexKey = indexKeyFor(bestToken)
    }

    fun matches(context: FilterRequestContext, perf: FilterPerfTracker? = null): Boolean {
        if (!matchesOptions(context)) return false
        return when (rule.matchType) {
            FilterMatchType.DOMAIN_ANCHOR -> matchesDomainAnchor(context)
            FilterMatchType.STARTS_WITH -> context.requestUrlLower.startsWith(patternLower)
            FilterMatchType.ENDS_WITH -> context.requestUrlLower.endsWith(patternLower)
            FilterMatchType.REGEX -> {
                perf?.recordRegexEvaluation()
                regex?.matcher(context.requestUrl)?.find() == true
            }
            FilterMatchType.SUBSTRING -> matchesWildcard(context.requestUrlLower, rule.pattern, wildcardRegex)
        }
    }

    private fun matchesOptions(context: FilterRequestContext): Boolean {
        if (rule.resourceTypes.isNotEmpty() && context.resourceType !in rule.resourceTypes) return false
        if (context.resourceType in rule.excludedResourceTypes) return false
        if (rule.thirdParty != null && context.isThirdParty != rule.thirdParty) return false
        if (rule.excludedDomains.any { isSameOrSubdomain(context.topLevelHost, it) }) return false
        if (rule.domains.isNotEmpty() && rule.domains.none { isSameOrSubdomain(context.topLevelHost, it) }) return false
        return true
    }

    private fun matchesDomainAnchor(context: FilterRequestContext): Boolean {
        if (anchorHost.isBlank()) return false
        if (!isSameOrSubdomain(context.requestHost, anchorHost)) return false
        if (anchorPath.isBlank()) return true
        return context.requestUrlLower.contains(anchorPath)
    }

    companion object {
        fun from(rule: FilterRule): CompiledRule? {
            val regex = if (rule.matchType == FilterMatchType.REGEX) {
                if (!FilterRuleParser.isRegexSafe(rule.pattern)) return null
                runCatching { Pattern.compile(rule.pattern) }.getOrNull() ?: return null
            } else {
                null
            }
            val wildcardRegex = if (
                rule.matchType == FilterMatchType.SUBSTRING &&
                (rule.pattern.contains("*") || rule.pattern.contains("^"))
            ) {
                runCatching { Pattern.compile(patternToRegex(rule.pattern.lowercase(Locale.US))) }.getOrNull()
            } else {
                null
            }
            return CompiledRule(rule, regex, wildcardRegex)
        }

        private val TOKEN_REGEX = Regex("[a-zA-Z0-9][a-zA-Z0-9.\\-]{2,}")

        private fun extractBestToken(rule: FilterRule): String {
            // For DOMAIN_ANCHOR, prefer the host part as token
            if (rule.matchType == FilterMatchType.DOMAIN_ANCHOR) {
                val host = rule.pattern
                    .removePrefix("||")
                    .removePrefix("http://").removePrefix("https://")
                    .substringBefore("^").substringBefore("/")
                    .normalizeHost()
                if (host.length >= 3) return host
            }
            // REGEX rules have no reliable literal token
            if (rule.matchType == FilterMatchType.REGEX) return ""
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

private fun matchesWildcard(lowerUrl: String, rawPattern: String, wildcardRegex: Pattern?): Boolean {
    val pattern = rawPattern
        .trim()
        .removePrefix("|")
        .removeSuffix("|")
        .lowercase(Locale.US)
    if (pattern.isBlank()) return false
    if (!pattern.contains("*") && !pattern.contains("^")) {
        return lowerUrl.contains(pattern)
    }
    return wildcardRegex?.matcher(lowerUrl)?.find() == true
}

private fun patternToRegex(pattern: String): String {
    val builder = StringBuilder()
    pattern.forEach { char ->
        when (char) {
            '*' -> builder.append(".*")
            '^' -> builder.append("(?:[^A-Za-z0-9_\\-.%]|$)")
            else -> builder.append(Regex.escape(char.toString()))
        }
    }
    return builder.toString()
}
