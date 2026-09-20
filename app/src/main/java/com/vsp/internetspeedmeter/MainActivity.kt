package com.vsp.internetspeedmeter

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.vsp.internetspeedmeter.BroadcastReciever.InternetService
import com.vsp.internetspeedmeter.Recyclerview.UsageAdapter
import com.vsp.internetspeedmeter.Room.Usage
import com.vsp.internetspeedmeter.Room.UsageViewModel
import com.vsp.internetspeedmeter.databinding.ActivityMainBinding
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: UsageViewModel by viewModels()
    private lateinit var adapter: UsageAdapter
    private lateinit var prefs: SharedPreferences

    private val dbDateFormat = SimpleDateFormat("dd-MM-yyyy", Locale.getDefault())
    private val displayDateFormat = SimpleDateFormat("EEEE, d MMM yyyy", Locale.getDefault())

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted && prefs.getBoolean(PREF_IS_STARTED, true)) {
                startMonitoringService()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

        setupRecyclerView()
        setupTodayHeader()
        setupServiceToggle()
        observeUsageData()
        checkAndRequestPermissions()
    }

    private fun setupRecyclerView() {
        adapter = UsageAdapter()
        binding.recyclerViewUsage.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            setHasFixedSize(true)
            adapter = this@MainActivity.adapter
        }
    }

    private fun setupTodayHeader() {
        binding.tvTodayDate.text = displayDateFormat.format(Date())
    }

    private fun setupServiceToggle() {
        val isStarted = prefs.getBoolean(PREF_IS_STARTED, true)
        binding.switchService.isChecked = isStarted
        updateServiceStatusText(isStarted)

        binding.switchService.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(PREF_IS_STARTED, isChecked).apply()
            updateServiceStatusText(isChecked)
            if (isChecked) {
                startMonitoringService()
            } else {
                stopMonitoringService()
            }
        }

        if (isStarted) {
            startMonitoringService()
        }
    }

    private fun updateServiceStatusText(isActive: Boolean) {
        binding.tvServiceStatus.text = if (isActive) {
            getString(R.string.service_status_active)
        } else {
            getString(R.string.service_status_stopped)
        }
    }

    private fun observeUsageData() {
        viewModel.allNotes.observe(this) { usages ->
            if (usages.isNullOrEmpty()) {
                binding.layoutEmpty.visibility = View.VISIBLE
                binding.recyclerViewUsage.visibility = View.GONE
                binding.tvTodayMobile.text = getString(R.string.zero_data)
                binding.tvTodayWifi.text = getString(R.string.zero_data)
                binding.tvTodayTotal.text = getString(R.string.zero_data)
                binding.tvTotalMobile.text = getString(R.string.zero_data)
                binding.tvTotalWifi.text = getString(R.string.zero_data)
                binding.tvGrandTotal.text = getString(R.string.zero_data)
                return@observe
            }

            binding.layoutEmpty.visibility = View.GONE
            binding.recyclerViewUsage.visibility = View.VISIBLE

            // Sort chronologically descending (latest date on top)
            val sortedList = usages.sortedByDescending {
                try {
                    dbDateFormat.parse(it.date)?.time ?: 0L
                } catch (_: Exception) {
                    0L
                }
            }

            adapter.submitList(sortedList)

            // Update Today's Highlight
            val todayStr = dbDateFormat.format(Calendar.getInstance().time)
            val todayUsage = usages.find { it.date == todayStr }
            binding.tvTodayMobile.text = todayUsage?.mobile ?: getString(R.string.zero_data)
            binding.tvTodayWifi.text = todayUsage?.wifi ?: getString(R.string.zero_data)
            binding.tvTodayTotal.text = todayUsage?.total ?: getString(R.string.zero_data)

            // Calculate aggregate 30-day totals
            val totalMobileBytes = usages.sumOf { parseToBytes(it.mobile) }
            val totalWifiBytes = usages.sumOf { parseToBytes(it.wifi) }
            val grandTotalBytes = totalMobileBytes + totalWifiBytes

            binding.tvTotalMobile.text = formatBytes(totalMobileBytes)
            binding.tvTotalWifi.text = formatBytes(totalWifiBytes)
            binding.tvGrandTotal.text = formatBytes(grandTotalBytes)
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

    private fun stopMonitoringService() {
        val serviceIntent = Intent(this, InternetService::class.java)
        stopService(serviceIntent)
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

    private fun parseToBytes(formatted: String): Long {
        val trimmed = formatted.trim()
        val parts = trimmed.split(" ")
        if (parts.size < 2) return 0L
        val value = parts[0].toDoubleOrNull() ?: return 0L
        val unit = parts[1].uppercase(Locale.getDefault())
        return when {
            unit.startsWith("GB") -> (value * 1_000_000_000.0).toLong()
            unit.startsWith("MB") -> (value * 1_000_000.0).toLong()
            unit.startsWith("KB") -> (value * 1_000.0).toLong()
            else -> value.toLong()
        }
    }

    private fun formatBytes(bytes: Long): String {
        return when {
            bytes >= 1_000_000_000L -> String.format(Locale.getDefault(), "%.1f GB", bytes.toDouble() / 1_000_000_000.0)
            bytes >= 1_000_000L -> String.format(Locale.getDefault(), "%.1f MB", bytes.toDouble() / 1_000_000.0)
            bytes >= 1_000L -> "${bytes / 1000L} KB"
            else -> "$bytes B"
        }
    }

    companion object {
        private const val PREF_NAME = "internetSpeed"
        private const val PREF_IS_STARTED = "isStarted"
    }
}
