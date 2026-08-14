package com.focuslock.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TimeZoneHelperTest {

    @Test
    fun `hard block starts at 8 30 pm`() {
        assertEquals(TimeZoneHelper.BlockZone.HARD_BLOCK, TimeZoneHelper.getZoneFor(20, 30))
    }

    @Test
    fun `hard block remains active after midnight`() {
        assertEquals(TimeZoneHelper.BlockZone.HARD_BLOCK, TimeZoneHelper.getZoneFor(7, 15))
    }

    @Test
    fun `soft block applies during the morning`() {
        assertEquals(TimeZoneHelper.BlockZone.SOFT_BLOCK, TimeZoneHelper.getZoneFor(10, 0))
    }

    @Test
    fun `afternoon is free time`() {
        assertEquals(TimeZoneHelper.BlockZone.FREE, TimeZoneHelper.getZoneFor(17, 45))
    }

    @Test
    fun `invalid time is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            TimeZoneHelper.getZoneFor(24, 0)
        }
    }
}
