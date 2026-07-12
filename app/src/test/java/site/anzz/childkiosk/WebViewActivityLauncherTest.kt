package site.anzz.childkiosk

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import site.anzz.childkiosk.util.KioskPrefs

@RunWith(RobolectricTestRunner::class)
class WebViewActivityLauncherTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        KioskPrefs.setProtectionMode(context, KioskPrefs.MODE_NONE)
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        shadowOf(manager).setLockTaskModeState(ActivityManager.LOCK_TASK_MODE_NONE)
    }

    @After
    fun tearDown() {
        KioskPrefs.setProtectionMode(context, KioskPrefs.MODE_NONE)
    }

    @Test
    fun normalModeUsesPersistentTaskHost() {
        val intent = WebViewActivityLauncher.createIntent(
            context,
            WebViewHostConditions(
                deviceOwner = false,
                lockTaskActive = false,
                softLockConfigured = false
            )
        )

        assertEquals(PersistentWebViewActivity::class.java.name, intent.component?.className)
        assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK, intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK)
        assertTrue(intent.hasFlag(Intent.FLAG_ACTIVITY_SINGLE_TOP))
    }

    @Test
    fun configuredSoftLockUsesSameTaskHost() {
        KioskPrefs.setProtectionMode(context, KioskPrefs.MODE_SOFT_LOCK)

        val intent = WebViewActivityLauncher.createIntent(context)

        assertEquals(WebViewActivity::class.java.name, intent.component?.className)
        assertFalse(intent.hasFlag(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    @Test
    fun activeLockTaskUsesSameTaskHost() {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        shadowOf(manager).setLockTaskModeState(ActivityManager.LOCK_TASK_MODE_LOCKED)

        val intent = WebViewActivityLauncher.createIntent(context)

        assertEquals(WebViewActivity::class.java.name, intent.component?.className)
        assertFalse(intent.hasFlag(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    @Test
    fun deviceOwnerConditionUsesSameTaskHost() {
        val conditions = WebViewHostConditions(
            deviceOwner = true,
            lockTaskActive = false,
            softLockConfigured = false
        )

        val intent = WebViewActivityLauncher.createIntent(context, conditions)

        assertEquals(WebViewActivity::class.java.name, intent.component?.className)
        assertFalse(intent.hasFlag(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    @Test
    fun externalEntryUsesNewTaskForKioskHost() {
        val intent = WebViewActivityLauncher.createIntent(
            context,
            WebViewHostConditions(
                deviceOwner = false,
                lockTaskActive = true,
                softLockConfigured = false
            ),
            WebViewLaunchSource.EXTERNAL_ENTRY
        )

        assertEquals(WebViewActivity::class.java.name, intent.component?.className)
        assertTrue(intent.hasFlag(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    @Test
    fun notificationResumeTargetsExactHostWithoutClearTop() {
        WebViewHostMode.entries.forEach { hostMode ->
            val intent = WebViewActivityLauncher.createResumeIntent(context, hostMode)
            val expectedClass = when (hostMode) {
                WebViewHostMode.KIOSK_SAME_TASK -> WebViewActivity::class.java
                WebViewHostMode.PERSISTENT_TASK -> PersistentWebViewActivity::class.java
            }

            assertEquals(expectedClass.name, intent.component?.className)
            assertTrue(intent.hasFlag(Intent.FLAG_ACTIVITY_NEW_TASK))
            assertTrue(intent.hasFlag(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
            assertTrue(intent.hasFlag(Intent.FLAG_ACTIVITY_SINGLE_TOP))
            assertFalse(intent.hasFlag(Intent.FLAG_ACTIVITY_CLEAR_TOP))
            assertTrue(intent.getBooleanExtra(WebViewActivity.EXTRA_RESUME_EXISTING_HOST, false))
        }
    }

    @Test
    fun homeActionPreservesNormalTaskAndClosesKioskHost() {
        assertEquals(
            WebViewHomeAction.OPEN_MAIN_TASK,
            resolveWebViewHomeAction(WebViewHostMode.PERSISTENT_TASK)
        )
        assertEquals(
            WebViewHomeAction.CLOSE_CURRENT_HOST,
            resolveWebViewHomeAction(WebViewHostMode.KIOSK_SAME_TASK)
        )
    }

    private fun Intent.hasFlag(flag: Int): Boolean = flags and flag == flag
}
