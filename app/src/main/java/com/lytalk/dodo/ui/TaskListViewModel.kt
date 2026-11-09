package com.lytalk.dodo.ui

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lytalk.dodo.data.AppDatabase
import com.lytalk.dodo.data.StepEntity
import com.lytalk.dodo.data.TaskEntity
import com.lytalk.dodo.data.TaskTransfer
import com.lytalk.dodo.schedule.AlarmScheduler
import com.lytalk.dodo.schedule.KeepAliveService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TaskListViewModel(app: Application) : AndroidViewModel(app) {
    private val dao = AppDatabase.get(app).taskDao()

    var message by mutableStateOf<String?>(null)

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

    fun exportTo(uri: Uri) {
        viewModelScope.launch {
            try {
                val app = getApplication<Application>()
                val json = withContext(Dispatchers.IO) {
                    val all = dao.all()
                    val steps = all.associate { task -> task.id to dao.stepsFor(task.id) }
                    TaskTransfer.exportJson(all, steps)
                }
                val written = withContext(Dispatchers.IO) {
                    app.contentResolver.openOutputStream(uri)?.use { stream ->
                        stream.write(json.toByteArray(Charsets.UTF_8))
                        true
                    } ?: false
                }
                message = if (written) "已导出 ${dao.all().size} 个任务" else "无法写入文件"
            } catch (error: Exception) {
                message = error.message ?: "导出失败"
            }
        }
    }

    fun importFrom(uri: Uri) {
        viewModelScope.launch {
            try {
                val app = getApplication<Application>()
                val text = withContext(Dispatchers.IO) {
                    app.contentResolver.openInputStream(uri)?.use { stream ->
                        val bytes = stream.readBytes()
                        if (bytes.size > 2_000_000) error("文件太大")
                        bytes.toString(Charsets.UTF_8)
                    } ?: error("无法读取文件")
                }
                val imported = TaskTransfer.parse(text)
                var enabled = false
                imported.forEach { item ->
                    val id = dao.insert(item.task)
                    dao.replaceSteps(
                        id,
                        item.steps.mapIndexed { index, (type, payload) ->
                            StepEntity(taskId = id, position = index, type = type, payload = payload)
                        },
                    )
                    val saved = dao.get(id) ?: return@forEach
                    if (saved.enabled) {
                        enabled = true
                        AlarmScheduler.scheduleNext(app, saved, resetInterval = true)
                    }
                }
                if (enabled) KeepAliveService.start(app)
                message = "已导入 ${imported.size} 个任务"
            } catch (error: Exception) {
                message = error.message ?: "导入失败"
            }
        }
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
