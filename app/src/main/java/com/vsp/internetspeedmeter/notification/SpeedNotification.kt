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
import android.graphics.PorterDuff
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
import kotlin.math.abs

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

    /** Guards the shared icon bitmap and [lastKey]; build and post happen under it. */
    private val lock = Any()
    private var lastKey: String? = null
    private val pendingIntents = HashMap<String, PendingIntent?>()

    /**
     * Builds the notification and hands it to [poster] (notify/startForeground).
     * As in the reference app nothing is rebuilt from scratch per tick: the icon
     * bitmap, its paints and the click PendingIntent are reused, and when
     * [force] is false an update identical to the last posted one is skipped,
     * which saves the notify IPC and the SystemUI redraw while the speed is flat.
     *
     * @param idle true while "تنها زمانی که به اینترنت متصل هستم" is on and there
     *   is no connection, which moves the notification to the silent channel.
     * @return true when the notification was posted.
     */
    fun post(
        context: Context,
        poster: (Notification) -> Unit,
        downBytesPerSec: Long,
        upBytesPerSec: Long,
        mobileTodayBytes: Long,
        wifiTodayBytes: Long,
        monthlyMobileBytes: Long = 0L,
        idle: Boolean = false,
        force: Boolean = true
    ): Boolean = synchronized(lock) {
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

        val colorMode = prefs.getString("notification_color", "system")
        val iconSpeed = FormatUtils.formatSpeedForIcon(totalSpeed, useBits)
        val key = "$title\n$text\n${iconSpeed.value}${iconSpeed.unit}\n$idle" +
            "\n$hideOnLockscreen\n$clickAction\n$colorMode"
        if (!force && key == lastKey) return@synchronized false

        val pending = contentIntent(context, clickAction ?: "dialog")

        val builder = NotificationCompat.Builder(
            context, if (idle) CHANNEL_ID_IDLE else CHANNEL_ID
        )
            .setSmallIcon(IconCompat.createWithBitmap(speedIcon(context, iconSpeed)))
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
        when (colorMode) {
            "light" -> builder.setColorized(true).setColor(Color.parseColor("#FAFAFA"))
            "dark" -> builder.setColorized(true).setColor(Color.parseColor("#282828"))
            else -> builder.setColor(Color.parseColor("#4285F4"))
        }

        poster(builder.build())
        lastKey = key
        true
    }

    /** One PendingIntent per click action, created once instead of on every tick. */
    private fun contentIntent(context: Context, clickAction: String): PendingIntent? =
        pendingIntents.getOrPut(clickAction) {
            val target = when (clickAction) {
                "app" -> Intent(context, MainActivity::class.java)
                "none" -> null
                else -> Intent(context, DialogActivity::class.java)
            }
            target?.let {
                it.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                PendingIntent.getActivity(
                    context.applicationContext, 0, it,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            }
        }

    private fun monthlyLimitBytes(prefs: android.content.SharedPreferences): Long {
        val mb = prefs.getString("limit_data_warning", "0")?.trim()?.toLongOrNull() ?: 0L
        return if (mb <= 0L) 0L else mb * 1024L * 1024L
    }

    private fun isPersianUi(context: Context): Boolean =
        ConfigurationCompat.getLocales(context.resources.configuration)[0]?.language == "fa"

    /** The speed number stays in Latin digits ("28 ک ب/ث"); only the unit is Persian. */
    private fun formatSpeed(context: Context, bytesPerSec: Long, bits: Boolean): String =
        if (isPersianUi(context)) FormatUtils.formatSpeedPersian(bytesPerSec, bits)
        else FormatUtils.formatSpeed(if (bits) bytesPerSec * 8L else bytesPerSec)
            .let { if (bits) it.replace("B/s", "b/s") else it }

    private fun formatBytes(context: Context, bytes: Long): String =
        if (isPersianUi(context)) PersianFormat.bytes(bytes) else FormatUtils.formatBytes(bytes)

    /** Icon bitmap, canvas, paints and baselines; built once per canvas size. */
    private class IconCanvas(val size: Int) {
        // The status bar only uses the alpha channel of a small icon.
        val bitmap: Bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ALPHA_8)
        val canvas = Canvas(bitmap)
        val valuePaint: Paint
        val unitPaint: Paint
        val valueBaseline: Float
        val unitBaseline: Float

        init {
            val valueHeight = size * 0.65f
            val unitHeight = size - valueHeight
            valuePaint = textPaint(valueHeight * 1.2f)
            val valueGlyph = abs(valuePaint.ascent() + valuePaint.descent())
            valueBaseline = abs((valueHeight - valueGlyph) / 3f) + valueGlyph
            unitPaint = textPaint(unitHeight * 1.2f)
            val unitGlyph = abs(unitPaint.ascent() + unitPaint.descent())
            unitBaseline = size - abs((unitHeight - unitGlyph) / 4f)
        }
    }

    private var iconCanvas: IconCanvas? = null

    /**
     * Status-bar icon: "28" over "KB/s", with the reference app's exact metrics:
     * canvas 24/36/48/72/96 px by density, number at 0.78 x height (scaleX 0.9,
     * 0.75 for three characters), unit at 0.42 x height, both DEFAULT_BOLD.
     * The same bitmap is redrawn every tick; the caller holds [lock] until the
     * notification is posted, which is when the bitmap is copied.
     */
    private fun speedIcon(context: Context, speed: FormatUtils.SpeedUnit): Bitmap {
        val size = when (context.resources.displayMetrics.densityDpi) {
            120, 160 -> 24
            240 -> 36
            320 -> 48
            640 -> 96
            else -> 72
        }
        val ic = iconCanvas?.takeIf { it.size == size } ?: IconCanvas(size).also { iconCanvas = it }
        ic.canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)

        ic.valuePaint.textScaleX = if (speed.value.length == 3) 0.75f else 0.9f
        ic.unitPaint.textScaleX = if (speed.unit.startsWith("K")) 1.05f else 1.0f

        ic.canvas.drawText(speed.value, size / 2f, ic.valueBaseline, ic.valuePaint)
        ic.canvas.drawText(speed.unit + "/s", size / 2f, ic.unitBaseline, ic.unitPaint)
        return ic.bitmap
    }

    private fun textPaint(size: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
        textSize = size
    }
}
