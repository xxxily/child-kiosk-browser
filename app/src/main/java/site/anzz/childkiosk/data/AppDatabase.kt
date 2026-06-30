package site.anzz.childkiosk.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Database(
    entities = [WebAppEntity::class, SystemConfigEntity::class, BrowserHistoryEntity::class],
    version = 6,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun webAppDao(): WebAppDao
    abstract fun systemConfigDao(): SystemConfigDao
    abstract fun browserHistoryDao(): BrowserHistoryDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "child_kiosk_database"
                )
                .enableMultiInstanceInvalidation()
                .addMigrations(MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
                .fallbackToDestructiveMigration()
                .addCallback(DatabaseCallback(context.applicationContext))
                .build()
                INSTANCE = instance
                instance
            }
        }

        private class DatabaseCallback(
            private val context: Context
        ) : RoomDatabase.Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) {
                super.onCreate(db)
                insertDefaultData()
            }

            override fun onDestructiveMigration(db: SupportSQLiteDatabase) {
                super.onDestructiveMigration(db)
                insertDefaultData()
            }

            private fun insertDefaultData() {
                CoroutineScope(Dispatchers.IO).launch {
                    val database = getInstance(context)
                    // 1. 初始化预设应用 (默认均禁用)
                    val presetApps = listOf(
                        WebAppEntity(
                            title = "Scratch",
                            url = "https://scratch.mit.edu/",
                            iconPath = "icon_gamepad",
                            isPreset = true,
                            isEnabled = false,
                            category = WebAppEntity.CATEGORY_GAME
                        ),
                        WebAppEntity(
                            title = "PBS Kids",
                            url = "https://pbskids.org/",
                            iconPath = "icon_rocket",
                            isPreset = true,
                            isEnabled = false,
                            category = WebAppEntity.CATEGORY_GAME
                        ),
                        WebAppEntity(
                            title = "NASA Kids' Club",
                            url = "https://www.nasa.gov/learning-resources/kids-club/",
                            iconPath = "icon_puzzle",
                            isPreset = true,
                            isEnabled = false,
                            category = WebAppEntity.CATEGORY_STUDY
                        ),
                        WebAppEntity(
                            title = "国家中小学智慧教育平台",
                            url = "https://basic.smartedu.cn/",
                            iconPath = "icon_school",
                            isPreset = true,
                            isEnabled = false,
                            category = WebAppEntity.CATEGORY_STUDY
                        ),
                        WebAppEntity(
                            title = "模拟钢琴",
                            url = "https://pages.anzz.site/app/piano/",
                            iconPath = "icon_music",
                            isPreset = true,
                            isEnabled = true,
                            category = WebAppEntity.CATEGORY_GAME
                        ),
                        WebAppEntity(
                            title = "文案荟萃",
                            url = "https://pages.anzz.site/books/",
                            iconPath = "icon_book",
                            isPreset = true,
                            isEnabled = true,
                            category = WebAppEntity.CATEGORY_BOOK
                        ),
                        WebAppEntity(
                            title = "科普中国",
                            url = "https://www.kepuchina.cn/",
                            iconPath = "icon_lightbulb",
                            isPreset = true,
                            isEnabled = false,
                            category = WebAppEntity.CATEGORY_STUDY
                        ),
                        WebAppEntity(
                            title = "中国数字科技馆",
                            url = "https://www.cdstm.cn/",
                            iconPath = "icon_toy",
                            isPreset = true,
                            isEnabled = false,
                            category = WebAppEntity.CATEGORY_STUDY
                        ),
                        WebAppEntity(
                            title = "汉字屋",
                            url = "https://www.hanziwu.com/",
                            iconPath = "icon_home",
                            isPreset = true,
                            isEnabled = false,
                            category = WebAppEntity.CATEGORY_BOOK
                        ),
                        WebAppEntity(
                            title = "img-playground",
                            url = "https://img-playground.anzz.site",
                            iconPath = "icon_paint",
                            isPreset = true,
                            isEnabled = true,
                            category = WebAppEntity.CATEGORY_STUDY
                        ),
                        WebAppEntity(
                            title = "中国科普博览",
                            url = "https://www.kepu.net.cn/",
                            iconPath = "icon_pet",
                            isPreset = true,
                            isEnabled = false,
                            category = WebAppEntity.CATEGORY_STUDY
                        ),
                        WebAppEntity(
                            title = "CodeFlux",
                            url = "https://code.anzz.site",
                            iconPath = "icon_school",
                            isPreset = true,
                            isEnabled = true,
                            category = WebAppEntity.CATEGORY_STUDY
                        )
                    ) + ADDITIONAL_DEFAULT_PRESET_APPS.map { it.toEntity() }
                    database.webAppDao().insertAll(
                        presetApps.map { it.copy(sourceType = WebAppEntity.SOURCE_PRESET) }
                    )

                    // 2. 初始化默认系统配置
                    val defaultConfig = SystemConfigEntity(
                        id = 1,
                        verificationMode = "MATH",
                        pinHash = "",
                        timeLimitMinutes = 0,
                        dailyLimitMinutes = 0,
                        usedTimeTodayMs = 0,
                        lastUsedDate = ""
                    )
                    database.systemConfigDao().insertOrUpdateConfig(defaultConfig)
                }
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE web_apps ADD COLUMN source_type TEXT NOT NULL DEFAULT 'LOCAL'")
                db.execSQL("ALTER TABLE web_apps ADD COLUMN source_id TEXT")
                db.execSQL("ALTER TABLE web_apps ADD COLUMN source_item_id TEXT")
                db.execSQL("UPDATE web_apps SET source_type = 'PRESET' WHERE is_preset = 1")
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                val baseCreatedAt = System.currentTimeMillis()
                ADDITIONAL_DEFAULT_PRESET_APPS.forEachIndexed { index, seed ->
                    insertPresetAppIfMissing(db, seed, baseCreatedAt + index)
                }
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS browser_history (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        title TEXT NOT NULL,
                        url TEXT NOT NULL,
                        host TEXT NOT NULL,
                        visited_at INTEGER NOT NULL,
                        web_app_id INTEGER
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_browser_history_visited_at ON browser_history(visited_at)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_browser_history_host ON browser_history(host)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_browser_history_url ON browser_history(url)")
            }
        }

        private data class PresetAppSeed(
            val title: String,
            val url: String,
            val iconPath: String,
            val category: String
        ) {
            fun toEntity(): WebAppEntity {
                return WebAppEntity(
                    title = title,
                    url = url,
                    iconPath = iconPath,
                    isPreset = true,
                    isEnabled = false,
                    category = category,
                    sourceType = WebAppEntity.SOURCE_PRESET
                )
            }
        }

        private val ADDITIONAL_DEFAULT_PRESET_APPS = listOf(
            PresetAppSeed(
                title = "Abeto Messenger",
                url = "https://messenger.abeto.co",
                iconPath = "icon_gamepad",
                category = WebAppEntity.CATEGORY_GAME
            ),
            PresetAppSeed(
                title = "Astrocade",
                url = "https://www.astrocade.com",
                iconPath = "icon_gamepad",
                category = WebAppEntity.CATEGORY_GAME
            ),
            PresetAppSeed(
                title = "Y8 Games",
                url = "https://zh.y8.com",
                iconPath = "icon_gamepad",
                category = WebAppEntity.CATEGORY_GAME
            ),
            PresetAppSeed(
                title = "Poki",
                url = "https://poki.com",
                iconPath = "icon_gamepad",
                category = WebAppEntity.CATEGORY_GAME
            ),
            PresetAppSeed(
                title = "在线小霸王",
                url = "https://www.yikm.net",
                iconPath = "icon_gamepad",
                category = WebAppEntity.CATEGORY_GAME
            ),
            PresetAppSeed(
                title = "WGame80",
                url = "https://wgame80.com",
                iconPath = "icon_gamepad",
                category = WebAppEntity.CATEGORY_GAME
            ),
            PresetAppSeed(
                title = "Neal.fun",
                url = "https://neal.fun",
                iconPath = "icon_toy",
                category = WebAppEntity.CATEGORY_GAME
            ),
            PresetAppSeed(
                title = "CrazyGames",
                url = "https://www.crazygames.com",
                iconPath = "icon_gamepad",
                category = WebAppEntity.CATEGORY_GAME
            ),
            PresetAppSeed(
                title = "Sandspiel",
                url = "https://sandspiel.club",
                iconPath = "icon_toy",
                category = WebAppEntity.CATEGORY_GAME
            ),
            PresetAppSeed(
                title = "ANZZ Map",
                url = "https://map.anzz.site",
                iconPath = "icon_home",
                category = WebAppEntity.CATEGORY_TOOL
            ),
            PresetAppSeed(
                title = "Drawnix",
                url = "https://drawnix.com",
                iconPath = "icon_paint",
                category = WebAppEntity.CATEGORY_TOOL
            ),
            PresetAppSeed(
                title = "Excalidraw",
                url = "https://excalidraw.com",
                iconPath = "icon_paint",
                category = WebAppEntity.CATEGORY_TOOL
            ),
            PresetAppSeed(
                title = "tldraw",
                url = "https://www.tldraw.com",
                iconPath = "icon_paint",
                category = WebAppEntity.CATEGORY_TOOL
            ),
            PresetAppSeed(
                title = "Draw.Chat",
                url = "https://draw.chat/zh/index.html",
                iconPath = "icon_paint",
                category = WebAppEntity.CATEGORY_TOOL
            ),
            PresetAppSeed(
                title = "HTwins",
                url = "https://htwins.net",
                iconPath = "icon_lightbulb",
                category = WebAppEntity.CATEGORY_TOOL
            )
        )

        private fun insertPresetAppIfMissing(
            db: SupportSQLiteDatabase,
            seed: PresetAppSeed,
            createdAt: Long
        ) {
            db.execSQL(
                """
                INSERT INTO web_apps (
                    title, url, icon_path, is_preset, is_enabled, category,
                    created_at, source_type, source_id, source_item_id
                )
                SELECT ?, ?, ?, 1, 0, ?, ?, ?, NULL, NULL
                WHERE NOT EXISTS (
                    SELECT 1 FROM web_apps WHERE url = ?
                )
                """.trimIndent(),
                arrayOf<Any?>(
                    seed.title,
                    seed.url,
                    seed.iconPath,
                    seed.category,
                    createdAt,
                    WebAppEntity.SOURCE_PRESET,
                    seed.url
                )
            )
        }
    }
}
