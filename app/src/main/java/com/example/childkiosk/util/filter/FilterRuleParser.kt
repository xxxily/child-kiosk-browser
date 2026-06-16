package com.example.childkiosk.util.filter

import java.util.Locale
import java.util.regex.Pattern

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
    val unsupportedOptions: Set<String> = emptySet()
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
    val excludedDomains: Set<String> = emptySet()
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

    fun parse(text: String, sourceId: String, sourceName: String): FilterParseResult {
        val rules = mutableListOf<FilterRule>()
        val cosmeticRules = mutableListOf<CosmeticFilterRule>()
        val scriptletRules = mutableListOf<ScriptletFilterRule>()
        val errors = mutableListOf<String>()
        var totalLines = 0
        var unsupportedRules = 0

        text.lineSequence().forEachIndexed { index, rawLine ->
            val line = rawLine.trim()
            if (line.isBlank() || line.startsWith("!") || line.startsWith("[")) {
                return@forEachIndexed
            }
            totalLines++

            parseScriptletLine(line, sourceId, sourceName)?.let { rule ->
                scriptletRules += rule
                return@forEachIndexed
            }

            parseCosmeticLine(line, sourceId, sourceName)?.let { rule ->
                cosmeticRules += rule
                return@forEachIndexed
            }

            if (line.startsWith("#")) {
                unsupportedRules++
                return@forEachIndexed
            }

            parseHostsLine(line, sourceId, sourceName)?.let { rule ->
                rules += rule
                return@forEachIndexed
            }

            val parsed = runCatching { parseAdblockLine(line, sourceId, sourceName) }
                .onFailure { errors += "第 ${index + 1} 行解析失败: ${it.message ?: line}" }
                .getOrNull()
            if (parsed == null) {
                unsupportedRules++
            } else {
                if (parsed.unsupportedOptions.isNotEmpty()) unsupportedRules++
                rules += parsed
            }
        }

        val enabledRules = rules.count { !it.badFilter }
        return FilterParseResult(
            rules = rules,
            cosmeticRules = cosmeticRules,
            scriptletRules = scriptletRules,
            totalLines = totalLines,
            enabledRules = enabledRules + cosmeticRules.count { !it.isException } + scriptletRules.size,
            unsupportedRules = unsupportedRules,
            errors = errors.take(20)
        )
    }

    private fun parseScriptletLine(line: String, sourceId: String, sourceName: String): ScriptletFilterRule? {
        val marker = when {
            "##+js(" in line -> "##+js("
            "#%#//scriptlet(" in line -> "#%#//scriptlet("
            else -> return null
        }
        val domainPart = line.substringBefore(marker).trim()
        val body = line.substringAfter(marker).removeSuffix(")").trim()
        if (body.isBlank()) return null
        val parts = splitScriptletArgs(body)
        val name = parts.firstOrNull()?.trim('\'', '"')?.trim().orEmpty()
        if (name.isBlank()) return null
        val domains = mutableSetOf<String>()
        val excludedDomains = mutableSetOf<String>()
        if (domainPart.isNotBlank()) {
            domainPart.split(",").map { it.trim() }.filter { it.isNotBlank() }.forEach { domain ->
                val excluded = domain.startsWith("~")
                val clean = domain.removePrefix("~").normalizeHost()
                if (clean.isNotBlank()) {
                    if (excluded) excludedDomains += clean else domains += clean
                }
            }
        }
        return ScriptletFilterRule(
            rawText = line,
            sourceId = sourceId,
            sourceName = sourceName,
            name = name,
            args = parts.drop(1).map { it.trim('\'', '"').trim() }.filter { it.length <= 180 },
            domains = domains,
            excludedDomains = excludedDomains
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
        if (current.isNotBlank()) result += current.toString().trim()
        return result
    }

    private fun parseCosmeticLine(line: String, sourceId: String, sourceName: String): CosmeticFilterRule? {
        val marker = when {
            "#@#" in line -> "#@#"
            "##" in line -> "##"
            else -> return null
        }
        val parts = line.split(marker, limit = 2)
        if (parts.size != 2) return null
        val domainPart = parts[0].trim()
        val selector = parts[1].trim()
        if (selector.isBlank()) return null
        val domains = mutableSetOf<String>()
        val excludedDomains = mutableSetOf<String>()
        if (domainPart.isNotBlank()) {
            domainPart.split(",").map { it.trim() }.filter { it.isNotBlank() }.forEach { domain ->
                val excluded = domain.startsWith("~")
                val clean = domain.removePrefix("~").normalizeHost()
                if (clean.isNotBlank()) {
                    if (excluded) excludedDomains += clean else domains += clean
                }
            }
        }
        return CosmeticFilterRule(
            rawText = line,
            sourceId = sourceId,
            sourceName = sourceName,
            selector = selector,
            domains = domains,
            excludedDomains = excludedDomains,
            isException = marker == "#@#"
        )
    }

    private fun parseHostsLine(line: String, sourceId: String, sourceName: String): FilterRule? {
        val parts = line.split(Regex("\\s+")).filter { it.isNotBlank() }
        val host = when {
            parts.size >= 2 && (parts[0] == "0.0.0.0" || parts[0] == "127.0.0.1" || parts[0] == "::1") -> parts[1]
            parts.size == 1 && !parts[0].contains("/") && parts[0].contains(".") &&
                !parts[0].startsWith("@@") && !parts[0].startsWith("||") -> parts[0]
            else -> return null
        }.normalizeHost()
        if (host.isBlank()) return null
        return FilterRule(
            rawText = line,
            sourceId = sourceId,
            sourceName = sourceName,
            pattern = host,
            matchType = FilterMatchType.DOMAIN_ANCHOR
        )
    }

    private fun parseAdblockLine(line: String, sourceId: String, sourceName: String): FilterRule {
        var working = line
        val isException = working.startsWith("@@")
        if (isException) working = working.removePrefix("@@")

        val patternPart: String
        val optionPart: String?
        val optionIndex = findOptionSeparator(working)
        if (optionIndex >= 0) {
            patternPart = working.substring(0, optionIndex)
            optionPart = working.substring(optionIndex + 1)
        } else {
            patternPart = working
            optionPart = null
        }

        val options = parseOptions(optionPart)
        val matchPattern = patternPart.trim()
        val matchType = when {
            matchPattern.startsWith("||") -> FilterMatchType.DOMAIN_ANCHOR
            matchPattern.startsWith("|") -> FilterMatchType.STARTS_WITH
            matchPattern.endsWith("|") -> FilterMatchType.ENDS_WITH
            matchPattern.length > 2 && matchPattern.startsWith("/") && matchPattern.endsWith("/") -> FilterMatchType.REGEX
            else -> FilterMatchType.SUBSTRING
        }
        val normalizedPattern = when (matchType) {
            FilterMatchType.DOMAIN_ANCHOR -> matchPattern.removePrefix("||").trimStart('|').substringBefore("^")
            FilterMatchType.STARTS_WITH -> matchPattern.removePrefix("|")
            FilterMatchType.ENDS_WITH -> matchPattern.removeSuffix("|")
            FilterMatchType.REGEX -> matchPattern.removePrefix("/").removeSuffix("/")
            FilterMatchType.SUBSTRING -> matchPattern
        }

        return FilterRule(
            rawText = line,
            sourceId = sourceId,
            sourceName = sourceName,
            pattern = normalizedPattern,
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
            unsupportedOptions = options.unsupportedOptions
        )
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
        val resourceTypes = mutableSetOf<FilterResourceType>()
        val excludedResourceTypes = mutableSetOf<FilterResourceType>()
        val domains = mutableSetOf<String>()
        val excludedDomains = mutableSetOf<String>()
        val unsupported = mutableSetOf<String>()
        var thirdParty: Boolean? = null
        var important = false
        var badFilter = false
        val removeParams = mutableSetOf<String>()

        raw.split(",").map { it.trim() }.filter { it.isNotBlank() }.forEach { option ->
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
                    normalized.removePrefix("removeparam=")
                        .split("|")
                        .map { it.trim() }
                        .filter { isSafeRemoveParamToken(it) }
                        .forEach { removeParams += it }
                }
                normalized.startsWith("domain=") -> {
                    normalized.removePrefix("domain=").split("|").forEach { domain ->
                        val excluded = domain.startsWith("~")
                        val clean = domain.removePrefix("~").normalizeHost()
                        if (clean.isNotBlank()) {
                            if (excluded) excludedDomains += clean else domains += clean
                        }
                    }
                }
                else -> {
                    val excluded = normalized.startsWith("~")
                    val resourceType = FilterResourceType.fromOption(normalized.removePrefix("~"))
                    if (resourceType != null) {
                        if (excluded) excludedResourceTypes += resourceType else resourceTypes += resourceType
                    } else if (normalized in SUPPORTED_BUT_PHASE_LATER_OPTIONS) {
                        unsupported += normalized
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
        if (value.isBlank() || value.length > 80) return false
        if (value.startsWith("/") && value.endsWith("/")) return false
        return value.all { it.isLetterOrDigit() || it == '_' || it == '-' || it == '*' }
    }

    fun isRegexSafe(pattern: String): Boolean {
        if (pattern.length > 300) return false
        val riskyFragments = listOf(".*.*", "(.+)+", "(.*)+", "(.+)*", "(.*)*")
        if (riskyFragments.any { pattern.contains(it) }) return false
        return runCatching { Pattern.compile(pattern); true }.getOrDefault(false)
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

    private val SUPPORTED_BUT_PHASE_LATER_OPTIONS = setOf(
        "redirect",
        "redirect-rule",
        "csp"
    )
}
