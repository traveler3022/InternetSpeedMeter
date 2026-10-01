package com.vsp.internetspeedmeter.ui

import android.app.AppOpsManager
import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.net.ConnectivityManager
import android.os.Build
import android.os.Process
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.vsp.internetspeedmeter.R
import com.vsp.internetspeedmeter.util.DayCycle
import com.vsp.internetspeedmeter.util.Jalali
import java.util.Calendar

/** Per-app usage from NetworkStatsManager (needs Usage Access); display only. */
object AppNetworkStats {

    /** NetworkStats.UID_TETHERING / UID_REMOVED (hidden constants). */
    private const val UID_TETHERING = -5
    private const val UID_REMOVED = -4

    class AppInfo(val label: String, val icon: Drawable?)

    fun hasUsageAccess(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun openUsageAccessSettings(context: Context) {
        try {
            context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        } catch (_: Exception) {
        }
    }

    /** Start of accounting day [dayNumber], "ساعت شروع روز" applied. */
    fun dayStartMillis(context: Context, dayNumber: Int): Long {
        val g = Jalali.gregorianFromDayNumber(dayNumber)
        return Calendar.getInstance().apply {
            clear()
            set(g.year, g.month - 1, g.day, DayCycle.startHour(context), 0, 0)
        }.timeInMillis
    }

    private fun dayNumberAt(context: Context, millis: Long): Int {
        val cal = Calendar.getInstance()
        cal.timeInMillis = millis
        cal.add(Calendar.HOUR_OF_DAY, -DayCycle.startHour(context))
        return Jalali.gregorianDayNumber(
            cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH))
    }

    private fun networkType(wifi: Boolean) =
        if (wifi) ConnectivityManager.TYPE_WIFI else ConnectivityManager.TYPE_MOBILE

    /** Bytes per uid between [start] and [end]; null without access. Run off the main thread. */
    fun uidTotals(context: Context, wifi: Boolean, start: Long, end: Long): Map<Int, Long>? {
        return try {
            val nsm = context.getSystemService(Context.NETWORK_STATS_SERVICE) as NetworkStatsManager
            val stats = nsm.querySummary(networkType(wifi), null, start, end)
            val perUid = HashMap<Int, Long>()
            val bucket = NetworkStats.Bucket()
            try {
                while (stats.hasNextBucket()) {
                    stats.getNextBucket(bucket)
                    perUid[bucket.uid] = (perUid[bucket.uid] ?: 0L) + bucket.rxBytes + bucket.txBytes
                }
            } finally {
                stats.close()
            }
            perUid
        } catch (_: Exception) {
            null
        }
    }

    /** Bytes of [uid] per accounting day over [days]; null without access. Off the main thread. */
    fun uidPerDay(context: Context, uid: Int, wifi: Boolean, days: IntRange): Map<Int, Long>? {
        return try {
            val nsm = context.getSystemService(Context.NETWORK_STATS_SERVICE) as NetworkStatsManager
            val stats = nsm.queryDetailsForUid(
                networkType(wifi), null,
                dayStartMillis(context, days.first), dayStartMillis(context, days.last + 1), uid)
            val perDay = HashMap<Int, Long>()
            val bucket = NetworkStats.Bucket()
            try {
                while (stats.hasNextBucket()) {
                    stats.getNextBucket(bucket)
                    val day = dayNumberAt(context, bucket.startTimeStamp)
                    perDay[day] = (perDay[day] ?: 0L) + bucket.rxBytes + bucket.txBytes
                }
            } finally {
                stats.close()
            }
            perDay
        } catch (_: Exception) {
            null
        }
    }

    /** Name and icon of a uid; null for uids without a package (other than the special ones). */
    fun appInfo(context: Context, uid: Int): AppInfo? {
        when (uid) {
            UID_TETHERING -> return AppInfo(
                context.getString(R.string.apps_hotspot),
                ContextCompat.getDrawable(context, R.drawable.ic_hotspot_24))
            UID_REMOVED -> return AppInfo(context.getString(R.string.apps_removed), null)
            Process.SYSTEM_UID -> return AppInfo(
                context.getString(R.string.apps_system),
                ContextCompat.getDrawable(context, R.drawable.ic_settings_24))
        }
        val pm = context.packageManager
        val pkg = pm.getPackagesForUid(uid)?.firstOrNull() ?: return null
        val label = try {
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        } catch (_: Exception) {
            pkg
        }
        val icon = try {
            pm.getApplicationIcon(pkg)
        } catch (_: Exception) {
            null
        }
        return AppInfo(label, icon)
    }
}
