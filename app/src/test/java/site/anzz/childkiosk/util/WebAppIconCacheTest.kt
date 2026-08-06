package site.anzz.childkiosk.util

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class WebAppIconCacheTest {
    private lateinit var context: Context
    private lateinit var iconDir: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        iconDir = File(context.filesDir, "web_app_icons").apply {
            deleteRecursively()
            mkdirs()
        }
    }

    @After
    fun tearDown() {
        iconDir.deleteRecursively()
    }

    @Test
    fun preferredIconUsesCachedWebsiteIcon() {
        val fileName = "${"a".repeat(64)}.png"
        File(iconDir, fileName).writeBytes(byteArrayOf(1, 2, 3))
        val cachedPath = "cached-web-icon:$fileName"

        assertEquals(
            cachedPath,
            WebAppIconCache.preferredIconPath(context, cachedPath, "icon_gamepad")
        )
    }

    @Test
    fun preferredIconFallsBackWhenWebsiteCacheIsMissing() {
        val missingPath = "cached-web-icon:${"b".repeat(64)}.png"

        assertEquals(
            "icon_gamepad",
            WebAppIconCache.preferredIconPath(context, missingPath, "icon_gamepad")
        )
    }
}
