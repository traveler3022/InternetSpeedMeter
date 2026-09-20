package com.vsp.internetspeedmeter

import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.ConfigurationCompat
import androidx.core.os.LocaleListCompat
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import com.vsp.internetspeedmeter.broadcastreceiver.InternetService
import com.vsp.internetspeedmeter.util.FormatUtils
import com.vsp.internetspeedmeter.util.PersianFormat

/** Rebuilds the notification so a changed preference is visible right away. */
private fun refreshNotification(context: android.content.Context?) {
    if (context == null) return
    val intent = Intent(context, InternetService::class.java)
    intent.action = "UPDATE_NOTIFICATION_SETTINGS"
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        context.startForegroundService(intent)
    } else {
        context.startService(intent)
    }
}

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        val defaultPrefs = PreferenceManager.getDefaultSharedPreferences(this)
        if (defaultPrefs.getString("theme_color", "light") == "dark") {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        } else {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
        }
        super.onCreate(savedInstanceState)

        val advanced = intent?.getBooleanExtra(EXTRA_ADVANCED, false) == true
        supportActionBar?.title =
            getString(if (advanced) R.string.advanced_title else R.string.settings_title)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        if (savedInstanceState == null) {
            val fragment = if (advanced) AdvancedFragment() else SettingsFragment()
            supportFragmentManager
                .beginTransaction()
                .replace(android.R.id.content, fragment)
                .commit()
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    class SettingsFragment : PreferenceFragmentCompat(),
        SharedPreferences.OnSharedPreferenceChangeListener {

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences, rootKey)
            updateLimitTitle()
        }

        override fun onPreferenceTreeClick(preference: Preference): Boolean {
            if (preference.key == "advanced_settings") {
                startActivity(
                    Intent(requireContext(), SettingsActivity::class.java)
                        .putExtra(EXTRA_ADVANCED, true)
                )
                return true
            }
            return super.onPreferenceTreeClick(preference)
        }

        override fun onResume() {
            super.onResume()
            preferenceManager.sharedPreferences?.registerOnSharedPreferenceChangeListener(this)
        }

        override fun onPause() {
            super.onPause()
            preferenceManager.sharedPreferences?.unregisterOnSharedPreferenceChangeListener(this)
        }

        override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
            when (key) {
                "theme_color" -> {
                    activity?.recreate()
                    return
                }
                "app_language" -> {
                    applyLanguage(sharedPreferences?.getString("app_language", "system"))
                    return
                }
                "limit_data_warning" -> updateLimitTitle()
            }
            refreshNotification(context)
        }

        private fun applyLanguage(value: String?) {
            val locales = when (value) {
                "fa" -> LocaleListCompat.forLanguageTags("fa")
                "en" -> LocaleListCompat.forLanguageTags("en")
                else -> LocaleListCompat.getEmptyLocaleList()
            }
            AppCompatDelegate.setApplicationLocales(locales)
        }

        /** The reference app keeps the current cap inside the title. */
        private fun updateLimitTitle() {
            val pref = findPreference<EditTextPreference>("limit_data_warning") ?: return
            val mb = pref.text?.trim()?.toLongOrNull() ?: 0L
            val value = if (mb <= 0L) {
                getString(R.string.pref_limit_off)
            } else {
                val bytes = mb * 1024L * 1024L
                if (isPersianUi()) PersianFormat.bytes(bytes) else FormatUtils.formatBytes(bytes)
            }
            pref.title = getString(R.string.pref_limit_title, value)
        }

        private fun isPersianUi(): Boolean =
            ConfigurationCompat.getLocales(resources.configuration)[0]?.language == "fa"
    }

    class AdvancedFragment : PreferenceFragmentCompat(),
        SharedPreferences.OnSharedPreferenceChangeListener {

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.advanced_preferences, rootKey)
        }

        override fun onPreferenceTreeClick(preference: Preference): Boolean {
            if (preference.key == "battery_optimization") {
                openBatterySettings()
                return true
            }
            return super.onPreferenceTreeClick(preference)
        }

        private fun openBatterySettings() {
            val context = context ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                try {
                    startActivity(
                        Intent(
                            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Uri.parse("package:" + context.packageName)
                        )
                    )
                    return
                } catch (_: Exception) {
                }
            }
            try {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + context.packageName)))
            } catch (_: Exception) {
            }
        }

        override fun onResume() {
            super.onResume()
            preferenceManager.sharedPreferences?.registerOnSharedPreferenceChangeListener(this)
        }

        override fun onPause() {
            super.onPause()
            preferenceManager.sharedPreferences?.unregisterOnSharedPreferenceChangeListener(this)
        }

        override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
            if (key == "pause_when_screen_off") {
                refreshNotification(context)
            }
        }
    }

    companion object {
        const val EXTRA_ADVANCED = "advanced"
    }
}
