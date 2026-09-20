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
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.vsp.internetspeedmeter.broadcastreceiver.InternetService
import com.vsp.internetspeedmeter.recyclerview.UsageAdapter
import com.vsp.internetspeedmeter.room.UsageViewModel
import com.vsp.internetspeedmeter.databinding.ActivityMainBinding
import com.vsp.internetspeedmeter.util.FormatUtils
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: UsageViewModel by viewModels()
    private lateinit var adapter: UsageAdapter
    private lateinit var prefs: SharedPreferences

    private val dbDateFormat = SimpleDateFormat("dd-MM-yyyy", Locale.getDefault())

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted) {
                startMonitoringService()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

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
                binding.tvTotalMobile.text = "0 B"
                binding.tvTotalWifi.text = "0 B"
                binding.tvGrandTotal.text = "0 B"
                adapter.submitList(emptyList())
                return@observe
            }

            // Sort chronologically descending (latest date on top)
            val sortedList = usages.sortedByDescending {
                try {
                    dbDateFormat.parse(it.date)?.time ?: 0L
                } catch (_: Exception) {
                    0L
                }
            }

            // Display up to 30 days of history
            val last30Days = sortedList.take(30)
            adapter.submitList(last30Days)

            // Calculate aggregate 30-day totals accurately using Long bytes
            val totalMobileBytes = last30Days.sumOf { it.mobile }
            val totalWifiBytes = last30Days.sumOf { it.wifi }
            val grandTotalBytes = totalMobileBytes + totalWifiBytes

            binding.tvTotalMobile.text = FormatUtils.formatBytes(totalMobileBytes)
            binding.tvTotalWifi.text = FormatUtils.formatBytes(totalWifiBytes)
            binding.tvGrandTotal.text = FormatUtils.formatBytes(grandTotalBytes)
        }
    }

    private fun startMonitoringService() {
        val serviceIntent = Intent(this, InternetService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
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
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }
            // Stop and Exit if user wants it (Internet Speed Meter Lite has this)
            else -> super.onOptionsItemSelected(item)
        }
    }

    companion object {
        private const val PREF_NAME = "internetSpeed"
    }
}
