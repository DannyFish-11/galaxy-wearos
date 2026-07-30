package com.galaxy.wear.sensing

import kotlin.math.abs

/**
 * 上行节流 —— 决定"这一拍要不要真的发出去"。
 *
 * 手表每几十秒算一次可打扰性,但**没必要**每次都上行:BLE/MQTT 链路上的
 * 每一条消息都要唤醒无线电,是手表上最耗电的动作之一。同时又不能只按固定
 * 周期发,否则"用户刚抬腕"这种最有价值的瞬时变化会被延迟几分钟才送到。
 *
 * 规则(按优先级):
 * 1. 从没发过 → 发(上游得先有个初值);
 * 2. 档位变了 → 发(档位是上游真正会分支的东西);
 * 3. 分数变化超过 [SCORE_DELTA] → 发(同档位内的显著漂移);
 * 4. 距上次发送超过 [HEARTBEAT_MS] → 发(心跳,让上游能识别"手表还活着"
 *    与"手表掉线了"的区别 —— 没有心跳的话,陈旧数据会被无限期当成最新)。
 *
 * 做成**无 Android 依赖的纯类**,时间由调用方注入,这样节流行为可以被
 * 单元测试确定性地验证,而不是靠真机蹲守。
 */
class InterruptibilityUplinkPolicy(
    private val scoreDelta: Float = SCORE_DELTA,
    private val heartbeatMs: Long = HEARTBEAT_MS,
) {
    private var lastSentBand: InterruptibilityBand? = null
    private var lastSentScore: Float = 0f
    private var lastSentAtMs: Long = 0L

    fun shouldSend(report: InterruptibilityReport, nowMs: Long): Boolean {
        val previousBand = lastSentBand ?: return true
        if (report.band != previousBand) return true
        if (abs(report.score - lastSentScore) >= scoreDelta) return true
        return nowMs - lastSentAtMs >= heartbeatMs
    }

    /** 只有**确认发出去了**才记账 —— 发送失败时不更新,下一拍会重试。 */
    fun recordSent(report: InterruptibilityReport, nowMs: Long) {
        lastSentBand = report.band
        lastSentScore = report.score
        lastSentAtMs = nowMs
    }

    fun reset() {
        lastSentBand = null
        lastSentScore = 0f
        lastSentAtMs = 0L
    }

    companion object {
        const val SCORE_DELTA = 0.15f
        const val HEARTBEAT_MS = 10L * 60L * 1000L
    }
}
