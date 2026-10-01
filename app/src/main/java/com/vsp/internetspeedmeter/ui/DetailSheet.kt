package com.vsp.internetspeedmeter.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.vsp.internetspeedmeter.R
import com.vsp.internetspeedmeter.databinding.SheetDetailBinding
import com.vsp.internetspeedmeter.room.UsageDatabase
import com.vsp.internetspeedmeter.util.AppCalendar
import com.vsp.internetspeedmeter.util.CalendarMath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Bottom sheet for a tapped day (its totals and top apps) or a tapped app
 * (its daily usage over the last 30 days).
 */
class DetailSheet : BottomSheetDialogFragment() {

    private var binding: SheetDetailBinding? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val b = SheetDetailBinding.inflate(inflater, container, false)
        binding = b
        b.sheetList.layoutManager = LinearLayoutManager(requireContext())
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val args = requireArguments()
        if (args.containsKey(ARG_DAY)) showDay(args.getInt(ARG_DAY))
        else showApp(args.getInt(ARG_UID), args.getString(ARG_LABEL).orEmpty(), args.getBoolean(ARG_WIFI))
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }

    private fun showDay(day: Int) {
        val b = binding ?: return
        val ctx = requireContext()
        b.tvSheetTitle.text = AppCalendar.dayLabel(ctx, day)
        val adapter = AppRowAdapter()
        b.sheetList.adapter = adapter

        viewLifecycleOwner.lifecycleScope.launch {
            val usage = withContext(Dispatchers.IO) {
                try {
                    UsageDatabase.getInstance(ctx).usageDao().getUsageByDate(CalendarMath.dbDate(day))
                } catch (_: Exception) {
                    null
                }
            }
            binding?.tvSheetSubtitle?.text = getString(
                R.string.day_detail_subtitle_fmt,
                Fmt.bytes(ctx, usage?.mobile ?: 0L), Fmt.bytes(ctx, usage?.wifi ?: 0L))

            if (!AppNetworkStats.hasUsageAccess(ctx)) {
                state(getString(R.string.apps_no_access))
                return@launch
            }
            val rows = withContext(Dispatchers.IO) {
                val start = AppNetworkStats.dayStartMillis(ctx, day)
                val end = AppNetworkStats.dayStartMillis(ctx, day + 1)
                val merged = HashMap<Int, Long>()
                for (wifi in listOf(false, true)) {
                    AppNetworkStats.uidTotals(ctx, wifi, start, end)?.forEach { (uid, bytes) ->
                        merged[uid] = (merged[uid] ?: 0L) + bytes
                    }
                }
                AppRowAdapter.rows(ctx, merged, limit = 10)
            }
            if (rows.isEmpty()) state(getString(R.string.apps_empty))
            else state(getString(R.string.day_detail_apps))
            adapter.submitList(rows)
        }
    }

    private fun showApp(uid: Int, label: String, wifi: Boolean) {
        val b = binding ?: return
        val ctx = requireContext()
        b.tvSheetTitle.text = label
        b.sheetChart.visibility = View.VISIBLE
        val today = AppCalendar.today(ctx)
        val days = today - 29..today

        viewLifecycleOwner.lifecycleScope.launch {
            val perDay = withContext(Dispatchers.IO) {
                AppNetworkStats.uidPerDay(ctx, uid, wifi, days)
            }
            val bb = binding ?: return@launch
            if (perDay == null) {
                state(getString(R.string.apps_no_access))
                return@launch
            }
            bb.sheetChart.bars = days.map { d ->
                val bytes = perDay[d] ?: 0L
                val n = d - days.first
                val label = if (n % 5 == 0 || d == today) AppCalendar.dayOfMonth(ctx, d) else ""
                if (wifi) BarChartView.Bar(label, 0L, bytes, d == today)
                else BarChartView.Bar(label, bytes, 0L, d == today)
            }
            val type = getString(if (wifi) R.string.column_wifi else R.string.column_mobile)
            bb.tvSheetSubtitle.text = getString(
                R.string.app_detail_subtitle_fmt, type, Fmt.bytes(ctx, perDay.values.sum()))
        }
    }

    private fun state(text: String) {
        val b = binding ?: return
        b.tvSheetState.text = text
        b.tvSheetState.visibility = View.VISIBLE
    }

    companion object {
        private const val ARG_DAY = "day"
        private const val ARG_UID = "uid"
        private const val ARG_LABEL = "label"
        private const val ARG_WIFI = "wifi"

        fun forDay(day: Int) = DetailSheet().apply {
            arguments = Bundle().apply { putInt(ARG_DAY, day) }
        }

        fun forApp(uid: Int, label: String, wifi: Boolean) = DetailSheet().apply {
            arguments = Bundle().apply {
                putInt(ARG_UID, uid)
                putString(ARG_LABEL, label)
                putBoolean(ARG_WIFI, wifi)
            }
        }
    }
}
