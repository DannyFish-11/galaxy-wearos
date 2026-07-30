package com.galaxy.wear.sensing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class HeartRateBaselineTest {

    @Test
    fun `样本不足时返回 null 而不是瞎猜一个基线`() {
        val baseline = HeartRateBaseline()
        repeat(HeartRateBaseline.MIN_SAMPLES - 1) { baseline.add(70f) }

        assertNull(baseline.resting())

        baseline.add(70f)
        assertNotNull(baseline.resting())
    }

    @Test
    fun `静息取低分位数`() {
        val baseline = HeartRateBaseline()
        (60..79).forEach { baseline.add(it.toFloat()) }

        // 20 个样本,第 10 百分位 → 最近邻索引 round(0.10 * 19) = 2 → 62。
        assertEquals(62f, baseline.resting()!!, 1e-4f)
    }

    @Test
    fun `单个坏读数不会把基线永久钉死——分位数对离群点稳健`() {
        val baseline = HeartRateBaseline()
        repeat(19) { baseline.add(70f) }
        // 手腕松动时光电传感器常给出离谱的低值。
        baseline.add(35f)

        assertEquals(70f, baseline.resting()!!, 1e-4f)
    }

    @Test
    fun `不可能的读数直接丢弃,不让坏数据进窗口`() {
        val baseline = HeartRateBaseline()
        baseline.add(5f)
        baseline.add(400f)
        baseline.add(HeartRateBaseline.PLAUSIBLE_MIN_BPM - 0.1f)
        baseline.add(HeartRateBaseline.PLAUSIBLE_MAX_BPM + 0.1f)

        assertEquals(0, baseline.size)
    }

    @Test
    fun `窗口满后按先进先出淘汰`() {
        val baseline = HeartRateBaseline(capacity = 5, minSamples = 1)
        listOf(60f, 61f, 62f, 63f, 64f, 65f, 66f).forEach { baseline.add(it) }

        assertEquals(5, baseline.size)
        // 最早的 60/61 已被淘汰,窗口是 62..66。
        assertEquals(62f, baseline.resting()!!, 1e-4f)
    }

    @Test
    fun `快照与恢复是可逆的——重启后不必等几十分钟重建基线`() {
        val original = HeartRateBaseline()
        (60..79).forEach { original.add(it.toFloat()) }
        val encoded = original.snapshot()

        val restored = HeartRateBaseline()
        restored.restore(encoded)

        assertEquals(original.size, restored.size)
        assertEquals(original.resting()!!, restored.resting()!!, 1e-4f)
    }

    @Test
    fun `恢复损坏的快照不抛异常,只丢弃坏条目`() {
        val baseline = HeartRateBaseline(capacity = 10, minSamples = 1)
        baseline.restore("70,not-a-number,,72, 74 ,999")

        // 70/72/74 保留;非数字与超范围的 999 被丢弃。
        assertEquals(3, baseline.size)
        assertEquals(70f, baseline.resting()!!, 1e-4f)
    }

    @Test
    fun `恢复空快照是安全的`() {
        val baseline = HeartRateBaseline()
        baseline.restore("")

        assertEquals(0, baseline.size)
        assertNull(baseline.resting())
    }
}
