package com.airreceiver.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import timber.log.Timber

/**
 * Receives boot completed broadcast to auto-start AirPlay service
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Timber.d("Boot completed, starting AirPlay service")

            val serviceIntent = Intent(context, AirPlayService::class.java).apply {
                action = AirPlayService.ACTION_START
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        }
    }
}
