package site.anzz.childkiosk.util

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import site.anzz.childkiosk.util.filter.FilterRepository

@RunWith(RobolectricTestRunner::class)
class KioskPrefsFilterEnablementTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        clearPreferences()
    }

    @After
    fun tearDown() {
        clearPreferences()
    }

    @Test
    fun legacyRepositoryValueMigratesOnlyWhenAuthoritativeValueIsAbsent() {
        FilterRepository.setEnabled(context, true)

        assertTrue(KioskPrefs.getOrMigrateLimitAdBlockEnabled(context, legacyEnabled = true))
        assertTrue(KioskPrefs.isLimitAdBlockEnabled(context))

        KioskPrefs.setLimitAdBlockEnabled(context, false)
        assertFalse(KioskPrefs.getOrMigrateLimitAdBlockEnabled(context, legacyEnabled = true))
    }

    @Test
    fun authoritativeKioskValueDoesNotDependOnLegacyRepositoryValue() {
        FilterRepository.setEnabled(context, false)
        KioskPrefs.setLimitAdBlockEnabled(context, true)

        assertTrue(KioskPrefs.isLimitAdBlockEnabled(context))
        assertTrue(FilterRepository.getSettings(context).enabled)
        assertTrue(KioskPrefs.getWebViewRuntimeConfig(context).filterSnapshot.enabled)
    }

    private fun clearPreferences() {
        context.getSharedPreferences("kiosk_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("kiosk_filter_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        FilterRepository.invalidate()
    }
}
