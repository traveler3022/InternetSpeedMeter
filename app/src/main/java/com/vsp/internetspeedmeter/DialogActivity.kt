package com.vsp.internetspeedmeter

import android.app.AppOpsManager
import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.vsp.internetspeedmeter.broadcastreceiver.InternetService
import com.vsp.internetspeedmeter.recyclerview.AppUsageAdapter
import com.vsp.internetspeedmeter.recyclerview.AppUsageItem
import com.vsp.internetspeedmeter.util.FormatUtils
import kotlinx.coroutines.*

class DialogActivity : AppCompatActivity() {

    private lateinit var tvSessionTime: TextView
    private lateinit var tvSessionUsage: TextView
    private lateinit var graphView: GraphView
    
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
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        setContentView(R.layout.dialog_graph)

        window.setLayout(
            (resources.displayMetrics.widthPixels * 0.9).toInt(),
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        )

        tvSessionTime = findViewById(R.id.tv_session_time)
        tvSessionUsage = findViewById(R.id.tv_session_usage)
        graphView = findViewById(R.id.graph_view)
    }

    override fun onResume() {
        super.onResume()
        handler.post(updateRunnable)
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
}
