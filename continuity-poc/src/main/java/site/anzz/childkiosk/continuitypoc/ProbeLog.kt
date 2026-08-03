package site.anzz.childkiosk.continuitypoc

import android.content.Context
import android.os.Process
import android.os.SystemClock
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

internal object ProbeLog {
    const val TAG = "ContinuityPoc"
    const val FILE_NAME = "continuity_probe.jsonl"

    private val lock = Any()
    @Volatile private var session: String = "uninitialized"

    fun beginSession(context: Context, requestedSession: String) {
        synchronized(lock) {
            session = sanitizeSession(requestedSession)
            logFiles(context).forEach { file ->
                file.parentFile?.mkdirs()
                file.writeText("")
            }
        }
        append(context, "session_started")
    }

    fun append(
        context: Context,
        event: String,
        details: Map<String, Any?> = emptyMap()
    ) {
        val record = JSONObject()
            .put("wallMs", System.currentTimeMillis())
            .put("elapsedMs", SystemClock.elapsedRealtime())
            .put("session", session)
            .put("pid", Process.myPid())
            .put("thread", Thread.currentThread().name)
            .put("event", event)
        details.forEach { (key, value) -> record.put(key, JSONObject.wrap(value)) }
        val line = record.toString()
        synchronized(lock) {
            logFiles(context).forEach { file ->
                runCatching {
                    FileOutputStream(file, true).bufferedWriter().use {
                        it.append(line)
                        it.newLine()
                    }
                }.onFailure { Log.e(TAG, "Unable to append probe log at ${file.path}", it) }
            }
        }
        Log.i(TAG, line)
    }

    private fun sanitizeSession(value: String): String {
        return value.trim().take(80).ifBlank { "session-${System.currentTimeMillis()}" }
    }

    private fun logFiles(context: Context): List<File> {
        return buildList {
            add(File(context.filesDir, FILE_NAME))
            context.getExternalFilesDir(null)?.let { add(File(it, FILE_NAME)) }
        }
    }
}
