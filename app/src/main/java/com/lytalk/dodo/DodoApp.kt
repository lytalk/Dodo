package com.lytalk.dodo

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager

class DodoApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.guard_channel),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.guard_channel_desc)
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(
                WAKE_CHANNEL_ID,
                "到点亮屏",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "定时到点时点亮屏幕"
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            },
        )
    }

    companion object {
        const val CHANNEL_ID = "guard"
        const val WAKE_CHANNEL_ID = "wake"
    }
}
