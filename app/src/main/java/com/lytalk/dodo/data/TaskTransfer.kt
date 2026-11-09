package com.lytalk.dodo.data

import org.json.JSONArray
import org.json.JSONObject

object TaskTransfer {
    private const val VERSION = 1

    private val stepTypes = setOf(
        StepTypes.LAUNCH_APP,
        StepTypes.CLICK_TEXT,
        StepTypes.CLICK_ID,
        StepTypes.CLICK_TARGET,
        StepTypes.CLOSE_APP,
        StepTypes.UNLOCK,
        StepTypes.INPUT_TEXT,
        StepTypes.WAIT,
        StepTypes.WAIT_FOR_TEXT,
        StepTypes.BACK,
    )

    fun exportJson(tasks: List<TaskEntity>, stepsByTask: Map<Long, List<StepEntity>>): String {
        val exported = JSONArray()
        tasks.forEach { task ->
            val steps = JSONArray()
            stepsByTask[task.id].orEmpty().forEach { step ->
                steps.put(
                    JSONObject()
                        .put("type", step.type)
                        .put("payload", step.payload),
                )
            }
            exported.put(taskJson(task).put("steps", steps))
        }
        return JSONObject().put("version", VERSION).put("tasks", exported).toString()
    }

    fun parse(text: String): List<ImportedTask> {
        val root = try {
            JSONObject(text)
        } catch (_: Exception) {
            throw IllegalArgumentException("这个文件不是嘟嘟的任务备份")
        }
        if (root.optInt("version", -1) != VERSION) {
            throw IllegalArgumentException("这份备份版本嘟嘟读不了")
        }
        val tasks = root.optJSONArray("tasks") ?: throw IllegalArgumentException("备份里没有任务")
        return buildList {
            for (index in 0 until tasks.length()) {
                val item = tasks.optJSONObject(index) ?: continue
                val name = item.optString("name").trim()
                if (name.isEmpty()) continue
                val steps = item.optJSONArray("steps")
                val parsedSteps = buildList {
                    if (steps != null) {
                        for (stepIndex in 0 until steps.length()) {
                            val step = steps.optJSONObject(stepIndex) ?: continue
                            val type = step.optString("type")
                            if (type !in stepTypes) continue
                            add(type to step.optString("payload"))
                        }
                    }
                }
                if (parsedSteps.isEmpty()) continue
                add(
                    ImportedTask(
                        task = TaskEntity(
                            name = name,
                            enabled = item.optBoolean("enabled", false),
                            scheduleType = item.optString("scheduleType", ScheduleCalculator.DAILY),
                            hour = item.optInt("hour", 8),
                            minute = item.optInt("minute", 0),
                            daysMask = item.optInt("daysMask", 0),
                            onceAtMillis = item.optNullableLong("onceAtMillis"),
                            lastRunAt = null,
                            lastResult = null,
                            webhookUrl = item.optString("webhookUrl"),
                            retryCount = item.optInt("retryCount", 0).coerceIn(0, 5),
                            cleanupType = item.optString("cleanupType", CleanupTypes.NONE),
                            cleanupStepType = item.optString("cleanupStepType"),
                            cleanupPayload = item.optString("cleanupPayload"),
                            nextRunAt = null,
                        ),
                        steps = parsedSteps,
                    ),
                )
            }
        }.ifEmpty { throw IllegalArgumentException("备份里没有可以导入的任务") }
    }

    private fun taskJson(task: TaskEntity): JSONObject = JSONObject()
        .put("name", task.name)
        .put("enabled", task.enabled)
        .put("scheduleType", task.scheduleType)
        .put("hour", task.hour)
        .put("minute", task.minute)
        .put("daysMask", task.daysMask)
        .put("onceAtMillis", task.onceAtMillis)
        .put("webhookUrl", task.webhookUrl)
        .put("retryCount", task.retryCount)
        .put("cleanupType", task.cleanupType)
        .put("cleanupStepType", task.cleanupStepType)
        .put("cleanupPayload", task.cleanupPayload)

    private fun JSONObject.optNullableLong(name: String): Long? =
        if (isNull(name) || !has(name)) null else optLong(name)
}

data class ImportedTask(
    val task: TaskEntity,
    val steps: List<Pair<String, String>>,
)
