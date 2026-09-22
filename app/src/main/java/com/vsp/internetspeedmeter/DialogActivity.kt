package com.vsp.internetspeedmeter

import android.app.AppOpsManager
import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.ConnectivityManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.provider.Settings
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.vsp.internetspeedmeter.broadcastreceiver.InternetService
import com.vsp.internetspeedmeter.recyclerview.AppUsageAdapter
import com.vsp.internetspeedmeter.recyclerview.AppUsageItem
import com.vsp.internetspeedmeter.util.DayCycle
import com.vsp.internetspeedmeter.util.FormatUtils
import com.vsp.internetspeedmeter.util.Palette
import kotlinx.coroutines.*
import java.util.Calendar

/**
 * Two views in one dialog:
 *  GRAPH (default) -> live speed graph + [Mobile | Wifi]
 *  APPS            -> per-app usage list (scrolls inside a fixed box) + [Mobile | Wifi] under it;
 *                     apps that used the network since the last refresh get an arrow
 * Mobile/Wifi enters APPS and swaps the dataset; it never resizes the dialog.
 */
class DialogActivity : AppCompatActivity() {

    private lateinit var tvSessionTime: TextView
    private lateinit var tvSessionUsage: TextView
    private lateinit var graphContainer: View
    private lateinit var graphView: GraphView
    private lateinit var appsContainer: View
    private lateinit var appsRecycler: RecyclerView
    private lateinit var btnGraphMobile: TextView
    private lateinit var btnGraphWifi: TextView
    private lateinit var btnAppsMobile: TextView
    private lateinit var btnAppsWifi: TextView

    private val appAdapter = AppUsageAdapter()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var appsMode = false
    private var showWifi = false

    // Per-uid totals of the previous app-list refresh; the growth since then
    // marks the apps that are using the network right now.
    private var previousTotals: Map<Int, Long>? = null

    private val handler = Handler(Looper.getMainLooper())

    /**
     * Android refreshes per-app stats for a normal app at most once every 15 s,
     * and an earlier request restarts that 15 s wait
     * (NetworkStatsService.POLL_RATE_LIMIT_MS), so the list refreshes slower than that.
     */
    private val appsTick = object : Runnable {
        override fun run() {
            loadAppUsage()
            handler.postDelayed(this, APPS_REFRESH_MS)
        }
    }
    private val tick = object : Runnable {
        override fun run() {
            updateSession()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Palette.apply(this, paintWindow = false)
        supportRequestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        window.setBackgroundDrawable(
            android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        setContentView(R.layout.dialog_graph)
        window.setLayout(
            (resources.displayMetrics.widthPixels * 0.9).toInt(),
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT)

        tvSessionTime = findViewById(R.id.tv_session_time)
        tvSessionUsage = findViewById(R.id.tv_session_usage)
        graphContainer = findViewById(R.id.graph_container)
        graphView = findViewById(R.id.graph_view)
        appsContainer = findViewById(R.id.apps_container)
        appsRecycler = findViewById(R.id.recycler_view_apps)
        btnGraphMobile = findViewById(R.id.btn_graph_mobile)
        btnGraphWifi = findViewById(R.id.btn_graph_wifi)
        btnAppsMobile = findViewById(R.id.btn_apps_mobile)
        btnAppsWifi = findViewById(R.id.btn_apps_wifi)

        appsRecycler.layoutManager = LinearLayoutManager(this)
        appsRecycler.adapter = appAdapter

        btnGraphMobile.setOnClickListener { enterApps(false) }
        btnGraphWifi.setOnClickListener { enterApps(true) }
        btnAppsMobile.setOnClickListener { enterApps(false) }
        btnAppsWifi.setOnClickListener { enterApps(true) }

        showGraph()
    }

    private fun showGraph() {
        appsMode = false
        graphContainer.visibility = View.VISIBLE
        appsContainer.visibility = View.GONE
        paintToggle(btnGraphMobile, false)
        paintToggle(btnGraphWifi, false)
    }

    private fun enterApps(wifi: Boolean) {
        if (wifi != showWifi) previousTotals = null
        appsMode = true
        showWifi = wifi
        graphContainer.visibility = View.GONE
        appsContainer.visibility = View.VISIBLE
        paintToggle(btnAppsMobile, !wifi)
        paintToggle(btnAppsWifi, wifi)
        handler.removeCallbacks(appsTick)
        handler.post(appsTick)
    }

    private fun paintToggle(tv: TextView, active: Boolean) {
        tv.setBackgroundColor(Palette.color(this, if (active) R.attr.ismHeader else R.attr.ismCard))
        tv.setTextColor(if (active) Color.WHITE else Palette.color(this, R.attr.ismMuted))
    }

    override fun onResume() {
        super.onResume()
        handler.post(tick)
        if (appsMode) handler.post(appsTick)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(tick)
        handler.removeCallbacks(appsTick)
    }
    override fun onDestroy() { super.onDestroy(); scope.cancel() }

    private fun updateSession() {
        val service = InternetService.instance ?: return
        val (timeMs, bytes) = service.getSessionInfo()
        val s = (timeMs / 1000) % 60
        val m = (timeMs / 60000) % 60
        val h = timeMs / 3600000
        tvSessionTime.text = String.format("%02d:%02d:%02d", h, m, s)
        tvSessionUsage.text = FormatUtils.formatBytes(bytes)
        if (!appsMode) graphView.setData(service.speedHistory, service.speedHistoryIndex)
    }

    private fun loadAppUsage() = scope.launch {
        if (!hasUsageAccess()) {
            requestUsageAccessOnce()
            appAdapter.submitList(emptyList())
            return@launch
        }
        val wifi = showWifi
        val totals = withContext(Dispatchers.IO) { queryUidTotals(wifi) } ?: return@launch
        if (wifi != showWifi) return@launch
        val previous = previousTotals
        previousTotals = totals
        val items = withContext(Dispatchers.IO) { toItems(totals, previous) }
        appAdapter.submitList(items)
    }

    /** Today's bytes per uid on the chosen transport. Needs Usage Access. */
    private fun queryUidTotals(wifi: Boolean): Map<Int, Long>? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return null
        return try {
            val nsm = getSystemService(Context.NETWORK_STATS_SERVICE) as NetworkStatsManager
            val startHour = DayCycle.startHour(this)
            val cal = Calendar.getInstance().apply {
                if (get(Calendar.HOUR_OF_DAY) < startHour) {
                    add(Calendar.DAY_OF_YEAR, -1)
                }
                set(Calendar.HOUR_OF_DAY, startHour)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            val type = if (wifi) ConnectivityManager.TYPE_WIFI else ConnectivityManager.TYPE_MOBILE
            val stats = nsm.querySummary(type, null, cal.timeInMillis, System.currentTimeMillis())
            val perUid = HashMap<Int, Long>()
            val bucket = NetworkStats.Bucket()
            while (stats.hasNextBucket()) {
                stats.getNextBucket(bucket)
                perUid[bucket.uid] = (perUid[bucket.uid] ?: 0L) + bucket.rxBytes + bucket.txBytes
            }
            stats.close()
            perUid
        } catch (_: Exception) { null }
    }

    /** Apps using the network right now first, then by today's usage. */
    private fun toItems(totals: Map<Int, Long>, previous: Map<Int, Long>?): List<AppUsageItem> {
        val pm = packageManager
        return totals.entries.filter { it.value > 0L }.mapNotNull { (uid, bytes) ->
            val pkg = pm.getPackagesForUid(uid)?.firstOrNull() ?: return@mapNotNull null
            val label = try {
                pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
            } catch (_: Exception) { pkg }
            val icon = try { pm.getApplicationIcon(pkg) } catch (_: Exception) { null }
            val recent = previous?.let { (bytes - (it[uid] ?: 0L)).coerceAtLeast(0L) } ?: 0L
            AppUsageItem(uid, pkg, label, icon, bytes, recent)
        }.sortedWith(
            compareByDescending<AppUsageItem> { it.recentBytes }.thenByDescending { it.bytesUsed }
        ).take(50)
    }

    private fun hasUsageAccess(): Boolean {
        val appOps = getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName)
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName)
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /** Send the user to the usage-access screen once; the rest of the dialog keeps working. */
    private fun requestUsageAccessOnce() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        if (prefs.getBoolean(PREF_ASKED_USAGE_ACCESS, false)) return
        prefs.edit().putBoolean(PREF_ASKED_USAGE_ACCESS, true).apply()
        Toast.makeText(this, R.string.apps_need_permission, Toast.LENGTH_LONG).show()
        try {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        } catch (_: Exception) {
        }
    }

    private companion object {
        const val PREF_ASKED_USAGE_ACCESS = "asked_usage_access"
        const val APPS_REFRESH_MS = 16_000L
    }
}
