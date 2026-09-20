package com.vsp.internetspeedmeter

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.PreferenceFragmentCompat
import com.vsp.internetspeedmeter.broadcastreceiver.InternetService

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Preferences"
        
        if (savedInstanceState == null) {
            supportFragmentManager
                .beginTransaction()
                .replace(android.R.id.content, SettingsFragment())
                .commit()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    class SettingsFragment : PreferenceFragmentCompat() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences, rootKey)
        }

        override fun onPreferenceTreeClick(preference: androidx.preference.Preference): Boolean {
            val context = requireContext()
            when (preference.key) {
                "hide_lockscreen_notification", "show_up_down_speed", "hide_notification_idle" -> {
                    // Send broadcast or intent to update the notification immediately
                    val intent = Intent(context, InternetService::class.java).apply {
                        action = "UPDATE_NOTIFICATION_SETTINGS"
                    }
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                        context.startForegroundService(intent)
                    } else {
                        context.startService(intent)
                    }
                }
            }
            return super.onPreferenceTreeClick(preference)
        }
    }
}
