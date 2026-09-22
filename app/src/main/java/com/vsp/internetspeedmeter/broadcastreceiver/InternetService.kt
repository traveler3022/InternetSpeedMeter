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
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
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
    // Counter baselines per key of readCounters() (see TrafficMath.interfaceDeltas)
    private val counterBaselines = HashMap<String, TrafficMath.Counters>()
    private var lastSpeedTime = 0L
    // Last reported speeds, re-shown for one tick when the counters look stale
    private var lastDownSpeed = 0L
    private var lastUpSpeed = 0L
    private var heldStaleTick = false

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

    /**
     * Network layout used by every tick. Recomputing it takes several binder
     * calls per network, so, like the reference app (which re-reads it only on
     * connectivity changes), it is cached and rebuilt only after a
     * NetworkCallback event or when it is older than [NETWORK_SNAPSHOT_MAX_AGE_MS].
     */
    private class NetworkSnapshot(
        val mobileKey: String,
        val wifiIfaces: List<String>,
        val wifiConnected: Boolean,
        val type: Int,
        val connected: Boolean,
        val takenAt: Long
    )

    @Volatile
    private var networkDirty = true
    private var networkSnapshot: NetworkSnapshot? = null
    private var networkCallbacks = emptyList<ConnectivityManager.NetworkCallback>()

    // Ticks since the notification was last posted even though it had not changed
    private var ticksSinceForcedPost = 0

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

        registerNetworkCallbacks()
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
        networkDirty = true
        postNotification(0L, 0L) { notification ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    SpeedNotification.NOTIFICATION_ID,
                    notification,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(SpeedNotification.NOTIFICATION_ID, notification)
            }
        }

        if (intent?.action == ACTION_RESET) {
            resetStatistics()
        }

        if (intent?.action == "UPDATE_NOTIFICATION_SETTINGS") {
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
     * Counter snapshot, keyed for [TrafficMath.interfaceDeltas]. Mobile and
     * Wi-Fi are read separately and never derived from each other:
     *
     * [MOBILE_PREFIX] + cellular interfaces = TrafficStats mobile counter. The
     * platform keeps it right (464xlat, modem interface names); the key holds
     * the interface set, so a set change starts a new baseline instead of
     * counting a since-boot counter as new traffic.
     *
     * [WIFI_PREFIX] + name = each interface of a connected Wi-Fi/Ethernet
     * network, plus its 464xlat "v4-" interface. Nothing is read for Wi-Fi
     * while no Wi-Fi network is connected.
     *
     * VPN tunnels are never read, so VPN traffic is counted once, on the
     * network that carried it.
     */
    private fun readCounters(): Map<String, TrafficMath.Counters> {
        val snapshot = currentNetworkSnapshot()
        val result = HashMap<String, TrafficMath.Counters>()
        for (name in snapshot.wifiIfaces) {
            readInterface(name)?.let { result[WIFI_PREFIX + name] = it }
        }

        // Old devices where the Wi-Fi interface counters are unreadable.
        if (snapshot.wifiConnected && result.isEmpty()) {
            val rx = TrafficStats.getTotalRxBytes() - TrafficStats.getMobileRxBytes()
            val tx = TrafficStats.getTotalTxBytes() - TrafficStats.getMobileTxBytes()
            if (rx >= 0L && tx >= 0L) result[WIFI_PREFIX + "*"] = TrafficMath.Counters(rx, tx)
        }

        result[MOBILE_PREFIX + snapshot.mobileKey] = TrafficMath.Counters(
            TrafficStats.getMobileRxBytes().coerceAtLeast(0L),
            TrafficStats.getMobileTxBytes().coerceAtLeast(0L)
        )
        return result
    }

    private fun currentNetworkSnapshot(): NetworkSnapshot = synchronized(stateLock) {
        val now = SystemClock.elapsedRealtime()
        val cached = networkSnapshot
        if (cached != null && !networkDirty &&
            now - cached.takenAt < NETWORK_SNAPSHOT_MAX_AGE_MS
        ) {
            return cached
        }
        // Cleared before reading, so an event that arrives meanwhile triggers
        // another rebuild on the next tick.
        networkDirty = false
        takeNetworkSnapshot(now).also { networkSnapshot = it }
    }

    private fun takeNetworkSnapshot(now: Long): NetworkSnapshot {
        val mobileIfaces = sortedSetOf<String>()
        val wifiIfaces = ArrayList<String>()
        var wifiConnected = false

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            for (network in connectivityManager.allNetworks) {
                val capabilities = connectivityManager.getNetworkCapabilities(network) ?: continue
                if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
                val iface = connectivityManager.getLinkProperties(network)?.interfaceName
                    ?.takeIf { it.isNotBlank() } ?: continue
                when {
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ->
                        mobileIfaces.add(iface)
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> {
                        wifiConnected = true
                        wifiIfaces.add(iface)
                        wifiIfaces.add(CLAT_PREFIX + iface)
                    }
                }
            }
        } else {
            @Suppress("DEPRECATION")
            wifiConnected = connectivityManager.activeNetworkInfo?.let {
                it.isConnected && it.type != ConnectivityManager.TYPE_MOBILE
            } == true
        }

        return NetworkSnapshot(
            mobileKey = mobileIfaces.joinToString(","),
            wifiIfaces = wifiIfaces,
            wifiConnected = wifiConnected,
            type = detectNetworkType(),
            connected = isConnected(),
            takenAt = now
        )
    }

    /**
     * Marks the cached network layout stale on any network change. If the
     * callbacks cannot be registered, [networkDirty] stays true and the layout
     * is simply re-read every tick, as before.
     */
    private fun registerNetworkCallbacks() {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { networkDirty = true }
            override fun onLost(network: Network) { networkDirty = true }
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                networkDirty = true
            }
            override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) {
                networkDirty = true
            }
        }
        val registered = ArrayList<ConnectivityManager.NetworkCallback>()
        try {
            // Every non-restricted network, VPNs and networks without internet included.
            val request = NetworkRequest.Builder()
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .build()
            connectivityManager.registerNetworkCallback(request, callback)
            registered.add(callback)
        } catch (_: Exception) {
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val defaultCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) { networkDirty = true }
                override fun onLost(network: Network) { networkDirty = true }
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    networkDirty = true
                }
            }
            try {
                connectivityManager.registerDefaultNetworkCallback(defaultCallback)
                registered.add(defaultCallback)
            } catch (_: Exception) {
            }
        }
        networkCallbacks = registered
        networkDirty = true
    }

    private fun unregisterNetworkCallbacks() {
        for (callback in networkCallbacks) {
            try {
                connectivityManager.unregisterNetworkCallback(callback)
            } catch (_: Exception) {
            }
        }
        networkCallbacks = emptyList()
    }

    private fun readInterface(iface: String): TrafficMath.Counters? {
        // TrafficStats.getRxBytes(String)/getTxBytes(String) are public only
        // since API 31; below that they are hidden APIs.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val rx = TrafficStats.getRxBytes(iface)
            val tx = TrafficStats.getTxBytes(iface)
            return if (rx >= 0L && tx >= 0L) TrafficMath.Counters(rx, tx) else null
        }
        val rx = readSysFs(iface, "rx_bytes") ?: return null
        val tx = readSysFs(iface, "tx_bytes") ?: return null
        return TrafficMath.Counters(rx, tx)
    }

    private fun readSysFs(iface: String, file: String): Long? {
        return try {
            java.io.File("/sys/class/net/$iface/statistics/$file")
                .readText().trim().toLong().coerceAtLeast(0L)
        } catch (_: Exception) {
            null
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

            // An unchanged notification is not re-posted, except every
            // FORCED_POST_TICKS ticks so a dismissed one comes back.
            val force = ++ticksSinceForcedPost >= FORCED_POST_TICKS
            if (postNotification(sample.downSpeed, sample.upSpeed, force = force)) {
                ticksSinceForcedPost = 0
            }

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
        val readings = readCounters()
        // The mobile counter is only comparable with the same interface set.
        counterBaselines.keys.removeAll { it.startsWith(MOBILE_PREFIX) && it !in readings }
        val deltas = TrafficMath.interfaceDeltas(counterBaselines, readings)

        var deltaRx = 0L
        var deltaTx = 0L
        var mobileBytes = 0L
        var wifiBytes = 0L
        for ((key, delta) in deltas) {
            deltaRx = TrafficMath.safeAdd(deltaRx, delta.rx)
            deltaTx = TrafficMath.safeAdd(deltaTx, delta.tx)
            val bytes = TrafficMath.safeAdd(delta.rx, delta.tx)
            if (key.startsWith(MOBILE_PREFIX)) {
                mobileBytes = TrafficMath.safeAdd(mobileBytes, bytes)
            } else {
                wifiBytes = TrafficMath.safeAdd(wifiBytes, bytes)
            }
        }

        allocateTrafficLocked(mobileBytes, wifiBytes, 0L)

        // Android 15+ caches TrafficStats results for ~1 s (NetworkStatsService
        // DEFAULT_TRAFFIC_STATS_CACHE_EXPIRY_DURATION_MS). Polling every second
        // then often returns the previous value: that tick reads 0 and the next
        // one gets two seconds of bytes. So when the counters stop moving right
        // after traffic, keep the last speed for one tick and keep the baseline
        // time, and the next change is divided by the real elapsed time.
        val downSpeed: Long
        val upSpeed: Long
        if (Build.VERSION.SDK_INT >= 35 && deltaRx == 0L && deltaTx == 0L &&
            !heldStaleTick && (lastDownSpeed > 0L || lastUpSpeed > 0L)
        ) {
            heldStaleTick = true
            downSpeed = lastDownSpeed
            upSpeed = lastUpSpeed
        } else {
            val elapsedMs = curTime - lastSpeedTime
            lastSpeedTime = curTime
            heldStaleTick = false
            downSpeed = TrafficMath.bytesPerSecond(deltaRx, elapsedMs)
            upSpeed = TrafficMath.bytesPerSecond(deltaTx, elapsedMs)
            lastDownSpeed = downSpeed
            lastUpSpeed = upSpeed
        }

        val loopElapsedMs = max(0L, SystemClock.elapsedRealtime() - curTime)
        return TrafficMath.Sample(
            deltaRx = deltaRx,
            deltaTx = deltaTx,
            downSpeed = downSpeed,
            upSpeed = upSpeed,
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
        val newNetworkType = currentNetworkSnapshot().type

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

        postNotification(0L, 0L)

        saveToPrefs()
        persistDailyUsage()
    }

    /**
     * "تنظیم مجدد آمار". Done here rather than by stopping the service: a stopping
     * service writes its in-memory totals back, which undid the reset.
     */
    private fun resetStatistics() {
        synchronized(stateLock) {
            dailyMobileBytes = 0L
            dailyWifiBytes = 0L
            monthlyMobileBase = 0L
            sessionStartTimeMs = SystemClock.elapsedRealtime()
            sessionBytes = 0L
            speedHistory.fill(0L)
            initHardwareCountersLocked()
        }
        saveToPrefs()
        usageRepository.resetAsync(DayCycle.upcomingDates().map { Usage(date = it) })
        postNotification(0L, 0L)
    }

    private fun initHardwareCountersLocked() {
        counterBaselines.clear()
        counterBaselines.putAll(readCounters())
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

    /** Builds and posts the notification; see [SpeedNotification.post]. */
    private fun postNotification(
        downSpeed: Long,
        upSpeed: Long,
        force: Boolean = true,
        poster: (Notification) -> Unit = {
            notificationManager.notify(SpeedNotification.NOTIFICATION_ID, it)
        }
    ): Boolean {
        val (mobile, wifi, monthly) = synchronized(stateLock) {
            Triple(
                dailyMobileBytes,
                dailyWifiBytes,
                monthlyMobileBytes()
            )
        }
        return SpeedNotification.post(
            this,
            poster,
            downSpeed,
            upSpeed,
            mobile,
            wifi,
            monthly,
            isNotificationIdle(),
            force
        )
    }

    /** "تنها زمانی که به اینترنت متصل هستم": silence the notification while offline. */
    private fun isNotificationIdle(): Boolean {
        val onlyWhenConnected = androidx.preference.PreferenceManager
            .getDefaultSharedPreferences(this)
            .getBoolean("notification_when_connected", false)
        return onlyWhenConnected && !currentNetworkSnapshot().connected
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

        unregisterNetworkCallbacks()

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

        const val ACTION_RESET = "com.vsp.internetspeedmeter.RESET_STATS"

        private const val MOBILE_PREFIX = "mobile:"
        private const val WIFI_PREFIX = "wifi:"

        /** Name prefix of the 464xlat interface (Nat464Xlat.CLAT_PREFIX). */
        private const val CLAT_PREFIX = "v4-"

        /** Safety net in case a network change produced no callback. */
        private const val NETWORK_SNAPSHOT_MAX_AGE_MS = 30_000L

        private const val FORCED_POST_TICKS = 10
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
