package com.galaxy.wear.sensing

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InterruptibilityUplinkPolicyTest {

    private fun report(
        score: Float,
        band: InterruptibilityBand = InterruptibilityBand.NEUTRAL,
    ) = InterruptibilityReport(score, band, emptyList(), 1f)

    @Test
    fun `第一条一定发——上游得先有个初值`() {
        val policy = InterruptibilityUplinkPolicy()

        assertTrue(policy.shouldSend(report(0.5f), nowMs = 0L))
    }

    @Test
    fun `发过之后原样重复不再发`() {
        val policy = InterruptibilityUplinkPolicy()
        val r = report(0.5f)
        policy.recordSent(r, 0L)

        assertFalse(policy.shouldSend(r, 1_000L))
    }

    @Test
    fun `档位变化立刻发——档位是上游真正会分支的东西`() {
        val policy = InterruptibilityUplinkPolicy()
        policy.recordSent(report(0.5f, InterruptibilityBand.NEUTRAL), 0L)

        assertTrue(policy.shouldSend(report(0.5f, InterruptibilityBand.BUSY), 1_000L))
    }

    @Test
    fun `同档位内分数显著漂移也发`() {
        val policy = InterruptibilityUplinkPolicy()
        policy.recordSent(report(0.40f), 0L)

        assertFalse(policy.shouldSend(report(0.50f), 1_000L))
        assertTrue(policy.shouldSend(report(0.56f), 1_000L))
    }

    @Test
    fun `心跳兜底——让上游能区分手表还活着与手表掉线了`() {
        val policy = InterruptibilityUplinkPolicy()
        val r = report(0.5f)
        policy.recordSent(r, 0L)

        assertFalse(policy.shouldSend(r, InterruptibilityUplinkPolicy.HEARTBEAT_MS - 1))
        assertTrue(policy.shouldSend(r, InterruptibilityUplinkPolicy.HEARTBEAT_MS))
    }

    @Test
    fun `发送失败时不记账,下一拍会重试`() {
        val policy = InterruptibilityUplinkPolicy()
        val r = report(0.5f)

        assertTrue(policy.shouldSend(r, 0L))
        // 模拟链路抛异常:调用方**没有**调 recordSent。
        assertTrue(policy.shouldSend(r, 1_000L))

        policy.recordSent(r, 1_000L)
        assertFalse(policy.shouldSend(r, 2_000L))
    }

    @Test
    fun `reset 之后回到从没发过的状态`() {
        val policy = InterruptibilityUplinkPolicy()
        val r = report(0.5f)
        policy.recordSent(r, 0L)
        assertFalse(policy.shouldSend(r, 1_000L))

        policy.reset()
        assertTrue(policy.shouldSend(r, 1_000L))
    }

    @Test
    fun `UNKNOWN 也走同一套节流,不被特殊对待`() {
        // 服务层刻意把 UNKNOWN 也上报(否则上游无法区分"手表在但没证据"与
        // "根本没手表"),节流策略把它压到最多每心跳一条。
        val policy = InterruptibilityUplinkPolicy()
        val unknown = InterruptibilityReport(0.5f, InterruptibilityBand.UNKNOWN, emptyList(), 0f)

        assertTrue(policy.shouldSend(unknown, 0L))
        policy.recordSent(unknown, 0L)
        assertFalse(policy.shouldSend(unknown, InterruptibilityUplinkPolicy.HEARTBEAT_MS - 1))
        assertTrue(policy.shouldSend(unknown, InterruptibilityUplinkPolicy.HEARTBEAT_MS))
    }
}
