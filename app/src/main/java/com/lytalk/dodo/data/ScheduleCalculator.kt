package com.lytalk.dodo.data

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

object ScheduleCalculator {
    const val ONCE = "ONCE"
    const val DAILY = "DAILY"
    const val WEEKLY = "WEEKLY"
    const val INTERVAL_MINUTE = "INTERVAL_MINUTE"
    const val INTERVAL_HOUR = "INTERVAL_HOUR"

    fun nextTrigger(task: TaskEntity, now: Long = System.currentTimeMillis()): Long? {
        if (!task.enabled) return null
        return when (task.scheduleType) {
            ONCE -> task.onceAtMillis?.takeIf { it > now }
            WEEKLY -> nextWeekly(now, task.hour, task.minute, task.daysMask)
            INTERVAL_MINUTE, INTERVAL_HOUR -> nextInterval(task, now)
            else -> nextDaily(now, task.hour, task.minute)
        }
    }

    fun isInterval(type: String): Boolean = type == INTERVAL_MINUTE || type == INTERVAL_HOUR

    fun intervalMillis(task: TaskEntity): Long? = when (task.scheduleType) {
        INTERVAL_MINUTE -> task.minute.coerceIn(1, 59) * 60_000L
        INTERVAL_HOUR -> task.hour.coerceIn(1, 24) * 3_600_000L
        else -> null
    }

    fun nextClock(hour: Int, minute: Int, now: Long = System.currentTimeMillis()): Long =
        nextDaily(now, hour, minute)

    fun withClock(dateMillis: Long, hour: Int, minute: Int): Long {
        val zone = ZoneId.systemDefault()
        val date = Instant.ofEpochMilli(dateMillis).atZone(zone).toLocalDate()
        return date.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()
    }

    fun nextDaily(now: Long, hour: Int, minute: Int): Long {
        val zone = ZoneId.systemDefault()
        val moment = Instant.ofEpochMilli(now).atZone(zone)
        val today = atClock(moment.toLocalDate(), hour, minute, zone)
        return if (today.toInstant().toEpochMilli() > now) {
            today.toInstant().toEpochMilli()
        } else {
            atClock(moment.toLocalDate().plusDays(1), hour, minute, zone).toInstant().toEpochMilli()
        }
    }

    fun nextWeekly(now: Long, hour: Int, minute: Int, mask: Int): Long? {
        if (mask == 0) return null
        val zone = ZoneId.systemDefault()
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        for (offset in 0..7) {
            val date = today.plusDays(offset.toLong())
            if (mask and dayMask(date) == 0) continue
            val candidate = atClock(date, hour, minute, zone).toInstant().toEpochMilli()
            if (candidate > now) return candidate
        }
        return null
    }

    private fun nextInterval(task: TaskEntity, now: Long): Long {
        val period = intervalMillis(task) ?: return now
        val stored = task.nextRunAt
        if (stored != null && stored > now) return stored
        if (stored != null) {
            var next = stored + period
            while (next <= now) next += period
            return next
        }
        return now + period
    }

    private fun atClock(date: LocalDate, hour: Int, minute: Int, zone: ZoneId): ZonedDateTime =
        LocalDateTime.of(date, LocalTime.of(hour, minute)).atZone(zone)

    private fun dayMask(date: LocalDate): Int {
        val index = when (date.dayOfWeek) {
            java.time.DayOfWeek.SUNDAY -> 0
            else -> date.dayOfWeek.value
        }
        return 1 shl index
    }
}
