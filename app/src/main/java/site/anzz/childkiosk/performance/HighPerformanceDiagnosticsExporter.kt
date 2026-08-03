package site.anzz.childkiosk.performance

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class HighPerformanceDiagnosticsExportResult(
    val displayPath: String,
    val bytesWritten: Int
)

object HighPerformanceDiagnosticsExporter {
    fun export(
        context: Context,
        runtimeStatus: HighPerformanceRuntimeStatusReadResult
    ): HighPerformanceDiagnosticsExportResult {
        val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val fileName = "child-kiosk-continuity-$timestamp.txt"
        val text = buildString {
            appendLine("Child Kiosk Browser - redacted background continuity diagnostics")
            appendLine("generatedAt=${SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date())}")
            appendLine("privacy=origin-only; no page body, cookies, request headers, or location coordinates")
            appendLine()
            append(HighPerformanceDiagnosticsFormatter.format(runtimeStatus))
        }
        val bytes = text.toByteArray(Charsets.UTF_8)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/ChildKiosk")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = requireNotNull(
                resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ) { "无法创建诊断文件" }
            try {
                requireNotNull(resolver.openOutputStream(uri, "w")).use { it.write(bytes) }
                resolver.update(
                    uri,
                    ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) },
                    null,
                    null
                )
            } catch (failure: Throwable) {
                resolver.delete(uri, null, null)
                throw failure
            }
            HighPerformanceDiagnosticsExportResult(
                displayPath = "Downloads/ChildKiosk/$fileName",
                bytesWritten = bytes.size
            )
        } else {
            val root = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
                ?: context.filesDir
            val directory = File(root, "ChildKiosk").apply { mkdirs() }
            val file = File(directory, fileName)
            file.writeBytes(bytes)
            HighPerformanceDiagnosticsExportResult(file.absolutePath, bytes.size)
        }
    }
}
