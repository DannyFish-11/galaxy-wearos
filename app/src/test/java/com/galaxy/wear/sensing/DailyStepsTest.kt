package com.galaxy.wear.sensing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 计步传感器报的是开机以来的累计数;「今天走了多少步」要自己记账:当天起点、换天、重启。
 */
class DailyStepsTest {

    @Test
    fun `the first reading of the day is where today starts`() {
        val d = DailySteps.observe(null, counter = 52_000, today = "2026-10-25", nowMs = 100L)
        assertEquals(0L, d.steps)
        assertEquals(100L, d.sinceMs)
        assertEquals(3_400L, DailySteps.observe(d, 55_400, "2026-10-25", 200L).steps)
    }

    @Test
    fun `a new day starts from zero again`() {
        val yesterday = DailySteps.observe(DailySteps.observe(null, 1_000, "2026-10-24", 1L), 9_000, "2026-10-24", 2L)
        assertEquals(8_000L, yesterday.steps)
        val today = DailySteps.observe(yesterday, 9_050, "2026-10-25", 3L)
        assertEquals("2026-10-25", today.date)
        assertEquals(0L, today.steps)
        assertEquals(3L, today.sinceMs)
    }

    @Test
    fun `a reboot restarts the sensor count and keeps what was walked before it`() {
        var d = DailySteps.observe(null, 40_000, "2026-10-25", 1L)
        d = DailySteps.observe(d, 42_000, "2026-10-25", 2L)
        d = DailySteps.observe(d, 30, "2026-10-25", 3L) // 重启后从 0 数起
        assertEquals(2_030L, d.steps)
        d = DailySteps.observe(d, 500, "2026-10-25", 4L)
        assertEquals(2_500L, d.steps)
        assertEquals(1L, d.sinceMs)
    }

    @Test
    fun `a nonsense reading changes nothing`() {
        val d = DailySteps.observe(null, 10, "2026-10-25", 1L)
        assertEquals(d, DailySteps.observe(d, -1, "2026-10-25", 2L))
    }

    @Test
    fun `the day survives being stored and anything garbled reads as nothing recorded`() {
        val d = StepDay("2026-10-25", 40_000, 120, 42_000, 1_700_000_000_000L)
        assertEquals(d, DailySteps.decode(DailySteps.encode(d)))
        assertNull(DailySteps.decode(null))
        assertNull(DailySteps.decode(""))
        assertNull(DailySteps.decode("2026-10-25|x|0|0|0"))
        assertNull(DailySteps.decode("2026-10-25|1|2|3"))
    }
}
