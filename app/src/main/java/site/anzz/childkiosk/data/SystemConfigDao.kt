package site.anzz.childkiosk.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SystemConfigDao {
    @Query("SELECT * FROM system_configs WHERE id = 1 LIMIT 1")
    fun getSystemConfigFlow(): Flow<SystemConfigEntity?>

    @Query("SELECT * FROM system_configs WHERE id = 1 LIMIT 1")
    suspend fun getSystemConfig(): SystemConfigEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateConfig(config: SystemConfigEntity)
}
