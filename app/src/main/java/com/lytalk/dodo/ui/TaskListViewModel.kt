package com.lytalk.dodo.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lytalk.dodo.data.AppDatabase
import com.lytalk.dodo.data.TaskEntity
import com.lytalk.dodo.schedule.AlarmScheduler
import com.lytalk.dodo.schedule.KeepAliveService
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class TaskListViewModel(app: Application) : AndroidViewModel(app) {
    private val dao = AppDatabase.get(app).taskDao()

    val tasks = dao.observeAll().stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        emptyList(),
    )

    fun ensureGuard() {
        viewModelScope.launch {
            if (dao.countEnabled() > 0) {
                KeepAliveService.start(getApplication())
            }
        }
    }

    fun setEnabled(task: TaskEntity, enabled: Boolean) {
        viewModelScope.launch {
            val updated = task.copy(enabled = enabled)
            dao.update(updated)
            val app = getApplication<Application>()
            if (enabled) {
                AlarmScheduler.scheduleNext(app, updated, resetInterval = true)
                KeepAliveService.start(app)
            } else {
                AlarmScheduler.cancel(app, task.id)
                if (dao.countEnabled() == 0) KeepAliveService.stop(app)
            }
        }
    }

    fun runNow(task: TaskEntity) {
        KeepAliveService.start(getApplication(), runTaskId = task.id, fromAlarm = false)
    }

    fun copy(task: TaskEntity) {
        viewModelScope.launch {
            val steps = dao.stepsFor(task.id)
            val copy = task.copy(
                id = 0,
                name = "${task.name} 副本",
                enabled = false,
                lastRunAt = null,
                lastResult = null,
            )
            val id = dao.insert(copy)
            if (steps.isNotEmpty()) {
                dao.replaceSteps(
                    id,
                    steps.mapIndexed { index, step ->
                        step.copy(id = 0, taskId = id, position = index)
                    },
                )
            }
        }
    }
}
