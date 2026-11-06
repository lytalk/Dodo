package com.lytalk.dodo.data

import java.text.SimpleDateFormat
import java.time.Instant
import java.time.ZoneId
import java.util.Calendar
import java.util.Date
import java.util.Locale

private val dateTimeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA)
private val nextFormat = SimpleDateFormat("M月d日 HH:mm", Locale.CHINA)

fun formatHm(hour: Int, minute: Int): String = "%02d:%02d".format(hour, minute)

fun dayBit(calendarDay: Int): Int = 1 shl (calendarDay - 1)

fun selectedDayLabels(mask: Int): String {
    val order = listOf(
        Calendar.MONDAY,
        Calendar.TUESDAY,
        Calendar.WEDNESDAY,
        Calendar.THURSDAY,
        Calendar.FRIDAY,
        Calendar.SATURDAY,
        Calendar.SUNDAY,
    )
    val names = mapOf(
        Calendar.MONDAY to "一",
        Calendar.TUESDAY to "二",
        Calendar.WEDNESDAY to "三",
        Calendar.THURSDAY to "四",
        Calendar.FRIDAY to "五",
        Calendar.SATURDAY to "六",
        Calendar.SUNDAY to "日",
    )
    val selected = order.filter { mask and dayBit(it) != 0 }.map { names.getValue(it) }
    return if (selected.isEmpty()) "未选星期" else selected.joinToString("、")
}

fun scheduleLabel(task: TaskEntity): String {
    val hm = formatHm(task.hour, task.minute)
    return when (task.scheduleType) {
        ScheduleCalculator.ONCE -> {
            val at = task.onceAtMillis
            if (at == null) "执行一次" else "${dateTimeFormat.format(Date(at))} 执行一次"
        }
        ScheduleCalculator.WEEKLY -> "每周 ${selectedDayLabels(task.daysMask)} $hm"
        ScheduleCalculator.INTERVAL_MINUTE -> "每 ${task.minute.coerceAtLeast(1)} 分钟"
        ScheduleCalculator.INTERVAL_HOUR -> "每 ${task.hour.coerceAtLeast(1)} 小时"
        else -> "每天 $hm"
    }
}

fun nextRunLabel(task: TaskEntity, now: Long = System.currentTimeMillis()): String {
    if (!task.enabled) return "未启用"
    val next = ScheduleCalculator.nextTrigger(task, now) ?: return "没有下一次自动执行"
    return "下次 ${formatNextDay(next, now)}"
}

private fun formatNextDay(next: Long, now: Long): String {
    val zone = ZoneId.systemDefault()
    val nextDate = Instant.ofEpochMilli(next).atZone(zone).toLocalDate()
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val hm = nextFormat.format(Date(next)).substringAfter(" ")
    val day = when (nextDate) {
        today -> "今天"
        today.plusDays(1) -> "明天"
        else -> "${nextDate.monthValue}月${nextDate.dayOfMonth}日"
    }
    return "$day $hm"
}

fun formatRunTime(millis: Long?): String {
    if (millis == null || millis <= 0L) return ""
    return dateTimeFormat.format(Date(millis))
}
