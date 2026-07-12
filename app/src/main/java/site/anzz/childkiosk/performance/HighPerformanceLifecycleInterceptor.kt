package site.anzz.childkiosk.performance

import android.app.Application
import android.util.Log

/**
 * Temporarily removes all [Application.ActivityLifecycleCallbacks] from the Application
 * so that [Application.dispatchActivityStopped] during [android.app.Activity.onStop] does
 * not notify Chromium's lifecycle observer.
 *
 * Chromium registers an ActivityLifecycleCallbacks that receives onActivityStopped()
 * and internally calls Page::SetVisibilityState(kHidden), which immediately freezes JS
 * timers. This bypasses all View-level visibility overrides in PersistentWebView.
 *
 * By temporarily clearing the callback list around super.onStop(), we allow the Activity
 * lifecycle to proceed normally (ComponentActivity's LifecycleRegistry is handled
 * internally, not via Application callbacks) while preventing Chromium from learning
 * that the Activity stopped.
 *
 * Usage:
 * ```
 * override fun onStop() {
 *     val token = HighPerformanceLifecycleInterceptor.suspendCallbacks(application)
 *     try {
 *         super.onStop()
 *     } finally {
 *         HighPerformanceLifecycleInterceptor.restoreCallbacks(application, token)
 *     }
 * }
 * ```
 */
internal object HighPerformanceLifecycleInterceptor {

    private const val TAG = "ChildKioskLifecycle"
    private const val FIELD_NAME = "mActivityLifecycleCallbacks"

    /**
     * Saves and clears all ActivityLifecycleCallbacks from the Application.
     * Returns a token that must be passed to [restoreCallbacks] to restore them.
     */
    fun suspendCallbacks(app: Application): Any? {
        return try {
            val field = Application::class.java.getDeclaredField(FIELD_NAME)
            field.isAccessible = true
            val callbacks = field.get(app) as? java.util.ArrayList<*>
            if (callbacks.isNullOrEmpty()) {
                Log.d(TAG, "suspendCallbacks: no callbacks to suspend")
                null
            } else {
                // Save a snapshot of all callbacks
                val saved = java.util.ArrayList(callbacks)
                callbacks.clear()
                Log.d(TAG, "suspendCallbacks: removed ${saved.size} lifecycle callbacks")
                saved
            }
        } catch (e: Exception) {
            Log.w(TAG, "suspendCallbacks failed (reflection blocked on this Android version)", e)
            null
        }
    }

    /**
     * Restores the previously removed ActivityLifecycleCallbacks.
     * Safe to call with null token (no-op).
     */
    fun restoreCallbacks(app: Application, token: Any?) {
        if (token == null) return
        val saved = token as? java.util.ArrayList<*> ?: return
        try {
            val field = Application::class.java.getDeclaredField(FIELD_NAME)
            field.isAccessible = true
            val callbacks = field.get(app) as? java.util.ArrayList<Any>
            if (callbacks != null) {
                // Re-add all previously saved callbacks that aren't already present
                saved.forEach { callback ->
                    if (!callbacks.contains(callback)) {
                        callbacks.add(callback)
                    }
                }
                Log.d(TAG, "restoreCallbacks: restored ${saved.size} lifecycle callbacks")
            }
        } catch (e: Exception) {
            Log.w(TAG, "restoreCallbacks failed", e)
        }
    }
}
