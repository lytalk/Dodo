package com.lytalk.dodo.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface TaskDao {
    @Query("SELECT * FROM tasks ORDER BY enabled DESC, id DESC")
    fun observeAll(): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks")
    suspend fun all(): List<TaskEntity>

    @Query("SELECT * FROM tasks WHERE id = :id")
    suspend fun get(id: Long): TaskEntity?

    @Query("SELECT COUNT(*) FROM tasks WHERE enabled = 1")
    suspend fun countEnabled(): Int

    @Insert
    suspend fun insert(task: TaskEntity): Long

    @Update
    suspend fun update(task: TaskEntity)

    @Query("DELETE FROM tasks WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT * FROM steps WHERE taskId = :taskId ORDER BY position ASC")
    suspend fun stepsFor(taskId: Long): List<StepEntity>

    @Insert
    suspend fun insertSteps(steps: List<StepEntity>)

    @Query("DELETE FROM steps WHERE taskId = :taskId")
    suspend fun deleteSteps(taskId: Long)

    @Transaction
    suspend fun replaceSteps(taskId: Long, steps: List<StepEntity>) {
        deleteSteps(taskId)
        if (steps.isNotEmpty()) insertSteps(steps)
    }
}
