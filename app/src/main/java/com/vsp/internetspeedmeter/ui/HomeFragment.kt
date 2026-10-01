package com.vsp.internetspeedmeter.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.preference.PreferenceManager
import com.vsp.internetspeedmeter.R
import com.vsp.internetspeedmeter.broadcastreceiver.InternetService
import com.vsp.internetspeedmeter.databinding.FragmentHomeBinding
import com.vsp.internetspeedmeter.room.Usage
import com.vsp.internetspeedmeter.room.UsageViewModel
import com.vsp.internetspeedmeter.util.AppCalendar
import com.vsp.internetspeedmeter.util.FormatUtils
import com.vsp.internetspeedmeter.util.PackageStore
import com.vsp.internetspeedmeter.util.UsageSummary
import com.vsp.internetspeedmeter.widget.UsageWidget

/** Home: live connection, today, package status and the last seven days. */
class HomeFragment : Fragment(R.layout.fragment_home) {

    private var binding: FragmentHomeBinding? = null
    private var rows: List<Usage> = emptyList()

    private val liveHandler = Handler(Looper.getMainLooper())
    private val liveTicker = object : Runnable {
        override fun run() {
            renderLive()
            liveHandler.postDelayed(this, 1000L)
        }
    }

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

    override fun onResume() {
        super.onResume()
        liveHandler.removeCallbacks(liveTicker)
        liveHandler.post(liveTicker)
    }

    override fun onPause() {
        liveHandler.removeCallbacks(liveTicker)
        super.onPause()
    }

    override fun onDestroyView() {
        liveHandler.removeCallbacks(liveTicker)
        binding = null
        super.onDestroyView()
    }

    private fun editPackage() {
        PackageDialog.show(requireContext()) {
            render()
            context?.let { UsageWidget.refresh(it) }
        }
    }

    private fun renderLive() {
        val b = binding ?: return
        val ctx = context ?: return
        val info = InternetService.instance?.getLiveInfo()

        if (info == null) {
            b.tvConnectionState.setText(R.string.connection_disconnected)
            b.tvConnectionState.setTextColor(requireContext().getColor(R.color.error))
            b.tvNetworkType.setText(R.string.network_unknown)
            b.tvDownload.text = FormatUtils.formatSpeed(0L, false)
            b.tvUpload.text = FormatUtils.formatSpeed(0L, false)
            return
        }

        b.tvConnectionState.setText(
            if (info.connected) R.string.connection_connected else R.string.connection_disconnected
        )
        b.tvConnectionState.setTextColor(
            ctx.getColor(if (info.connected) R.color.success else R.color.error)
        )

        val networkLabel = when (info.networkType) {
            1 -> R.string.network_mobile
            2 -> R.string.network_wifi
            else -> R.string.network_unknown
        }
        b.tvNetworkType.setText(networkLabel)

        val bits = PreferenceManager.getDefaultSharedPreferences(ctx)
            .getString("speed_unit", "byte") == "bit"
        val format = { value: Long ->
            if (AppCalendar.isPersianUi(ctx)) {
                FormatUtils.formatSpeedPersian(value, bits)
            } else {
                FormatUtils.formatSpeed(value, bits)
            }
        }
        b.tvDownload.text = format(info.downloadBytesPerSec)
        b.tvUpload.text = format(info.uploadBytesPerSec)
    }

    private fun render() {
        val b = binding ?: return
        val ctx = context ?: return
        val byDay = UsageSummary.byDay(rows)
        val today = AppCalendar.today(ctx)

        val day = byDay[today] ?: UsageSummary.Totals()
        b.tvTodayMobile.text = Fmt.bytes(ctx, day.mobile)
        b.tvTodayWifi.text = Fmt.bytes(ctx, day.wifi)
        b.tvTodayTotal.text = Fmt.bytes(ctx, day.total)

        val pkg = PackageStore.load(ctx)
        b.packageContent.visibility = if (pkg == null) View.GONE else View.VISIBLE
        b.tvPackageEdit.visibility = if (pkg == null) View.GONE else View.VISIBLE
        b.btnPackageSet.visibility = if (pkg == null) View.VISIBLE else View.GONE
        if (pkg != null) {
            val status = pkg.status(byDay, today)
            val permille = (status.used.toDouble() / pkg.volume * 1000).toInt().coerceIn(0, 1000)
            b.progressPackage.setProgressCompat(permille, false)
            b.tvPackageUsed.text = getString(
                R.string.package_used_fmt, Fmt.bytes(ctx, status.used), Fmt.bytes(ctx, pkg.volume)
            )
            b.tvPackageLeft.text = when {
                status.expired -> getString(R.string.package_expired)
                today < pkg.startDay -> getString(
                    R.string.package_not_started_fmt, AppCalendar.dayLabel(ctx, pkg.startDay)
                )
                else -> getString(
                    R.string.package_left_fmt, Fmt.bytes(ctx, status.remaining),
                    AppCalendar.digits(ctx, status.daysLeft)
                )
            }
        }

        b.chartWeek.bars = (today - 6..today).map { d ->
            val t = byDay[d] ?: UsageSummary.Totals()
            BarChartView.Bar(
                AppCalendar.dayOfMonth(ctx, d),
                t.mobile,
                t.wifi,
                highlight = d == today
            )
        }
    }
}
