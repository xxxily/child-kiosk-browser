package com.example.childkiosk.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "system_configs")
data class SystemConfigEntity(
    @PrimaryKey val id: Int = 1,
    @ColumnInfo(name = "verification_mode") val verificationMode: String = "MATH", // "MATH" or "PIN"
    @ColumnInfo(name = "pin_hash") val pinHash: String = "",
    @ColumnInfo(name = "time_limit_minutes") val timeLimitMinutes: Int = 0, // 0 means no limit
    @ColumnInfo(name = "daily_limit_minutes") val dailyLimitMinutes: Int = 0, // 0 means no limit
    @ColumnInfo(name = "used_time_today_ms") val usedTimeTodayMs: Long = 0,
    @ColumnInfo(name = "last_used_date") val lastUsedDate: String = ""
)
