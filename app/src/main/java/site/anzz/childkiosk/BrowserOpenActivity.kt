package site.anzz.childkiosk

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import site.anzz.childkiosk.util.KioskPrefs

class BrowserOpenActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        openIncomingUrl(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        openIncomingUrl(intent)
    }

    private fun openIncomingUrl(intent: Intent?) {
        val targetUrl = intent?.data?.takeIf { uri ->
            uri.scheme.equals("http", ignoreCase = true) ||
                uri.scheme.equals("https", ignoreCase = true)
        }?.toString()

        if (!targetUrl.isNullOrBlank()) {
            startActivity(Intent(this, WebViewActivity::class.java).apply {
                putExtra(WebViewActivity.EXTRA_CUSTOM_URL, targetUrl)
                putExtra(WebViewActivity.EXTRA_ORIENTATION_MODE, KioskPrefs.getOrientationMode(this@BrowserOpenActivity))
                KioskPrefs.putWebViewRuntimeConfig(this, this@BrowserOpenActivity)
            })
        }
        finish()
    }
}
