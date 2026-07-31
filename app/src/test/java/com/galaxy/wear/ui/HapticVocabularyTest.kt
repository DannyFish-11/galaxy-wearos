package com.galaxy.wear.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 触觉词汇表的设计契约。
 *
 * 手表最独特的地方是它贴着皮肤,**可以在你不看它的时候告诉你事情**。
 * 但这只在"不同的事有不同的手感"时才成立。这些用例把那个前提钉死。
 *
 * 判据全部来自 Android 官方触觉设计文档,不是我自己的审美偏好:
 * 元素时长 10~20 ms、强度随重要性与频率分级、同一含义同一手感、以及
 * "less is more"的克制原则。
 */
class HapticVocabularyTest {

    private val alerting = HapticType.values().filter { it.alerting }
    private val ambient = HapticType.values().filter { !it.alerting }

    // ── 覆盖 ────────────────────────────────────────────────────────────

    @Test
    fun `每个类别都有登记的模式,没有漏网的枚举值`() {
        assertEquals(HapticType.values().toSet(), HapticVocabulary.registeredTypes())
    }

    // ── 可区分性:这是整张表存在的理由 ──────────────────────────────────

    @Test
    fun `警示类别两两手感不同——不看表也要能分辨`() {
        val seen = mutableMapOf<HapticPattern, HapticType>()
        alerting.forEach { type ->
            val pattern = HapticVocabulary.patternFor(type)
            val clash = seen.put(pattern, type)
            assertTrue(
                "$type 与 $clash 手感完全一样 —— 用户不看屏幕就分不出这两件事",
                clash == null,
            )
        }
    }

    @Test
    fun `出错与需要决策必须分得开`() {
        // 原实现里这两个都是 EFFECT_DOUBLE_CLICK:两种完全不同的含义、
        // 一模一样的手感。这条是那个 bug 的回归钉。
        assertTrue(
            HapticVocabulary.patternFor(HapticType.ERROR) !=
                HapticVocabulary.patternFor(HapticType.DECISION_PROMPT)
        )
    }

    @Test
    fun `同样是两下的两个类别,靠间隔与强度拉开`() {
        val done = HapticVocabulary.patternFor(HapticType.TASK_DONE)
        val error = HapticVocabulary.patternFor(HapticType.ERROR)

        assertEquals(2, done.pulses.size)
        assertEquals(2, error.pulses.size)
        // 急促 vs 从容:间隔差要足够大,否则手腕上分不出来。
        assertTrue(
            "两下的间隔差不足,手感会糊在一起",
            done.pulses[1].gapBeforeMs - error.pulses[1].gapBeforeMs >= 40L,
        )
        assertTrue(error.pulses[0].strength.amplitude > done.pulses[0].strength.amplitude)
    }

    @Test
    fun `需要拿主意是全表唯一的三拍`() {
        val triples = HapticType.values().filter { HapticVocabulary.patternFor(it).pulses.size == 3 }
        assertEquals(listOf(HapticType.DECISION_PROMPT), triples)
    }

    @Test
    fun `非警示类别允许共用最轻的一下——伴随可见动作,再细分是噪音`() {
        ambient.forEach { type ->
            val pattern = HapticVocabulary.patternFor(type)
            assertEquals("非警示类别应当是单下: $type", 1, pattern.pulses.size)
            assertEquals(
                "全表最频繁的事件必须用最轻的一档: $type",
                HapticStrength.LIGHT,
                pattern.pulses[0].strength,
            )
        }
    }

    // ── 参考标准:元素时长 10~20 ms ─────────────────────────────────────

    @Test
    fun `每一下都落在文档给的 10 到 20 毫秒窗口内`() {
        // 超出这个窗口手感会发"嗡"(驱动器还会余振 20~50 ms)。
        HapticStrength.values().forEach { strength ->
            assertTrue(
                "${strength.name} 时长 ${strength.durationMs}ms 超出 10~20ms 窗口",
                strength.durationMs in 10L..20L,
            )
        }
    }

    @Test
    fun `三档强度严格递增,不留两档同强的含糊地带`() {
        val ordered = listOf(HapticStrength.LIGHT, HapticStrength.MEDIUM, HapticStrength.STRONG)
        ordered.zipWithNext { a, b ->
            assertTrue("${a.name} 应弱于 ${b.name}", a.amplitude < b.amplitude)
            assertTrue("${a.name} 应短于等于 ${b.name}", a.durationMs <= b.durationMs)
            assertTrue("${a.name} 的基元缩放应小于 ${b.name}", a.primitiveScale < b.primitiveScale)
        }
    }

    // ── 参考标准:克制(less is more) ──────────────────────────────────

    @Test
    fun `没有任何模式超过三下`() {
        HapticType.values().forEach { type ->
            val n = HapticVocabulary.patternFor(type).pulses.size
            assertTrue("$type 有 $n 下,超过克制上限", n <= HapticVocabulary.MAX_PULSES)
        }
    }

    @Test
    fun `没有任何模式超过总时长上限`() {
        HapticType.values().forEach { type ->
            val total = HapticVocabulary.patternFor(type).totalDurationMs
            assertTrue("$type 总时长 ${total}ms 超上限", total <= HapticVocabulary.MAX_TOTAL_MS)
        }
    }

    @Test
    fun `第一下永远没有前置静默`() {
        HapticType.values().forEach { type ->
            assertEquals(
                "$type 的第一下带了前置延迟,会让反馈显得迟钝",
                0L,
                HapticVocabulary.patternFor(type).pulses[0].gapBeforeMs,
            )
        }
    }

    // ── 参考标准:重要性驱动强度 ────────────────────────────────────────

    @Test
    fun `需要人拿主意的那一类,不比普通消息更弱`() {
        val decision = HapticVocabulary.patternFor(HapticType.DECISION_PROMPT)
        val message = HapticVocabulary.patternFor(HapticType.MESSAGE_ARRIVAL)

        // 更重要 = 更"占分量"。这里体现为下数更多、总时长更长。
        assertTrue(decision.pulses.size > message.pulses.size)
        assertTrue(decision.totalDurationMs > message.totalDurationMs)
    }

    @Test
    fun `警示类别都不弱于非警示类别`() {
        val ambientPeak = ambient.maxOf { t ->
            HapticVocabulary.patternFor(t).pulses.maxOf { it.strength.amplitude }
        }
        alerting.forEach { type ->
            val peak = HapticVocabulary.patternFor(type).pulses.maxOf { it.strength.amplitude }
            assertTrue("$type 比伴随点按的确认还轻,会被忽略", peak >= ambientPeak)
        }
    }

    // ── 波形导出:通知渠道与回退路径共用同一份 ──────────────────────────

    @Test
    fun `波形导出是标准的静默振动交替格式`() {
        val pattern = HapticVocabulary.patternFor(HapticType.DECISION_PROMPT)
        val timings = pattern.toWaveformTimings()
        val amplitudes = pattern.toWaveformAmplitudes()

        assertEquals(pattern.pulses.size * 2, timings.size)
        assertEquals(timings.size, amplitudes.size)
        // 偶数下标是静默段,奇数下标是振动段。
        timings.indices.forEach { i ->
            if (i % 2 == 0) {
                assertEquals("下标 $i 应为静默段", 0, amplitudes[i])
            } else {
                assertTrue("下标 $i 应为振动段", amplitudes[i] > 0)
            }
        }
        assertEquals(0L, timings[0])
        assertEquals(pattern.totalDurationMs, timings.sum())
    }

    @Test
    fun `未登记的类别退成最轻的一下,而不是抛异常把调用方带崩`() {
        // 触觉是锦上添花,任何情况下都不该成为崩溃源。
        val fallback = HapticVocabulary.patternFor(HapticType.UI_TAP)
        assertEquals(1, fallback.pulses.size)
        assertEquals(HapticStrength.LIGHT, fallback.pulses[0].strength)
    }
}
