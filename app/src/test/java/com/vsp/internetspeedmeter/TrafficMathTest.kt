package com.vsp.internetspeedmeter

import com.vsp.internetspeedmeter.util.TrafficMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrafficMathTest {

    @Test
    fun calculate_usesActualElapsedTime() {
        val sample = TrafficMath.calculate(
            currentRx = 2_000L,
            currentTx = 1_000L,
            previousRx = 1_000L,
            previousTx = 500L,
            elapsedMs = 2_000L
        )

        assertEquals(1_000L, sample.deltaRx)
        assertEquals(500L, sample.deltaTx)
        assertEquals(500L, sample.downSpeed)
        assertEquals(250L, sample.upSpeed)
    }

    @Test
    fun calculate_doesNotSubtractVpnTraffic() {
        val sample = TrafficMath.calculate(
            currentRx = 1_100L,
            currentTx = 2_000L,
            previousRx = 1_000L,
            previousTx = 1_000L,
            elapsedMs = 1_000L
        )

        assertEquals(100L, sample.deltaRx)
        assertEquals(1_000L, sample.deltaTx)
    }

    @Test
    fun countersMonotonic_detectsRebootOrCounterReset() {
        assertTrue(TrafficMath.countersMonotonic(200L, 100L, 300L, 200L))
        assertFalse(TrafficMath.countersMonotonic(50L, 100L, 300L, 200L))
        assertFalse(TrafficMath.countersMonotonic(200L, 100L, 150L, 200L))
    }

    @Test
    fun safeArithmetic_preventsOverflow() {
        assertEquals(Long.MAX_VALUE, TrafficMath.safeAdd(Long.MAX_VALUE, 1L))
        assertEquals(Long.MAX_VALUE, TrafficMath.safeMultiplyBy8(Long.MAX_VALUE))
        assertEquals(Long.MAX_VALUE, TrafficMath.bytesPerSecond(Long.MAX_VALUE, 1L))
    }

    @Test
    fun interfaceDeltas_newInterfaceDoesNotCountItsSinceBootCounter() {
        val baselines = hashMapOf("wlan0" to TrafficMath.Counters(1_000_000_000L, 0L))
        val deltas = TrafficMath.interfaceDeltas(
            baselines,
            mapOf(
                "wlan0" to TrafficMath.Counters(1_000_010_000L, 0L),
                "rmnet_data0" to TrafficMath.Counters(5_000_000_000L, 0L)
            )
        )

        assertEquals(mapOf("wlan0" to TrafficMath.Counters(10_000L, 0L)), deltas)
    }

    @Test
    fun interfaceDeltas_keepsBaselineOfInterfaceThatComesBack() {
        val baselines = hashMapOf("rmnet_data0" to TrafficMath.Counters(100L, 50L))
        TrafficMath.interfaceDeltas(baselines, mapOf("wlan0" to TrafficMath.Counters(10L, 10L)))
        val deltas = TrafficMath.interfaceDeltas(
            baselines,
            mapOf("rmnet_data0" to TrafficMath.Counters(300L, 80L))
        )

        assertEquals(TrafficMath.Counters(200L, 30L), deltas["rmnet_data0"])
    }

    @Test
    fun interfaceDeltas_rebaselinesCounterThatWentBackwards() {
        val baselines = hashMapOf("wlan0" to TrafficMath.Counters(1_000L, 1_000L))
        val deltas = TrafficMath.interfaceDeltas(
            baselines,
            mapOf("wlan0" to TrafficMath.Counters(10L, 10L))
        )

        assertTrue(deltas.isEmpty())
        assertEquals(TrafficMath.Counters(10L, 10L), baselines["wlan0"])
    }

    @Test
    fun splitTraffic_removesVpnDoubleCount() {
        // 1 MB downloaded through a VPN over Wi-Fi: tunnel 1 MB + Wi-Fi 1.05 MB
        val split = TrafficMath.splitTraffic(
            total = TrafficMath.Counters(2_050_000L, 100_000L),
            vpn = TrafficMath.Counters(1_000_000L, 40_000L),
            mobile = TrafficMath.Counters(0L, 0L)
        )

        assertEquals(TrafficMath.Counters(1_050_000L, 60_000L), split.physical)
        assertEquals(0L, split.mobileBytes)
        assertEquals(1_110_000L, split.wifiBytes)
    }

    @Test
    fun splitTraffic_capsMobileJumpAtPhysicalTraffic() {
        // A new cellular interface brings its since-boot counter into the sum
        val split = TrafficMath.splitTraffic(
            total = TrafficMath.Counters(5_000L, 1_000L),
            vpn = TrafficMath.Counters(0L, 0L),
            mobile = TrafficMath.Counters(900_000_000L, 1_000L)
        )

        assertEquals(6_000L, split.mobileBytes)
        assertEquals(0L, split.wifiBytes)
    }

    @Test
    fun splitTraffic_neverNegative() {
        val split = TrafficMath.splitTraffic(
            total = TrafficMath.Counters(100L, 0L),
            vpn = TrafficMath.Counters(500L, 10L),
            mobile = TrafficMath.Counters(0L, 0L)
        )

        assertEquals(TrafficMath.Counters(0L, 0L), split.physical)
        assertEquals(0L, split.wifiBytes)
    }
}
