package site.anzz.childkiosk.util

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

data class WebIconCandidate(
    val url: String,
    val label: String,
    val source: String,
    val sizeHint: String? = null,
    val score: Int = 0
)

object WebIconDiscovery {
    private const val TAG = "WebIconDiscovery"
    private const val PREFS_NAME = "web_icon_discovery_cache"
    private const val CACHE_TTL_MS = 7L * 24L * 60L * 60L * 1000L
    private const val FAILED_CACHE_TTL_MS = 12L * 60L * 60L * 1000L
    private const val MAX_HTML_CHARS = 192 * 1024
    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) ChildKioskBrowser/1.0 Chrome/120 Mobile Safari/537.36"

    private val hostLocks = ConcurrentHashMap<String, Mutex>()
    private val networkSemaphore = Semaphore(permits = 3)

    suspend fun discover(
        context: Context,
        siteUrl: String,
        forceRefresh: Boolean = false
    ): List<WebIconCandidate> = withContext(Dispatchers.IO) {
        val baseUrl = normalizedBaseUrl(siteUrl) ?: return@withContext emptyList()
        val cacheKey = cacheKeyFor(baseUrl)
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (!forceRefresh) {
            readCache(prefs, cacheKey)?.takeIf { !it.isExpired() }?.let { entry ->
                return@withContext entry.candidates
            }
        }

        val mutex = hostLocks.getOrPut(cacheKey) { Mutex() }
        mutex.withLock {
            if (!forceRefresh) {
                readCache(prefs, cacheKey)?.takeIf { !it.isExpired() }?.let { entry ->
                    return@withLock entry.candidates
                }
            }

            val candidates = networkSemaphore.withPermit {
                discoverFresh(baseUrl)
            }
            writeCache(prefs, cacheKey, candidates)
            candidates
        }
    }

    fun defaultFaviconUrl(siteUrl: String): String? {
        val baseUrl = normalizedBaseUrl(siteUrl) ?: return null
        return runCatching { URL(baseUrl.protocol, baseUrl.host, baseUrl.port, "/favicon.ico").toString() }
            .getOrNull()
    }

    fun normalizeWebUrl(rawUrl: String): String {
        val trimmed = rawUrl.trim()
        if (trimmed.isBlank()) return ""
        return when {
            trimmed.startsWith("http://", ignoreCase = true) -> trimmed
            trimmed.startsWith("https://", ignoreCase = true) -> trimmed
            trimmed.contains("://") -> ""
            else -> "https://$trimmed"
        }
    }

    fun isNetworkIconUrl(value: String?): Boolean {
        val text = value?.trim().orEmpty()
        return text.startsWith("http://", ignoreCase = true) ||
            text.startsWith("https://", ignoreCase = true)
    }

    private fun discoverFresh(baseUrl: URL): List<WebIconCandidate> {
        val rawCandidates = linkedSetOf<WebIconCandidate>()
        rawCandidates += declaredIconCandidates(baseUrl)
        rawCandidates += manifestIconCandidates(baseUrl)
        rawCandidates += rootFallbackCandidates(baseUrl)

        return rawCandidates
            .mapNotNull { candidate ->
                val verifiedUrl = resolveFinalIconUrl(candidate.url) ?: return@mapNotNull null
                candidate.copy(url = verifiedUrl)
            }
            .distinctBy { normalizeCandidateUrl(it.url) }
            .sortedWith(compareByDescending<WebIconCandidate> { it.score }.thenBy { it.url.length })
            .take(12)
    }

    private fun declaredIconCandidates(baseUrl: URL): List<WebIconCandidate> {
        val html = fetchText(
            url = baseUrl,
            accept = "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            maxChars = MAX_HTML_CHARS
        )
        if (html.isBlank()) return emptyList()

        val candidates = mutableListOf<WebIconCandidate>()
        candidates += parseLinkIconCandidates(html, baseUrl)

        manifestHref(html, baseUrl)?.let { manifestUrl ->
            candidates += parseManifestIconCandidates(manifestUrl)
        }
        return candidates
    }

    private fun manifestIconCandidates(baseUrl: URL): List<WebIconCandidate> {
        val manifestUrl = runCatching { URL(baseUrl, "/manifest.webmanifest") }.getOrNull()
            ?: return emptyList()
        return parseManifestIconCandidates(manifestUrl)
    }

    private fun parseLinkIconCandidates(html: String, baseUrl: URL): List<WebIconCandidate> {
        val linkTagRegex = Regex("<link\\b[^>]*>", RegexOption.IGNORE_CASE)
        return linkTagRegex.findAll(html)
            .mapNotNull { match ->
                val attrs = parseHtmlAttrs(match.value)
                val rel = attrs["rel"]?.lowercase(Locale.US).orEmpty()
                val href = attrs["href"].orEmpty()
                if (href.isBlank() || !rel.split(Regex("\\s+")).any { it.contains("icon") }) {
                    return@mapNotNull null
                }
                val url = runCatching { URL(baseUrl, href).toString() }.getOrNull()
                    ?: return@mapNotNull null
                if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) {
                    return@mapNotNull null
                }
                val sizes = attrs["sizes"]?.takeIf { it.isNotBlank() }
                val source = when {
                    rel.contains("apple-touch-icon") -> "HTML Apple Touch"
                    rel.contains("mask-icon") -> "HTML Mask Icon"
                    rel.contains("shortcut") -> "HTML Shortcut"
                    else -> "HTML Link"
                }
                WebIconCandidate(
                    url = url,
                    label = candidateLabel(source, sizes),
                    source = source,
                    sizeHint = sizes,
                    score = iconCandidateScore(url, rel, attrs["type"].orEmpty(), sizes)
                )
            }
            .toList()
    }

    private fun manifestHref(html: String, baseUrl: URL): URL? {
        val linkTagRegex = Regex("<link\\b[^>]*>", RegexOption.IGNORE_CASE)
        return linkTagRegex.findAll(html)
            .mapNotNull { match ->
                val attrs = parseHtmlAttrs(match.value)
                val rel = attrs["rel"]?.lowercase(Locale.US).orEmpty()
                val href = attrs["href"].orEmpty()
                if (href.isBlank() || !rel.split(Regex("\\s+")).contains("manifest")) {
                    return@mapNotNull null
                }
                runCatching { URL(baseUrl, href) }.getOrNull()
            }
            .firstOrNull()
    }

    private fun parseManifestIconCandidates(manifestUrl: URL): List<WebIconCandidate> {
        val json = fetchText(
            url = manifestUrl,
            accept = "application/manifest+json,application/json,text/plain,*/*;q=0.8",
            maxChars = 96 * 1024
        )
        if (json.isBlank()) return emptyList()

        return runCatching {
            val manifest = JSONObject(json)
            val icons = manifest.optJSONArray("icons") ?: JSONArray()
            buildList {
                for (index in 0 until icons.length()) {
                    val item = icons.optJSONObject(index) ?: continue
                    val src = item.optString("src").takeIf { it.isNotBlank() } ?: continue
                    val url = runCatching { URL(manifestUrl, src).toString() }.getOrNull() ?: continue
                    if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) continue
                    val sizes = item.optString("sizes").takeIf { it.isNotBlank() }
                    add(
                        WebIconCandidate(
                            url = url,
                            label = candidateLabel("Web Manifest", sizes),
                            source = "Web Manifest",
                            sizeHint = sizes,
                            score = iconCandidateScore(url, "manifest icon", item.optString("type"), sizes) + 8
                        )
                    )
                }
            }
        }.getOrElse { error ->
            Log.d(TAG, "Manifest parse failed: $manifestUrl", error)
            emptyList()
        }
    }

    private fun rootFallbackCandidates(baseUrl: URL): List<WebIconCandidate> {
        val paths = listOf(
            "/apple-touch-icon.png" to WebIconCandidate(
                url = URL(baseUrl.protocol, baseUrl.host, baseUrl.port, "/apple-touch-icon.png").toString(),
                label = "根目录 Apple Touch",
                source = "Root Fallback",
                sizeHint = null,
                score = 86
            ),
            "/apple-touch-icon-precomposed.png" to WebIconCandidate(
                url = URL(baseUrl.protocol, baseUrl.host, baseUrl.port, "/apple-touch-icon-precomposed.png").toString(),
                label = "根目录 Apple Touch",
                source = "Root Fallback",
                sizeHint = null,
                score = 82
            ),
            "/favicon.svg" to WebIconCandidate(
                url = URL(baseUrl.protocol, baseUrl.host, baseUrl.port, "/favicon.svg").toString(),
                label = "根目录 SVG",
                source = "Root Fallback",
                sizeHint = null,
                score = 72
            ),
            "/favicon.png" to WebIconCandidate(
                url = URL(baseUrl.protocol, baseUrl.host, baseUrl.port, "/favicon.png").toString(),
                label = "根目录 PNG",
                source = "Root Fallback",
                sizeHint = null,
                score = 68
            ),
            "/favicon.ico" to WebIconCandidate(
                url = URL(baseUrl.protocol, baseUrl.host, baseUrl.port, "/favicon.ico").toString(),
                label = "根目录 ICO",
                source = "Root Fallback",
                sizeHint = null,
                score = 48
            )
        )
        return paths.map { it.second }
    }

    private fun resolveFinalIconUrl(iconUrl: String): String? {
        if (!iconUrl.startsWith("http://", true) && !iconUrl.startsWith("https://", true)) return null
        val methods = listOf("HEAD", "GET")
        methods.forEach { method ->
            val result = runCatching {
                val conn = openConnection(URL(iconUrl), method, iconAcceptHeader())
                try {
                    val code = conn.responseCode
                    if (code in 200..399) {
                        val type = conn.contentType.orEmpty().lowercase(Locale.US)
                        val length = conn.contentLengthLong
                        if (method == "HEAD" || looksLikeIcon(iconUrl, type) || length in 1..3_000_000L) {
                            conn.url.toString()
                        } else {
                            null
                        }
                    } else {
                        null
                    }
                } finally {
                    conn.disconnect()
                }
            }.getOrNull()
            if (!result.isNullOrBlank()) return result
        }
        return null
    }

    private fun fetchText(url: URL, accept: String, maxChars: Int): String {
        return runCatching {
            val conn = openConnection(url, "GET", accept)
            try {
                val code = conn.responseCode
                if (code !in 200..399) return@runCatching ""
                conn.inputStream.bufferedReader().use { reader ->
                    val buffer = CharArray(maxChars)
                    val read = reader.read(buffer)
                    if (read <= 0) "" else String(buffer, 0, read)
                }
            } finally {
                conn.disconnect()
            }
        }.getOrElse { error ->
            Log.d(TAG, "Fetch failed: $url", error)
            ""
        }
    }

    private fun openConnection(url: URL, method: String, accept: String): HttpURLConnection {
        return (url.openConnection() as HttpURLConnection).apply {
            requestMethod = method
            instanceFollowRedirects = true
            connectTimeout = 3500
            readTimeout = 3500
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", accept)
            setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
        }
    }

    private fun iconAcceptHeader(): String {
        return "image/avif,image/webp,image/apng,image/svg+xml,image/png,image/*,*/*;q=0.8"
    }

    private fun looksLikeIcon(url: String, contentType: String): Boolean {
        val path = Uri.parse(url).path.orEmpty().lowercase(Locale.US)
        return contentType.startsWith("image/") ||
            contentType.contains("icon") ||
            path.endsWith(".ico") ||
            path.endsWith(".png") ||
            path.endsWith(".webp") ||
            path.endsWith(".svg") ||
            path.endsWith(".jpg") ||
            path.endsWith(".jpeg")
    }

    private fun parseHtmlAttrs(tag: String): Map<String, String> {
        val attrRegex = Regex("""([a-zA-Z_:][-a-zA-Z0-9_:.]*)\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s"'>`]+))""")
        return attrRegex.findAll(tag).associate { attr ->
            val key = attr.groupValues[1].lowercase(Locale.US)
            val value = attr.groupValues.drop(2).firstOrNull { it.isNotEmpty() }.orEmpty()
            key to value
        }
    }

    private fun iconCandidateScore(url: String, rel: String, type: String, sizes: String?): Int {
        val relLower = rel.lowercase(Locale.US)
        val typeLower = type.lowercase(Locale.US)
        var score = 0
        if (relLower.contains("apple-touch-icon")) score += 70
        if (relLower.contains("manifest")) score += 56
        if (relLower.contains("icon")) score += 44
        if (relLower.contains("shortcut")) score += 8
        if (relLower.contains("mask-icon")) score -= 18
        when {
            url.endsWith(".png", ignoreCase = true) || typeLower.contains("png") -> score += 35
            url.endsWith(".webp", ignoreCase = true) || typeLower.contains("webp") -> score += 32
            url.endsWith(".svg", ignoreCase = true) || typeLower.contains("svg") -> score += 24
            url.endsWith(".ico", ignoreCase = true) || typeLower.contains("icon") -> score += 12
        }
        if (sizes.equals("any", ignoreCase = true)) score += 18
        largestSizeArea(sizes)?.let { area ->
            score += when {
                area >= 512 * 512 -> 70
                area >= 256 * 256 -> 58
                area >= 192 * 192 -> 48
                area >= 128 * 128 -> 36
                area >= 64 * 64 -> 22
                area >= 32 * 32 -> 10
                else -> 2
            }
        }
        return score
    }

    private fun largestSizeArea(sizes: String?): Int? {
        if (sizes.isNullOrBlank()) return null
        return Regex("""(\d+)x(\d+)""").findAll(sizes)
            .mapNotNull { match ->
                val width = match.groupValues[1].toIntOrNull() ?: return@mapNotNull null
                val height = match.groupValues[2].toIntOrNull() ?: return@mapNotNull null
                width * height
            }
            .maxOrNull()
    }

    private fun candidateLabel(source: String, sizeHint: String?): String {
        return if (sizeHint.isNullOrBlank()) source else "$source $sizeHint"
    }

    private fun normalizedBaseUrl(rawUrl: String): URL? {
        val normalized = normalizeWebUrl(rawUrl)
        if (normalized.isBlank()) return null
        return runCatching {
            val uri = Uri.parse(normalized)
            val scheme = uri.scheme?.lowercase(Locale.US) ?: return@runCatching null
            val host = uri.host?.lowercase(Locale.US) ?: return@runCatching null
            if (scheme != "http" && scheme != "https") return@runCatching null
            val port = if (uri.port >= 0) ":${uri.port}" else ""
            URL("$scheme://$host$port/")
        }.getOrNull()
    }

    private fun cacheKeyFor(baseUrl: URL): String {
        val port = if (baseUrl.port >= 0) ":${baseUrl.port}" else ""
        return "${baseUrl.protocol.lowercase(Locale.US)}://${baseUrl.host.lowercase(Locale.US)}$port"
    }

    private fun normalizeCandidateUrl(url: String): String {
        return runCatching {
            val parsed = Uri.parse(url)
            parsed.buildUpon().fragment(null).build().toString()
        }.getOrDefault(url).lowercase(Locale.US)
    }

    private fun readCache(prefs: SharedPreferences, key: String): CacheEntry? {
        val raw = prefs.getString(key, null) ?: return null
        return runCatching {
            val json = JSONObject(raw)
            val cachedAt = json.optLong("cachedAt")
            val expiresAt = json.optLong("expiresAt", 0L)
            val candidatesJson = json.optJSONArray("candidates") ?: JSONArray()
            val candidates = buildList {
                for (index in 0 until candidatesJson.length()) {
                    val item = candidatesJson.optJSONObject(index) ?: continue
                    val url = item.optString("url").takeIf { it.isNotBlank() } ?: continue
                    add(
                        WebIconCandidate(
                            url = url,
                            label = item.optString("label"),
                            source = item.optString("source"),
                            sizeHint = item.optString("sizeHint").takeIf { it.isNotBlank() },
                            score = item.optInt("score")
                        )
                    )
                }
            }
            CacheEntry(cachedAt = cachedAt, expiresAt = expiresAt, candidates = candidates)
        }.getOrNull()
    }

    private fun writeCache(prefs: SharedPreferences, key: String, candidates: List<WebIconCandidate>) {
        val now = System.currentTimeMillis()
        val json = JSONObject()
            .put("cachedAt", now)
            .put("expiresAt", cacheExpiresAt(now, key, candidates.isEmpty()))
            .put(
                "candidates",
                JSONArray().apply {
                    candidates.forEach { candidate ->
                        put(
                            JSONObject()
                                .put("url", candidate.url)
                                .put("label", candidate.label)
                                .put("source", candidate.source)
                                .put("sizeHint", candidate.sizeHint.orEmpty())
                                .put("score", candidate.score)
                        )
                    }
                }
            )
        prefs.edit().putString(key, json.toString()).apply()
    }

    private data class CacheEntry(
        val cachedAt: Long,
        val expiresAt: Long,
        val candidates: List<WebIconCandidate>
    ) {
        fun isExpired(): Boolean {
            val now = System.currentTimeMillis()
            if (expiresAt > 0L) return now > expiresAt
            val age = now - cachedAt
            val ttl = if (candidates.isEmpty()) FAILED_CACHE_TTL_MS else CACHE_TTL_MS
            return age < 0 || age > ttl
        }
    }

    private fun cacheExpiresAt(now: Long, key: String, isFailure: Boolean): Long {
        val ttl = if (isFailure) FAILED_CACHE_TTL_MS else CACHE_TTL_MS
        val jitterWindow = if (isFailure) 2L * 60L * 60L * 1000L else 36L * 60L * 60L * 1000L
        val jitter = (key.hashCode().toLong() and Long.MAX_VALUE) % jitterWindow
        return now + ttl + jitter
    }
}
