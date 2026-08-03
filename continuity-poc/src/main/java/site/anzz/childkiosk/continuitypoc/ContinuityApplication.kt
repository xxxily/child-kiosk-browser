package site.anzz.childkiosk.continuitypoc

import android.app.Application
import android.os.Build
import android.util.Log
import android.webkit.WebView

class ContinuityApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val processName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            getProcessName()
        } else {
            packageName
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && processName.endsWith(":webview")) {
            runCatching { WebView.setDataDirectorySuffix("continuity_poc_webview") }
                .onFailure { Log.w(ProbeLog.TAG, "Unable to set WebView data directory suffix", it) }
        }
        Log.i(ProbeLog.TAG, "application_started process=$processName")
    }
}
