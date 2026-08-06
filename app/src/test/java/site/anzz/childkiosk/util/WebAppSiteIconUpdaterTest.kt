package site.anzz.childkiosk.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import site.anzz.childkiosk.data.WebAppEntity

class WebAppSiteIconUpdaterTest {
    @Test
    fun presetAppsUseAutomaticWebsiteIconDiscovery() {
        assertTrue(
            WebAppSiteIconUpdater.shouldAutoDiscoverSiteIcon(
                app(isPreset = true, sourceType = WebAppEntity.SOURCE_PRESET)
            )
        )
    }

    @Test
    fun userManagedAppsKeepTheirExplicitIconChoice() {
        assertFalse(
            WebAppSiteIconUpdater.shouldAutoDiscoverSiteIcon(
                app(isPreset = false, sourceType = WebAppEntity.SOURCE_LOCAL)
            )
        )
    }

    private fun app(isPreset: Boolean, sourceType: String): WebAppEntity {
        return WebAppEntity(
            id = 1,
            title = "示例",
            url = "https://example.com/",
            iconPath = "icon_public",
            isPreset = isPreset,
            sourceType = sourceType
        )
    }
}
