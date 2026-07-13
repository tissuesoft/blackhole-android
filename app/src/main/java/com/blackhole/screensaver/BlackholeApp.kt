package com.blackhole.screensaver

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.blackhole.screensaver.prefs.AppPrefs

class BlackholeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppPrefs.init(this)
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_text)
            setShowBadge(false)
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_ID = "blackhole_service"
        const val ACTION_STATE_CHANGED = "com.blackhole.screensaver.ACTION_STATE_CHANGED"
        const val ACTION_WIDGET_TOGGLE = "com.blackhole.screensaver.ACTION_WIDGET_TOGGLE"
        const val ACTION_INPUT_DETECTED = "com.blackhole.screensaver.ACTION_INPUT_DETECTED"
        const val ACTION_IDLE_SECONDS_CHANGED = "com.blackhole.screensaver.ACTION_IDLE_SECONDS_CHANGED"
    }
}
