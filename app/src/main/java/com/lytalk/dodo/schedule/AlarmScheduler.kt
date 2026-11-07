package com.lytalk.dodo.schedule

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import com.lytalk.dodo.MainActivity
import com.lytalk.dodo.data.AppDatabase
import com.lytalk.dodo.data.ScheduleCalculator
import com.lytalk.dodo.data.TaskEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object AlarmScheduler {
    const val EXTRA_TASK_ID = "task_id"
    private const val TAG = "Dodo"

    suspend fun rescheduleAll(context: Context) {
        val tasks = AppDatabase.get(context).taskDao().all()
        tasks.forEach { task ->
            if (task.enabled) scheduleNext(context, task) else cancel(context, task.id)
        }
    }

    suspend fun scheduleNext(context: Context, task: TaskEntity, resetInterval: Boolean = false) {
        val armed = rememberInterval(context, task, resetInterval)
        val triggerAt = ScheduleCalculator.nextTrigger(armed)
        if (triggerAt == null) {
            cancel(context, armed.id)
            return
        }
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        if (!alarmManager.canScheduleExactAlarms()) {
            Log.w(TAG, "精确闹钟权限未开启，任务 ${task.id} 未登记")
            return
        }
        try {
            alarmManager.setAlarmClock(
                AlarmManager.AlarmClockInfo(triggerAt, showPending(context)),
                alarmPending(context, armed.id),
            )
            Log.i(TAG, "已登记任务 ${armed.id}，触发时间 $triggerAt")
        } catch (error: SecurityException) {
            Log.e(TAG, "登记定时唤醒失败", error)
        }
    }

    private suspend fun rememberInterval(context: Context, task: TaskEntity, resetInterval: Boolean): TaskEntity {
        val period = ScheduleCalculator.intervalMillis(task) ?: return task
        val now = System.currentTimeMillis()
        val stored = task.nextRunAt
        val next = when {
            !resetInterval && stored != null && stored > now -> stored
            !resetInterval && stored != null -> {
                var upcoming = stored + period
                while (upcoming <= now) upcoming += period
                upcoming
            }
            else -> now + period
        }
        if (next == stored) return task
        val updated = task.copy(nextRunAt = next)
        withContext(Dispatchers.IO) { AppDatabase.get(context).taskDao().update(updated) }
        return updated
    }

    fun cancel(context: Context, taskId: Long) {
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        alarmManager.cancel(alarmPending(context, taskId))
    }

    private fun alarmPending(context: Context, taskId: Long): PendingIntent {
        val intent = Intent(context, AlarmReceiver::class.java).putExtra(EXTRA_TASK_ID, taskId)
        return PendingIntent.getBroadcast(
            context,
            taskId.toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun showPending(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP,
        )
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
