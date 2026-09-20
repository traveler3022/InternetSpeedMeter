package com.vsp.internetspeedmeter.BroadcastReciever

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.net.TrafficStats
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.vsp.internetspeedmeter.NotificationService
import com.vsp.internetspeedmeter.Room.Usage
import com.vsp.internetspeedmeter.Room.UsageRepository
import com.vsp.internetspeedmeter.util.FormatUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlin.math.max

class InternetService : Service() {

    private lateinit var notificationService: NotificationService
    private lateinit var usageRepository: UsageRepository
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Default + serviceJob)
    private var monitorJob: Job? = null

    private var isScreenOn = true

    private var prevTotalRx = 0L
    private var prevTotalTx = 0L
    private var prevMobileRx = 0L
    private var prevMobileTx = 0L

    private var dailyMobileBytes = 0L
    private var dailyWifiBytes = 0L
    private var lastRecordedDate = ""

    private lateinit var prefs: SharedPreferences
    private val dateFormat = SimpleDateFormat("dd-MM-yyyy", Locale.getDefault())

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    isScreenOn = false
                    persistDailyUsage()
                    stopMonitoring()
                }
                Intent.ACTION_SCREEN_ON -> {
                    isScreenOn = true
                    syncTrafficStats()
                    startMonitoring()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        lastRecordedDate = dateFormat.format(Calendar.getInstance().time)
        prefs = getSharedPreferences("traffic_data", Context.MODE_PRIVATE)

        val savedDate = prefs.getString("lastRecordedDate", "")
        if (savedDate == lastRecordedDate) {
            dailyMobileBytes = prefs.getLong("dailyMobileBytes", 0L)
            dailyWifiBytes = prefs.getLong("dailyWifiBytes", 0L)
        } else {
            dailyMobileBytes = 0L
            dailyWifiBytes = 0L
        }

        notificationService = NotificationService(this)
        usageRepository = UsageRepository(this)
        persistDailyUsage()
        syncTrafficStats()

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        ContextCompat.registerReceiver(
            this,
            screenReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val initialNotification = notificationService.updateNotification(
            0L, 0L, dailyMobileBytes, dailyWifiBytes
        ).build()
        startForeground(NotificationService.NOTIFICATION_ID, initialNotification)

        startMonitoring()

        return START_STICKY
    }

    private fun startMonitoring() {
        if (monitorJob?.isActive == true) return

        monitorJob = serviceScope.launch {
            var tickCount = 0
            while (isActive && isScreenOn) {
                val curTotalRx = sanitizeBytes(TrafficStats.getTotalRxBytes())
                val curTotalTx = sanitizeBytes(TrafficStats.getTotalTxBytes())
                val curMobileRx = sanitizeBytes(TrafficStats.getMobileRxBytes())
                val curMobileTx = sanitizeBytes(TrafficStats.getMobileTxBytes())

                // Detect initial run or counter reboot / rollover
                if (prevTotalRx == 0L || curTotalRx < prevTotalRx || curTotalTx < prevTotalTx ||
                    curMobileRx < prevMobileRx || curMobileTx < prevMobileTx
                ) {
                    syncTrafficStats()
                    delay(1000)
                    continue
                }

                val downSpeed = max(0L, curTotalRx - prevTotalRx)
                val upSpeed = max(0L, curTotalTx - prevTotalTx)

                val deltaMobileRx = max(0L, curMobileRx - prevMobileRx)
                val deltaMobileTx = max(0L, curMobileTx - prevMobileTx)
                val deltaMobile = deltaMobileRx + deltaMobileTx

                val deltaTotal = downSpeed + upSpeed
                val deltaWifi = max(0L, deltaTotal - deltaMobile)

                prevTotalRx = curTotalRx
                prevTotalTx = curTotalTx
                prevMobileRx = curMobileRx
                prevMobileTx = curMobileTx

                checkDateRollover()

                dailyMobileBytes += deltaMobile
                dailyWifiBytes += deltaWifi

                // Update real-time status bar notification every second
                val notification = notificationService.updateNotification(
                    downSpeed, upSpeed, dailyMobileBytes, dailyWifiBytes
                ).build()
                notificationService.notify(notification)

                // Save to SharedPreferences every second
                prefs.edit()
                    .putLong("dailyMobileBytes", dailyMobileBytes)
                    .putLong("dailyWifiBytes", dailyWifiBytes)
                    .putString("lastRecordedDate", lastRecordedDate)
                    .apply()

                // Throttle Room SQLite persistence to every 5 seconds to reduce flash wear and battery drain
                tickCount++
                if (tickCount % 5 == 0) {
                    persistDailyUsage()
                }

                delay(1000)
            }
        }
    }

    private fun stopMonitoring() {
        monitorJob?.cancel()
        monitorJob = null
    }

    private fun sanitizeBytes(bytes: Long): Long {
        return if (bytes == TrafficStats.UNSUPPORTED.toLong() || bytes < 0L) 0L else bytes
    }

    private fun syncTrafficStats() {
        prevTotalRx = sanitizeBytes(TrafficStats.getTotalRxBytes())
        prevTotalTx = sanitizeBytes(TrafficStats.getTotalTxBytes())
        prevMobileRx = sanitizeBytes(TrafficStats.getMobileRxBytes())
        prevMobileTx = sanitizeBytes(TrafficStats.getMobileTxBytes())
    }

    private fun checkDateRollover() {
        val today = dateFormat.format(Calendar.getInstance().time)
        if (today != lastRecordedDate) {
            persistDailyUsage()
            lastRecordedDate = today
            dailyMobileBytes = 0L
            dailyWifiBytes = 0L
            persistDailyUsage()
        }
    }

    private fun persistDailyUsage() {
        val mobileStr = FormatUtils.formatBytes(dailyMobileBytes)
        val wifiStr = FormatUtils.formatBytes(dailyWifiBytes)
        val totalStr = FormatUtils.formatBytes(dailyMobileBytes + dailyWifiBytes)
        usageRepository.insert(
            Usage(
                date = lastRecordedDate,
                mobile = mobileStr,
                wifi = wifiStr,
                total = totalStr
            )
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(screenReceiver)
        } catch (_: Exception) {}
        persistDailyUsage()
        stopMonitoring()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        serviceScope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
