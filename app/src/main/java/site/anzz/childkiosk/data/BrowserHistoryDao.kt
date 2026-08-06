package site.anzz.childkiosk.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface BrowserHistoryDao {
    @Query("SELECT * FROM browser_history ORDER BY visited_at DESC LIMIT :limit")
    fun getRecentHistoryFlow(limit: Int): Flow<List<BrowserHistoryEntity>>

    @Query("SELECT * FROM browser_history ORDER BY visited_at DESC LIMIT :limit")
    suspend fun getRecentHistory(limit: Int): List<BrowserHistoryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(history: BrowserHistoryEntity): Long

    @Query(
        """
        SELECT * FROM browser_history
        WHERE url = :url AND visited_at >= :visitedSince
        ORDER BY visited_at DESC, id DESC
        LIMIT 1
        """
    )
    suspend fun getMostRecentVisitSince(url: String, visitedSince: Long): BrowserHistoryEntity?

    @Query(
        """
        UPDATE browser_history
        SET title = :title,
            host = :host,
            visited_at = :visitedAt,
            web_app_id = COALESCE(:webAppId, web_app_id)
        WHERE id = :id
        """
    )
    suspend fun updateVisit(
        id: Long,
        title: String,
        host: String,
        visitedAt: Long,
        webAppId: Int?
    )

    @Query(
        """
        DELETE FROM browser_history
        WHERE url = :url
          AND visited_at >= :visitedSince
          AND id != :keepId
        """
    )
    suspend fun deleteDuplicateVisits(url: String, visitedSince: Long, keepId: Long)

    @Transaction
    suspend fun recordVisit(
        history: BrowserHistoryEntity,
        duplicateWindowMs: Long
    ): Long {
        val visitedSince = history.visitedAt - duplicateWindowMs.coerceAtLeast(0L)
        val existing = getMostRecentVisitSince(history.url, visitedSince)
        if (existing == null) {
            return insert(history)
        }

        updateVisit(
            id = existing.id,
            title = history.title,
            host = history.host,
            visitedAt = history.visitedAt,
            webAppId = history.webAppId
        )
        deleteDuplicateVisits(
            url = history.url,
            visitedSince = visitedSince,
            keepId = existing.id
        )
        return existing.id
    }

    @Query("DELETE FROM browser_history WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM browser_history")
    suspend fun clearAll()

    @Query("DELETE FROM browser_history WHERE visited_at < :cutoffMs")
    suspend fun deleteOlderThan(cutoffMs: Long)
}
