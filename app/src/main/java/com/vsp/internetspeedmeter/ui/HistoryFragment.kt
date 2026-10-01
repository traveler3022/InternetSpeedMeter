package com.vsp.internetspeedmeter.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.vsp.internetspeedmeter.R
import com.vsp.internetspeedmeter.databinding.FragmentHistoryBinding
import com.vsp.internetspeedmeter.databinding.ItemDayRowBinding
import com.vsp.internetspeedmeter.room.Usage
import com.vsp.internetspeedmeter.room.UsageViewModel
import com.vsp.internetspeedmeter.util.AppCalendar
import com.vsp.internetspeedmeter.util.UsageSummary

/** "تاریخچه": one month at a time, a daily chart and the past days, newest first. */
class HistoryFragment : Fragment(R.layout.fragment_history) {

    private var binding: FragmentHistoryBinding? = null
    private var rows: List<Usage> = emptyList()
    private var month = NO_MONTH
    private val adapter = DayAdapter { day -> openDay(day) }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val b = FragmentHistoryBinding.bind(view)
        binding = b
        month = savedInstanceState?.getInt(STATE_MONTH, NO_MONTH) ?: NO_MONTH
        b.listDays.layoutManager = LinearLayoutManager(requireContext())
        b.listDays.addItemDecoration(
            DividerItemDecoration(requireContext(), RecyclerView.VERTICAL).apply {
                setDrawable(requireContext().getDrawable(R.drawable.divider_subtle)!!)
            }
        )
        b.listDays.adapter = adapter
        b.btnPrev.setOnClickListener { month--; render() }
        b.btnNext.setOnClickListener { month++; render() }

        ViewModelProvider(requireActivity())[UsageViewModel::class.java].allNotes
            .observe(viewLifecycleOwner) {
                rows = it ?: emptyList()
                render()
            }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_MONTH, month)
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }

    private fun openDay(day: Int) {
        if (day > AppCalendar.today(requireContext())) return
        DetailSheet.forDay(day).show(childFragmentManager, "day")
    }

    private fun render() {
        val b = binding ?: return
        val ctx = requireContext()
        val today = AppCalendar.today(ctx)
        val current = AppCalendar.monthIndex(ctx, today)
        if (month == NO_MONTH || month > current) month = current

        val byDay = UsageSummary.byDay(rows)
        val days = AppCalendar.monthDays(ctx, month)
        b.tvMonth.text = AppCalendar.monthTitle(ctx, month)
        b.btnNext.isEnabled = month < current
        b.btnNext.alpha = if (month < current) 1f else 0.3f

        b.chartMonth.bars = days.map { d ->
            val t = byDay[d] ?: UsageSummary.Totals()
            val dom = AppCalendar.dayOfMonth(ctx, d)
            val n = d - days.first + 1
            val label = if (n == 1 || n % 5 == 0 || d == today) dom else ""
            BarChartView.Bar(label, t.mobile, t.wifi, highlight = d == today)
        }
        b.chartMonth.onBarClick = { i -> openDay(days.first + i) }

        val totals = UsageSummary.sum(byDay, days)
        b.tvHistMobile.text = Fmt.bytes(ctx, totals.mobile)
        b.tvHistWifi.text = Fmt.bytes(ctx, totals.wifi)
        b.tvHistTotal.text = Fmt.bytes(ctx, totals.total)

        val shown = days.filter { it <= today }.reversed()
            .map { it to (byDay[it] ?: UsageSummary.Totals()) }
        adapter.submit(shown, today)
        b.tvHistoryEmpty.visibility = if (totals.total == 0L) View.VISIBLE else View.GONE
    }

    private class DayAdapter(private val onClick: (Int) -> Unit) :
        RecyclerView.Adapter<DayAdapter.Holder>() {

        private var items: List<Pair<Int, UsageSummary.Totals>> = emptyList()
        private var today = 0

        fun submit(days: List<Pair<Int, UsageSummary.Totals>>, today: Int) {
            items = days
            this.today = today
            @Suppress("NotifyDataSetChanged")
            notifyDataSetChanged()
        }

        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(ItemDayRowBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val (day, t) = items[position]
            val b = holder.b
            val ctx = b.root.context
            b.tvDay.text = if (day == today) ctx.getString(R.string.row_today) else AppCalendar.dayLabel(ctx, day)
            b.tvDayMobile.text = Fmt.bytes(ctx, t.mobile)
            b.tvDayWifi.text = Fmt.bytes(ctx, t.wifi)
            b.tvDayTotal.text = Fmt.bytes(ctx, t.total)
            b.root.setOnClickListener { onClick(day) }
        }

        class Holder(val b: ItemDayRowBinding) : RecyclerView.ViewHolder(b.root)
    }

    private companion object {
        const val NO_MONTH = Int.MIN_VALUE
        const val STATE_MONTH = "month"
    }
}
