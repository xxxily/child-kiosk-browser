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
    val score: Int = 0,
    val referer: String? = null
)

object WebIconDiscovery {
    private const val TAG = "WebIconDiscovery"
    private const val PREFS_NAME = "web_icon_discovery_cache"
    private const val CACHE_SCHEMA_VERSION = 2
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

    fun iconRefererFor(siteUrl: String?): String? {
        return iconRefererCandidatesFor(siteUrl).firstOrNull()
    }

    fun iconRefererCandidatesFor(siteUrl: String?): List<String> {
        val text = siteUrl?.trim().orEmpty()
        if (text.isBlank()) return emptyList()
        val base = normalizedBaseUrl(text) ?: return emptyList()
        val candidates = linkedSetOf(base.toString())
        val host = base.host.lowercase(Locale.US)
        val alternateHost = if (host.startsWith("www.")) {
            host.removePrefix("www.")
        } else {
            "www.$host"
        }
        runCatching {
            URL(base.protocol, alternateHost, base.port, "/").toString()
        }.getOrNull()?.let { candidates += it }
        return candidates.toList()
    }

    private fun discoverFresh(baseUrl: URL): List<WebIconCandidate> {
        val rawCandidates = linkedSetOf<WebIconCandidate>()
        rawCandidates += declaredIconCandidates(baseUrl)
        rawCandidates += manifestIconCandidates(baseUrl)
        rawCandidates += rootFallbackCandidates(baseUrl)

        return rawCandidates
            .mapNotNull { candidate ->
                val verified = resolveFinalIcon(candidate.url, candidate.referer ?: baseUrl.toString())
                    ?: return@mapNotNull null
                candidate.copy(url = verified.url, referer = verified.referer ?: candidate.referer)
            }
            .distinctBy { normalizeCandidateUrl(it.url) }
            .sortedWith(compareByDescending<WebIconCandidate> { it.score }.thenBy { it.url.length })
            .take(12)
    }

    private fun declaredIconCandidates(baseUrl: URL): List<WebIconCandidate> {
        val page = fetchText(
            url = baseUrl,
            accept = "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            maxChars = MAX_HTML_CHARS
        )
        val html = page.text
        if (html.isBlank()) return emptyList()
        val pageUrl = page.finalUrl ?: baseUrl

        val candidates = mutableListOf<WebIconCandidate>()
        candidates += parseLinkIconCandidates(html, pageUrl)
        candidates += parseMetaImageCandidates(html, pageUrl)
        candidates += parseLogoImageCandidates(html, pageUrl)

        manifestHref(html, pageUrl)?.let { manifestUrl ->
            candidates += parseManifestIconCandidates(manifestUrl, pageUrl.toString())
        }
        return candidates
    }

    private fun manifestIconCandidates(baseUrl: URL): List<WebIconCandidate> {
        val manifestUrl = runCatching { URL(baseUrl, "/manifest.webmanifest") }.getOrNull()
            ?: return emptyList()
        return parseManifestIconCandidates(manifestUrl, baseUrl.toString())
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
                    score = iconCandidateScore(url, rel, attrs["type"].orEmpty(), sizes),
                    referer = baseUrl.toString()
                )
            }
            .toList()
    }

    private fun parseMetaImageCandidates(html: String, baseUrl: URL): List<WebIconCandidate> {
        val metaTagRegex = Regex("<meta\\b[^>]*>", RegexOption.IGNORE_CASE)
        return metaTagRegex.findAll(html)
            .mapNotNull { match ->
                val attrs = parseHtmlAttrs(match.value)
                val name = attrs["property"]?.lowercase(Locale.US)
                    ?: attrs["name"]?.lowercase(Locale.US)
                    ?: return@mapNotNull null
                if (name !in setOf("og:image", "og:image:url", "twitter:image", "twitter:image:src")) {
                    return@mapNotNull null
                }
                val content = attrs["content"].orEmpty()
                val url = runCatching { URL(baseUrl, content).toString() }.getOrNull()
                    ?: return@mapNotNull null
                if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) {
                    return@mapNotNull null
                }
                WebIconCandidate(
                    url = url,
                    label = "页面分享图",
                    source = "HTML Meta Image",
                    sizeHint = null,
                    score = iconCandidateScore(url, name, attrs["type"].orEmpty(), null) + 10,
                    referer = baseUrl.toString()
                )
            }
            .toList()
    }

    private fun parseLogoImageCandidates(html: String, baseUrl: URL): List<WebIconCandidate> {
        val imgTagRegex = Regex("<img\\b[^>]*>", RegexOption.IGNORE_CASE)
        return imgTagRegex.findAll(html)
            .mapNotNull { match ->
                val tag = match.value
                val attrs = parseHtmlAttrs(tag)
                val src = attrs["src"].orEmpty().ifBlank {
                    attrs["data-src"].orEmpty().ifBlank { attrs["data-original"].orEmpty() }
                }
                if (src.isBlank()) return@mapNotNull null
                val descriptor = listOf(
                    attrs["class"],
                    attrs["id"],
                    attrs["alt"],
                    attrs["title"],
                    src
                ).joinToString(" ").lowercase(Locale.US)
                val isLogoLike = descriptor.contains("logo") ||
                    descriptor.contains("brand") ||
                    descriptor.contains("favicon") ||
                    descriptor.contains("appicon") ||
                    descriptor.contains("app-icon") ||
                    descriptor.contains("siteicon") ||
                    descriptor.contains("site-icon")
                if (!isLogoLike) return@mapNotNull null
                val url = runCatching { URL(baseUrl, src).toString() }.getOrNull()
                    ?: return@mapNotNull null
                if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) {
                    return@mapNotNull null
                }
                WebIconCandidate(
                    url = url,
                    label = "页面 Logo",
                    source = "HTML Logo Image",
                    sizeHint = null,
                    score = iconCandidateScore(url, "logo image", attrs["type"].orEmpty(), null) + 34,
                    referer = baseUrl.toString()
                )
            }
            .take(8)
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

    private fun parseManifestIconCandidates(manifestUrl: URL, referer: String? = null): List<WebIconCandidate> {
        val json = fetchText(
            url = manifestUrl,
            accept = "application/manifest+json,application/json,text/plain,*/*;q=0.8",
            maxChars = 96 * 1024,
            referer = referer
        ).text
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
                            score = iconCandidateScore(url, "manifest icon", item.optString("type"), sizes) + 8,
                            referer = referer ?: manifestUrl.toString()
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
                score = 86,
                referer = baseUrl.toString()
            ),
            "/apple-touch-icon-precomposed.png" to WebIconCandidate(
                url = URL(baseUrl.protocol, baseUrl.host, baseUrl.port, "/apple-touch-icon-precomposed.png").toString(),
                label = "根目录 Apple Touch",
                source = "Root Fallback",
                sizeHint = null,
                score = 82,
                referer = baseUrl.toString()
            ),
            "/favicon.svg" to WebIconCandidate(
                url = URL(baseUrl.protocol, baseUrl.host, baseUrl.port, "/favicon.svg").toString(),
                label = "根目录 SVG",
                source = "Root Fallback",
                sizeHint = null,
                score = 72,
                referer = baseUrl.toString()
            ),
            "/favicon.png" to WebIconCandidate(
                url = URL(baseUrl.protocol, baseUrl.host, baseUrl.port, "/favicon.png").toString(),
                label = "根目录 PNG",
                source = "Root Fallback",
                sizeHint = null,
                score = 68,
                referer = baseUrl.toString()
            ),
            "/favicon.ico" to WebIconCandidate(
                url = URL(baseUrl.protocol, baseUrl.host, baseUrl.port, "/favicon.ico").toString(),
                label = "根目录 ICO",
                source = "Root Fallback",
                sizeHint = null,
                score = 48,
                referer = baseUrl.toString()
            )
        )
        return paths.map { it.second }
    }

    private fun resolveFinalIcon(iconUrl: String, referer: String?): VerifiedIcon? {
        if (!iconUrl.startsWith("http://", true) && !iconUrl.startsWith("https://", true)) return null
        listOf(referer, null).distinct().forEach { requestReferer ->
            resolveFinalIconWithReferer(iconUrl, requestReferer)?.let { return it }
        }
        return null
    }

    private fun resolveFinalIconWithReferer(iconUrl: String, referer: String?): VerifiedIcon? {
        val headResult = probeIconHead(iconUrl, referer)
        if (headResult?.isUsable == true) return headResult
        return probeIconBytes(iconUrl, referer)
    }

    private fun probeIconHead(iconUrl: String, referer: String?): VerifiedIcon? {
        return runCatching {
            val conn = openConnection(URL(iconUrl), "HEAD", iconAcceptHeader(), referer)
            try {
                val code = conn.responseCode
                if (code !in 200..399) return@runCatching null
                val finalUrl = conn.url.toString()
                val type = conn.contentType.orEmpty().lowercase(Locale.US)
                val length = conn.contentLengthLong
                val usable = contentTypeIsImage(type) && !contentTypeIsHtml(type) && length in 1..3_000_000L
                VerifiedIcon(finalUrl, referer, usable)
            } finally {
                conn.disconnect()
            }
        }.getOrNull()
    }

    private fun probeIconBytes(iconUrl: String, referer: String?): VerifiedIcon? {
        return runCatching {
            val conn = openConnection(URL(iconUrl), "GET", iconAcceptHeader(), referer)
            try {
                val code = conn.responseCode
                if (code !in 200..399) return@runCatching null
                val finalUrl = conn.url.toString()
                val type = conn.contentType.orEmpty().lowercase(Locale.US)
                val length = conn.contentLengthLong
                if (length > 3_000_000L) return@runCatching null
                val signature = ByteArray(32)
                val read = conn.inputStream.use { input -> input.read(signature) }
                if (read <= 0) return@runCatching null
                if (contentTypeIsHtml(type) || !looksLikeDecodableImage(finalUrl, type, signature, read)) {
                    return@runCatching null
                }
                VerifiedIcon(finalUrl, referer, true)
            } finally {
                conn.disconnect()
            }
        }.getOrNull()
    }

    private fun fetchText(
        url: URL,
        accept: String,
        maxChars: Int,
        referer: String? = null
    ): FetchTextResult {
        return runCatching {
            val conn = openConnection(url, "GET", accept, referer)
            try {
                val code = conn.responseCode
                if (code !in 200..399) return@runCatching FetchTextResult("")
                conn.inputStream.bufferedReader().use { reader ->
                    val buffer = CharArray(maxChars)
                    val read = reader.read(buffer)
                    val text = if (read <= 0) "" else String(buffer, 0, read)
                    FetchTextResult(text, conn.url)
                }
            } finally {
                conn.disconnect()
            }
        }.getOrElse { error ->
            Log.d(TAG, "Fetch failed: $url", error)
            FetchTextResult("")
        }
    }

    private fun openConnection(url: URL, method: String, accept: String, referer: String? = null): HttpURLConnection {
        return (url.openConnection() as HttpURLConnection).apply {
            requestMethod = method
            instanceFollowRedirects = true
            connectTimeout = 3500
            readTimeout = 3500
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", accept)
            setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
            if (!referer.isNullOrBlank()) {
                setRequestProperty("Referer", referer)
            }
        }
    }

    private fun iconAcceptHeader(): String {
        return "image/avif,image/webp,image/apng,image/svg+xml,image/png,image/*,*/*;q=0.8"
    }

    private fun looksLikeIcon(url: String, contentType: String): Boolean {
        val path = Uri.parse(url).path.orEmpty().lowercase(Locale.US)
        return contentTypeIsImage(contentType) ||
            contentType.contains("icon") ||
            path.endsWith(".ico") ||
            path.endsWith(".png") ||
            path.endsWith(".webp") ||
            path.endsWith(".svg") ||
            path.endsWith(".jpg") ||
            path.endsWith(".jpeg")
    }

    private fun contentTypeIsImage(contentType: String): Boolean {
        return contentType.startsWith("image/") ||
            contentType.contains("icon")
    }

    private fun contentTypeIsHtml(contentType: String): Boolean {
        return contentType.contains("text/html") ||
            contentType.contains("application/xhtml")
    }

    private fun looksLikeDecodableImage(url: String, contentType: String, bytes: ByteArray, read: Int): Boolean {
        if (read >= 8 &&
            bytes[0] == 0x89.toByte() &&
            bytes[1] == 0x50.toByte() &&
            bytes[2] == 0x4E.toByte() &&
            bytes[3] == 0x47.toByte()
        ) return true
        if (read >= 3 &&
            bytes[0] == 0xFF.toByte() &&
            bytes[1] == 0xD8.toByte() &&
            bytes[2] == 0xFF.toByte()
        ) return true
        if (read >= 12 &&
            bytes[0] == 'R'.code.toByte() &&
            bytes[1] == 'I'.code.toByte() &&
            bytes[2] == 'F'.code.toByte() &&
            bytes[3] == 'F'.code.toByte() &&
            bytes[8] == 'W'.code.toByte() &&
            bytes[9] == 'E'.code.toByte() &&
            bytes[10] == 'B'.code.toByte() &&
            bytes[11] == 'P'.code.toByte()
        ) return true
        if (read >= 4 &&
            bytes[0] == 0x00.toByte() &&
            bytes[1] == 0x00.toByte() &&
            bytes[2] == 0x01.toByte() &&
            bytes[3] == 0x00.toByte()
        ) return true
        val prefix = String(bytes, 0, read.coerceAtMost(bytes.size)).trimStart()
        if (prefix.startsWith("<svg", ignoreCase = true) || prefix.startsWith("<?xml", ignoreCase = true)) {
            return true
        }
        return looksLikeIcon(url, contentType) && !prefix.startsWith("<!doctype html", ignoreCase = true) &&
            !prefix.startsWith("<html", ignoreCase = true)
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
            if (json.optInt("version", 1) != CACHE_SCHEMA_VERSION) return@runCatching null
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
                            score = item.optInt("score"),
                            referer = item.optString("referer").takeIf { it.isNotBlank() }
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
            .put("version", CACHE_SCHEMA_VERSION)
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
                                .put("referer", candidate.referer.orEmpty())
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

    private data class FetchTextResult(
        val text: String,
        val finalUrl: URL? = null
    )

    private data class VerifiedIcon(
        val url: String,
        val referer: String?,
        val isUsable: Boolean
    )
}
