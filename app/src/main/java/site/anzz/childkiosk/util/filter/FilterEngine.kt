package site.anzz.childkiosk.util.filter

import java.util.Locale
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
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

class FilterEngine private constructor(
    private val importantIndex: TokenIndex,
    private val exceptionIndex: TokenIndex,
    private val blockingIndex: TokenIndex,
    private val cosmeticRules: List<CosmeticFilterRule>,
    private val scriptletRules: List<ScriptletFilterRule>,
    val report: FilterBuildReport
) {
    private val decisionCache = ConcurrentHashMap<String, FilterDecision>(2048, 0.75f, 4)
    private val maxCacheSize = 4096

    private fun cacheDecision(key: String, decision: FilterDecision): FilterDecision {
        if (decisionCache.size > maxCacheSize) decisionCache.clear()
        decisionCache[key] = decision
        return decision
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

    fun scriptletJsFor(host: String, siteOverride: SiteFilterOverride? = null): String {
        if (siteOverride?.isTemporarilyAllowed() == true || siteOverride?.scriptletDisabled == true) return ""
        val normalizedHost = host.normalizeHost()
        if (normalizedHost.isBlank()) return ""
        return scriptletRules
            .asSequence()
            .filter { it.matchesHost(normalizedHost) }
            .mapNotNull { scriptletToJs(it) }
            .take(80)
            .joinToString("\n")
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
        val paramsToRemove = blockingIndex.candidates(url, context.requestHost)
            .filter { it.rule.removeParams.isNotEmpty() }
            .filter { it.matches(context) }
            .flatMap { it.rule.removeParams.asSequence() }
            .toSet()
        if (paramsToRemove.isEmpty()) return null
        return removeParamsFromUrl(url, paramsToRemove)
    }

    fun decide(context: FilterRequestContext, siteOverride: SiteFilterOverride? = null): FilterDecision {
        if (siteOverride?.isTemporarilyAllowed() == true || siteOverride?.networkDisabled == true) {
            return FilterDecision(FilterAction.EXCEPTION, reason = "site override")
        }
        if (context.requestUrl.isBlank()) return FilterDecision.ALLOW
        val cacheKey = "${context.requestUrl}|${context.topLevelHost}|${context.resourceType}|${context.isThirdParty}"
        decisionCache[cacheKey]?.let { return it }

        val url = context.requestUrl
        val host = context.requestHost

        val importantBlock = importantIndex.candidates(url, host)
            .firstOrNull { it.matches(context) }
        if (importantBlock != null) {
            return cacheDecision(cacheKey,
                FilterDecision(FilterAction.BLOCK, importantBlock.rule, "important rule"))
        }

        val exception = exceptionIndex.candidates(url, host)
            .firstOrNull { it.matches(context) }
        if (exception != null) {
            return cacheDecision(cacheKey,
                FilterDecision(FilterAction.EXCEPTION, exception.rule, "exception rule"))
        }

        val block = blockingIndex.candidates(url, host)
            .firstOrNull { it.matches(context) }
        val decision = if (block != null) {
            FilterDecision(FilterAction.BLOCK, block.rule, "blocking rule")
        } else {
            FilterDecision.ALLOW
        }
        return cacheDecision(cacheKey, decision)
    }

    companion object {
        val EMPTY = FilterEngine(
            TokenIndex(emptyList()), TokenIndex(emptyList()), TokenIndex(emptyList()),
            emptyList(), emptyList(),
            FilterBuildReport(0, 0, 0, 0, 0, 0, emptyList(), emptyList())
        )

        fun build(sources: List<FilterRuleSource>): FilterEngine {
            val compiled = mutableListOf<CompiledRule>()
            val cosmetic = mutableListOf<CosmeticFilterRule>()
            val scriptlets = mutableListOf<ScriptletFilterRule>()
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
                    networkRules = parseResult.rules.count { !it.badFilter },
                    cosmeticRules = parseResult.cosmeticRules.count { !it.isException },
                    scriptletRules = parseResult.scriptletRules.count { it.supported },
                    errors = parseResult.errors
                )
                errors += parseResult.errors
                cosmetic += parseResult.cosmeticRules
                scriptlets += parseResult.scriptletRules.filter { it.supported }
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
            }.map { it.canonicalBadFilterTarget() }.toSet()

            val activeCompiled = if (badFilters.isEmpty()) {
                compiled
            } else {
                compiled.filterNot { it.rule.rawText in badFilters || it.rule.canonicalBadFilterTarget() in badFilters }
            }

            val important = activeCompiled.filter { !it.rule.isException && it.rule.important }
            val exceptions = activeCompiled.filter { it.rule.isException }
            val blocking = activeCompiled.filter { !it.rule.isException && !it.rule.important }
            val enabledRuleCount = activeCompiled.size
            val unsupportedRuleCount = reports.sumOf { it.unsupportedRules }
            return FilterEngine(
                importantIndex = TokenIndex(important.sortedByDescending { it.weight }),
                exceptionIndex = TokenIndex(exceptions.sortedByDescending { it.weight }),
                blockingIndex = TokenIndex(blocking.sortedByDescending { it.weight }),
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
                )
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

/**
 * Token-based reverse index for fast rule lookup.
 * Rules are bucketed by their best token; queries extract tokens from the URL
 * and only check matching buckets + universal (token-less) rules.
 */
private class TokenIndex(rules: List<CompiledRule>) {
    private val tokenMap: HashMap<String, MutableList<CompiledRule>>
    private val universalRules: List<CompiledRule>

    init {
        val map = HashMap<String, MutableList<CompiledRule>>(rules.size / 2 + 1)
        val universal = mutableListOf<CompiledRule>()
        for (rule in rules) {
            val token = rule.bestToken
            if (token.isEmpty()) {
                universal.add(rule)
            } else {
                map.getOrPut(token) { mutableListOf() }.add(rule)
            }
        }
        tokenMap = map
        universalRules = universal
    }

    fun candidates(url: String, host: String): Sequence<CompiledRule> = sequence {
        val seen = HashSet<CompiledRule>()

        // 1. Host token (highest priority)
        tokenMap[host]?.let { rules ->
            for (rule in rules) { if (seen.add(rule)) yield(rule) }
        }

        // 2. Parent domain tokens
        var dotIdx = host.indexOf('.')
        while (dotIdx > 0) {
            val parent = host.substring(dotIdx + 1)
            tokenMap[parent]?.let { rules ->
                for (rule in rules) { if (seen.add(rule)) yield(rule) }
            }
            dotIdx = host.indexOf('.', dotIdx + 1)
        }

        // 3. URL path tokens
        extractUrlTokens(url.lowercase(Locale.US)) { token ->
            tokenMap[token]?.let { rules ->
                for (rule in rules) { if (seen.add(rule)) yield(rule) }
            }
        }

        // 4. Universal rules (no extractable token)
        for (rule in universalRules) { yield(rule) }
    }
}

private inline fun extractUrlTokens(urlLower: String, onToken: (String) -> Unit) {
    var start = -1
    for (i in urlLower.indices) {
        val c = urlLower[i]
        if (c.isLetterOrDigit() || c == '.' || c == '-') {
            if (start < 0) start = i
        } else {
            if (start >= 0 && i - start >= 3) {
                onToken(urlLower.substring(start, i))
            }
            start = -1
        }
    }
    if (start >= 0 && urlLower.length - start >= 3) {
        onToken(urlLower.substring(start))
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

    init {
        val rawForAnchor = rule.pattern
            .removePrefix("http://")
            .removePrefix("https://")
        anchorHost = rawForAnchor.substringBefore("^").substringBefore("/").normalizeHost()
        anchorPath = rawForAnchor.substringAfter("/", missingDelimiterValue = "")
            .substringBefore("^").lowercase(Locale.US)
        bestToken = extractBestToken(rule)
    }

    fun matches(context: FilterRequestContext): Boolean {
        if (!matchesOptions(context)) return false
        return when (rule.matchType) {
            FilterMatchType.DOMAIN_ANCHOR -> matchesDomainAnchor(context)
            FilterMatchType.STARTS_WITH -> context.requestUrlLower.startsWith(patternLower)
            FilterMatchType.ENDS_WITH -> context.requestUrlLower.endsWith(patternLower)
            FilterMatchType.REGEX -> regex?.matcher(context.requestUrl)?.find() == true
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
