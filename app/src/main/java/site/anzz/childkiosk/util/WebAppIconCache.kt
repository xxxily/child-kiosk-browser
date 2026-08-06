package site.anzz.childkiosk.util

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

object WebAppIconCache {
    private const val CACHE_PREFIX = "cached-web-icon:"
    private const val ICON_DIR = "web_app_icons"
    private const val MAX_ICON_BYTES = 3_000_000L
    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) ChildKioskBrowser/1.0 Chrome/120 Mobile Safari/537.36"
    private val downloadLocks = ConcurrentHashMap<String, Mutex>()
    private val downloadSemaphore = Semaphore(permits = 2)

    fun isCachedIconPath(iconPath: String?): Boolean {
        return iconPath?.startsWith(CACHE_PREFIX) == true
    }

    fun isNetworkIconUrl(iconPath: String?): Boolean {
        val text = iconPath?.trim().orEmpty()
        return text.startsWith("http://", ignoreCase = true) ||
            text.startsWith("https://", ignoreCase = true)
    }

    fun resolveCachedFile(context: Context, iconPath: String?): File? {
        val name = iconPath
            ?.takeIf { isCachedIconPath(it) }
            ?.removePrefix(CACHE_PREFIX)
            ?.takeIf { it.matches(Regex("[a-f0-9]{64}\\.[a-z0-9]{2,5}")) }
            ?: return null
        val file = File(iconDir(context), name)
        return file.takeIf { it.isFile && it.length() > 0L }
    }

    fun resolveImageData(context: Context, iconPath: String?): Any? {
        return resolveCachedFile(context, iconPath) ?: iconPath
    }

    fun preferredIconPath(
        context: Context,
        cachedSiteIconPath: String?,
        fallbackIconPath: String?
    ): String {
        val usableSiteIcon = cachedSiteIconPath
            ?.takeIf(::isCachedIconPath)
            ?.takeIf { resolveCachedFile(context, it) != null }
        return usableSiteIcon ?: fallbackIconPath.orEmpty()
    }

    suspend fun freezeNetworkIcon(
        context: Context,
        iconPath: String,
        referer: String? = null
    ): String {
        val trimmed = iconPath.trim()
        if (!isNetworkIconUrl(trimmed) || isCachedIconPath(trimmed)) return trimmed
        findCachedByUrl(context.applicationContext, trimmed)?.let { return cachedPathFor(it) }
        return withContext(Dispatchers.IO) {
            val appContext = context.applicationContext
            val lock = downloadLocks.getOrPut(HashUtils.sha256(trimmed)) { Mutex() }
            lock.withLock {
                findCachedByUrl(appContext, trimmed)?.let { return@withLock cachedPathFor(it) }
                downloadSemaphore.withPermit {
                    findCachedByUrl(appContext, trimmed)?.let { return@withPermit cachedPathFor(it) }
                    downloadAndStore(appContext, trimmed, referer)
                        ?.let { cachedPathFor(it) }
                        ?: trimmed
                }
            }
        }
    }

    private fun downloadAndStore(context: Context, iconUrl: String, referer: String?): File? {
        listOf(referer, null).distinct().forEach { candidateReferer ->
            runCatching {
                val conn = openConnection(URL(iconUrl), candidateReferer)
                try {
                    val code = conn.responseCode
                    if (code !in 200..399) return@runCatching null
                    val contentLength = conn.contentLengthLong
                    if (contentLength > MAX_ICON_BYTES) return@runCatching null

                    val finalUrl = conn.url.toString()
                    val type = conn.contentType.orEmpty().lowercase(Locale.US)
                    val extension = extensionFor(finalUrl, type)
                    val target = File(iconDir(context), "${HashUtils.sha256(iconUrl)}.$extension")
                    val tmp = File(
                        target.parentFile,
                        "${target.name}.${android.os.Process.myPid()}.${Thread.currentThread().id}.tmp"
                    )
                    var total = 0L

                    conn.inputStream.use { input ->
                        tmp.outputStream().use { output ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                total += read
                                if (total > MAX_ICON_BYTES) {
                                    tmp.delete()
                                    return@runCatching null
                                }
                                output.write(buffer, 0, read)
                            }
                        }
                    }
                    if (total <= 0L) {
                        tmp.delete()
                        return@runCatching null
                    }
                    if (target.exists()) target.delete()
                    if (!tmp.renameTo(target)) {
                        tmp.copyTo(target, overwrite = true)
                        tmp.delete()
                    }
                    target
                } finally {
                    conn.disconnect()
                }
            }.getOrNull()?.let { return it }
        }
        return null
    }

    private fun iconDir(context: Context): File {
        return File(context.filesDir, ICON_DIR).apply { mkdirs() }
    }

    private fun cachedPathFor(file: File): String = CACHE_PREFIX + file.name

    private fun findCachedByUrl(context: Context, iconUrl: String): File? {
        val prefix = "${HashUtils.sha256(iconUrl)}."
        return iconDir(context)
            .listFiles()
            ?.firstOrNull { it.isFile && it.name.startsWith(prefix) && it.length() > 0L }
    }

    private fun openConnection(url: URL, referer: String?): HttpURLConnection {
        return (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            instanceFollowRedirects = true
            connectTimeout = 5_000
            readTimeout = 5_000
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/png,image/*,*/*;q=0.8")
            setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
            if (!referer.isNullOrBlank()) {
                setRequestProperty("Referer", referer)
            }
        }
    }

    private fun extensionFor(url: String, contentType: String): String {
        val pathExt = Uri.parse(url).lastPathSegment
            ?.substringAfterLast('.', "")
            ?.lowercase(Locale.US)
            ?.takeIf { it in setOf("png", "jpg", "jpeg", "webp", "svg", "ico", "gif") }
        if (!pathExt.isNullOrBlank()) return pathExt
        return when {
            contentType.contains("png") -> "png"
            contentType.contains("jpeg") || contentType.contains("jpg") -> "jpg"
            contentType.contains("webp") -> "webp"
            contentType.contains("svg") -> "svg"
            contentType.contains("gif") -> "gif"
            contentType.contains("icon") -> "ico"
            else -> "img"
        }
    }
}
