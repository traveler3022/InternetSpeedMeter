package com.vsp.internetspeedmeter.util

/**
 * Solar Hijri (Jalali) <-> Gregorian conversion, the jalaali-js algorithm
 * (Borkowski's leap-year breaks), valid for Jalali years -61 .. 3177.
 */
object Jalali {

    /** A year/month/day in whichever calendar the function names. */
    data class Date(val year: Int, val month: Int, val day: Int)

    private val breaks = intArrayOf(
        -61, 9, 38, 199, 426, 686, 756, 818, 1111, 1181, 1210,
        1635, 2060, 2097, 2192, 2262, 2324, 2394, 2456, 3178
    )

    fun fromGregorian(year: Int, month: Int, day: Int): Date =
        fromDayNumber(gregorianToDayNumber(year, month, day))

    fun toGregorian(year: Int, month: Int, day: Int): Date =
        dayNumberToGregorian(toDayNumber(year, month, day))

    /** Consecutive day number (Julian day) of a Gregorian date. */
    fun gregorianDayNumber(year: Int, month: Int, day: Int): Int =
        gregorianToDayNumber(year, month, day)

    fun gregorianFromDayNumber(dayNumber: Int): Date = dayNumberToGregorian(dayNumber)

    fun isLeap(year: Int): Boolean = yearInfo(year).leap == 0

    fun monthLength(year: Int, month: Int): Int = when {
        month <= 6 -> 31
        month <= 11 -> 30
        isLeap(year) -> 30
        else -> 29
    }

    /** leap: years since the last leap year (0 = leap); march: March day of 1 Farvardin. */
    private class YearInfo(val leap: Int, val gregorianYear: Int, val march: Int)

    private fun yearInfo(jy: Int): YearInfo {
        require(jy >= breaks.first() && jy < breaks.last()) { "Jalali year out of range: $jy" }
        val gy = jy + 621
        var leapJ = -14
        var jp = breaks[0]
        var jump = 0
        for (i in 1 until breaks.size) {
            val jm = breaks[i]
            jump = jm - jp
            if (jy < jm) break
            leapJ += jump / 33 * 8 + jump % 33 / 4
            jp = jm
        }
        var n = jy - jp
        leapJ += n / 33 * 8 + (n % 33 + 3) / 4
        if (jump % 33 == 4 && jump - n == 4) leapJ += 1
        val leapG = gy / 4 - (gy / 100 + 1) * 3 / 4 - 150
        val march = 20 + leapJ - leapG
        if (jump - n < 6) n = n - jump + (jump + 4) / 33 * 33
        var leap = ((n + 1) % 33 - 1) % 4
        if (leap == -1) leap = 4
        return YearInfo(leap, gy, march)
    }

    private fun toDayNumber(jy: Int, jm: Int, jd: Int): Int {
        val info = yearInfo(jy)
        return gregorianToDayNumber(info.gregorianYear, 3, info.march) +
            (jm - 1) * 31 - jm / 7 * (jm - 7) + jd - 1
    }

    private fun fromDayNumber(dayNumber: Int): Date {
        val gy = dayNumberToGregorian(dayNumber).year
        var jy = gy - 621
        val info = yearInfo(jy)
        var k = dayNumber - gregorianToDayNumber(gy, 3, info.march)
        if (k >= 0) {
            if (k <= 185) return Date(jy, 1 + k / 31, k % 31 + 1)
            k -= 186
        } else {
            jy -= 1
            k += 179
            if (info.leap == 1) k += 1
        }
        return Date(jy, 7 + k / 30, k % 30 + 1)
    }

    private fun gregorianToDayNumber(gy: Int, gm: Int, gd: Int): Int {
        var d = (gy + (gm - 8) / 6 + 100100) * 1461 / 4 +
            (153 * ((gm + 9) % 12) + 2) / 5 + gd - 34840408
        d = d - (gy + 100100 + (gm - 8) / 6) / 100 * 3 / 4 + 752
        return d
    }

    private fun dayNumberToGregorian(dayNumber: Int): Date {
        var j = 4 * dayNumber + 139361631
        j += (4 * dayNumber + 183187720) / 146097 * 3 / 4 * 4 - 3908
        val i = j % 1461 / 4 * 5 + 308
        val gd = i % 153 / 5 + 1
        val gm = i / 153 % 12 + 1
        val gy = j / 1461 - 100100 + (8 - gm) / 6
        return Date(gy, gm, gd)
    }
}
