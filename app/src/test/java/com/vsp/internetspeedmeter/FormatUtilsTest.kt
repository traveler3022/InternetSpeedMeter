package com.vsp.internetspeedmeter

import com.vsp.internetspeedmeter.util.FormatUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FormatUtilsTest {

    @Test
    fun testFormatSpeed_standardValues() {
        assertEquals("0 B/s", FormatUtils.formatSpeed(0L))
        assertEquals("500 B/s", FormatUtils.formatSpeed(500L))
        assertEquals("1 KB/s", FormatUtils.formatSpeed(1_000L))
        assertEquals("150 KB/s", FormatUtils.formatSpeed(150_000L))
        assertEquals("1.5 MB/s", FormatUtils.formatSpeed(1_500_000L))
        assertEquals("10.0 MB/s", FormatUtils.formatSpeed(10_000_000L))
        assertEquals("1.5 GB/s", FormatUtils.formatSpeed(1_500_000_000L))
    }

    @Test
    fun testFormatSpeed_negativeSanitization() {
        assertEquals("0 B/s", FormatUtils.formatSpeed(-100L))
        assertEquals("0 B/s", FormatUtils.formatSpeed(-1L))
    }

    @Test
    fun testFormatSpeedForIcon() {
        val zero = FormatUtils.formatSpeedForIcon(0L)
        assertEquals("0", zero.value)
        assertEquals("KB", zero.unit)

        val kb = FormatUtils.formatSpeedForIcon(450_000L)
        assertEquals("450", kb.value)
        assertEquals("KB", kb.unit)

        val mb = FormatUtils.formatSpeedForIcon(5_400_000L)
        assertEquals("5.4", mb.value)
        assertEquals("MB", mb.unit)

        val gb = FormatUtils.formatSpeedForIcon(2_100_000_000L)
        assertEquals("2.1", gb.value)
        assertEquals("GB", gb.unit)
    }

    @Test
    fun testFormatBytes_binaryUnits() {
        assertEquals("0 B", FormatUtils.formatBytes(0L))
        assertEquals("512 B", FormatUtils.formatBytes(512L))
        assertEquals("1 KB", FormatUtils.formatBytes(1024L))
        assertEquals("100 KB", FormatUtils.formatBytes(100 * 1024L))
        assertEquals("1.0 MB", FormatUtils.formatBytes(1024 * 1024L))
        assertEquals("1.5 MB", FormatUtils.formatBytes((1.5 * 1024 * 1024).toLong()))
        assertEquals("1.0 GB", FormatUtils.formatBytes(1024 * 1024 * 1024L))
    }

    @Test
    fun testParseDataToBytes_standardAndLocales() {
        // Standard dot separator
        assertEquals(1024L, FormatUtils.parseDataToBytes("1 KB"))
        assertEquals(1024 * 1024L, FormatUtils.parseDataToBytes("1.0 MB"))
        assertEquals((1.5 * 1024 * 1024).toLong(), FormatUtils.parseDataToBytes("1.5 MB"))
        assertEquals(1024 * 1024 * 1024L, FormatUtils.parseDataToBytes("1.0 GB"))

        // European / Persian comma separator ("1,5 MB")
        assertEquals((1.5 * 1024 * 1024).toLong(), FormatUtils.parseDataToBytes("1,5 MB"))
        assertEquals(1024 * 1024 * 1024L, FormatUtils.parseDataToBytes("1,0 GB"))

        // Plain bytes
        assertEquals(250L, FormatUtils.parseDataToBytes("250 B"))
        assertEquals(250L, FormatUtils.parseDataToBytes("250"))

        // Edge cases and malformed inputs
        assertEquals(0L, FormatUtils.parseDataToBytes(""))
        assertEquals(0L, FormatUtils.parseDataToBytes("   "))
        assertEquals(0L, FormatUtils.parseDataToBytes("invalid text"))
    }

    @Test
    fun testParseDataToBytes_handlesRepeatedWhitespaceAndPersianDigits() {
        assertEquals(
            (1.5 * 1024 * 1024).toLong(),
            FormatUtils.parseDataToBytes(" ۱٬۵   م ب ")
        )
    }

    @Test
    fun testFormatSpeed_bitsMode_doesNotOverflow() {
        val result = FormatUtils.formatSpeedPersian(Long.MAX_VALUE, bits = true)
        assertTrue(result.isNotEmpty())
    }

    @Test
    fun testRoundtripFormattingAndParsing() {
        val originalMb = 128 * 1024 * 1024L
        val formatted = FormatUtils.formatBytes(originalMb)
        val parsed = FormatUtils.parseDataToBytes(formatted)
        assertEquals(originalMb, parsed)
    }
}
