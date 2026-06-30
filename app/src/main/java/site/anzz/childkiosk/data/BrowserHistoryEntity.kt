package site.anzz.childkiosk.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "browser_history",
    indices = [
        Index(value = ["visited_at"]),
        Index(value = ["host"]),
        Index(value = ["url"])
    ]
)
data class BrowserHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "url") val url: String,
    @ColumnInfo(name = "host") val host: String,
    @ColumnInfo(name = "visited_at") val visitedAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "web_app_id") val webAppId: Int? = null
)
