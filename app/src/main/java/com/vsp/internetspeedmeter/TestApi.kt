package com.vsp.internetspeedmeter
import android.net.TrafficStats
class TestApi {
    fun test() {
        TrafficStats.getRxBytes("wlan0")
    }
}
