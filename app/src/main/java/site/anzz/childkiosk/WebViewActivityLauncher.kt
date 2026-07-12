package site.anzz.childkiosk

import android.app.ActivityManager
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.os.Build
import site.anzz.childkiosk.util.KioskPrefs
import java.lang.ref.WeakReference

internal enum class WebViewHostMode {
    KIOSK_SAME_TASK,
    PERSISTENT_TASK
}

internal enum class WebViewLaunchSource {
    MAIN_TASK,
    EXTERNAL_ENTRY
}

internal enum class WebViewHomeAction {
    CLOSE_CURRENT_HOST,
    OPEN_MAIN_TASK
}

internal data class WebViewHostConditions(
    val deviceOwner: Boolean,
    val lockTaskActive: Boolean,
    val softLockConfigured: Boolean
)

internal fun resolveWebViewHostMode(conditions: WebViewHostConditions): WebViewHostMode {
    return if (
        conditions.deviceOwner ||
        conditions.lockTaskActive ||
        conditions.softLockConfigured
    ) {
        WebViewHostMode.KIOSK_SAME_TASK
    } else {
        WebViewHostMode.PERSISTENT_TASK
    }
}

internal fun resolveWebViewHomeAction(hostMode: WebViewHostMode): WebViewHomeAction {
    return when (hostMode) {
        WebViewHostMode.KIOSK_SAME_TASK -> WebViewHomeAction.CLOSE_CURRENT_HOST
        WebViewHostMode.PERSISTENT_TASK -> WebViewHomeAction.OPEN_MAIN_TASK
    }
}

internal fun webViewHostMode(activity: WebViewActivity): WebViewHostMode {
    return if (activity is PersistentWebViewActivity) {
        WebViewHostMode.PERSISTENT_TASK
    } else {
        WebViewHostMode.KIOSK_SAME_TASK
    }
}

private fun webViewHostClass(hostMode: WebViewHostMode): Class<out WebViewActivity> {
    return when (hostMode) {
        WebViewHostMode.KIOSK_SAME_TASK -> WebViewActivity::class.java
        WebViewHostMode.PERSISTENT_TASK -> PersistentWebViewActivity::class.java
    }
}

/** Resolves the task topology in the main process, where kiosk preferences are current. */
internal object WebViewActivityLauncher {
    fun createIntent(
        context: Context,
        source: WebViewLaunchSource = WebViewLaunchSource.MAIN_TASK
    ): Intent {
        return createIntent(context, readHostConditions(context), source)
    }

    internal fun createIntent(
        context: Context,
        conditions: WebViewHostConditions,
        source: WebViewLaunchSource = WebViewLaunchSource.MAIN_TASK
    ): Intent {
        val hostMode = resolveWebViewHostMode(conditions)
        return Intent(context, webViewHostClass(hostMode)).apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            if (hostMode == WebViewHostMode.PERSISTENT_TASK ||
                source == WebViewLaunchSource.EXTERNAL_ENTRY
            ) {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
    }

    internal fun createResumeIntent(context: Context, hostMode: WebViewHostMode): Intent {
        return Intent(context, webViewHostClass(hostMode)).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
            putExtra(WebViewActivity.EXTRA_RESUME_EXISTING_HOST, true)
        }
    }

    private fun readHostConditions(context: Context): WebViewHostConditions {
        val deviceOwner = runCatching {
            context.getSystemService(DevicePolicyManager::class.java)
                ?.isDeviceOwnerApp(context.packageName) == true
        }.getOrDefault(false)
        val lockTaskActive = runCatching {
            val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                manager != null && manager.lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE
            } else {
                @Suppress("DEPRECATION")
                manager?.isInLockTaskMode == true
            }
        }.getOrDefault(true)
        return WebViewHostConditions(
            deviceOwner = deviceOwner,
            lockTaskActive = lockTaskActive,
            softLockConfigured = KioskPrefs.getProtectionMode(context) == KioskPrefs.MODE_SOFT_LOCK
        )
    }
}

/** Tracks the live host in the shared :webview process so notifications resume the right task. */
internal object WebViewHostRuntime {
    private var currentHost = WeakReference<WebViewActivity>(null)

    @Synchronized
    fun register(activity: WebViewActivity): WebViewActivity? {
        val previous = currentHost.get()
        currentHost = WeakReference(activity)
        return previous?.takeIf {
            it !== activity && !it.isFinishing && !it.isDestroyed
        }
    }

    @Synchronized
    fun unregister(activity: WebViewActivity) {
        if (currentHost.get() === activity) {
            currentHost.clear()
        }
    }

    @Synchronized
    fun currentHostMode(): WebViewHostMode? {
        return currentHost.get()?.let(::webViewHostMode)
    }
}

/** Normal-mode host whose task survives HOME/Launcher re-entry into MainActivity. */
class PersistentWebViewActivity : WebViewActivity()
