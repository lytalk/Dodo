package com.lytalk.dodo.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "tasks")
data class TaskEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val enabled: Boolean,
    val scheduleType: String,
    val hour: Int,
    val minute: Int,
    val daysMask: Int,
    val onceAtMillis: Long?,
    val lastRunAt: Long?,
    val lastResult: String?,
    val webhookUrl: String = "",
    val retryCount: Int = 0,
    val cleanupType: String = "NONE",
    val cleanupStepType: String = "",
    val cleanupPayload: String = "",
    val nextRunAt: Long? = null,
)
