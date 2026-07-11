package site.anzz.childkiosk.util.filter

internal enum class AdblockMatchResult {
    MATCH,
    NO_MATCH,
    BUDGET_EXHAUSTED
}

/** Shared per-decision budget so many near-miss rules cannot multiply matching work. */
internal class AdblockMatchBudget(maxSteps: Int) {
    init {
        require(maxSteps >= 0) { "maxSteps must be non-negative" }
    }

    var remainingSteps: Int = maxSteps
        private set

    var consumedSteps: Int = 0
        private set

    fun tryConsume(): Boolean {
        if (remainingSteps <= 0) return false
        remainingSteps--
        consumedSteps++
        return true
    }
}

/** Deterministic matcher for the bounded Adblock wildcard/separator subset accepted by parser. */
internal object AdblockPatternMatcher {
    fun matches(
        target: String,
        pattern: String,
        startAnchored: Boolean,
        endAnchored: Boolean,
        budget: AdblockMatchBudget
    ): AdblockMatchResult {
        val effectivePattern = buildString(pattern.length + 2) {
            if (!startAnchored) append('*')
            append(pattern)
            if (!endAnchored) append('*')
        }
        var patternIndex = 0
        var targetIndex = 0
        var wildcardIndex = -1
        var wildcardTargetIndex = -1

        while (targetIndex < target.length) {
            if (!budget.tryConsume()) return AdblockMatchResult.BUDGET_EXHAUSTED
            when {
                patternIndex < effectivePattern.length && effectivePattern[patternIndex] == '*' -> {
                    while (
                        patternIndex + 1 < effectivePattern.length &&
                        effectivePattern[patternIndex + 1] == '*'
                    ) {
                        if (!budget.tryConsume()) return AdblockMatchResult.BUDGET_EXHAUSTED
                        patternIndex++
                    }
                    wildcardIndex = patternIndex
                    wildcardTargetIndex = targetIndex
                    patternIndex++
                }
                patternIndex < effectivePattern.length &&
                    tokenMatches(effectivePattern[patternIndex], target[targetIndex]) -> {
                    patternIndex++
                    targetIndex++
                }
                wildcardIndex >= 0 -> {
                    wildcardTargetIndex++
                    if (wildcardTargetIndex > target.length) return AdblockMatchResult.NO_MATCH
                    targetIndex = wildcardTargetIndex
                    patternIndex = wildcardIndex + 1
                }
                else -> return AdblockMatchResult.NO_MATCH
            }
        }

        while (patternIndex < effectivePattern.length) {
            if (!budget.tryConsume()) return AdblockMatchResult.BUDGET_EXHAUSTED
            val token = effectivePattern[patternIndex]
            if (token != '*' && token != '^') return AdblockMatchResult.NO_MATCH
            patternIndex++
        }
        return AdblockMatchResult.MATCH
    }

    fun domainTarget(urlLower: String, anchorHost: String, maxLength: Int): String? {
        val schemeSeparator = urlLower.indexOf("://")
        if (schemeSeparator < 0) return null
        val authorityStart = schemeSeparator + 3
        val authorityEnd = urlLower.indexOfAny(charArrayOf('/', '?', '#'), authorityStart)
            .let { if (it < 0) urlLower.length else it }
        val authority = urlLower.substring(authorityStart, authorityEnd).substringAfterLast('@')
        val portSuffix = if (authority.startsWith('[')) {
            val closingBracket = authority.indexOf(']')
            if (closingBracket < 0) return null
            authority.substring(closingBracket + 1).takeIf { suffix ->
                suffix.length > 1 && suffix[0] == ':' && suffix.substring(1).all(Char::isDigit)
            }.orEmpty()
        } else {
            val colon = authority.lastIndexOf(':')
            if (colon > 0 && authority.indexOf(':') == colon && authority.substring(colon + 1).all(Char::isDigit)) {
                authority.substring(colon)
            } else {
                ""
            }
        }
        val prefix = anchorHost + portSuffix
        val remainingCapacity = (maxLength - prefix.length).coerceAtLeast(0)
        val suffixEnd = if (remainingCapacity >= urlLower.length - authorityEnd) {
            urlLower.length
        } else {
            authorityEnd + remainingCapacity
        }
        return prefix + urlLower.substring(authorityEnd, suffixEnd)
    }

    private fun tokenMatches(patternToken: Char, targetToken: Char): Boolean {
        if (patternToken == '^') {
            return !(
                targetToken in 'a'..'z' ||
                    targetToken in '0'..'9' ||
                    targetToken == '_' ||
                    targetToken == '-' ||
                    targetToken == '.' ||
                    targetToken == '%'
                )
        }
        return patternToken == targetToken
    }
}
