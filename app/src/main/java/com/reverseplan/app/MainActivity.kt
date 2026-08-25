package com.reverseplan.app

import android.os.Bundle
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.viewModels
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.zIndex
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.content.ContextCompat
import com.reverseplan.app.data.*
import com.reverseplan.app.domain.*
import com.reverseplan.app.ui.MissionUiState
import com.reverseplan.app.ui.MissionViewModel
import com.reverseplan.app.ui.MissionViewModelFactory
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay
import kotlin.math.roundToInt
import org.json.JSONArray
import org.json.JSONObject

private val Ink = Color(0xFF171A2A)
private val Violet = Color(0xFF6C63FF)
private val Gold = Color(0xFFFFC145)
private val Diamond = Color(0xFF4CC9F0)
private val SoftBackground = Color(0xFFF7F7FC)

private fun completionText(value: BigDecimal): String = value
    .setScale(2, RoundingMode.HALF_UP)
    .stripTrailingZeros()
    .toPlainString()

class MainActivity : ComponentActivity() {
    private val vm: MissionViewModel by viewModels {
        (application as MissionApp).let { MissionViewModelFactory(it.repository, it.taskNotificationScheduler) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MissionAppUi(vm) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MissionAppUi(vm: MissionViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val notificationPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var showDatePicker by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    val tabs = listOf(
        "首頁" to Icons.Default.Home,
        "任務庫" to Icons.Default.Checklist,
        "商城" to Icons.Default.Storefront,
        "紀錄" to Icons.Default.ReceiptLong,
        "設定" to Icons.Default.Settings
    )
    MaterialTheme(colorScheme = lightColorScheme(primary = Violet, secondary = Gold, background = SoftBackground)) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(tabs[tab].first, fontWeight = FontWeight.Bold) },
                    actions = {
                        val wallet = state.dashboard?.wallet
                        Text("🪙 ${wallet?.coins ?: 0}", color = Gold, fontWeight = FontWeight.Bold)
                        Text("  💎 ${wallet?.diamonds ?: 0}", color = Diamond, fontWeight = FontWeight.Bold, modifier = Modifier.padding(end = 12.dp))
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Ink, titleContentColor = Color.White)
                )
            },
            bottomBar = {
                NavigationBar {
                    tabs.forEachIndexed { index, (label, icon) ->
                        NavigationBarItem(
                            selected = tab == index,
                            onClick = { tab = index },
                            icon = { Icon(icon, label) },
                            label = { Text(label) }
                        )
                    }
                }
            }
        ) { padding ->
            Box(Modifier.padding(padding)) {
                when (tab) {
                    0 -> Dashboard(state, vm) { showDatePicker = true }
                    1 -> TaskLibrary(state, vm)
                    2 -> Shop(state, vm)
                    3 -> MonthlySummary(state, vm)
                    else -> Settings(state, vm)
                }
                state.error?.let { Notice(it, error = true, vm::consumeMessage) }
                state.message?.let { Notice(it, error = false, vm::consumeMessage) }
            }
        }
        if (showDatePicker) HomeDatePicker(state.selectedDate, { showDatePicker = false }) { date ->
            vm.loadDate(date)
            showDatePicker = false
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeDatePicker(initialDate: LocalDate, dismiss: () -> Unit, select: (LocalDate) -> Unit) {
    val pickerState = rememberDatePickerState(initialSelectedDateMillis = initialDate.toEpochDay() * 86_400_000L)
    DatePickerDialog(
        onDismissRequest = dismiss,
        confirmButton = { Button(onClick = { pickerState.selectedDateMillis?.let { select(LocalDate.ofEpochDay(it / 86_400_000L)) } }, enabled = pickerState.selectedDateMillis != null) { Text("選擇日期") } },
        dismissButton = { TextButton(dismiss) { Text("取消") } }
    ) { DatePicker(state = pickerState, title = null, headline = null) }
}

@Composable
private fun Notice(text: String, error: Boolean, close: () -> Unit) {
    LaunchedEffect(text, error) {
        delay(3_000)
        close()
    }
    Surface(
        color = if (error) MaterialTheme.colorScheme.errorContainer else Color(0xFFDDF8E9),
        modifier = Modifier.fillMaxWidth().padding(12.dp),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, Modifier.weight(1f))
            IconButton(close) { Icon(Icons.Default.Close, "關閉") }
        }
    }
}

private sealed interface TimelineEntry { val time: String; val key: String }
private data class TaskEntry(val card: TaskCardModel) : TimelineEntry {
    override val time: String get() = card.task.startTime.ifBlank { "00:00" }
    override val key: String get() = "task-${card.instance.id}"
}
private data class RewardEntry(val reward: RewardSchedule) : TimelineEntry {
    override val time: String get() = reward.exchange.scheduledAt?.let {
        Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalTime().toString().take(5)
    } ?: "23:59"
    override val key: String get() = "reward-${reward.exchange.id}"
}
private data class CurrentTimeEntry(val currentTime: String) : TimelineEntry {
    override val time: String get() = currentTime
    override val key: String = "current-time"
}

@Composable
private fun Dashboard(state: MissionUiState, vm: MissionViewModel, chooseDate: () -> Unit) {
    var settling by remember { mutableStateOf<TaskCardModel?>(null) }
    var editing by remember { mutableStateOf<TaskCardModel?>(null) }
    var reviewing by remember { mutableStateOf<TaskCardModel?>(null) }
    var editingReward by remember { mutableStateOf<RewardSchedule?>(null) }
    var quickAddInitial by remember { mutableStateOf<TaskEntity?>(null) }
    var viewingCompleted by remember { mutableStateOf(false) }
    var scrollToEntryKey by remember { mutableStateOf<String?>(null) }
    var currentTime by remember { mutableStateOf(LocalTime.now()) }
    val timelineListState = rememberLazyListState()
    val density = LocalDensity.current
    LaunchedEffect(Unit) {
        while (true) {
            currentTime = LocalTime.now()
            delay(30_000)
        }
    }
    val data = if (state.isDateLoading) null else state.dashboard
    val progressPreviewCards = data?.cards.orEmpty()
        .filter { card ->
            card.instance.settled || (
                state.selectedDate == LocalDate.now() &&
                    !card.instance.settled && card.task.priority == TaskPriority.RED
                )
        }
        .sortedBy { (it.instance.startTimeOverride ?: it.task.startTime).ifBlank { "99:99" } }
    val entries = remember(data, state.selectedDate, currentTime) {
        val isToday = state.selectedDate == LocalDate.now()
        val scheduledEntries = data?.cards.orEmpty().map(::TaskEntry) + data?.rewards.orEmpty().map(::RewardEntry)
        val currentEventIsVisible = isToday && (
            data?.cards?.any { inRange(it.task.startTime, it.task.endTime, currentTime) } == true ||
                data?.rewards?.any { rewardInRange(it, currentTime) } == true
            )
        (scheduledEntries +
            if (isToday && scheduledEntries.isNotEmpty() && !currentEventIsVisible) listOf(CurrentTimeEntry(currentTime.toString().take(5))) else emptyList()
        ).sortedBy { it.time }
    }
    LaunchedEffect(scrollToEntryKey, entries, state.isDateLoading) {
        val key = scrollToEntryKey ?: return@LaunchedEffect
        val entryIndex = entries.indexOfFirst { it.key == key }
        if (state.isDateLoading || entryIndex < 0) return@LaunchedEffect
        val viewportHeight = timelineListState.layoutInfo.let { it.viewportEndOffset - it.viewportStartOffset }
        val estimatedCardHeight = with(density) {
            when (val entry = entries[entryIndex]) {
                is TaskEntry -> if (entry.card.instance.settled) 150.dp.roundToPx() else 205.dp.roundToPx()
                is RewardEntry -> 96.dp.roundToPx()
                is CurrentTimeEntry -> 28.dp.roundToPx()
            }
        }
        val centeredOffset = -((viewportHeight - estimatedCardHeight).coerceAtLeast(0) / 2)
        timelineListState.animateScrollToItem(index = 3 + entryIndex, scrollOffset = centeredOffset)
        scrollToEntryKey = null
    }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize(),
            state = timelineListState,
            contentPadding = PaddingValues(vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("每日行程", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    TextButton({ vm.loadDate(LocalDate.now()) }) { Icon(Icons.Default.Today, null); Spacer(Modifier.width(4.dp)); Text("回到今天") }
                    TextButton(chooseDate) { Icon(Icons.Default.CalendarMonth, null); Spacer(Modifier.width(4.dp)); Text("選擇日期") }
                }
                Box(Modifier.padding(horizontal = 16.dp)) {
                    CalendarQuery(state.selectedDate, state.isDateLoading, vm::loadDate)
                }
                Box(Modifier.padding(horizontal = 16.dp)) {
                    CurrentScheduleCard(state.currentDashboard, currentTime) { card ->
                        scrollToEntryKey = TaskEntry(card).key
                        if (state.selectedDate != LocalDate.now()) vm.loadDate(LocalDate.now())
                    }
                }
            }
            if (state.isDateLoading) item {
                Box(Modifier.padding(top = 12.dp, start = 16.dp, end = 16.dp)) { DateLoadingCard() }
            } else item {
                val cards = data?.cards.orEmpty()
                val urgentCards = cards.filter { !it.instance.settled && it.task.priority == TaskPriority.RED }
                Card(modifier = Modifier.fillMaxWidth().padding(top = 12.dp, start = 16.dp, end = 16.dp).clickable(enabled = progressPreviewCards.isNotEmpty()) { viewingCompleted = true }) {
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                                Text(if (state.selectedDate == LocalDate.now()) "今天進度" else "當日進度", fontWeight = FontWeight.Bold)
                                if (state.selectedDate == LocalDate.now() && urgentCards.isNotEmpty()) {
                                    Text(
                                        "　${urgentCards.size} 項重要未完成",
                                        color = Color(0xFFD92D20),
                                        style = MaterialTheme.typography.labelMedium,
                                        maxLines = 1
                                    )
                                }
                            }
                            Text("本日獲得", style = MaterialTheme.typography.labelSmall)
                        }
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("${cards.count { it.instance.settled }} / ${cards.size} 項已結算", color = Color.Gray)
                            Spacer(Modifier.weight(1f))
                            Text("🪙 ${data?.earnedCoins ?: 0}　💎 ${data?.earnedDiamonds ?: 0}", color = Violet, maxLines = 1, softWrap = false)
                        }
                    }
                }
            }
            if (!state.isDateLoading) {
                item { Spacer(Modifier.height(32.dp)) }
                if (entries.isEmpty()) item { EmptyHint("這天尚未安排任務或獎勵。") }
                itemsIndexed(entries, key = { _, entry -> entry.key }) { _, entry ->
                    when (entry) {
                        is TaskEntry -> TimelineTask(
                            entry.card,
                            state.categories.firstOrNull { it.id == entry.card.task.categoryId },
                            currentTime = currentTime.takeIf { state.selectedDate == LocalDate.now() },
                            onSettle = { settling = entry.card },
                            onEdit = { if (entry.card.instance.settled) reviewing = entry.card else editing = entry.card }
                        )
                        is RewardEntry -> RewardTimelineItem(entry.reward, currentTime.takeIf { state.selectedDate == LocalDate.now() }) { editingReward = entry.reward }
                        is CurrentTimeEntry -> CurrentTimeTimelineMarker(entry.currentTime)
                    }
                }
                item {
                    val summary = state.dailySummaries.firstOrNull { it.date == state.selectedDate.toString() }?.content.orEmpty()
                    Box(Modifier.padding(top = 16.dp, start = 16.dp, end = 16.dp)) {
                        DailySummaryCard(state.selectedDate, summary) { vm.saveDailySummary(state.selectedDate, it) }
                    }
                }
            }
            item { Spacer(Modifier.height(92.dp)) }
        }
        if (!state.isDateLoading) ExtendedFloatingActionButton(
            onClick = { quickAddInitial = TaskEntity(name = "", startDate = state.selectedDate.toString(), timelineOnly = true) },
            icon = { Icon(Icons.Default.Add, "加入單次任務") },
            text = { Text("加入單次任務") },
            modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp)
        )
    }
    if (viewingCompleted) CompletedTasksDialog(
        cards = progressPreviewCards,
        categories = state.categories,
        dismiss = { viewingCompleted = false },
        openTask = { card ->
            viewingCompleted = false
            scrollToEntryKey = TaskEntry(card).key
        }
    )
    settling?.let { card ->
        SettleDialog(card, { settling = null }) { progress, result, checked ->
            vm.settle(card.instance.id, progress, result, checked)
            settling = null
        }
    }
    reviewing?.let { card ->
        TaskReviewDialog(
            card = card,
            dismiss = { reviewing = null },
            saveResult = { result -> vm.updateSettledResult(card.instance.id, result) },
            undo = { error -> vm.undoSettlement(card.instance.id, onSuccess = { reviewing = null }, onFailure = error) }
        )
    }
    editing?.let { card ->
        InstanceEditDialog(
            card = card,
            categories = state.categories,
            conflicts = state.timeConflicts,
            validate = { start, end -> vm.validateTime(card.instance.scheduledDate, start, end, card.task.id, card.instance.id) },
            dismiss = { editing = null },
            save = { name, description, location, address, allDay, start, end, coins, diamonds, categoryId, priority, checklist ->
                vm.updateInstance(card.instance.id, name, description, location, address, allDay, start, end, coins, diamonds, categoryId, priority, checklist)
                editing = null
            },
            delete = { vm.deleteInstance(card.instance.id); editing = null },
            undo = { showError -> vm.undoSettlement(card.instance.id, onSuccess = { editing = null }, onFailure = showError) }
        )
    }
    editingReward?.let { reward ->
        RewardInstanceEditDialog(
            reward,
            { editingReward = null },
            { date, start, end, note -> vm.updateRewardInstance(reward.exchange.id, date, start, end, note); editingReward = null },
            { vm.deleteRewardInstance(reward.exchange.id); editingReward = null }
        )
    }
    quickAddInitial?.let { initial ->
        TaskFormDialog(
            title = "加入時間軸單次任務",
            initial = initial,
            categories = state.categories,
            allowRepeat = false,
            dismiss = { quickAddInitial = null },
            save = { task ->
                vm.addTimelineTask(task.copy(timelineOnly = true, repeatType = RepeatType.NONE))
                quickAddInitial = null
            }
        )
    }
}

@Composable
private fun CompletedTasksDialog(cards: List<TaskCardModel>, categories: List<TaskCategoryEntity>, dismiss: () -> Unit, openTask: (TaskCardModel) -> Unit) {
    val urgentCards = cards.filter { !it.instance.settled && it.task.priority == TaskPriority.RED }
    val settledCards = cards.filter { it.instance.settled }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text("今天進度") },
        text = {
            if (cards.isEmpty()) Text("這天尚未有已結算的任務。", color = Color.Gray)
            else LazyColumn(Modifier.heightIn(max = 360.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (urgentCards.isNotEmpty()) {
                    item { Text("未完成的重要任務", color = Color(0xFFD92D20), fontWeight = FontWeight.Bold) }
                    items(urgentCards, key = { it.instance.id }) { card -> ProgressPreviewCard(card, categories, openTask) }
                }
                if (settledCards.isNotEmpty()) {
                    item { Text("已完成任務", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = if (urgentCards.isEmpty()) 0.dp else 8.dp)) }
                    items(settledCards, key = { it.instance.id }) { card -> ProgressPreviewCard(card, categories, openTask) }
                }
            }
        },
        confirmButton = { TextButton(dismiss) { Text("關閉") } }
    )
}

@Composable
private fun ProgressPreviewCard(card: TaskCardModel, categories: List<TaskCategoryEntity>, openTask: (TaskCardModel) -> Unit) {
    val isUrgent = !card.instance.settled && card.task.priority == TaskPriority.RED
    val category = categories.firstOrNull { it.id == card.task.categoryId }
    Card(
        colors = CardDefaults.cardColors(containerColor = if (isUrgent) Color(0xFFFFEBEE) else Color(0xFFEAF8ED)),
        modifier = Modifier.fillMaxWidth().clickable { openTask(card) }
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(category?.icon ?: "📌", style = MaterialTheme.typography.titleMedium)
                Column(Modifier.padding(start = 8.dp).weight(1f)) {
                    Text(card.task.name, fontWeight = FontWeight.Bold)
                    Text(category?.name ?: "其他", color = Color.Gray, style = MaterialTheme.typography.labelSmall)
                }
                Text(listOf(card.task.startTime, card.task.endTime).filter { it.isNotBlank() }.joinToString(" ～ ").ifBlank { "全天" }, color = Violet, style = MaterialTheme.typography.labelSmall)
            }
            if (isUrgent) Text("⚠ 重要任務未完成", color = Color(0xFFD92D20), style = MaterialTheme.typography.labelSmall)
            Text("完成度 ${completionText(card.instance.completionPercentage)}%", color = Color.Gray, style = MaterialTheme.typography.labelSmall)
            card.instance.result.takeIf { it.isNotBlank() }?.let { result ->
                Text(result, color = Color.Gray, maxLines = 1, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

@Composable
private fun DateLoadingCard() {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(22.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            Text("正在查詢當日日程…", modifier = Modifier.padding(start = 12.dp), color = Color.Gray)
        }
    }
}

@Composable
private fun CalendarQuery(date: LocalDate, isLoading: Boolean, select: (LocalDate) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        IconButton({ select(date.minusDays(1)) }) { Icon(Icons.Default.ChevronLeft, "前一天") }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(date.format(DateTimeFormatter.ofPattern("yyyy 年 M 月 d 日")), fontWeight = FontWeight.Bold)
            Text(weekdayText(date), color = Color.Gray, style = MaterialTheme.typography.labelMedium)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (isLoading) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            IconButton({ select(date.plusDays(1)) }) { Icon(Icons.Default.ChevronRight, "後一天") }
        }
    }
}

private fun weekdayText(date: LocalDate): String = listOf("星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日")[date.dayOfWeek.value - 1]

@Composable
private fun LegacyCurrentScheduleCard(data: DashboardData?, now: LocalTime, openTask: (TaskCardModel) -> Unit) {
    val task = data?.cards?.firstOrNull { card -> inRange(card.task.startTime, card.task.endTime, now) }
    val reward = data?.rewards?.firstOrNull { reward ->
        val zone = ZoneId.systemDefault()
        val start = reward.exchange.scheduledAt?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalTime() } ?: return@firstOrNull false
        val end = reward.exchange.scheduledEndAt?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalTime() } ?: return@firstOrNull false
        now >= start && now < end
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = Ink),
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier.fillMaxWidth().clickable(enabled = task != null) { task?.let(openTask) }
    ) {
        Column(Modifier.padding(20.dp)) {
            Text("現在進行中", color = Color.White.copy(alpha = .7f))
            when {
                task != null -> {
                    (task.instance.descriptionOverride ?: task.task.description).takeIf { it.isNotBlank() }?.let { description ->
                        Text(description, color = Color.White.copy(alpha = .78f), maxLines = 2, modifier = Modifier.padding(bottom = 6.dp))
                    }
                    Text("⚔️ ${task.task.name}", color = Color.White, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("${task.task.startTime} – ${task.task.endTime}", color = Gold)
                }
                reward != null -> {
                    reward.exchange.note.ifBlank { reward.item.description }.takeIf { it.isNotBlank() }?.let { description ->
                        Text(description, color = Color.White.copy(alpha = .78f), maxLines = 2, modifier = Modifier.padding(bottom = 6.dp))
                    }
                    Text("${reward.item.emoji} ${reward.item.name}", color = Color.White, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("獎勵時間", color = Gold)
                }
                else -> Text("目前沒有進行中的任務", color = Color.White, style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

@Composable
private fun IntermediateCurrentScheduleCard(data: DashboardData?, now: LocalTime, openTask: (TaskCardModel) -> Unit) {
    val task = data?.cards?.firstOrNull { inRange(it.task.startTime, it.task.endTime, now) }
    val reward = data?.rewards?.firstOrNull { rewardInRange(it, now) }
    Card(
        colors = CardDefaults.cardColors(containerColor = Ink),
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier.fillMaxWidth().clickable(enabled = task != null) { task?.let(openTask) }
    ) {
        Column(Modifier.padding(20.dp)) {
            Text("現在進行中", color = Color.White.copy(alpha = .7f))
            when {
                task != null -> {
                    val description = task.instance.descriptionOverride ?: task.task.description
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(.48f)) {
                            Text("📌 ${task.task.name}", color = Color.White, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 1)
                            Text("${task.task.startTime} ～ ${task.task.endTime}", color = Gold)
                        }
                        description.takeIf { it.isNotBlank() }?.let {
                            Text(it, color = Color.White.copy(alpha = .82f), maxLines = 2, modifier = Modifier.weight(.52f).padding(start = 12.dp))
                        }
                    }
                }
                reward != null -> {
                    val description = reward.exchange.note.ifBlank { reward.item.description }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(.48f)) {
                            Text("${reward.item.emoji} ${reward.item.name}", color = Color.White, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 1)
                            Text("獎勵行程", color = Gold)
                        }
                        description.takeIf { it.isNotBlank() }?.let {
                            Text(it, color = Color.White.copy(alpha = .82f), maxLines = 2, modifier = Modifier.weight(.52f).padding(start = 12.dp))
                        }
                    }
                }
                else -> Text("目前沒有進行中的任務或獎勵。", color = Color.White, style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

@Composable
private fun CurrentScheduleCard(data: DashboardData?, now: LocalTime, openTask: (TaskCardModel) -> Unit) {
    val task = data?.cards?.firstOrNull { inRange(it.task.startTime, it.task.endTime, now) }
    val reward = data?.rewards?.firstOrNull { rewardInRange(it, now) }
    val nextTask = data?.cards.orEmpty()
        .filter { card ->
            !card.instance.settled && card.task.startTime.isNotBlank() &&
                runCatching { LocalTime.parse(card.task.startTime).isAfter(now) }.getOrDefault(false)
        }
        .minByOrNull { it.task.startTime }
    val taskToOpen = task ?: nextTask?.takeIf { reward == null }
    Card(
        colors = CardDefaults.cardColors(containerColor = Ink),
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier.fillMaxWidth().clickable(enabled = taskToOpen != null) { taskToOpen?.let(openTask) }
    ) {
        Column(Modifier.padding(20.dp)) {
            Text(if (task == null && reward == null && nextTask != null) "下一個任務" else "現在進行中", color = Color.White.copy(alpha = .7f))
            when {
                task != null -> {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("📌 ${task.task.name}", color = Color.White, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 1, modifier = Modifier.weight(1f))
                        Text("${task.task.startTime} ～ ${task.task.endTime}", color = Gold, style = MaterialTheme.typography.labelLarge)
                    }
                }
                reward != null -> {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("${reward.item.emoji} ${reward.item.name}", color = Color.White, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 1, modifier = Modifier.weight(1f))
                        Text("獎勵行程", color = Gold, style = MaterialTheme.typography.labelLarge)
                    }
                }
                nextTask != null -> {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("📌 ${nextTask.task.name}", color = Color.White, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 1, modifier = Modifier.weight(1f))
                        Text("${nextTask.task.startTime} ～ ${nextTask.task.endTime}", color = Gold, style = MaterialTheme.typography.labelLarge)
                    }
                }
                else -> Text("目前沒有進行中的任務或獎勵。", color = Color.White, style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

private fun inRange(start: String, end: String, now: LocalTime): Boolean = runCatching {
    start.isNotBlank() && end.isNotBlank() && now >= LocalTime.parse(start) && (end == "24:00" || now < LocalTime.parse(end))
}.getOrDefault(false)

private fun rewardInRange(reward: RewardSchedule, now: LocalTime): Boolean {
    val zone = ZoneId.systemDefault()
    val start = reward.exchange.scheduledAt?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalTime() } ?: return false
    val end = reward.exchange.scheduledEndAt?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalTime() } ?: return false
    return now >= start && now < end
}

private fun timelineProgress(start: String, end: String, now: LocalTime): Float? = runCatching {
    if (!inRange(start, end, now)) null else {
        val startMinutes = LocalTime.parse(start).toSecondOfDay() / 60f
        val endMinutes = if (end == "24:00") 1440f else LocalTime.parse(end).toSecondOfDay() / 60f
        ((now.toSecondOfDay() / 60f - startMinutes) / (endMinutes - startMinutes)).coerceIn(0f, 1f)
    }
}.getOrNull()

@Composable
private fun CurrentTimeTimelineMarker(time: String) {
    val red = Color(0xFFD92D20)
    Box(Modifier.fillMaxWidth().height(28.dp), contentAlignment = Alignment.CenterStart) {
        // Keep the timeline continuous above and below the current-time marker.
        Box(Modifier.padding(start = 74.dp).width(2.dp).fillMaxHeight().background(Violet.copy(alpha = .38f)))
        Box(Modifier.fillMaxWidth().height(2.dp).background(red).zIndex(-1f))
        Text(
            time,
            color = red,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(start = 8.dp).background(SoftBackground).zIndex(1f)
        )
        Surface(color = red, shape = RoundedCornerShape(20.dp), modifier = Modifier.padding(start = 69.dp).size(12.dp).zIndex(1f)) {}
    }
}

@Composable
private fun TimelineTask(
    card: TaskCardModel,
    category: TaskCategoryEntity?,
    currentTime: LocalTime?,
    onSettle: () -> Unit,
    onEdit: () -> Unit
) {
    val nowProgress = currentTime?.let { timelineProgress(card.task.startTime, card.task.endTime, it) }
    val axisLineColor = Violet.copy(alpha = .38f)
    val axisNodeColor = Violet
    var cardHeightPx by remember(card.instance.id) { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val railTail = if (card.instance.settled) 8.dp else 24.dp
    val railHeight = maxOf(148.dp, with(density) { cardHeightPx.toDp() }) + railTail
    val rowModifier = Modifier.fillMaxWidth()
    Box(rowModifier) {
        Row(Modifier.fillMaxWidth().padding(end = 24.dp)) {
            Box(Modifier.width(64.dp).height(railHeight).padding(top = 10.dp), contentAlignment = Alignment.TopEnd) {
                Text(card.task.startTime.ifBlank { "全天" }, fontWeight = FontWeight.Bold)
                if (card.task.endTime.isNotBlank()) Text(card.task.endTime, style = MaterialTheme.typography.labelSmall, color = Color.Gray, modifier = Modifier.align(Alignment.TopEnd).padding(top = 22.dp))
            }
            Box(Modifier.width(22.dp).height(railHeight), contentAlignment = Alignment.TopCenter) {
                Box(Modifier.width(2.dp).fillMaxHeight().background(axisLineColor))
                Surface(color = axisNodeColor, shape = RoundedCornerShape(20.dp), modifier = Modifier.size(14.dp)) {}
            }
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier.weight(1f).padding(bottom = 12.dp).onSizeChanged { cardHeightPx = it.height }
            ) {
                TaskCard(card, category, onSettle, onEdit)
            }
        }
        nowProgress?.let { progress ->
            Box(
                Modifier.align(Alignment.TopStart).padding(top = (10f + progress * (railHeight.value - 14f)).dp)
                    .fillMaxWidth().height(2.dp).background(Color(0xFFD92D20)).zIndex(-1f)
            )
            Box(
                Modifier.align(Alignment.TopEnd).padding(top = (10f + progress * (railHeight.value - 14f)).dp)
                    .width(24.dp).height(2.dp).background(Color(0xFFD92D20))
            )
        }
    }
}

@Composable
private fun LegacyTimelineTask(card: TaskCardModel, category: TaskCategoryEntity?, currentTime: LocalTime?, onSettle: () -> Unit, onEdit: () -> Unit) {
    val nowProgress = currentTime?.let { timelineProgress(card.task.startTime, card.task.endTime, it) }
    Row(
        Modifier.fillMaxWidth()
            .then(if (card.instance.settled) Modifier.background(Color(0xFFE3F6E8), RoundedCornerShape(16.dp)).padding(vertical = 4.dp) else Modifier)
    ) {
        Box(Modifier.width(64.dp).height(148.dp).padding(top = 10.dp), contentAlignment = Alignment.TopEnd) {
            Text(card.task.startTime.ifBlank { "全天" }, fontWeight = FontWeight.Bold)
            if (card.task.endTime.isNotBlank()) Text(card.task.endTime, style = MaterialTheme.typography.labelSmall, color = Color.Gray, modifier = Modifier.align(Alignment.TopEnd).padding(top = 22.dp))
            nowProgress?.let { progress ->
                Text(currentTime.toString().take(5), color = Color(0xFFD92D20), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelSmall, modifier = Modifier.align(Alignment.TopEnd).padding(top = (progress * 126f).dp))
            }
        }
        Box(Modifier.width(22.dp).height(148.dp), contentAlignment = Alignment.TopCenter) {
            Box(Modifier.width(2.dp).fillMaxHeight().background(priorityColor(card.task.priority) ?: Violet.copy(alpha = .35f)))
            Surface(color = priorityColor(card.task.priority) ?: Violet, shape = RoundedCornerShape(20.dp), modifier = Modifier.size(14.dp)) {}
            nowProgress?.let { progress ->
                Box(Modifier.align(Alignment.TopCenter).padding(top = (progress * 134f).dp).fillMaxWidth().height(2.dp).background(Color(0xFFD92D20)))
            }
        }
        Box(
            Modifier.weight(1f).padding(bottom = 12.dp)
        ) { TaskCard(card, category, onSettle, onEdit) }
    }
}

@Composable
private fun RewardTimelineItem(reward: RewardSchedule, currentTime: LocalTime?, edit: () -> Unit) {
    val zone = ZoneId.systemDefault()
    val start = reward.exchange.scheduledAt?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalTime().toString().take(5) } ?: "未排時間"
    val end = reward.exchange.scheduledEndAt?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalTime().toString().take(5) }.orEmpty()
    val nowProgress = currentTime?.let { timelineProgress(start, end, it) }
    val axisLineColor = Violet.copy(alpha = .38f)
    val axisNodeColor = Violet
    Box(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(end = 24.dp)) {
            Box(Modifier.width(64.dp).height(96.dp).padding(top = 10.dp), contentAlignment = Alignment.TopEnd) {
                Text(start, fontWeight = FontWeight.Bold)
                if (end.isNotBlank()) Text(end, style = MaterialTheme.typography.labelSmall, color = Color.Gray, modifier = Modifier.align(Alignment.TopEnd).padding(top = 22.dp))
            }
            Box(Modifier.width(22.dp).height(96.dp), contentAlignment = Alignment.TopCenter) {
                Box(Modifier.width(2.dp).fillMaxHeight().background(axisLineColor))
                Surface(color = axisNodeColor, shape = RoundedCornerShape(20.dp), modifier = Modifier.size(14.dp)) {}
            }
            Spacer(Modifier.width(8.dp))
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF7DE)),
                modifier = Modifier.weight(1f).padding(bottom = 12.dp).clickable(onClick = edit)
            ) {
                Column(Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${reward.item.emoji} ${reward.item.name}", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        Icon(Icons.Default.Settings, "編輯獎勵")
                    }
                    if (reward.exchange.note.isNotBlank()) Text(reward.exchange.note, color = Color.Gray)
                }
            }
        }
        nowProgress?.let { progress ->
            Box(
                Modifier.align(Alignment.TopStart).padding(top = (10f + progress * 82f).dp)
                    .fillMaxWidth().height(2.dp).background(Color(0xFFD92D20)).zIndex(-1f)
            )
            Box(
                Modifier.align(Alignment.TopEnd).padding(top = (10f + progress * 82f).dp)
                    .width(24.dp).height(2.dp).background(Color(0xFFD92D20))
            )
        }
    }
}

@Composable
private fun LegacyRewardTimelineItem(reward: RewardSchedule, currentTime: LocalTime?, edit: () -> Unit) {
    val zone = ZoneId.systemDefault()
    val start = reward.exchange.scheduledAt?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalTime().toString().take(5) } ?: "未排時間"
    val end = reward.exchange.scheduledEndAt?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalTime().toString().take(5) }.orEmpty()
    val nowProgress = currentTime?.let { timelineProgress(start, end, it) }
    Row(Modifier.fillMaxWidth()) {
        Box(Modifier.width(64.dp).height(96.dp).padding(top = 10.dp), contentAlignment = Alignment.TopEnd) {
            Text(start, fontWeight = FontWeight.Bold)
            if (end.isNotBlank()) Text(end, style = MaterialTheme.typography.labelSmall, color = Color.Gray, modifier = Modifier.align(Alignment.TopEnd).padding(top = 22.dp))
            nowProgress?.let { progress ->
                Text(currentTime.toString().take(5), color = Color(0xFFD92D20), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelSmall, modifier = Modifier.align(Alignment.TopEnd).padding(top = (progress * 74f).dp))
            }
        }
        Box(Modifier.width(22.dp).height(96.dp), contentAlignment = Alignment.TopCenter) {
            Box(Modifier.width(2.dp).fillMaxHeight().background(Gold.copy(alpha = .45f)))
            Surface(color = Gold, shape = RoundedCornerShape(20.dp), modifier = Modifier.size(14.dp)) {}
            nowProgress?.let { progress ->
                Box(Modifier.align(Alignment.TopCenter).padding(top = (progress * 82f).dp).fillMaxWidth().height(2.dp).background(Color(0xFFD92D20)))
            }
        }
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF7DE)),
            modifier = Modifier.weight(1f).padding(bottom = 12.dp).clickable(onClick = edit)
        ) {
            Column(Modifier.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${reward.item.emoji} 獎勵：${reward.item.name}", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Icon(Icons.Default.Settings, "編輯獎勵")
                }
                if (reward.exchange.note.isNotBlank()) Text(reward.exchange.note, color = Color.Gray)
            }
        }
    }
}

@Composable
private fun TaskCard(card: TaskCardModel, category: TaskCategoryEntity?, onSettle: () -> Unit, onEdit: () -> Unit) {
    val task = card.task
    var detail by remember { mutableStateOf(false) }
    Card(
        shape = RoundedCornerShape(18.dp),
        border = priorityColor(task.priority)?.let { BorderStroke(2.dp, it) },
        colors = CardDefaults.cardColors(containerColor = if (card.unlocked) Color.White else Color(0xFFF0F0F4))
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(category?.icon ?: "⚔️", style = MaterialTheme.typography.headlineSmall)
                Column(Modifier.padding(start = 10.dp).weight(1f).clickable(onClick = onEdit)) {
                    Text(task.name, fontWeight = FontWeight.Bold)
                    Text(
                        listOfNotNull(
                            category?.name,
                            task.startTime.takeIf { it.isNotBlank() },
                            task.locationName.takeIf { it.isNotBlank() }
                        ).joinToString(" · ").ifBlank { "全天任務" },
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.Gray
                    )
                }
                IconButton(onEdit) { Icon(if (card.instance.settled) Icons.Default.Visibility else Icons.Default.Settings, if (card.instance.settled) "查閱任務" else "編輯任務") }
            }
            val previewText = if (card.instance.settled) card.instance.result else task.description
            if (previewText.isNotBlank()) {
                Text(
                    previewText,
                    color = Color.Gray,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    modifier = Modifier.padding(top = 8.dp).clickable { detail = true }
                )
            } else if (card.instance.settled) {
                Text("尚未填寫完成心得。", color = Color.Gray, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
            }
            LinearProgressIndicator(
                progress = { card.instance.completionPercentage.divide(BigDecimal("100")).toFloat().coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp)
            )
            Text(
                "完成度 ${completionText(card.instance.completionPercentage)}%　🪙 ${task.rewardCoins}　💎 ${task.rewardDiamonds}",
                style = MaterialTheme.typography.labelMedium
            )
            if (!card.unlocked) {
                Text("尚缺：${card.missing.joinToString("、")}", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
            } else if (!card.instance.settled) {
                Button(onClick = onSettle, modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) { Text("完成並領取獎勵") }
            }
        }
    }
    if (detail) AlertDialog(
        onDismissRequest = { detail = false },
        title = { Text(if (card.instance.settled) "完成心得" else task.name) },
        text = { Text(if (card.instance.settled) card.instance.result else task.description) },
        confirmButton = { TextButton({ detail = false }) { Text("關閉") } }
    )
}

private fun priorityColor(priority: TaskPriority): Color? = when (priority) {
    TaskPriority.NONE -> null
    TaskPriority.BLUE -> Color(0xFF2196F3)
    TaskPriority.YELLOW -> Color(0xFFFFC107)
    TaskPriority.ORANGE -> Color(0xFFFF8800)
    TaskPriority.RED -> Color(0xFFE53935)
}

@Composable
private fun SettleDialog(card: TaskCardModel, dismiss: () -> Unit, settle: (BigDecimal, String, Set<Int>) -> Unit) {
    val checklist = remember(card.task.checklist) { checklistItems(card.task.checklist) }
    var checked by remember(card.instance.id) {
        mutableStateOf(card.instance.checkedChecklistItems.split(',').mapNotNull { it.toIntOrNull() }.toSet())
    }
    var progress by remember(card.instance.id) {
        mutableFloatStateOf(if (checklist.isEmpty()) 100f else checked.size * 100f / checklist.size)
    }
    var result by remember { mutableStateOf(card.instance.result) }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text("結算：${card.task.name}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (checklist.isNotEmpty()) {
                    Text("檢查清單", fontWeight = FontWeight.Bold)
                    checklist.forEachIndexed { index, text ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = index in checked,
                                onCheckedChange = { isChecked ->
                                    checked = if (isChecked) checked + index else checked - index
                                    progress = checked.size * 100f / checklist.size
                                }
                            )
                            Text(text)
                        }
                    }
                    Text("勾選比例會自動帶入完成度；仍可手動調整。", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                }
                Text("完成度 ${progress.roundToInt()}%", fontWeight = FontWeight.Bold)
                Slider(
                    value = progress,
                    onValueChange = { progress = ((it / 10f).roundToInt() * 10).toFloat() },
                    valueRange = 0f..100f,
                    steps = 9
                )
                Field("任務結果／心得", result) { result = it }
                val rate = BigDecimal.valueOf(progress.toDouble()).divide(BigDecimal("100"), 6, RoundingMode.HALF_UP)
                Text("實際獲得：🪙 ${card.task.rewardCoins.multiply(rate).setScale(2, RoundingMode.HALF_UP)}　💎 ${card.task.rewardDiamonds.multiply(rate).setScale(2, RoundingMode.HALF_UP)}")
            }
        },
        confirmButton = {
            Button(onClick = { settle(BigDecimal.valueOf(progress.toDouble()), result, checked) }) { Text("確認結算") }
        },
        dismissButton = { TextButton(dismiss) { Text("取消") } }
    )
}

@Composable
private fun TaskReviewDialog(card: TaskCardModel, dismiss: () -> Unit, saveResult: (String) -> Unit, undo: ((String) -> Unit) -> Unit) {
    var result by remember(card.instance.id) { mutableStateOf(card.instance.result) }
    var undoError by remember(card.instance.id) { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text("查閱完成任務") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(card.task.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("${card.instance.scheduledDate} · ${card.task.startTime.ifBlank { "全天" }}${card.task.endTime.takeIf { it.isNotBlank() }?.let { " – $it" }.orEmpty()}", color = Color.Gray)
            card.task.description.takeIf { it.isNotBlank() }?.let { Text(it) }
            card.task.locationName.takeIf { it.isNotBlank() }?.let { Text("地點：$it", color = Color.Gray) }
            Text("完成度 ${completionText(card.instance.completionPercentage)}% · 已獲得 🪙 ${card.instance.earnedCoins}　💎 ${card.instance.earnedDiamonds}", style = MaterialTheme.typography.bodySmall)
            HorizontalDivider()
            Field("任務結果／心得", result) { result = it }
            Text("任務資訊如名稱、時間、獎勵等，需先撤回完成後才能編輯。", color = Color.Gray, style = MaterialTheme.typography.labelSmall)
            undoError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        } },
        confirmButton = { Button({ saveResult(result); dismiss() }) { Text("儲存心得") } },
        dismissButton = { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton({ undoError = null; undo { undoError = it } }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("撤回") }
            TextButton(dismiss) { Text("關閉") }
        } }
    )
}

@Composable
private fun DailySummaryCard(date: LocalDate, initial: String, save: (String) -> Unit) {
    var editing by remember(date) { mutableStateOf(initial.isBlank()) }
    var text by remember(date, initial) { mutableStateOf(initial) }
    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFF2F0FF))) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("${date.format(DateTimeFormatter.ofPattern("M 月 d 日"))} 當日總結", fontWeight = FontWeight.Bold)
                    Text("記錄今天的收穫、感受或明日提醒。", color = Color.Gray, style = MaterialTheme.typography.labelSmall)
                }
                IconButton({ editing = !editing }) { Icon(if (editing) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, if (editing) "收起總結" else "展開總結") }
            }
            if (editing) {
                Field("當日總結", text) { text = it }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton({ text = ""; save(""); editing = false }, Modifier.weight(1f)) { Text("清除") }
                    Button({ save(text); editing = false }, Modifier.weight(1f)) { Text("儲存總結") }
                }
            } else if (text.isBlank()) Text("尚未撰寫當日總結。", color = Color.Gray)
            else Text(text)
        }
    }
}

@Composable
private fun TaskLibrary(state: MissionUiState, vm: MissionViewModel) {
    var creatingInitial by remember { mutableStateOf<TaskEntity?>(null) }
    var editing by remember { mutableStateOf<TaskEntity?>(null) }
    var categoryManager by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("任務庫", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Text("這裡只會顯示任務類別與從任務庫建立的單次任務。", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
                    }
                    OutlinedButton({ categoryManager = true }) { Icon(Icons.Default.Category, null); Spacer(Modifier.width(4.dp)); Text("分類") }
                }
            }
            if (state.tasks.isEmpty()) item { EmptyHint("尚未建立任務類別。") }
            items(state.tasks, key = { it.id }) { task ->
                val category = state.categories.firstOrNull { it.id == task.categoryId }
                Card(border = priorityColor(task.priority)?.let { BorderStroke(2.dp, it) }) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(category?.icon ?: "🏷️", style = MaterialTheme.typography.headlineSmall)
                        Column(Modifier.padding(start = 10.dp).weight(1f)) {
                            Text(task.name, fontWeight = FontWeight.Bold)
                            Text("${category?.name ?: "未分類"} · ${repeatSummary(task)}", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                            if (task.checklist.isNotBlank()) Text("有 ${checklistItems(task.checklist).size} 個檢查項目", style = MaterialTheme.typography.labelSmall, color = Violet)
                        }
                        IconButton({ editing = task }) { Icon(Icons.Default.Settings, "編輯任務") }
                    }
                }
            }
            item { Spacer(Modifier.height(88.dp)) }
        }
        FloatingActionButton({ creatingInitial = TaskEntity(name = "", startDate = LocalDate.now().toString()) }, Modifier.align(Alignment.BottomEnd).padding(22.dp)) { Icon(Icons.Default.Add, "新增任務") }
    }
    creatingInitial?.let { initial ->
        TaskFormDialog(
            title = "新增任務",
            initial = initial,
            categories = state.categories,
            allowRepeat = true,
            dismiss = { creatingInitial = null },
            save = { task -> vm.saveTask(task); creatingInitial = null }
        )
    }
    editing?.let { task ->
        TaskFormDialog(
            title = "編輯任務類別",
            initial = task,
            categories = state.categories,
            allowRepeat = true,
            dismiss = { editing = null },
            save = { updated -> vm.saveTask(updated); editing = null },
            delete = { vm.deleteTask(task); editing = null }
        )
    }
    if (categoryManager) CategoryManagerDialog(state.categories, { categoryManager = false }, vm::saveCategory, vm::updateCategory, vm::deleteCategory)
}

@Composable
private fun CategoryManagerDialog(
    categories: List<TaskCategoryEntity>,
    dismiss: () -> Unit,
    save: (String, String) -> Unit,
    update: (TaskCategoryEntity, String, String) -> Unit,
    delete: (TaskCategoryEntity) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var icon by remember { mutableStateOf("🏷️") }
    var editing by remember { mutableStateOf<TaskCategoryEntity?>(null) }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text("任務分類") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("目前分類", fontWeight = FontWeight.Bold)
                categories.forEach { category ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(category.icon, style = MaterialTheme.typography.titleLarge)
                        Column(Modifier.padding(start = 8.dp).weight(1f)) {
                            Text(category.name, fontWeight = FontWeight.SemiBold)
                            Text(if (category.isPreset) "預設分類" else "自訂分類", color = Color.Gray, style = MaterialTheme.typography.labelSmall)
                        }
                        IconButton({ editing = category }) { Icon(Icons.Default.Settings, "編輯分類") }
                    }
                }
                HorizontalDivider()
                Text("新增自訂分類", fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Field("圖示", icon, Modifier.width(86.dp)) { icon = it }
                    Field("分類名稱", name, Modifier.weight(1f)) { name = it }
                }
            }
        },
        confirmButton = { Button(enabled = name.isNotBlank(), onClick = { save(name, icon); name = ""; icon = "🏷️" }) { Text("新增") } },
        dismissButton = { TextButton(dismiss) { Text("關閉") } }
    )
    editing?.let { category -> CategoryEditDialog(category, { editing = null }, update, delete) }
}

@Composable
private fun CategoryEditDialog(category: TaskCategoryEntity, dismiss: () -> Unit, update: (TaskCategoryEntity, String, String) -> Unit, delete: (TaskCategoryEntity) -> Unit) {
    var name by remember(category.id) { mutableStateOf(category.name) }
    var icon by remember(category.id) { mutableStateOf(category.icon) }
    var confirmDelete by remember(category.id) { mutableStateOf(false) }
    val canDelete = category.name.trim() != "其他"
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text("編輯分類") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { Field("圖示", icon) { icon = it }; Field("分類名稱", name) { name = it } } },
        confirmButton = { Button(enabled = name.isNotBlank(), onClick = { update(category, name, icon); dismiss() }) { Text("儲存") } },
        dismissButton = { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { if (canDelete) TextButton({ confirmDelete = true }) { Text("刪除", color = MaterialTheme.colorScheme.error) }; TextButton(dismiss) { Text("取消") } } }
    )
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("刪除分類？") },
        text = { Text("使用這個分類的任務會自動改為「其他」分類。") },
        confirmButton = { Button({ delete(category); confirmDelete = false; dismiss() }) { Text("確認刪除") } },
        dismissButton = { TextButton({ confirmDelete = false }) { Text("取消") } }
    )
}

@Composable
private fun TaskFormDialog(
    title: String,
    initial: TaskEntity,
    categories: List<TaskCategoryEntity>,
    allowRepeat: Boolean,
    dismiss: () -> Unit,
    save: (TaskEntity) -> Unit,
    delete: (() -> Unit)? = null
) {
    var name by remember(initial.id) { mutableStateOf(initial.name) }
    var description by remember(initial.id) { mutableStateOf(initial.description) }
    var date by remember(initial.id) { mutableStateOf(initial.startDate) }
    var allDay by remember(initial.id) { mutableStateOf(initial.allDay) }
    var start by remember(initial.id) { mutableStateOf(initial.startTime) }
    var end by remember(initial.id) { mutableStateOf(initial.endTime) }
    var location by remember(initial.id) { mutableStateOf(initial.locationName) }
    var address by remember(initial.id) { mutableStateOf(initial.address) }
    var coins by remember(initial.id) { mutableStateOf(initial.rewardCoins.toPlainString()) }
    var diamonds by remember(initial.id) { mutableStateOf(initial.rewardDiamonds.toPlainString()) }
    var categoryId by remember(initial.id, categories) {
        mutableStateOf(initial.categoryId.takeIf { current -> categories.any { it.id == current } }
            ?: categories.firstOrNull { it.name == "其他" }?.id
            ?: initial.categoryId)
    }
    var priority by remember(initial.id) { mutableStateOf(initial.priority) }
    var checklist by remember(initial.id) { mutableStateOf(initial.checklist) }
    var repeat by remember(initial.id) { mutableStateOf(if (allowRepeat) initial.repeatType else RepeatType.NONE) }
    var repeatEndDate by remember(initial.id) { mutableStateOf(initial.repeatEndDate) }
    var weekDays by remember(initial.id) {
        mutableStateOf(initial.repeatConfig.substringAfter("days=", "").substringBefore(';').split(',').mapNotNull { it.toIntOrNull() }.toSet())
    }
    var intervalDays by remember(initial.id) { mutableStateOf(initial.repeatConfig.substringAfter("interval=", "1").ifBlank { "1" }) }
    var monthDay by remember(initial.id) { mutableStateOf(initial.repeatConfig.substringAfter("monthDay=", "").ifBlank { LocalDate.parse(initial.startDate).dayOfMonth.toString() }) }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Field("任務名稱", name) { name = it }
                Field("描述", description) { description = it }
                Field("日期 YYYY-MM-DD", date) { date = it }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(allDay, { allDay = it })
                    Text("全天任務")
                }
                if (!allDay) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Field("開始 HH:mm", start, Modifier.weight(1f)) { start = it }
                    Field("結束 HH:mm", end, Modifier.weight(1f)) { end = it }
                }
                Field("地點", location) { location = it }
                Field("地址", address) { address = it }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Field("金幣獎勵", coins, Modifier.weight(1f)) { coins = it }
                    Field("鑽石獎勵", diamonds, Modifier.weight(1f)) { diamonds = it }
                }
                Text("任務分類", fontWeight = FontWeight.Bold)
                CategoryPicker(categories, categoryId) { categoryId = it }
                Text("重要程度（以卡片外框顏色標示）", fontWeight = FontWeight.Bold)
                PriorityPicker(priority) { priority = it }
                if (allowRepeat) {
                    Text("重複週期", fontWeight = FontWeight.Bold)
                    RepeatPicker(repeat) { repeat = it }
                    if (repeat != RepeatType.NONE) Field("重複結束日期 YYYY-MM-DD（選填）", repeatEndDate) { repeatEndDate = it }
                    when (repeat) {
                        RepeatType.WEEKLY -> WeekdayPicker(weekDays) { weekDays = it }
                        RepeatType.INTERVAL -> Field("每隔幾天", intervalDays) { intervalDays = it.filter(Char::isDigit) }
                        RepeatType.MONTHLY -> Field("每月日期（1-31 或 last）", monthDay) { monthDay = it }
                        else -> Unit
                    }
                }
                Text("檢查清單（每行一個項目）", fontWeight = FontWeight.Bold)
                Field("檢查清單", checklist) { checklist = it }
            }
        },
        confirmButton = {
            Button(enabled = name.isNotBlank(), onClick = {
                val config = when (repeat) {
                    RepeatType.WEEKLY -> "days=" + (if (weekDays.isEmpty()) setOf(LocalDate.now().dayOfWeek.value) else weekDays).sorted().joinToString(",")
                    RepeatType.INTERVAL -> "interval=" + (intervalDays.toIntOrNull()?.coerceAtLeast(1) ?: 1)
                    RepeatType.MONTHLY -> "monthDay=" + monthDay.ifBlank {
                        runCatching { LocalDate.parse(date).dayOfMonth }.getOrDefault(LocalDate.now().dayOfMonth).toString()
                    }
                    else -> ""
                }
                save(initial.copy(
                    name = name.trim(),
                    description = description,
                    startDate = date.ifBlank { LocalDate.now().toString() },
                    allDay = allDay,
                    startTime = if (allDay) "" else start,
                    endTime = if (allDay) "" else end,
                    locationName = location,
                    address = address,
                    rewardCoins = coins.toBigDecimalOrNull() ?: BigDecimal.ZERO,
                    rewardDiamonds = diamonds.toBigDecimalOrNull() ?: BigDecimal.ZERO,
                    categoryId = categoryId,
                    priority = priority,
                    checklist = checklist,
                    repeatType = repeat,
                    repeatConfig = config,
                    repeatEndDate = if (repeat == RepeatType.NONE) "" else repeatEndDate
                ))
            }) { Text("儲存") }
        },
        dismissButton = {
            Row {
                delete?.let { deleteAction ->
                    TextButton(deleteAction, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("刪除任務") }
                }
                TextButton(dismiss) { Text("取消") }
            }
        }
    )
}

@Composable
private fun CategoryPicker(categories: List<TaskCategoryEntity>, selected: String, change: (String) -> Unit) {
    val visibleCategories = categories.distinctBy { it.name.trim().lowercase() }
    if (visibleCategories.isEmpty()) Text("正在建立預設分類…", color = Color.Gray)
    visibleCategories.chunked(3).forEach { row ->
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            row.forEach { category ->
                FilterChip(
                    selected = selected == category.id,
                    onClick = { change(category.id) },
                    label = { Text("${category.icon} ${category.name}") },
                    modifier = Modifier.weight(1f)
                )
            }
            repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}

@Composable
private fun PriorityPicker(selected: TaskPriority, change: (TaskPriority) -> Unit) {
    val rows = listOf(
        listOf(TaskPriority.NONE to "無外框", TaskPriority.BLUE to "藍色", TaskPriority.YELLOW to "黃色"),
        listOf(TaskPriority.ORANGE to "橙色", TaskPriority.RED to "紅色")
    )
    rows.forEach { row ->
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            row.forEach { (priority, label) ->
                FilterChip(
                    selected = selected == priority,
                    onClick = { change(priority) },
                    label = { Text(label) },
                    border = FilterChipDefaults.filterChipBorder(
                        enabled = true,
                        selected = selected == priority,
                        borderColor = priorityColor(priority) ?: Color.Gray,
                        selectedBorderColor = priorityColor(priority) ?: Violet
                    ),
                    modifier = Modifier.weight(1f)
                )
            }
            repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}

@Composable
private fun PuzzleCategoryPicker(selected: String, change: (String) -> Unit) {
    val options = listOf(
        "work" to "💼 工作", "study" to "📚 學習", "health" to "🏃 健康",
        "life" to "🏠 生活", "finance" to "💰 財務", "other" to "✨ 其他"
    )
    options.chunked(3).forEach { row ->
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            row.forEach { (key, label) -> FilterChip(selected = selected == key, onClick = { change(key) }, label = { Text(label) }, modifier = Modifier.weight(1f)) }
            repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}

@Composable
private fun RepeatPicker(selected: RepeatType, change: (RepeatType) -> Unit) {
    val rows = listOf(
        listOf(RepeatType.NONE to "單次", RepeatType.DAILY to "每天", RepeatType.WEEKLY to "每週"),
        listOf(RepeatType.MONTHLY to "每月", RepeatType.INTERVAL to "間隔")
    )
    rows.forEach { row ->
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            row.forEach { (type, label) ->
                FilterChip(selected = selected == type, onClick = { change(type) }, label = { Text(label) }, modifier = Modifier.weight(1f))
            }
            repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}

@Composable
private fun WeekdayPicker(selected: Set<Int>, change: (Set<Int>) -> Unit) {
    Text("重複星期", fontWeight = FontWeight.Bold)
    val labels = listOf("一", "二", "三", "四", "五", "六", "日")
    listOf(0..3, 4..6).forEach { indexes ->
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            indexes.forEach { index ->
                val day = index + 1
                FilterChip(
                    selected = day in selected,
                    onClick = { change(if (day in selected) selected - day else selected + day) },
                    label = { Text(labels[index]) },
                    modifier = Modifier.weight(1f)
                )
            }
            repeat(4 - indexes.count()) { Spacer(Modifier.weight(1f)) }
        }
    }
}

@Composable
private fun InstanceEditDialog(
    card: TaskCardModel,
    categories: List<TaskCategoryEntity>,
    conflicts: List<String>,
    validate: (String, String) -> Unit,
    dismiss: () -> Unit,
    save: (String, String, String, String, Boolean, String, String, BigDecimal, BigDecimal, String, TaskPriority, String) -> Unit,
    delete: () -> Unit,
    undo: ((String) -> Unit) -> Unit
) {
    val task = card.task
    var name by remember { mutableStateOf(task.name) }
    var description by remember { mutableStateOf(task.description) }
    var location by remember { mutableStateOf(task.locationName) }
    var address by remember { mutableStateOf(task.address) }
    var allDay by remember { mutableStateOf(task.allDay) }
    var start by remember { mutableStateOf(task.startTime) }
    var end by remember { mutableStateOf(task.endTime) }
    var coins by remember { mutableStateOf(task.rewardCoins.toPlainString()) }
    var diamonds by remember { mutableStateOf(task.rewardDiamonds.toPlainString()) }
    var categoryId by remember { mutableStateOf(task.categoryId) }
    var priority by remember { mutableStateOf(task.priority) }
    var checklist by remember { mutableStateOf(task.checklist) }
    var undoError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(start, end, allDay) { if (!allDay) validate(start, end) }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text("編輯此日程任務") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("只會修改 ${card.instance.scheduledDate}；不影響重複週期。", color = Violet, style = MaterialTheme.typography.labelSmall)
                undoError?.let { Text("⚠ $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall) }
                Field("任務名稱", name) { name = it }
                Field("描述", description) { description = it }
                Field("地點", location) { location = it }
                Field("地址", address) { address = it }
                Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(allDay, { allDay = it }); Text("全天任務") }
                if (!allDay) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Field("開始 HH:mm", start, Modifier.weight(1f)) { start = it }
                        Field("結束 HH:mm", end, Modifier.weight(1f)) { end = it }
                    }
                    conflicts.forEach { Text("⚠ $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Field("金幣獎勵", coins, Modifier.weight(1f)) { coins = it }
                    Field("鑽石獎勵", diamonds, Modifier.weight(1f)) { diamonds = it }
                }
                Text("任務分類", fontWeight = FontWeight.Bold)
                CategoryPicker(categories, categoryId) { categoryId = it }
                Text("重要程度", fontWeight = FontWeight.Bold)
                PriorityPicker(priority) { priority = it }
                Field("檢查清單", checklist) { checklist = it }
            }
        },
        confirmButton = {},
        dismissButton = {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                if (card.instance.settled) {
                    TextButton({ undoError = null; undo { undoError = it } }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("撤回") }
                } else {
                    TextButton(delete, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("刪除") }
                }
                Spacer(Modifier.width(8.dp))
                TextButton(dismiss) { Text("取消") }
                Spacer(Modifier.width(8.dp))
                Button(enabled = allDay || conflicts.isEmpty(), onClick = {
                    save(name, description, location, address, allDay, start, end,
                        coins.toBigDecimalOrNull() ?: task.rewardCoins,
                        diamonds.toBigDecimalOrNull() ?: task.rewardDiamonds,
                        categoryId, priority, checklist)
                }) { Text("儲存") }
            }
        }
    )
}

@Composable
private fun RewardInstanceEditDialog(
    reward: RewardSchedule,
    dismiss: () -> Unit,
    save: (String, String, String, String) -> Unit,
    delete: () -> Unit
) {
    val zone = ZoneId.systemDefault()
    val stamp = reward.exchange.scheduledAt ?: System.currentTimeMillis()
    var date by remember { mutableStateOf(Instant.ofEpochMilli(stamp).atZone(zone).toLocalDate().toString()) }
    var start by remember { mutableStateOf(Instant.ofEpochMilli(stamp).atZone(zone).toLocalTime().toString().take(5)) }
    var end by remember { mutableStateOf(reward.exchange.scheduledEndAt?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalTime().toString().take(5) } ?: "") }
    var note by remember { mutableStateOf(reward.exchange.note) }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text("編輯獎勵日程") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(reward.item.name, fontWeight = FontWeight.Bold)
                Field("日期 YYYY-MM-DD", date) { date = it }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Field("開始 HH:mm", start, Modifier.weight(1f)) { start = it }
                    Field("結束 HH:mm", end, Modifier.weight(1f)) { end = it }
                }
                Field("描述", note) { note = it }
            }
        },
        confirmButton = { Button(enabled = start.length == 5 && end.length == 5, onClick = { save(date, start, end, note) }) { Text("儲存") } },
        dismissButton = {
            Row {
                TextButton(delete, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("取消並退款") }
                TextButton(dismiss) { Text("關閉") }
            }
        }
    )
}

@Composable
private fun Field(label: String, value: String, modifier: Modifier = Modifier.fillMaxWidth(), set: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { raw ->
            val digits = raw.filter(Char::isDigit)
            set(if (label.contains("HH:mm") && digits.length == 4 && ':' !in raw) digits.take(2) + ":" + digits.drop(2) else raw)
        },
        label = { Text(label) },
        modifier = modifier,
        singleLine = !label.contains("描述") && !label.contains("清單") && !label.contains("心得") && !label.contains("總結"),
        minLines = if (label.contains("描述") || label.contains("清單") || label.contains("心得") || label.contains("總結")) 3 else 1
    )
}

private fun checklistItems(value: String): List<String> = value.lineSequence().map { it.trim() }.filter { it.isNotBlank() }.toList()

private fun repeatSummary(task: TaskEntity): String = when (task.repeatType) {
    RepeatType.NONE -> "單次 · ${task.startDate}"
    RepeatType.DAILY -> "每天"
    RepeatType.WEEKLY -> "每週 " + task.repeatConfig.substringAfter("days=", "")
    RepeatType.MONTHLY -> "每月 " + task.repeatConfig.substringAfter("monthDay=", "")
    RepeatType.INTERVAL -> "每隔 " + task.repeatConfig.substringAfter("interval=", "1") + " 天"
} + task.repeatEndDate.takeIf { it.isNotBlank() }?.let { " · 至 $it" }.orEmpty()

@Composable
private fun EmptyHint(text: String) {
    Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Text(text, Modifier.padding(16.dp), color = Color.Gray)
    }
}

@Composable
private fun Shop(state: MissionUiState, vm: MissionViewModel) {
    var creating by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<ShopItemEntity?>(null) }
    var redeeming by remember { mutableStateOf<ShopItemEntity?>(null) }
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("獎勵商城", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) }
        items(state.shop, key = { it.item.id }) { card ->
            Card(colors = CardDefaults.cardColors(containerColor = if (card.unlocked) Color.White else Color(0xFFF0F0F3))) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (card.unlocked) card.item.emoji else "🔒", style = MaterialTheme.typography.headlineMedium)
                    Column(Modifier.padding(start = 12.dp).weight(1f)) {
                        Text(card.item.name, fontWeight = FontWeight.Bold)
                        Text(card.item.description, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                        Text("🪙 ${card.item.coinPrice}　💎 ${card.item.diamondPrice}", color = Violet, fontWeight = FontWeight.Bold)
                        if (!card.unlocked) Text(card.reason, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        IconButton({ editing = card.item }) { Icon(Icons.Default.Settings, "編輯商品") }
                        Button(onClick = { redeeming = card.item }, enabled = card.unlocked && card.item.active) { Text("兌換") }
                    }
                }
            }
        }
        item { OutlinedButton({ creating = true }, Modifier.fillMaxWidth()) { Icon(Icons.Default.Add, null); Text("新增自訂獎勵") } }
        item { Spacer(Modifier.height(72.dp)) }
    }
    if (creating) ShopEditor({ creating = false }) { item -> vm.saveItem(item); creating = false }
    editing?.let { item -> ShopEditDialog(item, { editing = null }, { updated -> vm.saveItem(updated); editing = null }, { vm.deleteItem(item); editing = null }) }
    redeeming?.let { item -> RewardScheduleDialog(item, { redeeming = null }) { date, start, end, note -> vm.exchange(item.id, date, start, end, note); redeeming = null } }
}

@Composable
private fun ShopEditor(dismiss: () -> Unit, save: (ShopItemEntity) -> Unit) {
    var emoji by remember { mutableStateOf("🎁") }
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var coins by remember { mutableStateOf("0") }
    var diamonds by remember { mutableStateOf("0") }
    var sortOrder by remember { mutableStateOf("0") }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text("新增商城獎勵") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Field("Emoji", emoji) { emoji = it }; Field("商品名稱", name) { name = it }; Field("描述", description) { description = it }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Field("金幣價格", coins, Modifier.weight(1f)) { coins = it }; Field("鑽石價格", diamonds, Modifier.weight(1f)) { diamonds = it } }
            Field("排序（數字小的排前面）", sortOrder) { sortOrder = it.filter(Char::isDigit) }
        } },
        confirmButton = { Button(enabled = name.isNotBlank(), onClick = { save(ShopItemEntity(name = name, emoji = emoji.ifBlank { "🎁" }, description = description, coinPrice = coins.toBigDecimalOrNull() ?: BigDecimal.ZERO, diamondPrice = diamonds.toBigDecimalOrNull() ?: BigDecimal.ZERO, sortOrder = sortOrder.toIntOrNull() ?: 0)) }) { Text("上架") } },
        dismissButton = { TextButton(dismiss) { Text("取消") } }
    )
}

@Composable
private fun ShopEditDialog(item: ShopItemEntity, dismiss: () -> Unit, save: (ShopItemEntity) -> Unit, delete: () -> Unit) {
    var emoji by remember { mutableStateOf(item.emoji) }
    var name by remember { mutableStateOf(item.name) }
    var description by remember { mutableStateOf(item.description) }
    var coins by remember { mutableStateOf(item.coinPrice.toPlainString()) }
    var diamonds by remember { mutableStateOf(item.diamondPrice.toPlainString()) }
    var sortOrder by remember { mutableStateOf(item.sortOrder.toString()) }
    var active by remember { mutableStateOf(item.active) }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text("編輯商品") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Field("Emoji", emoji) { emoji = it }; Field("商品名稱", name) { name = it }; Field("描述", description) { description = it }
            Field("金幣價格", coins) { coins = it }; Field("鑽石價格", diamonds) { diamonds = it }
            Field("排序（數字小的排前面）", sortOrder) { sortOrder = it.filter(Char::isDigit) }
            Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(active, { active = it }); Text("上架商品") }
        } },
        confirmButton = { Button(onClick = { save(item.copy(emoji = emoji.ifBlank { "🎁" }, name = name, description = description, coinPrice = coins.toBigDecimalOrNull() ?: item.coinPrice, diamondPrice = diamonds.toBigDecimalOrNull() ?: item.diamondPrice, sortOrder = sortOrder.toIntOrNull() ?: item.sortOrder, active = active)) }) { Text("儲存") } },
        dismissButton = { Row { TextButton(delete, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("刪除") }; TextButton(dismiss) { Text("取消") } }
        }
    )
}

@Composable
private fun RewardScheduleDialog(item: ShopItemEntity, dismiss: () -> Unit, confirm: (String, String, String, String) -> Unit) {
    var date by remember { mutableStateOf(LocalDate.now().toString()) }
    var start by remember { mutableStateOf("") }
    var end by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text("安排獎勵：${item.name}") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("結束時間留白時預設為一小時後；23:00 以後預設 23:59。", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
            Field("獎勵日期 YYYY-MM-DD", date) { date = it }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Field("開始 HH:mm", start, Modifier.weight(1f)) { start = it }; Field("結束 HH:mm", end, Modifier.weight(1f)) { end = it } }
            Field("描述", note) { note = it }
        } },
        confirmButton = { Button(onClick = { confirm(date, start, end, note) }) { Text("兌換並安排") } },
        dismissButton = { TextButton(dismiss) { Text("取消") } }
    )
}

@Composable
private fun History(state: MissionUiState) {
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Text("錢包交易紀錄", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) }
        if (state.transactions.isEmpty()) item { EmptyHint("完成任務或兌換獎勵後，紀錄會出現在這裡。") }
        items(state.transactions, key = { it.id }) { tx ->
            Card {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (tx.coinChange >= BigDecimal.ZERO) Icons.Default.AddCircle else Icons.Default.RemoveCircle, null, tint = if (tx.coinChange >= BigDecimal.ZERO) Color(0xFF2EAD74) else MaterialTheme.colorScheme.error)
                    Column(Modifier.padding(start = 10.dp).weight(1f)) {
                        Text(tx.note, fontWeight = FontWeight.SemiBold)
                        Text(tx.type.name + " · " + java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(java.util.Date(tx.createdAt)), style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                    }
                    Text("🪙 ${if (tx.coinChange >= BigDecimal.ZERO) "+" else ""}${tx.coinChange}\n💎 ${if (tx.diamondChange >= BigDecimal.ZERO) "+" else ""}${tx.diamondChange}", color = Violet)
                }
            }
        }
    }
}

@Composable
private fun LegacySettings(vm: MissionViewModel) {
    var coins by remember { mutableStateOf("") }
    var diamonds by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("手動調整") }
    Column(Modifier.fillMaxSize().padding(20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("設定", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("手動調整錢包", fontWeight = FontWeight.Bold)
                Field("金幣增減", coins) { coins = it }
                Field("鑽石增減", diamonds) { diamonds = it }
                Field("說明", note) { note = it }
                Button(onClick = { vm.adjust(coins.toBigDecimalOrNull() ?: BigDecimal.ZERO, diamonds.toBigDecimalOrNull() ?: BigDecimal.ZERO, note); coins = ""; diamonds = "" }, modifier = Modifier.fillMaxWidth()) { Text("確認調整") }
            }
        }
    }
}

@Composable
private fun MonthlySummary(state: MissionUiState, vm: MissionViewModel) {
    val stats = state.monthlyStats
    val month = state.selectedMonth
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("每月統計", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                IconButton({ vm.loadMonth(month.minusMonths(1)) }) { Icon(Icons.Default.ChevronLeft, "上個月") }
                Text(month.format(DateTimeFormatter.ofPattern("yyyy 年 M 月")), fontWeight = FontWeight.Bold)
                IconButton({ vm.loadMonth(month.plusMonths(1)) }) { Icon(Icons.Default.ChevronRight, "下個月") }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                MonthlyMetric("完成任務比例", percent(stats?.completionRatio ?: 0f), Modifier.weight(1f))
                MonthlyMetric("獎勵加權比例", percent(stats?.weightedCompletionRatio ?: 0f), Modifier.weight(1f))
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                MonthlyMetric("完成 / 規劃", "${stats?.settledTasks ?: 0} / ${stats?.plannedTasks ?: 0}", Modifier.weight(1f))
                MonthlyMetric("有完成的天數", "${stats?.activeDays ?: 0} 天", Modifier.weight(1f))
            }
        }
        item {
            Card {
                Column(Modifier.padding(16.dp)) {
                    Text("每日完成比例", fontWeight = FontWeight.Bold)
                    Text("依每日任務的平均完成度計算", color = Color.Gray, style = MaterialTheme.typography.labelSmall)
                    CompletionLineChart(stats?.daily.orEmpty(), Modifier.fillMaxWidth().height(176.dp).padding(top = 12.dp))
                }
            }
        }
        item {
            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("各分類完成比例", fontWeight = FontWeight.Bold)
                    if (stats?.categories.isNullOrEmpty()) Text("本月尚無任務資料。", color = Color.Gray)
                    stats?.categories.orEmpty().forEach { category ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${category.icon} ${category.name}", modifier = Modifier.width(112.dp))
                            LinearProgressIndicator(progress = { category.completion.coerceIn(0f, 1f) }, modifier = Modifier.weight(1f))
                            Text(percent(category.completion), modifier = Modifier.padding(start = 8.dp), style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Ink)) {
                Row(Modifier.fillMaxWidth().padding(18.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column { Text("本月實得獎勵", color = Color.White.copy(alpha = .7f)); Text("🪙 ${stats?.earnedCoins ?: 0}", color = Gold, fontWeight = FontWeight.Bold) }
                    Text("💎 ${stats?.earnedDiamonds ?: 0}", color = Diamond, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 22.dp))
                }
            }
        }
        item {
            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("每日總結", fontWeight = FontWeight.Bold)
                    val summaries = state.dailySummaries.filter { it.date.startsWith(month.toString()) }
                    if (summaries.isEmpty()) Text("本月尚未撰寫每日總結。", color = Color.Gray)
                    summaries.forEach { summary ->
                        Column {
                            Text(runCatching { LocalDate.parse(summary.date).format(DateTimeFormatter.ofPattern("M 月 d 日")) }.getOrDefault(summary.date), fontWeight = FontWeight.SemiBold)
                            Text(summary.content, color = Color.Gray)
                        }
                    }
                }
            }
        }
        item { Spacer(Modifier.height(72.dp)) }
    }
}

@Composable
private fun MonthlyMetric(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier = modifier) { Column(Modifier.padding(14.dp)) { Text(label, color = Color.Gray, style = MaterialTheme.typography.labelSmall); Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Violet) } }
}

@Composable
private fun CompletionLineChart(values: List<DailyCompletion>, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val width = size.width
        val height = size.height
        drawLine(Color.LightGray, start = androidx.compose.ui.geometry.Offset(0f, height), end = androidx.compose.ui.geometry.Offset(width, height), strokeWidth = 2f)
        drawLine(Color.LightGray.copy(alpha = .5f), start = androidx.compose.ui.geometry.Offset(0f, height / 2), end = androidx.compose.ui.geometry.Offset(width, height / 2), strokeWidth = 1f)
        if (values.size > 1) {
            values.zipWithNext().forEachIndexed { index, (first, second) ->
                val x1 = width * index / (values.size - 1)
                val x2 = width * (index + 1) / (values.size - 1)
                drawLine(Violet, androidx.compose.ui.geometry.Offset(x1, height * (1f - first.completion.coerceIn(0f, 1f))), androidx.compose.ui.geometry.Offset(x2, height * (1f - second.completion.coerceIn(0f, 1f))), strokeWidth = 5f)
            }
        }
        values.forEachIndexed { index, point ->
            val x = if (values.size <= 1) width / 2 else width * index / (values.size - 1)
            val y = height * (1f - point.completion.coerceIn(0f, 1f))
            drawCircle(Violet, 5f, androidx.compose.ui.geometry.Offset(x, y))
        }
    }
}

private fun percent(value: Float): String = "${(value.coerceIn(0f, 1f) * 100).roundToInt()}%"

private enum class SettingPage { HOME, WALLET, TRANSACTIONS, SCHEDULES, PUZZLES, PUZZLE_EDITOR }

@Composable
private fun Settings(state: MissionUiState, vm: MissionViewModel) {
    var page by remember { mutableStateOf(SettingPage.HOME) }
    var editingPuzzle by remember { mutableStateOf<SchedulePuzzleEntity?>(null) }
    val context = LocalContext.current
    var exportText by remember { mutableStateOf<String?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val text = exportText
        if (uri != null && text != null) {
            runCatching { context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(text) } }
        }
        exportText = null
    }
    val scheduleImportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { selected -> runCatching { context.contentResolver.openInputStream(selected)?.bufferedReader()?.use { it.readText() } }.getOrNull()?.let(vm::importSchedule) }
    }
    val puzzleImportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { selected -> runCatching { context.contentResolver.openInputStream(selected)?.bufferedReader()?.use { it.readText() } }.getOrNull()?.let(vm::importPuzzles) }
    }
    when (page) {
        SettingPage.HOME -> SettingsHome(state) { page = it }
        SettingPage.WALLET -> WalletSettings({ page = SettingPage.HOME }, vm)
        SettingPage.TRANSACTIONS -> TransactionHistory({ page = SettingPage.HOME }, state.transactions)
        SettingPage.SCHEDULES -> MySchedules(
            state = state,
            back = { page = SettingPage.HOME },
            select = vm::selectSchedule,
            create = vm::createSchedule,
            rename = vm::renameSchedule,
            delete = vm::deleteSchedule,
            exportWithData = { includeCompletionData ->
                vm.exportSchedule(includeCompletionData) { name, json ->
                    exportText = json
                    val suffix = if (includeCompletionData) "_含完成資料" else ""
                    exportLauncher.launch("${name.replace(Regex("[^A-Za-z0-9_\\-\u4e00-\u9fff]"), "_")}$suffix.json")
                }
            },
            import = { scheduleImportLauncher.launch(arrayOf("application/json", "text/plain")) }
        )
        SettingPage.PUZZLES -> PuzzleSettings(
            state = state,
            back = { page = SettingPage.HOME },
            newPuzzle = { editingPuzzle = null; page = SettingPage.PUZZLE_EDITOR },
            edit = { puzzle -> editingPuzzle = puzzle; page = SettingPage.PUZZLE_EDITOR },
            vm = vm,
            export = { vm.exportPuzzles { json -> exportText = json; exportLauncher.launch("行程拼圖.json") } },
            import = { puzzleImportLauncher.launch(arrayOf("application/json", "text/plain")) }
        )
        SettingPage.PUZZLE_EDITOR -> PuzzleEditorPage(
            initial = editingPuzzle,
            back = { editingPuzzle = null; page = SettingPage.PUZZLES },
            save = { puzzle, updateAppliedInstances ->
                vm.savePuzzle(puzzle, updateAppliedInstances)
                editingPuzzle = null
                page = SettingPage.PUZZLES
            },
            delete = { puzzle, deleteAppliedInstances ->
                vm.deletePuzzle(puzzle, deleteAppliedInstances)
                editingPuzzle = null
                page = SettingPage.PUZZLES
            }
        )
    }
}

@Composable
private fun SettingsHome(state: MissionUiState, open: (SettingPage) -> Unit) {
    val activeName = state.schedules.firstOrNull { it.id == state.activeScheduleId }?.name ?: "載入中"
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Text("設定", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) }
        item { Card(colors = CardDefaults.cardColors(containerColor = Ink)) { Column(Modifier.padding(16.dp)) { Text("目前行程", color = Color.White.copy(alpha = .7f)); Text(activeName, color = Color.White, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) } } }
        item { SettingRow("我的行程", "切換、建立、刪除或匯出行程", Icons.Default.CalendarMonth) { open(SettingPage.SCHEDULES) } }
        item { SettingRow("行程拼圖", "先規劃旅行等特殊日程，再套用到時間軸", Icons.Default.Extension) { open(SettingPage.PUZZLES) } }
        item { SettingRow("錢包調整", "手動增減金幣與鑽石", Icons.Default.AccountBalanceWallet) { open(SettingPage.WALLET) } }
        item { SettingRow("交易紀錄", "查看目前行程的錢包收支", Icons.Default.ReceiptLong) { open(SettingPage.TRANSACTIONS) } }
    }
}

@Composable
private fun SettingRow(title: String, detail: String, icon: androidx.compose.ui.graphics.vector.ImageVector, open: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = open)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = Violet)
            Column(Modifier.padding(start = 14.dp).weight(1f)) { Text(title, fontWeight = FontWeight.Bold); Text(detail, style = MaterialTheme.typography.bodySmall, color = Color.Gray) }
            Icon(Icons.Default.ChevronRight, null, tint = Color.Gray)
        }
    }
}

@Composable
private fun SettingsHeader(title: String, back: () -> Unit, actions: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(back) { Icon(Icons.Default.ArrowBack, "返回") }
        Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.weight(1f))
        actions()
    }
}

@Composable
private fun WalletSettings(back: () -> Unit, vm: MissionViewModel) {
    var coins by remember { mutableStateOf("") }
    var diamonds by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("手動調整") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        SettingsHeader("錢包調整", back)
        Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("正數為增加，負數為扣除。", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
            Field("金幣增減", coins) { coins = it }
            Field("鑽石增減", diamonds) { diamonds = it }
            Field("說明", note) { note = it }
            Button(onClick = { vm.adjust(coins.toBigDecimalOrNull() ?: BigDecimal.ZERO, diamonds.toBigDecimalOrNull() ?: BigDecimal.ZERO, note); coins = ""; diamonds = "" }, Modifier.fillMaxWidth()) { Text("確認調整") }
        }
    }
}

@Composable
private fun TransactionHistory(back: () -> Unit, transactions: List<TransactionEntity>) {
    Column(Modifier.fillMaxSize()) {
        SettingsHeader("交易紀錄", back)
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (transactions.isEmpty()) item { EmptyHint("這個行程還沒有錢包交易紀錄。") }
            items(transactions, key = { it.id }) { tx ->
                Card { Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (tx.coinChange >= BigDecimal.ZERO) Icons.Default.AddCircle else Icons.Default.RemoveCircle, null, tint = if (tx.coinChange >= BigDecimal.ZERO) Color(0xFF2EAD74) else MaterialTheme.colorScheme.error)
                    Column(Modifier.padding(start = 10.dp).weight(1f)) { Text(tx.note, fontWeight = FontWeight.SemiBold); Text(java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(java.util.Date(tx.createdAt)), style = MaterialTheme.typography.labelSmall, color = Color.Gray) }
                    Text("🪙 ${if (tx.coinChange >= BigDecimal.ZERO) "+" else ""}${tx.coinChange}\n💎 ${if (tx.diamondChange >= BigDecimal.ZERO) "+" else ""}${tx.diamondChange}", color = Violet)
                } }
            }
            item { Spacer(Modifier.height(72.dp)) }
        }
    }
}

@Composable
private fun LegacyMySchedules(state: MissionUiState, back: () -> Unit, select: (String) -> Unit, create: (String) -> Unit, delete: (String) -> Unit, export: () -> Unit) {
    var creating by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        SettingsHeader("我的行程", back)
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { Text("切換行程後，任務庫、時間軸、月統計與商城會切換至該行程。", color = Color.Gray, style = MaterialTheme.typography.bodySmall) }
            items(state.schedules, key = { it.id }) { schedule ->
                Card(border = if (schedule.id == state.activeScheduleId) BorderStroke(2.dp, Violet) else null) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(schedule.name, fontWeight = FontWeight.Bold)
                            Text(when { schedule.id == MissionRepository.DEFAULT_SCHEDULE_ID -> "預設行程"; schedule.isSample -> "範例行程"; else -> "自訂行程" }, color = Color.Gray, style = MaterialTheme.typography.labelSmall)
                        }
                        if (schedule.id == state.activeScheduleId) Text("使用中", color = Violet, fontWeight = FontWeight.Bold) else OutlinedButton({ select(schedule.id) }) { Text("切換") }
                    }
                }
            }
            item { OutlinedButton({ creating = true }, Modifier.fillMaxWidth()) { Icon(Icons.Default.Add, null); Text("新增自訂行程") } }
            item { Button(export, Modifier.fillMaxWidth()) { Icon(Icons.Default.IosShare, null); Spacer(Modifier.width(6.dp)); Text("匯出目前行程（JSON）") } }
            item { Spacer(Modifier.height(72.dp)) }
        }
    }
    if (creating) {
        var name by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { creating = false }, title = { Text("新增自訂行程") }, text = { Field("行程名稱", name) { name = it } }, confirmButton = { Button(enabled = name.isNotBlank(), onClick = { create(name); creating = false }) { Text("新增") } }, dismissButton = { TextButton({ creating = false }) { Text("取消") } })
    }
}

@Composable
private fun LegacyPuzzleSettings(state: MissionUiState, back: () -> Unit, vm: MissionViewModel) {
    var creating by remember { mutableStateOf(false) }
    var applying by remember { mutableStateOf<SchedulePuzzleEntity?>(null) }
    Column(Modifier.fillMaxSize()) {
        SettingsHeader("行程拼圖", back)
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { Text("拼圖不會立即加入時間軸。套用時會覆寫指定日期範圍內尚未完成的日程，適合旅行或特殊安排。", color = Color.Gray, style = MaterialTheme.typography.bodySmall) }
            if (state.puzzles.isEmpty()) item { EmptyHint("尚未建立行程拼圖。") }
            items(state.puzzles, key = { it.id }) { puzzle ->
                Card { Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(puzzle.name, fontWeight = FontWeight.Bold)
                    Text("${puzzle.startDate} 至 ${puzzle.endDate} · ${puzzleEntryCount(puzzle.entriesJson)} 個項目", color = Color.Gray, style = MaterialTheme.typography.labelSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button({ applying = puzzle }, Modifier.weight(1f)) { Text("套用到時間軸") }
                    }
                } }
            }
            item { OutlinedButton({ creating = true }, Modifier.fillMaxWidth()) { Icon(Icons.Default.Add, null); Text("新增行程拼圖") } }
            item { Spacer(Modifier.height(72.dp)) }
        }
    }
    if (creating) PuzzleEditorDialog(state.activeScheduleId, { creating = false }) { puzzle -> vm.savePuzzle(puzzle); creating = false }
    applying?.let { puzzle -> AlertDialog(onDismissRequest = { applying = null }, title = { Text("套用行程拼圖？") }, text = { Text("會覆寫目前時間軸中尚未完成的日程。") }, confirmButton = { Button({ vm.applyPuzzle(puzzle, LocalDate.now().toString(), "00:00"); applying = null }) { Text("確認套用") } }, dismissButton = { TextButton({ applying = null }) { Text("取消") } }) }
}

@Composable
private fun PuzzleEditorDialog(scheduleId: String, dismiss: () -> Unit, save: (SchedulePuzzleEntity) -> Unit) {
    var name by remember { mutableStateOf("") }
    var startDate by remember { mutableStateOf(LocalDate.now().toString()) }
    var endDate by remember { mutableStateOf(LocalDate.now().toString()) }
    var entryDate by remember { mutableStateOf(LocalDate.now().toString()) }
    var entryStart by remember { mutableStateOf("") }
    var entryEnd by remember { mutableStateOf("") }
    var entryName by remember { mutableStateOf("") }
    var entryDescription by remember { mutableStateOf("") }
    var entries by remember { mutableStateOf(emptyList<PuzzleEntry>()) }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text("新增行程拼圖") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Field("拼圖名稱", name) { name = it }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Field("開始日期 YYYY-MM-DD", startDate, Modifier.weight(1f)) { startDate = it }; Field("結束日期 YYYY-MM-DD", endDate, Modifier.weight(1f)) { endDate = it } }
            HorizontalDivider(); Text("加入拼圖項目", fontWeight = FontWeight.Bold)
            Field("項目日期 YYYY-MM-DD", entryDate) { entryDate = it }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Field("開始 HH:mm", entryStart, Modifier.weight(1f)) { entryStart = it }; Field("結束 HH:mm", entryEnd, Modifier.weight(1f)) { entryEnd = it } }
            Field("任務名稱", entryName) { entryName = it }; Field("描述", entryDescription) { entryDescription = it }
            OutlinedButton(enabled = entryName.isNotBlank(), onClick = { entries = entries + PuzzleEntry(0, 60, entryName, entryDescription); entryStart = ""; entryEnd = ""; entryName = ""; entryDescription = "" }, modifier = Modifier.fillMaxWidth()) { Text("加入此項目") }
            entries.forEachIndexed { index, entry -> Row(verticalAlignment = Alignment.CenterVertically) { Text(entry.name, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall); IconButton({ entries = entries.filterIndexed { i, _ -> i != index } }) { Icon(Icons.Default.Close, "移除") } } }
        } },
        confirmButton = { Button(enabled = name.isNotBlank(), onClick = { save(SchedulePuzzleEntity(scheduleId = scheduleId, name = name, startDate = startDate, endDate = endDate, entriesJson = puzzleEntriesJson(entries))) }) { Text("儲存拼圖") } },
        dismissButton = { TextButton(dismiss) { Text("取消") }
        }
    )
}

private fun puzzleEntriesJson(entries: List<PuzzleEntry>): String = JSONArray(entries.map { entry -> JSONObject().apply { put("id", entry.id); put("offsetMinutes", entry.offsetMinutes); put("durationMinutes", entry.durationMinutes); put("name", entry.name); put("description", entry.description); put("categoryKey", entry.categoryKey); put("priority", entry.priority.name) } }).toString()
private fun puzzleEntryCount(json: String): Int = runCatching { JSONArray(json).length() }.getOrDefault(0)

@Composable
private fun MySchedules(
    state: MissionUiState,
    back: () -> Unit,
    select: (String) -> Unit,
    create: (String) -> Unit,
    rename: (String, String) -> Unit,
    delete: (String) -> Unit,
    exportWithData: (Boolean) -> Unit,
    import: () -> Unit
) {
    var creating by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<ScheduleEntity?>(null) }
    var choosingExport by remember { mutableStateOf(false) }
    val export: () -> Unit = { choosingExport = true }
    Column(Modifier.fillMaxSize()) {
        SettingsHeader("我的行程", back)
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { Text("目前資料庫的既有內容已成為預設行程；範例行程僅供參考。", color = Color.Gray, style = MaterialTheme.typography.bodySmall) }
            items(state.schedules, key = { it.id }) { schedule ->
                Card(border = if (schedule.id == state.activeScheduleId) BorderStroke(2.dp, Violet) else null) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(schedule.name, fontWeight = FontWeight.Bold)
                            Text(when { schedule.id == MissionRepository.DEFAULT_SCHEDULE_ID -> "預設行程"; schedule.isSample -> "範例行程"; else -> "自訂行程" }, color = Color.Gray, style = MaterialTheme.typography.labelSmall)
                        }
                        if (schedule.id == state.activeScheduleId) Text("使用中", color = Violet, fontWeight = FontWeight.Bold) else OutlinedButton({ select(schedule.id) }) { Text("切換") }
                        IconButton({ editing = schedule }) { Icon(Icons.Default.Settings, "編輯行程") }
                    }
                }
            }
            item { OutlinedButton({ creating = true }, Modifier.fillMaxWidth()) { Icon(Icons.Default.Add, null); Text("新增自訂行程") } }
            item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) { OutlinedButton(import, Modifier.weight(1f)) { Text("匯入行程") }; Button(export, Modifier.weight(1f)) { Text("匯出目前行程") } } }
            item { Spacer(Modifier.height(72.dp)) }
        }
    }
    if (creating) ScheduleNameDialog(
        title = "新增自訂行程",
        initialName = "",
        dismiss = { creating = false },
        save = { name -> create(name); creating = false }
    )
    if (choosingExport) AlertDialog(
        onDismissRequest = { choosingExport = false },
        title = { Text("選擇匯出內容") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("純行程只包含任務類別、任務與商城商品；包含完成資料會額外帶入已完成任務的完成度、心得、獲得獎勵與每日總結，並將任務獎勵加到錢包。")
                OutlinedButton(onClick = { exportWithData(false); choosingExport = false }, modifier = Modifier.fillMaxWidth()) { Text("純行程") }
                Button(onClick = { exportWithData(true); choosingExport = false }, modifier = Modifier.fillMaxWidth()) { Text("包含完成資料") }
            }
        },
        confirmButton = { TextButton({ choosingExport = false }) { Text("取消") } }
    )
    editing?.let { schedule ->
        ScheduleNameDialog(
            title = "編輯行程名稱",
            initialName = schedule.name,
            dismiss = { editing = null },
            save = { name -> rename(schedule.id, name); editing = null },
            delete = if (schedule.id == MissionRepository.DEFAULT_SCHEDULE_ID) null else { { delete(schedule.id); editing = null } }
        )
    }
}

@Composable
private fun ScheduleNameDialog(title: String, initialName: String, dismiss: () -> Unit, save: (String) -> Unit, delete: (() -> Unit)? = null) {
    var name by remember { mutableStateOf(initialName) }
    var confirmDelete by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(title) },
        text = { Field("行程名稱", name) { name = it } },
        confirmButton = { Button(enabled = name.isNotBlank(), onClick = { save(name) }) { Text("儲存") } },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                delete?.let { TextButton({ confirmDelete = true }) { Text("刪除", color = MaterialTheme.colorScheme.error) } }
                TextButton(dismiss) { Text("取消") }
            }
        }
    )
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("刪除行程？") },
        text = { Text("此行程的任務、商城內容與紀錄都會一併刪除，無法復原。") },
        confirmButton = { Button({ delete?.invoke(); confirmDelete = false }) { Text("確認刪除") } },
        dismissButton = { TextButton({ confirmDelete = false }) { Text("取消") } }
    )
}

@Composable
private fun PuzzleSettings(
    state: MissionUiState,
    back: () -> Unit,
    newPuzzle: () -> Unit,
    edit: (SchedulePuzzleEntity) -> Unit,
    vm: MissionViewModel,
    export: () -> Unit,
    import: () -> Unit
) {
    var applying by remember { mutableStateOf<SchedulePuzzleEntity?>(null) }
    var expandedPuzzleId by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize()) {
        SettingsHeader("行程拼圖", back)
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { Text("拼圖是跨行程保留的模板。建立時只設定時長與相對項目；套用時才指定真正的起始日期時間。", color = Color.Gray, style = MaterialTheme.typography.bodySmall) }
            if (state.puzzles.isEmpty()) item { EmptyHint("尚未建立行程拼圖。") }
            items(state.puzzles, key = { it.id }) { puzzle ->
                Card { Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(puzzle.name, fontWeight = FontWeight.Bold)
                    puzzle.description.takeIf { it.isNotBlank() }?.let { Text(it, color = Color.Gray, style = MaterialTheme.typography.bodySmall) }
                    Text("${if (puzzle.puzzleType == PuzzleType.SINGLE_DAY) "單日拼圖" else "跨日拼圖"} · ${puzzleDurationText(puzzle)} · ${puzzleEntryCount(puzzle.entriesJson)} 個已規劃項目", color = Color.Gray, style = MaterialTheme.typography.labelSmall)
                    if (expandedPuzzleId == puzzle.id) PuzzleTimelinePreview(parsePuzzleEntriesForUi(puzzle.entriesJson), if (puzzle.puzzleType == PuzzleType.MULTI_DAY) puzzle.durationDays else 1, Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton({ expandedPuzzleId = if (expandedPuzzleId == puzzle.id) null else puzzle.id }) { Text(if (expandedPuzzleId == puzzle.id) "收起預覽" else "查看預覽") }
                        Button({ applying = puzzle }, Modifier.weight(1f)) { Text("套用到時間軸") }
                        IconButton({ edit(puzzle) }) { Icon(Icons.Default.Settings, "編輯拼圖") }
                    }
                } }
            }
            item { OutlinedButton(newPuzzle, Modifier.fillMaxWidth()) { Icon(Icons.Default.Add, null); Text("新增行程拼圖") } }
            item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) { OutlinedButton(import, Modifier.weight(1f)) { Text("匯入拼圖") }; Button(export, Modifier.weight(1f)) { Text("匯出拼圖") } } }
            item { Spacer(Modifier.height(72.dp)) }
        }
    }
    applying?.let { puzzle -> PuzzleApplyDialog(puzzle, { applying = null }, vm)
    }
}

@Composable
private fun PuzzleEditorPage(initial: SchedulePuzzleEntity? = null, back: () -> Unit, save: (SchedulePuzzleEntity, Boolean) -> Unit, delete: (SchedulePuzzleEntity, Boolean) -> Unit) {
    var name by remember(initial?.id) { mutableStateOf(initial?.name.orEmpty()) }
    var description by remember(initial?.id) { mutableStateOf(initial?.description.orEmpty()) }
    var puzzleType by remember(initial?.id) { mutableStateOf(initial?.puzzleType ?: PuzzleType.SINGLE_DAY) }
    var days by remember(initial?.id) { mutableStateOf((initial?.durationDays?.takeIf { initial.puzzleType == PuzzleType.MULTI_DAY } ?: 2).toString()) }
    var hours by remember(initial?.id) { mutableStateOf((initial?.let { if (it.puzzleType == PuzzleType.SINGLE_DAY) it.durationDays * 24 + it.durationHours else it.durationHours } ?: 1).toString()) }
    var minutes by remember(initial?.id) { mutableStateOf((initial?.durationMinutes ?: 0).toString()) }
    var step by remember(initial?.id) { mutableStateOf(0) }
    var selectedDay by remember(initial?.id) { mutableIntStateOf(1) }
    var itemTime by remember(initial?.id) { mutableStateOf("00:00") }
    var itemDuration by remember(initial?.id) { mutableStateOf("60") }
    var itemName by remember(initial?.id) { mutableStateOf("") }
    var itemDescription by remember(initial?.id) { mutableStateOf("") }
    var itemCategoryKey by remember(initial?.id) { mutableStateOf("other") }
    var itemPriority by remember(initial?.id) { mutableStateOf(TaskPriority.NONE) }
    var entries by remember(initial?.id) { mutableStateOf(initial?.let { parsePuzzleEntriesForUi(it.entriesJson) }.orEmpty()) }
    var editingEntryIndex by remember(initial?.id) { mutableStateOf<Int?>(null) }
    var updateAppliedInstances by remember(initial?.id) { mutableStateOf(false) }
    var confirmDelete by remember(initial?.id) { mutableStateOf(false) }
    var deleteAppliedInstances by remember(initial?.id) { mutableStateOf(false) }
    var pendingEntry by remember(initial?.id) { mutableStateOf<PuzzleEntry?>(null) }
    var confirmSingleDayConversion by remember(initial?.id) { mutableStateOf(false) }
    val dayCount = if (puzzleType == PuzzleType.MULTI_DAY) (days.toIntOrNull() ?: 2).coerceAtLeast(2) else 1
    val limitedDayDuration = ((hours.toIntOrNull()?.coerceAtLeast(0) ?: 0) * 60 + (minutes.toIntOrNull()?.coerceAtLeast(0) ?: 0))
    val configuredDuration = limitedDayDuration.coerceAtMost(1440)
    fun nextOffsetForDay(day: Int): Int = entries.filter { it.offsetMinutes / 1440 + 1 == day }
        .maxOfOrNull { it.offsetMinutes + it.durationMinutes } ?: (day - 1) * 1440
    fun resetItemForm(nextOffset: Int = (selectedDay - 1) * 1440) {
        val nextDay = (nextOffset / 1440 + 1).coerceAtMost(dayCount)
        selectedDay = nextDay
        itemTime = "%02d:%02d".format((nextOffset % 1440) / 60, nextOffset % 60)
        itemName = ""; itemDescription = ""; itemDuration = "60"
        itemCategoryKey = "other"; itemPriority = TaskPriority.NONE; editingEntryIndex = null
    }
    fun saveEntry(entry: PuzzleEntry) {
        entries = if (editingEntryIndex == null) entries + entry else entries.mapIndexed { index, existing -> if (index == editingEntryIndex) entry else existing }
        resetItemForm(entry.offsetMinutes + entry.durationMinutes)
    }
    fun expandDurationTo(totalMinutes: Int) {
        hours = (totalMinutes / 60).toString()
        minutes = (totalMinutes % 60).toString()
    }
    Column(Modifier.fillMaxSize()) {
        SettingsHeader(
            when {
                step == 0 && initial == null -> "新增行程拼圖"
                step == 0 -> "編輯行程拼圖"
                else -> "編排行程拼圖"
            },
            { if (step == 1) step = 0 else back() },
            actions = { if (initial != null) IconButton({ confirmDelete = true }) { Icon(Icons.Default.Delete, "刪除拼圖", tint = MaterialTheme.colorScheme.error) } }
        )
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (step == 0) {
                item { Text("先設定拼圖類型、基本資料與時長，下一步再安排任務。", color = Color.Gray, style = MaterialTheme.typography.bodySmall) }
                item { Field("拼圖名稱", name) { name = it } }
                item { Field("拼圖描述", description) { description = it } }
                item { Text("拼圖類型", fontWeight = FontWeight.Bold) }
                item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(puzzleType == PuzzleType.SINGLE_DAY, {
                        if (puzzleType == PuzzleType.MULTI_DAY && entries.any { it.offsetMinutes >= 1440 }) confirmSingleDayConversion = true
                        else { puzzleType = PuzzleType.SINGLE_DAY; selectedDay = 1 }
                    }, { Text("單日拼圖") })
                    FilterChip(puzzleType == PuzzleType.MULTI_DAY, { puzzleType = PuzzleType.MULTI_DAY; selectedDay = 1 }, { Text("跨日拼圖") })
                } }
                if (puzzleType == PuzzleType.SINGLE_DAY) {
                    item { Text("單日時長（最多 24 小時）", fontWeight = FontWeight.Bold) }
                    item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Field("幾時", hours, Modifier.weight(1f)) { hours = it.filter(Char::isDigit).take(2) }; Field("幾分", minutes, Modifier.weight(1f)) { minutes = it.filter(Char::isDigit).take(2) } } }
                } else {
                    item { Text("跨日設定", fontWeight = FontWeight.Bold) }
                    item { Field("總天數（至少 2 天）", days) { days = it.filter(Char::isDigit) } }
                    item { Text("最後一天時長（最多 24 小時）", fontWeight = FontWeight.Bold) }
                    item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Field("幾時", hours, Modifier.weight(1f)) { hours = it.filter(Char::isDigit).take(2) }; Field("幾分", minutes, Modifier.weight(1f)) { minutes = it.filter(Char::isDigit).take(2) } } }
                }
                if (limitedDayDuration !in 1..1440) item { Text("時長須介於 1 分鐘至 24 小時。", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                item { Button(enabled = name.isNotBlank() && limitedDayDuration in 1..1440 && (puzzleType == PuzzleType.SINGLE_DAY || dayCount >= 2), onClick = { step = 1 }, modifier = Modifier.fillMaxWidth()) { Text("下一步：設定任務") } }
            } else {
            item { Text(if (puzzleType == PuzzleType.SINGLE_DAY) "未安排項目的時間段會保留空白。" else "第 2 天起均從 00:00 開始計算；可切換查看每一天的規劃。", color = Color.Gray, style = MaterialTheme.typography.bodySmall) }
            if (puzzleType == PuzzleType.MULTI_DAY) item { Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                IconButton(enabled = selectedDay > 1, onClick = { selectedDay--; resetItemForm(nextOffsetForDay(selectedDay)) }) { Icon(Icons.Default.ChevronLeft, "前一天") }
                Text(if (selectedDay == dayCount) "最後一天（第 $selectedDay 天）" else "第 $selectedDay 天", modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center, fontWeight = FontWeight.Bold)
                IconButton(enabled = selectedDay < dayCount, onClick = { selectedDay++; resetItemForm(nextOffsetForDay(selectedDay)) }) { Icon(Icons.Default.ChevronRight, "後一天") }
            } }
            item { Text("加入第 $selectedDay 天相對日程項目", fontWeight = FontWeight.Bold) }
            item { Field("相對時間 HH:mm", itemTime) { itemTime = it } }
            item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Field("持續分鐘", itemDuration, Modifier.weight(1f)) { itemDuration = it.filter(Char::isDigit) }; Field("任務名稱", itemName, Modifier.weight(2f)) { itemName = it } } }
            item { Field("描述", itemDescription) { itemDescription = it } }
            item { Text("任務類型", fontWeight = FontWeight.Bold) }
            item { PuzzleCategoryPicker(itemCategoryKey) { itemCategoryKey = it } }
            item { Text("重要程度", fontWeight = FontWeight.Bold) }
            item { PriorityPicker(itemPriority) { itemPriority = it } }
            item { OutlinedButton(enabled = itemName.isNotBlank() && runCatching { LocalTime.parse(itemTime) }.isSuccess, onClick = {
                val time = LocalTime.parse(itemTime)
                val offset = (selectedDay - 1) * 1440 + time.hour * 60 + time.minute
                val original = editingEntryIndex?.let(entries::getOrNull)
                val updated = PuzzleEntry(offset, itemDuration.toIntOrNull()?.coerceAtLeast(1) ?: 60, itemName, itemDescription, original?.id ?: java.util.UUID.randomUUID().toString(), itemCategoryKey, itemPriority)
                val endOfDay = time.hour * 60 + time.minute + updated.durationMinutes
                if (endOfDay > 1440) return@OutlinedButton
                val limitedDay = puzzleType == PuzzleType.SINGLE_DAY || selectedDay == dayCount
                if (limitedDay && endOfDay > configuredDuration) pendingEntry = updated else saveEntry(updated)
            }, modifier = Modifier.fillMaxWidth()) { Text(if (editingEntryIndex == null) "加入拼圖項目" else "更新拼圖項目") } }
            val inputEnd = runCatching { LocalTime.parse(itemTime).toSecondOfDay() / 60 + (itemDuration.toIntOrNull() ?: 0) }.getOrDefault(0)
            if (inputEnd > 1440) item { Text("任務不可超過當天 24:00。", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            if (editingEntryIndex != null) item { TextButton({ resetItemForm() }) { Text("取消項目編輯") } }
            item { Text("第 $selectedDay 天時間軸預覽", fontWeight = FontWeight.Bold) }
            item { PuzzleTimelinePreview(entries.filter { it.offsetMinutes / 1440 + 1 == selectedDay }.map { it.copy(offsetMinutes = it.offsetMinutes % 1440) }, 1, Modifier.fillMaxWidth()) }
            items(entries.indices.filter { entries[it].offsetMinutes / 1440 + 1 == selectedDay }, key = { entries[it].id }) { index ->
                val entry = entries[index]
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${puzzleEntryLabel(entry)} · ${entry.name}", modifier = Modifier.weight(1f))
                    IconButton({
                        editingEntryIndex = index
                        selectedDay = entry.offsetMinutes / 1440 + 1
                        itemTime = "%02d:%02d".format((entry.offsetMinutes % 1440) / 60, entry.offsetMinutes % 60)
                        itemDuration = entry.durationMinutes.toString()
                        itemName = entry.name
                        itemDescription = entry.description
                        itemCategoryKey = entry.categoryKey
                        itemPriority = entry.priority
                    }) { Icon(Icons.Default.Settings, "編輯項目") }
                    IconButton({
                        entries = entries.filterIndexed { itemIndex, _ -> itemIndex != index }
                        if (editingEntryIndex == index) resetItemForm()
                    }) { Icon(Icons.Default.Close, "移除") }
                }
            }
            if (initial != null) item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(updateAppliedInstances, { updateAppliedInstances = it })
                    Column(Modifier.padding(start = 6.dp)) {
                        Text("同步更新已套用的拼圖實例")
                        Text("只會更新尚未完成的項目；已完成項目會保留。", color = Color.Gray, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            item { Button(enabled = name.isNotBlank() && limitedDayDuration in 1..1440, onClick = {
                val base = initial ?: SchedulePuzzleEntity(scheduleId = MissionRepository.DEFAULT_SCHEDULE_ID, name = "", startDate = "", endDate = "")
                save(base.copy(name = name, description = description, startDate = "", endDate = "", entriesJson = puzzleEntriesJson(entries), puzzleType = puzzleType, durationDays = if (puzzleType == PuzzleType.MULTI_DAY) dayCount else 0, durationHours = hours.toIntOrNull() ?: 0, durationMinutes = minutes.toIntOrNull() ?: 0), updateAppliedInstances)
            }, modifier = Modifier.fillMaxWidth()) { Text("儲存拼圖") } }
            item { Spacer(Modifier.height(72.dp)) }
            }
        }
    }
    pendingEntry?.let { entry -> AlertDialog(
        onDismissRequest = { pendingEntry = null },
        title = { Text("超出拼圖時長") },
        text = { Text("此任務超出${if (puzzleType == PuzzleType.SINGLE_DAY) "單日" else "最後一天"}設定時長。確認後會自動擴大至任務結束時間，最多 24 小時。") },
        confirmButton = { Button({ expandDurationTo((entry.offsetMinutes % 1440) + entry.durationMinutes); saveEntry(entry); pendingEntry = null }) { Text("自動擴大並加入") } },
        dismissButton = { TextButton({ pendingEntry = null }) { Text("取消") } }
    ) }
    if (confirmSingleDayConversion) AlertDialog(
        onDismissRequest = { confirmSingleDayConversion = false },
        title = { Text("改為單日拼圖？") },
        text = { Text("改為單日拼圖會移除第 2 天以後的 ${entries.count { it.offsetMinutes >= 1440 }} 個任務，且無法復原。") },
        confirmButton = { Button({ entries = entries.filter { it.offsetMinutes < 1440 }; puzzleType = PuzzleType.SINGLE_DAY; selectedDay = 1; resetItemForm(); confirmSingleDayConversion = false }) { Text("移除後續任務並轉換") } },
        dismissButton = { TextButton({ confirmSingleDayConversion = false }) { Text("取消") } }
    )
    if (confirmDelete && initial != null) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("刪除行程拼圖？") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("可選擇是否連同這個拼圖已套用到時間軸的實例一併刪除。")
            Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(deleteAppliedInstances, { deleteAppliedInstances = it }); Text("同時刪除已套用的拼圖實例") }
        } },
        confirmButton = { Button({ delete(initial, deleteAppliedInstances); confirmDelete = false }) { Text("確認刪除") } },
        dismissButton = { TextButton({ confirmDelete = false }) { Text("取消") } }
    )
}

@Composable
private fun PuzzleTimelinePreview(entries: List<PuzzleEntry>, days: Int, modifier: Modifier = Modifier) {
    val availableDays = maxOf(days.coerceAtLeast(1), (entries.maxOfOrNull { it.offsetMinutes / 1440 + 1 } ?: 1))
    var viewingDay by remember(entries, days) { mutableIntStateOf(1) }
    val dayEntries = entries.filter { it.offsetMinutes / 1440 + 1 == viewingDay }.sortedBy { it.offsetMinutes }
    Card(modifier = modifier) { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (availableDays > 1) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            IconButton(enabled = viewingDay > 1, onClick = { viewingDay-- }) { Icon(Icons.Default.ChevronLeft, "前一天") }
            Text("第 $viewingDay 天時間軸", modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center, fontWeight = FontWeight.Bold)
            IconButton(enabled = viewingDay < availableDays, onClick = { viewingDay++ }) { Icon(Icons.Default.ChevronRight, "後一天") }
        } else Text("時間軸預覽", fontWeight = FontWeight.Bold)
        if (dayEntries.isEmpty()) Text("此日保留空白。", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
        dayEntries.forEach { entry ->
            val minute = entry.offsetMinutes % 1440
            val end = minute + entry.durationMinutes
            Row(verticalAlignment = Alignment.Top, modifier = Modifier.fillMaxWidth()) {
                Text("%02d:%02d".format(minute / 60, minute % 60), modifier = Modifier.width(48.dp), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                Box(Modifier.width(3.dp).height(62.dp).background(priorityColor(entry.priority) ?: Violet.copy(alpha = .45f)))
                Card(border = priorityColor(entry.priority)?.let { BorderStroke(2.dp, it) }, modifier = Modifier.padding(start = 8.dp).weight(1f)) {
                    Column(Modifier.padding(9.dp)) {
                        Text(entry.name, fontWeight = FontWeight.SemiBold)
                        Text("%02d:%02d – %02d:%02d · ${entry.durationMinutes} 分".format(minute / 60, minute % 60, end / 60, end % 60), color = Color.Gray, style = MaterialTheme.typography.labelSmall)
                        entry.description.takeIf { it.isNotBlank() }?.let { Text(it, maxLines = 1, color = Color.Gray, style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
        }
    } }
}

@Composable
private fun PuzzleApplyDialog(puzzle: SchedulePuzzleEntity, dismiss: () -> Unit, vm: MissionViewModel) {
    var date by remember { mutableStateOf(LocalDate.now().toString()) }
    var time by remember { mutableStateOf("09:00") }
    var preview by remember { mutableStateOf<PuzzleApplicationPreview?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var detail by remember { mutableStateOf(false) }
    LaunchedEffect(puzzle.id, date, time) {
        if (runCatching { LocalDate.parse(date); LocalTime.parse(time) }.isSuccess) {
            error = null
            preview = null
            vm.previewPuzzle(puzzle, date, time, { preview = it }, { error = it })
        }
    }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text("套用：${puzzle.name}") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("拼圖時長：${puzzleDurationText(puzzle)}", color = Color.Gray)
            Field("開始日期 YYYY-MM-DD", date) { date = it }
            Field("開始時間 HH:mm", time) { time = it }
            error?.let { Text("⚠ $it", color = MaterialTheme.colorScheme.error) }
            preview?.let { result ->
                val names = result.overwritten
                if (names.isEmpty()) Text("不會覆寫現有未完成日程。", color = Color(0xFF2EAD74))
                else {
                    Text("將覆寫 ${names.size} 個未完成日程：${names.take(3).joinToString("、")}", color = MaterialTheme.colorScheme.error)
                    if (names.size > 3) TextButton({ detail = true }) { Text("查看詳細") }
                }
                if (result.skippedEntries.isNotEmpty()) {
                    Text("⚠ 目前開始時間會略過 ${result.skippedEntries.size} 個超出當天的項目：${result.skippedEntries.take(3).joinToString("、")}", color = MaterialTheme.colorScheme.error)
                    result.latestFullStart?.let { Text("若要完整拼入，開始時間請設定為 $it 或更早。", color = Color.Gray, style = MaterialTheme.typography.bodySmall) }
                }
            } ?: Text("正在檢查會被覆寫的日程…", color = Color.Gray)
        } },
        confirmButton = { Button(enabled = error == null && preview != null && date.length == 10 && time.length == 5, onClick = { vm.applyPuzzle(puzzle, date, time); dismiss() }) { Text("確認套用") } },
        dismissButton = { TextButton(dismiss) { Text("取消") } }
    )
    if (detail) AlertDialog(onDismissRequest = { detail = false }, title = { Text("將覆寫的日程") }, text = { Text(preview?.overwritten.orEmpty().joinToString("\n")) }, confirmButton = { TextButton({ detail = false }) { Text("關閉") } })
}

private fun puzzleDurationText(puzzle: SchedulePuzzleEntity): String = if (puzzle.puzzleType == PuzzleType.SINGLE_DAY) {
    val total = puzzle.durationDays * 1440 + puzzle.durationHours * 60 + puzzle.durationMinutes
    "${total / 60} 時 ${total % 60} 分"
} else "${puzzle.durationDays} 天；最後一天 ${puzzle.durationHours} 時 ${puzzle.durationMinutes} 分"
private fun puzzleEntryLabel(entry: PuzzleEntry): String { val minuteOfDay = entry.offsetMinutes % 1440; return "第 ${entry.offsetMinutes / 1440 + 1} 天 ${"%02d".format(minuteOfDay / 60)}:${"%02d".format(minuteOfDay % 60)}" }
private fun parsePuzzleEntriesForUi(json: String): List<PuzzleEntry> = runCatching {
    JSONArray(json).let { array -> List(array.length()) { index ->
        array.getJSONObject(index).let { entry ->
            val id = entry.optString("id")
            val categoryKey = entry.optString("categoryKey", "other").ifBlank { "other" }
            val priority = runCatching { TaskPriority.valueOf(entry.optString("priority", "NONE")) }.getOrDefault(TaskPriority.NONE)
            if (id.isBlank()) PuzzleEntry(entry.optInt("offsetMinutes"), entry.optInt("durationMinutes", 60), entry.optString("name"), entry.optString("description"), categoryKey = categoryKey, priority = priority)
            else PuzzleEntry(entry.optInt("offsetMinutes"), entry.optInt("durationMinutes", 60), entry.optString("name"), entry.optString("description"), id, categoryKey, priority)
        }
    } }
}.getOrDefault(emptyList())
