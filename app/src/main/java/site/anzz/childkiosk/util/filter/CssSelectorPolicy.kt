package site.anzz.childkiosk.util.filter

import java.util.Locale

/**
 * Fail-closed policy for subscription-provided cosmetic selectors.
 *
 * Selectors are later passed as data to DOM selection. This policy deliberately accepts only a
 * bounded, non-procedural CSS selector subset so malformed subscription content cannot escape
 * into declaration/at-rule grammar or trigger unbounded selector work.
 */
internal object CssSelectorPolicy {
    fun isAllowed(selector: String): Boolean = estimatedCost(selector) != null

    /**
     * Returns a conservative cost unit for selectors that fit the supported subset. The value is
     * used to cap aggregate DOM-selection work before JavaScript is constructed.
     */
    internal fun estimatedCost(selector: String): Int? {
        if (selector.length !in 1..MAX_SELECTOR_LENGTH) return null
        if (selector.any { it.isISOControl() }) return null

        val lower = selector.lowercase(Locale.ROOT)
        if (FORBIDDEN_FRAGMENTS.any { it in lower }) return null
        if (selector.any { it in FORBIDDEN_CHARACTERS }) return null

        var squareDepth = 0
        var parenthesisDepth = 0
        var quote: Char? = null
        var groupCount = 1
        var branchCount = 1
        var combinatorCount = 0
        var tokenCount = 0
        var attributeCount = 0
        var pseudoCount = 0
        var functionalPseudoCount = 0
        var previousSignificant: Char? = null
        var pendingWhitespace = false
        var groupHasContent = false
        var cost = BASE_COST + (selector.length - 1) / LENGTH_COST_CHUNK

        selector.forEachIndexed { index, char ->
            if (quote != null) {
                if (char == quote) quote = null
                return@forEachIndexed
            }
            if (char.isWhitespace()) {
                if (squareDepth == 0) pendingWhitespace = true
                return@forEachIndexed
            }
            if (
                pendingWhitespace &&
                squareDepth == 0 &&
                previousSignificant != null &&
                previousSignificant !in NON_DESCENDANT_LEFT_BOUNDARIES &&
                char !in NON_DESCENDANT_RIGHT_BOUNDARIES
            ) {
                combinatorCount++
                cost += DESCENDANT_COMBINATOR_COST
            }
            pendingWhitespace = false

            when (char) {
                '\'', '"' -> quote = char
                '[' -> {
                    squareDepth++
                    if (squareDepth > MAX_NESTING_DEPTH) return null
                    attributeCount++
                    tokenCount++
                    cost += ATTRIBUTE_SELECTOR_COST
                }
                ']' -> {
                    squareDepth--
                    if (squareDepth < 0) return null
                }
                '(' -> {
                    parenthesisDepth++
                    if (parenthesisDepth > MAX_NESTING_DEPTH) return null
                    functionalPseudoCount++
                    tokenCount++
                    cost += FUNCTIONAL_PSEUDO_COST
                }
                ')' -> {
                    parenthesisDepth--
                    if (parenthesisDepth < 0) return null
                }
                ',' -> {
                    if (squareDepth != 0) return@forEachIndexed
                    if (parenthesisDepth == 0) {
                        if (!groupHasContent) return null
                        groupCount++
                        groupHasContent = false
                        cost += SELECTOR_GROUP_COST
                    } else {
                        branchCount++
                        cost += FUNCTION_BRANCH_COST
                    }
                    tokenCount++
                }
                '>', '+', '~' -> {
                    if (squareDepth == 0) {
                        combinatorCount++
                        tokenCount++
                        cost += EXPLICIT_COMBINATOR_COST
                    }
                }
                '*' -> {
                    if (squareDepth == 0) return null
                    if (selector.getOrNull(index + 1) == '=') cost += SUBSTRING_ATTRIBUTE_COST
                }
                '^', '$' -> if (squareDepth > 0 && selector.getOrNull(index + 1) == '=') {
                    cost += SUBSTRING_ATTRIBUTE_COST
                }
                else -> {
                    if (squareDepth == 0 && char == ':') {
                        pseudoCount++
                        tokenCount++
                        cost += PSEUDO_CLASS_COST
                    }
                    if (squareDepth == 0 && (char == '#' || char == '.')) tokenCount++
                }
            }
            if (squareDepth == 0 && parenthesisDepth == 0 && char != ',') groupHasContent = true
            if (squareDepth == 0) previousSignificant = char
            if (
                groupCount > MAX_SELECTOR_GROUPS ||
                branchCount > MAX_FUNCTION_BRANCHES ||
                combinatorCount > MAX_COMBINATORS ||
                tokenCount > MAX_TOKENS ||
                attributeCount > MAX_ATTRIBUTES ||
                pseudoCount > MAX_PSEUDO_CLASSES ||
                functionalPseudoCount > MAX_FUNCTIONAL_PSEUDOS ||
                cost > MAX_ESTIMATED_COST
            ) return null
        }

        if (quote != null || squareDepth != 0 || parenthesisDepth != 0 || !groupHasContent) return null
        return cost
    }

    private val FORBIDDEN_CHARACTERS = setOf('{', '}', ';', '@', '\\')

    private val FORBIDDEN_FRAGMENTS = listOf(
        "/*",
        "*/",
        "url(",
        "##",
        "#@#",
        ":-abp-",
        ":has(",
        ":has-text",
        ":contains(",
        ":matches-css",
        ":xpath(",
        ":upward(",
        ":remove(",
        ":style(",
        "::"
    )

    private val NON_DESCENDANT_LEFT_BOUNDARIES = setOf('>', '+', '~', ',', '(')
    private val NON_DESCENDANT_RIGHT_BOUNDARIES = setOf('>', '+', '~', ',', ')')

    private const val MAX_SELECTOR_LENGTH = 256
    private const val MAX_SELECTOR_GROUPS = 4
    private const val MAX_FUNCTION_BRANCHES = 4
    private const val MAX_COMBINATORS = 8
    private const val MAX_TOKENS = 32
    private const val MAX_NESTING_DEPTH = 3
    private const val MAX_ATTRIBUTES = 4
    private const val MAX_PSEUDO_CLASSES = 6
    private const val MAX_FUNCTIONAL_PSEUDOS = 3
    private const val MAX_ESTIMATED_COST = 48

    private const val BASE_COST = 1
    private const val LENGTH_COST_CHUNK = 64
    private const val SELECTOR_GROUP_COST = 3
    private const val FUNCTION_BRANCH_COST = 2
    private const val EXPLICIT_COMBINATOR_COST = 2
    private const val DESCENDANT_COMBINATOR_COST = 3
    private const val ATTRIBUTE_SELECTOR_COST = 4
    private const val SUBSTRING_ATTRIBUTE_COST = 4
    private const val PSEUDO_CLASS_COST = 2
    private const val FUNCTIONAL_PSEUDO_COST = 2
}
