package com.vsp.internetspeedmeter.util

import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

object FormatUtils {

    private val decimalSymbols = DecimalFormatSymbols(Locale.US)
    private val decimalFormat = DecimalFormat("#.0", decimalSymbols)

    data class SpeedUnit(val value: String, val unit: String)

    /**
     * Formats network throughput speed (decimal units: 1000 B = 1 KB).
     */
    fun formatSpeed(bytesPerSec: Long): String {
        val safeBytes = if (bytesPerSec < 0L) 0L else bytesPerSec
        return when {
            safeBytes >= 1_000_000_000L -> "${decimalFormat.format(safeBytes.toDouble() / 1_000_000_000L)} GB/s"
            safeBytes >= 1_000_000L -> "${decimalFormat.format(safeBytes.toDouble() / 1_000_000L)} MB/s"
            safeBytes >= 1_000L -> "${safeBytes / 1000L} KB/s"
            else -> "$safeBytes B/s"
        }
    }

    /**
     * Formats speed for the compact status bar icon (max 3 digits + 2-letter unit).
     */
    fun formatSpeedForIcon(bytesPerSec: Long): SpeedUnit {
        val safeBytes = if (bytesPerSec < 0L) 0L else bytesPerSec
        return when {
            safeBytes >= 1_000_000_000L -> SpeedUnit(decimalFormat.format(safeBytes.toDouble() / 1_000_000_000L), "GB")
            safeBytes >= 1_000_000L -> SpeedUnit(decimalFormat.format(safeBytes.toDouble() / 1_000_000L), "MB")
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
            safeBytes >= 1_073_741_824L -> "${decimalFormat.format(safeBytes.toDouble() / 1_073_741_824.0)} GB"
            safeBytes >= 1_048_576L -> "${decimalFormat.format(safeBytes.toDouble() / 1_048_576.0)} MB"
            safeBytes >= 1024L -> "${safeBytes / 1024L} KB"
            else -> "$safeBytes B"
        }
    }

    /**
     * Parses a formatted data volume string back into bytes with locale tolerance (e.g. "12.5 MB" or "12,5 MB").
     */
    fun parseDataToBytes(formatted: String): Long {
        val trimmed = formatted.trim()
        if (trimmed.isEmpty()) return 0L

        val parts = trimmed.split(" ")
        if (parts.isEmpty()) return 0L

        val numberPart = parts[0].replace(',', '.')
        val value = numberPart.toDoubleOrNull() ?: return 0L

        if (parts.size < 2) {
            return value.toLong()
        }

        val unit = parts[1].uppercase(Locale.US)
        return when {
            unit.startsWith("GB") -> (value * 1_073_741_824.0).toLong()
            unit.startsWith("MB") -> (value * 1_048_576.0).toLong()
            unit.startsWith("KB") -> (value * 1024.0).toLong()
            else -> value.toLong()
        }
    }
}
