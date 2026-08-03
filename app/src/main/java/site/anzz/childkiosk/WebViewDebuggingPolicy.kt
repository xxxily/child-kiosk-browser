package site.anzz.childkiosk

import android.content.Context
import android.content.Intent
import android.content.IntentFilter

internal data class WebViewDebuggingPolicy(
    val chromeInspectEnabled: Boolean
)

/** Publishes the process-wide WebView debugging preference to the live isolated WebView process. */
internal object WebViewDebuggingPolicyBridge {
    private const val ACTION_POLICY_CHANGED =
        "site.anzz.childkiosk.action.WEBVIEW_DEBUGGING_POLICY_CHANGED"
    private const val EXTRA_CHROME_INSPECT_ENABLED = "chromeInspectEnabled"

    fun publish(context: Context, policy: WebViewDebuggingPolicy) {
        context.sendBroadcast(createIntent(context, policy))
    }

    internal fun createIntent(context: Context, policy: WebViewDebuggingPolicy): Intent {
        return Intent(ACTION_POLICY_CHANGED)
            .setPackage(context.packageName)
            .putExtra(EXTRA_CHROME_INSPECT_ENABLED, policy.chromeInspectEnabled)
    }

    fun read(intent: Intent?): WebViewDebuggingPolicy? {
        if (intent?.action != ACTION_POLICY_CHANGED ||
            !intent.hasExtra(EXTRA_CHROME_INSPECT_ENABLED)
        ) {
            return null
        }
        return WebViewDebuggingPolicy(
            chromeInspectEnabled = intent.getBooleanExtra(
                EXTRA_CHROME_INSPECT_ENABLED,
                false
            )
        )
    }

    fun intentFilter() = IntentFilter(ACTION_POLICY_CHANGED)
}
