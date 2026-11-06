package com.lytalk.dodo.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.lytalk.dodo.data.AppDatabase
import com.lytalk.dodo.data.CleanupTypes
import com.lytalk.dodo.data.ScheduleCalculator
import com.lytalk.dodo.data.StepEntity
import com.lytalk.dodo.data.StepPayloads
import com.lytalk.dodo.data.StepTypes
import com.lytalk.dodo.data.TaskEntity
import com.lytalk.dodo.data.dayBit
import com.lytalk.dodo.schedule.AlarmScheduler
import com.lytalk.dodo.schedule.KeepAliveService
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.TimeZone

data class StepDraft(
    val key: Long,
    val type: String,
    val payload: String,
)

class TaskEditViewModel(
    app: Application,
    private val taskId: Long,
) : AndroidViewModel(app) {
    private val dao = AppDatabase.get(app).taskDao()
    private var previous: TaskEntity? = null

    var name by mutableStateOf("")
    var enabled by mutableStateOf(true)
    var scheduleType by mutableStateOf(ScheduleCalculator.DAILY)
    var hour by mutableStateOf(8)
    var minute by mutableStateOf(0)
    var daysMask by mutableStateOf(WEEKDAY_MASK)
    var onceAtMillis by mutableStateOf(ScheduleCalculator.nextClock(8, 0))
    private var onceDateManual = false
    private var savedHour = 8
    private var savedMinute = 0
    var nextRunAt by mutableStateOf<Long?>(null)
    var webhookUrl by mutableStateOf("")
    var retryCount by mutableStateOf(0)
    var cleanupType by mutableStateOf(CleanupTypes.NONE)
    var cleanupStepType by mutableStateOf("")
    var cleanupPayload by mutableStateOf("")
    var editingCleanup by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var loaded by mutableStateOf(taskId == 0L)
    var saving by mutableStateOf(false)
    var notice by mutableStateOf<String?>(null)
    val steps = mutableStateListOf<StepDraft>()

    init {
        if (taskId != 0L) {
            viewModelScope.launch {
                val task = dao.get(taskId)
                if (task != null) {
                    previous = task
                    name = task.name
                    enabled = task.enabled
                    scheduleType = task.scheduleType
                    hour = task.hour
                    minute = task.minute
                    daysMask = task.daysMask
                    onceDateManual = task.scheduleType == ScheduleCalculator.ONCE
                    if (task.scheduleType != ScheduleCalculator.INTERVAL_MINUTE &&
                        task.scheduleType != ScheduleCalculator.INTERVAL_HOUR
                    ) {
                        savedHour = task.hour
                        savedMinute = task.minute
                    }
                    onceAtMillis = task.onceAtMillis ?: ScheduleCalculator.nextClock(task.hour, task.minute)
                    nextRunAt = task.nextRunAt
                    webhookUrl = task.webhookUrl
                    retryCount = task.retryCount
                    cleanupType = task.cleanupType
                    cleanupStepType = task.cleanupStepType
                    cleanupPayload = task.cleanupPayload
                    steps.clear()
                    steps += dao.stepsFor(taskId).map { StepDraft(it.id, it.type, it.payload) }
                }
                loaded = true
            }
        }
    }

    fun setTime(newHour: Int, newMinute: Int) {
        hour = newHour
        minute = newMinute
        if (scheduleType != ScheduleCalculator.ONCE) return
        onceAtMillis = if (onceDateManual) {
            ScheduleCalculator.withClock(onceAtMillis, newHour, newMinute)
        } else {
            ScheduleCalculator.nextClock(newHour, newMinute)
        }
    }

    fun chooseSchedule(type: String) {
        val leavingInterval = scheduleType == ScheduleCalculator.INTERVAL_MINUTE ||
            scheduleType == ScheduleCalculator.INTERVAL_HOUR
        val enteringInterval = type == ScheduleCalculator.INTERVAL_MINUTE ||
            type == ScheduleCalculator.INTERVAL_HOUR
        if (!leavingInterval && enteringInterval) {
            savedHour = hour
            savedMinute = minute
        }
        if (enteringInterval) nextRunAt = null
        scheduleType = type
        when (type) {
            ScheduleCalculator.INTERVAL_MINUTE -> if (minute !in MINUTE_INTERVALS) minute = 15
            ScheduleCalculator.INTERVAL_HOUR -> if (hour !in HOUR_INTERVALS) hour = 1
            else -> {
                if (leavingInterval) {
                    hour = savedHour
                    minute = savedMinute
                }
                if (type == ScheduleCalculator.ONCE && !onceDateManual) {
                    onceAtMillis = ScheduleCalculator.nextClock(hour, minute)
                }
            }
        }
    }

    fun setOnceDateFromUtc(utcMidnight: Long) {
        val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        utc.timeInMillis = utcMidnight
        val local = Calendar.getInstance()
        local.set(Calendar.YEAR, utc.get(Calendar.YEAR))
        local.set(Calendar.MONTH, utc.get(Calendar.MONTH))
        local.set(Calendar.DAY_OF_MONTH, utc.get(Calendar.DAY_OF_MONTH))
        local.set(Calendar.HOUR_OF_DAY, hour)
        local.set(Calendar.MINUTE, minute)
        local.set(Calendar.SECOND, 0)
        local.set(Calendar.MILLISECOND, 0)
        onceAtMillis = local.timeInMillis
        onceDateManual = true
    }

    fun toggleDay(calendarDay: Int) {
        daysMask = daysMask xor dayBit(calendarDay)
    }

    fun addLaunch(packageName: String, label: String) {
        addStep(StepTypes.LAUNCH_APP, StepPayloads.launch(packageName, label))
    }

    fun addClose(packageName: String, label: String) {
        addStep(StepTypes.CLOSE_APP, StepPayloads.closeApp(packageName, label))
    }

    fun addPicked(viewId: String, text: String, description: String, xFraction: Float, yFraction: Float) {
        addStep(
            StepTypes.CLICK_TARGET,
            StepPayloads.clickTarget(viewId, text, description, xFraction, yFraction),
        )
    }

    fun addStep(type: String, payload: String) {
        if (editingCleanup) {
            cleanupType = CleanupTypes.STEP
            cleanupStepType = type
            cleanupPayload = payload
            editingCleanup = false
            return
        }
        steps += StepDraft(System.nanoTime(), type, payload)
    }

    fun moveStep(index: Int, delta: Int) {
        moveTo(index, index + delta)
    }

    fun moveTo(from: Int, to: Int) {
        if (from !in steps.indices || to !in steps.indices || from == to) return
        val item = steps.removeAt(from)
        steps.add(to, item)
    }

    fun removeStep(index: Int) {
        if (index in steps.indices) steps.removeAt(index)
    }

    fun duplicate() {
        if (saving) return
        val message = validate()
        if (message != null) {
            error = message
            return
        }
        error = null
        saving = true
        viewModelScope.launch {
            try {
                val copyName = "${name.trim()} 副本"
                val entity = TaskEntity(
                    name = copyName,
                    enabled = false,
                    scheduleType = scheduleType,
                    hour = hour,
                    minute = minute,
                    daysMask = daysMask,
                    onceAtMillis = if (scheduleType == ScheduleCalculator.ONCE) onceAtMillis else null,
                    lastRunAt = null,
                    lastResult = null,
                    webhookUrl = webhookUrl.trim(),
                    retryCount = retryCount.coerceIn(0, 5),
                    cleanupType = cleanupType,
                    cleanupStepType = if (cleanupType == CleanupTypes.STEP) cleanupStepType else "",
                    cleanupPayload = if (cleanupType == CleanupTypes.STEP) cleanupPayload else "",
                )
                val id = dao.insert(entity)
                dao.replaceSteps(
                    id,
                    steps.mapIndexed { index, step ->
                        StepEntity(
                            taskId = id,
                            position = index,
                            type = step.type,
                            payload = step.payload,
                        )
                    },
                )
                notice = "已复制为「$copyName」，默认关闭，可在列表里打开"
            } finally {
                saving = false
            }
        }
    }

    fun save(onDone: () -> Unit) {
        if (saving) return
        val message = validate()
        if (message != null) {
            error = message
            return
        }
        error = null
        saving = true
        viewModelScope.launch {
            try {
                val once = if (scheduleType == ScheduleCalculator.ONCE) onceAtMillis else null
                val entity = TaskEntity(
                    id = taskId,
                    name = name.trim(),
                    enabled = enabled,
                    scheduleType = scheduleType,
                    hour = hour,
                    minute = minute,
                    daysMask = daysMask,
                    onceAtMillis = once,
                    webhookUrl = webhookUrl.trim(),
                    retryCount = retryCount.coerceIn(0, 5),
                    cleanupType = cleanupType,
                    cleanupStepType = if (cleanupType == CleanupTypes.STEP) cleanupStepType else "",
                    cleanupPayload = if (cleanupType == CleanupTypes.STEP) cleanupPayload else "",
                    lastRunAt = previous?.lastRunAt,
                    lastResult = previous?.lastResult,
                    nextRunAt = nextRunAt,
                )
                val id = if (taskId == 0L) dao.insert(entity) else {
                    dao.update(entity)
                    taskId
                }
                dao.replaceSteps(
                    id,
                    steps.mapIndexed { index, step ->
                        StepEntity(
                            taskId = id,
                            position = index,
                            type = step.type,
                            payload = step.payload,
                        )
                    },
                )
                val saved = dao.get(id) ?: return@launch
                val app = getApplication<Application>()
                if (saved.enabled) {
                    AlarmScheduler.scheduleNext(app, saved, resetInterval = true)
                    KeepAliveService.start(app)
                } else {
                    AlarmScheduler.cancel(app, id)
                    if (dao.countEnabled() == 0) KeepAliveService.stop(app)
                }
                onDone()
            } finally {
                saving = false
            }
        }
    }

    fun delete(onDone: () -> Unit) {
        if (taskId == 0L) {
            onDone()
            return
        }
        viewModelScope.launch {
            val app = getApplication<Application>()
            AlarmScheduler.cancel(app, taskId)
            dao.deleteById(taskId)
            if (dao.countEnabled() == 0) KeepAliveService.stop(app)
            onDone()
        }
    }

    private fun validate(): String? {
        if (name.isBlank()) return "请填写任务名称"
        if (steps.isEmpty()) return "请至少添加一个步骤"
        if (scheduleType == ScheduleCalculator.WEEKLY && daysMask == 0) return "请至少选择一个星期"
        if (cleanupType == CleanupTypes.STEP && cleanupStepType.isBlank()) return "请设置兜底步骤，或改成其他清理方式"
        if (enabled && scheduleType == ScheduleCalculator.ONCE && onceAtMillis <= System.currentTimeMillis()) {
            return "执行一次的时间已经过了，请改时间或关闭启用"
        }
        val hook = webhookUrl.trim()
        if (hook.isNotEmpty() && !hook.startsWith("http://") && !hook.startsWith("https://")) {
            return "企业微信机器人地址需要以 http:// 或 https:// 开头"
        }
        return null
    }

    class Factory(
        private val app: Application,
        private val taskId: Long,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return TaskEditViewModel(app, taskId) as T
        }
    }

    private companion object {
        val WEEKDAY_MASK: Int = listOf(
            Calendar.MONDAY,
            Calendar.TUESDAY,
            Calendar.WEDNESDAY,
            Calendar.THURSDAY,
            Calendar.FRIDAY,
        ).fold(0) { mask, day -> mask or dayBit(day) }

        val MINUTE_INTERVALS = setOf(1, 5, 10, 15, 30)
        val HOUR_INTERVALS = setOf(1, 2, 3, 6, 12)
    }
}
