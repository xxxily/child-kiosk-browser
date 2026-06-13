package com.example.childkiosk.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Database(entities = [WebAppEntity::class, SystemConfigEntity::class], version = 2, exportSchema = false)
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
                INSTANCE?.let { database ->
                    CoroutineScope(Dispatchers.IO).launch {
                        // 1. 初始化预设应用 (默认均禁用)
                        val presetApps = listOf(
                            WebAppEntity(
                                title = "Scratch",
                                url = "https://scratch.mit.edu/",
                                iconPath = "icon_gamepad",
                                isPreset = true,
                                isEnabled = false
                            ),
                            WebAppEntity(
                                title = "PBS Kids",
                                url = "https://pbskids.org/",
                                iconPath = "icon_rocket",
                                isPreset = true,
                                isEnabled = false
                            ),
                            WebAppEntity(
                                title = "NASA Kids' Club",
                                url = "https://www.nasa.gov/learning-resources/kids-club/",
                                iconPath = "icon_puzzle",
                                isPreset = true,
                                isEnabled = false
                            ),
                            WebAppEntity(
                                title = "国家中小学智慧教育平台",
                                url = "https://basic.smartedu.cn/",
                                iconPath = "icon_school",
                                isPreset = true,
                                isEnabled = false
                            ),
                            WebAppEntity(
                                title = "故宫博物院(青少版)",
                                url = "https://www.dpm.org.cn/kids.html",
                                iconPath = "icon_book",
                                isPreset = true,
                                isEnabled = false
                            ),
                            WebAppEntity(
                                title = "中华珍宝馆",
                                url = "http://www.ltfc.net/",
                                iconPath = "icon_paint",
                                isPreset = true,
                                isEnabled = false
                            ),
                            WebAppEntity(
                                title = "科普中国",
                                url = "https://www.kepuchina.cn/",
                                iconPath = "icon_lightbulb",
                                isPreset = true,
                                isEnabled = false
                            ),
                            WebAppEntity(
                                title = "中国数字科技馆",
                                url = "https://www.cdstm.cn/",
                                iconPath = "icon_toy",
                                isPreset = true,
                                isEnabled = false
                            ),
                            WebAppEntity(
                                title = "汉字屋",
                                url = "https://www.hanziwu.com/",
                                iconPath = "icon_home",
                                isPreset = true,
                                isEnabled = false
                            ),
                            WebAppEntity(
                                title = "编程猫",
                                url = "https://www.codemao.cn/",
                                iconPath = "icon_gamepad",
                                isPreset = true,
                                isEnabled = false
                            ),
                            WebAppEntity(
                                title = "中国科普博览",
                                url = "https://www.kepu.net.cn/",
                                iconPath = "icon_pet",
                                isPreset = true,
                                isEnabled = false
                            ),
                            WebAppEntity(
                                title = "宝宝巴士官网",
                                url = "https://www.babybus.com/",
                                iconPath = "icon_gift",
                                isPreset = true,
                                isEnabled = false
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
