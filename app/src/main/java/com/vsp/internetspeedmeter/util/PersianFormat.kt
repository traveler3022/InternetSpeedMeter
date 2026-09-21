package com.vsp.internetspeedmeter.util

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** Persian digits / date labels for the usage table, matching the reference app. */
object PersianFormat {

    private val digits = charArrayOf('۰','۱','۲','۳','۴','۵','۶','۷','۸','۹')

    private val months = arrayOf(
        "ژانویه","فوریه","مارس","آوریل","مه","ژوئن",
        "ژوئیه","اوت","سپتامبر","اکتبر","نوامبر","دسامبر"
    )

    private val dbDateFormat = ThreadLocal.withInitial {
        SimpleDateFormat("dd-MM-yyyy", Locale.US)
    }

    /** 1234 -> ۱۲۳۴ ; "." decimal separator becomes the Persian thousands-style comma used by the app. */
    fun toPersian(input: String): String {
        val sb = StringBuilder(input.length)
        for (ch in input) {
            when {
                ch in '0'..'9' -> sb.append(digits[ch - '0'])
                ch == '.' -> sb.append('٬')
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    fun bytes(bytes: Long): String = toPersian(FormatUtils.formatBytesPersian(bytes))

    fun speed(bytesPerSec: Long): String = toPersian(FormatUtils.formatSpeedPersian(bytesPerSec))

    /** "20-09-2026" -> "۲۰ سپتامبر ۲۰۲۶" ; today -> "امروز". */
    fun dateLabel(dbDate: String, todayDbDate: String): String {
        if (dbDate == todayDbDate) return "امروز"
        return try {
            val cal = Calendar.getInstance()
            cal.time = dbDateFormat.get().parse(dbDate) ?: return toPersian(dbDate)
            val d = cal.get(Calendar.DAY_OF_MONTH)
            val m = months[cal.get(Calendar.MONTH)]
            val y = cal.get(Calendar.YEAR)
            toPersian("$d") + " " + m + " " + toPersian("$y")
        } catch (_: Exception) {
            toPersian(dbDate)
        }
    }

    fun today(): String = dbDateFormat.get().format(Calendar.getInstance().time)
}
