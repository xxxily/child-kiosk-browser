package site.anzz.childkiosk.performance.cdp

import android.os.Looper
import android.webkit.WebView

internal data class WebViewDebuggingLease(
    val id: String,
    val persistentDebuggingEnabled: Boolean,
    val temporarilyEnabled: Boolean
)

internal data class WebViewDebuggingRelease(
    val released: Boolean,
    val persistentDebuggingEnabled: Boolean,
    val debuggingEnabled: Boolean
)

/** Serializes the process-wide WebView debugging flag with short-lived continuity leases. */
internal object WebViewDebuggingGate {
    private var persistentDebuggingEnabled = false
    private var activeLeaseId: String? = null
    private var appliedDebuggingEnabled = false

    fun applyPersistentPreference(enabled: Boolean): Result<Unit> {
        ensureMainThread()
        persistentDebuggingEnabled = enabled
        return applyDesiredState()
    }

    fun acquireTemporary(leaseId: String): Result<WebViewDebuggingLease> {
        ensureMainThread()
        require(leaseId.isNotBlank()) { "A WebView debugging lease id is required" }
        val existing = activeLeaseId
        require(existing == null || existing == leaseId) {
            "Another WebView debugging lease is active"
        }
        activeLeaseId = leaseId
        return applyDesiredState().map {
            WebViewDebuggingLease(
                id = leaseId,
                persistentDebuggingEnabled = persistentDebuggingEnabled,
                temporarilyEnabled = !persistentDebuggingEnabled
            )
        }.onFailure {
            if (activeLeaseId == leaseId) activeLeaseId = null
        }
    }

    fun releaseTemporary(leaseId: String): Result<WebViewDebuggingRelease> {
        ensureMainThread()
        val released = activeLeaseId == leaseId
        if (released) activeLeaseId = null
        return applyDesiredState().map {
            WebViewDebuggingRelease(
                released = released,
                persistentDebuggingEnabled = persistentDebuggingEnabled,
                debuggingEnabled = appliedDebuggingEnabled
            )
        }
    }

    fun forceRestorePersistent(): Result<Unit> {
        ensureMainThread()
        activeLeaseId = null
        return applyDesiredState()
    }

    internal fun persistentPreferenceForTests(): Boolean = persistentDebuggingEnabled

    internal fun desiredDebuggingEnabledForTests(): Boolean =
        persistentDebuggingEnabled || activeLeaseId != null

    internal fun resetForTests() {
        persistentDebuggingEnabled = false
        activeLeaseId = null
        appliedDebuggingEnabled = false
    }

    private fun applyDesiredState(): Result<Unit> {
        val desired = persistentDebuggingEnabled || activeLeaseId != null
        if (desired == appliedDebuggingEnabled) return Result.success(Unit)
        return runCatching {
            WebView.setWebContentsDebuggingEnabled(desired)
            appliedDebuggingEnabled = desired
        }
    }

    private fun ensureMainThread() {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "WebView debugging state must be changed on the main thread"
        }
    }
}
