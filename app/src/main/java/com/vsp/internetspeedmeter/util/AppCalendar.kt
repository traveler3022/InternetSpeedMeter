package com.vsp.internetspeedmeter.util

import android.content.Context
import androidx.core.os.ConfigurationCompat
import androidx.preference.PreferenceManager
import java.text.DateFormatSymbols
import java.util.Locale

/**
 * The "تقویم" preference: Gregorian (default) or Solar Hijri. The database
 * keys stay Gregorian "dd-MM-yyyy"; only labels and month grouping follow it.
 * Dates are passed around as day numbers (see [CalendarMath]).
 */
object AppCalendar {

    const val KEY = "calendar_type"
    private const val JALALI = "jalali"

    private val jalaliMonthsFa = arrayOf(
        "فروردین", "اردیبهشت", "خرداد", "تیر", "مرداد", "شهریور",
        "مهر", "آبان", "آذر", "دی", "بهمن", "اسفند"
    )
    private val jalaliMonthsEn = arrayOf(
        "Farvardin", "Ordibehesht", "Khordad", "Tir", "Mordad", "Shahrivar",
        "Mehr", "Aban", "Azar", "Dey", "Bahman", "Esfand"
    )
    private val gregorianMonthsFa = arrayOf(
        "ژانویه", "فوریه", "مارس", "آوریل", "مه", "ژوئن",
        "ژوئیه", "اوت", "سپتامبر", "اکتبر", "نوامبر", "دسامبر"
    )

    fun isJalali(context: Context): Boolean =
        PreferenceManager.getDefaultSharedPreferences(context).getString(KEY, "gregorian") == JALALI

    fun isPersianUi(context: Context): Boolean =
        ConfigurationCompat.getLocales(context.resources.configuration)[0]?.language == "fa"

    /** The current accounting day ("ساعت شروع روز" applied) as a day number. */
    fun today(context: Context): Int =
        CalendarMath.dayNumber(DayCycle.currentDate(context)) ?: 0

    fun monthIndex(context: Context, dayNumber: Int): Int =
        CalendarMath.monthIndex(isJalali(context), dayNumber)

    fun monthDays(context: Context, index: Int): IntRange =
        CalendarMath.monthDays(isJalali(context), index)

    /** "۹ مهر ۱۴۰۵", "۱ اکتبر ۲۰۲۶", "9 Mehr 1405" or "1 Oct 2026". */
    fun dayLabel(context: Context, dayNumber: Int): String {
        val p = CalendarMath.parts(isJalali(context), dayNumber)
        return if (isPersianUi(context)) {
            "${digits(context, p.day)} ${monthName(context, p.month)} ${digits(context, p.year)}"
        } else {
            "${p.day} ${monthName(context, p.month, short = true)} ${p.year}"
        }
    }

    /** Day of the month, for chart labels. */
    fun dayOfMonth(context: Context, dayNumber: Int): String =
        digits(context, CalendarMath.parts(isJalali(context), dayNumber).day)

    /** "مهر ۱۴۰۵", "October 2026" ... */
    fun monthTitle(context: Context, index: Int): String =
        monthName(context, CalendarMath.monthOf(index)) + " " +
            digits(context, CalendarMath.yearOf(index))

    fun monthName(context: Context, month: Int, short: Boolean = false): String {
        val i = (month - 1).coerceIn(0, 11)
        return when {
            isJalali(context) -> if (isPersianUi(context)) jalaliMonthsFa[i] else jalaliMonthsEn[i]
            isPersianUi(context) -> gregorianMonthsFa[i]
            short -> DateFormatSymbols(Locale.US).shortMonths[i]
            else -> DateFormatSymbols(Locale.US).months[i]
        }
    }

    fun digits(context: Context, n: Int): String =
        if (isPersianUi(context)) PersianFormat.toPersian("$n") else "$n"
}
