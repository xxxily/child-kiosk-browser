package site.anzz.childkiosk.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "web_apps")
data class WebAppEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "url") val url: String,
    @ColumnInfo(name = "icon_path") val iconPath: String?,
    @ColumnInfo(name = "site_icon_path") val siteIconPath: String? = null,
    @ColumnInfo(name = "is_preset") val isPreset: Boolean = false,
    @ColumnInfo(name = "is_enabled") val isEnabled: Boolean = true,
    @ColumnInfo(name = "category", defaultValue = "OTHER") val category: String = CATEGORY_OTHER,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "source_type", defaultValue = "LOCAL") val sourceType: String = SOURCE_LOCAL,
    @ColumnInfo(name = "source_id") val sourceId: String? = null,
    @ColumnInfo(name = "source_item_id") val sourceItemId: String? = null
) {
    companion object {
        const val CATEGORY_GAME = "GAME"
        const val CATEGORY_VIDEO = "VIDEO"
        const val CATEGORY_BOOK = "BOOK"
        const val CATEGORY_STUDY = "STUDY"
        const val CATEGORY_TOOL = "TOOL"
        const val CATEGORY_OTHER = "OTHER"

        const val SOURCE_LOCAL = "LOCAL"
        const val SOURCE_PRESET = "PRESET"
        const val SOURCE_SUBSCRIPTION = "SUBSCRIPTION"

        fun getCategoryDisplayName(category: String): String {
            return when (category) {
                CATEGORY_GAME -> "游戏"
                CATEGORY_VIDEO -> "视频"
                CATEGORY_BOOK -> "绘本"
                CATEGORY_STUDY -> "学习"
                CATEGORY_TOOL -> "工具"
                else -> "其他"
            }
        }

        fun getCategoryEmoji(category: String): String {
            return when (category) {
                CATEGORY_GAME -> "🎮"
                CATEGORY_VIDEO -> "📺"
                CATEGORY_BOOK -> "📚"
                CATEGORY_STUDY -> "✍️"
                CATEGORY_TOOL -> "🧰"
                else -> "⚙️"
            }
        }
    }
}
