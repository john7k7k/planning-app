package com.reverseplan.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.reverseplan.app.data.*
import com.reverseplan.app.domain.*
import kotlinx.coroutines.Job
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
    val monthlyStats: MonthlyStats? = null,
    val tasks: List<TaskEntity> = emptyList(),
    val categories: List<TaskCategoryEntity> = emptyList(),
    val schedules: List<ScheduleEntity> = emptyList(),
    val puzzles: List<SchedulePuzzleEntity> = emptyList(),
    val shop: List<ShopCardModel> = emptyList(),
    val transactions: List<TransactionEntity> = emptyList(),
    val error: String? = null,
    val message: String? = null,
    val timeConflicts: List<String> = emptyList(),
    val isDateLoading: Boolean = false
)

class MissionViewModel(private val repo: MissionRepository) : ViewModel() {
    private val _state = MutableStateFlow(MissionUiState())
    val state: StateFlow<MissionUiState> = _state.asStateFlow()
    private val activeScheduleId = MutableStateFlow(MissionRepository.DEFAULT_SCHEDULE_ID)
    private data class DailyCacheValue(val dashboard: DashboardData, val shop: List<ShopCardModel>)
    private val dailyCache = mutableMapOf<String, DailyCacheValue>()
    private var dateQueryJob: Job? = null
    private var prefetchJob: Job? = null

    init {
        viewModelScope.launch { repo.initialize() }
        viewModelScope.launch {
            repo.activeSchedule().filterNotNull().collect { settings ->
                activeScheduleId.value = settings.activeScheduleId
                _state.update { it.copy(activeScheduleId = settings.activeScheduleId, currentDashboard = null) }
                refresh()
                if (_state.value.selectedDate != LocalDate.now()) refreshCurrentSchedule(settings.activeScheduleId)
                loadMonth(_state.value.selectedMonth)
            }
        }
        viewModelScope.launch { activeScheduleId.flatMapLatest { repo.tasks(it) }.collect { tasks -> _state.update { it.copy(tasks = tasks) } } }
        viewModelScope.launch { activeScheduleId.flatMapLatest { repo.categories(it) }.collect { categories -> _state.update { it.copy(categories = categories) } } }
        viewModelScope.launch { repo.puzzles().collect { puzzles -> _state.update { it.copy(puzzles = puzzles) } } }
        viewModelScope.launch { activeScheduleId.flatMapLatest { repo.transactions(it) }.collect { transactions -> _state.update { it.copy(transactions = transactions) } } }
        viewModelScope.launch { repo.schedules().collect { schedules -> _state.update { it.copy(schedules = schedules) } } }
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
    fun refresh(date: LocalDate = _state.value.selectedDate) = viewModelScope.launch {
        dailyCache.clear()
        dateQueryJob?.cancel()
        prefetchJob?.cancel()
        val scheduleId = activeScheduleId.value
        runCatching { repo.dashboard(date, scheduleId) to repo.shopCards(scheduleId, date) }
            .onSuccess { (dashboard, shop) -> _state.update { current ->
                if (current.selectedDate != date || current.activeScheduleId != scheduleId) current
                else current.copy(dashboard = dashboard, shop = shop, currentDashboard = if (date == LocalDate.now()) dashboard else current.currentDashboard, error = null, isDateLoading = false)
            } }
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

    fun selectSchedule(id: String) = viewModelScope.launch { runCatching { repo.selectSchedule(id) }.onSuccess { say("已切換行程") }.onFailure(::fail) }
    fun createSchedule(name: String) = viewModelScope.launch { runCatching { repo.createSchedule(name) }.onSuccess { say("行程已新增") }.onFailure(::fail) }
    fun renameSchedule(id: String, name: String) = viewModelScope.launch { runCatching { repo.renameSchedule(id, name) }.onSuccess { say("行程名稱已更新") }.onFailure(::fail) }
    fun deleteSchedule(id: String) = viewModelScope.launch { runCatching { repo.deleteSchedule(id) }.onSuccess { say("自訂行程已刪除") }.onFailure(::fail) }
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
    fun validateTime(date: String, start: String, end: String, taskId: String?, instanceId: String?) = viewModelScope.launch { _state.update { it.copy(timeConflicts = repo.timeConflicts(activeScheduleId.value, date, start, end, taskId, instanceId)) } }
    fun settle(instanceId: String, progress: BigDecimal, result: String, checkedItems: Set<Int>) = viewModelScope.launch { repo.settle(instanceId, progress, result, checkedItems).onSuccess { (coins, diamonds) -> say("結算完成：🪙 $coins　💎 $diamonds"); refresh(); loadMonth(_state.value.selectedMonth) }.onFailure(::fail) }

    fun saveItem(item: ShopItemEntity, prerequisites: List<String> = emptyList(), minimums: Map<String, BigDecimal> = emptyMap()) = viewModelScope.launch { runCatching { repo.saveItem(item.copy(scheduleId = activeScheduleId.value), prerequisites, minimums) }.onSuccess { say("商品已儲存"); refresh() }.onFailure(::fail) }
    fun deleteItem(item: ShopItemEntity) = viewModelScope.launch { runCatching { repo.deleteItem(item) }.onSuccess { say("商品已刪除"); refresh() }.onFailure(::fail) }
    fun exchange(id: String, date: String, startTime: String, endTime: String, note: String) = viewModelScope.launch {
        runCatching {
            val scheduledAt = if (date.isBlank() && startTime.isBlank()) null else LocalDate.parse(date.ifBlank { LocalDate.now().toString() }).atTime(LocalTime.parse(startTime.ifBlank { "00:00" })).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            val scheduledEndAt = scheduledAt?.let { val day = LocalDate.parse(date.ifBlank { LocalDate.now().toString() }); val start = LocalTime.parse(startTime.ifBlank { "00:00" }); val end = if (endTime.isBlank()) { if (start.hour >= 23) LocalTime.of(23, 59) else start.plusHours(1) } else LocalTime.parse(endTime); day.atTime(end).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() }
            repo.exchange(activeScheduleId.value, id, scheduledAt, scheduledEndAt, note).getOrThrow()
        }.onSuccess { say("兌換成功，獎勵已安排"); refresh() }.onFailure(::fail)
    }
    fun updateRewardInstance(id: String, date: String, start: String, end: String, note: String) = viewModelScope.launch { runCatching { val day = LocalDate.parse(date); repo.updateRewardInstance(id, day.atTime(LocalTime.parse(start)).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(), day.atTime(LocalTime.parse(end)).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(), note) }.onSuccess { say("獎勵日程已更新"); refresh() }.onFailure(::fail) }
    fun deleteRewardInstance(id: String) = viewModelScope.launch { runCatching { repo.deleteRewardInstance(id) }.onSuccess { say("獎勵已取消，貨幣已退回"); refresh() }.onFailure(::fail) }
    fun adjust(coins: BigDecimal, diamonds: BigDecimal, note: String) = viewModelScope.launch { runCatching { repo.adjust(activeScheduleId.value, coins, diamonds, note) }.onSuccess { say("錢包已調整"); refresh() }.onFailure(::fail) }
    fun consumeMessage() = _state.update { it.copy(message = null, error = null) }
    private fun say(text: String) = _state.update { it.copy(message = text, error = null) }
    private fun fail(error: Throwable) = _state.update { it.copy(error = error.message ?: "發生未知錯誤") }
}

class MissionViewModelFactory(private val repo: MissionRepository) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T = MissionViewModel(repo) as T
}
