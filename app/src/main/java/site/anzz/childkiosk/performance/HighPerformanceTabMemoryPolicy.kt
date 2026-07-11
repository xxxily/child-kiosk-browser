package site.anzz.childkiosk.performance

internal data class HighPerformanceTabMemoryCandidate(
    val tabId: String,
    val lastActiveTimeMs: Long,
    val protected: Boolean
)

internal data class HighPerformanceTabMemoryDecision(
    val tabIdsToFreeze: List<String>
)

/**
 * Applies the ordinary WebView memory cap without ever selecting a protected high-performance tab.
 */
internal object HighPerformanceTabMemoryPolicy {
    fun decide(
        backgroundTabs: List<HighPerformanceTabMemoryCandidate>,
        maxBackgroundWebViews: Int
    ): HighPerformanceTabMemoryDecision {
        val safeLimit = maxBackgroundWebViews.coerceAtLeast(0)
        val excess = (backgroundTabs.size - safeLimit).coerceAtLeast(0)
        val tabIdsToFreeze = backgroundTabs
            .asSequence()
            .filterNot(HighPerformanceTabMemoryCandidate::protected)
            .sortedBy(HighPerformanceTabMemoryCandidate::lastActiveTimeMs)
            .take(excess)
            .map(HighPerformanceTabMemoryCandidate::tabId)
            .toList()
        return HighPerformanceTabMemoryDecision(
            tabIdsToFreeze = tabIdsToFreeze
        )
    }
}
