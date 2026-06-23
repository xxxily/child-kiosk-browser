package site.anzz.childkiosk.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface WebAppDao {
    @Query("SELECT * FROM web_apps ORDER BY created_at DESC")
    fun getAllWebAppsFlow(): Flow<List<WebAppEntity>>

    @Query("SELECT * FROM web_apps ORDER BY created_at DESC")
    suspend fun getAllWebApps(): List<WebAppEntity>

    @Query("SELECT * FROM web_apps WHERE id = :id")
    suspend fun getWebAppById(id: Int): WebAppEntity?

    @Query("SELECT * FROM web_apps WHERE source_type = :sourceType")
    suspend fun getWebAppsBySourceType(sourceType: String): List<WebAppEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertWebApp(webApp: WebAppEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(webApps: List<WebAppEntity>)

    @Update
    suspend fun updateWebApp(webApp: WebAppEntity)

    @Delete
    suspend fun deleteWebApp(webApp: WebAppEntity)

    @Query("DELETE FROM web_apps WHERE source_type = :sourceType")
    suspend fun deleteWebAppsBySourceType(sourceType: String)
}
