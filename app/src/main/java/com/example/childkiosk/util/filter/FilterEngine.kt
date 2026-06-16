package com.example.childkiosk.util.filter

import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Pattern

data class FilterBuildReport(
    val ruleCount: Int,
    val enabledRuleCount: Int,
    val unsupportedRuleCount: Int,
    val sourceReports: List<FilterSourceReport>,
    val errors: List<String>
)

data class FilterSourceReport(
    val sourceId: String,
    val sourceName: String,
    val totalLines: Int,
    val enabledRules: Int,
    val unsupportedRules: Int,
    val errors: List<String>
)

class FilterEngine private constructor(
    private val blockingRules: List<CompiledRule>,
    private val exceptionRules: List<CompiledRule>,
    private val importantBlockingRules: List<CompiledRule>,
    private val cosmeticRules: List<CosmeticFilterRule>,
    val report: FilterBuildReport
) {
    private val decisionCache = object : LinkedHashMap<String, FilterDecision>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, FilterDecision>?): Boolean {
            return size > 512
        }
    }

    fun cosmeticCssFor(host: String, siteOverride: SiteFilterOverride? = null): String {
        if (siteOverride?.isTemporarilyAllowed() == true || siteOverride?.cosmeticDisabled == true) return ""
        val normalizedHost = host.normalizeHost()
        if (normalizedHost.isBlank()) return ""
        val exceptions = cosmeticRules
            .filter { it.isException && it.matchesHost(normalizedHost) }
            .map { it.selector }
            .toSet()
        val selectors = cosmeticRules
            .asSequence()
            .filter { !it.isException }
            .filter { it.matchesHost(normalizedHost) }
            .map { it.selector }
            .filterNot { it in exceptions }
            .filter { isSafeCssSelector(it) }
            .take(800)
            .toList()
        if (selectors.isEmpty()) return ""
        return selectors.joinToString(",\n") + " { display: none !important; visibility: hidden !important; }"
    }

    @Synchronized
    fun decide(context: FilterRequestContext, siteOverride: SiteFilterOverride? = null): FilterDecision {
        if (siteOverride?.isTemporarilyAllowed() == true || siteOverride?.networkDisabled == true) {
            return FilterDecision(FilterAction.EXCEPTION, reason = "site override")
        }
        if (context.requestUrl.isBlank()) return FilterDecision.ALLOW
        val cacheKey = "${context.requestUrl}|${context.topLevelHost}|${context.resourceType}|${context.isThirdParty}"
        decisionCache[cacheKey]?.let { return it }

        val importantBlock = importantBlockingRules.firstOrNull { it.matches(context) }
        if (importantBlock != null) {
            return FilterDecision(FilterAction.BLOCK, importantBlock.rule, "important rule").also {
                decisionCache[cacheKey] = it
            }
        }

        val exception = exceptionRules.firstOrNull { it.matches(context) }
        if (exception != null) {
            return FilterDecision(FilterAction.EXCEPTION, exception.rule, "exception rule").also {
                decisionCache[cacheKey] = it
            }
        }

        val block = blockingRules.firstOrNull { it.matches(context) }
        val decision = if (block != null) {
            FilterDecision(FilterAction.BLOCK, block.rule, "blocking rule")
        } else {
            FilterDecision.ALLOW
        }
        decisionCache[cacheKey] = decision
        return decision
    }

    companion object {
        val EMPTY = FilterEngine(emptyList(), emptyList(), emptyList(), emptyList(), FilterBuildReport(0, 0, 0, emptyList(), emptyList()))

        fun build(sources: List<FilterRuleSource>): FilterEngine {
            val compiled = mutableListOf<CompiledRule>()
            val cosmetic = mutableListOf<CosmeticFilterRule>()
            val reports = mutableListOf<FilterSourceReport>()
            val errors = mutableListOf<String>()

            sources.forEach { source ->
                val parseResult = FilterRuleParser.parse(source.rulesText, source.id, source.name)
                reports += FilterSourceReport(
                    sourceId = source.id,
                    sourceName = source.name,
                    totalLines = parseResult.totalLines,
                    enabledRules = parseResult.enabledRules,
                    unsupportedRules = parseResult.unsupportedRules,
                    errors = parseResult.errors
                )
                errors += parseResult.errors
                cosmetic += parseResult.cosmeticRules
                parseResult.rules.filter { !it.badFilter }.forEach { rule ->
                    CompiledRule.from(rule)?.let { compiledRule ->
                        compiled += compiledRule
                    } ?: run {
                        errors += "规则编译失败: ${rule.rawText}"
                    }
                }
            }

            val badFilters = sources.flatMap {
                FilterRuleParser.parse(it.rulesText, it.id, it.name).rules.filter { rule -> rule.badFilter }
            }.map { it.rawText.removeSuffix("\$badfilter") }.toSet()

            val activeCompiled = if (badFilters.isEmpty()) {
                compiled
            } else {
                compiled.filterNot { it.rule.rawText in badFilters }
            }

            val important = activeCompiled.filter { !it.rule.isException && it.rule.important }
            val exceptions = activeCompiled.filter { it.rule.isException }
            val blocking = activeCompiled.filter { !it.rule.isException && !it.rule.important }
            val enabledRuleCount = activeCompiled.size
            val unsupportedRuleCount = reports.sumOf { it.unsupportedRules }
            return FilterEngine(
                blockingRules = blocking.sortedByDescending { it.weight },
                exceptionRules = exceptions.sortedByDescending { it.weight },
                importantBlockingRules = important.sortedByDescending { it.weight },
                cosmeticRules = cosmetic,
                report = FilterBuildReport(
                    ruleCount = reports.sumOf { it.totalLines },
                    enabledRuleCount = enabledRuleCount,
                    unsupportedRuleCount = unsupportedRuleCount,
                    sourceReports = reports,
                    errors = errors.take(30)
                )
            )
        }
    }
}

private fun CosmeticFilterRule.matchesHost(host: String): Boolean {
    if (excludedDomains.any { isSameOrSubdomain(host, it) }) return false
    return domains.isEmpty() || domains.any { isSameOrSubdomain(host, it) }
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

    fun matches(context: FilterRequestContext): Boolean {
        if (!matchesOptions(context)) return false
        return when (rule.matchType) {
            FilterMatchType.DOMAIN_ANCHOR -> matchesDomainAnchor(context)
            FilterMatchType.STARTS_WITH -> context.requestUrl.lowercase(Locale.US).startsWith(rule.pattern.lowercase(Locale.US))
            FilterMatchType.ENDS_WITH -> context.requestUrl.lowercase(Locale.US).endsWith(rule.pattern.lowercase(Locale.US))
            FilterMatchType.REGEX -> regex?.matcher(context.requestUrl)?.find() == true
            FilterMatchType.SUBSTRING -> matchesWildcard(context.requestUrl, rule.pattern, wildcardRegex)
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
        val pattern = rule.pattern
            .removePrefix("http://")
            .removePrefix("https://")
            .substringBefore("/")
            .normalizeHost()
        if (pattern.isBlank()) return false
        if (isSameOrSubdomain(context.requestHost, pattern)) return true
        return context.requestUrl.lowercase(Locale.US).contains(pattern.lowercase(Locale.US))
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
    }
}

private fun matchesWildcard(url: String, rawPattern: String, wildcardRegex: Pattern?): Boolean {
    val lowerUrl = url.lowercase(Locale.US)
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
