package com.airreceiver

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import timber.log.Timber

class AirReceiverApplication : Application() {

    companion object {
        const val NOTIFICATION_CHANNEL_ID = "airplay_service"
        lateinit var instance: AirReceiverApplication
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this

        // Initialize Timber for logging
        Timber.plant(Timber.DebugTree())

        // Create notification channel
        createNotificationChannel()

        Timber.d("AirReceiver Application started")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_description)
                setShowBadge(false)
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }
}
