package com.reverseplan.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import java.math.BigDecimal

class AppConverters {
    @TypeConverter fun fromBigDecimal(value: BigDecimal?): String? = value?.toPlainString()
    @TypeConverter fun toBigDecimal(value: String?): BigDecimal? = value?.let(::BigDecimal)
    @TypeConverter fun fromRepeat(value: RepeatType) = value.name
    @TypeConverter fun toRepeat(value: String) = RepeatType.valueOf(value)
    @TypeConverter fun fromTaskPriority(value: TaskPriority) = value.name
    @TypeConverter fun toTaskPriority(value: String) = TaskPriority.valueOf(value)
    @TypeConverter fun fromPrerequisiteMode(value: PrerequisiteMode) = value.name
    @TypeConverter fun toPrerequisiteMode(value: String) = PrerequisiteMode.valueOf(value)
    @TypeConverter fun fromTaskStatus(value: TaskStatus) = value.name
    @TypeConverter fun toTaskStatus(value: String) = TaskStatus.valueOf(value)
    @TypeConverter fun fromLimit(value: LimitType) = value.name
    @TypeConverter fun toLimit(value: String) = LimitType.valueOf(value)
    @TypeConverter fun fromTransaction(value: TransactionType) = value.name
    @TypeConverter fun toTransaction(value: String) = TransactionType.valueOf(value)
    @TypeConverter fun fromPuzzleType(value: PuzzleType) = value.name
    @TypeConverter fun toPuzzleType(value: String) = runCatching { PuzzleType.valueOf(value) }.getOrDefault(PuzzleType.SINGLE_DAY)
}

@Database(entities = [ScheduleEntity::class, ScheduleSettingsEntity::class, SchedulePuzzleEntity::class, TaskEntity::class, TaskInstanceEntity::class, TaskCategoryEntity::class, TaskPrerequisiteEntity::class, ShopItemEntity::class, ShopItemPrerequisiteEntity::class, WalletEntity::class, TransactionEntity::class, ShopExchangeEntity::class], version = 15, exportSchema = true)
@TypeConverters(AppConverters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun taskDao(): TaskDao
    abstract fun taskCategoryDao(): TaskCategoryDao
    abstract fun scheduleDao(): ScheduleDao
    abstract fun shopDao(): ShopDao
    abstract fun walletDao(): WalletDao
    companion object {
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE shop_exchanges ADD COLUMN scheduledAt INTEGER")
                db.execSQL("ALTER TABLE shop_exchanges ADD COLUMN note TEXT NOT NULL DEFAULT ''")
            }
        }
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE task_instances ADD COLUMN startTimeOverride TEXT")
                db.execSQL("ALTER TABLE task_instances ADD COLUMN endTimeOverride TEXT")
                db.execSQL("ALTER TABLE shop_exchanges ADD COLUMN scheduledEndAt INTEGER")
            }
        }
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE task_instances ADD COLUMN nameOverride TEXT")
                db.execSQL("ALTER TABLE task_instances ADD COLUMN descriptionOverride TEXT")
                db.execSQL("ALTER TABLE task_instances ADD COLUMN locationOverride TEXT")
                db.execSQL("ALTER TABLE task_instances ADD COLUMN addressOverride TEXT")
                db.execSQL("ALTER TABLE task_instances ADD COLUMN allDayOverride INTEGER")
                db.execSQL("ALTER TABLE task_instances ADD COLUMN rewardCoinsOverride TEXT")
                db.execSQL("ALTER TABLE task_instances ADD COLUMN rewardDiamondsOverride TEXT")
            }
        }
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tasks ADD COLUMN repeatEndDate TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE tasks ADD COLUMN categoryId TEXT NOT NULL DEFAULT 'other'")
                db.execSQL("ALTER TABLE tasks ADD COLUMN priority TEXT NOT NULL DEFAULT 'NONE'")
                db.execSQL("ALTER TABLE tasks ADD COLUMN checklist TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE tasks ADD COLUMN timelineOnly INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE task_instances ADD COLUMN checkedChecklistItems TEXT NOT NULL DEFAULT ''")
                db.execSQL("CREATE TABLE IF NOT EXISTS task_categories (id TEXT NOT NULL, name TEXT NOT NULL, icon TEXT NOT NULL, isPreset INTEGER NOT NULL, createdAt INTEGER NOT NULL, PRIMARY KEY(id))")
            }
        }
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE task_instances ADD COLUMN categoryIdOverride TEXT")
                db.execSQL("ALTER TABLE task_instances ADD COLUMN priorityOverride TEXT")
                db.execSQL("ALTER TABLE task_instances ADD COLUMN checklistOverride TEXT")
            }
        }
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE task_instances ADD COLUMN deleted INTEGER NOT NULL DEFAULT 0")
            }
        }
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tasks ADD COLUMN scheduleId TEXT NOT NULL DEFAULT 'default'")
                db.execSQL("ALTER TABLE task_categories ADD COLUMN scheduleId TEXT NOT NULL DEFAULT 'default'")
                db.execSQL("ALTER TABLE shop_items ADD COLUMN scheduleId TEXT NOT NULL DEFAULT 'default'")
                db.execSQL("ALTER TABLE transactions ADD COLUMN scheduleId TEXT NOT NULL DEFAULT 'default'")
                db.execSQL("CREATE TABLE IF NOT EXISTS schedules (id TEXT NOT NULL, name TEXT NOT NULL, isSample INTEGER NOT NULL, createdAt INTEGER NOT NULL, PRIMARY KEY(id))")
                db.execSQL("CREATE TABLE IF NOT EXISTS schedule_settings (id INTEGER NOT NULL, activeScheduleId TEXT NOT NULL, PRIMARY KEY(id))")
                db.execSQL("CREATE TABLE IF NOT EXISTS schedule_puzzles (id TEXT NOT NULL, scheduleId TEXT NOT NULL, name TEXT NOT NULL, startDate TEXT NOT NULL, endDate TEXT NOT NULL, entriesJson TEXT NOT NULL, createdAt INTEGER NOT NULL, PRIMARY KEY(id))")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_schedule_puzzles_scheduleId ON schedule_puzzles(scheduleId)")
            }
        }
        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE schedule_puzzles ADD COLUMN durationDays INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE schedule_puzzles ADD COLUMN durationHours INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE schedule_puzzles ADD COLUMN durationMinutes INTEGER NOT NULL DEFAULT 0")
            }
        }
        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tasks ADD COLUMN puzzleId TEXT")
                db.execSQL("ALTER TABLE tasks ADD COLUMN puzzleEntryId TEXT")
                db.execSQL("ALTER TABLE tasks ADD COLUMN puzzleBaseAt INTEGER")
            }
        }
        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE schedule_puzzles ADD COLUMN description TEXT NOT NULL DEFAULT ''")
            }
        }
        private val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE schedule_settings ADD COLUMN exampleSchedulesInitialized INTEGER NOT NULL DEFAULT 0")
            }
        }
        private val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS index_tasks_scheduleId_active ON tasks(scheduleId, active)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_task_instances_scheduledDate ON task_instances(scheduledDate)")
            }
        }
        private val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE schedule_puzzles ADD COLUMN puzzleType TEXT NOT NULL DEFAULT 'SINGLE_DAY'")
            }
        }
        private val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE shop_items ADD COLUMN sortOrder INTEGER NOT NULL DEFAULT 0")
            }
        }
        fun create(context: Context) = Room.databaseBuilder(context, AppDatabase::class.java, "mission_market.db").addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15).build()
    }
}
