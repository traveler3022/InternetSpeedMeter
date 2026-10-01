package com.vsp.internetspeedmeter

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.preference.PreferenceManager
import com.google.android.material.button.MaterialButton
import com.vsp.internetspeedmeter.databinding.ActivityOnboardingBinding
import com.vsp.internetspeedmeter.ui.AppNetworkStats
import com.vsp.internetspeedmeter.util.Palette

/** First start: asks for every permission on one page and shows which are on. */
class OnboardingActivity : AppCompatActivity() {

    private lateinit var binding: ActivityOnboardingBinding

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        Palette.applyNightMode(this)
        super.onCreate(savedInstanceState)
        Palette.apply(this)
        binding = ActivityOnboardingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        try {
            binding.ivOnbIcon.setImageDrawable(packageManager.getApplicationIcon(packageName))
        } catch (_: Exception) {
        }

        binding.btnOnbNotif.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        binding.btnOnbUsage.setOnClickListener { AppNetworkStats.openUsageAccessSettings(this) }
        binding.btnOnbBattery.setOnClickListener { requestBatteryExemption() }
        binding.btnOnbDone.setOnClickListener {
            PreferenceManager.getDefaultSharedPreferences(this).edit()
                .putBoolean(KEY_DONE, true).apply()
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        show(binding.tvOnbNotifStatus, binding.btnOnbNotif,
            NotificationManagerCompat.from(this).areNotificationsEnabled())
        show(binding.tvOnbUsageStatus, binding.btnOnbUsage, AppNetworkStats.hasUsageAccess(this))
        val power = getSystemService(Context.POWER_SERVICE) as PowerManager
        show(binding.tvOnbBatteryStatus, binding.btnOnbBattery,
            Build.VERSION.SDK_INT < Build.VERSION_CODES.M || power.isIgnoringBatteryOptimizations(packageName))
    }

    private fun show(status: TextView, button: MaterialButton, granted: Boolean) {
        status.visibility = if (granted) View.VISIBLE else View.GONE
        button.visibility = if (granted) View.GONE else View.VISIBLE
    }

    private fun requestBatteryExemption() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        try {
            startActivity(Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        } catch (_: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (_: Exception) {
            }
        }
    }

    companion object {
        private const val KEY_DONE = "onboarding_done"

        fun isDone(context: Context): Boolean =
            PreferenceManager.getDefaultSharedPreferences(context).getBoolean(KEY_DONE, false)
    }
}
