package com.vsp.internetspeedmeter.broadcastreceiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                val isStarted = prefs.getBoolean(PREF_IS_STARTED, true)

                if (isStarted) {
                    val serviceIntent = Intent(context, InternetService::class.java)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(serviceIntent)
                    } else {
                        context.startService(serviceIntent)
                    }
                }
            }
        }
    }

    companion object {
        private const val PREF_NAME = "internetSpeed"
        private const val PREF_IS_STARTED = "isStarted"
    }
}
