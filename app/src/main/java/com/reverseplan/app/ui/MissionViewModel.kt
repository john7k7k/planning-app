package com.reverseplan.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.reverseplan.app.TaskNotificationScheduler
import com.reverseplan.app.data.*
import com.reverseplan.app.domain.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId

data class MissionUiState(
    val selectedDate: LocalDate = LocalDate.now(),
    val selectedMonth: YearMonth = YearMonth.now(),
    val activeScheduleId: String = MissionRepository.DEFAULT_SCHEDULE_ID,
    val dashboard: DashboardData? = null,
    /** Always represents today's current schedule, independent of the date being browsed. */
    val currentDashboard: DashboardData? = null,
    val calendarPriorityMonth: YearMonth? = null,
    val calendarPriorityTasks: List<CalendarPriorityTask> = emptyList(),
    val monthlyStats: MonthlyStats? = null,
    val tasks: List<TaskEntity> = emptyList(),
    val categories: List<TaskCategoryEntity> = emptyList(),
    val schedules: List<ScheduleEntity> = emptyList(),
    val puzzles: List<SchedulePuzzleEntity> = emptyList(),
    val dailySummaries: List<DailySummaryEntity> = emptyList(),
    val shop: List<ShopCardModel> = emptyList(),
    val transactions: List<TransactionEntity> = emptyList(),
    val hasSecretKey: Boolean = false,
    val secretRecordCount: Int = 0,
    val error: String? = null,
    val message: String? = null,
    val timeConflicts: List<String> = emptyList(),
    val isDateLoading: Boolean = false
)

class MissionViewModel(
    private val repo: MissionRepository,
    private val taskNotificationScheduler: TaskNotificationScheduler
) : ViewModel() {
    private val _state = MutableStateFlow(MissionUiState())
    val state: StateFlow<MissionUiState> = _state.asStateFlow()
    private val activeScheduleId = MutableStateFlow(MissionRepository.DEFAULT_SCHEDULE_ID)
    private data class DailyCacheValue(val dashboard: DashboardData, val shop: List<ShopCardModel>)
    private val dailyCache = mutableMapOf<String, DailyCacheValue>()
    private var dateQueryJob: Job? = null
    private var prefetchJob: Job? = null
    private var overwritePreviewJob: Job? = null
    private var librarySchedulePreviewJob: Job? = null
    private var calendarPriorityJob: Job? = null
    private var secretCryptoJob: Job? = null
    /** Kept only in memory for the current app session; never written to storage. */
    private val secretKeySessions = mutableMapOf<String, String>()

    init {
        viewModelScope.launch { repo.initialize() }
        viewModelScope.launch {
            repo.activeSchedule().filterNotNull().collect { settings ->
                activeScheduleId.value = settings.activeScheduleId
                val hasSecretKey = repo.hasSecretKey(settings.activeScheduleId)
                _state.update { it.copy(activeScheduleId = settings.activeScheduleId, hasSecretKey = hasSecretKey, currentDashboard = null, calendarPriorityMonth = null, calendarPriorityTasks = emptyList()) }
                refresh()
                if (_state.value.selectedDate != LocalDate.now()) refreshCurrentSchedule(settings.activeScheduleId)
                loadMonth(_state.value.selectedMonth)
            }
        }
        viewModelScope.launch { activeScheduleId.flatMapLatest { repo.tasks(it) }.collect { tasks -> _state.update { it.copy(tasks = tasks) } } }
        viewModelScope.launch { activeScheduleId.flatMapLatest { repo.categories(it) }.collect { categories -> _state.update { it.copy(categories = categories) } } }
        viewModelScope.launch { repo.puzzles().collect { puzzles -> _state.update { it.copy(puzzles = puzzles) } } }
        viewModelScope.launch { activeScheduleId.flatMapLatest { repo.dailySummaries(it) }.collect { summaries -> _state.update { it.copy(dailySummaries = summaries) } } }
        viewModelScope.launch { activeScheduleId.flatMapLatest { repo.transactions(it) }.collect { transactions -> _state.update { it.copy(transactions = transactions) } } }
        viewModelScope.launch { repo.schedules().collect { schedules ->
            _state.update { current ->
                val active = schedules.firstOrNull { it.id == current.activeScheduleId }
                current.copy(
                    schedules = schedules,
                    hasSecretKey = active?.let { it.secretRecordsEnabled && it.secretKeySalt.isNotBlank() && it.secretKeyVerifier.isNotBlank() } == true
                )
            }
        } }
        refresh()
        loadMonth(YearMonth.now())
    }

    fun loadDate(date: LocalDate) {
        val scheduleId = activeScheduleId.value
        val cached = dailyCache[cacheKey(scheduleId, date)]
        dateQueryJob?.cancel()
        prefetchJob?.cancel()
        _state.update {
            if (cached == null) it.copy(selectedDate = date, dashboard = null, shop = emptyList(), timeConflicts = emptyList(), isDateLoading = true)
            else it.copy(selectedDate = date, dashboard = cached.dashboard, shop = cached.shop, timeConflicts = emptyList(), isDateLoading = false)
        }
        if (cached == null) dateQueryJob = loadDateFromRepository(date, scheduleId) else prefetchNearbyDates(date, scheduleId)
    }
    fun loadMonth(month: YearMonth) = viewModelScope.launch {
        _state.update { it.copy(selectedMonth = month) }
        runCatching { repo.monthlyStats(activeScheduleId.value, month) }
            .onSuccess { stats -> _state.update { it.copy(monthlyStats = stats) } }
            .onFailure(::fail)
    }
    fun loadCalendarPriorityTasks(month: YearMonth) {
        if (_state.value.calendarPriorityMonth == month) return
        calendarPriorityJob?.cancel()
        val scheduleId = activeScheduleId.value
        calendarPriorityJob = viewModelScope.launch {
            runCatching { repo.calendarPriorityTasks(scheduleId, month) }
                .onSuccess { tasks ->
                    _state.update { current ->
                        if (current.activeScheduleId == scheduleId) current.copy(calendarPriorityMonth = month, calendarPriorityTasks = tasks) else current
                    }
                }
                .onFailure(::fail)
        }
    }
    fun refresh(date: LocalDate = _state.value.selectedDate) = viewModelScope.launch {
        dailyCache.clear()
        dateQueryJob?.cancel()
        prefetchJob?.cancel()
        calendarPriorityJob?.cancel()
        _state.update { it.copy(calendarPriorityMonth = null, calendarPriorityTasks = emptyList()) }
        val scheduleId = activeScheduleId.value
        runCatching { Triple(repo.dashboard(date, scheduleId), repo.shopCards(scheduleId, date), repo.secretRecordCount(scheduleId)) }
            .onSuccess { (dashboard, shop, secretRecordCount) -> _state.update { current ->
                if (current.selectedDate != date || current.activeScheduleId != scheduleId) current
                else current.copy(dashboard = dashboard, shop = shop, secretRecordCount = secretRecordCount, currentDashboard = if (date == LocalDate.now()) dashboard else current.currentDashboard, error = null, isDateLoading = false)
                }
                rescheduleTaskNotifications(scheduleId)
            }
            .onFailure { error ->
                _state.update { current -> if (current.selectedDate == date && current.activeScheduleId == scheduleId) current.copy(isDateLoading = false, error = error.message ?: "發生未知錯誤") else current }
            }
        prefetchNearbyDates(date, scheduleId)
    }

    private fun loadDateFromRepository(date: LocalDate, scheduleId: String) = viewModelScope.launch {
        runCatching { DailyCacheValue(repo.dashboard(date, scheduleId), repo.shopCards(scheduleId, date)) }
            .onSuccess { value ->
                dailyCache[cacheKey(scheduleId, date)] = value
                _state.update { current ->
                    if (current.selectedDate != date || current.activeScheduleId != scheduleId) current
                    else current.copy(dashboard = value.dashboard, shop = value.shop, currentDashboard = if (date == LocalDate.now()) value.dashboard else current.currentDashboard, error = null, isDateLoading = false)
                }
                prefetchNearbyDates(date, scheduleId)
            }
            .onFailure { error -> _state.update { current -> if (current.selectedDate == date && current.activeScheduleId == scheduleId) current.copy(isDateLoading = false, error = error.message ?: "發生未知錯誤") else current } }
    }

    private fun refreshCurrentSchedule(scheduleId: String) = viewModelScope.launch {
        val today = LocalDate.now()
        runCatching { repo.dashboard(today, scheduleId) }.onSuccess { dashboard ->
            _state.update { current -> if (current.activeScheduleId == scheduleId) current.copy(currentDashboard = dashboard) else current }
        }
    }

    private fun prefetchNearbyDates(date: LocalDate, scheduleId: String) {
        prefetchJob?.cancel()
        prefetchJob = viewModelScope.launch {
            // Let the user's next tap take priority over cache work.
            delay(250)
            for (nearbyDate in listOf(date.minusDays(1), date.plusDays(1))) {
                val key = cacheKey(scheduleId, nearbyDate)
                if (!dailyCache.containsKey(key)) {
                    runCatching { DailyCacheValue(repo.dashboard(nearbyDate, scheduleId), repo.shopCards(scheduleId, nearbyDate)) }
                        .onSuccess { dailyCache[key] = it }
                }
            }
        }
    }

    private fun cacheKey(scheduleId: String, date: LocalDate) = "$scheduleId|$date"

    private fun rescheduleTaskNotifications(scheduleId: String = activeScheduleId.value) = viewModelScope.launch {
        runCatching { taskNotificationScheduler.scheduleUpcoming(scheduleId) }
    }
    fun rescheduleNotifications() = rescheduleTaskNotifications()

    fun selectSchedule(id: String) = viewModelScope.launch { runCatching { repo.selectSchedule(id) }.onSuccess { say("已切換行程") }.onFailure(::fail) }
    fun createSchedule(name: String) = viewModelScope.launch { runCatching { repo.createSchedule(name) }.onSuccess { say("行程已新增") }.onFailure(::fail) }
    fun renameSchedule(id: String, name: String) = viewModelScope.launch { runCatching { repo.renameSchedule(id, name) }.onSuccess { say("行程名稱已更新") }.onFailure(::fail) }
    fun deleteSchedule(id: String) = viewModelScope.launch { runCatching { repo.deleteSchedule(id) }.onSuccess { say("自訂行程已刪除") }.onFailure(::fail) }
    fun exportSchedule(includeCompletionData: Boolean, onReady: (String, String) -> Unit) = viewModelScope.launch {
        runCatching {
            val schedule = _state.value.schedules.firstOrNull { it.id == activeScheduleId.value } ?: error("找不到行程")
            schedule.name to repo.exportSchedule(schedule.id, includeCompletionData)
        }.onSuccess { (name, json) -> onReady(name, json) }.onFailure(::fail)
    }

    fun exportSchedule(onReady: (String, String) -> Unit) = viewModelScope.launch {
        runCatching { val schedule = _state.value.schedules.firstOrNull { it.id == activeScheduleId.value } ?: error("找不到行程"); schedule.name to repo.exportSchedule(schedule.id) }
            .onSuccess { (name, json) -> onReady(name, json) }.onFailure(::fail)
    }
    fun importSchedule(json: String) = viewModelScope.launch {
        runCatching { repo.importSchedule(json) }.onSuccess { schedule -> repo.selectSchedule(schedule.id); say("行程已匯入並切換") }.onFailure(::fail)
    }

    fun savePuzzle(puzzle: SchedulePuzzleEntity, updateAppliedInstances: Boolean = false) = viewModelScope.launch {
        runCatching { repo.savePuzzle(puzzle.copy(scheduleId = MissionRepository.DEFAULT_SCHEDULE_ID), updateAppliedInstances) }
            .onSuccess { say(if (updateAppliedInstances) "拼圖與未完成的已套用項目已更新" else "行程拼圖已儲存"); refresh() }
            .onFailure(::fail)
    }
    fun deletePuzzle(puzzle: SchedulePuzzleEntity, deleteAppliedInstances: Boolean = false) = viewModelScope.launch {
        runCatching { repo.deletePuzzle(puzzle, deleteAppliedInstances) }
            .onSuccess { say(if (deleteAppliedInstances) "行程拼圖與已套用實例已刪除" else "行程拼圖已刪除"); refresh() }
            .onFailure(::fail)
    }
    fun previewPuzzle(puzzle: SchedulePuzzleEntity, date: String, time: String, onReady: (PuzzleApplicationPreview) -> Unit, onFailure: (String) -> Unit) = viewModelScope.launch {
        runCatching { repo.puzzleOverwritePreview(puzzle.id, activeScheduleId.value, date, time) }.onSuccess(onReady).onFailure { onFailure(it.message ?: "無法分析覆寫日程") }
    }
    fun applyPuzzle(puzzle: SchedulePuzzleEntity, date: String, time: String) = viewModelScope.launch { runCatching { repo.applyPuzzle(puzzle.id, activeScheduleId.value, date, time) }.onSuccess { skipped -> say(if (skipped.isEmpty()) "行程拼圖已套用到時間軸" else "已套用拼圖，略過 ${skipped.size} 個超出當天的項目"); refresh() }.onFailure(::fail) }
    fun exportPuzzles(onReady: (String) -> Unit) = viewModelScope.launch { runCatching { repo.exportPuzzles() }.onSuccess(onReady).onFailure(::fail) }
    fun importPuzzles(json: String) = viewModelScope.launch { runCatching { repo.importPuzzles(json) }.onSuccess { say("行程拼圖已匯入") }.onFailure(::fail) }

    fun saveTask(task: TaskEntity, prerequisites: List<String> = emptyList(), minimums: Map<String, BigDecimal> = emptyMap()) = viewModelScope.launch {
        runCatching { repo.saveTask(task.copy(scheduleId = activeScheduleId.value), prerequisites, minimums) }.onSuccess { say("任務已儲存"); refresh(); loadMonth(_state.value.selectedMonth) }.onFailure(::fail)
    }
    fun addTimelineTask(task: TaskEntity) = viewModelScope.launch {
        runCatching { repo.createTimelineTask(task.copy(scheduleId = activeScheduleId.value)) }.onSuccess { say("單次任務已加入時間軸"); refresh(); loadMonth(_state.value.selectedMonth) }.onFailure(::fail)
    }
    fun previewTimelineOverwrite(date: String, start: String, end: String, onReady: (TimelineOverwritePreview) -> Unit, onFailure: (String) -> Unit, excludeTaskId: String? = null) {
        overwritePreviewJob?.cancel()
        val scheduleId = activeScheduleId.value
        overwritePreviewJob = viewModelScope.launch {
            try {
                onReady(repo.timelineOverwritePreview(scheduleId, date, start, end, excludeTaskId))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                onFailure(error.message ?: "無法檢查時間衝突")
            }
        }
    }
    fun addTimelineTaskWithOverwrite(task: TaskEntity, strategy: TimelineOverwriteStrategy) = viewModelScope.launch {
        runCatching { repo.createTimelineTaskWithOverwrite(task.copy(scheduleId = activeScheduleId.value), strategy) }
            .onSuccess { say("單次任務已加入時間軸並套用覆寫"); refresh(); loadMonth(_state.value.selectedMonth) }
            .onFailure(::fail)
    }
    fun saveTaskWithOverwrite(task: TaskEntity, strategy: TimelineOverwriteStrategy) = viewModelScope.launch {
        runCatching { repo.saveTaskWithOverwrite(task.copy(scheduleId = activeScheduleId.value), strategy) }
            .onSuccess { say("任務已儲存並套用覆寫"); refresh(); loadMonth(_state.value.selectedMonth) }
            .onFailure(::fail)
    }
    fun previewTaskLibrarySchedule(task: TaskEntity, previewDays: Int, onReady: (TaskLibrarySchedulePreview) -> Unit, onFailure: (String) -> Unit) {
        librarySchedulePreviewJob?.cancel()
        val scheduleId = activeScheduleId.value
        librarySchedulePreviewJob = viewModelScope.launch {
            try {
                onReady(repo.taskLibrarySchedulePreview(task.copy(scheduleId = scheduleId), previewDays))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                onFailure(error.message ?: "無法預覽任務實例")
            }
        }
    }
    fun deleteTask(task: TaskEntity) = viewModelScope.launch { runCatching { repo.deleteTask(task) }.onSuccess { say("任務已刪除"); refresh(); loadMonth(_state.value.selectedMonth) }.onFailure(::fail) }
    fun saveCategory(name: String, icon: String) = viewModelScope.launch { runCatching { repo.saveCategory(activeScheduleId.value, name, icon) }.onSuccess { say("分類已新增") }.onFailure(::fail) }
    fun updateCategory(category: TaskCategoryEntity, name: String, icon: String) = viewModelScope.launch { runCatching { repo.updateCategory(activeScheduleId.value, category, name, icon) }.onSuccess { say("分類已更新"); refresh() }.onFailure(::fail) }
    fun deleteCategory(category: TaskCategoryEntity) = viewModelScope.launch { runCatching { repo.deleteCategory(activeScheduleId.value, category) }.onSuccess { say("分類已刪除，相關任務已改用其他分類"); refresh() }.onFailure(::fail) }

    fun updateInstance(instanceId: String, name: String, description: String, location: String, address: String, allDay: Boolean, start: String, end: String, coins: BigDecimal, diamonds: BigDecimal, categoryId: String, priority: TaskPriority, checklist: String) = viewModelScope.launch {
        runCatching { repo.updateInstanceContent(instanceId, name, description, location, address, allDay, start, end, coins, diamonds, categoryId, priority, checklist) }.onSuccess { say("日程任務已更新"); refresh(); loadMonth(_state.value.selectedMonth) }.onFailure(::fail)
    }
    fun deleteInstance(instanceId: String) = viewModelScope.launch { runCatching { repo.deleteInstance(instanceId) }.onSuccess { say("日程任務已刪除"); refresh(); loadMonth(_state.value.selectedMonth) }.onFailure(::fail) }
    fun undoSettlement(instanceId: String, onSuccess: () -> Unit = {}, onFailure: (String) -> Unit = {}) = viewModelScope.launch {
        repo.undoSettlement(instanceId).onSuccess { (coins, diamonds) -> say("已撤回完成，歸還 🪙 $coins　💎 $diamonds"); refresh(); loadMonth(_state.value.selectedMonth); onSuccess() }.onFailure { onFailure(it.message ?: "撤回失敗") }
    }
    fun updateSettledResult(instanceId: String, result: String) = viewModelScope.launch { runCatching { repo.updateSettledResult(instanceId, result) }.onSuccess { say("任務心得已儲存"); refresh() }.onFailure(::fail) }
    fun updateSecretKey(currentKey: String, newKey: String, onSuccess: () -> Unit, onFailure: (String) -> Unit) = viewModelScope.launch {
        val scheduleId = activeScheduleId.value
        runCatching { repo.updateSecretKey(scheduleId, currentKey, newKey) }
            .onSuccess { secretKeySessions[scheduleId] = newKey; onSuccess() }
            .onFailure { onFailure(it.message ?: "無法更新秘密金鑰") }
    }
    fun readSecretRecord(instanceId: String, key: String, onSuccess: (String) -> Unit, onFailure: (String) -> Unit) = viewModelScope.launch {
        secretCryptoJob?.cancel()
        secretCryptoJob = viewModelScope.launch {
            try {
                val record = repo.readSecretRecord(instanceId, key)
                secretKeySessions[activeScheduleId.value] = key
                onSuccess(record)
            } catch (_: CancellationException) {
                // The dialog has already returned to its idle state.
            } catch (error: Throwable) {
                onFailure(error.message ?: "無法開啟秘密紀錄")
            }
        }
    }
    fun saveSecretRecord(instanceId: String, content: String, onSuccess: () -> Unit, onFailure: (String) -> Unit) = viewModelScope.launch {
        val key = secretKeySessions[activeScheduleId.value]
            ?: return@launch onFailure("請先驗證秘密金鑰")
        secretCryptoJob?.cancel()
        secretCryptoJob = viewModelScope.launch {
            try {
                repo.saveSecretRecord(instanceId, key, content)
                onSuccess()
                refresh()
            } catch (_: CancellationException) {
                // The dialog has already returned to its idle state.
            } catch (error: Throwable) {
                onFailure(error.message ?: "無法儲存秘密紀錄")
            }
        }
    }
    fun cancelSecretOperation() { secretCryptoJob?.cancel(); secretCryptoJob = null }
    fun clearSecretRecords(onSuccess: () -> Unit, onFailure: (String) -> Unit) = viewModelScope.launch {
        val scheduleId = activeScheduleId.value
        runCatching { repo.clearSecretRecords(scheduleId) }
            .onSuccess { secretKeySessions.remove(scheduleId); onSuccess(); refresh() }
            .onFailure { onFailure(it.message ?: "無法清除秘密紀錄") }
    }
    fun saveDailySummary(date: LocalDate, content: String) = viewModelScope.launch { runCatching { repo.saveDailySummary(activeScheduleId.value, date, content) }.onSuccess { say(if (content.isBlank()) "當日總結已清除" else "當日總結已儲存") }.onFailure(::fail) }
    fun validateTime(date: String, start: String, end: String, taskId: String?, instanceId: String?) = viewModelScope.launch { _state.update { it.copy(timeConflicts = repo.timeConflicts(activeScheduleId.value, date, start, end, taskId, instanceId)) } }
    fun settle(instanceId: String, progress: BigDecimal, result: String, checkedItems: Set<Int>) = viewModelScope.launch { repo.settle(instanceId, progress, result, checkedItems).onSuccess { (coins, diamonds) -> say("結算完成：🪙 $coins　💎 $diamonds"); refresh(); loadMonth(_state.value.selectedMonth) }.onFailure(::fail) }

    fun saveItem(item: ShopItemEntity, prerequisites: List<String> = emptyList(), minimums: Map<String, BigDecimal> = emptyMap()) = viewModelScope.launch { runCatching { repo.saveItem(item.copy(scheduleId = activeScheduleId.value), prerequisites, minimums) }.onSuccess { say("商品已儲存"); refresh() }.onFailure(::fail) }
    fun deleteItem(item: ShopItemEntity) = viewModelScope.launch { runCatching { repo.deleteItem(item) }.onSuccess { say("商品已刪除"); refresh() }.onFailure(::fail) }
    fun exchange(id: String, date: String, startTime: String, endTime: String, note: String) = viewModelScope.launch {
        runCatching {
            val (scheduledAt, scheduledEndAt) = rewardScheduleTimes(date, startTime, endTime)
            repo.exchange(activeScheduleId.value, id, scheduledAt, scheduledEndAt, note).getOrThrow()
        }.onSuccess { say("兌換成功，獎勵已安排"); refresh() }.onFailure(::fail)
    }
    fun exchangeWithOverwrite(id: String, date: String, startTime: String, endTime: String, note: String, strategy: TimelineOverwriteStrategy) = viewModelScope.launch {
        runCatching {
            val (scheduledAt, scheduledEndAt) = rewardScheduleTimes(date, startTime, endTime)
            repo.exchangeWithOverwrite(activeScheduleId.value, id, scheduledAt, scheduledEndAt, note, strategy).getOrThrow()
        }.onSuccess { say("兌換成功，已覆寫衝突日程並退款舊獎勵"); refresh() }.onFailure(::fail)
    }
    fun updateRewardInstance(id: String, date: String, start: String, end: String, note: String) = viewModelScope.launch { runCatching { val day = LocalDate.parse(date); repo.updateRewardInstance(id, day.atTime(LocalTime.parse(start)).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(), day.atTime(LocalTime.parse(end)).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(), note) }.onSuccess { say("獎勵日程已更新"); refresh() }.onFailure(::fail) }
    fun deleteRewardInstance(id: String) = viewModelScope.launch { runCatching { repo.deleteRewardInstance(id) }.onSuccess { say("獎勵已取消，貨幣已退回"); refresh() }.onFailure(::fail) }
    fun adjust(coins: BigDecimal, diamonds: BigDecimal, note: String) = viewModelScope.launch { runCatching { repo.adjust(activeScheduleId.value, coins, diamonds, note) }.onSuccess { say("錢包已調整"); refresh() }.onFailure(::fail) }
    fun consumeMessage() = _state.update { it.copy(message = null, error = null) }
    private fun say(text: String) = _state.update { it.copy(message = text, error = null) }
    private fun fail(error: Throwable) = _state.update { it.copy(error = error.message ?: "發生未知錯誤") }

    private fun rewardScheduleTimes(dateText: String, startText: String, endText: String): Pair<Long?, Long?> {
        if (startText.isBlank()) return null to null
        val day = LocalDate.parse(dateText.ifBlank { LocalDate.now().toString() })
        val start = LocalTime.parse(startText)
        val end = when {
            endText.isBlank() -> if (start.hour >= 23) LocalTime.of(23, 59) else start.plusHours(1)
            endText == "24:00" -> null
            else -> LocalTime.parse(endText)
        }
        val startAt = day.atTime(start).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val endAt = if (end == null) day.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        else day.atTime(end).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        return startAt to endAt
    }
}

class MissionViewModelFactory(
    private val repo: MissionRepository,
    private val taskNotificationScheduler: TaskNotificationScheduler
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T = MissionViewModel(repo, taskNotificationScheduler) as T
}
