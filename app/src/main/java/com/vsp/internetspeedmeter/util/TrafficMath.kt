package com.vsp.internetspeedmeter.util

import kotlin.math.max
import kotlin.math.min

/**
 * Pure arithmetic for traffic-counter sampling.
 *
 * Counters are cumulative and may reset (for example after reboot).
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

    data class Counters(val rx: Long, val tx: Long)

    /**
     * Per-interface deltas against [baselines], which is updated in place.
     *
     * Summing all interfaces into one number breaks when the interface set
     * changes: a cellular link that appears next to Wi-Fi brings its whole
     * since-boot counter into the sum, which then looks like gigabytes of new
     * traffic. Each interface is therefore compared only with its own previous
     * value. A newly seen interface contributes nothing until the next sample,
     * and a counter that went backwards (interface re-created) is re-baselined.
     * Baselines of interfaces that are currently absent are kept, so traffic on
     * a link that comes back later is still counted.
     */
    fun interfaceDeltas(
        baselines: MutableMap<String, Counters>,
        current: Map<String, Counters>
    ): Map<String, Counters> {
        val deltas = HashMap<String, Counters>()
        for ((name, now) in current) {
            val before = baselines[name]
            baselines[name] = now
            if (before == null) continue
            val deltaRx = monotonicDelta(now.rx, before.rx)
            val deltaTx = monotonicDelta(now.tx, before.tx)
            if (deltaRx > 0L || deltaTx > 0L) deltas[name] = Counters(deltaRx, deltaTx)
        }
        return deltas
    }

    data class Split(val physical: Counters, val mobileBytes: Long, val wifiBytes: Long)

    /**
     * Splits one sample's deltas into traffic that crossed a real network.
     *
     * With a VPN every byte is counted on the tunnel interface and again,
     * encrypted, on Wi-Fi or mobile, so the device total holds it twice;
     * subtracting the tunnel delta leaves what went over the air. Mobile is
     * capped at that amount because the mobile counter sums only the current
     * cellular interfaces and can jump when that set changes.
     */
    fun splitTraffic(total: Counters, vpn: Counters, mobile: Counters): Split {
        val physical = Counters(
            rx = max(0L, total.rx - vpn.rx.coerceAtLeast(0L)),
            tx = max(0L, total.tx - vpn.tx.coerceAtLeast(0L))
        )
        val physicalBytes = safeAdd(physical.rx, physical.tx)
        val mobileBytes = min(safeAdd(mobile.rx, mobile.tx), physicalBytes)
        return Split(physical, mobileBytes, physicalBytes - mobileBytes)
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
        elapsedMs: Long
    ): Sample {
        val deltaRx = monotonicDelta(currentRx, previousRx)
        val deltaTx = monotonicDelta(currentTx, previousTx)

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
