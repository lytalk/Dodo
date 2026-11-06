package com.lytalk.dodo.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [TaskEntity::class, StepEntity::class],
    version = 6,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun taskDao(): TaskDao

    companion object {
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tasks ADD COLUMN webhookUrl TEXT NOT NULL DEFAULT ''")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tasks ADD COLUMN retryCount INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE tasks ADD COLUMN cleanupType TEXT NOT NULL DEFAULT 'NONE'")
                db.execSQL("ALTER TABLE tasks ADD COLUMN cleanupStepType TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE tasks ADD COLUMN cleanupPayload TEXT NOT NULL DEFAULT ''")
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tasks ADD COLUMN intervalSecond INTEGER NOT NULL DEFAULT 30")
                db.execSQL("ALTER TABLE tasks ADD COLUMN intervalMinute INTEGER NOT NULL DEFAULT 15")
                db.execSQL("ALTER TABLE tasks ADD COLUMN intervalHour INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE tasks ADD COLUMN loopCount INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE tasks ADD COLUMN rangeStartMillis INTEGER")
                db.execSQL("ALTER TABLE tasks ADD COLUMN rangeEndMillis INTEGER")
                db.execSQL("ALTER TABLE tasks ADD COLUMN windowStartMinute INTEGER")
                db.execSQL("ALTER TABLE tasks ADD COLUMN windowEndMinute INTEGER")
                db.execSQL(
                    "UPDATE tasks SET intervalMinute = CASE WHEN minute < 1 THEN 15 ELSE minute END " +
                        "WHERE scheduleType = 'INTERVAL_MINUTE'",
                )
                db.execSQL(
                    "UPDATE tasks SET intervalHour = CASE WHEN hour < 1 THEN 1 ELSE hour END " +
                        "WHERE scheduleType = 'INTERVAL_HOUR'",
                )
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "UPDATE tasks SET minute = CASE WHEN intervalMinute < 1 THEN 1 WHEN intervalMinute > 59 THEN 59 ELSE intervalMinute END " +
                        "WHERE scheduleType = 'INTERVAL_MINUTE'",
                )
                db.execSQL(
                    "UPDATE tasks SET hour = CASE WHEN intervalHour < 1 THEN 1 WHEN intervalHour > 24 THEN 24 ELSE intervalHour END " +
                        "WHERE scheduleType = 'INTERVAL_HOUR'",
                )
                db.execSQL(
                    "UPDATE tasks SET scheduleType = 'INTERVAL_MINUTE', " +
                        "minute = CASE WHEN intervalSecond < 60 THEN 1 WHEN intervalSecond / 60 > 59 THEN 59 ELSE intervalSecond / 60 END " +
                        "WHERE scheduleType = 'INTERVAL_SECOND'",
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `tasks_new` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `name` TEXT NOT NULL,
                        `enabled` INTEGER NOT NULL,
                        `scheduleType` TEXT NOT NULL,
                        `hour` INTEGER NOT NULL,
                        `minute` INTEGER NOT NULL,
                        `daysMask` INTEGER NOT NULL,
                        `onceAtMillis` INTEGER,
                        `lastRunAt` INTEGER,
                        `lastResult` TEXT,
                        `webhookUrl` TEXT NOT NULL DEFAULT '',
                        `retryCount` INTEGER NOT NULL DEFAULT 0,
                        `cleanupType` TEXT NOT NULL DEFAULT 'NONE',
                        `cleanupStepType` TEXT NOT NULL DEFAULT '',
                        `cleanupPayload` TEXT NOT NULL DEFAULT ''
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    INSERT INTO tasks_new (
                        id, name, enabled, scheduleType, hour, minute, daysMask, onceAtMillis,
                        lastRunAt, lastResult, webhookUrl, retryCount, cleanupType, cleanupStepType, cleanupPayload
                    )
                    SELECT
                        id, name, enabled, scheduleType, hour, minute, daysMask, onceAtMillis,
                        lastRunAt, lastResult, webhookUrl, retryCount, cleanupType, cleanupStepType, cleanupPayload
                    FROM tasks
                    """.trimIndent(),
                )
                db.execSQL("DROP TABLE tasks")
                db.execSQL("ALTER TABLE tasks_new RENAME TO tasks")
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tasks ADD COLUMN nextRunAt INTEGER")
            }
        }

        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "dodo.db",
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6).build().also { instance = it }
            }
        }
    }
}
