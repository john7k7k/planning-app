package com.reverseplan.app.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface TaskDao {
    @Query("SELECT * FROM tasks WHERE scheduleId = :scheduleId AND timelineOnly = 0 ORDER BY active DESC, createdAt DESC") fun observeTasks(scheduleId: String): Flow<List<TaskEntity>>
    @Query("SELECT * FROM tasks WHERE scheduleId = :scheduleId AND active = 1 ORDER BY createdAt ASC") suspend fun activeTasks(scheduleId: String): List<TaskEntity>
    @Query("SELECT * FROM tasks WHERE scheduleId = :scheduleId") suspend fun tasksForSchedule(scheduleId: String): List<TaskEntity>
    @Query("SELECT * FROM tasks WHERE puzzleId = :puzzleId") suspend fun tasksForPuzzle(puzzleId: String): List<TaskEntity>
    @Query("SELECT * FROM tasks WHERE id = :id") suspend fun task(id: String): TaskEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(task: TaskEntity)
    @Delete suspend fun delete(task: TaskEntity)
    @Query("DELETE FROM task_prerequisites WHERE taskId = :taskId") suspend fun clearPrerequisites(taskId: String)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertPrerequisites(items: List<TaskPrerequisiteEntity>)
    @Query("SELECT * FROM task_prerequisites WHERE taskId = :taskId") suspend fun prerequisites(taskId: String): List<TaskPrerequisiteEntity>
    @Query("SELECT * FROM task_prerequisites") suspend fun allPrerequisites(): List<TaskPrerequisiteEntity>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertInstance(instance: TaskInstanceEntity)
    @Query("SELECT * FROM task_instances WHERE taskId = :taskId AND scheduledDate = :date LIMIT 1") suspend fun instance(taskId: String, date: String): TaskInstanceEntity?
    @Query("SELECT * FROM task_instances WHERE id = :id LIMIT 1") suspend fun instanceById(id: String): TaskInstanceEntity?
    @Query("SELECT * FROM task_instances WHERE scheduledDate = :date") suspend fun instancesForDate(date: String): List<TaskInstanceEntity>
    @Query("SELECT * FROM task_instances WHERE scheduledDate >= :startDate AND scheduledDate <= :endDate") suspend fun instancesBetween(startDate: String, endDate: String): List<TaskInstanceEntity>
    @Query("SELECT * FROM task_instances WHERE taskId = :taskId ORDER BY scheduledDate DESC LIMIT 1") suspend fun lastInstance(taskId: String): TaskInstanceEntity?
    @Query("SELECT * FROM task_instances WHERE taskId = :taskId") suspend fun instancesForTask(taskId: String): List<TaskInstanceEntity>
    @Query("SELECT * FROM task_instances ORDER BY scheduledDate DESC") fun observeInstances(): Flow<List<TaskInstanceEntity>>
    @Update suspend fun updateInstance(instance: TaskInstanceEntity)
    @Query("DELETE FROM task_instances WHERE taskId = :taskId") suspend fun deleteInstancesForTask(taskId: String)
    @Query("DELETE FROM tasks WHERE scheduleId = :scheduleId") suspend fun deleteTasksForSchedule(scheduleId: String)
    @Query("UPDATE tasks SET categoryId = :replacementId WHERE scheduleId = :scheduleId AND categoryId = :categoryId") suspend fun replaceTaskCategory(scheduleId: String, categoryId: String, replacementId: String)
    @Query("UPDATE task_instances SET categoryIdOverride = :replacementId WHERE categoryIdOverride = :categoryId") suspend fun replaceInstanceCategory(categoryId: String, replacementId: String)
}

@Dao
interface TaskCategoryDao {
    @Query("SELECT * FROM task_categories WHERE scheduleId = :scheduleId ORDER BY isPreset DESC, createdAt ASC") fun observeCategories(scheduleId: String): Flow<List<TaskCategoryEntity>>
    @Query("SELECT * FROM task_categories WHERE scheduleId = :scheduleId") suspend fun categories(scheduleId: String): List<TaskCategoryEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(category: TaskCategoryEntity)
    @Delete suspend fun delete(category: TaskCategoryEntity)
    @Query("DELETE FROM task_categories WHERE scheduleId = :scheduleId") suspend fun deleteCategoriesForSchedule(scheduleId: String)
}

@Dao
interface ScheduleDao {
    @Query("SELECT * FROM schedules ORDER BY isSample ASC, createdAt ASC") fun observeSchedules(): Flow<List<ScheduleEntity>>
    @Query("SELECT * FROM schedules") suspend fun schedules(): List<ScheduleEntity>
    @Query("SELECT * FROM schedules WHERE id = :id") suspend fun schedule(id: String): ScheduleEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(schedule: ScheduleEntity)
    @Delete suspend fun delete(schedule: ScheduleEntity)
    @Query("SELECT * FROM schedule_settings WHERE id = 1") fun observeSettings(): Flow<ScheduleSettingsEntity?>
    @Query("SELECT * FROM schedule_settings WHERE id = 1") suspend fun settings(): ScheduleSettingsEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveSettings(settings: ScheduleSettingsEntity)
    @Query("SELECT * FROM schedule_puzzles ORDER BY createdAt DESC") fun observePuzzles(): Flow<List<SchedulePuzzleEntity>>
    @Query("SELECT * FROM schedule_puzzles ORDER BY createdAt DESC") suspend fun allPuzzles(): List<SchedulePuzzleEntity>
    @Query("SELECT * FROM schedule_puzzles WHERE id = :id") suspend fun puzzle(id: String): SchedulePuzzleEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertPuzzle(puzzle: SchedulePuzzleEntity)
    @Delete suspend fun deletePuzzle(puzzle: SchedulePuzzleEntity)
    @Query("DELETE FROM schedule_puzzles WHERE scheduleId = :scheduleId") suspend fun deletePuzzlesForSchedule(scheduleId: String)
    @Query("SELECT * FROM daily_summaries WHERE scheduleId = :scheduleId ORDER BY date DESC") fun observeDailySummaries(scheduleId: String): Flow<List<DailySummaryEntity>>
    @Query("SELECT * FROM daily_summaries WHERE scheduleId = :scheduleId ORDER BY date DESC") suspend fun dailySummaries(scheduleId: String): List<DailySummaryEntity>
    @Query("SELECT * FROM daily_summaries WHERE scheduleId = :scheduleId AND date = :date LIMIT 1") suspend fun dailySummary(scheduleId: String, date: String): DailySummaryEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertDailySummary(summary: DailySummaryEntity)
    @Query("DELETE FROM daily_summaries WHERE scheduleId = :scheduleId AND date = :date") suspend fun deleteDailySummary(scheduleId: String, date: String)
    @Query("DELETE FROM daily_summaries WHERE scheduleId = :scheduleId") suspend fun deleteDailySummariesForSchedule(scheduleId: String)
}

@Dao
interface ShopDao {
    @Query("SELECT * FROM shop_items WHERE scheduleId = :scheduleId ORDER BY active DESC, sortOrder ASC, diamondPrice ASC, coinPrice ASC, createdAt DESC") fun observeItems(scheduleId: String): Flow<List<ShopItemEntity>>
    @Query("SELECT * FROM shop_items WHERE id = :id") suspend fun item(id: String): ShopItemEntity?
    @Query("SELECT * FROM shop_items WHERE scheduleId = :scheduleId ORDER BY active DESC, sortOrder ASC, diamondPrice ASC, coinPrice ASC, createdAt DESC") suspend fun allItems(scheduleId: String): List<ShopItemEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(item: ShopItemEntity)
    @Delete suspend fun delete(item: ShopItemEntity)
    @Query("DELETE FROM shop_items WHERE scheduleId = :scheduleId") suspend fun deleteItemsForSchedule(scheduleId: String)
    @Query("DELETE FROM shop_item_prerequisites WHERE shopItemId = :itemId") suspend fun clearPrerequisites(itemId: String)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertPrerequisites(items: List<ShopItemPrerequisiteEntity>)
    @Query("SELECT * FROM shop_item_prerequisites WHERE shopItemId = :itemId") suspend fun prerequisites(itemId: String): List<ShopItemPrerequisiteEntity>
    @Insert suspend fun insertExchange(exchange: ShopExchangeEntity)
    @Query("SELECT * FROM shop_exchanges WHERE id = :id") suspend fun exchange(id: String): ShopExchangeEntity?
    @Update suspend fun updateExchange(exchange: ShopExchangeEntity)
    @Delete suspend fun deleteExchange(exchange: ShopExchangeEntity)
    @Query("SELECT COUNT(*) FROM shop_exchanges WHERE shopItemId = :itemId AND exchangedAt >= :from") suspend fun exchangeCount(itemId: String, from: Long): Int
    @Query("SELECT * FROM shop_exchanges ORDER BY exchangedAt DESC") fun observeExchanges(): Flow<List<ShopExchangeEntity>>
    @Query("SELECT * FROM shop_exchanges WHERE scheduledAt IS NOT NULL AND scheduledAt < :end AND COALESCE(scheduledEndAt, scheduledAt) > :start") suspend fun overlappingSchedules(start: Long, end: Long): List<ShopExchangeEntity>
    @Query("SELECT * FROM shop_exchanges WHERE scheduledAt IS NOT NULL AND scheduledAt >= :start AND scheduledAt < :end ORDER BY scheduledAt") suspend fun schedulesForDay(start: Long, end: Long): List<ShopExchangeEntity>
}

@Dao
interface WalletDao {
    @Query("SELECT * FROM wallet WHERE id = 1") fun observeWallet(): Flow<WalletEntity?>
    @Query("SELECT * FROM wallet WHERE id = 1") suspend fun wallet(): WalletEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun save(wallet: WalletEntity)
    @Insert suspend fun transaction(item: TransactionEntity)
    @Query("DELETE FROM transactions WHERE scheduleId = :scheduleId") suspend fun deleteTransactionsForSchedule(scheduleId: String)
    @Query("SELECT * FROM transactions WHERE scheduleId = :scheduleId ORDER BY createdAt DESC") fun observeTransactions(scheduleId: String): Flow<List<TransactionEntity>>
}
