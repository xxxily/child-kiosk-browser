package site.anzz.childkiosk.util.filter

import android.webkit.WebView
import org.json.JSONArray

internal object WebViewFilterInjector {
    internal const val HIDDEN_CLASS = "child-kiosk-filter-hidden"
    internal const val MAX_SELECTORS_PER_PAGE = 256
    internal const val MAX_SELECTOR_COST_PER_PAGE = 1_024
    internal const val MAX_NODES_PER_SELECTOR = 200
    internal const val MAX_HIDDEN_NODES_PER_PAGE = 2_000
    internal const val MAX_INJECTION_DURATION_MS = 12

    /**
     * Selectors are serialized as data and evaluated only through querySelectorAll(). They never
     * enter stylesheet grammar; the only stylesheet content is the local constant below.
     */
    internal fun buildCosmeticInjectionScript(matches: List<CosmeticFilterMatch>): String {
        val selectors = selectMatchesWithinBudget(matches).map { it.selector }
        return buildCosmeticInjectionScriptForSelectors(selectors)
    }

    private fun buildCosmeticInjectionScriptForSelectors(selectors: List<String>): String {
        val selectorsJson = JSONArray(selectors).toString()
        return """
            (function() {
                var hiddenClass = '$HIDDEN_CLASS';
                var styleId = 'child-kiosk-cosmetic-style';
                var style = document.getElementById(styleId);
                if (!style) {
                    style = document.createElement('style');
                    style.id = styleId;
                    (document.head || document.documentElement).appendChild(style);
                }
                style.textContent = '.$HIDDEN_CLASS { display: none !important; visibility: hidden !important; }';

                var startedAt = performance.now();
                try {
                    var previouslyHidden = document.querySelectorAll('.' + hiddenClass);
                    for (var oldIndex = 0;
                         oldIndex < previouslyHidden.length && oldIndex < $MAX_HIDDEN_NODES_PER_PAGE;
                         oldIndex++) {
                        previouslyHidden[oldIndex].classList.remove(hiddenClass);
                    }
                } catch (ignored) {}

                var selectors = $selectorsJson;
                var hitIndexes = [];
                var totalHidden = 0;
                for (var selectorIndex = 0; selectorIndex < selectors.length; selectorIndex++) {
                    if (totalHidden >= $MAX_HIDDEN_NODES_PER_PAGE) break;
                    if (performance.now() - startedAt >= $MAX_INJECTION_DURATION_MS) break;
                    try {
                        var nodes = document.querySelectorAll(selectors[selectorIndex]);
                        var selectorHidden = 0;
                        for (var nodeIndex = 0; nodeIndex < nodes.length; nodeIndex++) {
                            if (selectorHidden >= $MAX_NODES_PER_SELECTOR ||
                                totalHidden >= $MAX_HIDDEN_NODES_PER_PAGE) break;
                            var node = nodes[nodeIndex];
                            if (!node.classList.contains(hiddenClass)) {
                                node.classList.add(hiddenClass);
                                selectorHidden++;
                                totalHidden++;
                            }
                        }
                        if (selectorHidden > 0) hitIndexes.push(selectorIndex);
                    } catch (ignored) {}
                }
                return JSON.stringify(hitIndexes);
            })();
        """.trimIndent()
    }

    internal fun selectMatchesWithinBudget(
        matches: List<CosmeticFilterMatch>
    ): List<CosmeticFilterMatch> {
        val selected = ArrayList<CosmeticFilterMatch>(minOf(matches.size, MAX_SELECTORS_PER_PAGE))
        val seen = HashSet<String>()
        var totalCost = 0
        for (match in matches) {
            val selector = match.selector
            if (!seen.add(selector)) continue
            val cost = CssSelectorPolicy.estimatedCost(selector) ?: break
            if (totalCost + cost > MAX_SELECTOR_COST_PER_PAGE) break
            selected += match
            totalCost += cost
            if (selected.size >= MAX_SELECTORS_PER_PAGE) break
        }
        return selected
    }

    internal fun inject(
        webView: WebView,
        matches: List<CosmeticFilterMatch>,
        onResult: (String?) -> Unit = {}
    ) {
        val selectors = matches.map { it.selector }
        webView.evaluateJavascript(buildCosmeticInjectionScriptForSelectors(selectors), onResult)
    }
}
