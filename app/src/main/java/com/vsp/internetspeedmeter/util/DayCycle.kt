package com.vsp.internetspeedmeter.util

import android.content.Context
import androidx.preference.PreferenceManager
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * The accounting day used for the usage table. The "ساعت شروع روز" preference
 * shifts the rollover, so a day that starts at 04:00 keeps counting everything
 * before 04:00 on the previous day.
 */
object DayCycle {

    const val KEY_DAY_START_HOUR = "day_start_hour"

    private val dbDateFormat = ThreadLocal.withInitial {
        SimpleDateFormat("dd-MM-yyyy", Locale.US)
    }

    fun startHour(context: Context): Int {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        return prefs.getString(KEY_DAY_START_HOUR, "0")?.toIntOrNull()?.coerceIn(0, 23) ?: 0
    }

    /** Current accounting date in the "dd-MM-yyyy" form used as the Room primary key. */
    fun currentDate(context: Context): String {
        val cal = Calendar.getInstance()
        cal.add(Calendar.HOUR_OF_DAY, -startHour(context))
        return dbDateFormat.get().format(cal.time)
    }

    /** "20-09-2026" -> "09-2026", the month bucket used for the monthly limit. */
    fun monthOf(dbDate: String): String =
        if (dbDate.length >= 10) dbDate.substring(3) else dbDate
}
