package com.vsp.internetspeedmeter.notification

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
import android.graphics.Typeface
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.core.os.ConfigurationCompat
import androidx.preference.PreferenceManager
import com.vsp.internetspeedmeter.DialogActivity
import com.vsp.internetspeedmeter.MainActivity
import com.vsp.internetspeedmeter.R
import com.vsp.internetspeedmeter.util.FormatUtils
import com.vsp.internetspeedmeter.util.PersianFormat

/**
 * ORIGINAL look (do NOT replace with a custom RemoteViews layout):
 *   status-bar icon  = speed number + unit, drawn as a small bitmap icon
 *   title            = "سرعت: ۲ ک ب/ث"
 *   text             = "موبایل: ۱۵۰٬۷ م ب   وای فای: ۰ م ب"
 * A custom contentView is what produced the black clipped box with the circle
 * inside the body; the stock template lets the launcher draw the round badge.
 */
object SpeedNotification {

    const val CHANNEL_ID = "internet_speed_channel"
    const val CHANNEL_ID_IDLE = "internet_speed_channel_idle"
    const val NOTIFICATION_ID = 1001

    /** Channels of the previous custom-layout notification. */
    private const val LEGACY_CHANNEL_ID = "speed_meter_channel"
    private const val LEGACY_CHANNEL_ID_IDLE = "speed_meter_channel_idle"
    private const val LEGACY_NOTIFICATION_ID = 1

    fun ensureChannel(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        try {
            nm.cancel(LEGACY_NOTIFICATION_ID)
        } catch (_: Exception) {
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        try {
            nm.deleteNotificationChannel(LEGACY_CHANNEL_ID)
            nm.deleteNotificationChannel(LEGACY_CHANNEL_ID_IDLE)
        } catch (_: Exception) {
        }

        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.app_name),
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    setShowBadge(false)
                    enableVibration(false)
                    setSound(null, null)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                }
            )
        }
        if (nm.getNotificationChannel(CHANNEL_ID_IDLE) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID_IDLE,
                    context.getString(R.string.app_name) + " (idle)",
                    NotificationManager.IMPORTANCE_MIN
                ).apply {
                    setShowBadge(false)
                    enableVibration(false)
                    setSound(null, null)
                    lockscreenVisibility = Notification.VISIBILITY_SECRET
                }
            )
        }
    }

    /**
     * @param idle true while "تنها زمانی که به اینترنت متصل هستم" is on and there
     *   is no connection, which moves the notification to the silent channel.
     */
    fun build(
        context: Context,
        downBytesPerSec: Long,
        upBytesPerSec: Long,
        mobileTodayBytes: Long,
        wifiTodayBytes: Long,
        monthlyMobileBytes: Long = 0L,
        idle: Boolean = false
    ): Notification {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val hideOnLockscreen = prefs.getBoolean("hide_lockscreen", false)
        val showUpDown = prefs.getBoolean("show_up_down_speed", true)
        val clickAction = prefs.getString("notification_click_action", "dialog")
        val useBits = prefs.getString("speed_unit", "byte") == "bit"

        val totalSpeed = downBytesPerSec + upBytesPerSec

        val title = if (showUpDown) {
            context.getString(
                R.string.notif_up_down,
                formatSpeed(context, totalSpeed, useBits),
                formatSpeed(context, downBytesPerSec, useBits),
                formatSpeed(context, upBytesPerSec, useBits)
            )
        } else {
            context.getString(R.string.notif_speed, formatSpeed(context, totalSpeed, useBits))
        }

        // "محدودیت استفاده از اینترنت سیم کارت": 0 disables it, otherwise the
        // monthly mobile figure is shown against the cap.
        val limitBytes = monthlyLimitBytes(prefs)
        val text = if (limitBytes > 0L) {
            context.getString(
                R.string.notif_data_limit,
                formatBytes(context, monthlyMobileBytes),
                formatBytes(context, limitBytes),
                formatBytes(context, wifiTodayBytes)
            )
        } else {
            context.getString(
                R.string.notif_data,
                formatBytes(context, mobileTodayBytes),
                formatBytes(context, wifiTodayBytes)
            )
        }

        val target = when (clickAction) {
            "app" -> Intent(context, MainActivity::class.java)
            "none" -> null
            else -> Intent(context, DialogActivity::class.java)
        }
        val pending = target?.let {
            it.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            PendingIntent.getActivity(
                context, 0, it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        val builder = NotificationCompat.Builder(
            context, if (idle) CHANNEL_ID_IDLE else CHANNEL_ID
        )
            .setSmallIcon(IconCompat.createWithBitmap(speedIcon(totalSpeed, useBits)))
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(pending)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setSilent(true)
            .setPriority(
                if (idle) NotificationCompat.PRIORITY_MIN else NotificationCompat.PRIORITY_LOW
            )
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(
                if (hideOnLockscreen) NotificationCompat.VISIBILITY_SECRET
                else NotificationCompat.VISIBILITY_PUBLIC
            )

        // "رنگ اعلان": a foreground-service notification may be colorized, which
        // is the only way to tint the surface without a custom layout.
        when (prefs.getString("notification_color", "system")) {
            "light" -> builder.setColorized(true).setColor(Color.parseColor("#FAFAFA"))
            "dark" -> builder.setColorized(true).setColor(Color.parseColor("#282828"))
            else -> builder.setColor(Color.parseColor("#4285F4"))
        }

        return builder.build()
    }

    private fun monthlyLimitBytes(prefs: android.content.SharedPreferences): Long {
        val mb = prefs.getString("limit_data_warning", "0")?.trim()?.toLongOrNull() ?: 0L
        return if (mb <= 0L) 0L else mb * 1024L * 1024L
    }

    private fun isPersianUi(context: Context): Boolean =
        ConfigurationCompat.getLocales(context.resources.configuration)[0]?.language == "fa"

    private fun formatSpeed(context: Context, bytesPerSec: Long, bits: Boolean): String =
        if (isPersianUi(context)) PersianFormat.toPersian(
            FormatUtils.formatSpeedPersian(bytesPerSec, bits)
        )
        else FormatUtils.formatSpeed(if (bits) bytesPerSec * 8L else bytesPerSec)
            .let { if (bits) it.replace("B/s", "b/s") else it }

    private fun formatBytes(context: Context, bytes: Long): String =
        if (isPersianUi(context)) PersianFormat.bytes(bytes) else FormatUtils.formatBytes(bytes)

    /**
     * Status-bar icon: "28" over "KB/s". Both lines are condensed-bold and are
     * scaled to fill the 96px canvas, so the glyphs come out as large and as
     * heavy as the reference app's icon instead of thin and small.
     */
    private fun speedIcon(bytesPerSec: Long, bits: Boolean): Bitmap {
        val size = 96
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val speed = FormatUtils.formatSpeedForIcon(bytesPerSec, bits)

        val valuePaint = textPaint(66f)
        fitWidth(valuePaint, speed.value, size - 2f)

        val unitText = speed.unit + "/s"
        val unitPaint = textPaint(40f)
        fitWidth(unitPaint, unitText, size - 1f)

        canvas.drawText(speed.value, size / 2f, 58f, valuePaint)
        canvas.drawText(unitText, size / 2f, 96f, unitPaint)
        return bmp
    }

    private fun textPaint(size: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
        isFakeBoldText = true
        textSize = size
    }

    private fun fitWidth(paint: Paint, text: String, maxWidth: Float) {
        val width = paint.measureText(text)
        if (width > maxWidth && width > 0f) {
            paint.textSize = paint.textSize * maxWidth / width
        }
    }
}
