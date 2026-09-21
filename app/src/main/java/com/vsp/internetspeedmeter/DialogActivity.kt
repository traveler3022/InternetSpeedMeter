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
import kotlinx.coroutines.*
import java.util.Calendar

/**
 * Two views in one dialog:
 *  GRAPH (default) -> live speed graph + [Mobile | Wifi]
 *  APPS            -> per-app usage list (scrolls inside a fixed box) + [Mobile | Wifi] under it
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

    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            updateSession()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
        appsMode = true
        showWifi = wifi
        graphContainer.visibility = View.GONE
        appsContainer.visibility = View.VISIBLE
        paintToggle(btnAppsMobile, !wifi)
        paintToggle(btnAppsWifi, wifi)
        loadAppUsage()
    }

    private fun paintToggle(tv: TextView, active: Boolean) {
        tv.setBackgroundColor(if (active) ACCENT else Color.WHITE)
        tv.setTextColor(if (active) Color.WHITE else IDLE_TEXT)
    }

    override fun onResume() { super.onResume(); handler.post(tick) }
    override fun onPause() { super.onPause(); handler.removeCallbacks(tick) }
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
        val items = withContext(Dispatchers.IO) { queryAppUsage(showWifi) }
        appAdapter.submitList(items)
    }

    /** Today's per-app bytes on the chosen transport. Needs Usage Access. */
    private fun queryAppUsage(wifi: Boolean): List<AppUsageItem> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return emptyList()
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

            val pm = packageManager
            perUid.entries.filter { it.value > 0L }.mapNotNull { (uid, bytes) ->
                val pkg = pm.getPackagesForUid(uid)?.firstOrNull() ?: return@mapNotNull null
                val label = try {
                    pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
                } catch (_: Exception) { pkg }
                val icon = try { pm.getApplicationIcon(pkg) } catch (_: Exception) { null }
                AppUsageItem(uid, pkg, label, icon, bytes)
            }.sortedByDescending { it.bytesUsed }.take(50)
        } catch (_: Exception) { emptyList() }
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
        val ACCENT: Int = Color.parseColor("#4A90D9")
        val IDLE_TEXT: Int = Color.parseColor("#5A5A5A")
    }
}
