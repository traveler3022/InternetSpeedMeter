package com.vsp.internetspeedmeter
import android.net.TrafficStats
class TestApi {
    fun test() {
        @android.annotation.SuppressLint("NewApi")
        val bytes = TrafficStats.getRxBytes("wlan0")
    }
}
