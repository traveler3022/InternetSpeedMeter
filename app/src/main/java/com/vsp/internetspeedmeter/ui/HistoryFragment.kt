package com.vsp.internetspeedmeter.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.vsp.internetspeedmeter.R
import com.vsp.internetspeedmeter.databinding.FragmentHistoryBinding
import com.vsp.internetspeedmeter.databinding.ItemDayRowBinding
import com.vsp.internetspeedmeter.room.Usage
import com.vsp.internetspeedmeter.room.UsageViewModel
import com.vsp.internetspeedmeter.util.AppCalendar
import com.vsp.internetspeedmeter.util.Palette
import com.vsp.internetspeedmeter.util.UsageSummary

/** Design 2 history: period filters, usage chart, summary and daily breakdown. */
class HistoryFragment : Fragment(R.layout.fragment_history) {

    private var binding: FragmentHistoryBinding? = null
    private var rows: List<Usage> = emptyList()
    private var periodDays = 30
    private val adapter = DayAdapter { day -> openDay(day) }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val b = FragmentHistoryBinding.bind(view)
        binding = b
        periodDays = savedInstanceState?.getInt(STATE_PERIOD, 30) ?: 30

        b.listDays.layoutManager = LinearLayoutManager(requireContext())
        b.listDays.adapter = adapter
        b.chipsPeriod.setOnCheckedStateChangeListener { _, ids ->
            periodDays = when (ids.firstOrNull()) {
                R.id.chip_7_days -> 7
                R.id.chip_90_days -> 90
                else -> 30
            }
            render()
        }
        when (periodDays) {
            7 -> b.chipsPeriod.check(R.id.chip_7_days)
            90 -> b.chipsPeriod.check(R.id.chip_90_days)
            else -> b.chipsPeriod.check(R.id.chip_30_days)
        }

        ViewModelProvider(requireActivity())[UsageViewModel::class.java].allNotes
            .observe(viewLifecycleOwner) {
                rows = it ?: emptyList()
                render()
            }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_PERIOD, periodDays)
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
        val byDay = UsageSummary.byDay(rows)
        val first = today - periodDays + 1
        val days = first..today

        b.tvChartTitle.text = getString(
            when (periodDays) {
                7 -> R.string.history_last_7
                90 -> R.string.history_last_90
                else -> R.string.history_last_30
            }
        )

        b.chartPeriod.bars = days.mapIndexed { index, d ->
            val t = byDay[d] ?: UsageSummary.Totals()
            val label = when {
                periodDays <= 7 -> AppCalendar.dayOfMonth(ctx, d)
                index == 0 || index == days.count() - 1 || index % 5 == 0 ->
                    AppCalendar.dayOfMonth(ctx, d)
                else -> ""
            }
            BarChartView.Bar(
                label.toString(),
                t.mobile,
                t.wifi,
                highlight = d == today
            )
        }
        b.chartPeriod.onBarClick = { index -> openDay(first + index) }

        val totals = UsageSummary.sum(byDay, days)
        b.tvHistMobile.text = Fmt.bytes(ctx, totals.mobile)
        b.tvHistWifi.text = Fmt.bytes(ctx, totals.wifi)
        b.tvHistTotal.text = Fmt.bytes(ctx, totals.total)

        val shown = days.reversed().map { it to (byDay[it] ?: UsageSummary.Totals()) }
        adapter.submit(shown, today)
        b.tvHistoryEmpty.visibility =
            if (totals.total == 0L) View.VISIBLE else View.GONE
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
            b.tvDay.text =
                if (day == today) ctx.getString(R.string.row_today)
                else AppCalendar.dayLabel(ctx, day)
            b.tvDayMobile.text = Fmt.bytes(ctx, t.mobile)
            b.tvDayWifi.text = Fmt.bytes(ctx, t.wifi)
            b.tvDayTotal.text = Fmt.bytes(ctx, t.total)
            b.root.setBackgroundColor(
                Palette.color(
                    ctx,
                    if (position % 2 == 0) R.attr.ismRowLighter else R.attr.ismRowLight
                )
            )
            b.root.setOnClickListener { onClick(day) }
        }

        class Holder(val b: ItemDayRowBinding) : RecyclerView.ViewHolder(b.root)
    }

    private companion object {
        const val STATE_PERIOD = "history_period"
    }
}
