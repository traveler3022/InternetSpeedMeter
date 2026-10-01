package com.vsp.internetspeedmeter.util

import android.content.Context
import androidx.preference.PreferenceManager

/** The "بستهٔ اینترنت" set on the home screen, kept in the default preferences. */
object PackageStore {

    private const val KEY_VOLUME = "package_volume_bytes"
    private const val KEY_START = "package_start"
    private const val KEY_DAYS = "package_days"

    fun load(context: Context): DataPackage? {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val volume = prefs.getLong(KEY_VOLUME, 0L)
        val start = prefs.getString(KEY_START, null)?.let { CalendarMath.dayNumber(it) }
        val days = prefs.getInt(KEY_DAYS, 0)
        return if (volume > 0L && start != null && days > 0) DataPackage(volume, start, days) else null
    }

    fun save(context: Context, pkg: DataPackage) {
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putLong(KEY_VOLUME, pkg.volume)
            .putString(KEY_START, CalendarMath.dbDate(pkg.startDay))
            .putInt(KEY_DAYS, pkg.days)
            .apply()
    }

    fun clear(context: Context) {
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .remove(KEY_VOLUME).remove(KEY_START).remove(KEY_DAYS)
            .apply()
    }

    /** Typed numbers may come in Persian or Arabic digits and separators. */
    fun parseNumber(text: CharSequence?): Double? {
        val normalized = buildString {
            for (ch in text?.toString()?.trim().orEmpty()) {
                append(
                    when (ch) {
                        in '۰'..'۹' -> '0' + (ch - '۰')
                        in '٠'..'٩' -> '0' + (ch - '٠')
                        '٫', '٬', ',' -> '.'
                        else -> ch
                    }
                )
            }
        }
        return normalized.toDoubleOrNull()?.takeIf { it.isFinite() }
    }
}
