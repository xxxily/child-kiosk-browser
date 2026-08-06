package site.anzz.childkiosk.util

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import site.anzz.childkiosk.data.AppDatabase
import site.anzz.childkiosk.data.WebAppEntity
import java.util.concurrent.ConcurrentHashMap

object WebAppSiteIconUpdater {
    private const val TAG = "WebAppSiteIcon"
    private const val MAX_DOWNLOAD_CANDIDATES = 3

    private val appLocks = ConcurrentHashMap<Int, Mutex>()
    private val automaticRefreshSemaphore = Semaphore(permits = 1)

    suspend fun refreshAfterOpen(context: Context, webAppId: Int): Boolean =
        withContext(Dispatchers.IO) {
            if (webAppId <= 0) return@withContext false
            val lock = appLocks.getOrPut(webAppId) { Mutex() }
            lock.withLock {
                automaticRefreshSemaphore.withPermit {
                    refreshLocked(context.applicationContext, webAppId)
                }
            }
        }

    private suspend fun refreshLocked(context: Context, webAppId: Int): Boolean {
        val dao = AppDatabase.getInstance(context).webAppDao()
        val app = dao.getWebAppById(webAppId) ?: return false
        if (!shouldAutoDiscoverSiteIcon(app)) return false
        if (WebAppIconCache.resolveCachedFile(context, app.siteIconPath) != null) return false

        val candidates = WebIconDiscovery.discover(context, app.url)
        for (candidate in candidates.take(MAX_DOWNLOAD_CANDIDATES)) {
            val cachedPath = WebAppIconCache.freezeNetworkIcon(
                context = context,
                iconPath = candidate.url,
                referer = candidate.referer ?: app.url
            )
            if (
                WebAppIconCache.isCachedIconPath(cachedPath) &&
                WebAppIconCache.resolveCachedFile(context, cachedPath) != null
            ) {
                dao.updateSiteIconPath(app.id, cachedPath)
                Log.i(TAG, "Cached website icon after app open: appId=${app.id}")
                return true
            }
        }

        Log.d(TAG, "Website icon unavailable; keeping fallback icon: appId=${app.id}")
        return false
    }

    internal fun shouldAutoDiscoverSiteIcon(app: WebAppEntity): Boolean {
        return app.isPreset || app.sourceType == WebAppEntity.SOURCE_PRESET
    }
}
