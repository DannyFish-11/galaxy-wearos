package com.galaxy.wear.sensing

/**
 * 个人静息心率基线 —— 纯 Kotlin 滑动窗口,**永不上传**。
 *
 * 为什么必须是"个人的":静息心率的个体差异(常见 40~90 bpm)远大于唤起
 * 造成的变化(十几 bpm)。用绝对阈值判"心率高不高",对静息 45 的人和静息 85
 * 的人会给出完全相反的结论。所以估计器只吃"相对本人静息的抬升比例",
 * 而这个比例的分母就由本类维护。
 *
 * 取**低分位数**而不是最小值:最小值会被单个坏读数(光电传感器在手腕松动时
 * 常给出离谱低值)永久钉死;分位数对这类离群点稳健。
 *
 * 取**长窗口**:短窗口在一次跑步中会把"静息"抬到运动心率,反而让抬升比例
 * 归零、正好在最不该打扰的时候判成可打扰。窗口按 [CAPACITY] 条样本计,
 * 配合监视器的占空比采样(约每分钟一条),覆盖数小时。
 */
class HeartRateBaseline(
    private val capacity: Int = CAPACITY,
    private val minSamples: Int = MIN_SAMPLES,
) {
    private val samples = ArrayDeque<Float>(capacity)

    /** 采样。明显不可能的读数直接丢弃,不让坏数据进窗口。 */
    fun add(bpm: Float) {
        if (bpm < PLAUSIBLE_MIN_BPM || bpm > PLAUSIBLE_MAX_BPM) return
        if (samples.size >= capacity) samples.removeFirst()
        samples.addLast(bpm)
    }

    /** 样本不足时返回 null —— 宁可让心率这一路**不参与**,也不给一个瞎猜的基线。 */
    fun resting(): Float? {
        if (samples.size < minSamples) return null
        val sorted = samples.sorted()
        // 最近邻分位:index = round(p * (n-1)),小样本下比线性插值更好解释。
        val idx = Math.round(RESTING_PERCENTILE * (sorted.size - 1)).coerceIn(0, sorted.size - 1)
        return sorted[idx]
    }

    val size: Int get() = samples.size

    fun clear() = samples.clear()

    /**
     * 导出/导入 —— 供监视器落到加密偏好里跨进程存活。
     * 没有持久化的话,每次重启后要等几十分钟基线才重新建立,
     * 而这段时间里心率那一路是完全失效的。
     */
    fun snapshot(): String = samples.joinToString(",")

    fun restore(encoded: String) {
        samples.clear()
        if (encoded.isBlank()) return
        encoded.split(',').forEach { token ->
            token.trim().toFloatOrNull()?.let { add(it) }
        }
    }

    companion object {
        const val CAPACITY = 240
        const val MIN_SAMPLES = 20
        const val RESTING_PERCENTILE = 0.10f
        const val PLAUSIBLE_MIN_BPM = 30f
        const val PLAUSIBLE_MAX_BPM = 220f
    }
}
