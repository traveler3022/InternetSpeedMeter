package com.vsp.internetspeedmeter.util

import java.util.Calendar
import java.util.GregorianCalendar
import java.util.Locale

/**
 * Calendar arithmetic on day numbers (consecutive integers, see
 * [Jalali.gregorianDayNumber]); no Android dependency, so it is unit tested.
 * A month index is year * 12 + month - 1 in the chosen calendar.
 */
object CalendarMath {

    /** "dd-MM-yyyy" (the database key) -> Gregorian date, or null. */
    fun parseDbDate(dbDate: String): Jalali.Date? {
        val p = dbDate.split("-")
        if (p.size != 3) return null
        val d = p[0].toIntOrNull() ?: return null
        val m = p[1].toIntOrNull() ?: return null
        val y = p[2].toIntOrNull() ?: return null
        if (m !in 1..12 || d !in 1..31) return null
        return Jalali.Date(y, m, d)
    }

    fun dayNumber(dbDate: String): Int? =
        parseDbDate(dbDate)?.let { Jalali.gregorianDayNumber(it.year, it.month, it.day) }

    fun dbDate(dayNumber: Int): String {
        val g = Jalali.gregorianFromDayNumber(dayNumber)
        return String.format(Locale.US, "%02d-%02d-%04d", g.day, g.month, g.year)
    }

    /** [dayNumber] as year/month/day of the Solar Hijri or Gregorian calendar. */
    fun parts(jalali: Boolean, dayNumber: Int): Jalali.Date {
        val g = Jalali.gregorianFromDayNumber(dayNumber)
        return if (jalali) Jalali.fromGregorian(g.year, g.month, g.day) else g
    }

    fun monthIndex(jalali: Boolean, dayNumber: Int): Int =
        parts(jalali, dayNumber).let { it.year * 12 + it.month - 1 }

    fun yearOf(index: Int): Int = Math.floorDiv(index, 12)

    fun monthOf(index: Int): Int = Math.floorMod(index, 12) + 1

    /** Day numbers of the month [index]. */
    fun monthDays(jalali: Boolean, index: Int): IntRange {
        val year = yearOf(index)
        val month = monthOf(index)
        val first: Int
        val length: Int
        if (jalali) {
            val g = Jalali.toGregorian(year, month, 1)
            first = Jalali.gregorianDayNumber(g.year, g.month, g.day)
            length = Jalali.monthLength(year, month)
        } else {
            first = Jalali.gregorianDayNumber(year, month, 1)
            length = GregorianCalendar(year, month - 1, 1).getActualMaximum(Calendar.DAY_OF_MONTH)
        }
        return first until first + length
    }
}
