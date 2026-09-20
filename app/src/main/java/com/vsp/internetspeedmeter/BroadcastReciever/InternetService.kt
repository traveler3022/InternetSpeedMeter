package com.vsp.internetspeedmeter.BroadcastReciever

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.net.TrafficStats
import android.os.IBinder
import com.vsp.internetspeedmeter.NotificationService
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
        @Override
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    isScreenOn = false
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
        syncTrafficStats()

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        registerReceiver(screenReceiver, filter)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val initialNotification = notificationService.updateNotification(0L, 0L, dailyMobileBytes, dailyWifiBytes).build()
        startForeground(NotificationService.NOTIFICATION_ID, initialNotification)

        startMonitoring()

        return START_STICKY
    }

    private fun startMonitoring() {
        if (monitorJob?.isActive == true) return

        monitorJob = serviceScope.launch {
            while (isActive && isScreenOn) {
                val curTotalRx = TrafficStats.getTotalRxBytes()
                val curTotalTx = TrafficStats.getTotalTxBytes()
                val curMobileRx = TrafficStats.getMobileRxBytes()
                val curMobileTx = TrafficStats.getMobileTxBytes()

                if (prevTotalRx == 0L || curTotalRx < prevTotalRx) {
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

                prefs.edit()
                    .putLong("dailyMobileBytes", dailyMobileBytes)
                    .putLong("dailyWifiBytes", dailyWifiBytes)
                    .putString("lastRecordedDate", lastRecordedDate)
                    .apply()

                val notification = notificationService.updateNotification(
                    downSpeed, upSpeed, dailyMobileBytes, dailyWifiBytes
                ).build()

                startForeground(NotificationService.NOTIFICATION_ID, notification)

                delay(1000)
            }
        }
    }

    private fun stopMonitoring() {
        monitorJob?.cancel()
        monitorJob = null
    }

    private fun syncTrafficStats() {
        prevTotalRx = TrafficStats.getTotalRxBytes()
        prevTotalTx = TrafficStats.getTotalTxBytes()
        prevMobileRx = TrafficStats.getMobileRxBytes()
        prevMobileTx = TrafficStats.getMobileTxBytes()
    }

    private fun checkDateRollover() {
        val today = dateFormat.format(Calendar.getInstance().time)
        if (today != lastRecordedDate) {
            lastRecordedDate = today
            dailyMobileBytes = 0L
            dailyWifiBytes = 0L
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(screenReceiver)
        } catch (_: Exception) {}
        stopMonitoring()
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        serviceScope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
