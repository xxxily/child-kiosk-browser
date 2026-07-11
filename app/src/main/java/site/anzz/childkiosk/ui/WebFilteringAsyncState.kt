package site.anzz.childkiosk.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import site.anzz.childkiosk.util.filter.FilterBuildReport
import site.anzz.childkiosk.util.filter.FilterEngine
import site.anzz.childkiosk.util.filter.FilterRepository
import site.anzz.childkiosk.util.filter.FilterRuntimeSnapshot

internal const val MAX_CUSTOM_FILTER_RULE_BYTES = 128 * 1024

internal data class WebFilteringEngineUiState(
    val engine: FilterEngine = FilterEngine.EMPTY,
    val isLoading: Boolean = false,
    val errorMessage: String = ""
)

internal data class CustomRuleValidationUiState(
    val report: FilterBuildReport = FilterEngine.EMPTY.report,
    val isValidating: Boolean = false,
    val errorMessage: String = ""
)

@Composable
internal fun rememberWebFilteringEngineUiState(
    context: Context,
    snapshot: FilterRuntimeSnapshot,
    refreshKey: Int
): State<WebFilteringEngineUiState> {
    val appContext = context.applicationContext
    return produceState(
        initialValue = WebFilteringEngineUiState(isLoading = snapshot.enabled),
        snapshot,
        refreshKey
    ) {
        if (!snapshot.enabled) {
            value = WebFilteringEngineUiState(engine = FilterEngine.EMPTY)
            return@produceState
        }
        val previous = value.engine
        value = value.copy(isLoading = true, errorMessage = "")
        value = withContext(Dispatchers.IO) {
            runCatching { FilterRepository.getEngine(appContext, snapshot) }
                .fold(
                    onSuccess = { engine -> WebFilteringEngineUiState(engine = engine) },
                    onFailure = { error ->
                        WebFilteringEngineUiState(
                            engine = previous,
                            errorMessage = error.message ?: "过滤规则编译失败"
                        )
                    }
                )
        }
    }
}

@Composable
internal fun rememberCustomRuleValidationUiState(
    rules: String,
    debounceMs: Long = 400L
): State<CustomRuleValidationUiState> {
    return produceState(
        initialValue = CustomRuleValidationUiState(isValidating = rules.isNotBlank()),
        rules,
        debounceMs
    ) {
        val previousReport = value.report
        value = value.copy(isValidating = true, errorMessage = "")
        if (debounceMs > 0L) delay(debounceMs)
        value = withContext(Dispatchers.Default) {
            runCatching { FilterRepository.validateCustomRules(rules) }
                .fold(
                    onSuccess = { report -> CustomRuleValidationUiState(report = report) },
                    onFailure = { error ->
                        CustomRuleValidationUiState(
                            report = previousReport,
                            errorMessage = error.message ?: "规则校验失败"
                        )
                    }
                )
        }
    }
}
