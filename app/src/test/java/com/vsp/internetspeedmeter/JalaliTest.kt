package com.vsp.internetspeedmeter

import com.vsp.internetspeedmeter.util.Jalali
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.GregorianCalendar

class JalaliTest {

    @Test
    fun knownDates() {
        assertEquals(Jalali.Date(1405, 7, 9), Jalali.fromGregorian(2026, 10, 1))
        assertEquals(Jalali.Date(1403, 1, 1), Jalali.fromGregorian(2024, 3, 20))
        assertEquals(Jalali.Date(1403, 12, 30), Jalali.fromGregorian(2025, 3, 20))
        assertEquals(Jalali.Date(1404, 1, 1), Jalali.fromGregorian(2025, 3, 21))
        assertEquals(Jalali.Date(2026, 3, 21), Jalali.toGregorian(1405, 1, 1))
        assertEquals(Jalali.Date(1979, 2, 11), Jalali.toGregorian(1357, 11, 22))
    }

    @Test
    fun leapYearsAndMonthLengths() {
        assertTrue(Jalali.isLeap(1403))
        assertFalse(Jalali.isLeap(1404))
        assertEquals(30, Jalali.monthLength(1403, 12))
        assertEquals(29, Jalali.monthLength(1404, 12))
        assertEquals(31, Jalali.monthLength(1405, 6))
        assertEquals(30, Jalali.monthLength(1405, 7))
    }

    @Test
    fun everyDayRoundTripsAndFollowsThePreviousOne() {
        val cal = GregorianCalendar(1990, Calendar.JANUARY, 1)
        var previous = Jalali.fromGregorian(1989, 12, 31)
        repeat(365 * 60) {
            val y = cal.get(Calendar.YEAR)
            val m = cal.get(Calendar.MONTH) + 1
            val d = cal.get(Calendar.DAY_OF_MONTH)
            val j = Jalali.fromGregorian(y, m, d)
            assertEquals(Jalali.Date(y, m, d), Jalali.toGregorian(j.year, j.month, j.day))
            val expectedNext = if (previous.day < Jalali.monthLength(previous.year, previous.month)) {
                Jalali.Date(previous.year, previous.month, previous.day + 1)
            } else if (previous.month < 12) {
                Jalali.Date(previous.year, previous.month + 1, 1)
            } else {
                Jalali.Date(previous.year + 1, 1, 1)
            }
            assertEquals(expectedNext, j)
            previous = j
            cal.add(Calendar.DAY_OF_MONTH, 1)
        }
    }
}
