package com.vsp.internetspeedmeter

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.Typeface
import android.graphics.drawable.Icon
import android.os.Build
import com.vsp.internetspeedmeter.util.FormatUtils

class NotificationService(private val context: Context) {

    companion object {
        const val CHANNEL_ID = "speed_meter_channel"
        const val CHANNEL_NAME = "Internet Speed Meter"
        const val CHANNEL_DESC = "Displays real-time network speed and daily usage"
        const val TAG = "internetspeed"
        const val NOTIFICATION_ID = 1
    }

    private lateinit var mBuilder: Notification.Builder
    private val mNotifyMgr: NotificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private lateinit var bitmap: Bitmap
    private lateinit var canvas: Canvas
    private lateinit var paint: Paint
    private lateinit var unitsPaint: Paint

    init {
        setupIcon()
        createNotification()
    }

    fun createNotification() {
        createNotificationChannel()

        mBuilder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(context)
        }

        val notifiIntent = Intent(context, MainActivity::class.java)
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags = flags or PendingIntent.FLAG_IMMUTABLE
        }
        val pendingIntent = PendingIntent.getActivity(context, 100, notifiIntent, flags)

        val icon = getIcon("0", "KB")
        if (icon != null) {
            mBuilder.setSmallIcon(icon)
        }
        mBuilder.setContentTitle("Down: 0 KB/s   Up: 0 KB/s")
            .setContentText("Mobile: 0 B   WiFi: 0 B")
            .setOngoing(true)
            .setShowWhen(false)
            .setContentIntent(pendingIntent)
            .setOnlyAlertOnce(true)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            mBuilder.setVisibility(Notification.VISIBILITY_PUBLIC)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            mBuilder.setBadgeIconType(Notification.BADGE_ICON_NONE)
        }
    }

    fun updateNotification(
        downSpeedBytes: Long,
        upSpeedBytes: Long,
        mobileBytes: Long,
        wifiBytes: Long
    ): Notification.Builder {
        val downStr = FormatUtils.formatSpeed(downSpeedBytes)
        val upStr = FormatUtils.formatSpeed(upSpeedBytes)

        val totalSpeedBytes = downSpeedBytes + upSpeedBytes
        val iconSpeed = FormatUtils.formatSpeedForIcon(totalSpeedBytes)
        val icon = getIcon(iconSpeed.value, iconSpeed.unit)
        if (icon != null) {
            mBuilder.setSmallIcon(icon)
        }

        val mobileStr = FormatUtils.formatBytes(mobileBytes)
        val wifiStr = FormatUtils.formatBytes(wifiBytes)
        val totalStr = FormatUtils.formatBytes(mobileBytes + wifiBytes)

        mBuilder.setContentTitle("Down: $downStr   Up: $upStr")
        mBuilder.setContentText("Mobile: $mobileStr   WiFi: $wifiStr")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH) {
            mBuilder.setSubText("Total: $totalStr")
        }

        return mBuilder
    }

    fun notify(notification: Notification) {
        mNotifyMgr.notify(NOTIFICATION_ID, notification)
    }

    private fun setupIcon() {
        paint = Paint().apply {
            isAntiAlias = true
            textSize = 52f
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            color = Color.WHITE
        }

        unitsPaint = Paint().apply {
            isAntiAlias = true
            textSize = 36f
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            color = Color.WHITE
        }

        bitmap = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
        canvas = Canvas(bitmap)
    }

    fun getIcon(speed: String, units: String): Icon? {
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        canvas.drawText(speed, 48f, 50f, paint)
        canvas.drawText(units, 48f, 88f, unitsPaint)

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Icon.createWithBitmap(bitmap)
        } else {
            null
        }
    }

    private fun createNotificationChannel() {
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
            }
            mNotifyMgr.createNotificationChannel(channel)
        }
    }
}
