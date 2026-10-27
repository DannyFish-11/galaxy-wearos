package com.galaxy.wear.sensing

/**
 * 今天走了多少步 —— 计步传感器读数的记账。
 *
 * 计步传感器(`TYPE_STEP_COUNTER`)报的是**开机以来**的累计步数,不是「今天」:
 *  * 每天第一次见到读数时把它记成当天的起点([StepDay.baseline]),之后的读数减去起点就是今天的步数;
 *  * 重启后累计数从 0 重来(读数比上次小),把重启前今天已走的记进 [StepDay.carried],起点归零;
 *  * 今天第一次读数之前走的步算不进来(表关着、刚授权、刚装上),所以带上 [StepDay.sinceMs] ——
 *    智能体据此说「从 9 点 12 分起走了 3400 步」,而不是把半天的数当成一整天。
 *
 * 纯 Kotlin,不碰 Android —— 单测能直接跑。
 */
data class StepDay(
    /** 本地日期,`yyyy-MM-dd`。 */
    val date: String,
    /** 当天起点时的传感器读数(重启后为 0)。 */
    val baseline: Long,
    /** 今天重启之前已经走的步数。 */
    val carried: Long,
    /** 最近一次读数。 */
    val last: Long,
    /** 今天从什么时候开始算的(毫秒时间戳)。 */
    val sinceMs: Long,
) {
    val steps: Long get() = carried + (last - baseline)
}

object DailySteps {

    /** 读到一次传感器累计数 [counter] 之后今天的账。 */
    fun observe(prev: StepDay?, counter: Long, today: String, nowMs: Long): StepDay = when {
        counter < 0 -> prev ?: StepDay(today, 0, 0, 0, nowMs)
        prev == null || prev.date != today -> StepDay(today, baseline = counter, carried = 0, last = counter, sinceMs = nowMs)
        // 累计数变小了 = 重启过,传感器从 0 重新数
        counter < prev.last -> prev.copy(baseline = 0, carried = prev.steps, last = counter)
        else -> prev.copy(last = counter)
    }

    fun encode(day: StepDay): String = listOf(day.date, day.baseline, day.carried, day.last, day.sinceMs).joinToString("|")

    /** 存坏了、格式不对就当没有记过。 */
    fun decode(raw: String?): StepDay? {
        val parts = raw?.split("|") ?: return null
        if (parts.size != 5) return null
        val numbers = parts.drop(1).map { it.toLongOrNull() ?: return null }
        return StepDay(parts[0], numbers[0], numbers[1], numbers[2], numbers[3])
    }
}
