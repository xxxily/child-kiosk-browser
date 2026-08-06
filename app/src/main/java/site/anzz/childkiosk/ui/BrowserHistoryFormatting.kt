package site.anzz.childkiosk.ui

import android.net.Uri
import site.anzz.childkiosk.data.BrowserHistoryEntity
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

internal data class BrowserHistoryDaySection(
    val date: LocalDate,
    val label: String,
    val items: List<BrowserHistoryEntity>
)

internal fun buildHistoryDaySections(
    history: List<BrowserHistoryEntity>
): List<BrowserHistoryDaySection> {
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    return history
        .sortedByDescending(BrowserHistoryEntity::visitedAt)
        .groupBy { item -> Instant.ofEpochMilli(item.visitedAt).atZone(zone).toLocalDate() }
        .map { (date, items) ->
            BrowserHistoryDaySection(
                date = date,
                label = historyDayLabel(date, today),
                items = items
            )
        }
}

private fun historyDayLabel(date: LocalDate, today: LocalDate): String {
    return when (date) {
        today -> "今天"
        today.minusDays(1) -> "昨天"
        else -> {
            val pattern = if (date.year == today.year) "M月d日 EEEE" else "yyyy年M月d日 EEEE"
            date.format(DateTimeFormatter.ofPattern(pattern, Locale.getDefault()))
        }
    }
}

internal fun formatHistoryTime(timestamp: Long): String {
    return Instant.ofEpochMilli(timestamp)
        .atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault()))
}

internal fun normalizeHistoryUrl(url: String): String {
    return runCatching {
        val uri = Uri.parse(url.trim())
        val scheme = uri.scheme?.lowercase(Locale.US) ?: return@runCatching ""
        val host = uri.host?.lowercase(Locale.US) ?: return@runCatching ""
        if (scheme != "http" && scheme != "https") return@runCatching ""
        val port = if (uri.port >= 0) ":${uri.port}" else ""
        val path = uri.encodedPath?.takeIf { it.isNotBlank() } ?: "/"
        val query = uri.encodedQuery?.let { "?$it" }.orEmpty()
        "$scheme://$host$port$path$query"
    }.getOrDefault("")
}
