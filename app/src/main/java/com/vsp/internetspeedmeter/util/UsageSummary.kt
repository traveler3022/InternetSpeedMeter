package com.vsp.internetspeedmeter.util

import com.vsp.internetspeedmeter.room.Usage

/** Sums over the daily usage rows, keyed by day number (see [CalendarMath]). */
object UsageSummary {

    data class Totals(val mobile: Long = 0L, val wifi: Long = 0L) {
        val total: Long get() = TrafficMath.safeAdd(mobile, wifi)

        operator fun plus(other: Totals) = Totals(
            TrafficMath.safeAdd(mobile, other.mobile),
            TrafficMath.safeAdd(wifi, other.wifi)
        )
    }

    /** Day number -> totals; rows with a malformed date are skipped. */
    fun byDay(rows: List<Usage>): Map<Int, Totals> {
        val map = HashMap<Int, Totals>()
        for (row in rows) {
            val day = CalendarMath.dayNumber(row.date) ?: continue
            map[day] = (map[day] ?: Totals()) + Totals(row.mobile, row.wifi)
        }
        return map
    }

    fun sum(byDay: Map<Int, Totals>, days: IntRange): Totals {
        var totals = Totals()
        for (day in days) byDay[day]?.let { totals += it }
        return totals
    }
}

/** A mobile data package: [volume] bytes for [days] days from day number [startDay]. */
data class DataPackage(val volume: Long, val startDay: Int, val days: Int) {

    val endDay: Int get() = startDay + days - 1

    data class Status(val used: Long, val remaining: Long, val daysLeft: Int, val expired: Boolean)

    /** Mobile usage of the package's days up to [today]. */
    fun status(byDay: Map<Int, UsageSummary.Totals>, today: Int): Status {
        val used = if (today < startDay) 0L
        else UsageSummary.sum(byDay, startDay..minOf(today, endDay)).mobile
        val expired = today > endDay
        val daysLeft = when {
            expired -> 0
            today < startDay -> days
            else -> endDay - today + 1
        }
        return Status(used, (volume - used).coerceAtLeast(0L), daysLeft, expired)
    }
}
