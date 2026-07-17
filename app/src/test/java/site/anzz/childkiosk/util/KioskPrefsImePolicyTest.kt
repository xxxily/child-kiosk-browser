package site.anzz.childkiosk.util

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import site.anzz.childkiosk.util.filter.FilterRepository

@RunWith(RobolectricTestRunner::class)
class KioskPrefsImePolicyTest {
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
    fun `normal child normal mode round trip keeps ime unrestricted`() {
        KioskPrefs.applyQuickMode(context, KioskPrefs.QUICK_MODE_NORMAL)
        assertFalse(KioskPrefs.getWebViewRuntimeConfig(context).limitImeInput)

        KioskPrefs.applyQuickMode(context, KioskPrefs.QUICK_MODE_CHILD)
        assertFalse(KioskPrefs.getWebViewRuntimeConfig(context).limitImeInput)

        KioskPrefs.applyQuickMode(context, KioskPrefs.QUICK_MODE_NORMAL)
        assertFalse(KioskPrefs.getWebViewRuntimeConfig(context).limitImeInput)
    }

    @Test
    fun `mode presets clear a previously enabled ime restriction`() {
        KioskPrefs.setLimitImeInputEnabled(context, true)

        KioskPrefs.applyQuickMode(context, KioskPrefs.QUICK_MODE_CHILD)
        assertFalse(KioskPrefs.getWebViewRuntimeConfig(context).limitImeInput)

        KioskPrefs.setLimitImeInputEnabled(context, true)
        KioskPrefs.applyQuickMode(context, KioskPrefs.QUICK_MODE_NORMAL)
        assertFalse(KioskPrefs.getWebViewRuntimeConfig(context).limitImeInput)
    }

    private fun clearPreferences() {
        context.getSharedPreferences("kiosk_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("kiosk_filter_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        FilterRepository.invalidate()
    }
}
