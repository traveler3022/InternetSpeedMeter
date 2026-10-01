package com.vsp.internetspeedmeter.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import com.vsp.internetspeedmeter.R
import com.vsp.internetspeedmeter.databinding.FragmentAppsBinding
import com.vsp.internetspeedmeter.util.AppCalendar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "برنامه‌ها": per-app usage for today, 7 days or this month, on mobile or
 * Wi-Fi, searchable. An arrow marks apps that used data since the previous
 * refresh; Android refreshes these stats at most every 15 s for an app, so
 * the list refreshes every 16 s.
 */
class AppsFragment : Fragment(R.layout.fragment_apps) {

    private var binding: FragmentAppsBinding? = null
    private val adapter = AppRowAdapter { row ->
        DetailSheet.forApp(row.uid, row.label, wifi).show(childFragmentManager, "app")
    }
    private var period = R.id.chip_today
    private var wifi = false
    private var rows: List<AppRow> = emptyList()
    private var previous: Map<Int, Long>? = null

    private val handler = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() {
            load()
            handler.postDelayed(this, REFRESH_MS)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val b = FragmentAppsBinding.bind(view)
        binding = b
        b.listApps.layoutManager = LinearLayoutManager(requireContext())
        b.listApps.addItemDecoration(
            DividerItemDecoration(requireContext(), androidx.recyclerview.widget.RecyclerView.VERTICAL).apply {
                setDrawable(requireContext().getDrawable(R.drawable.divider_subtle)!!)
            }
        )
        b.listApps.adapter = adapter

        b.chipsPeriod.setOnCheckedStateChangeListener { _, ids ->
            period = ids.firstOrNull() ?: R.id.chip_today
            restart()
        }
        b.toggleType.addOnButtonCheckedListener { _, id, checked ->
            if (!checked) return@addOnButtonCheckedListener
            wifi = id == R.id.btn_type_wifi
            restart()
        }
        b.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) = show()
        })
        b.btnGrantAccess.setOnClickListener { AppNetworkStats.openUsageAccessSettings(requireContext()) }
    }

    override fun onResume() {
        super.onResume()
        handler.post(refresh)
    }

    override fun onPause() {
        handler.removeCallbacks(refresh)
        super.onPause()
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }

    private fun restart() {
        previous = null
        rows = emptyList()
        show()
        handler.removeCallbacks(refresh)
        handler.post(refresh)
    }

    private fun load() {
        val ctx = context ?: return
        val b = binding ?: return
        if (!AppNetworkStats.hasUsageAccess(ctx)) {
            b.tvAppsState.setText(R.string.apps_no_access)
            b.tvAppsState.visibility = View.VISIBLE
            b.btnGrantAccess.visibility = View.VISIBLE
            adapter.submitList(emptyList())
            return
        }
        b.btnGrantAccess.visibility = View.GONE

        val today = AppCalendar.today(ctx)
        val firstDay = when (period) {
            R.id.chip_week -> today - 6
            R.id.chip_month -> AppCalendar.monthDays(ctx, AppCalendar.monthIndex(ctx, today)).first
            else -> today
        }
        val wantWifi = wifi
        val wantPeriod = period
        viewLifecycleOwner.lifecycleScope.launch {
            val start = AppNetworkStats.dayStartMillis(ctx, firstDay)
            val totals = withContext(Dispatchers.IO) {
                AppNetworkStats.uidTotals(ctx, wantWifi, start, System.currentTimeMillis())
            } ?: return@launch
            if (wantWifi != wifi || wantPeriod != period) return@launch
            val before = previous
            previous = totals
            rows = withContext(Dispatchers.IO) { AppRowAdapter.rows(ctx, totals, before) }
            show()
        }
    }

    private fun show() {
        val b = binding ?: return
        val query = b.etSearch.text?.toString()?.trim().orEmpty()
        val shown = if (query.isEmpty()) rows else rows.filter { it.label.contains(query, ignoreCase = true) }
        adapter.submitList(shown)
        val hasAccess = AppNetworkStats.hasUsageAccess(requireContext())
        if (hasAccess) {
            b.tvAppsState.setText(R.string.apps_empty)
            b.tvAppsState.visibility = if (shown.isEmpty() && previous != null) View.VISIBLE else View.GONE
        }
    }

    private companion object {
        const val REFRESH_MS = 16_000L
    }
}
