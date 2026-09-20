package com.vsp.internetspeedmeter

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.Typeface
import android.graphics.drawable.Icon
import android.os.Build
import androidx.preference.PreferenceManager
import com.vsp.internetspeedmeter.util.FormatUtils
import kotlin.math.max

class NotificationService(private val context: Context) {

    companion object {
        const val CHANNEL_ID = "speed_meter_channel"
        const val CHANNEL_ID_IDLE = "speed_meter_channel_idle"
        const val CHANNEL_NAME = "Internet Speed Meter"
        const val CHANNEL_DESC = "Displays real-time network speed and daily usage"
        const val NOTIFICATION_ID = 1
    }

    private lateinit var mBuilder: Notification.Builder
    private val mNotifyMgr: NotificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private lateinit var speedPaint: Paint
    private lateinit var unitPaint: Paint
    private var iconSize = 48
    private var isIdle = false
    private val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)

    init {
        setupPaints()
        createNotificationChannels()
        createNotification()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = CHANNEL_DESC
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            mNotifyMgr.createNotificationChannel(channel)

            val channelIdle = NotificationChannel(
                CHANNEL_ID_IDLE,
                "Internet Speed Meter (Idle)",
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = "Used when connection is idle"
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            mNotifyMgr.createNotificationChannel(channelIdle)
        }
    }

    fun createNotification() {
        mBuilder = getBuilder(false)

        val notifiIntent = Intent(context, MainActivity::class.java)
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags = flags or PendingIntent.FLAG_IMMUTABLE
        }
        val pendingIntent = PendingIntent.getActivity(context, 100, notifiIntent, flags)

        val icon = getIcon("0", "KB")
        if (icon != null) {
            mBuilder.setSmallIcon(icon)
        } else {
            @Suppress("DEPRECATION")
            mBuilder.setSmallIcon(android.R.drawable.stat_sys_download)
        }

        mBuilder.setContentTitle("Speed: 0 B/s")
            .setContentText("Mobile: 0 B  |  WiFi: 0 B")
            .setOngoing(true)
            .setShowWhen(false)
            .setContentIntent(pendingIntent)
            .setOnlyAlertOnce(true)

        applySettings()
    }

    private fun getBuilder(idle: Boolean): Notification.Builder {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, if (idle) CHANNEL_ID_IDLE else CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(context).apply {
                setPriority(if (idle) Notification.PRIORITY_MIN else Notification.PRIORITY_LOW)
            }
        }
    }

    private fun applySettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            val hideLock = prefs.getBoolean("hide_lockscreen_notification", false)
            mBuilder.setVisibility(if (hideLock) Notification.VISIBILITY_SECRET else Notification.VISIBILITY_PUBLIC)
        }
    }

    fun updateNotification(
        downSpeedBytes: Long,
        upSpeedBytes: Long,
        mobileBytes: Long,
        wifiBytes: Long
    ): Notification.Builder {
        val totalSpeedBytes = max(0L, downSpeedBytes + upSpeedBytes)
        
        val hideWhenIdle = prefs.getBoolean("hide_notification_idle", false)
        val currentlyIdle = totalSpeedBytes == 0L
        
        if (hideWhenIdle && currentlyIdle != isIdle) {
            isIdle = currentlyIdle
            val oldIntent = mBuilder.build().contentIntent
            mBuilder = getBuilder(isIdle)
            if (oldIntent != null) {
                mBuilder.setContentIntent(oldIntent)
            }
            mBuilder.setOngoing(true)
                .setShowWhen(false)
                .setOnlyAlertOnce(true)
        } else if (!hideWhenIdle && isIdle) {
            isIdle = false
            val oldIntent = mBuilder.build().contentIntent
            mBuilder = getBuilder(false)
            if (oldIntent != null) {
                mBuilder.setContentIntent(oldIntent)
            }
            mBuilder.setOngoing(true)
                .setShowWhen(false)
                .setOnlyAlertOnce(true)
        }

        applySettings()

        val showUpDown = prefs.getBoolean("show_up_down_speed", false)

        val downStr = FormatUtils.formatSpeed(downSpeedBytes)
        val upStr = FormatUtils.formatSpeed(upSpeedBytes)
        val totalSpeedStr = FormatUtils.formatSpeed(totalSpeedBytes)

        val iconSpeed = FormatUtils.formatSpeedForIcon(totalSpeedBytes)
        val icon = getIcon(iconSpeed.value, iconSpeed.unit + "/s")
        if (icon != null) {
            mBuilder.setSmallIcon(icon)
        } else {
            @Suppress("DEPRECATION")
            mBuilder.setSmallIcon(android.R.drawable.stat_sys_download)
        }

        val mobileStr = FormatUtils.formatBytes(mobileBytes)
        val wifiStr = FormatUtils.formatBytes(wifiBytes)

        if (showUpDown) {
            mBuilder.setContentTitle("Down: $downStr   Up: $upStr")
        } else {
            mBuilder.setContentTitle("Speed: $totalSpeedStr")
        }

        mBuilder.setContentText("Mobile: $mobileStr  |  WiFi: $wifiStr")

        return mBuilder
    }

    fun notify(notification: Notification) {
        try {
            mNotifyMgr.notify(NOTIFICATION_ID, notification)
        } catch (_: Exception) {}
    }

    private fun setupPaints() {
        val density = context.resources.displayMetrics.density
        iconSize = (density * 24).toInt().coerceIn(24, 96)

        speedPaint = Paint().apply {
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
            color = Color.WHITE
            textSize = iconSize * 0.52f
        }

        unitPaint = Paint().apply {
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            color = Color.WHITE
            textSize = iconSize * 0.38f
        }
    }

    fun getIcon(speed: String, units: String): Icon? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return null

        val bitmap = Bitmap.createBitmap(iconSize, iconSize, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)

        val centerX = iconSize / 2f
        val defaultSpeedSize = iconSize * 0.52f
        speedPaint.textSize = defaultSpeedSize
        val textWidth = speedPaint.measureText(speed)
        val maxTextWidth = iconSize - 2f
        if (textWidth > maxTextWidth && textWidth > 0f) {
            speedPaint.textSize = defaultSpeedSize * (maxTextWidth / textWidth)
        }

        val spMetrics = speedPaint.fontMetrics
        val unMetrics = unitPaint.fontMetrics

        val spHeight = spMetrics.descent - spMetrics.ascent
        val unHeight = unMetrics.descent - unMetrics.ascent
        val totalTextHeight = spHeight + unHeight

        val startY = (iconSize - totalTextHeight) / 2f
        val speedBaseline = startY - spMetrics.ascent
        val unitBaseline = speedBaseline + spMetrics.descent - unMetrics.ascent

        canvas.drawText(speed, centerX, speedBaseline, speedPaint)
        canvas.drawText(units, centerX, unitBaseline, unitPaint)

        val immutableBitmap = bitmap.copy(bitmap.config, false)
        bitmap.recycle()

        return Icon.createWithBitmap(immutableBitmap)
    }
}
