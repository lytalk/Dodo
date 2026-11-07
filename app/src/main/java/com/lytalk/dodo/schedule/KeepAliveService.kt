package com.lytalk.dodo.schedule

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import com.lytalk.dodo.DodoApp
import com.lytalk.dodo.MainActivity
import com.lytalk.dodo.R
import com.lytalk.dodo.a11y.AutoAccessibilityService
import com.lytalk.dodo.a11y.StepExecutor
import com.lytalk.dodo.data.AppDatabase
import com.lytalk.dodo.data.ScheduleCalculator
import com.lytalk.dodo.data.TaskEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

class KeepAliveService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val running = AtomicBoolean(false)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val runTaskId = intent?.getLongExtra(EXTRA_RUN_TASK_ID, -1L) ?: -1L
        val fromAlarm = intent?.getBooleanExtra(EXTRA_FROM_ALARM, false) ?: false
        startForeground(NOTIFICATION_ID, notification("嘟嘟守护中"))
        scope.launch {
            try {
                if (runTaskId <= 0L) {
                    withContext(Dispatchers.IO) { AlarmScheduler.rescheduleAll(this@KeepAliveService) }
                }
                if (runTaskId > 0L) {
                    runTask(runTaskId, fromAlarm)
                }
            } finally {
                val enabled = withContext(Dispatchers.IO) {
                    AppDatabase.get(this@KeepAliveService).taskDao().countEnabled()
                }
                if (enabled == 0 && !running.get()) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                } else if (!running.get()) {
                    notify("嘟嘟守护中，已启用 $enabled 个任务")
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        WakeActivity.finishIfUp()
        super.onDestroy()
    }

    private suspend fun runTask(taskId: Long, fromAlarm: Boolean) {
        if (!running.compareAndSet(false, true)) {
            postponeIfBusy(taskId)
            return
        }
        val power = getSystemService(PowerManager::class.java)
        val wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "dodo:run")
        wakeLock.setReferenceCounted(false)
        wakeLock.acquire(3 * 60 * 1000L)
        @Suppress("DEPRECATION")
        val screenLock = power.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                PowerManager.ACQUIRE_CAUSES_WAKEUP or
                PowerManager.ON_AFTER_RELEASE,
            "dodo:screen",
        )
        screenLock.setReferenceCounted(false)
        screenLock.acquire(3 * 60 * 1000L)
        try {
            val task = withContext(Dispatchers.IO) { AppDatabase.get(this@KeepAliveService).taskDao().get(taskId) } ?: return
            notify("正在执行：${task.name}")
            val result = try {
                ensureAwake()
                val accessibility = AutoAccessibilityService.instance
                    ?: return finishTask(task, fromAlarm, "无障碍服务未开启")
                accessibility.setRetrievalActive(true)
                val steps = withContext(Dispatchers.IO) {
                    AppDatabase.get(this@KeepAliveService).taskDao().stepsFor(taskId)
                }
                try {
                    StepExecutor(accessibility).run(steps, task)
                } finally {
                    accessibility.setRetrievalActive(false)
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                error.message ?: "执行失败"
            }
            finishTask(task, fromAlarm, result)
        } finally {
            WakeActivity.finishIfUp()
            if (wakeLock.isHeld) wakeLock.release()
            if (screenLock.isHeld) screenLock.release()
            running.set(false)
        }
    }

    private suspend fun postponeIfBusy(taskId: Long) {
        val dao = AppDatabase.get(this).taskDao()
        val task = withContext(Dispatchers.IO) { dao.get(taskId) } ?: return
        if (task.scheduleType == ScheduleCalculator.ONCE && task.enabled) {
        val updated = task.copy(
            onceAtMillis = System.currentTimeMillis() + 60_000,
            lastRunAt = System.currentTimeMillis(),
            lastResult = withWebhook(task, "已有任务正在执行，一分钟后重试").take(300),
        )
            withContext(Dispatchers.IO) { dao.update(updated) }
            AlarmScheduler.scheduleNext(this, updated)
        } else {
            writeResult(taskId, "已有任务正在执行", disableOnce = false)
        }
    }

    private suspend fun finishTask(task: TaskEntity, fromAlarm: Boolean, result: String) {
        val disableOnce = fromAlarm && task.scheduleType == ScheduleCalculator.ONCE
        writeResult(task.id, result, disableOnce)
    }

    private suspend fun writeResult(taskId: Long, result: String, disableOnce: Boolean) {
        val dao = AppDatabase.get(this).taskDao()
        val task = withContext(Dispatchers.IO) { dao.get(taskId) } ?: return
        val updated = task.copy(
            enabled = if (disableOnce) false else task.enabled,
            lastRunAt = System.currentTimeMillis(),
            lastResult = withWebhook(task, result).take(300),
        )
        withContext(Dispatchers.IO) { dao.update(updated) }
        if (updated.enabled) {
            AlarmScheduler.scheduleNext(this, updated)
        } else {
            AlarmScheduler.cancel(this, updated.id)
        }
    }

    private suspend fun withWebhook(task: TaskEntity, result: String): String {
        val note = WebhookClient.post(task.webhookUrl, task, result) ?: return result
        return "$result；$note"
    }

    private suspend fun ensureAwake() {
        val power = getSystemService(PowerManager::class.java)
        if (!power.isInteractive) {
            requestScreenOn()
            val deadline = System.currentTimeMillis() + 6_000
            while (!power.isInteractive && System.currentTimeMillis() < deadline) {
                delay(100)
            }
        }
        WakeActivity.finishIfUp()
        delay(500)
    }

    private fun requestScreenOn() {
        val open = Intent(this, WakeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pending = PendingIntent.getActivity(
            this,
            7,
            open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val wakeNotification = Notification.Builder(this, DodoApp.WAKE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("正在点亮屏幕")
            .setCategory(Notification.CATEGORY_ALARM)
            .setFullScreenIntent(pending, true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(WAKE_NOTIFICATION_ID, wakeNotification)
        runCatching { startActivity(open) }
    }

    private fun notify(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification(text))
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this,
            1,
            Intent(this, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP,
            ),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, DodoApp.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(open)
            .build()
    }

    companion object {
        private const val NOTIFICATION_ID = 1001
        private const val WAKE_NOTIFICATION_ID = 1002
        private const val EXTRA_RUN_TASK_ID = "run_task_id"
        private const val EXTRA_FROM_ALARM = "from_alarm"

        fun start(context: Context, runTaskId: Long = -1L, fromAlarm: Boolean = false) {
            val intent = Intent(context, KeepAliveService::class.java)
                .putExtra(EXTRA_RUN_TASK_ID, runTaskId)
                .putExtra(EXTRA_FROM_ALARM, fromAlarm)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, KeepAliveService::class.java))
        }
    }
}
