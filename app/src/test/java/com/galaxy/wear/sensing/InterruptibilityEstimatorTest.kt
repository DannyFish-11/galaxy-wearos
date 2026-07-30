package com.galaxy.wear.sensing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 融合核的行为契约。
 *
 * 这些用例是手表端唯一能在 CI 里真跑的判定层 —— 传感器采集在 CI 上跑不了,
 * 所以判定逻辑必须**全部**留在纯 Kotlin 侧,由这里守住。
 */
class InterruptibilityEstimatorTest {

    private val estimator = InterruptibilityEstimator()

    // ── 权威顺序:显式表态 > 睡眠代理 > 主动注意 > 连续融合 ──────────────

    @Test
    fun `勿扰压过一切,连抬腕在看也压过`() {
        val report = estimator.estimate(
            InterruptibilitySignals(
                dndActive = true,
                attending = true,
                motionIntensity = 0.01f,
                heartRateBpm = 60f,
                restingHeartRateBpm = 60f,
            )
        )

        assertEquals(InterruptibilityBand.BLOCKED, report.band)
        assertEquals(0f, report.score, 1e-4f)
        assertEquals(listOf(InterruptibilityReason.DO_NOT_DISTURB), report.reasons)
        // 用户显式开的勿扰是确定证据,不是"猜的"。
        assertEquals(1f, report.confidence, 1e-4f)
    }

    @Test
    fun `长时间静止且心率贴近个人静息,判为极可能在睡`() {
        val report = estimator.estimate(
            InterruptibilitySignals(
                heartRateBpm = 55f,
                restingHeartRateBpm = 55f,
                motionIntensity = 0.01f,
                stillnessSeconds = InterruptibilityThresholds.ASLEEP_STILL_SECONDS + 1,
            )
        )

        assertEquals(InterruptibilityBand.BLOCKED, report.band)
        assertTrue(report.reasons.contains(InterruptibilityReason.LIKELY_ASLEEP))
    }

    @Test
    fun `人在看表就不可能在睡——抬腕必须压过睡眠代理`() {
        val report = estimator.estimate(
            InterruptibilitySignals(
                heartRateBpm = 55f,
                restingHeartRateBpm = 55f,
                motionIntensity = 0.01f,
                stillnessSeconds = InterruptibilityThresholds.ASLEEP_STILL_SECONDS * 2,
                attending = true,
            )
        )

        assertEquals(InterruptibilityBand.FREE, report.band)
        assertTrue(report.reasons.contains(InterruptibilityReason.ATTENDING))
        assertFalse(report.reasons.contains(InterruptibilityReason.LIKELY_ASLEEP))
    }

    @Test
    fun `主动抬腕是最好的时机——断点理论下直接给高分`() {
        val report = estimator.estimate(InterruptibilitySignals(attending = true))

        assertEquals(InterruptibilityBand.FREE, report.band)
        assertEquals(InterruptibilityThresholds.ATTENDING_SCORE, report.score, 1e-4f)
    }

    @Test
    fun `运动中抬腕只是瞄一眼,打折但仍可打扰`() {
        val report = estimator.estimate(
            InterruptibilitySignals(attending = true, motionIntensity = 5f)
        )

        assertEquals(InterruptibilityThresholds.ATTENDING_SCORE_MOVING, report.score, 1e-4f)
        assertEquals(InterruptibilityBand.FREE, report.band)
        assertTrue(report.reasons.contains(InterruptibilityReason.HIGH_MOTION))
    }

    // ── 连续融合 ────────────────────────────────────────────────────────

    @Test
    fun `高运动加高唤起加长时间没交互,判为忙`() {
        val report = estimator.estimate(
            InterruptibilitySignals(
                motionIntensity = 4f,
                heartRateBpm = 130f,
                restingHeartRateBpm = 60f,
                lastInteractionAgeMs = 20 * 60 * 1000L,
            )
        )

        assertEquals(InterruptibilityBand.BUSY, report.band)
        assertEquals(0.2f, report.score, 0.01f)
        // 三路证据全有 → 满置信度。
        assertEquals(1f, report.confidence, 1e-4f)
        assertTrue(report.reasons.contains(InterruptibilityReason.HIGH_MOTION))
        assertTrue(report.reasons.contains(InterruptibilityReason.ELEVATED_AROUSAL))
    }

    @Test
    fun `静止加平静加刚交互过,判为可打扰`() {
        val report = estimator.estimate(
            InterruptibilitySignals(
                motionIntensity = 0.05f,
                heartRateBpm = 62f,
                restingHeartRateBpm = 60f,
                lastInteractionAgeMs = 30_000L,
            )
        )

        assertEquals(InterruptibilityBand.FREE, report.band)
        assertEquals(0.79f, report.score, 0.01f)
        assertTrue(report.reasons.contains(InterruptibilityReason.STILL))
        assertTrue(report.reasons.contains(InterruptibilityReason.CALM))
        assertTrue(report.reasons.contains(InterruptibilityReason.RECENT_INTERACTION))
    }

    // ── 缺失即未知,不是放行 ────────────────────────────────────────────

    @Test
    fun `一路证据都没有时返回 UNKNOWN 而不是 NEUTRAL`() {
        val report = estimator.estimate(InterruptibilitySignals())

        // 这条是整个设计的安全阀:上游绝不能把"没数据"读成"可以打扰"。
        assertEquals(InterruptibilityBand.UNKNOWN, report.band)
        assertNotEquals(InterruptibilityBand.NEUTRAL, report.band)
        assertEquals(0f, report.confidence, 1e-4f)
        assertEquals(listOf(InterruptibilityReason.NO_SENSOR_DATA), report.reasons)
    }

    @Test
    fun `只有部分证据时置信度按权重折算`() {
        val report = estimator.estimate(InterruptibilitySignals(motionIntensity = 0.05f))

        assertEquals(0.75f, report.score, 1e-4f)
        assertEquals(
            InterruptibilityThresholds.W_MOTION / InterruptibilityThresholds.TOTAL_WEIGHT,
            report.confidence,
            1e-4f,
        )
    }

    @Test
    fun `个人静息基线未建立时,心率这一路完全不参与`() {
        // 130 bpm 对静息 45 的人是剧烈运动,对静息 95 的人可能只是走路 ——
        // 没有个人基线就没有意义,宁可不用,也不能拿绝对值瞎判。
        val report = estimator.estimate(
            InterruptibilitySignals(motionIntensity = 0.05f, heartRateBpm = 130f, restingHeartRateBpm = null)
        )

        assertFalse(report.reasons.contains(InterruptibilityReason.ELEVATED_AROUSAL))
        assertEquals(0.75f, report.score, 1e-4f)
        assertEquals(
            InterruptibilityThresholds.W_MOTION / InterruptibilityThresholds.TOTAL_WEIGHT,
            report.confidence,
            1e-4f,
        )
    }

    // ── 迟滞 ────────────────────────────────────────────────────────────

    @Test
    fun `档位迟滞——同一个输入在不同历史下给出不同档位`() {
        // 构造一个只有"交互新鲜度"一路证据的输入,分数≈0.62,正好落在
        // 0.65 边界下方、0.60(= 0.65 - 迟滞)上方。
        val borderline = InterruptibilitySignals(lastInteractionAgeMs = 556_800L)

        // 无历史(UNKNOWN)时,0.62 够不着 0.65 → NEUTRAL。
        val cold = InterruptibilityEstimator()
        val coldReport = cold.estimate(borderline)
        assertEquals(0.62f, coldReport.score, 0.01f)
        assertEquals(InterruptibilityBand.NEUTRAL, coldReport.band)

        // 先进入 FREE,再喂同样的输入 → 迟滞把它留在 FREE,不来回翻。
        val warm = InterruptibilityEstimator()
        assertEquals(
            InterruptibilityBand.FREE,
            warm.estimate(InterruptibilitySignals(lastInteractionAgeMs = 10_000L)).band,
        )
        assertEquals(InterruptibilityBand.FREE, warm.estimate(borderline).band)
    }

    @Test
    fun `reset 之后迟滞记忆清空`() {
        val e = InterruptibilityEstimator()
        assertEquals(
            InterruptibilityBand.FREE,
            e.estimate(InterruptibilitySignals(lastInteractionAgeMs = 10_000L)).band,
        )
        e.reset()
        assertEquals(
            InterruptibilityBand.NEUTRAL,
            e.estimate(InterruptibilitySignals(lastInteractionAgeMs = 556_800L)).band,
        )
    }

    @Test
    fun `分数恒在 0 到 1 之间,原因标签不重复`() {
        val extremes = listOf(
            InterruptibilitySignals(motionIntensity = 1_000f, heartRateBpm = 210f, restingHeartRateBpm = 40f),
            InterruptibilitySignals(motionIntensity = -5f, heartRateBpm = 30f, restingHeartRateBpm = 60f),
            InterruptibilitySignals(motionIntensity = 0f, lastInteractionAgeMs = Long.MAX_VALUE),
        )
        extremes.forEach { signals ->
            val report = InterruptibilityEstimator().estimate(signals)
            assertTrue("score=${report.score}", report.score in 0f..1f)
            assertTrue("confidence=${report.confidence}", report.confidence in 0f..1f)
            assertEquals(report.reasons.distinct(), report.reasons)
        }
    }
}
