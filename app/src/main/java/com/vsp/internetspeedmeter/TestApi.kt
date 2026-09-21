package com.vsp.internetspeedmeter
import android.net.TrafficStats
class TestApi {
    @android.annotation.SuppressLint("NewApi")
    fun test() {
        TrafficStats.getRxBytes("wlan0")
    }
}
