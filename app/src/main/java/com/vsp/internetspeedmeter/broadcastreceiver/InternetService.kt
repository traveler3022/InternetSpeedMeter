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
import java.util.Locale
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
    // Per-interface counter baselines (see TrafficMath.interfaceDeltas)
    private val ifaceBaselines = HashMap<String, TrafficMath.Counters>()
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

    /**
     * Reads traffic from the underlying non-VPN network interfaces.
     *
     * Device-wide aggregate counters can aggregate traffic at multiple layers
     * when a VPN is active. A VPN packet can be observed on its
     * virtual interface and again on the physical transport, which makes a total
     * device counter unsuitable as the source for a speed meter that must not
     * double-count the same network transfer.
     *
     * We therefore resolve the interfaces belonging to non-VPN Wi-Fi/mobile/
     * Ethernet networks via ConnectivityManager + LinkProperties and sample only
     * those interfaces.
     */
    private data class IfaceReading(val counters: TrafficMath.Counters, val networkType: Int)

    @Suppress("DEPRECATION")
    private fun readHardwareTraffic(): Map<String, IfaceReading> {
        // interface name -> network type (1: mobile, 2: Wi-Fi/Ethernet)
        val interfaceTypes = LinkedHashMap<String, Int>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            for (network in connectivityManager.allNetworks) {
                val capabilities = connectivityManager.getNetworkCapabilities(network)
                    ?: continue

                // A VPN network may expose the underlying Wi-Fi/cellular transport
                // too. NOT_VPN is therefore the authoritative filter here.
                if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)) {
                    continue
                }

                if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                    continue
                }

                val type = when {
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> 1
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> 2
                    else -> continue
                }

                connectivityManager.getLinkProperties(network)?.interfaceName
                    ?.takeIf { it.isNotBlank() }
                    ?.let { interfaceTypes[it] = type }
            }
        }

        // Fallback for old Android/vendor implementations where ConnectivityManager
        // does not expose a usable interface name. Do not include virtual VPN/tunnel
        // interfaces in this fallback path.
        if (interfaceTypes.isEmpty()) {
            try {
                val interfaces = NetworkInterface.getNetworkInterfaces()
                interfaces?.let { all ->
                    for (iface in all) {
                        val name = iface.name
                        if (!iface.isUp || iface.isLoopback || isVirtualVpnInterface(name)) {
                            continue
                        }
                        interfaceTypes[name] = networkTypeFromName(name)
                    }
                }
            } catch (_: Exception) {
                // Keep the empty result; the next sample can retry discovery.
            }
        }

        val result = HashMap<String, IfaceReading>()
        for ((name, type) in interfaceTypes) {
            val counters = TrafficMath.Counters(
                rx = readInterfaceBytes(name, "rx_bytes"),
                tx = readInterfaceBytes(name, "tx_bytes")
            )
            result[name] = IfaceReading(counters, type)
        }
        return result
    }

    private fun networkTypeFromName(name: String): Int {
        val lower = name.lowercase(Locale.ROOT)
        return when {
            lower.startsWith("wlan") || lower.startsWith("eth") -> 2
            lower.startsWith("rmnet") || lower.startsWith("ccmni") ||
                lower.startsWith("pdp") || lower.startsWith("wwan") ||
                lower.startsWith("seth") -> 1
            else -> 0
        }
    }

    private fun isVirtualVpnInterface(name: String): Boolean {
        val lower = name.lowercase(Locale.ROOT)
        return lower == "lo" ||
            lower.startsWith("tun") ||
            lower.startsWith("tap") ||
            lower.startsWith("wg") ||
            lower.startsWith("vpn") ||
            // 464xlat stacked interface: its traffic is also counted on the
            // underlying cellular interface.
            lower.startsWith("v4-") ||
            lower.startsWith("dummy")
    }

    private fun readInterfaceBytes(iface: String, file: String): Long {
        // TrafficStats.getRxBytes(String)/getTxBytes(String) are public only
        // since API 31; below that they are hidden APIs.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val fromTrafficStats = if (file == "rx_bytes") {
                TrafficStats.getRxBytes(iface)
            } else {
                TrafficStats.getTxBytes(iface)
            }

            if (fromTrafficStats != TrafficStats.UNSUPPORTED.toLong() && fromTrafficStats >= 0L) {
                return fromTrafficStats
            }
        }

        return readSysFs(iface, file)
    }

    private fun readSysFs(iface: String, file: String): Long {
        return try {
            val f = java.io.File("/sys/class/net/$iface/statistics/$file")
            if (f.exists()) {
                f.readText().trim().toLong().coerceAtLeast(0L)
            } else {
                0L
            }
        } catch (_: Exception) {
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
        val readings = readHardwareTraffic()
        val deltas = TrafficMath.interfaceDeltas(
            ifaceBaselines,
            readings.mapValues { it.value.counters }
        )

        var deltaRx = 0L
        var deltaTx = 0L
        var mobileBytes = 0L
        var wifiBytes = 0L
        var unknownBytes = 0L
        for ((name, delta) in deltas) {
            deltaRx = TrafficMath.safeAdd(deltaRx, delta.rx)
            deltaTx = TrafficMath.safeAdd(deltaTx, delta.tx)
            val bytes = TrafficMath.safeAdd(delta.rx, delta.tx)
            when (readings[name]?.networkType) {
                1 -> mobileBytes = TrafficMath.safeAdd(mobileBytes, bytes)
                2 -> wifiBytes = TrafficMath.safeAdd(wifiBytes, bytes)
                else -> unknownBytes = TrafficMath.safeAdd(unknownBytes, bytes)
            }
        }

        val elapsedMs = curTime - lastSpeedTime
        lastSpeedTime = curTime

        allocateTrafficLocked(mobileBytes, wifiBytes, unknownBytes)

        val loopElapsedMs = max(0L, SystemClock.elapsedRealtime() - curTime)
        return TrafficMath.Sample(
            deltaRx = deltaRx,
            deltaTx = deltaTx,
            downSpeed = TrafficMath.bytesPerSecond(deltaRx, elapsedMs),
            upSpeed = TrafficMath.bytesPerSecond(deltaTx, elapsedMs),
            loopElapsedMs = loopElapsedMs
        )
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
     * Adds already-classified traffic to the daily totals. Bytes on interfaces
     * whose transport is unknown (name-based fallback) go to the active
     * transport, or to the last known one when nothing is active.
     */
    private fun allocateTrafficLocked(mobileBytes: Long, wifiBytes: Long, unknownBytes: Long) {
        val newNetworkType = detectNetworkType()

        if (newNetworkType != 0 && newNetworkType != currentNetworkType) {
            currentNetworkType = newNetworkType
            sessionStartTimeMs = SystemClock.elapsedRealtime()
            sessionBytes = 0L
        }

        var mobileAllocation = mobileBytes
        var wifiAllocation = wifiBytes
        when (if (newNetworkType != 0) newNetworkType else currentNetworkType) {
            1 -> mobileAllocation = TrafficMath.safeAdd(mobileAllocation, unknownBytes)
            2 -> wifiAllocation = TrafficMath.safeAdd(wifiAllocation, unknownBytes)
        }

        val deltaTotal = TrafficMath.safeAdd(mobileAllocation, wifiAllocation)
        if (deltaTotal <= 0L) return

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
        ifaceBaselines.clear()
        for ((name, reading) in readHardwareTraffic()) {
            ifaceBaselines[name] = reading.counters
        }
        lastSpeedTime = SystemClock.elapsedRealtime()
    }

    private fun initHardwareCounters() {
        synchronized(stateLock) {
            initHardwareCountersLocked()
        }
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
