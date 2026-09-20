package com.vsp.internetspeedmeter

import android.app.AppOpsManager
import android.app.usage.NetworkStats
import android.app.AppOpsManager
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
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.vsp.internetspeedmeter.broadcastreceiver.InternetService
import com.vsp.internetspeedmeter.recyclerview.AppUsageAdapter
import com.vsp.internetspeedmeter.recyclerview.AppUsageItem
import com.vsp.internetspeedmeter.util.FormatUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

/**
 * Session dialog: session time / session usage header, live speed graph and
 * the per-app usage list with the Mobile | Wifi switch.
 */
class DialogActivity : AppCompatActivity() {

    private lateinit var tvSessionTime: TextView
    private lateinit var tvSessionUsage: TextView
    private lateinit var graphView: GraphView
    private lateinit var appsRecycler: RecyclerView
    private lateinit var btnMobile: TextView
    private lateinit var btnWifi: TextView

    private val appAdapter = AppUsageAdapter()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var showWifi = true

    private val handler = Handler(Looper.getMainLooper())
    private val updateRunnable = object : Runnable {
        override fun run() {
            updateData()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportRequestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        window.setBackgroundDrawable(
            android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        setContentView(R.layout.dialog_graph)

        window.setLayout(
            (resources.displayMetrics.widthPixels * 0.9).toInt(),
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        )

        tvSessionTime = findViewById(R.id.tv_session_time)
        tvSessionUsage = findViewById(R.id.tv_session_usage)
        graphView = findViewById(R.id.graph_view)
        appsRecycler = findViewById(R.id.recycler_view_apps)
        btnMobile = findViewById(R.id.btn_mobile)
        btnWifi = findViewById(R.id.btn_wifi)

        appsRecycler.layoutManager = LinearLayoutManager(this)
        appsRecycler.adapter = appAdapter

        btnMobile.setOnClickListener { selectNetwork(false) }
        btnWifi.setOnClickListener { selectNetwork(true) }
        selectNetwork(true)
    }

    private fun selectNetwork(wifi: Boolean) {
        showWifi = wifi
        val accent = Color.parseColor("#4A90D9")
        val idle = Color.WHITE
        val idleText = Color.parseColor("#5A5A5A")
        btnWifi.setBackgroundColor(if (wifi) accent else idle)
        btnWifi.setTextColor(if (wifi) Color.WHITE else idleText)
        btnMobile.setBackgroundColor(if (wifi) idle else accent)
        btnMobile.setTextColor(if (wifi) idleText else Color.WHITE)
        loadAppUsage()
    }

    override fun onResume() {
        super.onResume()
        handler.post(updateRunnable)
        loadAppUsage()
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(updateRunnable)
    }

    private fun updateData() {
        val service = InternetService.instance ?: return

        val sessionInfo = service.getSessionInfo()
        val timeMs = sessionInfo.first
        val bytes = sessionInfo.second

        val seconds = (timeMs / 1000) % 60
        val minutes = (timeMs / (1000 * 60)) % 60
        val hours = (timeMs / (1000 * 60 * 60))

        tvSessionTime.text = String.format("%02d:%02d:%02d", hours, minutes, seconds)
        tvSessionUsage.text = FormatUtils.formatBytes(bytes)

        graphView.setData(service.speedHistory, service.speedHistoryIndex)
    }

    /** Per-app bytes for today on the selected transport (needs usage-access permission). */
    private fun loadAppUsage() = scope.launch {
        if (!hasUsageAccess()) {
            requestUsageAccessOnce()
            appAdapter.submitList(emptyList())
            return@launch
        }
        val items = withContext(Dispatchers.IO) { queryAppUsage(showWifi) }
        appAdapter.submitList(items)
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
        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
        if (prefs.getBoolean(PREF_ASKED_USAGE_ACCESS, false)) return
        prefs.edit().putBoolean(PREF_ASKED_USAGE_ACCESS, true).apply()
        Toast.makeText(this, R.string.usage_access_needed, Toast.LENGTH_LONG).show()
        try {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        } catch (_: Exception) {
        }
    }

    private fun queryAppUsage(wifi: Boolean): List<AppUsageItem> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return emptyList()
        return try {
            val nsm = getSystemService(Context.NETWORK_STATS_SERVICE) as NetworkStatsManager
            val cal = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }
            val start = cal.timeInMillis
            val end = System.currentTimeMillis()
            val type = if (wifi) ConnectivityManager.TYPE_WIFI else ConnectivityManager.TYPE_MOBILE

            val stats: NetworkStats = nsm.querySummary(type, null, start, end)
            val perUid = HashMap<Int, Long>()
            val bucket = NetworkStats.Bucket()
            while (stats.hasNextBucket()) {
                stats.getNextBucket(bucket)
                perUid[bucket.uid] = (perUid[bucket.uid] ?: 0L) + bucket.rxBytes + bucket.txBytes
            }
            stats.close()

            val pm = packageManager
            perUid.entries
                .filter { it.value > 0L }
                .mapNotNull { (uid, bytes) ->
                    val pkgs = pm.getPackagesForUid(uid) ?: return@mapNotNull null
                    val pkg = pkgs.firstOrNull() ?: return@mapNotNull null
                    val label = try {
                        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
                    } catch (_: Exception) { pkg }
                    val icon = try { pm.getApplicationIcon(pkg) } catch (_: Exception) { null }
                    AppUsageItem(uid, pkg, label, icon, bytes)
                }
                .sortedByDescending { it.bytesUsed }
                .take(30)
        } catch (_: Exception) {
            emptyList()
        }
    }

    companion object {
        private const val PREF_ASKED_USAGE_ACCESS = "asked_usage_access"
    }
}
