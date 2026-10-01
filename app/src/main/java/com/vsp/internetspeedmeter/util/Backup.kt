package com.vsp.internetspeedmeter.util

import android.content.Context
import android.net.Uri
import com.vsp.internetspeedmeter.room.Usage
import com.vsp.internetspeedmeter.room.UsageDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** "پشتیبان‌گیری" / "بازگردانی": the daily usage table as a JSON file. */
object Backup {

    /** Writes every day with usage to [uri]; returns the number of days. */
    suspend fun export(context: Context, uri: Uri): Int = withContext(Dispatchers.IO) {
        val rows = UsageDatabase.getInstance(context).usageDao().getAllOnce()
            .filter { it.mobile > 0L || it.wifi > 0L }
            .sortedBy { CalendarMath.dayNumber(it.date) ?: 0 }
        val days = JSONArray()
        for (row in rows) {
            days.put(JSONObject().put("date", row.date).put("mobile", row.mobile).put("wifi", row.wifi))
        }
        val json = JSONObject().put("app", "internet-meter").put("version", 1).put("days", days)
        val out = context.contentResolver.openOutputStream(uri, "wt")
            ?: throw IllegalStateException("cannot open $uri")
        out.use { it.write(json.toString(2).toByteArray(Charsets.UTF_8)) }
        rows.size
    }

    /**
     * Merges a backup into the table, keeping the larger value of each day, so
     * restoring twice or over newer data never lowers or doubles anything.
     * Today is skipped: the running service owns today's row.
     */
    suspend fun import(context: Context, uri: Uri): Int = withContext(Dispatchers.IO) {
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("cannot open $uri")
        val text = input.use { it.readBytes().toString(Charsets.UTF_8) }
        val days = JSONObject(text).getJSONArray("days")

        val dao = UsageDatabase.getInstance(context).usageDao()
        val existing = dao.getAllOnce().associateBy { it.date }
        val today = DayCycle.currentDate(context)
        val merged = ArrayList<Usage>()
        for (i in 0 until days.length()) {
            val day = days.getJSONObject(i)
            val date = day.optString("date")
            if (CalendarMath.dayNumber(date) == null || date == today) continue
            val old = existing[date]
            val mobile = maxOf(day.optLong("mobile").coerceAtLeast(0L), old?.mobile ?: 0L)
            val wifi = maxOf(day.optLong("wifi").coerceAtLeast(0L), old?.wifi ?: 0L)
            merged += Usage(date, mobile, wifi, TrafficMath.safeAdd(mobile, wifi))
        }
        dao.upsertAll(merged)
        merged.size
    }
}
