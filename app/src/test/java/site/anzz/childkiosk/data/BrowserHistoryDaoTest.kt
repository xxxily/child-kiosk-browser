package site.anzz.childkiosk.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BrowserHistoryDaoTest {
    private lateinit var database: AppDatabase
    private lateinit var dao: BrowserHistoryDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.browserHistoryDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun repeatedVisitInsideWindowUpdatesOneRowAndPreservesAppAssociation() = runBlocking {
        dao.recordVisit(
            history(
                title = "旧标题",
                visitedAt = 100_000L,
                webAppId = 7
            ),
            duplicateWindowMs = 30_000L
        )
        dao.recordVisit(
            history(
                title = "新标题",
                visitedAt = 120_000L,
                webAppId = null
            ),
            duplicateWindowMs = 30_000L
        )

        val rows = dao.getRecentHistory(10)
        assertEquals(1, rows.size)
        assertEquals("新标题", rows.single().title)
        assertEquals(120_000L, rows.single().visitedAt)
        assertEquals(7, rows.single().webAppId)
    }

    @Test
    fun repeatedVisitOutsideWindowCreatesASeparateVisit() = runBlocking {
        dao.recordVisit(history(visitedAt = 100_000L), duplicateWindowMs = 30_000L)
        dao.recordVisit(history(visitedAt = 131_000L), duplicateWindowMs = 30_000L)

        assertEquals(2, dao.getRecentHistory(10).size)
    }

    @Test
    fun recordingVisitCollapsesPreexistingRecentDuplicates() = runBlocking {
        dao.insert(history(title = "重复一", visitedAt = 100_000L))
        dao.insert(history(title = "重复二", visitedAt = 110_000L))

        dao.recordVisit(
            history(title = "最终标题", visitedAt = 120_000L),
            duplicateWindowMs = 30_000L
        )

        val rows = dao.getRecentHistory(10)
        assertEquals(1, rows.size)
        assertEquals("最终标题", rows.single().title)
        assertEquals(120_000L, rows.single().visitedAt)
    }

    private fun history(
        title: String = "示例网站",
        visitedAt: Long,
        webAppId: Int? = null
    ): BrowserHistoryEntity {
        return BrowserHistoryEntity(
            title = title,
            url = "https://example.com/",
            host = "example.com",
            visitedAt = visitedAt,
            webAppId = webAppId
        )
    }
}
