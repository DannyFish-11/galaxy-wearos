package com.galaxy.wear.sensing

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 可打扰性(interruptibility)—— 手表回答"现在能不能打扰他",而**不**交出身体数据。
 *
 * ## 隐私契约(所有者拍板)
 * 心率/运动/睡眠这些原始信号**只在手表本地参与运算**,任何时候都不上传。
 * 离开手表的只有 [InterruptibilityReport] 这一个标量报告:一个 0..1 的分数、
 * 一个粗粒度档位、若干**不含数值的**原因标签、以及一个置信度。
 * `InterruptibilityWireContractTest` 把这条契约钉成测试:上行 JSON 的键集合
 * 必须与允许集合**完全相等**,多一个键(哪怕叫 `bpm`)测试就红。
 *
 * ## 为什么需要它
 * 常驻注意力循环(V2 侧 `core/ambient_attention_loop.py`)的决策提示词里写着
 * 「克制是美德——拿不准就选 SILENT」。那只是一句**祈使句**:模型无从知道
 * 此刻用户是在发呆还是在跑步。这个报告把那句话变成一个**可测量的输入**。
 */

/** 粗粒度档位。UNKNOWN 与 FREE 必须严格区分——"没数据"不等于"可以打扰"。 */
enum class InterruptibilityBand(val wire: String) {
    /** 传感器无任何可用证据(权限被拒/硬件缺失)。上游必须按"未知"处理,不得当成放行。 */
    UNKNOWN("unknown"),

    /** 明确不要打扰:用户开了勿扰,或极可能在睡觉。 */
    BLOCKED("blocked"),

    /** 正忙(运动中/心率明显高于个人静息/刚有大动作)。只有要紧事才值得打断。 */
    BUSY("busy"),

    /** 说不好。既没证据说他忙,也没证据说他闲。 */
    NEUTRAL("neutral"),

    /** 可以打扰:抬腕在看、刚交互过、静息且平静。 */
    FREE("free"),
}

/**
 * 原因标签。**刻意只给标签、不给数值** —— 这是隐私契约的一部分:
 * 上游知道"因为高运动量",但不知道加速度是多少、心率是多少。
 */
enum class InterruptibilityReason(val wire: String) {
    DO_NOT_DISTURB("dnd"),
    LIKELY_ASLEEP("likely_asleep"),
    ATTENDING("attending"),
    HIGH_MOTION("high_motion"),
    MODERATE_MOTION("moderate_motion"),
    STILL("still"),
    ELEVATED_AROUSAL("elevated_arousal"),
    CALM("calm"),
    RECENT_INTERACTION("recent_interaction"),
    NO_SENSOR_DATA("no_sensor_data"),
}

/**
 * 传感器原始快照 —— **永远不离开手表**。
 *
 * 每个字段都可为 null,表示"这一路没有证据"(权限没给、硬件没有、还没采到)。
 * 估计器据此算置信度,而不是把缺失当成 0。
 *
 * @param heartRateBpm        当前心率。
 * @param restingHeartRateBpm 该用户**自己**的静息基线(见 [HeartRateBaseline])。
 *                            用个人相对值而非绝对阈值,是因为静息心率的个体差异
 *                            (40~90 bpm)远大于唤起带来的变化。
 * @param motionIntensity     加速度合矢量在采样窗内的标准差(m/s²,已因取标准差而
 *                            自然去掉重力直流分量)。
 * @param attending           用户此刻正在看表。Wear OS 的抬腕亮屏(tilt-to-wake)
 *                            使"屏幕可交互"成为抬腕的可靠代理信号,且**不需要任何
 *                            传感器权限**。如实标注:这是代理,不是手势识别。
 * @param dndActive           系统勿扰/影院模式开启 —— 用户的**显式**表态。
 * @param stillnessSeconds    连续静止时长(秒),用于睡眠代理判据。
 * @param lastInteractionAgeMs 距上次亮屏/交互的毫秒数;null 表示本次进程内还没观察到。
 */
data class InterruptibilitySignals(
    val heartRateBpm: Float? = null,
    val restingHeartRateBpm: Float? = null,
    val motionIntensity: Float? = null,
    val attending: Boolean = false,
    val dndActive: Boolean = false,
    val stillnessSeconds: Long = 0L,
    val lastInteractionAgeMs: Long? = null,
)

/**
 * 上行报告 —— 这是**唯一**离开手表的东西。
 *
 * @param score      0.0 = 绝对不要打扰,1.0 = 随便打扰。
 * @param band       粗粒度档位,给不想处理浮点的消费方用。
 * @param reasons    粗粒度原因标签(无数值)。
 * @param confidence 0.0 = 完全没证据(此时 band 必为 [InterruptibilityBand.UNKNOWN]),
 *                   1.0 = 证据齐全。上游**必须**同时看 confidence,否则会把
 *                   "没数据的 0.5" 误读成"中性可打扰"。
 */
data class InterruptibilityReport(
    val score: Float,
    val band: InterruptibilityBand,
    val reasons: List<InterruptibilityReason>,
    val confidence: Float,
) {
    /** 只有 BLOCKED 是"明确别打扰";UNKNOWN 是"不知道",两者含义不同,别合并。 */
    val isBlocked: Boolean get() = band == InterruptibilityBand.BLOCKED
}

/** 上行 JSON 的**完整**允许键集合。改这里必须同时改隐私契约测试。 */
val INTERRUPTIBILITY_WIRE_KEYS: Set<String> =
    setOf("score", "band", "reasons", "confidence", "device", "timestamp")

/**
 * 序列化成上行载荷。
 *
 * 刻意做成**纯 JVM 函数**(不碰 Android),这样隐私契约能在 `:app:testDebugUnitTest`
 * 里被真跑一遍,而不是靠人工审阅保证。
 *
 * @param timestampMs 由调用方注入,便于测试确定性。
 */
fun InterruptibilityReport.toWirePayload(
    deviceId: String = "wear_os",
    timestampMs: Long,
): JsonObject = buildJsonObject {
    // 分数保留两位:再多的精度对决策没有意义,却会把细微的生理波动泄露出去。
    put("score", Math.round(score * 100f) / 100f)
    put("band", band.wire)
    put("reasons", buildJsonArray { reasons.forEach { add(it.wire) } })
    put("confidence", Math.round(confidence * 100f) / 100f)
    put("device", deviceId)
    put("timestamp", timestampMs)
}
