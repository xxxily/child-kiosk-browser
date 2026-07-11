package site.anzz.childkiosk.performance

internal data class HighPerformanceTabKey(
    val ownerId: String,
    val tabId: String
)

/** Keeps explicit Stop decisions stable while a logical tab receives a replacement WebView. */
internal class HighPerformanceRestartPolicy {
    private var suppressUnapprovedTabs = false
    private val parentAuthorizationRequiredOwners = mutableSetOf<String>()
    private val explicitlySuppressedTabs = mutableSetOf<HighPerformanceTabKey>()
    private val explicitlyAuthorizedTabs = mutableSetOf<HighPerformanceTabKey>()

    fun shouldSuppress(key: HighPerformanceTabKey, allowRestart: Boolean): Boolean {
        if (allowRestart) authorize(key)
        return key.ownerId in parentAuthorizationRequiredOwners ||
            key in explicitlySuppressedTabs ||
            (suppressUnapprovedTabs && key !in explicitlyAuthorizedTabs)
    }

    /** Ordinary navigation can never bypass a health-limit parent authorization latch. */
    fun authorize(key: HighPerformanceTabKey): Boolean {
        if (key.ownerId in parentAuthorizationRequiredOwners) return false
        explicitlySuppressedTabs -= key
        explicitlyAuthorizedTabs += key
        return true
    }

    fun suppressAll(keys: Collection<HighPerformanceTabKey>) {
        suppressUnapprovedTabs = true
        explicitlyAuthorizedTabs.clear()
        explicitlySuppressedTabs.clear()
        explicitlySuppressedTabs += keys
    }

    /** Clears global/manual Stop suppression without bypassing owner health limits. */
    fun clearSuppression() {
        suppressUnapprovedTabs = false
        explicitlySuppressedTabs.clear()
        explicitlyAuthorizedTabs.clear()
    }

    fun requireParentAuthorization(ownerId: String) {
        parentAuthorizationRequiredOwners += ownerId
        explicitlyAuthorizedTabs.removeAll { it.ownerId == ownerId }
    }

    fun clearParentAuthorizationRequirement(ownerId: String) {
        parentAuthorizationRequiredOwners -= ownerId
        explicitlySuppressedTabs.removeAll { it.ownerId == ownerId }
        explicitlyAuthorizedTabs.removeAll { it.ownerId == ownerId }
    }

    fun forget(key: HighPerformanceTabKey) {
        explicitlySuppressedTabs -= key
        explicitlyAuthorizedTabs -= key
    }

    fun forgetOwner(ownerId: String) {
        parentAuthorizationRequiredOwners -= ownerId
        explicitlySuppressedTabs.removeAll { it.ownerId == ownerId }
        explicitlyAuthorizedTabs.removeAll { it.ownerId == ownerId }
    }
}
