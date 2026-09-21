package com.vsp.internetspeedmeter.util

import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

object FormatUtils {

    private val decimalSymbols = DecimalFormatSymbols(Locale.US)
    private val decimalFormat = ThreadLocal.withInitial {
        DecimalFormat("#.0", decimalSymbols)
    }

    private fun decimal(value: Double): String = decimalFormat.get().format(value)

    data class SpeedUnit(val value: String, val unit: String)

    /**
     * Formats network throughput speed (decimal units: 1000 B = 1 KB).
     */
    fun formatSpeed(bytesPerSec: Long): String {
        val safeBytes = if (bytesPerSec < 0L) 0L else bytesPerSec
        return when {
            safeBytes >= 1_000_000_000L -> "${decimal(safeBytes.toDouble() / 1_000_000_000L)} GB/s"
            safeBytes >= 1_000_000L -> "${decimal(safeBytes.toDouble() / 1_000_000L)} MB/s"
            safeBytes >= 1_000L -> "${safeBytes / 1000L} KB/s"
            else -> "$safeBytes B/s"
        }
    }

    /**
     * Formats speed for the compact status bar icon (max 3 digits + 2-letter unit).
     */
    fun formatSpeedForIcon(bytesPerSec: Long, bits: Boolean = false): SpeedUnit {
        val safeBytes = if (bytesPerSec < 0L) 0L else bytesPerSec
        if (bits) {
            val b = TrafficMath.safeMultiplyBy8(safeBytes)
            return when {
                b >= 1_000_000_000L -> SpeedUnit(decimal(b.toDouble() / 1_000_000_000L), "Gb")
                b >= 1_000_000L -> SpeedUnit(decimal(b.toDouble() / 1_000_000L), "Mb")
                b >= 1_000L -> {
                    val kb = b / 1000L
                    SpeedUnit((if (kb > 999L) 999L else kb).toString(), "Kb")
                }
                else -> SpeedUnit(b.toString(), "b")
            }
        }
        return when {
            safeBytes >= 1_000_000_000L -> SpeedUnit(decimal(safeBytes.toDouble() / 1_000_000_000L), "GB")
            safeBytes >= 1_000_000L -> SpeedUnit(decimal(safeBytes.toDouble() / 1_000_000L), "MB")
            safeBytes >= 1_000L -> {
                val kb = safeBytes / 1000L
                SpeedUnit((if (kb > 999L) 999L else kb).toString(), "KB")
            }
            else -> SpeedUnit("0", "KB")
        }
    }

    /**
     * Formats data volume storage / usage (binary units: 1024 B = 1 KB, 1048576 B = 1 MB).
     */
    fun formatBytes(bytes: Long): String {
        val safeBytes = if (bytes < 0L) 0L else bytes
        return when {
            safeBytes >= 1_073_741_824L -> "${decimal(safeBytes.toDouble() / 1_073_741_824.0)} GB"
            safeBytes >= 1_048_576L -> "${decimal(safeBytes.toDouble() / 1_048_576.0)} MB"
            safeBytes >= 1024L -> "${safeBytes / 1024L} KB"
            else -> "$safeBytes B"
        }
    }

    /**
     * Persian speed label. [bits] switches the unit to bits per second
     * (the "واحد سرعت" preference).
     */
    fun formatSpeedPersian(bytesPerSec: Long, bits: Boolean = false): String {
        val safeBytes = if (bytesPerSec < 0L) 0L else bytesPerSec
        if (bits) {
            val b = TrafficMath.safeMultiplyBy8(safeBytes)
            return when {
                b >= 1_000_000_000L -> "${decimal(b.toDouble() / 1_000_000_000L)} گ بیت/ث"
                b >= 1_000_000L -> "${decimal(b.toDouble() / 1_000_000L)} م بیت/ث"
                b >= 1_000L -> "${b / 1000L} ک بیت/ث"
                else -> "$b بیت/ث"
            }
        }
        return when {
            safeBytes >= 1_000_000_000L -> "${decimal(safeBytes.toDouble() / 1_000_000_000L)} گ ب/ث"
            safeBytes >= 1_000_000L -> "${decimal(safeBytes.toDouble() / 1_000_000L)} م ب/ث"
            safeBytes >= 1_000L -> "${safeBytes / 1000L} ک ب/ث"
            else -> "$safeBytes ب/ث"
        }
    }

    fun formatBytesPersian(bytes: Long): String {
        val safeBytes = if (bytes < 0L) 0L else bytes
        return when {
            safeBytes >= 1_073_741_824L -> "${decimal(safeBytes.toDouble() / 1_073_741_824.0)} گ ب"
            safeBytes >= 1_048_576L -> "${decimal(safeBytes.toDouble() / 1_048_576.0)} م ب"
            safeBytes >= 1024L -> "${safeBytes / 1024L} ک ب"
            else -> "$safeBytes ب"
        }
    }

    /**
     * Parses a formatted data volume string back into bytes with locale tolerance (e.g. "12.5 MB" or "12,5 MB").
     */
    fun parseDataToBytes(formatted: String): Long {
        val normalized = formatted
            .trim()
            .replace('۰', '0').replace('۱', '1').replace('۲', '2')
            .replace('۳', '3').replace('۴', '4').replace('۵', '5')
            .replace('۶', '6').replace('۷', '7').replace('۸', '8')
            .replace('۹', '9')
            .replace('٬', '.')
            .replace('٫', '.')
            .replace(',', '.')
            .replace(Regex("\\s+"), " ")

        if (normalized.isEmpty()) return 0L

        val parts = normalized.split(" ")
        val value = parts.firstOrNull()?.toDoubleOrNull() ?: return 0L
        if (!value.isFinite() || value < 0.0) return 0L

        val unitText = parts.drop(1).joinToString("").uppercase(Locale.US)
        val multiplier = when {
            unitText.startsWith("GB") || unitText.startsWith("گب") -> 1_073_741_824.0
            unitText.startsWith("MB") || unitText.startsWith("مب") -> 1_048_576.0
            unitText.startsWith("KB") || unitText.startsWith("کب") -> 1024.0
            else -> 1.0
        }

        val bytes = value * multiplier
        return when {
            bytes >= Long.MAX_VALUE.toDouble() -> Long.MAX_VALUE
            else -> bytes.toLong()
        }
    }
}
