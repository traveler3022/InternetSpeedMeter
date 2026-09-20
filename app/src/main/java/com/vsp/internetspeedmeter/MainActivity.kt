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
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.vsp.internetspeedmeter.broadcastreceiver.InternetService
import com.vsp.internetspeedmeter.databinding.ActivityMainBinding
import com.vsp.internetspeedmeter.recyclerview.UsageAdapter
import com.vsp.internetspeedmeter.room.UsageViewModel
import com.vsp.internetspeedmeter.util.PersianFormat
import java.text.SimpleDateFormat
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: UsageViewModel by viewModels()
    private lateinit var adapter: UsageAdapter
    private lateinit var prefs: SharedPreferences

    private val dbDateFormat = SimpleDateFormat("dd-MM-yyyy", Locale.US)

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startMonitoringService()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        val defaultPrefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
        if (defaultPrefs.getString("theme_color", "light") == "dark") {
            androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(
                androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES)
        } else {
            androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(
                androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO)
        }
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Black app bar with the app title + overflow menu (settings / reset / stop & exit)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.title = getString(R.string.app_name)

        prefs = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

        setupRecyclerView()
        observeUsageData()
        checkAndRequestPermissions()
        startMonitoringService()
    }

    private fun setupRecyclerView() {
        adapter = UsageAdapter()
        binding.recyclerViewUsage.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            setHasFixedSize(true)
            adapter = this@MainActivity.adapter
        }
    }

    private fun observeUsageData() {
        viewModel.allNotes.observe(this) { usages ->
            if (usages.isNullOrEmpty()) {
                val zero = PersianFormat.bytes(0L)
                binding.tvTotalMobile.text = zero
                binding.tvTotalWifi.text = zero
                binding.tvGrandTotal.text = zero
                adapter.submitList(emptyList())
                return@observe
            }

            val sortedList = usages.sortedByDescending {
                try { dbDateFormat.parse(it.date)?.time ?: 0L } catch (_: Exception) { 0L }
            }

            val last30Days = sortedList.take(30)
            adapter.submitList(last30Days)

            val totalMobileBytes = last30Days.sumOf { it.mobile }
            val totalWifiBytes = last30Days.sumOf { it.wifi }

            binding.tvTotalMobile.text = PersianFormat.bytes(totalMobileBytes)
            binding.tvTotalWifi.text = PersianFormat.bytes(totalWifiBytes)
            binding.tvGrandTotal.text = PersianFormat.bytes(totalMobileBytes + totalWifiBytes)
        }
    }

    private fun startMonitoringService() {
        val serviceIntent = Intent(this, InternetService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(serviceIntent)
        else startService(serviceIntent)
    }

    private fun stopMonitoringAndExit() {
        stopService(Intent(this, InternetService::class.java))
        finishAffinity()
    }

    private fun checkAndRequestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_preferences -> {
                startActivity(Intent(this, SettingsActivity::class.java)); true
            }
            R.id.action_reset_stats -> {
                viewModel.deleteAllNotes()
                prefs.edit().clear().apply()
                Toast.makeText(this, R.string.reset_done, Toast.LENGTH_SHORT).show()
                true
            }
            R.id.action_stop_exit -> { stopMonitoringAndExit(); true }
            else -> super.onOptionsItemSelected(item)
        }
    }

    companion object {
        private const val PREF_NAME = "internetSpeed"
    }
}
