package site.anzz.childkiosk

import android.content.ComponentCallbacks2

internal enum class WebViewPoolTrimAction {
    NONE,
    TRIM_TO_ONE,
    CLEAR
}

internal data class MemoryTrimDecision(
    val levelName: String,
    val action: WebViewPoolTrimAction
)

internal fun memoryTrimDecision(level: Int): MemoryTrimDecision = when (level) {
    ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE ->
        MemoryTrimDecision("RUNNING_MODERATE", WebViewPoolTrimAction.TRIM_TO_ONE)
    ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW ->
        MemoryTrimDecision("RUNNING_LOW", WebViewPoolTrimAction.CLEAR)
    ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL ->
        MemoryTrimDecision("RUNNING_CRITICAL", WebViewPoolTrimAction.CLEAR)
    ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN ->
        MemoryTrimDecision("UI_HIDDEN", WebViewPoolTrimAction.CLEAR)
    ComponentCallbacks2.TRIM_MEMORY_BACKGROUND ->
        MemoryTrimDecision("BACKGROUND", WebViewPoolTrimAction.CLEAR)
    ComponentCallbacks2.TRIM_MEMORY_MODERATE ->
        MemoryTrimDecision("MODERATE", WebViewPoolTrimAction.CLEAR)
    ComponentCallbacks2.TRIM_MEMORY_COMPLETE ->
        MemoryTrimDecision("COMPLETE", WebViewPoolTrimAction.CLEAR)
    else -> MemoryTrimDecision("UNKNOWN_$level", WebViewPoolTrimAction.NONE)
}
