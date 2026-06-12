package com.example.childkiosk.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Database(entities = [WebAppEntity::class, SystemConfigEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {

    abstract fun webAppDao(): WebAppDao
    abstract fun systemConfigDao(): SystemConfigDao

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
                INSTANCE?.let { database ->
                    CoroutineScope(Dispatchers.IO).launch {
                        // 1. 初始化预设应用
                        val presetApps = listOf(
                            WebAppEntity(
                                title = "Scratch",
                                url = "https://scratch.mit.edu/",
                                iconPath = "icon_gamepad", // 预设图标名，后面在应用中匹配
                                isPreset = true
                            ),
                            WebAppEntity(
                                title = "PBS Kids",
                                url = "https://pbskids.org/",
                                iconPath = "icon_rocket",
                                isPreset = true
                            ),
                            WebAppEntity(
                                title = "NASA Kids' Club",
                                url = "https://www.nasa.gov/learning-resources/kids-club/",
                                iconPath = "icon_puzzle",
                                isPreset = true
                            )
                        )
                        database.webAppDao().insertAll(presetApps)

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
        }
    }
}
