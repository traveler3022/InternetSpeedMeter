package com.vsp.internetspeedmeter

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.vsp.internetspeedmeter.broadcastreceiver.InternetService
import com.vsp.internetspeedmeter.databinding.ActivityMainBinding
import com.vsp.internetspeedmeter.ui.AppsFragment
import com.vsp.internetspeedmeter.ui.HistoryFragment
import com.vsp.internetspeedmeter.ui.HomeFragment
import com.vsp.internetspeedmeter.ui.SettingsFragment
import com.vsp.internetspeedmeter.util.Palette
import com.vsp.internetspeedmeter.widget.UsageWidget

/** Hosts the four tabs: home, history, apps and settings. */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: SharedPreferences

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startMonitoringService()
        }

    private val onboardingLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (OnboardingActivity.isDone(this)) {
                startMonitoringWhenReady()
                UsageWidget.refresh(this)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        Palette.applyNightMode(this)
        super.onCreate(savedInstanceState)
        Palette.apply(this)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.title = getString(R.string.app_name)

        prefs = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

        binding.bottomNav.setOnItemSelectedListener { item ->
            showTab(item.itemId)
            true
        }
        binding.bottomNav.setOnItemReselectedListener { }
        if (savedInstanceState == null) showTab(R.id.nav_home)

        if (OnboardingActivity.isDone(this)) {
            startMonitoringWhenReady()
            UsageWidget.refresh(this)
        } else {
            onboardingLauncher.launch(Intent(this, OnboardingActivity::class.java))
        }
    }

    private fun showTab(itemId: Int) {
        val fragment: Fragment = when (itemId) {
            R.id.nav_history -> HistoryFragment()
            R.id.nav_apps -> AppsFragment()
            R.id.nav_settings -> SettingsFragment()
            else -> HomeFragment()
        }
        supportFragmentManager.beginTransaction()
            .setCustomAnimations(
                R.anim.fade_in, R.anim.fade_out, R.anim.fade_in, R.anim.fade_out)
            .replace(R.id.fragment_container, fragment)
            .commit()
    }

    private fun startMonitoringService() {
        prefs.edit().putBoolean("isStarted", true).apply()
        val serviceIntent = Intent(this, InternetService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(serviceIntent)
            else startService(serviceIntent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun stopMonitoringAndExit() {
        prefs.edit().putBoolean("isStarted", false).apply()
        stopService(Intent(this, InternetService::class.java))
        finishAffinity()
    }

    private fun startMonitoringWhenReady() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
        ) {
            requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        startMonitoringService()
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_stop_exit -> { stopMonitoringAndExit(); true }
            else -> super.onOptionsItemSelected(item)
        }
    }

    companion object {
        private const val PREF_NAME = "internetSpeed"
    }
}
