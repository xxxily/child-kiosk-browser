package site.anzz.childkiosk.util

import site.anzz.childkiosk.data.SystemConfigEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object TimeLimiter {

    fun getTodayDateString(): String {
        return SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
    }

    /**
     * 判断是否已超限（每日或单次）
     */
    fun isLimitExceeded(config: SystemConfigEntity): Boolean {
        val today = getTodayDateString()
        val usedToday = if (config.lastUsedDate == today) config.usedTimeTodayMs else 0L
        
        val dailyLimitMs = config.dailyLimitMinutes * 60 * 1000L
        if (dailyLimitMs > 0 && usedToday >= dailyLimitMs) {
            return true
        }
        return false
    }

    /**
     * 计算当前会话剩余的可用时间（毫秒）
     * 返回 -1L 表示无限制
     */
    fun calculateRemainingTimeMs(config: SystemConfigEntity, sessionStartedTimeMs: Long): Long {
        val today = getTodayDateString()
        val usedToday = if (config.lastUsedDate == today) config.usedTimeTodayMs else 0L

        val dailyLimitMs = config.dailyLimitMinutes * 60 * 1000L
        val sessionLimitMs = config.timeLimitMinutes * 60 * 1000L

        val dailyRemainingMs = if (dailyLimitMs > 0) {
            (dailyLimitMs - usedToday).coerceAtLeast(0)
        } else {
            Long.MAX_VALUE
        }

        val sessionUsedMs = System.currentTimeMillis() - sessionStartedTimeMs
        val sessionRemainingMs = if (sessionLimitMs > 0) {
            (sessionLimitMs - sessionUsedMs).coerceAtLeast(0)
        } else {
            Long.MAX_VALUE
        }

        val minRemaining = minOf(dailyRemainingMs, sessionRemainingMs)
        return if (minRemaining == Long.MAX_VALUE) -1L else minRemaining
    }
}
