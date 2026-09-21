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
import com.vsp.internetspeedmeter.room.Usage
import com.vsp.internetspeedmeter.room.UsageViewModel
import androidx.core.os.ConfigurationCompat
import com.vsp.internetspeedmeter.util.DayCycle
import com.vsp.internetspeedmeter.util.FormatUtils
import com.vsp.internetspeedmeter.util.PersianFormat
import java.text.SimpleDateFormat
import java.util.Calendar
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
        populateFutureMonthDays()
        observeUsageData()
        checkAndRequestPermissions()
        startMonitoringService()
    }

    private fun populateFutureMonthDays() {
        val list = mutableListOf<Usage>()
        for (i in 0..30) {
            val c = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, i) }
            list.add(Usage(date = dbDateFormat.format(c.time), mobile = 0L, wifi = 0L, total = 0L))
        }
        viewModel.insertAllIgnore(list)
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
                val zero = formatTotal(0L)
                binding.tvTotalMobile.text = zero
                binding.tvTotalWifi.text = zero
                binding.tvGrandTotal.text = zero
                adapter.submitList(emptyList())
                return@observe
            }

            val todayStr = DayCycle.currentDate(this)
            val todayTime = try { dbDateFormat.parse(todayStr)?.time ?: 0L } catch (_: Exception) { 0L }

            val sortedList = usages.sortedWith(Comparator { a, b ->
                val timeA = try { dbDateFormat.parse(a.date)?.time ?: 0L } catch (_: Exception) { 0L }
                val timeB = try { dbDateFormat.parse(b.date)?.time ?: 0L } catch (_: Exception) { 0L }

                when {
                    a.date == todayStr -> -1
                    b.date == todayStr -> 1
                    timeA >= todayTime && timeB >= todayTime -> timeA.compareTo(timeB)
                    timeA < todayTime && timeB < todayTime -> timeB.compareTo(timeA)
                    timeA >= todayTime -> -1
                    else -> 1
                }
            })

            val displayList = sortedList.take(35)
            adapter.submitList(displayList)

            // The summary row is labelled "این ماه", so it sums the current month only
            val currentMonth = DayCycle.monthOf(todayStr)
            val thisMonth = sortedList.filter { DayCycle.monthOf(it.date) == currentMonth }

            val totalMobileBytes = thisMonth.sumOf { it.mobile }
            val totalWifiBytes = thisMonth.sumOf { it.wifi }

            binding.tvTotalMobile.text = formatTotal(totalMobileBytes)
            binding.tvTotalWifi.text = formatTotal(totalWifiBytes)
            binding.tvGrandTotal.text = formatTotal(totalMobileBytes + totalWifiBytes)
        }
    }

    private fun formatTotal(bytes: Long): String {
        val persian = ConfigurationCompat
            .getLocales(resources.configuration)[0]?.language == "fa"
        return if (persian) PersianFormat.bytes(bytes) else FormatUtils.formatBytes(bytes)
    }

    private fun startMonitoringService() {
        prefs.edit().putBoolean("isStarted", true).apply()
        val serviceIntent = Intent(this, InternetService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(serviceIntent)
        else startService(serviceIntent)
    }

    /** Clears the table, the cached counters and restarts the service from zero. */
    private fun resetStatistics() {
        stopService(Intent(this, InternetService::class.java))
        viewModel.deleteAllNotes()
        prefs.edit().clear().apply()
        getSharedPreferences(TRAFFIC_PREF_NAME, Context.MODE_PRIVATE).edit().clear().apply()
        populateFutureMonthDays()
        startMonitoringService()
        Toast.makeText(this, R.string.reset_done, Toast.LENGTH_SHORT).show()
    }

    private fun stopMonitoringAndExit() {
        prefs.edit().putBoolean("isStarted", false).apply()
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
                resetStatistics()
                true
            }
            R.id.action_stop_exit -> { stopMonitoringAndExit(); true }
            else -> super.onOptionsItemSelected(item)
        }
    }

    companion object {
        private const val PREF_NAME = "internetSpeed"
        private const val TRAFFIC_PREF_NAME = "traffic_data"
    }
}
