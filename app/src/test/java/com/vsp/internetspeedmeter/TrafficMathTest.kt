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

}
