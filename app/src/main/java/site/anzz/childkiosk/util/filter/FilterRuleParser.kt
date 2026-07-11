package site.anzz.childkiosk.util.filter

import java.util.Locale

data class FilterRule(
    val rawText: String,
    val sourceId: String,
    val sourceName: String,
    val pattern: String,
    val matchType: FilterMatchType,
    val isException: Boolean = false,
    val resourceTypes: Set<FilterResourceType> = emptySet(),
    val excludedResourceTypes: Set<FilterResourceType> = emptySet(),
    val thirdParty: Boolean? = null,
    val domains: Set<String> = emptySet(),
    val excludedDomains: Set<String> = emptySet(),
    val important: Boolean = false,
    val badFilter: Boolean = false,
    val removeParams: Set<String> = emptySet(),
    val unsupportedOptions: Set<String> = emptySet(),
    val startAnchored: Boolean = false,
    val endAnchored: Boolean = false,
    val domainAnchored: Boolean = false,
    val hasWildcard: Boolean = false,
    val hasSeparator: Boolean = false,
    val sourceTier: FilterRuleSourceTier = FilterRuleSourceTier.SUBSCRIPTION
)

data class CosmeticFilterRule(
    val rawText: String,
    val sourceId: String,
    val sourceName: String,
    val selector: String,
    val domains: Set<String> = emptySet(),
    val excludedDomains: Set<String> = emptySet(),
    val isException: Boolean = false
)

data class ScriptletFilterRule(
    val rawText: String,
    val sourceId: String,
    val sourceName: String,
    val name: String,
    val args: List<String>,
    val domains: Set<String> = emptySet(),
    val excludedDomains: Set<String> = emptySet(),
    val supported: Boolean = true
)

enum class FilterMatchType {
    SUBSTRING,
    DOMAIN_ANCHOR,
    STARTS_WITH,
    ENDS_WITH,
    REGEX
}

data class FilterParseResult(
    val rules: List<FilterRule>,
    val cosmeticRules: List<CosmeticFilterRule>,
    val scriptletRules: List<ScriptletFilterRule>,
    val totalLines: Int,
    val enabledRules: Int,
    val unsupportedRules: Int,
    val errors: List<String>
)

object FilterRuleParser {

    fun parse(
        text: String,
        sourceId: String,
        sourceName: String,
        sourceTier: FilterRuleSourceTier = FilterRuleSourceTier.SUBSCRIPTION
    ): FilterParseResult {
        val rules = mutableListOf<FilterRule>()
        val cosmeticRules = mutableListOf<CosmeticFilterRule>()
        val scriptletRules = mutableListOf<ScriptletFilterRule>()
        val errors = mutableListOf<String>()
        var totalLines = 0
        var unsupportedRules = 0

        text.lineSequence().forEachIndexed { index, rawLine ->
            val line = rawLine.trim()
            if (
                line.isBlank() ||
                line.startsWith("!") ||
                line.startsWith("[") ||
                line.startsWith("/*")
            ) {
                return@forEachIndexed
            }
            totalLines++

            val scriptletMarker = supportedScriptletMarker(line)
            if (scriptletMarker != null) {
                val rule = parseScriptletLine(line, sourceId, sourceName, scriptletMarker)
                if (rule == null) {
                    unsupportedRules++
                } else {
                    scriptletRules += rule
                    if (!rule.supported) unsupportedRules++
                }
                return@forEachIndexed
            }

            if (containsUnsupportedCosmeticMarker(line)) {
                unsupportedRules++
                return@forEachIndexed
            }

            val cosmeticMarker = standardCosmeticMarker(line)
            if (cosmeticMarker != null) {
                val rule = parseCosmeticLine(line, sourceId, sourceName, cosmeticMarker)
                if (rule == null) {
                    unsupportedRules++
                } else {
                    cosmeticRules += rule
                }
                return@forEachIndexed
            }

            if (line.startsWith("#")) {
                unsupportedRules++
                return@forEachIndexed
            }

            parseHostsLine(line, sourceId, sourceName, sourceTier)?.let { rule ->
                rules += rule
                return@forEachIndexed
            }

            val parsed = runCatching { parseAdblockLine(line, sourceId, sourceName, sourceTier) }
                .onFailure { errors += "第 ${index + 1} 行解析失败: ${it.message ?: line}" }
                .getOrNull()
            if (parsed == null) {
                unsupportedRules++
            } else {
                rules += parsed
            }
        }

        val enabledRules = rules.count { !it.badFilter }
        return FilterParseResult(
            rules = rules,
            cosmeticRules = cosmeticRules,
            scriptletRules = scriptletRules,
            totalLines = totalLines,
            enabledRules = enabledRules +
                cosmeticRules.count { !it.isException } +
                scriptletRules.count { it.supported },
            unsupportedRules = unsupportedRules,
            errors = errors.take(20)
        )
    }

    private fun supportedScriptletMarker(line: String): String? {
        return when {
            "##+js(" in line -> "##+js("
            "#%#//scriptlet(" in line -> "#%#//scriptlet("
            else -> null
        }
    }

    private fun containsUnsupportedCosmeticMarker(line: String): Boolean {
        if (UNSUPPORTED_COSMETIC_MARKERS.any { it in line }) return true
        return line.contains("#@#^") ||
            line.contains("##^") ||
            line.contains("##+js") ||
            line.contains("#@#+js")
    }

    private fun standardCosmeticMarker(line: String): String? {
        return when {
            "#@#" in line -> "#@#"
            "##" in line -> "##"
            else -> null
        }
    }

    private fun parseScriptletLine(
        line: String,
        sourceId: String,
        sourceName: String,
        marker: String
    ): ScriptletFilterRule? {
        val markerIndex = line.indexOf(marker)
        if (markerIndex < 0 || !line.endsWith(")")) return null
        val domainPart = line.substring(0, markerIndex).trim()
        val body = line.substring(markerIndex + marker.length, line.length - 1).trim()
        if (body.isBlank()) return null
        val parts = splitScriptletArgs(body)
        val name = parts.firstOrNull()?.trim('\'', '"')?.trim().orEmpty()
        if (name.isBlank()) return null
        val scope = parseDomainScope(domainPart) ?: return null
        return ScriptletFilterRule(
            rawText = line,
            sourceId = sourceId,
            sourceName = sourceName,
            name = name,
            args = parts.drop(1)
                .map { it.trim('\'', '"').trim() }
                .filter { it.length <= MAX_SCRIPTLET_ARGUMENT_LENGTH },
            domains = scope.included,
            excludedDomains = scope.excluded,
            supported = isSupportedScriptletName(name)
        )
    }

    private fun splitScriptletArgs(body: String): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()
        var quote: Char? = null
        body.forEach { char ->
            when {
                quote != null && char == quote -> {
                    quote = null
                    current.append(char)
                }
                quote == null && (char == '\'' || char == '"') -> {
                    quote = char
                    current.append(char)
                }
                quote == null && char == ',' -> {
                    result += current.toString().trim()
                    current.clear()
                }
                else -> current.append(char)
            }
        }
        if (quote != null) return emptyList()
        if (current.isNotBlank()) result += current.toString().trim()
        return result
    }

    private fun parseCosmeticLine(
        line: String,
        sourceId: String,
        sourceName: String,
        marker: String
    ): CosmeticFilterRule? {
        val markerIndex = line.indexOf(marker)
        if (markerIndex < 0) return null
        val domainPart = line.substring(0, markerIndex).trim()
        val selector = line.substring(markerIndex + marker.length).trim()
        if (!CssSelectorPolicy.isAllowed(selector)) return null
        val scope = parseDomainScope(domainPart) ?: return null
        return CosmeticFilterRule(
            rawText = line,
            sourceId = sourceId,
            sourceName = sourceName,
            selector = selector,
            domains = scope.included,
            excludedDomains = scope.excluded,
            isException = marker == "#@#"
        )
    }

    private fun parseDomainScope(raw: String): DomainScope? {
        if (raw.isBlank()) return DomainScope()
        if (raw.length > MAX_DOMAIN_SCOPE_LENGTH) return null
        val entries = raw.split(',')
        if (entries.size > MAX_DOMAINS_PER_RULE) return null
        val domains = linkedSetOf<String>()
        val excludedDomains = linkedSetOf<String>()
        var sawEntry = false
        entries.forEach { value ->
            val domain = value.trim()
            if (domain.isBlank()) return@forEach
            sawEntry = true
            val excluded = domain.startsWith("~")
            val clean = normalizeFilterHost(domain.removePrefix("~")) ?: return null
            if (excluded) excludedDomains += clean else domains += clean
        }
        if (!sawEntry) return null
        return DomainScope(domains, excludedDomains)
    }

    private fun parseHostsLine(
        line: String,
        sourceId: String,
        sourceName: String,
        sourceTier: FilterRuleSourceTier
    ): FilterRule? {
        val commentIndex = line.indexOf('#').takeIf { index ->
            index >= 0 && (index == 0 || line[index - 1].isWhitespace())
        } ?: -1
        val content = if (commentIndex >= 0) line.substring(0, commentIndex).trim() else line.trim()
        if (content.isBlank()) return null
        val parts = content.split(Regex("\\s+")).filter { it.isNotBlank() }
        val hostToken = when {
            parts.size == 2 && isHostsSinkAddress(parts[0]) -> parts[1]
            parts.size == 1 -> parts[0]
            else -> return null
        }
        if (hostToken.startsWith('.') || hostToken.any { it in ADBLOCK_HOST_METACHARACTERS }) return null
        val host = normalizeFilterHost(hostToken) ?: return null
        if (!host.contains('.') || isIpLiteral(host) || host == "localhost") return null
        return FilterRule(
            rawText = line,
            sourceId = sourceId,
            sourceName = sourceName,
            pattern = host,
            matchType = FilterMatchType.DOMAIN_ANCHOR,
            domainAnchored = true,
            sourceTier = sourceTier
        )
    }

    private fun parseAdblockLine(
        line: String,
        sourceId: String,
        sourceName: String,
        sourceTier: FilterRuleSourceTier
    ): FilterRule? {
        var working = line
        val isException = working.startsWith("@@")
        if (isException) working = working.removePrefix("@@")
        if (working.isBlank()) return null

        // Raw subscription regex is intentionally unsupported until a linear-time regex
        // implementation is available. Detect its closing delimiter before looking for the
        // option '$' so regex-internal '$' is never misclassified as an option separator.
        if (findRawRegexClosingDelimiter(working) >= 0) return null

        val optionIndex = findOptionSeparator(working)
        val patternPart = if (optionIndex >= 0) working.substring(0, optionIndex) else working
        val optionPart = if (optionIndex >= 0) working.substring(optionIndex + 1) else null
        val options = parseOptions(optionPart)
        if (options.unsupportedOptions.isNotEmpty()) return null

        var matchPattern = patternPart.trim()
        if (matchPattern.isBlank() || matchPattern.length > MAX_NETWORK_PATTERN_LENGTH) return null

        val domainAnchored = matchPattern.startsWith("||")
        val startAnchored = !domainAnchored && matchPattern.startsWith("|")
        if (domainAnchored) {
            matchPattern = matchPattern.removePrefix("||")
        } else if (startAnchored) {
            matchPattern = matchPattern.removePrefix("|")
        }

        val endAnchored = matchPattern.endsWith("|")
        if (endAnchored) matchPattern = matchPattern.dropLast(1)
        if (matchPattern.isBlank()) return null
        if (matchPattern.any { it.isISOControl() || it.isWhitespace() }) return null

        // Interior anchors, escape syntax, and excessive generated-pattern complexity are not
        // approximated. Failing unsupported syntax closed avoids silent over/under-blocking.
        if ('|' in matchPattern || '\\' in matchPattern) return null
        val wildcardCount = matchPattern.count { it == '*' }
        val separatorCount = matchPattern.count { it == '^' }
        if (
            wildcardCount > MAX_WILDCARDS ||
            separatorCount > MAX_SEPARATORS ||
            generatedMatcherComplexity(matchPattern, wildcardCount, separatorCount) >
            MAX_GENERATED_MATCHER_COMPLEXITY
        ) {
            return null
        }
        if (domainAnchored) {
            val rawHost = matchPattern.takeWhile {
                it != '/' && it != '^' && it != '*' && it != '?' && it != '#'
            }
            val normalizedHost = domainAnchorHost(matchPattern) ?: return null
            if (':' !in rawHost && rawHost != normalizedHost) {
                matchPattern = normalizedHost + matchPattern.substring(rawHost.length)
            }
        }

        val hasWildcard = wildcardCount > 0
        val hasSeparator = separatorCount > 0
        val matchType = when {
            domainAnchored -> FilterMatchType.DOMAIN_ANCHOR
            startAnchored -> FilterMatchType.STARTS_WITH
            endAnchored -> FilterMatchType.ENDS_WITH
            else -> FilterMatchType.SUBSTRING
        }
        if (shouldSkipWeakUnrestrictedSubstring(matchPattern, matchType, isException, options)) return null

        return FilterRule(
            rawText = line,
            sourceId = sourceId,
            sourceName = sourceName,
            pattern = matchPattern,
            matchType = matchType,
            isException = isException,
            resourceTypes = options.resourceTypes,
            excludedResourceTypes = options.excludedResourceTypes,
            thirdParty = options.thirdParty,
            domains = options.domains,
            excludedDomains = options.excludedDomains,
            important = options.important,
            badFilter = options.badFilter,
            removeParams = options.removeParams,
            unsupportedOptions = options.unsupportedOptions,
            startAnchored = startAnchored,
            endAnchored = endAnchored,
            domainAnchored = domainAnchored,
            hasWildcard = hasWildcard,
            hasSeparator = hasSeparator,
            sourceTier = sourceTier
        )
    }

    private fun findRawRegexClosingDelimiter(line: String): Int {
        if (!line.startsWith('/') || line.length < 2) return -1
        var escaped = false
        var lastCandidate = -1
        line.forEachIndexed { index, char ->
            if (index == 0) return@forEachIndexed
            when {
                escaped -> escaped = false
                char == '\\' -> escaped = true
                char == '/' && (index == line.lastIndex || line.getOrNull(index + 1) == '$') -> {
                    lastCandidate = index
                }
            }
        }
        return lastCandidate
    }

    private fun findOptionSeparator(line: String): Int {
        var escaped = false
        line.forEachIndexed { index, char ->
            when {
                escaped -> escaped = false
                char == '\\' -> escaped = true
                char == '$' -> return index
            }
        }
        return -1
    }

    private fun parseOptions(raw: String?): ParsedOptions {
        if (raw.isNullOrBlank()) return ParsedOptions()
        if (raw.length > MAX_OPTION_TEXT_LENGTH) {
            return ParsedOptions(unsupportedOptions = setOf("options-limit"))
        }
        val options = raw.split(',').map { it.trim() }.filter { it.isNotBlank() }
        if (options.size > MAX_OPTIONS_PER_RULE) {
            return ParsedOptions(unsupportedOptions = setOf("options-limit"))
        }
        val resourceTypes = linkedSetOf<FilterResourceType>()
        val excludedResourceTypes = linkedSetOf<FilterResourceType>()
        val domains = linkedSetOf<String>()
        val excludedDomains = linkedSetOf<String>()
        val unsupported = linkedSetOf<String>()
        var thirdParty: Boolean? = null
        var important = false
        var badFilter = false
        val removeParams = linkedSetOf<String>()

        options.forEach { option ->
            val normalized = option.lowercase(Locale.US)
            when {
                normalized == "third-party" -> thirdParty = true
                normalized == "~third-party" -> thirdParty = false
                normalized == "first-party" -> thirdParty = false
                normalized == "~first-party" -> thirdParty = true
                normalized == "important" -> important = true
                normalized == "badfilter" -> badFilter = true
                normalized == "popup" -> resourceTypes += FilterResourceType.POPUP
                normalized == "removeparam" -> removeParams += COMMON_TRACKING_PARAMS
                normalized.startsWith("removeparam=") -> {
                    val rawParams = normalized.removePrefix("removeparam=").split('|')
                    val safeParams = rawParams.map { it.trim() }.filter { isSafeRemoveParamToken(it) }
                    if (safeParams.isEmpty() || safeParams.size != rawParams.size) {
                        unsupported += normalized
                    } else {
                        removeParams += safeParams
                    }
                }
                normalized.startsWith("domain=") -> {
                    val rawDomains = normalized.removePrefix("domain=").split('|')
                    if (rawDomains.size > MAX_DOMAINS_PER_RULE) {
                        unsupported += "domain-limit"
                        return@forEach
                    }
                    var parsedAny = false
                    rawDomains.forEach { domain ->
                        val excluded = domain.startsWith("~")
                        val clean = normalizeFilterHost(domain.removePrefix("~"))
                        if (clean == null) {
                            unsupported += normalized
                        } else {
                            parsedAny = true
                            if (excluded) excludedDomains += clean else domains += clean
                        }
                    }
                    if (!parsedAny) unsupported += normalized
                }
                else -> {
                    val excluded = normalized.startsWith("~")
                    val resourceType = FilterResourceType.fromOption(normalized.removePrefix("~"))
                    if (resourceType != null) {
                        if (excluded) excludedResourceTypes += resourceType else resourceTypes += resourceType
                    } else {
                        unsupported += normalized
                    }
                }
            }
        }

        return ParsedOptions(
            resourceTypes = resourceTypes,
            excludedResourceTypes = excludedResourceTypes,
            thirdParty = thirdParty,
            domains = domains,
            excludedDomains = excludedDomains,
            important = important,
            badFilter = badFilter,
            removeParams = removeParams,
            unsupportedOptions = unsupported
        )
    }

    private fun isSafeRemoveParamToken(value: String): Boolean {
        if (value.isBlank() || value.length > MAX_REMOVE_PARAM_LENGTH) return false
        if (value.startsWith("~") || value.startsWith("/") || value.endsWith("/")) return false
        return value.all { it.isLetterOrDigit() || it == '_' || it == '-' || it == '*' }
    }

    private fun generatedMatcherComplexity(
        pattern: String,
        wildcardCount: Int,
        separatorCount: Int
    ): Int {
        if (wildcardCount == 0 && separatorCount == 0) return pattern.length
        return pattern.length + wildcardCount * WILDCARD_COMPLEXITY_WEIGHT +
            separatorCount * SEPARATOR_COMPLEXITY_WEIGHT
    }

    private fun shouldSkipWeakUnrestrictedSubstring(
        matchPattern: String,
        matchType: FilterMatchType,
        isException: Boolean,
        options: ParsedOptions
    ): Boolean {
        if (isException || matchType != FilterMatchType.SUBSTRING) return false
        if (options.hasConstraints()) return false
        val normalized = matchPattern.trim().lowercase(Locale.US)
        if (normalized in WEAK_UNRESTRICTED_SUBSTRING_RULES) return true
        if (normalized.length < 5) return true
        val plainWord = normalized.all { it.isLetterOrDigit() || it == '-' || it == '_' }
        return plainWord && normalized.length <= 6 && normalized !in HIGH_CONFIDENCE_SHORT_SUBSTRINGS
    }

    private data class ParsedOptions(
        val resourceTypes: Set<FilterResourceType> = emptySet(),
        val excludedResourceTypes: Set<FilterResourceType> = emptySet(),
        val thirdParty: Boolean? = null,
        val domains: Set<String> = emptySet(),
        val excludedDomains: Set<String> = emptySet(),
        val important: Boolean = false,
        val badFilter: Boolean = false,
        val removeParams: Set<String> = emptySet(),
        val unsupportedOptions: Set<String> = emptySet()
    ) {
        fun hasConstraints(): Boolean {
            return resourceTypes.isNotEmpty() ||
                excludedResourceTypes.isNotEmpty() ||
                thirdParty != null ||
                domains.isNotEmpty() ||
                excludedDomains.isNotEmpty() ||
                important ||
                badFilter ||
                removeParams.isNotEmpty()
        }
    }

    private data class DomainScope(
        val included: Set<String> = emptySet(),
        val excluded: Set<String> = emptySet()
    )

    private val COMMON_TRACKING_PARAMS = setOf(
        "utm_source",
        "utm_medium",
        "utm_campaign",
        "utm_content",
        "utm_term",
        "fbclid",
        "gclid",
        "yclid",
        "mc_cid",
        "mc_eid"
    )

    private val WEAK_UNRESTRICTED_SUBSTRING_RULES = setOf(
        "search",
        "hidden",
        "button",
        "image",
        "images",
        "img",
        "icon",
        "logo",
        "static",
        "assets",
        "common",
        "header",
        "footer",
        "menu",
        "nav",
        "share",
        "wechat",
        "weixin"
    )

    private val HIGH_CONFIDENCE_SHORT_SUBSTRINGS = setOf(
        "ad",
        "ads",
        "adjs",
        "adid",
        "adserver",
        "cnzz"
    )

    private val SUPPORTED_SCRIPTLETS = setOf(
        "no-window-open-if",
        "nowoif",
        "abort-on-property-read",
        "aopr",
        "set-constant",
        "set"
    )

    private val UNSUPPORTED_COSMETIC_MARKERS = listOf(
        "#@?#",
        "#?#",
        "#@$#",
        "#@%#",
        "#$#",
        "#%#"
    )

    private val ADBLOCK_HOST_METACHARACTERS = setOf(
        '$', '*', '^', '|', '@', '/', '?', '=', '!', '#', '[', ']', '{', '}', '\\'
    )

    private fun isSupportedScriptletName(name: String): Boolean {
        return name.removeSuffix(".js").lowercase(Locale.US) in SUPPORTED_SCRIPTLETS
    }

    private const val MAX_SCRIPTLET_ARGUMENT_LENGTH = 180
    // Bounded patterns keep a single hostile rule from consuming most of a request budget.
    private const val MAX_NETWORK_PATTERN_LENGTH = 512
    private const val MAX_OPTION_TEXT_LENGTH = 2_048
    private const val MAX_OPTIONS_PER_RULE = 32
    private const val MAX_DOMAIN_SCOPE_LENGTH = 2_048
    private const val MAX_DOMAINS_PER_RULE = 64
    private const val MAX_REMOVE_PARAM_LENGTH = 80
    private const val MAX_WILDCARDS = 16
    private const val MAX_SEPARATORS = 32
    private const val WILDCARD_COMPLEXITY_WEIGHT = 16
    private const val SEPARATOR_COMPLEXITY_WEIGHT = 2
    private const val MAX_GENERATED_MATCHER_COMPLEXITY = 768
}
