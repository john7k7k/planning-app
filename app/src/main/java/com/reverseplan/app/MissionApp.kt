package com.reverseplan.app

import android.app.Application
import com.reverseplan.app.data.AppDatabase
import com.reverseplan.app.domain.MissionRepository

class MissionApp : Application() {
    val repository by lazy { MissionRepository(AppDatabase.create(this)) }
    val taskNotificationScheduler by lazy { TaskNotificationScheduler(this, repository) }

    override fun onCreate() {
        super.onCreate()
        taskNotificationScheduler.createChannel()
    }
}
