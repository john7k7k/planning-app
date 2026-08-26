package com.reverseplan.app.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.math.BigDecimal
import java.util.UUID

enum class RepeatType { NONE, DAILY, WEEKLY, MONTHLY, INTERVAL }
enum class TaskPriority { NONE, BLUE, YELLOW, ORANGE, RED }
enum class PrerequisiteMode { ALL, ANY }
enum class TaskStatus { NOT_STARTED, IN_PROGRESS, COMPLETED, SETTLED, LOCKED, EXPIRED }
enum class LimitType { UNLIMITED, TOTAL, DAILY, WEEKLY, MONTHLY }
enum class TransactionType { TASK_REWARD, SHOP_PURCHASE, MANUAL_ADJUSTMENT }
enum class PuzzleType { SINGLE_DAY, MULTI_DAY }

@Entity(tableName = "schedules")
data class ScheduleEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val name: String,
    val isSample: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "schedule_settings")
data class ScheduleSettingsEntity(
    @PrimaryKey val id: Int = 1,
    val activeScheduleId: String = "default",
    /** Prevents a deleted example itinerary from being recreated on later launches. */
    val exampleSchedulesInitialized: Boolean = false
)

@Entity(tableName = "daily_summaries", indices = [Index(value = ["scheduleId", "date"], unique = true)])
data class DailySummaryEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val scheduleId: String,
    /** ISO-8601 date, YYYY-MM-DD. */
    val date: String,
    val content: String,
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "schedule_puzzles", indices = [Index(value = ["scheduleId"])])
data class SchedulePuzzleEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val scheduleId: String,
    val name: String,
    val description: String = "",
    val startDate: String,
    val endDate: String,
    /** JSON array of simple task entries used only when the puzzle is applied. */
    val entriesJson: String = "[]",
    val puzzleType: PuzzleType = PuzzleType.SINGLE_DAY,
    val durationDays: Int = 1,
    val durationHours: Int = 0,
    val durationMinutes: Int = 0,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "tasks", indices = [Index(value = ["scheduleId", "active"])])
data class TaskEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val name: String,
    val description: String = "",
    val locationName: String = "",
    val address: String = "",
    val startDate: String,
    val startTime: String = "",
    val endTime: String = "",
    val allDay: Boolean = false,
    val repeatType: RepeatType = RepeatType.NONE,
    /** CSV format such as days=1,3,5;interval=2;monthDay=1 */
    val repeatConfig: String = "",
    /** Optional inclusive end date for a repeating task, in yyyy-MM-dd. */
    val repeatEndDate: String = "",
    /** A persistent category chosen from task_categories. */
    val categoryId: String = "other",
    val priority: TaskPriority = TaskPriority.NONE,
    /** One checklist entry per line. */
    val checklist: String = "",
    /** Backing task for an event created directly on the timeline. */
    val timelineOnly: Boolean = false,
    val scheduleId: String = "default",
    /** Links a timeline-only task to the puzzle that created it. */
    val puzzleId: String? = null,
    val puzzleEntryId: String? = null,
    val puzzleBaseAt: Long? = null,
    val rewardCoins: BigDecimal = BigDecimal.ZERO,
    val rewardDiamonds: BigDecimal = BigDecimal.ZERO,
    val prerequisiteMode: PrerequisiteMode = PrerequisiteMode.ALL,
    val active: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "task_instances", indices = [Index(value = ["taskId", "scheduledDate"], unique = true), Index(value = ["scheduledDate"])])
data class TaskInstanceEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val taskId: String,
    val scheduledDate: String,
    val completionPercentage: BigDecimal = BigDecimal.ZERO,
    val result: String = "",
    val status: TaskStatus = TaskStatus.NOT_STARTED,
    val settled: Boolean = false,
    val earnedCoins: BigDecimal = BigDecimal.ZERO,
    val earnedDiamonds: BigDecimal = BigDecimal.ZERO,
    val settledAt: Long? = null,
    val startTimeOverride: String? = null,
    val endTimeOverride: String? = null,
    val nameOverride: String? = null,
    val descriptionOverride: String? = null,
    val locationOverride: String? = null,
    val addressOverride: String? = null,
    val allDayOverride: Boolean? = null,
    val rewardCoinsOverride: BigDecimal? = null,
    val rewardDiamondsOverride: BigDecimal? = null,
    val categoryIdOverride: String? = null,
    val priorityOverride: TaskPriority? = null,
    val checklistOverride: String? = null,
    /** CSV of checked checklist indices for this scheduled instance. */
    val checkedChecklistItems: String = "",
    /** A removed occurrence is retained so repeating tasks do not recreate it. */
    val deleted: Boolean = false
)

@Entity(tableName = "task_categories")
data class TaskCategoryEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val name: String,
    val icon: String = "🏷️",
    val scheduleId: String = "default",
    val isPreset: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "task_prerequisites", indices = [Index(value = ["taskId"]), Index(value = ["prerequisiteTaskId"])])
data class TaskPrerequisiteEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val taskId: String,
    val prerequisiteTaskId: String,
    val minimumCompletionPercentage: BigDecimal = BigDecimal("100")
)

@Entity(tableName = "shop_items")
data class ShopItemEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val name: String,
    val description: String = "",
    val imageUri: String = "",
    val emoji: String = "🎁",
    val coinPrice: BigDecimal = BigDecimal.ZERO,
    val diamondPrice: BigDecimal = BigDecimal.ZERO,
    val prerequisiteMode: PrerequisiteMode = PrerequisiteMode.ALL,
    val active: Boolean = true,
    val scheduleId: String = "default",
    val limitType: LimitType = LimitType.UNLIMITED,
    val limitCount: Int = 0,
    /** Smaller values are displayed earlier in the reward shop. */
    val sortOrder: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "shop_item_prerequisites", indices = [Index(value = ["shopItemId"])])
data class ShopItemPrerequisiteEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val shopItemId: String,
    val prerequisiteTaskId: String,
    val minimumCompletionPercentage: BigDecimal = BigDecimal("100")
)

@Entity(tableName = "wallet")
data class WalletEntity(
    @PrimaryKey val id: Int = 1,
    val coins: BigDecimal = BigDecimal.ZERO,
    val diamonds: BigDecimal = BigDecimal.ZERO,
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "transactions", indices = [Index(value = ["createdAt"])])
data class TransactionEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val type: TransactionType,
    val coinChange: BigDecimal,
    val diamondChange: BigDecimal,
    val coinsBefore: BigDecimal,
    val coinsAfter: BigDecimal,
    val diamondsBefore: BigDecimal,
    val diamondsAfter: BigDecimal,
    val relatedTaskInstanceId: String? = null,
    val relatedShopItemId: String? = null,
    val scheduleId: String = "default",
    val createdAt: Long = System.currentTimeMillis(),
    val note: String = ""
)

@Entity(tableName = "shop_exchanges", indices = [Index(value = ["shopItemId", "exchangedAt"])])
data class ShopExchangeEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val shopItemId: String,
    val coinCost: BigDecimal,
    val diamondCost: BigDecimal,
    val exchangedAt: Long = System.currentTimeMillis(),
    /** Optional real-world date/time at which the redeemed reward is planned. */
    val scheduledAt: Long? = null,
    val scheduledEndAt: Long? = null,
    val note: String = ""
)
