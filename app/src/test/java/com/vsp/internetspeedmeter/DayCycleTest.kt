package com.vsp.internetspeedmeter

import com.vsp.internetspeedmeter.util.DayCycle
import org.junit.Assert.assertEquals
import org.junit.Test

class DayCycleTest {

    @Test
    fun testMonthOf_extractsPrefix() {
        val date = "21-09-2026"
        val month = DayCycle.monthOf(date)
        assertEquals("09-2026", month)
    }

    @Test
    fun testMonthOf_handlesShortDate() {
        val date = "2026"
        val month = DayCycle.monthOf(date)
        assertEquals("2026", month) // fallback if length < 7
    }
}
