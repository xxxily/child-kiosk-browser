package site.anzz.childkiosk.ui.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserTabUrlIdentityTest {
    @Test
    fun rootUrlWithOrWithoutSlashFindsTheSameTab() {
        val tab = BrowserTab(url = "https://map.anzz.site/")

        assertSame(tab, listOf(tab).findBrowserTabByUrl("https://map.anzz.site"))
        assertTrue(
            browserTabUrlsEquivalent(
                "HTTPS://MAP.ANZZ.SITE:443",
                "https://map.anzz.site/"
            )
        )
    }

    @Test
    fun meaningfulPathQueryAndFragmentDifferencesRemainDistinct() {
        assertFalse(
            browserTabUrlsEquivalent(
                "https://example.test/app",
                "https://example.test/app/"
            )
        )
        assertFalse(
            browserTabUrlsEquivalent(
                "https://example.test/?mode=a",
                "https://example.test/?mode=b"
            )
        )
        assertFalse(
            browserTabUrlsEquivalent(
                "https://example.test/#one",
                "https://example.test/#two"
            )
        )
        assertEquals(null, emptyList<BrowserTab>().findBrowserTabByUrl("about:blank"))
    }
}
