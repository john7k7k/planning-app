package com.reverseplan.app

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.reverseplan.app.data.AppDatabase
import com.reverseplan.app.domain.MissionRepository
import com.reverseplan.app.domain.TaskCardModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

class TaskNotificationScheduler(
    private val context: Context,
    private val repository: MissionRepository
) {
    fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "任務開始提醒",
                NotificationManager.IMPORTANCE_HIGH
            ).apply { description = "任務開始時提醒你開始執行" }
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    /** Keeps the next two weeks of scheduled tasks available even while the app is closed. */
    suspend fun scheduleUpcoming(scheduleId: String, days: Long = 14) {
        createChannel()
        val now = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()
        for (offset in 0..days) {
            val day = LocalDate.now(zone).plusDays(offset)
            repository.dashboard(day, scheduleId).cards.forEach { card ->
                val start = card.task.startTime.takeIf { !card.task.allDay && it.length == 5 } ?: return@forEach
                val triggerAt = runCatching {
                    day.atTime(LocalTime.parse(start)).atZone(zone).toInstant().toEpochMilli()
                }.getOrNull() ?: return@forEach
                if (!card.instance.settled && triggerAt > now) schedule(card, triggerAt)
            }
        }
    }

    private fun schedule(card: TaskCardModel, triggerAt: Long) {
        val intent = Intent(context, TaskStartReceiver::class.java).apply {
            putExtra(EXTRA_INSTANCE_ID, card.instance.id)
            putExtra(EXTRA_TRIGGER_AT, triggerAt)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            card.instance.id.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val alarms = context.getSystemService(AlarmManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && alarms.canScheduleExactAlarms()) {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
        } else {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
        }
    }

    companion object {
        const val CHANNEL_ID = "task_start_reminders"
        const val EXTRA_INSTANCE_ID = "task_instance_id"
        const val EXTRA_TRIGGER_AT = "task_trigger_at"

        fun show(context: Context, instanceId: String, taskName: String, description: String) {
            val launchIntent = Intent(context, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            )
            val contentIntent = PendingIntent.getActivity(
                context,
                instanceId.hashCode(),
                launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_agenda)
                .setContentTitle("任務開始：$taskName")
                .setContentText(description.ifBlank { "現在開始執行這個任務吧。" })
                .setStyle(NotificationCompat.BigTextStyle().bigText(description.ifBlank { "現在開始執行這個任務吧。" }))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(contentIntent)
                .build()
            NotificationManagerCompat.from(context).notify(instanceId.hashCode(), notification)
        }
    }
}

class TaskStartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val instanceId = intent.getStringExtra(TaskNotificationScheduler.EXTRA_INSTANCE_ID) ?: return
        val expectedTriggerAt = intent.getLongExtra(TaskNotificationScheduler.EXTRA_TRIGGER_AT, -1L)
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val db = AppDatabase.create(context.applicationContext)
                val instance = db.taskDao().instanceById(instanceId) ?: return@launch
                val task = db.taskDao().task(instance.taskId) ?: return@launch
                val allDay = instance.allDayOverride ?: task.allDay
                val startTime = instance.startTimeOverride ?: task.startTime
                val actualTriggerAt = runCatching {
                    LocalDate.parse(instance.scheduledDate).atTime(LocalTime.parse(startTime))
                        .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                }.getOrNull()
                if (!instance.deleted && !instance.settled && !allDay && actualTriggerAt == expectedTriggerAt) {
                    TaskNotificationScheduler.show(
                        context,
                        instanceId,
                        instance.nameOverride ?: task.name,
                        instance.descriptionOverride ?: task.description
                    )
                }
                val scheduleId = db.scheduleDao().settings()?.activeScheduleId ?: MissionRepository.DEFAULT_SCHEDULE_ID
                TaskNotificationScheduler(context.applicationContext, MissionRepository(db)).scheduleUpcoming(scheduleId)
            } finally {
                pendingResult.finish()
            }
        }
    }
}

class TaskNotificationRescheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val appContext = context.applicationContext
                val db = AppDatabase.create(appContext)
                val scheduleId = db.scheduleDao().settings()?.activeScheduleId ?: MissionRepository.DEFAULT_SCHEDULE_ID
                TaskNotificationScheduler(appContext, MissionRepository(db)).scheduleUpcoming(scheduleId)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
