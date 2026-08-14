package com.focuslock.app

import java.util.Calendar
import java.util.concurrent.TimeUnit

object TimeZoneHelper {

    enum class BlockZone {
        HARD_BLOCK,
        SOFT_BLOCK,
        FREE
    }

    private const val HARD_BLOCK_START_HOUR = 20
    private const val HARD_BLOCK_START_MINUTE = 30
    private const val MORNING_UNLOCK_HOUR = 10
    private const val SOFT_BLOCK_END_HOUR = 15

    fun getCurrentZone(): BlockZone {
        val calendar = Calendar.getInstance()
        return getZoneFor(
            hour = calendar.get(Calendar.HOUR_OF_DAY),
            minute = calendar.get(Calendar.MINUTE)
        )
    }

    fun getZoneFor(hour: Int, minute: Int): BlockZone {
        require(hour in 0..23) { "Hour must be between 0 and 23" }
        require(minute in 0..59) { "Minute must be between 0 and 59" }

        return when {
            isHardBlockTime(hour, minute) -> BlockZone.HARD_BLOCK
            hour in MORNING_UNLOCK_HOUR until SOFT_BLOCK_END_HOUR -> BlockZone.SOFT_BLOCK
            else -> BlockZone.FREE
        }
    }

    fun getMinutesUntilUnlock(): Long {
        val now = Calendar.getInstance()
        val nextUnlockTime = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, MORNING_UNLOCK_HOUR)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)

            if (isAfterEveningHardBlockStart(
                    now.get(Calendar.HOUR_OF_DAY),
                    now.get(Calendar.MINUTE)
                )
            ) {
                add(Calendar.DAY_OF_YEAR, 1)
            }
        }

        val diffMillis = nextUnlockTime.timeInMillis - now.timeInMillis
        return TimeUnit.MILLISECONDS.toMinutes(diffMillis).coerceAtLeast(0)
    }

    private fun isHardBlockTime(hour: Int, minute: Int): Boolean {
        return isAfterEveningHardBlockStart(hour, minute) || hour < MORNING_UNLOCK_HOUR
    }

    private fun isAfterEveningHardBlockStart(hour: Int, minute: Int): Boolean {
        return hour > HARD_BLOCK_START_HOUR ||
                (hour == HARD_BLOCK_START_HOUR && minute >= HARD_BLOCK_START_MINUTE)
    }
}
