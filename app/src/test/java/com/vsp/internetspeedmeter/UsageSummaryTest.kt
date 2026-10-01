package com.vsp.internetspeedmeter

import com.vsp.internetspeedmeter.room.Usage
import com.vsp.internetspeedmeter.util.CalendarMath
import com.vsp.internetspeedmeter.util.DataPackage
import com.vsp.internetspeedmeter.util.UsageSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UsageSummaryTest {

    private fun day(dbDate: String) = CalendarMath.dayNumber(dbDate)!!

    @Test
    fun dbDateRoundTrip() {
        assertEquals("01-10-2026", CalendarMath.dbDate(day("01-10-2026")))
        assertEquals(day("01-03-2024") - 1, day("29-02-2024"))
        assertNull(CalendarMath.dayNumber("2026-10-01"))
        assertNull(CalendarMath.dayNumber("garbage"))
    }

    @Test
    fun monthsInBothCalendars() {
        val oct1 = day("01-10-2026")
        // 1 October 2026 = 9 Mehr 1405; Mehr has 30 days and starts on 23 September.
        val mehr = CalendarMath.monthIndex(true, oct1)
        assertEquals(1405 * 12 + 6, mehr)
        val mehrDays = CalendarMath.monthDays(true, mehr)
        assertEquals(day("23-09-2026"), mehrDays.first)
        assertEquals(30, mehrDays.count())

        val october = CalendarMath.monthIndex(false, oct1)
        assertEquals(day("01-10-2026")..day("31-10-2026"), CalendarMath.monthDays(false, october))
        assertEquals(29, CalendarMath.monthDays(false, 2024 * 12 + 1).count())
    }

    @Test
    fun sumsSkipMalformedRows() {
        val rows = listOf(
            Usage("30-09-2026", mobile = 100, wifi = 10),
            Usage("01-10-2026", mobile = 200, wifi = 20),
            Usage("bad", mobile = 999, wifi = 999)
        )
        val byDay = UsageSummary.byDay(rows)
        val totals = UsageSummary.sum(byDay, day("30-09-2026")..day("01-10-2026"))
        assertEquals(UsageSummary.Totals(300, 30), totals)
        assertEquals(330L, totals.total)
    }

    @Test
    fun packageStatus() {
        val start = day("25-09-2026")
        val pkg = DataPackage(volume = 1_000, startDay = start, days = 30)
        val byDay = UsageSummary.byDay(listOf(
            Usage("24-09-2026", mobile = 5_000),            // before the package
            Usage("25-09-2026", mobile = 300, wifi = 9_000), // Wi-Fi does not count
            Usage("01-10-2026", mobile = 200)
        ))

        val now = pkg.status(byDay, day("01-10-2026"))
        assertEquals(500L, now.used)
        assertEquals(500L, now.remaining)
        assertEquals(24, now.daysLeft)
        assertFalse(now.expired)

        val ended = pkg.status(byDay, start + 30)
        assertTrue(ended.expired)
        assertEquals(0, ended.daysLeft)

        val early = pkg.status(byDay, start - 1)
        assertEquals(0L, early.used)
        assertEquals(30, early.daysLeft)

        val over = DataPackage(volume = 100, startDay = start, days = 30).status(byDay, start)
        assertEquals(0L, over.remaining)
    }
}
