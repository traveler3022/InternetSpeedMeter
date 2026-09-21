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
import com.vsp.internetspeedmeter.util.TrafficMath
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.NetworkInterface
import kotlin.math.max

class InternetService : Service() {

    private lateinit var notificationManager: NotificationManager
    private lateinit var usageRepository: UsageRepository
    private lateinit var connectivityManager: ConnectivityManager
    private lateinit var powerManager: PowerManager

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Default + serviceJob)
    private val stateLock = Any()
    private var monitorJob: Job? = null

    private var isScreenOn = true
    private var isPowerSaveMode = false
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
    @Volatile
    private var monthlyMobileBase = 0L

    private lateinit var prefs: SharedPreferences
    // Session Tracking
    private var currentNetworkType = 0 // 0: None, 1: Mobile, 2: Wifi
    private var sessionStartTimeMs = 0L
    private var sessionBytes = 0L

    // Graph Tracking
    val speedHistory = LongArray(60)
    var speedHistoryIndex = 0

    private val systemEventReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    synchronized(stateLock) { isScreenOn = false }
                    sampleAndAccumulateTraffic()
                    if (pauseWhenScreenOff()) stopMonitoring()
                }
                Intent.ACTION_SCREEN_ON -> {
                    synchronized(stateLock) {
                        isScreenOn = true
                    }
                    // Keep the existing speed baseline; this sample must cover the
                    // real elapsed interval since the previous sample.
                    sampleAndAccumulateTraffic()
                    startMonitoring()
                }
                PowerManager.ACTION_POWER_SAVE_MODE_CHANGED -> {
                    synchronized(stateLock) {
                        isPowerSaveMode = powerManager.isPowerSaveMode
                    }
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

        synchronized(stateLock) {
            sessionStartTimeMs = SystemClock.elapsedRealtime()
            currentNetworkType = detectNetworkType()
        }

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
        val initialNotification = buildNotification(0L, 0L)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                SpeedNotification.NOTIFICATION_ID,
                initialNotification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(SpeedNotification.NOTIFICATION_ID, initialNotification)
        }

        if (intent?.action == "UPDATE_NOTIFICATION_SETTINGS") {
            notificationManager.notify(SpeedNotification.NOTIFICATION_ID, initialNotification)
            return START_STICKY
        }

        val shouldMonitor = synchronized(stateLock) {
            isScreenOn || !pauseWhenScreenOff()
        }
        if (shouldMonitor) {
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
        } catch (_: Exception) {
            // Some vendor kernels expose transient/incomplete interface information.
            // Treat an unreadable VPN counter as zero; the total counters remain authoritative.
        }
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
        synchronized(stateLock) {
            if (monitorJob?.isActive == true) return
            monitorJob = serviceScope.launch {
                monitoringLoop()
            }
        }
    }

    private suspend fun CoroutineScope.monitoringLoop() {
        var tickCount = 0

        while (isActive) {
            val shouldRun = synchronized(stateLock) {
                isScreenOn || !pauseWhenScreenOff()
            }
            if (!shouldRun) break

            // Roll over before sampling so bytes collected across midnight are
            // assigned to the new accounting day instead of the previous one.
            checkDateRollover()

            val sample = synchronized(stateLock) {
                readAndApplyTrafficSampleLocked()
            }

            if (sample.countersReset) {
                delay(1000L)
                continue
            }

            synchronized(stateLock) {
                val totalSpeed = TrafficMath.safeAdd(sample.downSpeed, sample.upSpeed)
                speedHistoryIndex = (speedHistoryIndex + 1) % speedHistory.size
                speedHistory[speedHistoryIndex] = totalSpeed
            }

            val notification = buildNotification(sample.downSpeed, sample.upSpeed)
            notificationManager.notify(SpeedNotification.NOTIFICATION_ID, notification)

            tickCount++
            if (tickCount % 5 == 0) {
                saveToPrefs()
                persistDailyUsage()
            }

            val targetDelay = synchronized(stateLock) {
                if (isPowerSaveMode) 3000L else 1000L
            }
            val remainingDelay = max(100L, targetDelay - sample.loopElapsedMs)
            delay(remainingDelay)
        }

        synchronized(stateLock) {
            if (monitorJob?.isActive == false) {
                monitorJob = null
            }
        }
    }

    private fun readAndApplyTrafficSampleLocked(): TrafficMath.Sample {
        val curTime = SystemClock.elapsedRealtime()
        val curRawRx = sanitizeBytes(TrafficStats.getTotalRxBytes())
        val curRawTx = sanitizeBytes(TrafficStats.getTotalTxBytes())
        val curVpn = getVpnTraffic()

        if (!TrafficMath.countersMonotonic(
                curRawRx, lastHardwareTotalRx,
                curRawTx, lastHardwareTotalTx
            )
        ) {
            initHardwareCountersLocked()
            return TrafficMath.Sample.countersReset()
        }

        val result = TrafficMath.calculate(
            currentRx = curRawRx,
            currentTx = curRawTx,
            previousRx = lastHardwareTotalRx,
            previousTx = lastHardwareTotalTx,
            currentVpnRx = curVpn.first,
            currentVpnTx = curVpn.second,
            previousVpnRx = lastHardwareVpnRx,
            previousVpnTx = lastHardwareVpnTx,
            elapsedMs = curTime - lastSpeedTime
        )

        lastSpeedTime = curTime
        lastHardwareTotalRx = curRawRx
        lastHardwareTotalTx = curRawTx
        lastHardwareVpnRx = curVpn.first
        lastHardwareVpnTx = curVpn.second

        val deltaTotal = TrafficMath.safeAdd(result.deltaRx, result.deltaTx)
        if (deltaTotal > 0L) {
            allocateTrafficLocked(deltaTotal)
        }

        val loopElapsedMs = max(0L, SystemClock.elapsedRealtime() - curTime)
        return result.copy(loopElapsedMs = loopElapsedMs)
    }

    private fun sampleAndAccumulateTraffic() {
        // Roll over first so this sample belongs entirely to the current
        // accounting day.
        checkDateRollover()

        val shouldPersist = synchronized(stateLock) {
            val result = readAndApplyTrafficSampleLocked()
            result.deltaRx > 0L || result.deltaTx > 0L
        }

        if (shouldPersist) {
            saveToPrefs()
            persistDailyUsage()
        }
    }

    /**
     * Allocates traffic to the currently active transport. The mobile counters are
     * only used when they provide an actual positive delta; otherwise the active
     * transport is used as the fallback.
     */
    private fun allocateTrafficLocked(deltaTotal: Long) {
        if (deltaTotal <= 0L) return

        val newNetworkType = detectNetworkType()
        val curMobileRx = sanitizeBytes(TrafficStats.getMobileRxBytes())
        val curMobileTx = sanitizeBytes(TrafficStats.getMobileTxBytes())

        val mobileDeltaRx = TrafficMath.monotonicDelta(curMobileRx, lastHardwareMobileRx)
        val mobileDeltaTx = TrafficMath.monotonicDelta(curMobileTx, lastHardwareMobileTx)
        val deltaMobile = TrafficMath.safeAdd(mobileDeltaRx, mobileDeltaTx)

        lastHardwareMobileRx = curMobileRx
        lastHardwareMobileTx = curMobileTx

        if (newNetworkType != 0 && newNetworkType != currentNetworkType) {
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
            when (newNetworkType) {
                1 -> {
                    mobileAllocation = deltaTotal
                    wifiAllocation = 0L
                }
                2 -> {
                    mobileAllocation = 0L
                    wifiAllocation = deltaTotal
                }
                else -> {
                    // No active transport: retain the last known classification
                    // instead of silently assigning unknown traffic to Wi-Fi.
                    when (currentNetworkType) {
                        1 -> {
                            mobileAllocation = deltaTotal
                            wifiAllocation = 0L
                        }
                        2 -> {
                            mobileAllocation = 0L
                            wifiAllocation = deltaTotal
                        }
                        else -> {
                            mobileAllocation = 0L
                            wifiAllocation = 0L
                        }
                    }
                }
            }
        }

        dailyMobileBytes = TrafficMath.safeAdd(dailyMobileBytes, mobileAllocation)
        dailyWifiBytes = TrafficMath.safeAdd(dailyWifiBytes, wifiAllocation)
        sessionBytes = TrafficMath.safeAdd(sessionBytes, deltaTotal)
    }

    private fun detectNetworkType(): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val activeNetwork = connectivityManager.activeNetwork
            val activeCapabilities = connectivityManager.getNetworkCapabilities(activeNetwork)

            // Normal case: the default network directly exposes its transport.
            val activeType = activeCapabilities?.let { capabilities ->
                when {
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> 1
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> 2
                    else -> 0
                }
            } ?: 0

            if (activeType != 0) return activeType

            // When a VPN is the active network, look for a reachable non-VPN
            // network as a fallback. Mobile counters remain authoritative for
            // positive mobile deltas during traffic allocation.
            var hasWifi = false
            for (network in connectivityManager.allNetworks) {
                val capabilities = connectivityManager.getNetworkCapabilities(network) ?: continue
                if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
                if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue

                if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                    return 1
                }
                if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                ) {
                    hasWifi = true
                }
            }
            return if (hasWifi) 2 else 0
        }

        @Suppress("DEPRECATION")
        val activeNetworkInfo = connectivityManager.activeNetworkInfo
            ?: return 0
        if (!activeNetworkInfo.isConnected) return 0

        @Suppress("DEPRECATION")
        return when (activeNetworkInfo.type) {
            ConnectivityManager.TYPE_MOBILE -> 1
            ConnectivityManager.TYPE_WIFI,
            ConnectivityManager.TYPE_ETHERNET -> 2
            else -> 0
        }
    }

    private fun stopMonitoring() {
        synchronized(stateLock) {
            monitorJob?.cancel()
            monitorJob = null
        }

        notificationManager.notify(SpeedNotification.NOTIFICATION_ID, buildNotification(0L, 0L))

        saveToPrefs()
        persistDailyUsage()
    }

    private fun initHardwareCountersLocked() {
        lastHardwareTotalRx = sanitizeBytes(TrafficStats.getTotalRxBytes())
        lastHardwareTotalTx = sanitizeBytes(TrafficStats.getTotalTxBytes())
        val vpn = getVpnTraffic()
        lastHardwareVpnRx = vpn.first
        lastHardwareVpnTx = vpn.second
        lastHardwareMobileRx = sanitizeBytes(TrafficStats.getMobileRxBytes())
        lastHardwareMobileTx = sanitizeBytes(TrafficStats.getMobileTxBytes())
        lastSpeedTime = SystemClock.elapsedRealtime()
    }

    private fun initHardwareCounters() {
        synchronized(stateLock) {
            initHardwareCountersLocked()
        }
    }

    private fun sanitizeBytes(bytes: Long): Long {
        return if (bytes == TrafficStats.UNSUPPORTED.toLong() || bytes < 0L) 0L else bytes
    }

    private fun checkDateRollover() {
        val rollover = synchronized(stateLock) {
            val today = DayCycle.currentDate(this)
            if (today == lastRecordedDate) {
                null
            } else {
                val previous = Usage(
                    date = lastRecordedDate,
                    mobile = dailyMobileBytes,
                    wifi = dailyWifiBytes,
                    total = TrafficMath.safeAdd(dailyMobileBytes, dailyWifiBytes)
                )

                lastRecordedDate = today
                dailyMobileBytes = 0L
                dailyWifiBytes = 0L

                val current = Usage(date = today)
                previous to current
            }
        } ?: return

        // Persist immutable snapshots so a later state change cannot overwrite
        // the record that belonged to the previous accounting day.
        persistDailyUsage(rollover.first)
        persistDailyUsage(rollover.second)
        saveToPrefs()
        refreshMonthlyBase()
    }

    /** Monthly mobile usage of the completed days, used by the "محدودیت" preference. */
    private fun refreshMonthlyBase() {
        val date = synchronized(stateLock) { lastRecordedDate }
        val month = DayCycle.monthOf(date)

        serviceScope.launch(Dispatchers.IO) {
            val result = try {
                usageRepository.getMonthlyMobileExcluding(month, date)
            } catch (_: Exception) {
                0L
            }

            synchronized(stateLock) {
                if (lastRecordedDate == date) {
                    monthlyMobileBase = result.coerceAtLeast(0L)
                }
            }
        }
    }

    private fun monthlyMobileBytes(): Long =
        TrafficMath.safeAdd(monthlyMobileBase, dailyMobileBytes)

    private fun buildNotification(downSpeed: Long, upSpeed: Long): Notification {
        val (mobile, wifi, monthly) = synchronized(stateLock) {
            Triple(
                dailyMobileBytes,
                dailyWifiBytes,
                monthlyMobileBytes()
            )
        }
        return SpeedNotification.build(
            this,
            downSpeed,
            upSpeed,
            mobile,
            wifi,
            monthly,
            isNotificationIdle()
        )
    }

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
            val date = synchronized(stateLock) { lastRecordedDate }
            val todayUsage = usageRepository.getUsageByDate(date)

            if (todayUsage != null) {
                synchronized(stateLock) {
                    if (date == lastRecordedDate) {
                        dailyMobileBytes = maxOf(dailyMobileBytes, todayUsage.mobile)
                        dailyWifiBytes = maxOf(dailyWifiBytes, todayUsage.wifi)
                    }
                }
                saveToPrefs()
            }
            persistDailyUsage()
        }
    }

    private fun saveToPrefs() {
        val snapshot = synchronized(stateLock) {
            Triple(dailyMobileBytes, dailyWifiBytes, lastRecordedDate)
        }

        prefs.edit()
            .putLong("dailyMobileBytes", snapshot.first)
            .putLong("dailyWifiBytes", snapshot.second)
            .putString("lastRecordedDate", snapshot.third)
            .apply()
    }

    private fun persistDailyUsage(snapshot: Usage? = null) {
        val usage = snapshot ?: synchronized(stateLock) {
            Usage(
                date = lastRecordedDate,
                mobile = dailyMobileBytes,
                wifi = dailyWifiBytes,
                total = TrafficMath.safeAdd(dailyMobileBytes, dailyWifiBytes)
            )
        }
        usageRepository.insert(usage)
    }

    override fun onDestroy() {
        synchronized(stateLock) {
            // Mark the screen state false so no new monitoring cycle is started
            // while the service is being torn down.
            isScreenOn = false
        }

        stopMonitoring()

        try {
            unregisterReceiver(systemEventReceiver)
        } catch (_: Exception) {
            // Receiver may already have been unregistered by the framework.
        }

        // Capture any bytes that arrived since the final monitor tick.
        sampleAndAccumulateTraffic()
        saveToPrefs()
        persistDailyUsage()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }

        if (instance == this) instance = null
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        var instance: InternetService? = null
    }

    fun getSessionInfo(): Pair<Long, Long> {
        return synchronized(stateLock) {
            Pair(
                max(0L, SystemClock.elapsedRealtime() - sessionStartTimeMs),
                sessionBytes
            )
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
