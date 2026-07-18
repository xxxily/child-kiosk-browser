package site.anzz.childkiosk

import android.content.Context
import android.content.Intent

/**
 * Publishes the system-bar mode to a live WebView host in the isolated process.
 * Web page text input deliberately follows the platform WebView behavior without policy overrides.
 */
internal data class WebViewSystemUiPolicy(
    val normalSystemBars: Boolean
)

internal object WebViewSystemUiPolicyBridge {
    private const val ACTION_POLICY_CHANGED =
        "site.anzz.childkiosk.action.WEBVIEW_SYSTEM_UI_POLICY_CHANGED"
    private const val EXTRA_NORMAL_SYSTEM_BARS = "normalSystemBars"

    fun publish(context: Context, policy: WebViewSystemUiPolicy) {
        context.sendBroadcast(createIntent(context, policy))
    }

    internal fun createIntent(context: Context, policy: WebViewSystemUiPolicy): Intent {
        return Intent(ACTION_POLICY_CHANGED)
            .setPackage(context.packageName)
            .putExtra(EXTRA_NORMAL_SYSTEM_BARS, policy.normalSystemBars)
    }

    fun read(intent: Intent?): WebViewSystemUiPolicy? {
        if (intent?.action != ACTION_POLICY_CHANGED ||
            !intent.hasExtra(EXTRA_NORMAL_SYSTEM_BARS)
        ) {
            return null
        }
        return WebViewSystemUiPolicy(
            normalSystemBars = intent.getBooleanExtra(EXTRA_NORMAL_SYSTEM_BARS, false)
        )
    }

    fun intentFilter() = android.content.IntentFilter(ACTION_POLICY_CHANGED)
}

internal fun shouldRecoverSystemUiFromInsets(
    normalSystemBars: Boolean,
    showNormalStatusBar: Boolean,
    imeVisible: Boolean,
    statusBarsVisible: Boolean,
    navigationBarsVisible: Boolean
): Boolean {
    if (imeVisible) return false
    return if (normalSystemBars) {
        !showNormalStatusBar && statusBarsVisible
    } else {
        statusBarsVisible || navigationBarsVisible
    }
}
