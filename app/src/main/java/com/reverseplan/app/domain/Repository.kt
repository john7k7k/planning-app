package com.reverseplan.app.domain

import androidx.room.withTransaction
import com.reverseplan.app.data.*
import kotlinx.coroutines.flow.Flow
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.util.UUID

data class TaskCardModel(val task: TaskEntity, val instance: TaskInstanceEntity, val unlocked: Boolean, val missing: List<String>)
data class RewardSchedule(val item: ShopItemEntity, val exchange: ShopExchangeEntity)
data class DashboardData(val wallet: WalletEntity, val cards: List<TaskCardModel>, val rewards: List<RewardSchedule>, val earnedCoins: BigDecimal, val earnedDiamonds: BigDecimal)
data class ShopCardModel(val item: ShopItemEntity, val unlocked: Boolean, val reason: String)
data class DailyCompletion(val date: LocalDate, val completion: Float)
data class CategoryCompletion(val categoryId: String, val name: String, val icon: String, val completion: Float, val count: Int)
data class MonthlyStats(
    val month: YearMonth,
    val plannedTasks: Int,
    val settledTasks: Int,
    val completionRatio: Float,
    val weightedCompletionRatio: Float,
    val earnedCoins: BigDecimal,
    val earnedDiamonds: BigDecimal,
    val activeDays: Int,
    val daily: List<DailyCompletion>,
    val categories: List<CategoryCompletion>
)

data class PuzzleEntry(
    val offsetMinutes: Int,
    val durationMinutes: Int,
    val name: String,
    val description: String,
    val id: String = UUID.randomUUID().toString(),
    /** A built-in category key so the same puzzle works in every itinerary. */
    val categoryKey: String = "other",
    val priority: TaskPriority = TaskPriority.NONE
)
data class PuzzleApplicationPreview(
    val overwritten: List<String>,
    val skippedEntries: List<String>,
    val latestFullStart: String?
)

class MissionRepository(private val db: AppDatabase) {
    private val taskDao = db.taskDao()
    private val categoryDao = db.taskCategoryDao()
    private val scheduleDao = db.scheduleDao()
    private val shopDao = db.shopDao()
    private val walletDao = db.walletDao()

    fun tasks(scheduleId: String): Flow<List<TaskEntity>> = taskDao.observeTasks(scheduleId)
    fun categories(scheduleId: String): Flow<List<TaskCategoryEntity>> = categoryDao.observeCategories(scheduleId)
    fun schedules(): Flow<List<ScheduleEntity>> = scheduleDao.observeSchedules()
    fun activeSchedule(): Flow<ScheduleSettingsEntity?> = scheduleDao.observeSettings()
    fun puzzles(): Flow<List<SchedulePuzzleEntity>> = scheduleDao.observePuzzles()
    fun dailySummaries(scheduleId: String): Flow<List<DailySummaryEntity>> = scheduleDao.observeDailySummaries(scheduleId)
    fun items(scheduleId: String): Flow<List<ShopItemEntity>> = shopDao.observeItems(scheduleId)
    fun transactions(scheduleId: String): Flow<List<TransactionEntity>> = walletDao.observeTransactions(scheduleId)

    suspend fun initialize() = db.withTransaction {
        if (walletDao.wallet() == null) walletDao.save(WalletEntity())
        if (scheduleDao.schedules().isEmpty()) {
            val hasExistingData = taskDao.tasksForSchedule(DEFAULT_SCHEDULE_ID).isNotEmpty()
            scheduleDao.upsert(ScheduleEntity(id = DEFAULT_SCHEDULE_ID, name = if (hasExistingData) "我的原有行程" else "空白行程"))
            seedCategories(DEFAULT_SCHEDULE_ID)
        } else {
            if (categoryDao.categories(DEFAULT_SCHEDULE_ID).isEmpty()) seedCategories(DEFAULT_SCHEDULE_ID)
        }
        val storedSettings = scheduleDao.settings()
        var settings = storedSettings ?: ScheduleSettingsEntity(activeScheduleId = DEFAULT_SCHEDULE_ID)
        if (!settings.exampleSchedulesInitialized) {
            val legacyIds = setOf(SAMPLE_WORK_ID, SAMPLE_STUDY_ID, SAMPLE_HEALTH_ID)
            if (settings.activeScheduleId in legacyIds) settings = settings.copy(activeScheduleId = DEFAULT_SCHEDULE_ID)
            legacyIds.forEach { legacyId -> if (scheduleDao.schedule(legacyId) != null) removeScheduleData(legacyId) }
            seedSampleSchedule(SAMPLE_BALANCED_ID, "範例：平衡的一天", balancedDayExampleTasks())
            settings = settings.copy(exampleSchedulesInitialized = true)
        }
        // Existing installations may already have the sample itinerary, so seed its rewards separately.
        if (scheduleDao.schedule(SAMPLE_BALANCED_ID) != null) seedSampleShopItems(SAMPLE_BALANCED_ID)
        if (settings != storedSettings) scheduleDao.saveSettings(settings)
    }

    suspend fun selectSchedule(id: String) {
        require(scheduleDao.schedule(id) != null) { "找不到行程" }
        scheduleDao.saveSettings((scheduleDao.settings() ?: ScheduleSettingsEntity()).copy(activeScheduleId = id))
    }

    suspend fun createSchedule(name: String) = db.withTransaction {
        require(name.isNotBlank()) { "行程名稱不可空白" }
        val schedule = ScheduleEntity(name = name.trim())
        scheduleDao.upsert(schedule)
        seedCategories(schedule.id)
    }

    suspend fun renameSchedule(id: String, name: String) {
        require(name.isNotBlank()) { "行程名稱不可空白" }
        val schedule = scheduleDao.schedule(id) ?: error("找不到行程")
        scheduleDao.upsert(schedule.copy(name = name.trim()))
    }

    suspend fun deleteSchedule(id: String) = db.withTransaction {
        check(scheduleDao.schedule(id) != null) { "找不到行程" }
        check(id != DEFAULT_SCHEDULE_ID) { "預設行程不可刪除" }
        removeScheduleData(id)
        if (scheduleDao.settings()?.activeScheduleId == id) {
            scheduleDao.saveSettings((scheduleDao.settings() ?: ScheduleSettingsEntity()).copy(activeScheduleId = DEFAULT_SCHEDULE_ID))
        }
    }

    private suspend fun removeScheduleData(id: String) {
        val schedule = scheduleDao.schedule(id) ?: return
        taskDao.tasksForSchedule(id).forEach { taskDao.deleteInstancesForTask(it.id) }
        taskDao.deleteTasksForSchedule(id)
        categoryDao.deleteCategoriesForSchedule(id)
        shopDao.deleteItemsForSchedule(id)
        walletDao.deleteTransactionsForSchedule(id)
        scheduleDao.deletePuzzlesForSchedule(id)
        scheduleDao.deleteDailySummariesForSchedule(id)
        scheduleDao.delete(schedule)
    }

    suspend fun exportSchedule(scheduleId: String): String {
        val schedule = scheduleDao.schedule(scheduleId) ?: error("找不到行程")
        return JSONObject().apply {
            put("format", "mission-market-schedule-v1")
            put("name", schedule.name)
            put("exportedAt", System.currentTimeMillis())
            put("categories", JSONArray(categoryDao.categories(scheduleId).map { category -> JSONObject().apply { put("id", category.id); put("name", category.name); put("icon", category.icon) } }))
            put("tasks", JSONArray(taskDao.tasksForSchedule(scheduleId).filterNot { it.timelineOnly }.map { task -> JSONObject().apply {
                put("name", task.name); put("description", task.description); put("startDate", task.startDate)
                put("startTime", task.startTime); put("endTime", task.endTime); put("allDay", task.allDay)
                put("repeatType", task.repeatType.name); put("repeatConfig", task.repeatConfig); put("repeatEndDate", task.repeatEndDate)
                put("categoryId", task.categoryId); put("priority", task.priority.name); put("checklist", task.checklist)
                put("rewardCoins", task.rewardCoins.toPlainString()); put("rewardDiamonds", task.rewardDiamonds.toPlainString())
            } }))
            put("shopItems", JSONArray(shopDao.allItems(scheduleId).map { item -> JSONObject().apply {
                put("name", item.name); put("emoji", item.emoji); put("description", item.description)
                put("coinPrice", item.coinPrice.toPlainString()); put("diamondPrice", item.diamondPrice.toPlainString()); put("sortOrder", item.sortOrder)
            } }))
        }.toString(2)
    }

    suspend fun importSchedule(jsonText: String): ScheduleEntity = db.withTransaction {
        val source = JSONObject(jsonText)
        require(source.optString("format") == "mission-market-schedule-v1") { "不是支援的行程匯出檔" }
        val schedule = ScheduleEntity(name = "匯入：" + source.optString("name", "未命名行程"))
        scheduleDao.upsert(schedule)
        seedCategories(schedule.id)
        val categoryMap = mutableMapOf<String, String>()
        val importedCategoryNames = categoryDao.categories(schedule.id).associateBy { it.name.trim().lowercase() }.toMutableMap()
        val categories = source.optJSONArray("categories") ?: JSONArray()
        for (i in 0 until categories.length()) {
            val category = categories.getJSONObject(i)
            val name = category.optString("name", "其他").trim().ifBlank { "其他" }
            val nameKey = name.lowercase()
            val resolvedCategory = importedCategoryNames[nameKey] ?: TaskCategoryEntity(name = name, icon = category.optString("icon", "🏷️"), scheduleId = schedule.id).also {
                categoryDao.upsert(it)
                importedCategoryNames[nameKey] = it
            }
            category.optString("id").takeIf { it.isNotBlank() }?.let { categoryMap[it] = resolvedCategory.id }
        }
        val tasks = source.optJSONArray("tasks") ?: JSONArray()
        for (i in 0 until tasks.length()) {
            val task = tasks.getJSONObject(i)
            val categoryId = categoryMap[task.optString("categoryId")] ?: defaultCategoryId(schedule.id, "other")
            taskDao.upsert(TaskEntity(
                name = task.optString("name", "未命名任務"), description = task.optString("description"),
                startDate = task.optString("startDate", LocalDate.now().toString()), startTime = task.optString("startTime"), endTime = task.optString("endTime"),
                allDay = task.optBoolean("allDay", true), repeatType = runCatching { RepeatType.valueOf(task.optString("repeatType", "NONE")) }.getOrDefault(RepeatType.NONE),
                repeatConfig = task.optString("repeatConfig"), repeatEndDate = task.optString("repeatEndDate"), categoryId = categoryId,
                priority = runCatching { TaskPriority.valueOf(task.optString("priority", "NONE")) }.getOrDefault(TaskPriority.NONE), checklist = task.optString("checklist"),
                rewardCoins = task.optString("rewardCoins", "0").toBigDecimalOrNull() ?: BigDecimal.ZERO,
                rewardDiamonds = task.optString("rewardDiamonds", "0").toBigDecimalOrNull() ?: BigDecimal.ZERO, scheduleId = schedule.id
            ))
        }
        val items = source.optJSONArray("shopItems") ?: JSONArray()
        for (i in 0 until items.length()) {
            val item = items.getJSONObject(i)
            shopDao.upsert(ShopItemEntity(name = item.optString("name", "未命名商品"), emoji = item.optString("emoji", "🎁"), description = item.optString("description"), coinPrice = item.optString("coinPrice", "0").toBigDecimalOrNull() ?: BigDecimal.ZERO, diamondPrice = item.optString("diamondPrice", "0").toBigDecimalOrNull() ?: BigDecimal.ZERO, sortOrder = item.optInt("sortOrder", 0), scheduleId = schedule.id))
        }
        schedule
    }

    suspend fun savePuzzle(puzzle: SchedulePuzzleEntity, updateAppliedInstances: Boolean = false) = db.withTransaction {
        require(puzzle.name.isNotBlank()) { "拼圖名稱不可空白" }
        require(puzzle.durationDays >= 0 && puzzle.durationHours >= 0 && puzzle.durationMinutes >= 0) { "拼圖時長不可為負數" }
        val normalized = normalizePuzzle(puzzle)
        scheduleDao.upsertPuzzle(normalized)
        if (updateAppliedInstances) updateAppliedPuzzleInstances(normalized)
    }

    suspend fun deletePuzzle(puzzle: SchedulePuzzleEntity, deleteAppliedInstances: Boolean = false) = db.withTransaction {
        if (deleteAppliedInstances) {
            taskDao.tasksForPuzzle(puzzle.id).forEach { task ->
                taskDao.deleteInstancesForTask(task.id)
                taskDao.delete(task)
            }
        }
        scheduleDao.deletePuzzle(puzzle)
    }

    suspend fun puzzleOverwritePreview(puzzleId: String, scheduleId: String, startDate: String, startTime: String): PuzzleApplicationPreview {
        val puzzle = scheduleDao.puzzle(puzzleId) ?: error("找不到行程拼圖")
        val start = LocalDate.parse(startDate).atTime(LocalTime.parse(startTime))
        val end = puzzleWindowEnd(puzzle, start)
        val names = mutableListOf<String>()
        var day = start.toLocalDate()
        while (!day.isAfter(end.toLocalDate())) {
            ensure(day, scheduleId)
            taskDao.instancesForDate(day.toString()).filterNot { it.deleted || it.settled }.forEach { instance ->
                taskDao.task(instance.taskId)?.takeIf { it.scheduleId == scheduleId && overlapsPuzzleWindow(it, instance, start, end) }?.let { task ->
                    names += (instance.nameOverride ?: task.name)
                }
            }
            day = day.plusDays(1)
        }
        return PuzzleApplicationPreview(
            overwritten = names.distinct(),
            skippedEntries = skippedPuzzleEntries(puzzle, start).map { it.name }.distinct(),
            latestFullStart = latestFullStart(puzzle)
        )
    }

    suspend fun applyPuzzle(puzzleId: String, scheduleId: String, startDate: String, startTime: String): List<String> = db.withTransaction {
        val puzzle = scheduleDao.puzzle(puzzleId) ?: error("找不到行程拼圖")
        val start = LocalDate.parse(startDate).atTime(LocalTime.parse(startTime))
        val end = puzzleWindowEnd(puzzle, start)
        var day = start.toLocalDate()
        while (!day.isAfter(end.toLocalDate())) {
            ensure(day, scheduleId)
            taskDao.instancesForDate(day.toString()).filterNot { it.settled || it.deleted }.forEach { instance ->
                taskDao.task(instance.taskId)?.takeIf { it.scheduleId == scheduleId && overlapsPuzzleWindow(it, instance, start, end) }?.let {
                    taskDao.updateInstance(instance.copy(deleted = true))
                }
            }
            day = day.plusDays(1)
        }
        val entries = parsePuzzleEntries(puzzle.entriesJson)
        val skipped = skippedPuzzleEntries(puzzle, start, entries)
        insertPuzzleEntries(puzzle, scheduleId, start, entries.filterNot { entry -> skipped.any { it.id == entry.id } })
        skipped.map { it.name }.distinct()
    }

    private fun normalizePuzzle(puzzle: SchedulePuzzleEntity): SchedulePuzzleEntity = when (puzzle.puzzleType) {
        PuzzleType.SINGLE_DAY -> {
            val total = puzzle.durationDays * 1440 + puzzle.durationHours * 60 + puzzle.durationMinutes
            require(total in 1..1440) { "單日拼圖時長必須介於 1 分鐘至 24 小時" }
            puzzle.copy(durationDays = 0, durationHours = total / 60, durationMinutes = total % 60)
        }
        PuzzleType.MULTI_DAY -> {
            require(puzzle.durationDays >= 2) { "跨日拼圖至少需要 2 天" }
            val finalDay = puzzle.durationHours * 60 + puzzle.durationMinutes
            require(finalDay in 1..1440) { "最後一天時長必須介於 1 分鐘至 24 小時" }
            puzzle
        }
    }

    private fun puzzleWindowEnd(puzzle: SchedulePuzzleEntity, start: LocalDateTime): LocalDateTime = when (puzzle.puzzleType) {
        PuzzleType.SINGLE_DAY -> minOf(start.plusMinutes(singleDayDuration(puzzle).toLong()), start.toLocalDate().plusDays(1).atStartOfDay())
        PuzzleType.MULTI_DAY -> start.toLocalDate().plusDays((puzzle.durationDays - 1).toLong()).atStartOfDay().plusMinutes(finalDayDuration(puzzle).toLong())
    }

    private fun singleDayDuration(puzzle: SchedulePuzzleEntity): Int = (puzzle.durationDays * 1440 + puzzle.durationHours * 60 + puzzle.durationMinutes).coerceAtMost(1440)
    private fun finalDayDuration(puzzle: SchedulePuzzleEntity): Int = (puzzle.durationHours * 60 + puzzle.durationMinutes).coerceAtMost(1440)
    private fun entryDateTime(puzzle: SchedulePuzzleEntity, base: LocalDateTime, entry: PuzzleEntry): LocalDateTime {
        val day = entry.offsetMinutes / 1440
        val minute = entry.offsetMinutes % 1440
        return if (puzzle.puzzleType == PuzzleType.MULTI_DAY && day > 0) base.toLocalDate().plusDays(day.toLong()).atStartOfDay().plusMinutes(minute.toLong()) else base.plusMinutes(entry.offsetMinutes.toLong())
    }
    private fun skippedPuzzleEntries(puzzle: SchedulePuzzleEntity, base: LocalDateTime, entries: List<PuzzleEntry> = parsePuzzleEntries(puzzle.entriesJson)): List<PuzzleEntry> {
        val end = puzzleWindowEnd(puzzle, base)
        return entries.filter { entry ->
            val entryStart = entryDateTime(puzzle, base, entry)
            val entryEnd = entryStart.plusMinutes(entry.durationMinutes.toLong())
            val endsAt24 = entryEnd == entryStart.toLocalDate().plusDays(1).atStartOfDay()
            entry.durationMinutes <= 0 || (entryEnd.toLocalDate() != entryStart.toLocalDate() && !endsAt24) || entryEnd.isAfter(end)
        }
    }
    private fun latestFullStart(puzzle: SchedulePuzzleEntity): String? {
        val required = when (puzzle.puzzleType) {
            PuzzleType.SINGLE_DAY -> singleDayDuration(puzzle)
            PuzzleType.MULTI_DAY -> parsePuzzleEntries(puzzle.entriesJson).filter { it.offsetMinutes < 1440 }.maxOfOrNull { it.offsetMinutes + it.durationMinutes } ?: 0
        }
        val latest = 1440 - required
        return if (latest < 0) null else "%02d:%02d".format(latest / 60, latest % 60)
    }

    /** A puzzle only replaces items whose actual timed interval intersects its applied interval. */
    private fun overlapsPuzzleWindow(template: TaskEntity, instance: TaskInstanceEntity, puzzleStart: LocalDateTime, puzzleEnd: LocalDateTime): Boolean {
        val task = effectiveTask(template, instance)
        if (task.allDay || task.startTime.isBlank() || task.endTime.isBlank()) return false
        val itemStart = runCatching { LocalDate.parse(instance.scheduledDate).atTime(LocalTime.parse(task.startTime)) }.getOrNull() ?: return false
        val itemEnd = runCatching { if (task.endTime == "24:00") LocalDate.parse(instance.scheduledDate).plusDays(1).atStartOfDay() else LocalDate.parse(instance.scheduledDate).atTime(LocalTime.parse(task.endTime)) }.getOrNull() ?: return false
        return itemStart.isBefore(puzzleEnd) && puzzleStart.isBefore(itemEnd)
    }

    /** Rebuilds every unfinished application of a puzzle while retaining any completed puzzle entries. */
    private suspend fun updateAppliedPuzzleInstances(puzzle: SchedulePuzzleEntity) {
        val applications = taskDao.tasksForPuzzle(puzzle.id)
            .filter { it.puzzleBaseAt != null }
            .groupBy { it.scheduleId to it.puzzleBaseAt!! }
        applications.forEach { (application, appliedTasks) ->
            val completedEntries = appliedTasks.mapNotNull { task ->
                task.puzzleEntryId?.takeIf { entryId -> taskDao.instancesForTask(task.id).any { it.settled } }
            }.toSet()
            appliedTasks.filter { task -> taskDao.instancesForTask(task.id).none { it.settled } }.forEach { task ->
                taskDao.deleteInstancesForTask(task.id)
                taskDao.delete(task)
            }
            val base = Instant.ofEpochMilli(application.second).atZone(ZoneId.systemDefault()).toLocalDateTime()
            insertPuzzleEntries(
                puzzle = puzzle,
                scheduleId = application.first,
                base = base,
                entries = parsePuzzleEntries(puzzle.entriesJson).filterNot { it.id in completedEntries }
            )
        }
    }

    private suspend fun insertPuzzleEntries(puzzle: SchedulePuzzleEntity, scheduleId: String, base: LocalDateTime, entries: List<PuzzleEntry>) {
        val baseAt = base.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        entries.forEach { entry ->
            require(entry.durationMinutes > 0) { "拼圖項目的時長必須大於零" }
            val entryStart = entryDateTime(puzzle, base, entry)
            val entryEnd = entryStart.plusMinutes(entry.durationMinutes.toLong())
            val endsAt24 = entryEnd == entryStart.toLocalDate().plusDays(1).atStartOfDay()
            require(entryEnd.toLocalDate() == entryStart.toLocalDate() || endsAt24) { "單一拼圖項目不可跨日" }
            val task = TaskEntity(
                name = entry.name,
                description = entry.description,
                startDate = entryStart.toLocalDate().toString(),
                startTime = entryStart.toLocalTime().toString().take(5),
                endTime = if (endsAt24) "24:00" else entryEnd.toLocalTime().toString().take(5),
                allDay = false,
                timelineOnly = true,
                scheduleId = scheduleId,
                categoryId = defaultCategoryId(scheduleId, entry.categoryKey),
                priority = entry.priority,
                puzzleId = puzzle.id,
                puzzleEntryId = entry.id,
                puzzleBaseAt = baseAt
            )
            validateTask(task)
            taskDao.upsert(task)
            taskDao.insertInstance(TaskInstanceEntity(taskId = task.id, scheduledDate = task.startDate))
        }
    }

    suspend fun monthlyStats(scheduleId: String, month: YearMonth): MonthlyStats {
        val first = month.atDay(1)
        val last = month.atEndOfMonth()
        var date = first
        while (!date.isAfter(last)) {
            ensure(date, scheduleId)
            date = date.plusDays(1)
        }
        val tasks = taskDao.tasksForSchedule(scheduleId).associateBy { it.id }
        val categories = categoryDao.categories(scheduleId).associateBy { it.id }
        val instances = taskDao.instancesBetween(first.toString(), last.toString()).filter { !it.deleted && it.taskId in tasks }
        val planned = instances.size
        val settled = instances.count { it.settled }
        val completionRatio = if (planned == 0) 0f else settled.toFloat() / planned
        val weights = instances.map { instance ->
            val task = effectiveTask(tasks.getValue(instance.taskId), instance)
            (task.rewardCoins + task.rewardDiamonds).takeIf { it > BigDecimal.ZERO } ?: BigDecimal.ONE
        }
        val totalWeight = weights.fold(BigDecimal.ZERO, BigDecimal::add)
        val weighted = if (totalWeight == BigDecimal.ZERO) 0f else instances.indices.fold(BigDecimal.ZERO) { total, index ->
            total + weights[index].multiply(instances[index].completionPercentage).divide(BigDecimal("100"), 6, RoundingMode.HALF_UP)
        }.divide(totalWeight, 6, RoundingMode.HALF_UP).toFloat()
        val daily = generateSequence(first) { date -> date.plusDays(1).takeIf { !it.isAfter(last) } }.map { date ->
            val dayItems = instances.filter { it.scheduledDate == date.toString() }
            DailyCompletion(date, if (dayItems.isEmpty()) 0f else dayItems.map { it.completionPercentage.toFloat() / 100f }.average().toFloat())
        }.toList()
        val categoryStats = instances.groupBy { instance ->
            val template = tasks.getValue(instance.taskId)
            instance.categoryIdOverride ?: template.categoryId
        }.map { (categoryId, group) ->
            val category = categories[categoryId]
            CategoryCompletion(categoryId, category?.name ?: "未分類", category?.icon ?: "🏷️", group.map { it.completionPercentage.toFloat() / 100f }.average().toFloat(), group.size)
        }.sortedByDescending { it.completion }
        return MonthlyStats(
            month, planned, settled, completionRatio, weighted,
            earnedCoins = instances.filter { it.settled }.fold(BigDecimal.ZERO) { total, item -> total + item.earnedCoins },
            earnedDiamonds = instances.filter { it.settled }.fold(BigDecimal.ZERO) { total, item -> total + item.earnedDiamonds },
            activeDays = daily.count { it.completion > 0f }, daily = daily, categories = categoryStats
        )
    }

    suspend fun saveCategory(scheduleId: String, name: String, icon: String) {
        require(name.isNotBlank()) { "分類名稱不可空白" }
        require(categoryDao.categories(scheduleId).none { it.name.trim().equals(name.trim(), ignoreCase = true) }) { "已有同名分類" }
        categoryDao.upsert(TaskCategoryEntity(name = name.trim(), icon = icon.ifBlank { "🏷️" }, scheduleId = scheduleId))
    }

    suspend fun updateCategory(scheduleId: String, category: TaskCategoryEntity, name: String, icon: String) {
        require(name.isNotBlank()) { "分類名稱不可空白" }
        require(category.scheduleId == scheduleId) { "分類不屬於目前行程" }
        if (category.id == defaultCategoryId(scheduleId, "other")) require(name.trim() == "其他") { "「其他」分類名稱不可修改" }
        require(categoryDao.categories(scheduleId).none { it.id != category.id && it.name.trim().equals(name.trim(), ignoreCase = true) }) { "已有同名分類" }
        categoryDao.upsert(category.copy(name = name.trim(), icon = icon.ifBlank { "🏷️" }))
    }

    suspend fun deleteCategory(scheduleId: String, category: TaskCategoryEntity) = db.withTransaction {
        require(category.scheduleId == scheduleId) { "分類不屬於目前行程" }
        val categories = categoryDao.categories(scheduleId)
        val otherId = defaultCategoryId(scheduleId, "other")
        check(category.id != otherId && category.name.trim() != "其他") { "「其他」分類不可刪除" }
        val replacement = categories.firstOrNull { it.id == otherId || it.name.trim() == "其他" }
            ?: TaskCategoryEntity(id = otherId, name = "其他", icon = "✨", scheduleId = scheduleId, isPreset = true).also { categoryDao.upsert(it) }
        if (replacement.name != "其他") {
            categoryDao.upsert(replacement.copy(name = "其他"))
        }
        taskDao.replaceTaskCategory(scheduleId, category.id, replacement.id)
        taskDao.replaceInstanceCategory(category.id, replacement.id)
        categoryDao.delete(category)
    }

    suspend fun saveTask(task: TaskEntity, prerequisiteIds: List<String> = emptyList(), minimums: Map<String, BigDecimal> = emptyMap()) = db.withTransaction {
        validateTask(task); checkTaskConflict(task)
        require(prerequisiteIds.none { it == task.id || createsCycle(task.id, it) }) { "前置任務不可形成循環" }
        taskDao.upsert(task.copy(timelineOnly = false, updatedAt = System.currentTimeMillis()))
        if (prerequisiteIds.isNotEmpty()) {
            taskDao.clearPrerequisites(task.id)
            taskDao.insertPrerequisites(prerequisiteIds.distinct().map { TaskPrerequisiteEntity(taskId = task.id, prerequisiteTaskId = it, minimumCompletionPercentage = minimums[it] ?: BigDecimal("100")) })
        }
    }

    suspend fun createTimelineTask(task: TaskEntity) = db.withTransaction {
        require(task.repeatType == RepeatType.NONE) { "時間軸單次任務不可設定重複週期" }
        val event = task.copy(timelineOnly = true, repeatType = RepeatType.NONE, repeatConfig = "", repeatEndDate = "", updatedAt = System.currentTimeMillis())
        validateTask(event); checkTaskConflict(event)
        taskDao.upsert(event); taskDao.insertInstance(TaskInstanceEntity(taskId = event.id, scheduledDate = event.startDate))
    }

    suspend fun deleteTask(task: TaskEntity) = db.withTransaction { taskDao.deleteInstancesForTask(task.id); taskDao.clearPrerequisites(task.id); taskDao.delete(task) }

    private suspend fun createsCycle(taskId: String, candidate: String): Boolean {
        val graph = taskDao.allPrerequisites().groupBy { it.taskId }
        fun visit(node: String, seen: MutableSet<String>): Boolean = node == taskId || (seen.add(node) && graph[node].orEmpty().any { visit(it.prerequisiteTaskId, seen) })
        return visit(candidate, mutableSetOf())
    }

    /** 24:00 is a valid end-of-day marker for timeline-only puzzle tasks. */
    private fun parseEndTime(value: String): LocalTime = if (value == "24:00") LocalTime.MAX else LocalTime.parse(value)

    private fun validateTask(task: TaskEntity) {
        require(runCatching { LocalDate.parse(task.startDate) }.isSuccess) { "任務日期格式須為 YYYY-MM-DD" }
        if (task.repeatEndDate.isNotBlank()) {
            require(runCatching { LocalDate.parse(task.repeatEndDate) }.isSuccess) { "重複結束日期格式須為 YYYY-MM-DD" }
            require(!LocalDate.parse(task.repeatEndDate).isBefore(LocalDate.parse(task.startDate))) { "重複結束日期不可早於開始日期" }
        }
        if (!task.allDay && task.startTime.isNotBlank()) {
            require(runCatching { LocalTime.parse(task.startTime) }.isSuccess) { "開始時間格式須為 HH:mm" }
            if (task.endTime.isNotBlank()) {
                require(runCatching { parseEndTime(task.endTime) }.isSuccess) { "結束時間格式須為 HH:mm" }
                require(parseEndTime(task.endTime).isAfter(LocalTime.parse(task.startTime))) { "結束時間必須晚於開始時間" }
            }
        }
    }

    private fun occurs(task: TaskEntity, day: LocalDate): Boolean {
        val start = LocalDate.parse(task.startDate)
        if (!task.active || day.isBefore(start)) return false
        task.repeatEndDate.takeIf { it.isNotBlank() }?.let { if (day.isAfter(LocalDate.parse(it))) return false }
        return when (task.repeatType) {
            RepeatType.NONE -> day == start
            RepeatType.DAILY -> true
            RepeatType.WEEKLY -> task.repeatConfig.substringAfter("days=", "").substringBefore(';').split(',').mapNotNull { it.toIntOrNull() }.let { it.isEmpty() || day.dayOfWeek.value in it }
            RepeatType.MONTHLY -> task.repeatConfig.substringAfter("monthDay=", "").let { value -> if (value == "last") day == day.with(TemporalAdjusters.lastDayOfMonth()) else day.dayOfMonth == (value.toIntOrNull() ?: start.dayOfMonth) }
            RepeatType.INTERVAL -> ChronoUnit.DAYS.between(start, day) % (task.repeatConfig.substringAfter("interval=", "1").toLongOrNull()?.coerceAtLeast(1) ?: 1L) == 0L
        }
    }

    private suspend fun ensure(day: LocalDate, scheduleId: String): List<TaskEntity> {
        val due = taskDao.activeTasks(scheduleId).filter { occurs(it, day) }
        due.forEach { taskDao.insertInstance(TaskInstanceEntity(taskId = it.id, scheduledDate = day.toString())) }
        return due
    }

    suspend fun dashboard(day: LocalDate, scheduleId: String): DashboardData {
        val due = ensure(day, scheduleId)
        val visible = taskDao.instancesForDate(day.toString()).filterNot { it.deleted }.associateBy { it.taskId }
        val cards = due.mapNotNull { task -> visible[task.id]?.let { instance -> val unlock = taskUnlock(task, day); TaskCardModel(effectiveTask(task, instance), instance, unlock.first, unlock.second) } }
        val start = day.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val end = day.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val rewards = shopDao.schedulesForDay(start, end).mapNotNull { exchange -> shopDao.item(exchange.shopItemId)?.takeIf { it.scheduleId == scheduleId }?.let { RewardSchedule(it, exchange) } }
        val settled = cards.filter { it.instance.settled }
        return DashboardData(walletDao.wallet() ?: WalletEntity(), cards, rewards, settled.fold(BigDecimal.ZERO) { total, card -> total + card.instance.earnedCoins }, settled.fold(BigDecimal.ZERO) { total, card -> total + card.instance.earnedDiamonds })
    }

    private fun effectiveTask(task: TaskEntity, instance: TaskInstanceEntity) = task.copy(
        name = instance.nameOverride ?: task.name, description = instance.descriptionOverride ?: task.description,
        locationName = instance.locationOverride ?: task.locationName, address = instance.addressOverride ?: task.address,
        startTime = instance.startTimeOverride ?: task.startTime, endTime = instance.endTimeOverride ?: task.endTime,
        allDay = instance.allDayOverride ?: task.allDay, rewardCoins = instance.rewardCoinsOverride ?: task.rewardCoins,
        rewardDiamonds = instance.rewardDiamondsOverride ?: task.rewardDiamonds, categoryId = instance.categoryIdOverride ?: task.categoryId,
        priority = instance.priorityOverride ?: task.priority, checklist = instance.checklistOverride ?: task.checklist
    )

    suspend fun updateInstanceContent(instanceId: String, name: String, description: String, location: String, address: String, allDay: Boolean, start: String, end: String, coins: BigDecimal, diamonds: BigDecimal, categoryId: String, priority: TaskPriority, checklist: String) = db.withTransaction {
        val instance = taskDao.instanceById(instanceId) ?: error("找不到任務實例")
        check(!instance.settled) { "已完成任務請先撤回，才能修改任務資訊" }
        if (!allDay && start.isNotBlank() && end.isNotBlank()) require(parseEndTime(end).isAfter(LocalTime.parse(start))) { "結束時間必須晚於開始時間" }
        taskDao.updateInstance(instance.copy(nameOverride = name, descriptionOverride = description, locationOverride = location, addressOverride = address, allDayOverride = allDay, startTimeOverride = if (allDay) "" else start, endTimeOverride = if (allDay) "" else end, rewardCoinsOverride = coins, rewardDiamondsOverride = diamonds, categoryIdOverride = categoryId, priorityOverride = priority, checklistOverride = checklist))
    }

    suspend fun updateSettledResult(instanceId: String, result: String) = db.withTransaction {
        val instance = taskDao.instanceById(instanceId) ?: error("找不到任務實例")
        check(instance.settled) { "任務尚未完成" }
        taskDao.updateInstance(instance.copy(result = result))
    }

    suspend fun saveDailySummary(scheduleId: String, date: LocalDate, content: String) = db.withTransaction {
        val text = content.trim()
        if (text.isBlank()) scheduleDao.deleteDailySummary(scheduleId, date.toString())
        else {
            val existing = scheduleDao.dailySummary(scheduleId, date.toString())
            scheduleDao.upsertDailySummary(DailySummaryEntity(id = existing?.id ?: UUID.randomUUID().toString(), scheduleId = scheduleId, date = date.toString(), content = text))
        }
    }

    suspend fun deleteInstance(instanceId: String) = db.withTransaction {
        val instance = taskDao.instanceById(instanceId) ?: error("找不到任務實例")
        check(!instance.settled) { "已完成任務請先撤回獎勵，再刪除" }
        val task = taskDao.task(instance.taskId) ?: error("找不到任務")
        if (task.timelineOnly) { taskDao.deleteInstancesForTask(task.id); taskDao.delete(task) } else taskDao.updateInstance(instance.copy(deleted = true))
    }

    suspend fun undoSettlement(instanceId: String): Result<Pair<BigDecimal, BigDecimal>> = runCatching { db.withTransaction {
        val instance = taskDao.instanceById(instanceId) ?: error("找不到任務實例")
        check(instance.settled) { "此任務尚未完成結算" }
        val old = walletDao.wallet() ?: WalletEntity()
        check(old.coins >= instance.earnedCoins && old.diamonds >= instance.earnedDiamonds) { "錢包餘額不足，無法歸還本次任務獎勵" }
        val updated = old.copy(coins = old.coins - instance.earnedCoins, diamonds = old.diamonds - instance.earnedDiamonds, updatedAt = System.currentTimeMillis())
        val coins = instance.earnedCoins; val diamonds = instance.earnedDiamonds
        taskDao.updateInstance(instance.copy(status = if (instance.completionPercentage > BigDecimal.ZERO) TaskStatus.IN_PROGRESS else TaskStatus.NOT_STARTED, settled = false, earnedCoins = BigDecimal.ZERO, earnedDiamonds = BigDecimal.ZERO, settledAt = null))
        walletDao.save(updated)
        walletDao.transaction(TransactionEntity(type = TransactionType.MANUAL_ADJUSTMENT, coinChange = coins.negate(), diamondChange = diamonds.negate(), coinsBefore = old.coins, coinsAfter = updated.coins, diamondsBefore = old.diamonds, diamondsAfter = updated.diamonds, relatedTaskInstanceId = instance.id, scheduleId = (taskDao.task(instance.taskId)?.scheduleId ?: DEFAULT_SCHEDULE_ID), note = "撤回任務完成，歸還獎勵"))
        coins to diamonds
    } }

    suspend fun timeConflicts(scheduleId: String, date: String, startText: String, endText: String, excludeTaskId: String? = null, excludeInstanceId: String? = null): List<String> {
        if (startText.length != 5 || endText.length != 5) return emptyList()
        val day = runCatching { LocalDate.parse(date) }.getOrNull() ?: return listOf("日期格式須為 YYYY-MM-DD")
        val start = runCatching { LocalTime.parse(startText) }.getOrNull() ?: return listOf("開始時間格式須為 HH:mm")
        val end = runCatching { parseEndTime(endText) }.getOrNull() ?: return listOf("結束時間格式須為 HH:mm")
        if (!end.isAfter(start)) return listOf("結束時間必須晚於開始時間")
        // 以實際仍存在於當日日程的實例為準。拼圖覆寫的項目會保留歷史實例，
        // 但標記為 deleted，因此不能再由類別定義回推成衝突。
        ensure(day, scheduleId)
        return taskDao.instancesForDate(date).filter { instance ->
            !instance.deleted && instance.id != excludeInstanceId &&
                (excludeInstanceId != null || instance.taskId != excludeTaskId)
        }.mapNotNull { instance ->
            val task = taskDao.task(instance.taskId) ?: return@mapNotNull null
            if (task.scheduleId != scheduleId) return@mapNotNull null
            val effective = effectiveTask(task, instance)
            if (!effective.allDay && effective.startTime.isNotBlank() && effective.endTime.isNotBlank() && start < parseEndTime(effective.endTime) && LocalTime.parse(effective.startTime) < end) "與日程「${effective.name}」(${effective.startTime}–${effective.endTime}) 重疊" else null
        }.distinct()
    }

    private suspend fun taskUnlock(task: TaskEntity, day: LocalDate): Pair<Boolean, List<String>> {
        val rules = taskDao.prerequisites(task.id); if (rules.isEmpty()) return true to emptyList()
        val failed = rules.filter { rule -> ((taskDao.instance(rule.prerequisiteTaskId, day.toString()) ?: taskDao.lastInstance(rule.prerequisiteTaskId))?.completionPercentage ?: BigDecimal.ZERO) < rule.minimumCompletionPercentage }.mapNotNull { taskDao.task(it.prerequisiteTaskId)?.name }
        return (if (task.prerequisiteMode == PrerequisiteMode.ALL) failed.isEmpty() else failed.size < rules.size) to failed
    }

    suspend fun settle(instanceId: String, percentage: BigDecimal, result: String, checkedItems: Set<Int> = emptySet()): Result<Pair<BigDecimal, BigDecimal>> = runCatching { db.withTransaction {
        require(percentage >= BigDecimal.ZERO && percentage <= BigDecimal("100")) { "完成度必須介於 0 到 100" }
        val instance = taskDao.instanceById(instanceId) ?: error("找不到任務實例")
        check(!instance.deleted && !instance.settled) { "此任務無法結算" }
        val template = taskDao.task(instance.taskId) ?: error("找不到任務")
        val task = effectiveTask(template, instance); val rate = percentage.divide(BigDecimal("100"), 4, RoundingMode.HALF_UP)
        val coins = task.rewardCoins.multiply(rate).setScale(2, RoundingMode.HALF_UP); val diamonds = task.rewardDiamonds.multiply(rate).setScale(2, RoundingMode.HALF_UP)
        val old = walletDao.wallet() ?: WalletEntity(); val updated = old.copy(coins = old.coins + coins, diamonds = old.diamonds + diamonds, updatedAt = System.currentTimeMillis())
        taskDao.updateInstance(instance.copy(completionPercentage = percentage, checkedChecklistItems = checkedItems.sorted().joinToString(","), result = result, status = TaskStatus.SETTLED, settled = true, earnedCoins = coins, earnedDiamonds = diamonds, settledAt = System.currentTimeMillis()))
        walletDao.save(updated)
        walletDao.transaction(TransactionEntity(type = TransactionType.TASK_REWARD, coinChange = coins, diamondChange = diamonds, coinsBefore = old.coins, coinsAfter = updated.coins, diamondsBefore = old.diamonds, diamondsAfter = updated.diamonds, relatedTaskInstanceId = instance.id, scheduleId = template.scheduleId, note = "任務結算：${task.name}"))
        coins to diamonds
    } }

    suspend fun saveItem(item: ShopItemEntity, prerequisiteIds: List<String>, minimums: Map<String, BigDecimal>) = db.withTransaction {
        shopDao.upsert(item.copy(updatedAt = System.currentTimeMillis()))
        if (prerequisiteIds.isNotEmpty()) { shopDao.clearPrerequisites(item.id); shopDao.insertPrerequisites(prerequisiteIds.distinct().map { ShopItemPrerequisiteEntity(shopItemId = item.id, prerequisiteTaskId = it, minimumCompletionPercentage = minimums[it] ?: BigDecimal("100")) }) }
    }
    suspend fun deleteItem(item: ShopItemEntity) = shopDao.delete(item)

    suspend fun shopCards(scheduleId: String, day: LocalDate = LocalDate.now()): List<ShopCardModel> = shopDao.allItems(scheduleId).map { item ->
        val rules = shopDao.prerequisites(item.id)
        val failed = rules.filter { rule -> ((taskDao.instance(rule.prerequisiteTaskId, day.toString()) ?: taskDao.lastInstance(rule.prerequisiteTaskId))?.completionPercentage ?: BigDecimal.ZERO) < rule.minimumCompletionPercentage }
        val unlocked = if (item.prerequisiteMode == PrerequisiteMode.ALL) failed.isEmpty() else failed.size < rules.size
        ShopCardModel(item, unlocked, if (unlocked) "" else "尚未達成：${failed.mapNotNull { taskDao.task(it.prerequisiteTaskId)?.name }.joinToString("、")}")
    }
    private fun limitStart(type: LimitType): Long = when (type) { LimitType.TOTAL, LimitType.UNLIMITED -> 0L; LimitType.DAILY -> LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli(); LimitType.WEEKLY -> LocalDate.now().minusDays((LocalDate.now().dayOfWeek.value - 1).toLong()).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli(); LimitType.MONTHLY -> LocalDate.now().withDayOfMonth(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() }

    suspend fun exchange(scheduleId: String, itemId: String, scheduledAt: Long?, scheduledEndAt: Long?, rewardNote: String): Result<Unit> = runCatching { db.withTransaction {
        val item = shopDao.item(itemId) ?: error("找不到商品"); check(item.scheduleId == scheduleId && item.active) { "商品未上架" }
        val card = shopCards(scheduleId).first { it.item.id == itemId }; check(card.unlocked) { "商品尚未解鎖：${card.reason}" }
        if (item.limitType != LimitType.UNLIMITED) check(shopDao.exchangeCount(item.id, limitStart(item.limitType)) < item.limitCount) { "已達兌換次數限制" }
        val old = walletDao.wallet() ?: WalletEntity(); check(old.coins >= item.coinPrice && old.diamonds >= item.diamondPrice) { "貨幣不足" }
        if (scheduledAt != null && scheduledEndAt != null) { require(scheduledEndAt > scheduledAt) { "獎勵結束時間必須晚於開始時間" }; check(shopDao.overlappingSchedules(scheduledAt, scheduledEndAt).isEmpty()) { "此獎勵時間與另一個已排程獎勵重疊" }; checkRewardTaskConflict(scheduleId, scheduledAt, scheduledEndAt) }
        val updated = old.copy(coins = old.coins - item.coinPrice, diamonds = old.diamonds - item.diamondPrice, updatedAt = System.currentTimeMillis()); walletDao.save(updated)
        shopDao.insertExchange(ShopExchangeEntity(shopItemId = item.id, coinCost = item.coinPrice, diamondCost = item.diamondPrice, scheduledAt = scheduledAt, scheduledEndAt = scheduledEndAt, note = rewardNote))
        walletDao.transaction(TransactionEntity(type = TransactionType.SHOP_PURCHASE, coinChange = item.coinPrice.negate(), diamondChange = item.diamondPrice.negate(), coinsBefore = old.coins, coinsAfter = updated.coins, diamondsBefore = old.diamonds, diamondsAfter = updated.diamonds, relatedShopItemId = item.id, scheduleId = scheduleId, note = "兌換：${item.name}${if (rewardNote.isBlank()) "" else "｜$rewardNote"}"))
    } }
    suspend fun updateRewardInstance(exchangeId: String, scheduledAt: Long?, scheduledEndAt: Long?, note: String) = db.withTransaction { val exchange = shopDao.exchange(exchangeId) ?: error("找不到獎勵實例"); if (scheduledAt != null && scheduledEndAt != null) require(scheduledEndAt > scheduledAt) { "結束時間必須晚於開始時間" }; shopDao.updateExchange(exchange.copy(scheduledAt = scheduledAt, scheduledEndAt = scheduledEndAt, note = note)) }
    suspend fun deleteRewardInstance(exchangeId: String) = db.withTransaction { val exchange = shopDao.exchange(exchangeId) ?: error("找不到獎勵實例"); val item = shopDao.item(exchange.shopItemId); val old = walletDao.wallet() ?: WalletEntity(); val updated = old.copy(coins = old.coins + exchange.coinCost, diamonds = old.diamonds + exchange.diamondCost, updatedAt = System.currentTimeMillis()); shopDao.deleteExchange(exchange); walletDao.save(updated); walletDao.transaction(TransactionEntity(type = TransactionType.MANUAL_ADJUSTMENT, coinChange = exchange.coinCost, diamondChange = exchange.diamondCost, coinsBefore = old.coins, coinsAfter = updated.coins, diamondsBefore = old.diamonds, diamondsAfter = updated.diamonds, relatedShopItemId = exchange.shopItemId, scheduleId = item?.scheduleId ?: DEFAULT_SCHEDULE_ID, note = "取消獎勵實例，退回貨幣")) }

    private suspend fun checkTaskConflict(candidate: TaskEntity) {
        if (candidate.allDay || candidate.startTime.isBlank() || candidate.endTime.isBlank()) return
        val day = LocalDate.parse(candidate.startDate); val start = LocalTime.parse(candidate.startTime); val end = parseEndTime(candidate.endTime)
        ensure(day, candidate.scheduleId)
        val hasConflict = taskDao.instancesForDate(day.toString()).any { instance ->
            if (instance.deleted || instance.taskId == candidate.id) return@any false
            val template = taskDao.task(instance.taskId) ?: return@any false
            if (template.scheduleId != candidate.scheduleId) return@any false
            val effective = effectiveTask(template, instance)
            !effective.allDay && effective.startTime.isNotBlank() && effective.endTime.isNotBlank() &&
                start < parseEndTime(effective.endTime) && LocalTime.parse(effective.startTime) < end
        }
        check(!hasConflict) { "此時間與既有任務重疊" }
    }
    private suspend fun checkRewardTaskConflict(scheduleId: String, startAt: Long, endAt: Long) {
        val zone = ZoneId.systemDefault(); val day = Instant.ofEpochMilli(startAt).atZone(zone).toLocalDate(); val start = Instant.ofEpochMilli(startAt).atZone(zone).toLocalTime(); val end = Instant.ofEpochMilli(endAt).atZone(zone).toLocalTime()
        check(!taskDao.activeTasks(scheduleId).filter { occurs(it, day) && !it.allDay && it.startTime.isNotBlank() && it.endTime.isNotBlank() }.any { start < parseEndTime(it.endTime) && LocalTime.parse(it.startTime) < end }) { "此獎勵時間與任務重疊" }
    }
    suspend fun adjust(scheduleId: String, coins: BigDecimal, diamonds: BigDecimal, note: String) = db.withTransaction { val old = walletDao.wallet() ?: WalletEntity(); val updated = old.copy(coins = old.coins + coins, diamonds = old.diamonds + diamonds, updatedAt = System.currentTimeMillis()); require(updated.coins >= BigDecimal.ZERO && updated.diamonds >= BigDecimal.ZERO) { "調整後貨幣不可小於 0" }; walletDao.save(updated); walletDao.transaction(TransactionEntity(type = TransactionType.MANUAL_ADJUSTMENT, coinChange = coins, diamondChange = diamonds, coinsBefore = old.coins, coinsAfter = updated.coins, diamondsBefore = old.diamonds, diamondsAfter = updated.diamonds, scheduleId = scheduleId, note = note)) }

    private suspend fun seedCategories(scheduleId: String) {
        val ids = categoryDao.categories(scheduleId).map { it.id }.toSet()
        defaultCategorySpecs.filterNot { defaultCategoryId(scheduleId, it.first) in ids }.forEach { (key, name, icon) -> categoryDao.upsert(TaskCategoryEntity(id = defaultCategoryId(scheduleId, key), name = name, icon = icon, scheduleId = scheduleId, isPreset = true)) }
    }
    private suspend fun seedSampleSchedule(id: String, name: String, tasks: List<TaskEntity>) {
        if (scheduleDao.schedule(id) == null) { scheduleDao.upsert(ScheduleEntity(id = id, name = name, isSample = true)); seedCategories(id); tasks.forEach { taskDao.upsert(it.copy(scheduleId = id, categoryId = defaultCategoryId(id, it.categoryId))) } }
    }
    private suspend fun seedSampleShopItems(scheduleId: String) {
        listOf(
            ShopItemEntity(id = "sample-reward-coffee", scheduleId = scheduleId, emoji = "☕", name = "喜歡的咖啡", description = "完成一段專注規劃後，給自己一杯喜歡的飲品。", coinPrice = BigDecimal("35")),
            ShopItemEntity(id = "sample-reward-movie", scheduleId = scheduleId, emoji = "🎬", name = "電影放鬆夜", description = "安排一晚看電影或影集，讓努力有舒服的收尾。", coinPrice = BigDecimal("120"), diamondPrice = BigDecimal("1")),
            ShopItemEntity(id = "sample-reward-daytrip", scheduleId = scheduleId, emoji = "🌿", name = "半日小旅行", description = "累積足夠進度後，安排一段無壓力的散步、展覽或近郊行程。", coinPrice = BigDecimal("280"), diamondPrice = BigDecimal("3"))
        ).forEach { item -> if (shopDao.item(item.id) == null) shopDao.upsert(item) }
    }
    private fun balancedDayExampleTasks() = listOf(
        sampleTask("sample-day-start", "整理今日重點", "08:30", "08:45", "life", "確認三個最重要目標，讓一天從清楚的方向開始。", "5"),
        sampleTask("sample-day-deep-work", "深度工作：核心任務", "09:00", "11:00", "work", "關閉通知，專心完成最有價值的一件工作。", "30", TaskPriority.YELLOW),
        sampleTask("sample-day-messages", "回覆訊息與行政", "11:15", "11:45", "work", "集中處理信件、訊息與待辦行政事項。", "10", TaskPriority.BLUE),
        sampleTask("sample-day-walk", "午餐與散步", "12:00", "13:00", "health", "用餐後散步，讓下午維持精神。", "10"),
        sampleTask("sample-day-project", "專案推進", "14:00", "16:00", "work", "處理需要協作或思考的專案工作。", "25", TaskPriority.ORANGE),
        sampleTask("sample-day-exercise", "運動與放鬆", "18:30", "19:15", "health", "選擇跑步、重訓或伸展，完成即可。", "15", TaskPriority.BLUE),
        sampleTask("sample-day-learn", "學習與輸出", "20:00", "21:00", "study", "閱讀、上課或整理筆記，寫下至少一個收穫。", "20", TaskPriority.YELLOW),
        sampleTask("sample-day-review", "晚間回顧與明日規劃", "21:30", "21:45", "life", "記下今天進度，預先安排明天的第一步。", "10")
    )
    private fun sampleTask(id: String, name: String, start: String, end: String, category: String, description: String, coins: String, priority: TaskPriority = TaskPriority.NONE) =
        TaskEntity(id = id, name = name, description = description, startDate = LocalDate.now().toString(), startTime = start, endTime = end, allDay = false, repeatType = RepeatType.DAILY, categoryId = category, priority = priority, rewardCoins = BigDecimal(coins))
    private fun defaultCategoryId(scheduleId: String, key: String) = if (scheduleId == DEFAULT_SCHEDULE_ID) key else "$scheduleId-$key"
    suspend fun exportPuzzles(): String = JSONObject().apply {
        put("format", "mission-market-puzzles-v1")
        put("puzzles", JSONArray(scheduleDao.allPuzzles().map { puzzle -> JSONObject().apply {
            put("name", puzzle.name); put("description", puzzle.description); put("puzzleType", puzzle.puzzleType.name); put("durationDays", puzzle.durationDays); put("durationHours", puzzle.durationHours); put("durationMinutes", puzzle.durationMinutes); put("entries", JSONArray(puzzle.entriesJson))
        } }))
    }.toString(2)

    suspend fun importPuzzles(jsonText: String) = db.withTransaction {
        val source = JSONObject(jsonText)
        require(source.optString("format") == "mission-market-puzzles-v1") { "不是支援的拼圖匯出檔" }
        val puzzles = source.optJSONArray("puzzles") ?: JSONArray()
        for (i in 0 until puzzles.length()) {
            val puzzle = puzzles.getJSONObject(i)
            scheduleDao.upsertPuzzle(SchedulePuzzleEntity(
                scheduleId = DEFAULT_SCHEDULE_ID,
                name = "匯入：" + puzzle.optString("name", "未命名拼圖"),
                description = puzzle.optString("description"),
                startDate = "", endDate = "", entriesJson = puzzle.optJSONArray("entries")?.toString() ?: "[]",
                puzzleType = runCatching { PuzzleType.valueOf(puzzle.optString("puzzleType", "SINGLE_DAY")) }.getOrDefault(PuzzleType.SINGLE_DAY),
                durationDays = puzzle.optInt("durationDays", 1), durationHours = puzzle.optInt("durationHours", 0), durationMinutes = puzzle.optInt("durationMinutes", 0)
            ))
        }
    }

    private fun parsePuzzleEntries(raw: String): List<PuzzleEntry> = runCatching {
        JSONArray(raw).let { array -> List(array.length()) { i ->
            array.getJSONObject(i).let { entry ->
                val entryId = entry.optString("id").takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()
                val categoryKey = entry.optString("categoryKey", "other").ifBlank { "other" }
                val priority = runCatching { TaskPriority.valueOf(entry.optString("priority", "NONE")) }.getOrDefault(TaskPriority.NONE)
                if (entry.has("offsetMinutes")) PuzzleEntry(entry.optInt("offsetMinutes"), entry.optInt("durationMinutes", 60), entry.getString("name"), entry.optString("description"), entryId, categoryKey, priority)
                else PuzzleEntry(0, 60, entry.getString("name"), entry.optString("description"), entryId, categoryKey, priority)
            }
        } }
    }.getOrDefault(emptyList())

    companion object {
        const val DEFAULT_SCHEDULE_ID = "default"
        private const val SAMPLE_BALANCED_ID = "sample-balanced-day"
        private const val SAMPLE_WORK_ID = "sample-work"; private const val SAMPLE_STUDY_ID = "sample-study"; private const val SAMPLE_HEALTH_ID = "sample-health"
        private val defaultCategorySpecs = listOf(Triple("work", "工作", "💼"), Triple("study", "學習", "📚"), Triple("health", "健康", "🏃"), Triple("life", "生活", "🏠"), Triple("finance", "財務", "💰"), Triple("other", "其他", "✨"))
    }
}
