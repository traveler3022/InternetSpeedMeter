package com.vsp.internetspeedmeter.ui

import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.lifecycleScope
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.vsp.internetspeedmeter.R
import com.vsp.internetspeedmeter.broadcastreceiver.InternetService
import com.vsp.internetspeedmeter.util.AppCalendar
import com.vsp.internetspeedmeter.util.Backup
import com.vsp.internetspeedmeter.util.CalendarMath
import com.vsp.internetspeedmeter.util.FormatUtils
import com.vsp.internetspeedmeter.util.PackageStore
import com.vsp.internetspeedmeter.util.Palette
import com.vsp.internetspeedmeter.util.PersianFormat
import kotlinx.coroutines.launch

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

/**
 * "تنظیمات" tab: display, package and day, the notification preferences
 * (unchanged keys and behaviour), running, data and about.
 */
class SettingsFragment : PreferenceFragmentCompat(),
    SharedPreferences.OnSharedPreferenceChangeListener {

    private val exportFile = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri -> uri?.let { runBackup(true, it) } }

    private val importFile = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { runBackup(false, it) } }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.preferences, rootKey)
        updateLimitTitle()
        updatePackageSummary()
        findPreference<Preference>("about_version")?.summary = try {
            requireContext().packageManager.getPackageInfo(requireContext().packageName, 0).versionName
        } catch (_: Exception) {
            null
        }
    }

    override fun onViewCreated(view: android.view.View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val list = view.findViewById<RecyclerView>(androidx.preference.R.id.recycler_view)
        list?.apply {
            setBackgroundColor(Palette.color(requireContext(), R.attr.ismPage))
            setPadding(16, 8, 16, 18)
            clipToPadding = false
            itemAnimator = null
        }
    }

    override fun onPreferenceTreeClick(preference: Preference): Boolean {
        when (preference.key) {
            "data_package" -> PackageDialog.show(requireContext()) { updatePackageSummary() }
            "battery_optimization" -> openBatterySettings()
            "backup_export" -> exportFile.launch(
                "internet-meter-" + CalendarMath.dbDate(AppCalendar.today(requireContext())) + ".json")
            "backup_import" -> importFile.launch(arrayOf("application/json", "text/plain", "*/*"))
            "reset_stats" -> resetStatistics()
            else -> return super.onPreferenceTreeClick(preference)
        }
        return true
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
            Palette.KEY -> {
                // Every view has to be rebuilt with the new palette
                activity?.recreate()
                return
            }
            "app_language" -> {
                applyLanguage(sharedPreferences?.getString("app_language", "system"))
                return
            }
            "limit_data_warning" -> updateLimitTitle()
            AppCalendar.KEY -> updatePackageSummary()
        }
        if (key in NOTIFICATION_KEYS) refreshNotification(context)
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
            if (AppCalendar.isPersianUi(requireContext())) PersianFormat.bytes(bytes)
            else FormatUtils.formatBytes(bytes)
        }
        pref.title = getString(R.string.pref_limit_title, value)
    }

    private fun updatePackageSummary() {
        val pref = findPreference<Preference>("data_package") ?: return
        val ctx = requireContext()
        val pkg = PackageStore.load(ctx)
        pref.summary = if (pkg == null) getString(R.string.pref_package_none)
        else getString(
            R.string.pref_package_summary_fmt, Fmt.bytes(ctx, pkg.volume),
            AppCalendar.digits(ctx, pkg.days), AppCalendar.dayLabel(ctx, pkg.startDay))
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

    /** The service clears the table and its own counters. */
    private fun resetStatistics() {
        val context = requireContext()
        val intent = Intent(context, InternetService::class.java)
            .setAction(InternetService.ACTION_RESET)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
        else context.startService(intent)
        Toast.makeText(context, R.string.reset_done, Toast.LENGTH_SHORT).show()
    }

    private fun runBackup(export: Boolean, uri: Uri) {
        val context = requireContext().applicationContext
        lifecycleScope.launch {
            val message = try {
                if (export) getString(R.string.backup_done_fmt, AppCalendar.digits(context, Backup.export(context, uri)))
                else getString(R.string.restore_done_fmt, AppCalendar.digits(context, Backup.import(context, uri)))
            } catch (_: Exception) {
                getString(R.string.backup_failed)
            }
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }

    private companion object {
        /** Preferences the notification reads; a change rebuilds it at once, as before. */
        val NOTIFICATION_KEYS = setOf(
            "hide_lockscreen", "notification_when_connected", "show_up_down_speed",
            "notification_click_action", "speed_unit", "limit_data_warning",
            "day_start_hour", "notification_color", "pause_when_screen_off"
        )
    }
}
