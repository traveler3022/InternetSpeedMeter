package com.vsp.internetspeedmeter.util

import kotlin.math.max

/**
 * Pure arithmetic for TrafficStats sampling.
 *
 * TrafficStats counters are cumulative and may reset (for example after reboot).
 * Keeping the arithmetic here makes counter-reset and timing behavior testable
 * without an Android Service instance.
 */
object TrafficMath {

    data class Sample(
        val deltaRx: Long,
        val deltaTx: Long,
        val downSpeed: Long,
        val upSpeed: Long,
        val countersReset: Boolean = false,
        val loopElapsedMs: Long = 0L
    ) {
        companion object {
            fun countersReset(): Sample =
                Sample(
                    deltaRx = 0L,
                    deltaTx = 0L,
                    downSpeed = 0L,
                    upSpeed = 0L,
                    countersReset = true,
                    loopElapsedMs = 0L
                )
        }
    }

    fun monotonicDelta(current: Long, previous: Long): Long =
        if (current >= previous && current >= 0L && previous >= 0L) {
            current - previous
        } else {
            0L
        }

    fun countersMonotonic(
        currentRx: Long,
        previousRx: Long,
        currentTx: Long,
        previousTx: Long
    ): Boolean =
        currentRx >= 0L &&
            currentTx >= 0L &&
            previousRx >= 0L &&
            previousTx >= 0L &&
            currentRx >= previousRx &&
            currentTx >= previousTx

    fun calculate(
        currentRx: Long,
        currentTx: Long,
        previousRx: Long,
        previousTx: Long,
        currentVpnRx: Long,
        currentVpnTx: Long,
        previousVpnRx: Long,
        previousVpnTx: Long,
        elapsedMs: Long
    ): Sample {
        val rawDeltaRx = monotonicDelta(currentRx, previousRx)
        val rawDeltaTx = monotonicDelta(currentTx, previousTx)

        val vpnDeltaRx = monotonicDelta(currentVpnRx, previousVpnRx)
        val vpnDeltaTx = monotonicDelta(currentVpnTx, previousVpnTx)

        val deltaRx = (rawDeltaRx - vpnDeltaRx).coerceAtLeast(0L)
        val deltaTx = (rawDeltaTx - vpnDeltaTx).coerceAtLeast(0L)

        return Sample(
            deltaRx = deltaRx,
            deltaTx = deltaTx,
            downSpeed = bytesPerSecond(deltaRx, elapsedMs),
            upSpeed = bytesPerSecond(deltaTx, elapsedMs)
        )
    }

    fun bytesPerSecond(deltaBytes: Long, elapsedMs: Long): Long {
        if (deltaBytes <= 0L) return 0L

        val safeElapsed = max(1L, elapsedMs)
        val result = deltaBytes.toDouble() * 1000.0 / safeElapsed.toDouble()

        return when {
            result.isNaN() || result <= 0.0 -> 0L
            result >= Long.MAX_VALUE.toDouble() -> Long.MAX_VALUE
            else -> result.toLong()
        }
    }

    fun safeAdd(a: Long, b: Long): Long {
        val left = a.coerceAtLeast(0L)
        val right = b.coerceAtLeast(0L)
        return if (left > Long.MAX_VALUE - right) {
            Long.MAX_VALUE
        } else {
            left + right
        }
    }

    fun safeMultiplyBy8(value: Long): Long {
        val safe = value.coerceAtLeast(0L)
        return if (safe > Long.MAX_VALUE / 8L) Long.MAX_VALUE else safe * 8L
    }
}
