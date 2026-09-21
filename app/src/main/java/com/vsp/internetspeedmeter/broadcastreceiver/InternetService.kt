package com.vsp.internetspeedmeter.broadcastreceiver

import android.app.Notification
import android.app.NotificationManager
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
import com.vsp.internetspeedmeter.notification.SpeedNotification
import com.vsp.internetspeedmeter.room.Usage
import com.vsp.internetspeedmeter.room.UsageRepository
import com.vsp.internetspeedmeter.util.DayCycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.NetworkInterface
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlin.math.max

class InternetService : Service() {

    private lateinit var notificationManager: NotificationManager
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
    private var lastHardwareVpnRx = 0L
    private var lastHardwareVpnTx = 0L
    private var lastHardwareMobileRx = 0L
    private var lastHardwareMobileTx = 0L
    private var lastSpeedTime = 0L

    // Cumulative daily stats
    private var dailyMobileBytes = 0L
    private var dailyWifiBytes = 0L
    private var lastRecordedDate = ""

    // Mobile bytes of the current month, excluding today (today is added live)
    private var monthlyMobileBase = 0L

    private lateinit var prefs: SharedPreferences
    private val dateFormat = SimpleDateFormat("dd-MM-yyyy", Locale.US)

    // Session Tracking
    private var currentNetworkType = -1 // 0: None, 1: Mobile, 2: Wifi
    private var sessionStartTimeMs = 0L
    private var sessionBytes = 0L

    // Graph Tracking
    val speedHistory = LongArray(60)
    var speedHistoryIndex = 0

    private val systemEventReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    isScreenOn = false
                    sampleAndAccumulateTraffic()
                    if (pauseWhenScreenOff()) stopMonitoring()
                }
                Intent.ACTION_SCREEN_ON -> {
                    isScreenOn = true
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
        instance = this
        super.onCreate()

        powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        isScreenOn = powerManager.isInteractive
        isPowerSaveMode = powerManager.isPowerSaveMode

        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        notificationManager =
            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        SpeedNotification.ensureChannel(this)
        usageRepository = UsageRepository(this)

        lastRecordedDate = DayCycle.currentDate(this)
        prefs = getSharedPreferences("traffic_data", Context.MODE_PRIVATE)

        val savedDate = prefs.getString("lastRecordedDate", "")
        if (savedDate == lastRecordedDate) {
            dailyMobileBytes = prefs.getLong("dailyMobileBytes", 0L)
            dailyWifiBytes = prefs.getLong("dailyWifiBytes", 0L)
        } else {
            dailyMobileBytes = 0L
            dailyWifiBytes = 0L
        }

        initHardwareCounters()
        checkDateRollover()
        syncWithDatabase()
        refreshMonthlyBase()

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
            notificationManager.notify(SpeedNotification.NOTIFICATION_ID, buildNotification(0L, 0L))
            return START_STICKY
        }

        val initialNotification = buildNotification(0L, 0L)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                startForeground(
                    SpeedNotification.NOTIFICATION_ID,
                    initialNotification,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } catch (_: Exception) {
                startForeground(SpeedNotification.NOTIFICATION_ID, initialNotification)
            }
        } else {
            startForeground(SpeedNotification.NOTIFICATION_ID, initialNotification)
        }

        if (isScreenOn || !pauseWhenScreenOff()) {
            startMonitoring()
        }

        return START_STICKY
    }

    @android.annotation.SuppressLint("NewApi")
    private fun getVpnTraffic(): Pair<Long, Long> {
        var rx = 0L
        var tx = 0L
        try {
            val ifaces = NetworkInterface.getNetworkInterfaces()
            ifaces?.let {
                for (iface in it) {
                    if (iface.isUp && (iface.name.startsWith("tun") || iface.name.startsWith("tap"))) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            rx += sanitizeBytes(TrafficStats.getRxBytes(iface.name))
                            tx += sanitizeBytes(TrafficStats.getTxBytes(iface.name))
                        } else {
                            rx += readSysFs(iface.name, "rx_bytes")
                            tx += readSysFs(iface.name, "tx_bytes")
                        }
                    }
                }
            }
        } catch (e: Throwable) {}
        return Pair(rx, tx)
    }

    private fun readSysFs(iface: String, file: String): Long {
        return try {
            val f = java.io.File("/sys/class/net/$iface/statistics/$file")
            if (f.exists()) {
                f.readText().trim().toLong()
            } else {
                0L
            }
        } catch (e: Exception) {
            0L
        }
    }





    private fun startMonitoring() {
        if (monitorJob?.isActive == true) return

        monitorJob = serviceScope.launch {
            var tickCount = 0
            while (isActive && (isScreenOn || !pauseWhenScreenOff())) {
                val curTime = SystemClock.elapsedRealtime()
                val curRawRx = sanitizeBytes(TrafficStats.getTotalRxBytes())
                val curRawTx = sanitizeBytes(TrafficStats.getTotalTxBytes())
                val curVpn = getVpnTraffic()

                if (curRawRx < lastHardwareTotalRx || curRawTx < lastHardwareTotalTx) {
                    initHardwareCounters()
                    delay(1000)
                    continue
                }

                val elapsedMs = max(1L, curTime - lastSpeedTime)
                
                var rawDeltaRx = max(0L, curRawRx - lastHardwareTotalRx)
                var rawDeltaTx = max(0L, curRawTx - lastHardwareTotalTx)
                
                val vpnDeltaRx = max(0L, curVpn.first - lastHardwareVpnRx)
                val vpnDeltaTx = max(0L, curVpn.second - lastHardwareVpnTx)
                
                val deltaRx = max(0L, rawDeltaRx - vpnDeltaRx)
                val deltaTx = max(0L, rawDeltaTx - vpnDeltaTx)

                val downSpeed = ((deltaRx * 1000.0) / elapsedMs).toLong()
                val upSpeed = ((deltaTx * 1000.0) / elapsedMs).toLong()
                val totalSpeed = downSpeed + upSpeed

                speedHistoryIndex = (speedHistoryIndex + 1) % 60
                if (tickCount >= 60) {
                    System.arraycopy(speedHistory, 1, speedHistory, 0, 59)
                    speedHistoryIndex = 59
                }
                speedHistory[speedHistoryIndex] = totalSpeed

                lastSpeedTime = curTime
                lastHardwareTotalRx = curRawRx
                lastHardwareTotalTx = curRawTx
                lastHardwareVpnRx = curVpn.first
                lastHardwareVpnTx = curVpn.second

                val deltaTotal = deltaRx + deltaTx
                if (deltaTotal > 0L) {
                    allocateTraffic(deltaTotal)
                }

                checkDateRollover()

                notificationManager.notify(
                    SpeedNotification.NOTIFICATION_ID, buildNotification(downSpeed, upSpeed))

                tickCount++
                if (tickCount % 5 == 0) {
                    saveToPrefs()
                    persistDailyUsage()
                }

                val targetDelay = if (isPowerSaveMode) 3000L else 1000L
                val loopElapsed = SystemClock.elapsedRealtime() - curTime
                val remainingDelay = max(100L, targetDelay - loopElapsed)
                delay(remainingDelay)
            }
        }
    }

    private fun sampleAndAccumulateTraffic() {
        val curRawRx = sanitizeBytes(TrafficStats.getTotalRxBytes())
        val curRawTx = sanitizeBytes(TrafficStats.getTotalTxBytes())
        val curVpn = getVpnTraffic()

        if (curRawRx >= lastHardwareTotalRx && curRawTx >= lastHardwareTotalTx) {
            val rawDeltaRx = curRawRx - lastHardwareTotalRx
            val rawDeltaTx = curRawTx - lastHardwareTotalTx
            
            val vpnDeltaRx = max(0L, curVpn.first - lastHardwareVpnRx)
            val vpnDeltaTx = max(0L, curVpn.second - lastHardwareVpnTx)
            
            val deltaRx = max(0L, rawDeltaRx - vpnDeltaRx)
            val deltaTx = max(0L, rawDeltaTx - vpnDeltaTx)
            
            val deltaTotal = deltaRx + deltaTx
            if (deltaTotal > 0L) {
                allocateTraffic(deltaTotal)
                saveToPrefs()
                persistDailyUsage()
            }
        }

        lastHardwareTotalRx = curRawRx
        lastHardwareTotalTx = curRawTx
        lastHardwareVpnRx = curVpn.first
        lastHardwareVpnTx = curVpn.second
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

        val curMobileRx = sanitizeBytes(TrafficStats.getMobileRxBytes())
        val curMobileTx = sanitizeBytes(TrafficStats.getMobileTxBytes())
        val deltaMobile = max(0L, curMobileRx - lastHardwareMobileRx) +
                max(0L, curMobileTx - lastHardwareMobileTx)

        lastHardwareMobileRx = curMobileRx
        lastHardwareMobileTx = curMobileTx

        val newNetworkType = when {
            isWifi -> 2
            isMobile -> 1
            else -> 0
        }
        if (newNetworkType != currentNetworkType && newNetworkType != 0) {
            currentNetworkType = newNetworkType
            sessionStartTimeMs = SystemClock.elapsedRealtime()
            sessionBytes = 0L
        }

        val mobileAllocation: Long
        val wifiAllocation: Long

        if (deltaMobile > 0L) {
            mobileAllocation = deltaMobile.coerceAtMost(deltaTotal)
            wifiAllocation = deltaTotal - mobileAllocation
        } else {
            if (isMobile) {
                mobileAllocation = deltaTotal
                wifiAllocation = 0L
            } else {
                mobileAllocation = 0L
                wifiAllocation = deltaTotal
            }
        }

        dailyMobileBytes += mobileAllocation
        dailyWifiBytes += wifiAllocation
        sessionBytes += deltaTotal
    }

    private fun stopMonitoring() {
        monitorJob?.cancel()
        monitorJob = null

        notificationManager.notify(SpeedNotification.NOTIFICATION_ID, buildNotification(0L, 0L))

        saveToPrefs()
        persistDailyUsage()
    }

    private fun initHardwareCounters() {
        lastHardwareTotalRx = sanitizeBytes(TrafficStats.getTotalRxBytes())
        lastHardwareTotalTx = sanitizeBytes(TrafficStats.getTotalTxBytes())
        val vpn = getVpnTraffic()
        lastHardwareVpnRx = vpn.first
        lastHardwareVpnTx = vpn.second
        lastHardwareMobileRx = sanitizeBytes(TrafficStats.getMobileRxBytes())
        lastHardwareMobileTx = sanitizeBytes(TrafficStats.getMobileTxBytes())
        lastSpeedTime = SystemClock.elapsedRealtime()
        isStatsInitialized = true
    }

    private fun sanitizeBytes(bytes: Long): Long {
        return if (bytes == TrafficStats.UNSUPPORTED.toLong() || bytes < 0L) 0L else bytes
    }

    private fun checkDateRollover() {
        val today = DayCycle.currentDate(this)
        if (today != lastRecordedDate) {
            saveToPrefs()
            persistDailyUsage()

            lastRecordedDate = today
            dailyMobileBytes = 0L
            dailyWifiBytes = 0L

            saveToPrefs()
            persistDailyUsage()
            refreshMonthlyBase()
        }
    }

    /** Monthly mobile usage of the completed days, used by the "محدودیت" preference. */
    private fun refreshMonthlyBase() {
        serviceScope.launch(Dispatchers.IO) {
            monthlyMobileBase = try {
                usageRepository.getMonthlyMobileExcluding(
                    DayCycle.monthOf(lastRecordedDate), lastRecordedDate
                )
            } catch (_: Exception) {
                0L
            }
        }
    }

    private fun monthlyMobileBytes(): Long = monthlyMobileBase + dailyMobileBytes

    private fun buildNotification(downSpeed: Long, upSpeed: Long): Notification =
        SpeedNotification.build(
            this, downSpeed, upSpeed, dailyMobileBytes, dailyWifiBytes,
            monthlyMobileBytes(), isNotificationIdle()
        )

    /** "تنها زمانی که به اینترنت متصل هستم": silence the notification while offline. */
    private fun isNotificationIdle(): Boolean {
        val onlyWhenConnected = androidx.preference.PreferenceManager
            .getDefaultSharedPreferences(this)
            .getBoolean("notification_when_connected", false)
        return onlyWhenConnected && !isConnected()
    }

    private fun isConnected(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val capabilities =
                connectivityManager.getNetworkCapabilities(connectivityManager.activeNetwork)
            capabilities != null &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } else {
            @Suppress("DEPRECATION")
            connectivityManager.activeNetworkInfo?.isConnected == true
        }
    }

    private fun pauseWhenScreenOff(): Boolean =
        androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
            .getBoolean("pause_when_screen_off", true)

    private fun syncWithDatabase() {
        serviceScope.launch(Dispatchers.IO) {
            val todayUsage = usageRepository.getUsageByDate(lastRecordedDate)
            if (todayUsage != null) {
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
        if (instance == this) instance = null
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

    companion object {
        var instance: InternetService? = null
    }

    fun getSessionInfo(): Pair<Long, Long> {
        return Pair(kotlin.math.max(0L, android.os.SystemClock.elapsedRealtime() - sessionStartTimeMs), sessionBytes)
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
