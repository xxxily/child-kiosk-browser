package site.anzz.childkiosk.performance

import android.net.Uri
import org.json.JSONObject
import java.net.IDN
import java.util.ArrayDeque
import java.util.Locale

/**
 * A privacy-safe, process-local audit record for the high-performance runtime.
 *
 * The audit deliberately stores an Origin instead of a URL. It never accepts page content,
 * request headers, cookies, or arbitrary exception messages. Runtime status persistence takes a
 * bounded snapshot of this ring so a previous renderer/service interruption remains diagnosable
 * after the main process opens the admin UI.
 */
object HighPerformanceDiagnostics {
    private const val MAX_EVENTS = 200
    internal const val MAX_REASON_LENGTH = 160
    private const val MAX_IDENTIFIER_LENGTH = 96
    private val urlPattern = Regex("(?i)\\b(?:https?|wss?)://[^\\s\\p{Cc}]+")
    private val controlCharacters = Regex("\\p{Cc}+")
    private val identifierPattern = Regex("[^A-Za-z0-9._:-]")

    private val lock = Any()
    private val events = ArrayDeque<HighPerformanceAuditEvent>(MAX_EVENTS)

    fun record(
        type: String,
        result: String = "ok",
        originOrUrl: String? = null,
        sessionId: String? = null,
        reason: String? = null,
        timestamp: Long = System.currentTimeMillis()
    ): HighPerformanceAuditEvent {
        val event = HighPerformanceAuditEvent(
            timestamp = timestamp,
            type = safeIdentifier(type, "unknown_event"),
            result = safeIdentifier(result, "unknown"),
            origin = originOrUrl?.let(::originOnly),
            sessionId = sessionId?.let { safeIdentifier(it, "unknown") },
            reason = reason?.let(::safeReason)?.takeIf { it.isNotBlank() }
        )
        synchronized(lock) {
            while (events.size >= MAX_EVENTS) {
                events.removeFirst()
            }
            events.addLast(event)
        }
        return event
    }

    fun snapshot(limit: Int = MAX_EVENTS): List<HighPerformanceAuditEvent> {
        val safeLimit = limit.coerceIn(0, MAX_EVENTS)
        return synchronized(lock) {
            events.toList().takeLast(safeLimit)
        }
    }

    fun clear() {
        synchronized(lock) { events.clear() }
    }

    /** Returns a canonical http(s) Origin, or null when the value is not safe to retain. */
    fun originOnly(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return null
        return runCatching {
            val uri = Uri.parse(trimmed)
            val scheme = uri.scheme?.lowercase(Locale.US)
                ?.takeIf { it == "http" || it == "https" }
                ?: return@runCatching null
            val rawHost = uri.host?.trim()?.trimEnd('.')?.takeIf { it.isNotBlank() }
                ?: return@runCatching null
            val asciiHost = if (rawHost.contains(':')) {
                rawHost.lowercase(Locale.US)
            } else {
                IDN.toASCII(rawHost, IDN.USE_STD3_ASCII_RULES).lowercase(Locale.US)
            }
            val formattedHost = if (asciiHost.contains(':')) "[$asciiHost]" else asciiHost
            val port = uri.port.takeUnless {
                it < 0 || (scheme == "http" && it == 80) || (scheme == "https" && it == 443)
            }
            buildString {
                append(scheme)
                append("://")
                append(formattedHost)
                if (port != null) append(":$port")
            }
        }.getOrNull()
    }

    internal fun safeIdentifier(raw: String, fallback: String): String {
        return raw.trim()
            .replace(identifierPattern, "_")
            .take(MAX_IDENTIFIER_LENGTH)
            .ifBlank { fallback }
    }

    /** Removes URLs and control characters before diagnostics are persisted or copied. */
    fun safeReason(raw: String): String {
        return raw
            .replace(urlPattern, "[url]")
            .replace(controlCharacters, " ")
            .trim()
            .take(MAX_REASON_LENGTH)
    }

    /** Re-sanitizes persisted/untrusted events for display, export, and clipboard use. */
    fun sanitize(event: HighPerformanceAuditEvent): HighPerformanceAuditEvent {
        return HighPerformanceAuditEvent(
            timestamp = event.timestamp,
            type = safeIdentifier(event.type, "unknown_event"),
            result = safeIdentifier(event.result, "unknown"),
            origin = event.origin?.let(::originOnly),
            sessionId = event.sessionId?.let { safeIdentifier(it, "unknown") },
            reason = event.reason?.let(::safeReason)?.takeIf(String::isNotBlank)
        )
    }
}

data class HighPerformanceAuditEvent(
    val timestamp: Long,
    val type: String,
    val result: String,
    val origin: String?,
    val sessionId: String?,
    val reason: String?
) {
    internal fun toJson(): JSONObject = JSONObject()
        .put("timestamp", timestamp)
        .put("type", type)
        .put("result", result)
        .apply {
            origin?.let { put("origin", it) }
            sessionId?.let { put("sessionId", it) }
            reason?.let { put("reason", it) }
        }

    companion object {
        internal fun fromJson(json: JSONObject): HighPerformanceAuditEvent? {
            val timestamp = json.optLong("timestamp", -1L).takeIf { it > 0L } ?: return null
            val type = json.optString("type").takeIf { it.isNotBlank() } ?: return null
            return HighPerformanceDiagnostics.sanitize(
                HighPerformanceAuditEvent(
                    timestamp = timestamp,
                    type = type,
                    result = json.optString("result", "unknown"),
                    origin = json.optString("origin").takeIf { it.isNotBlank() },
                    sessionId = json.optString("sessionId").takeIf { it.isNotBlank() },
                    reason = json.optString("reason").takeIf { it.isNotBlank() }
                )
            )
        }
    }
}
