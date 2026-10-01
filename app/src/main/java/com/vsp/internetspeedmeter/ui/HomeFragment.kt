package com.vsp.internetspeedmeter.ui

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import com.vsp.internetspeedmeter.R
import com.vsp.internetspeedmeter.databinding.FragmentHomeBinding
import com.vsp.internetspeedmeter.room.Usage
import com.vsp.internetspeedmeter.room.UsageViewModel
import com.vsp.internetspeedmeter.util.AppCalendar
import com.vsp.internetspeedmeter.util.PackageStore
import com.vsp.internetspeedmeter.util.UsageSummary
import com.vsp.internetspeedmeter.widget.UsageWidget

/** "خانه": today, the data package, this month and the last 7 days. */
class HomeFragment : Fragment(R.layout.fragment_home) {

    private var binding: FragmentHomeBinding? = null
    private var rows: List<Usage> = emptyList()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val b = FragmentHomeBinding.bind(view)
        binding = b
        b.btnPackageSet.setOnClickListener { editPackage() }
        b.tvPackageEdit.setOnClickListener { editPackage() }

        ViewModelProvider(requireActivity())[UsageViewModel::class.java].allNotes
            .observe(viewLifecycleOwner) {
                rows = it ?: emptyList()
                render()
            }
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }

    private fun editPackage() {
        PackageDialog.show(requireContext()) {
            render()
            context?.let { UsageWidget.refresh(it) }
        }
    }

    private fun render() {
        val b = binding ?: return
        val ctx = context ?: return
        val byDay = UsageSummary.byDay(rows)
        val today = AppCalendar.today(ctx)

        val day = byDay[today] ?: UsageSummary.Totals()
        b.tvTodayMobile.text = Fmt.bytes(ctx, day.mobile)
        b.tvTodayWifi.text = Fmt.bytes(ctx, day.wifi)
        b.tvTodayTotal.text = getString(R.string.home_total_fmt, Fmt.bytes(ctx, day.total))

        val pkg = PackageStore.load(ctx)
        b.packageContent.visibility = if (pkg == null) View.GONE else View.VISIBLE
        b.tvPackageEdit.visibility = if (pkg == null) View.GONE else View.VISIBLE
        b.btnPackageSet.visibility = if (pkg == null) View.VISIBLE else View.GONE
        if (pkg != null) {
            val status = pkg.status(byDay, today)
            val permille = (status.used.toDouble() / pkg.volume * 1000).toInt().coerceIn(0, 1000)
            b.progressPackage.setProgressCompat(permille, false)
            b.tvPackageUsed.text = getString(
                R.string.package_used_fmt, Fmt.bytes(ctx, status.used), Fmt.bytes(ctx, pkg.volume))
            b.tvPackageLeft.text = when {
                status.expired -> getString(R.string.package_expired)
                today < pkg.startDay -> getString(
                    R.string.package_not_started_fmt, AppCalendar.dayLabel(ctx, pkg.startDay))
                else -> getString(
                    R.string.package_left_fmt, Fmt.bytes(ctx, status.remaining),
                    AppCalendar.digits(ctx, status.daysLeft))
            }
        }

        val month = AppCalendar.monthIndex(ctx, today)
        val monthTotals = UsageSummary.sum(byDay, AppCalendar.monthDays(ctx, month))
        b.tvMonthTitle.text = getString(R.string.home_month_fmt, AppCalendar.monthTitle(ctx, month))
        b.tvMonthMobile.text = Fmt.bytes(ctx, monthTotals.mobile)
        b.tvMonthWifi.text = Fmt.bytes(ctx, monthTotals.wifi)
        b.tvMonthTotal.text = Fmt.bytes(ctx, monthTotals.total)

        b.chartWeek.bars = (today - 6..today).map { d ->
            val t = byDay[d] ?: UsageSummary.Totals()
            BarChartView.Bar(AppCalendar.dayOfMonth(ctx, d), t.mobile, t.wifi, highlight = d == today)
        }
    }
}
