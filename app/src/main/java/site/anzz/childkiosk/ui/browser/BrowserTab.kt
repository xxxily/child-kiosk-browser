package site.anzz.childkiosk.ui.browser

import android.os.Bundle
import android.webkit.WebView

data class BrowserTab(
    val id: String = java.util.UUID.randomUUID().toString(),
    var url: String = "about:blank",
    var title: String = "新建标签页",
    var webView: WebView? = null,
    var savedState: Bundle? = null,
    var isLoading: Boolean = false,
    var progress: Int = 0,
    var lastActiveTimeMs: Long = System.currentTimeMillis()
)
