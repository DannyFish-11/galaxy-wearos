package com.galaxy.wear.ui

/**
 * 触觉词汇表 —— **纯 Kotlin**,不碰 Android,因此可以在 CI 里被真跑一遍。
 *
 * ## 为什么要有这么一张表
 *
 * 手表最独特的地方是:它贴着皮肤,所以**可以在你不看它的时候告诉你事情**。
 * 但这只在"不同的事有不同的手感"时才成立。原实现里 `ERROR` 与
 * `DECISION_PROMPT` 都是 `EFFECT_DOUBLE_CLICK` —— 两种完全不同的含义、
 * 一模一样的手感,用户根本分不出"出错了"和"需要我拿主意"。那不是风格问题,
 * 是这块载体最大的长处被抹掉了。
 *
 * ## 依据的参考标准(不自己拍脑袋定数)
 *
 * 来自 Android 官方触觉设计文档(developer.android.com 的 haptics-principles /
 * custom-haptic-effects,以及 AOSP 的 haptics UX foundation):
 *
 * 1. **单次触觉元素 10~20 ms**。文档原文:"A good keyclick haptic feedback
 *    signal should last between 10 to 20 milliseconds",且驱动器在输入结束后
 *    还会余振 20~50 ms —— 所以元素本身必须短,否则手感发"嗡"。
 *    [HapticStrength] 的 12/16/20 ms 全部落在这个窗口内。
 * 2. **强度随"重要性 × 频率"分级**。文档要求很频繁的事件要非常轻,重要的
 *    事件要更强;并把 `EFFECT_CLICK` 定为轻(TICK)与重(HEAVY_CLICK)之间的
 *    强度基准点。本表因此是 LIGHT/MEDIUM/STRONG 三档,最频繁的 UI_TAP 取最轻。
 * 3. **同一含义永远同一手感**(consistency of meaning)—— 用户靠重复才建立
 *    得起联想。所以词汇表集中在一处,任何调用方都不许自己拼 VibrationEffect。
 * 4. **克制**:文档反复讲 "less is more",过多振动会让手变木。所以每个模式
 *    最多 3 下、总时长设了上限。
 *
 * ## 节奏承载身份,强度承载轻重
 *
 * 人对"几下、间隔多长"的分辨力,远好于对"振动强弱"的分辨力。所以
 * **身份主要靠节奏**(1 下 / 2 下从容 / 2 下急促 / 3 下等距),
 * **轻重才靠强度**。两个维度叠起来,5 个 alerting 类别两两可分。
 *
 * 所有阈值集中在这里,所有者按自己的手感实测后可直接改这一张表。
 */

/** 三档强度。对应 Android 的 TICK / CLICK / HEAVY_CLICK 三个预定义效果。 */
enum class HapticStrength(
    /** 振幅(0~255),给 waveform 回退路径用。 */
    val amplitude: Int,
    /** 元素时长(ms),落在文档给的 10~20 ms 窗口内。 */
    val durationMs: Long,
    /** 组合基元的缩放系数(0~1),给 Composition 路径用。 */
    val primitiveScale: Float,
) {
    LIGHT(amplitude = 80, durationMs = 12L, primitiveScale = 0.4f),
    MEDIUM(amplitude = 160, durationMs = 16L, primitiveScale = 0.7f),
    STRONG(amplitude = 255, durationMs = 20L, primitiveScale = 1.0f),
}

/**
 * 一下振动。
 *
 * @param gapBeforeMs 与上一下之间的静默间隔。第一下恒为 0。
 *   间隔是**身份的主要载体**:40 ms 的两下听感是"急促"、90 ms 是"从容",
 *   这两种在手腕上是完全不同的两个词。
 */
data class HapticPulse(val strength: HapticStrength, val gapBeforeMs: Long = 0L)

/** 一个完整的触觉模式。 */
data class HapticPattern(val pulses: List<HapticPulse>) {

    val totalDurationMs: Long
        get() = pulses.sumOf { it.gapBeforeMs + it.strength.durationMs }

    /**
     * 转成 Android 波形数组:``[静默, 振动, 静默, 振动, ...]``。
     *
     * 这个格式**同时**给两个地方用,不重复造:
     * - `VibrationEffect.createWaveform` 的回退路径;
     * - `NotificationChannel.setVibrationPattern` —— 决策通知在 Android O+
     *   上由渠道决定振动,不接这里的话,全表最重要的那一类反而用的是系统默认。
     */
    fun toWaveformTimings(): LongArray {
        val out = LongArray(pulses.size * 2)
        pulses.forEachIndexed { i, pulse ->
            out[i * 2] = pulse.gapBeforeMs
            out[i * 2 + 1] = pulse.strength.durationMs
        }
        return out
    }

    /** 与 [toWaveformTimings] 一一对应的振幅数组(静默段为 0)。 */
    fun toWaveformAmplitudes(): IntArray {
        val out = IntArray(pulses.size * 2)
        pulses.forEachIndexed { i, pulse ->
            out[i * 2] = 0
            out[i * 2 + 1] = pulse.strength.amplitude
        }
        return out
    }
}

object HapticVocabulary {

    /** 急促:两下几乎连在一起,读作"出事了"。 */
    const val GAP_URGENT_MS = 40L

    /** 从容:两下之间留得住,读作"收束/完成了"。 */
    const val GAP_CALM_MS = 90L

    /** 等距三拍:唯一的三下,读作"需要你"。 */
    const val GAP_TRIPLET_MS = 70L

    /** 每个模式最多几下(克制原则)。 */
    const val MAX_PULSES = 3

    /** 单个模式的总时长上限(克制原则)。 */
    const val MAX_TOTAL_MS = 300L

    private val PATTERNS: Map<HapticType, HapticPattern> = mapOf(
        // ── 非警示:伴随可见动作,只确认"生效了",越轻越好 ──────────────
        HapticType.UI_TAP to HapticPattern(listOf(HapticPulse(HapticStrength.LIGHT))),
        HapticType.PHASE_CHANGE to HapticPattern(listOf(HapticPulse(HapticStrength.LIGHT))),

        // ── 警示:不请自来,必须两两可分 ────────────────────────────────
        // 一下中等 —— 最普通的"有事找你"。
        HapticType.MESSAGE_ARRIVAL to HapticPattern(listOf(HapticPulse(HapticStrength.MEDIUM))),
        // 一下强 —— 侧键唤起时多半没在看屏幕,要一下明确的"我在听了"。
        HapticType.LISTENING_START to HapticPattern(listOf(HapticPulse(HapticStrength.STRONG))),
        // 两下从容 —— 收束感。
        HapticType.TASK_DONE to HapticPattern(
            listOf(
                HapticPulse(HapticStrength.MEDIUM),
                HapticPulse(HapticStrength.MEDIUM, gapBeforeMs = GAP_CALM_MS),
            )
        ),
        // 三下等距 —— 全表唯一的三拍,留给"需要你拿主意"。
        HapticType.DECISION_PROMPT to HapticPattern(
            listOf(
                HapticPulse(HapticStrength.MEDIUM),
                HapticPulse(HapticStrength.MEDIUM, gapBeforeMs = GAP_TRIPLET_MS),
                HapticPulse(HapticStrength.MEDIUM, gapBeforeMs = GAP_TRIPLET_MS),
            )
        ),
        // 两下急促且强 —— 与 TASK_DONE 同为两下,但间隔与强度都不同。
        HapticType.ERROR to HapticPattern(
            listOf(
                HapticPulse(HapticStrength.STRONG),
                HapticPulse(HapticStrength.STRONG, gapBeforeMs = GAP_URGENT_MS),
            )
        ),
    )

    /**
     * 取模式。表里必然有(由 `HapticVocabularyTest` 保证覆盖全部枚举值),
     * 万一漏了就退成最轻的一下,而不是抛异常把调用方带崩 —— 触觉是锦上添花,
     * 不该成为崩溃源。
     */
    fun patternFor(type: HapticType): HapticPattern =
        PATTERNS[type] ?: HapticPattern(listOf(HapticPulse(HapticStrength.LIGHT)))

    /** 全部已登记的类别,供测试遍历。 */
    fun registeredTypes(): Set<HapticType> = PATTERNS.keys
}
