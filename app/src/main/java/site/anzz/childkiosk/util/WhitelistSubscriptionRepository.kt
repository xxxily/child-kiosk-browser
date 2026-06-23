package site.anzz.childkiosk.util

import android.content.Context
import androidx.room.withTransaction
import site.anzz.childkiosk.data.AppDatabase
import site.anzz.childkiosk.data.WebAppEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

object WhitelistSubscriptionRepository {
    const val SOURCE_ID = "WHITELIST_SUBSCRIPTION"

    private const val MAX_SUBSCRIPTION_BYTES = 512 * 1024
    private const val MIN_REFRESH_INTERVAL_HOURS = 1
    private const val MAX_REFRESH_INTERVAL_HOURS = 168
    private val refreshInProgress = AtomicBoolean(false)

    data class RefreshResult(
        val title: String,
        val importedCount: Int,
        val skippedCount: Int
    )

    private data class ParsedSubscription(
        val title: String,
        val apps: List<ParsedWebApp>,
        val rawAppCount: Int
    )

    private data class ParsedWebApp(
        val sourceItemId: String,
        val title: String,
        val url: String,
        val normalizedUrl: String,
        val iconPath: String,
        val category: String,
        val enabled: Boolean
    )

    suspend fun refreshNow(context: Context): RefreshResult = withContext(Dispatchers.IO) {
        if (!refreshInProgress.compareAndSet(false, true)) {
            error("白名单订阅正在刷新")
        }

        val appContext = context.applicationContext
        try {
            val url = KioskPrefs.getWhitelistSubscriptionUrl(appContext).trim()
            require(url.startsWith("https://", ignoreCase = true)) { "仅支持 HTTPS 白名单订阅地址" }

            KioskPrefs.setWhitelistSubscriptionLastAttemptAt(appContext, System.currentTimeMillis())
            val parsed = parseSubscription(downloadSubscription(url))
            if (parsed.apps.isEmpty() && parsed.rawAppCount > 0) {
                error("订阅中没有可导入的网站条目")
            }

            val result = syncSubscriptionApps(appContext, parsed)
            KioskPrefs.setWhitelistSubscriptionTitle(appContext, parsed.title)
            KioskPrefs.setWhitelistSubscriptionImportedCount(appContext, result.importedCount)
            KioskPrefs.setWhitelistSubscriptionLastSuccessAt(appContext, System.currentTimeMillis())
            KioskPrefs.setWhitelistSubscriptionLastError(appContext, "")
            result
        } catch (e: Exception) {
            KioskPrefs.setWhitelistSubscriptionLastError(appContext, e.message ?: "白名单订阅刷新失败")
            throw e
        } finally {
            refreshInProgress.set(false)
        }
    }

    suspend fun refreshIfDue(context: Context): Boolean = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val url = KioskPrefs.getWhitelistSubscriptionUrl(appContext)
        if (url.isBlank() || !KioskPrefs.isWhitelistSubscriptionAutoRefreshEnabled(appContext)) {
            return@withContext false
        }
        val intervalHours = KioskPrefs.getWhitelistSubscriptionRefreshIntervalHours(appContext)
            .coerceIn(MIN_REFRESH_INTERVAL_HOURS, MAX_REFRESH_INTERVAL_HOURS)
        val lastAttemptAt = KioskPrefs.getWhitelistSubscriptionLastAttemptAt(appContext)
        val dueAt = lastAttemptAt + intervalHours * 60L * 60L * 1000L
        if (System.currentTimeMillis() < dueAt) return@withContext false

        runCatching { refreshNow(appContext) }.isSuccess
    }

    private suspend fun syncSubscriptionApps(
        context: Context,
        parsed: ParsedSubscription
    ): RefreshResult {
        val db = AppDatabase.getInstance(context)
        return db.withTransaction {
            val dao = db.webAppDao()
            val existingApps = dao.getAllWebApps()
            val existingSubscribed = existingApps.filter { it.sourceType == WebAppEntity.SOURCE_SUBSCRIPTION }
            val existingByItemId = existingSubscribed
                .mapNotNull { app -> app.sourceItemId?.let { it to app } }
                .toMap()
            val existingByUrl = existingSubscribed.associateBy { normalizeWebsiteUrl(it.url) }
            val localUrls = existingApps
                .filterNot { it.sourceType == WebAppEntity.SOURCE_SUBSCRIPTION }
                .map { normalizeWebsiteUrl(it.url) }
                .filter { it.isNotBlank() }
                .toSet()

            val now = System.currentTimeMillis()
            var skippedCount = 0
            val rows = parsed.apps.mapIndexedNotNull { index, app ->
                if (localUrls.contains(app.normalizedUrl)) {
                    skippedCount++
                    return@mapIndexedNotNull null
                }
                val previous = existingByItemId[app.sourceItemId] ?: existingByUrl[app.normalizedUrl]
                WebAppEntity(
                    title = app.title,
                    url = app.url,
                    iconPath = app.iconPath,
                    isPreset = false,
                    isEnabled = previous?.isEnabled ?: app.enabled,
                    category = app.category,
                    createdAt = previous?.createdAt ?: (now - index),
                    sourceType = WebAppEntity.SOURCE_SUBSCRIPTION,
                    sourceId = SOURCE_ID,
                    sourceItemId = app.sourceItemId
                )
            }

            dao.deleteWebAppsBySourceType(WebAppEntity.SOURCE_SUBSCRIPTION)
            if (rows.isNotEmpty()) {
                dao.insertAll(rows)
            }
            RefreshResult(
                title = parsed.title,
                importedCount = rows.size,
                skippedCount = skippedCount
            )
        }
    }

    private fun parseSubscription(text: String): ParsedSubscription {
        val json = JSONObject(text)
        val version = json.optInt("version", -1)
        require(version == 1) { "不支持的白名单订阅版本: $version" }

        val apps = json.optJSONArray("apps") ?: error("订阅缺少 apps 数组")
        val title = json.optString("title", "白名单订阅").ifBlank { "白名单订阅" }
        val parsedApps = mutableListOf<ParsedWebApp>()
        val seenIds = mutableSetOf<String>()
        val seenUrls = mutableSetOf<String>()

        for (index in 0 until apps.length()) {
            val item = apps.optJSONObject(index) ?: continue
            val parsed = parseApp(item) ?: continue
            if (!seenIds.add(parsed.sourceItemId) || !seenUrls.add(parsed.normalizedUrl)) {
                continue
            }
            parsedApps.add(parsed)
        }

        return ParsedSubscription(
            title = title.take(80),
            apps = parsedApps,
            rawAppCount = apps.length()
        )
    }

    private fun parseApp(json: JSONObject): ParsedWebApp? {
        val title = json.optString("title").trim().take(80)
        val rawUrl = json.optString("url").trim()
        if (title.isBlank() || rawUrl.isBlank()) return null

        val normalizedUrl = normalizeWebsiteUrl(rawUrl)
        if (normalizedUrl.isBlank()) return null

        val category = normalizeCategory(json.optString("category"))
        val sourceItemId = json.optString("id").trim()
            .ifBlank { normalizedUrl }
            .take(120)
        return ParsedWebApp(
            sourceItemId = sourceItemId,
            title = title,
            url = rawUrl,
            normalizedUrl = normalizedUrl,
            iconPath = normalizeIcon(json.optString("icon"), category),
            category = category,
            enabled = json.optBoolean("enabled", true)
        )
    }

    private fun normalizeCategory(category: String): String {
        return when (category.trim().uppercase(Locale.US)) {
            WebAppEntity.CATEGORY_GAME -> WebAppEntity.CATEGORY_GAME
            WebAppEntity.CATEGORY_VIDEO -> WebAppEntity.CATEGORY_VIDEO
            WebAppEntity.CATEGORY_BOOK -> WebAppEntity.CATEGORY_BOOK
            WebAppEntity.CATEGORY_STUDY -> WebAppEntity.CATEGORY_STUDY
            else -> WebAppEntity.CATEGORY_OTHER
        }
    }

    private fun normalizeIcon(icon: String, category: String): String {
        val value = icon.trim()
        if (value.startsWith("https://", ignoreCase = true) || value.startsWith("http://", ignoreCase = true)) {
            return value
        }
        val builtIn = setOf(
            "icon_gamepad",
            "icon_rocket",
            "icon_puzzle",
            "icon_book",
            "icon_paint",
            "icon_pet",
            "icon_music",
            "icon_school",
            "icon_lightbulb",
            "icon_toy",
            "icon_gift",
            "icon_home"
        )
        if (value in builtIn) return value
        return when (category) {
            WebAppEntity.CATEGORY_GAME -> "icon_gamepad"
            WebAppEntity.CATEGORY_VIDEO -> "icon_rocket"
            WebAppEntity.CATEGORY_BOOK -> "icon_book"
            WebAppEntity.CATEGORY_STUDY -> "icon_school"
            else -> "icon_home"
        }
    }

    private fun normalizeWebsiteUrl(url: String): String {
        return runCatching {
            val uri = URI(url.trim())
            val scheme = uri.scheme?.lowercase(Locale.US) ?: return@runCatching ""
            val host = uri.host?.lowercase(Locale.US) ?: return@runCatching ""
            if (scheme != "http" && scheme != "https") return@runCatching ""
            val port = if (uri.port >= 0) ":${uri.port}" else ""
            val path = uri.rawPath?.ifBlank { "/" } ?: "/"
            val query = uri.rawQuery?.let { "?$it" }.orEmpty()
            "$scheme://$host$port$path$query"
        }.getOrDefault("")
    }

    private fun downloadSubscription(url: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 30000
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "ChildKioskBrowser/WhitelistSubscription")
        }
        return try {
            val code = connection.responseCode
            if (code !in 200..299) error("订阅下载失败: HTTP $code")
            val contentLength = connection.contentLengthLong
            if (contentLength > MAX_SUBSCRIPTION_BYTES) error("订阅超过 512KB 限制")
            val output = ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    if (output.size() > MAX_SUBSCRIPTION_BYTES) {
                        error("订阅超过 512KB 限制")
                    }
                }
            }
            output.toString(Charsets.UTF_8.name())
        } finally {
            connection.disconnect()
        }
    }
}
