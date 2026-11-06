package com.lytalk.dodo.schedule

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.lytalk.dodo.data.AppDatabase
import com.lytalk.dodo.data.ScheduleCalculator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val taskId = intent.getLongExtra(AlarmScheduler.EXTRA_TASK_ID, -1L)
        if (taskId < 0L) return
        val pending = goAsync()
        val appContext = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val task = AppDatabase.get(appContext).taskDao().get(taskId)
                if (task != null && task.enabled) {
                    if (task.scheduleType != ScheduleCalculator.ONCE) {
                        AlarmScheduler.scheduleNext(appContext, task)
                    }
                    KeepAliveService.start(appContext, runTaskId = taskId, fromAlarm = true)
                }
            } finally {
                pending.finish()
            }
        }
    }
}
