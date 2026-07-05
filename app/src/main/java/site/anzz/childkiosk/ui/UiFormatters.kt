package site.anzz.childkiosk.ui

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal fun formatTimestamp(timestamp: Long): String {
    if (timestamp <= 0L) return "从未"
    return runCatching {
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date(timestamp))
    }.getOrDefault(timestamp.toString())
}
