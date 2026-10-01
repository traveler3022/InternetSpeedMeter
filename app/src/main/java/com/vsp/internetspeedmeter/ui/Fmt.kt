package com.vsp.internetspeedmeter.ui

import android.content.Context
import com.vsp.internetspeedmeter.util.AppCalendar
import com.vsp.internetspeedmeter.util.FormatUtils
import com.vsp.internetspeedmeter.util.PersianFormat

/** Data volumes as the rest of the app shows them (Persian digits in Persian UI). */
object Fmt {
    fun bytes(context: Context, bytes: Long): String =
        if (AppCalendar.isPersianUi(context)) PersianFormat.bytes(bytes) else FormatUtils.formatBytes(bytes)
}
