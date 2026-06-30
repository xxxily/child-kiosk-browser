package site.anzz.childkiosk.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface BrowserHistoryDao {
    @Query("SELECT * FROM browser_history ORDER BY visited_at DESC LIMIT :limit")
    fun getRecentHistoryFlow(limit: Int): Flow<List<BrowserHistoryEntity>>

    @Query("SELECT * FROM browser_history ORDER BY visited_at DESC LIMIT :limit")
    suspend fun getRecentHistory(limit: Int): List<BrowserHistoryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(history: BrowserHistoryEntity): Long

    @Query("DELETE FROM browser_history WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM browser_history")
    suspend fun clearAll()

    @Query("DELETE FROM browser_history WHERE visited_at < :cutoffMs")
    suspend fun deleteOlderThan(cutoffMs: Long)
}
