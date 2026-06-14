package com.example.childkiosk.util

import android.content.Context
import java.io.File
import java.util.Locale

data class WebDataStats(
    val totalBytes: Long,
    val webViewDataBytes: Long,
    val httpCacheBytes: Long,
    val codeCacheBytes: Long
)

object WebDataManager {

    fun collectStats(context: Context): WebDataStats {
        val appContext = context.applicationContext
        val dataDir = File(appContext.applicationInfo.dataDir)
        val webViewDir = File(dataDir, "app_webview")
        val httpCacheDir = appContext.cacheDir
        val codeCacheDir = appContext.codeCacheDir

        val webViewDataBytes = sizeOf(webViewDir)
        val httpCacheBytes = sizeOf(httpCacheDir)
        val codeCacheBytes = sizeOf(codeCacheDir)

        return WebDataStats(
            totalBytes = webViewDataBytes + httpCacheBytes + codeCacheBytes,
            webViewDataBytes = webViewDataBytes,
            httpCacheBytes = httpCacheBytes,
            codeCacheBytes = codeCacheBytes
        )
    }

    fun clearKnownWebCacheFiles(context: Context) {
        val appContext = context.applicationContext
        val dataDir = File(appContext.applicationInfo.dataDir)
        val webViewDefaultDir = File(dataDir, "app_webview/Default")

        listOf(
            appContext.cacheDir,
            appContext.codeCacheDir,
            File(webViewDefaultDir, "Cache"),
            File(webViewDefaultDir, "Code Cache"),
            File(webViewDefaultDir, "GPUCache"),
            File(webViewDefaultDir, "Service Worker/CacheStorage"),
            File(webViewDefaultDir, "blob_storage")
        ).forEach { dir ->
            deleteChildren(dir)
        }
    }

    fun formatBytes(bytes: Long): String {
        if (bytes <= 0L) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB")
        var value = bytes.toDouble()
        var unitIndex = 0
        while (value >= 1024.0 && unitIndex < units.lastIndex) {
            value /= 1024.0
            unitIndex++
        }
        return if (unitIndex == 0) {
            "${bytes} ${units[unitIndex]}"
        } else {
            String.format(Locale.US, "%.1f %s", value, units[unitIndex])
        }
    }

    private fun sizeOf(file: File?): Long {
        if (file == null || !file.exists()) return 0L
        if (file.isFile) return file.length()
        return file.listFiles()?.sumOf { sizeOf(it) } ?: 0L
    }

    private fun deleteChildren(dir: File?) {
        if (dir == null || !dir.exists() || !dir.isDirectory) return
        dir.listFiles()?.forEach { child ->
            runCatching {
                child.deleteRecursively()
            }
        }
    }
}
