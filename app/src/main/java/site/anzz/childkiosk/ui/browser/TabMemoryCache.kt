package site.anzz.childkiosk.ui.browser

import android.os.Bundle

data class TabCacheItem(
    val id: String,
    val url: String,
    val title: String,
    val savedState: Bundle?,
    val lastActiveTimeMs: Long
)

object TabMemoryCache {
    val tabList = mutableListOf<TabCacheItem>()
    var activeTabId: String? = null
    
    fun clear() {
        tabList.clear()
        activeTabId = null
    }
}
