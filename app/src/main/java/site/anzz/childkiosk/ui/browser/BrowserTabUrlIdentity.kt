package site.anzz.childkiosk.ui.browser

import java.net.URI
import java.util.Locale

internal fun Iterable<BrowserTab>.findBrowserTabByUrl(url: String): BrowserTab? {
    val requestedIdentity = browserTabUrlIdentity(url)
    return firstOrNull { browserTabUrlIdentity(it.url) == requestedIdentity }
}

internal fun browserTabUrlsEquivalent(first: String, second: String): Boolean =
    browserTabUrlIdentity(first) == browserTabUrlIdentity(second)

private fun browserTabUrlIdentity(rawUrl: String): String {
    val trimmed = rawUrl.trim()
    return runCatching {
        val uri = URI(trimmed)
        val scheme = uri.scheme?.lowercase(Locale.US) ?: return@runCatching trimmed
        if (scheme != "http" && scheme != "https") return@runCatching trimmed
        if (uri.rawUserInfo != null) return@runCatching trimmed
        val host = uri.host?.lowercase(Locale.US) ?: return@runCatching trimmed
        val port = when {
            uri.port < 0 -> ""
            scheme == "http" && uri.port == 80 -> ""
            scheme == "https" && uri.port == 443 -> ""
            else -> ":${uri.port}"
        }
        val renderedHost = if (':' in host) "[$host]" else host
        val path = uri.rawPath.orEmpty().ifEmpty { "/" }
        val query = uri.rawQuery?.let { "?$it" }.orEmpty()
        val fragment = uri.rawFragment?.let { "#$it" }.orEmpty()
        "$scheme://$renderedHost$port$path$query$fragment"
    }.getOrDefault(trimmed)
}
