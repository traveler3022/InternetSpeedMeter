package com.vsp.internetspeedmeter.broadcastreceiver

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.TrafficStats
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.vsp.internetspeedmeter.NotificationService
import com.vsp.internetspeedmeter.room.Usage
import com.vsp.internetspeedmeter.room.UsageRepository
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
    private lateinit var connectivityManager: ConnectivityManager
    private lateinit var powerManager: PowerManager

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Default + serviceJob)
    private var monitorJob: Job? = null

    private var isScreenOn = true
    private var isPowerSaveMode = false
    private var isStatsInitialized = false

    // Hardware counter baselines
    private var lastHardwareTotalRx = 0L
    private var lastHardwareTotalTx = 0L
    private var lastHardwareMobileRx = 0L
    private var lastHardwareMobileTx = 0L
    private var lastSpeedTime = 0L

    // Cumulative daily stats
    private var dailyMobileBytes = 0L
    private var dailyWifiBytes = 0L
    private var lastRecordedDate = ""

    private lateinit var prefs: SharedPreferences
    private val dateFormat = SimpleDateFormat("dd-MM-yyyy", Locale.US)

    private val systemEventReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    isScreenOn = false
                    // Sample all traffic up to screen-off and persist
                    sampleAndAccumulateTraffic()
                    stopMonitoring()
                }
                Intent.ACTION_SCREEN_ON -> {
                    isScreenOn = true
                    // Capture all background bytes consumed while screen was asleep
                    sampleAndAccumulateTraffic()
                    lastSpeedTime = SystemClock.elapsedRealtime()
                    startMonitoring()
                }
                PowerManager.ACTION_POWER_SAVE_MODE_CHANGED -> {
                    isPowerSaveMode = powerManager.isPowerSaveMode
                }
                Intent.ACTION_DATE_CHANGED,
                Intent.ACTION_TIME_TICK -> {
                    checkDateRollover()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()

        powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        isScreenOn = powerManager.isInteractive
        isPowerSaveMode = powerManager.isPowerSaveMode

        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        notificationService = NotificationService(this)
        usageRepository = UsageRepository(this)

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

        // Initialize hardware counters and reseed state
        initHardwareCounters()

        // Sync with Room DB if prefs has 0 bytes (e.g. after fresh restart)
        checkDateRollover()
        syncWithDatabase()

        // Register system event broadcasts
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
            addAction(Intent.ACTION_DATE_CHANGED)
            addAction(Intent.ACTION_TIME_TICK)
        }
        ContextCompat.registerReceiver(
            this,
            systemEventReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "UPDATE_NOTIFICATION_SETTINGS") {
            val notification = notificationService.updateNotification(
                0L, 0L, dailyMobileBytes, dailyWifiBytes
            ).build()
            notificationService.notify(notification)
            return START_STICKY
        }

        val initialNotification = notificationService.updateNotification(
            0L, 0L, dailyMobileBytes, dailyWifiBytes
        ).build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                startForeground(
                    NotificationService.NOTIFICATION_ID,
                    initialNotification,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } catch (_: Exception) {
                startForeground(NotificationService.NOTIFICATION_ID, initialNotification)
            }
        } else {
            startForeground(NotificationService.NOTIFICATION_ID, initialNotification)
        }

        if (isScreenOn) {
            startMonitoring()
        }

        return START_STICKY
    }

    private fun startMonitoring() {
        if (monitorJob?.isActive == true) return

        monitorJob = serviceScope.launch {
            var tickCount = 0
            while (isActive && isScreenOn) {
                val curTime = SystemClock.elapsedRealtime()
                val curTotalRx = sanitizeBytes(TrafficStats.getTotalRxBytes())
                val curTotalTx = sanitizeBytes(TrafficStats.getTotalTxBytes())

                // Counter rollover / system reboot detection
                if (curTotalRx < lastHardwareTotalRx || curTotalTx < lastHardwareTotalTx) {
                    initHardwareCounters()
                    delay(1000)
                    continue
                }

                val elapsedMs = max(1L, curTime - lastSpeedTime)
                val deltaRx = max(0L, curTotalRx - lastHardwareTotalRx)
                val deltaTx = max(0L, curTotalTx - lastHardwareTotalTx)

                // High-precision speed calculation based on actual elapsed monotonic time
                val downSpeed = ((deltaRx * 1000.0) / elapsedMs).toLong()
                val upSpeed = ((deltaTx * 1000.0) / elapsedMs).toLong()

                lastSpeedTime = curTime
                lastHardwareTotalRx = curTotalRx
                lastHardwareTotalTx = curTotalTx

                // Accumulate daily volume
                val deltaTotal = deltaRx + deltaTx
                if (deltaTotal > 0L) {
                    allocateTraffic(deltaTotal)
                }

                checkDateRollover()

                // Update notification every tick
                val notification = notificationService.updateNotification(
                    downSpeed, upSpeed, dailyMobileBytes, dailyWifiBytes
                ).build()
                notificationService.notify(notification)

                // Throttle persistence to SQLite & SharedPreferences to every 5 ticks to save battery and flash wear
                tickCount++
                if (tickCount % 5 == 0) {
                    saveToPrefs()
                    persistDailyUsage()
                }

                // If power saving is active, throttle loop to 3 seconds, otherwise standard 1 second
                val targetDelay = if (isPowerSaveMode) 3000L else 1000L
                val loopElapsed = SystemClock.elapsedRealtime() - curTime
                val remainingDelay = max(100L, targetDelay - loopElapsed)
                delay(remainingDelay)
            }
        }
    }

    /**
     * Captures and accumulates background data transfer during screen-off / sleep periods.
     * Prevents any data loss when the phone screen is turned off and back on.
     */
    private fun sampleAndAccumulateTraffic() {
        val curTotalRx = sanitizeBytes(TrafficStats.getTotalRxBytes())
        val curTotalTx = sanitizeBytes(TrafficStats.getTotalTxBytes())

        if (curTotalRx >= lastHardwareTotalRx && curTotalTx >= lastHardwareTotalTx) {
            val deltaRx = curTotalRx - lastHardwareTotalRx
            val deltaTx = curTotalTx - lastHardwareTotalTx
            val deltaTotal = deltaRx + deltaTx
            if (deltaTotal > 0L) {
                allocateTraffic(deltaTotal)
                saveToPrefs()
                persistDailyUsage()
            }
        }

        lastHardwareTotalRx = curTotalRx
        lastHardwareTotalTx = curTotalTx
        lastSpeedTime = SystemClock.elapsedRealtime()
    }

    private fun allocateTraffic(deltaTotal: Long) {
        if (deltaTotal <= 0L) return

        var isWifi = false
        var isMobile = false

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val activeNetwork = connectivityManager.activeNetwork
            val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork)
            if (capabilities != null) {
                if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                ) {
                    isWifi = true
                } else if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                    isMobile = true
                }
            }
        } else {
            @Suppress("DEPRECATION")
            val activeNetworkInfo = connectivityManager.activeNetworkInfo
            if (activeNetworkInfo != null && activeNetworkInfo.isConnected) {
                if (activeNetworkInfo.type == ConnectivityManager.TYPE_WIFI ||
                    activeNetworkInfo.type == ConnectivityManager.TYPE_ETHERNET
                ) {
                    isWifi = true
                } else if (activeNetworkInfo.type == ConnectivityManager.TYPE_MOBILE) {
                    isMobile = true
                }
            }
        }

        when {
            isWifi -> dailyWifiBytes += deltaTotal
            isMobile -> dailyMobileBytes += deltaTotal
            else -> {
                // Secondary check using mobile stats delta
                val curMobileRx = sanitizeBytes(TrafficStats.getMobileRxBytes())
                val curMobileTx = sanitizeBytes(TrafficStats.getMobileTxBytes())
                val deltaMobile = max(0L, curMobileRx - lastHardwareMobileRx) +
                        max(0L, curMobileTx - lastHardwareMobileTx)

                lastHardwareMobileRx = curMobileRx
                lastHardwareMobileTx = curMobileTx

                if (deltaMobile > 0L) {
                    val mobileAllocation = deltaMobile.coerceAtMost(deltaTotal)
                    dailyMobileBytes += mobileAllocation
                    dailyWifiBytes += (deltaTotal - mobileAllocation)
                } else {
                    dailyWifiBytes += deltaTotal
                }
            }
        }
    }

    private fun stopMonitoring() {
        monitorJob?.cancel()
        monitorJob = null

        // Display 0 B/s in status bar while screen is sleeping
        val notification = notificationService.updateNotification(
            0L, 0L, dailyMobileBytes, dailyWifiBytes
        ).build()
        notificationService.notify(notification)

        saveToPrefs()
        persistDailyUsage()
    }

    private fun initHardwareCounters() {
        lastHardwareTotalRx = sanitizeBytes(TrafficStats.getTotalRxBytes())
        lastHardwareTotalTx = sanitizeBytes(TrafficStats.getTotalTxBytes())
        lastHardwareMobileRx = sanitizeBytes(TrafficStats.getMobileRxBytes())
        lastHardwareMobileTx = sanitizeBytes(TrafficStats.getMobileTxBytes())
        lastSpeedTime = SystemClock.elapsedRealtime()
        isStatsInitialized = true
    }

    private fun sanitizeBytes(bytes: Long): Long {
        return if (bytes == TrafficStats.UNSUPPORTED.toLong() || bytes < 0L) 0L else bytes
    }

    private fun checkDateRollover() {
        val today = dateFormat.format(Calendar.getInstance().time)
        if (today != lastRecordedDate) {
            // Commit final usage for previous day
            saveToPrefs()
            persistDailyUsage()

            // Transition to new day
            lastRecordedDate = today
            dailyMobileBytes = 0L
            dailyWifiBytes = 0L

            saveToPrefs()
            persistDailyUsage()
        }
    }

    private fun syncWithDatabase() {
        serviceScope.launch(Dispatchers.IO) {
            val todayUsage = usageRepository.getUsageByDate(lastRecordedDate)
            if (todayUsage != null) {
                // If DB has higher figures (e.g. from previous run), adopt the higher value
                if (todayUsage.mobile > dailyMobileBytes) {
                    dailyMobileBytes = todayUsage.mobile
                }
                if (todayUsage.wifi > dailyWifiBytes) {
                    dailyWifiBytes = todayUsage.wifi
                }
                saveToPrefs()
            }
            persistDailyUsage()
        }
    }

    private fun saveToPrefs() {
        prefs.edit()
            .putLong("dailyMobileBytes", dailyMobileBytes)
            .putLong("dailyWifiBytes", dailyWifiBytes)
            .putString("lastRecordedDate", lastRecordedDate)
            .apply()
    }

    private fun persistDailyUsage() {
        usageRepository.insert(
            Usage(
                date = lastRecordedDate,
                mobile = dailyMobileBytes,
                wifi = dailyWifiBytes,
                total = dailyMobileBytes + dailyWifiBytes
            )
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(systemEventReceiver)
        } catch (_: Exception) {}

        sampleAndAccumulateTraffic()
        saveToPrefs()
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
