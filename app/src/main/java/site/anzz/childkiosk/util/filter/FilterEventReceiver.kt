package site.anzz.childkiosk.util.filter

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.json.JSONObject

class FilterEventReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        val ctx = context ?: return
        val jsonStr = intent?.getStringExtra("event_json") ?: return
        parseValidatedFilterEvent(jsonStr)?.let { event ->
            FilterRepository.recordEvent(ctx, event)
        }
    }

    internal companion object {
        private const val MAX_EVENT_JSON_BYTES = 32 * 1024
        private const val MAX_URL_LENGTH = 4_096
        private const val MAX_RULE_LENGTH = 1_024
        private const val MAX_LABEL_LENGTH = 256
        private const val MAX_CLOCK_SKEW_MS = 5 * 60 * 1_000L
        private const val MAX_EVENT_AGE_MS = 24 * 60 * 60 * 1_000L
        private val ALLOWED_ACTIONS = setOf("ALLOW", "BLOCK", "EXCEPTION")

        internal fun parseValidatedFilterEvent(
            jsonText: String,
            nowMs: Long = System.currentTimeMillis()
        ): FilterEvent? {
            if (jsonText.toByteArray(Charsets.UTF_8).size > MAX_EVENT_JSON_BYTES) return null
            return runCatching { FilterEvent.fromJson(JSONObject(jsonText)) }
                .getOrNull()
                ?.takeIf { event ->
                    event.action in ALLOWED_ACTIONS &&
                        event.timestamp in (nowMs - MAX_EVENT_AGE_MS)..(nowMs + MAX_CLOCK_SKEW_MS) &&
                        event.url.length <= MAX_URL_LENGTH &&
                        event.topLevelUrl.length <= MAX_URL_LENGTH &&
                        event.ruleText.length <= MAX_RULE_LENGTH &&
                        event.sourceName.length <= MAX_LABEL_LENGTH &&
                        event.sourceId.length <= MAX_LABEL_LENGTH &&
                        event.reason.length <= MAX_LABEL_LENGTH &&
                        event.resourceType.length <= MAX_LABEL_LENGTH &&
                        event.matchType.length <= MAX_LABEL_LENGTH &&
                        event.indexKey.length <= MAX_LABEL_LENGTH &&
                        event.cacheStatus.length <= MAX_LABEL_LENGTH &&
                        event.candidateCount in 0..100_000 &&
                        isHttpUrlOrBlank(event.url) &&
                        isHttpUrlOrBlank(event.topLevelUrl)
                }
        }

        private fun isHttpUrlOrBlank(value: String): Boolean {
            return value.isBlank() ||
                value.startsWith("https://", ignoreCase = true) ||
                value.startsWith("http://", ignoreCase = true)
        }
    }
}
