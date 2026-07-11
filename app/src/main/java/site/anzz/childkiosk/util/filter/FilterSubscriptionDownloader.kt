package site.anzz.childkiosk.util.filter

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Locale

internal data class DownloadedFilterSubscription(
    val sourceUrl: String,
    val finalUrl: String,
    val byteCount: Long,
    val text: String
)

internal object FilterSubscriptionDownloader {
    const val MAX_SOURCE_BYTES: Long = 15L * 1024L * 1024L
    private const val MAX_REDIRECTS = 5
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 30_000

    fun download(sourceUrl: String, stagingFile: File): DownloadedFilterSubscription {
        val source = requireHttpsUrl(sourceUrl)
        var current = source
        try {
            repeat(MAX_REDIRECTS + 1) { redirectCount ->
                val connection = (current.toURL().openConnection() as HttpURLConnection).apply {
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = READ_TIMEOUT_MS
                    requestMethod = "GET"
                    instanceFollowRedirects = false
                    useCaches = false
                    setRequestProperty("Accept", "text/plain, application/octet-stream;q=0.9, */*;q=0.1")
                    setRequestProperty("User-Agent", "ChildKioskBrowser/AdblockSubscription")
                }
                try {
                    val code = connection.responseCode
                    if (code in REDIRECT_CODES) {
                        if (redirectCount >= MAX_REDIRECTS) error("订阅重定向次数过多")
                        val location = connection.getHeaderField("Location")
                            ?.trim()
                            ?.takeIf { it.isNotEmpty() }
                            ?: error("订阅重定向缺少 Location")
                        current = requireHttpsUrl(current.resolve(location).toString())
                        return@repeat
                    }
                    if (code !in 200..299) error("订阅下载失败: HTTP $code")

                    val finalUri = requireHttpsUrl(connection.url.toURI().toString())
                    val contentType = connection.contentType.orEmpty()
                    stagingFile.parentFile?.mkdirs()
                    val byteCount = FileOutputStream(stagingFile).use { output ->
                        val count = copyLimited(
                            input = connection.inputStream,
                            output = output,
                            declaredLength = connection.contentLengthLong
                        )
                        output.fd.sync()
                        count
                    }
                    val text = decodeAndValidate(stagingFile.readBytes(), contentType)
                    return DownloadedFilterSubscription(
                        sourceUrl = source.toString(),
                        finalUrl = finalUri.toString(),
                        byteCount = byteCount,
                        text = text
                    )
                } finally {
                    connection.disconnect()
                }
            }
            error("订阅重定向次数过多")
        } catch (error: Throwable) {
            stagingFile.delete()
            throw error
        }
    }

    internal fun readLimited(
        input: InputStream,
        declaredLength: Long = -1L,
        maxBytes: Long = MAX_SOURCE_BYTES
    ): ByteArray {
        val output = ByteArrayOutputStream()
        copyLimited(input, output, declaredLength, maxBytes)
        return output.toByteArray()
    }

    internal fun copyLimited(
        input: InputStream,
        output: OutputStream,
        declaredLength: Long = -1L,
        maxBytes: Long = MAX_SOURCE_BYTES
    ): Long {
        require(maxBytes > 0L)
        if (declaredLength > maxBytes) error("订阅超过 15MB 限制")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        input.use { stream ->
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                total += count
                if (total > maxBytes) error("订阅超过 15MB 限制")
                output.write(buffer, 0, count)
            }
        }
        return total
    }

    internal fun decodeAndValidate(bytes: ByteArray, contentType: String = ""): String {
        if (bytes.isEmpty()) error("订阅内容为空")
        val mediaType = contentType.substringBefore(';').trim().lowercase(Locale.US)
        if (mediaType == "text/html" || mediaType == "application/xhtml+xml") {
            error("订阅返回了 HTML 页面")
        }
        val decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val decoded = runCatching { decoder.decode(ByteBuffer.wrap(bytes)).toString() }
            .getOrElse { error("订阅不是有效的 UTF-8 文本") }
            .removePrefix("\uFEFF")
        if (decoded.isBlank()) error("订阅内容为空")
        if (looksLikeHtml(decoded)) error("订阅返回了 HTML 页面")
        return decoded
    }

    internal fun decodeAndValidate(text: String, contentType: String = ""): String {
        return decodeAndValidate(text.toByteArray(StandardCharsets.UTF_8), contentType)
    }

    internal fun readLimitedForTest(text: String, maxBytes: Long): String {
        val bytes = readLimited(ByteArrayInputStream(text.toByteArray(StandardCharsets.UTF_8)), maxBytes = maxBytes)
        return decodeAndValidate(bytes)
    }

    private fun looksLikeHtml(text: String): Boolean {
        val prefix = text.trimStart().take(1_024).lowercase(Locale.US)
        return prefix.startsWith("<!doctype html") ||
            prefix.startsWith("<html") ||
            prefix.startsWith("<head") ||
            prefix.startsWith("<body") ||
            (prefix.startsWith("<?xml") && "<html" in prefix)
    }

    private fun requireHttpsUrl(rawUrl: String): URI {
        val uri = runCatching { URI(rawUrl.trim()) }.getOrNull()
            ?: error("订阅 URL 无效")
        if (!uri.scheme.equals("https", ignoreCase = true) || uri.host.isNullOrBlank()) {
            error("仅支持 HTTPS 订阅")
        }
        if (uri.userInfo != null) error("订阅 URL 不允许包含用户信息")
        return uri
    }

    private val REDIRECT_CODES = setOf(
        HttpURLConnection.HTTP_MOVED_PERM,
        HttpURLConnection.HTTP_MOVED_TEMP,
        HttpURLConnection.HTTP_SEE_OTHER,
        307,
        308
    )
}
