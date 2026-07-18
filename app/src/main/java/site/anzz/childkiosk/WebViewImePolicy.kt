package site.anzz.childkiosk

import android.content.Context
import android.content.Intent

internal data class WebViewImePolicy(
    val limitImeInput: Boolean,
    val normalSystemBars: Boolean
)

/**
 * Publishes the small subset of settings that must update a live WebView host immediately.
 * The explicit broadcast avoids stale SharedPreferences reads in the isolated :webview process.
 */
internal object WebViewImePolicyBridge {
    private const val ACTION_POLICY_CHANGED =
        "site.anzz.childkiosk.action.WEBVIEW_IME_POLICY_CHANGED"
    private const val EXTRA_LIMIT_IME_INPUT = "limitImeInput"
    private const val EXTRA_NORMAL_SYSTEM_BARS = "normalSystemBars"

    fun publish(context: Context, policy: WebViewImePolicy) {
        context.sendBroadcast(createIntent(context, policy))
    }

    internal fun createIntent(context: Context, policy: WebViewImePolicy): Intent {
        return Intent(ACTION_POLICY_CHANGED)
            .setPackage(context.packageName)
            .putExtra(EXTRA_LIMIT_IME_INPUT, policy.limitImeInput)
            .putExtra(EXTRA_NORMAL_SYSTEM_BARS, policy.normalSystemBars)
    }

    fun read(intent: Intent?): WebViewImePolicy? {
        if (intent?.action != ACTION_POLICY_CHANGED ||
            !intent.hasExtra(EXTRA_LIMIT_IME_INPUT) ||
            !intent.hasExtra(EXTRA_NORMAL_SYSTEM_BARS)
        ) {
            return null
        }
        return WebViewImePolicy(
            limitImeInput = intent.getBooleanExtra(EXTRA_LIMIT_IME_INPUT, false),
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
