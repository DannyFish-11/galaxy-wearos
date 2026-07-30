package com.galaxy.wear.sensing

/**
 * 融合核 —— 把传感器快照折成一个"现在能不能打扰他"的标量。
 *
 * ## 依据的参考标准(不自己拍脑袋定数)
 *
 * 1. **Breakpoint theory**(Iqbal & Bailey,任务断点处的打断代价显著更低):
 *    手表上能观测到的断点代理是"运动状态的停止"和"用户主动抬腕看表"。
 *    因此本估计器把**用户主动注意**([InterruptibilitySignals.attending])
 *    当作最强的正向信号,而把**持续高运动量**当作最强的负向连续信号。
 * 2. **Bounded deferral**(Horvitz & Apacible,有界延迟优于直接丢弃):
 *    本模块只负责**判定**,不负责丢消息。BLOCKED 的语义是"现在别主动开口",
 *    上游应当延迟而非丢弃。用户**显式发起**的请求不受此分数约束。
 * 3. **HRV / RMSSD 的取舍(如实说明)**:文献里常用 RMSSD(如 37 ms 这类阈值)
 *    衡量副交感活性与压力。但本项目可用的 `Sensor.TYPE_HEART_RATE` 只给
 *    **平滑后的 BPM**,拿不到逐拍 RR 间期,**算不出真正的 RMSSD**。
 *    所以这里**不假装做 HRV**,改用"心率相对**个人静息基线**的抬升比例"作为
 *    唤起度代理 —— 个体间静息心率差异(40~90 bpm)远大于唤起造成的变化,
 *    绝对阈值在跨人使用时没有意义,个人相对值才有。
 *
 * ## 缺失即未知,不是放行
 * 每一路信号带一个权重,只有**真正拿到**的信号参与加权平均;
 * `confidence` = 参与权重 / 总权重。全无证据时返回
 * [InterruptibilityBand.UNKNOWN] 而不是 NEUTRAL —— 上游绝不能把"没数据"
 * 读成"可以打扰"。
 *
 * ## 有状态的部分
 * 只有档位迟滞需要记忆上一拍的档位(见 [InterruptibilityThresholds.BAND_HYSTERESIS]),
 * 用来避免分数在边界上抖动导致档位反复翻转。除此之外本类无副作用。
 */
class InterruptibilityEstimator {

    private var lastBand: InterruptibilityBand = InterruptibilityBand.UNKNOWN

    /** 供测试与重启后复位。 */
    fun reset() {
        lastBand = InterruptibilityBand.UNKNOWN
    }

    fun estimate(signals: InterruptibilitySignals): InterruptibilityReport {
        // ── 第 0 档:显式表态。用户自己开的勿扰,任何传感器证据都不该推翻它。 ──
        // 注意语义边界:这只压制**主动**开口;用户显式发起的对话不走这条分数。
        if (signals.dndActive) {
            return finalize(0f, listOf(InterruptibilityReason.DO_NOT_DISTURB), 1f, forced = InterruptibilityBand.BLOCKED)
        }

        // ── 第 1 档:睡眠代理。命名为 LIKELY_ASLEEP 而非 ASLEEP —— 这是长时间
        // 静止 + 心率贴近个人静息 + 没在看表的**代理判据**,不是睡眠分期。 ──
        if (looksAsleep(signals)) {
            return finalize(0.05f, listOf(InterruptibilityReason.LIKELY_ASLEEP), 0.8f, forced = InterruptibilityBand.BLOCKED)
        }

        // ── 第 2 档:用户主动在看表。断点理论下这是最好的时机,直接给高分。 ──
        if (signals.attending) {
            val reasons = mutableListOf(InterruptibilityReason.ATTENDING)
            // 运动中抬腕通常只是"瞄一眼",不是坐下来聊天的时机 —— 打个折,但仍算 FREE。
            val motion = signals.motionIntensity
            val score = if (motion != null && motion > InterruptibilityThresholds.MOTION_MODERATE) {
                reasons.add(InterruptibilityReason.HIGH_MOTION)
                InterruptibilityThresholds.ATTENDING_SCORE_MOVING
            } else {
                InterruptibilityThresholds.ATTENDING_SCORE
            }
            return finalize(score, reasons, 1f)
        }

        // ── 第 3 档:连续融合。 ──
        val reasons = mutableListOf<InterruptibilityReason>()
        var weighted = 0f
        var usedWeight = 0f

        signals.motionIntensity?.let { motion ->
            weighted += InterruptibilityThresholds.W_MOTION * motionScore(motion, reasons)
            usedWeight += InterruptibilityThresholds.W_MOTION
        }

        val hr = signals.heartRateBpm
        val resting = signals.restingHeartRateBpm
        // 基线没建立起来之前,心率这一路**不参与**——绝对 BPM 跨人无意义。
        if (hr != null && resting != null && resting > 0f) {
            weighted += InterruptibilityThresholds.W_AROUSAL * arousalScore((hr - resting) / resting, reasons)
            usedWeight += InterruptibilityThresholds.W_AROUSAL
        }

        signals.lastInteractionAgeMs?.let { age ->
            weighted += InterruptibilityThresholds.W_RECENCY * recencyScore(age, reasons)
            usedWeight += InterruptibilityThresholds.W_RECENCY
        }

        if (usedWeight <= 0f) {
            // 一路证据都没有。返回 UNKNOWN,分数给中性值只是为了让数值字段有定义,
            // 上游必须靠 confidence==0 判断"这条不能用"。
            return finalize(0.5f, listOf(InterruptibilityReason.NO_SENSOR_DATA), 0f, forced = InterruptibilityBand.UNKNOWN)
        }

        val confidence = usedWeight / InterruptibilityThresholds.TOTAL_WEIGHT
        return finalize(weighted / usedWeight, reasons, confidence)
    }

    // ── 各路信号的打分函数 ───────────────────────────────────────────────

    /**
     * 运动强度 → 分数。参考量级(加速度合矢量标准差,m/s²):
     * 静置手表 ≈ 0~0.15;轻微手部动作 ≈ 0.15~1.0;步行 ≈ 1.5~4;跑动 > 5。
     */
    private fun motionScore(motion: Float, reasons: MutableList<InterruptibilityReason>): Float = when {
        motion < InterruptibilityThresholds.MOTION_STILL -> {
            reasons.add(InterruptibilityReason.STILL)
            0.75f
        }
        motion < InterruptibilityThresholds.MOTION_LIGHT ->
            lerp(motion, InterruptibilityThresholds.MOTION_STILL, InterruptibilityThresholds.MOTION_LIGHT, 0.6f, 0.4f)
        motion < InterruptibilityThresholds.MOTION_MODERATE -> {
            reasons.add(InterruptibilityReason.MODERATE_MOTION)
            lerp(motion, InterruptibilityThresholds.MOTION_LIGHT, InterruptibilityThresholds.MOTION_MODERATE, 0.4f, 0.15f)
        }
        else -> {
            reasons.add(InterruptibilityReason.HIGH_MOTION)
            0.1f
        }
    }

    /** 心率相对个人静息的抬升比例 → 分数(唤起度越高越不该打扰)。 */
    private fun arousalScore(elevation: Float, reasons: MutableList<InterruptibilityReason>): Float = when {
        elevation <= InterruptibilityThresholds.HR_ELEVATION_CALM -> {
            reasons.add(InterruptibilityReason.CALM)
            0.8f
        }
        elevation <= InterruptibilityThresholds.HR_ELEVATION_MILD ->
            lerp(elevation, InterruptibilityThresholds.HR_ELEVATION_CALM, InterruptibilityThresholds.HR_ELEVATION_MILD, 0.8f, 0.5f)
        elevation <= InterruptibilityThresholds.HR_ELEVATION_HIGH -> {
            reasons.add(InterruptibilityReason.ELEVATED_AROUSAL)
            lerp(elevation, InterruptibilityThresholds.HR_ELEVATION_MILD, InterruptibilityThresholds.HR_ELEVATION_HIGH, 0.5f, 0.2f)
        }
        else -> {
            reasons.add(InterruptibilityReason.ELEVATED_AROUSAL)
            0.1f
        }
    }

    /** 距上次交互的时长 → 分数。刚交互过 = 人就在手表跟前。 */
    private fun recencyScore(ageMs: Long, reasons: MutableList<InterruptibilityReason>): Float = when {
        ageMs <= InterruptibilityThresholds.RECENT_MS -> {
            reasons.add(InterruptibilityReason.RECENT_INTERACTION)
            0.85f
        }
        ageMs <= InterruptibilityThresholds.STALE_MS ->
            lerp(
                ageMs.toFloat(),
                InterruptibilityThresholds.RECENT_MS.toFloat(),
                InterruptibilityThresholds.STALE_MS.toFloat(),
                0.85f,
                0.6f,
            )
        else -> 0.5f
    }

    private fun looksAsleep(s: InterruptibilitySignals): Boolean {
        if (s.attending) return false
        if (s.stillnessSeconds < InterruptibilityThresholds.ASLEEP_STILL_SECONDS) return false
        val hr = s.heartRateBpm ?: return false
        val resting = s.restingHeartRateBpm ?: return false
        if (resting <= 0f) return false
        return hr <= resting * InterruptibilityThresholds.ASLEEP_HR_RATIO
    }

    // ── 档位 + 迟滞 ─────────────────────────────────────────────────────

    private fun finalize(
        rawScore: Float,
        reasons: List<InterruptibilityReason>,
        confidence: Float,
        forced: InterruptibilityBand? = null,
    ): InterruptibilityReport {
        val score = rawScore.coerceIn(0f, 1f)
        val band = forced ?: bandWithHysteresis(score)
        lastBand = band
        return InterruptibilityReport(
            score = score,
            band = band,
            reasons = reasons.distinct(),
            confidence = confidence.coerceIn(0f, 1f),
        )
    }

    /**
     * 迟滞:**离开**当前档位需要多越过边界 [InterruptibilityThresholds.BAND_HYSTERESIS]。
     * 没有迟滞时,分数在 0.65 附近的正常抖动会让档位每一拍都翻转,上游看到的
     * 就是一串毫无意义的状态变更(还会把上行节流策略打穿)。
     */
    private fun bandWithHysteresis(score: Float): InterruptibilityBand {
        val h = InterruptibilityThresholds.BAND_HYSTERESIS
        val freeCut = if (lastBand == InterruptibilityBand.FREE) {
            InterruptibilityThresholds.BAND_FREE - h
        } else {
            InterruptibilityThresholds.BAND_FREE
        }
        val busyCut = if (lastBand == InterruptibilityBand.BUSY) {
            InterruptibilityThresholds.BAND_BUSY + h
        } else {
            InterruptibilityThresholds.BAND_BUSY
        }
        return when {
            score >= freeCut -> InterruptibilityBand.FREE
            score < busyCut -> InterruptibilityBand.BUSY
            else -> InterruptibilityBand.NEUTRAL
        }
    }

    private fun lerp(x: Float, x0: Float, x1: Float, y0: Float, y1: Float): Float {
        if (x1 <= x0) return y1
        val t = ((x - x0) / (x1 - x0)).coerceIn(0f, 1f)
        return y0 + (y1 - y0) * t
    }
}

/**
 * 所有阈值集中一处,便于所有者按自己的身体数据实测后调整。
 *
 * 这些数字不是凭空定的:运动量级取自加速度计常见量程经验值,心率一律用
 * **个人相对**抬升比例(理由见 [InterruptibilityEstimator] 类文档),
 * 睡眠判据是长时间静止 + 贴近个人静息心率的保守代理。
 */
object InterruptibilityThresholds {
    // 运动强度(加速度合矢量标准差,m/s²)
    const val MOTION_STILL = 0.15f
    const val MOTION_LIGHT = 1.0f
    const val MOTION_MODERATE = 3.0f

    // 心率相对个人静息的抬升比例
    const val HR_ELEVATION_CALM = 0.05f
    const val HR_ELEVATION_MILD = 0.20f
    const val HR_ELEVATION_HIGH = 0.40f

    // 交互新鲜度
    const val RECENT_MS = 60_000L
    const val STALE_MS = 600_000L

    // 睡眠代理
    const val ASLEEP_STILL_SECONDS = 30L * 60L
    const val ASLEEP_HR_RATIO = 1.03f

    // 主动注意
    const val ATTENDING_SCORE = 0.9f
    const val ATTENDING_SCORE_MOVING = 0.7f

    // 档位边界与迟滞
    const val BAND_FREE = 0.65f
    const val BAND_BUSY = 0.35f
    const val BAND_HYSTERESIS = 0.05f

    // 各路权重
    const val W_MOTION = 1.0f
    const val W_AROUSAL = 0.8f
    const val W_RECENCY = 0.6f
    const val TOTAL_WEIGHT = W_MOTION + W_AROUSAL + W_RECENCY
}
