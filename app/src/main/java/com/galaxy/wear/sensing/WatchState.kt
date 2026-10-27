package com.galaxy.wear.sensing

import com.ufo.galaxy.shared.protocol.DeviceState

/**
 * 这块表此刻的状态(V2 `core/device_state.py` 读),跟着心跳帧走:`{"type":"heartbeat","payload":{"state":{...}}}`
 * (见 [com.galaxy.wear.data.WatchMember.stateFrame])。
 *
 * 可打扰性并在这里:档位由手表判断,原因由手表写成一句话放进 `note` —— 中心不再翻译标签。
 * 说不出所以然([InterruptibilityBand.UNKNOWN] 或零置信度)就不报 `interruptible`,中心当不知道。
 * 戴没戴着手表现在测不出,不报。
 *
 * 隐私契约不变:心率、运动、睡眠这些原始信号只在手表本地参与运算,离开手表的只有 [DeviceState] 那几个键,
 * `note` 里只有词、没有数值(`WatchStateTest` 钉死)。纯 Kotlin,不碰 Android(读系统在 [AndroidWatchState])。
 */
object WatchState {

    /** 从系统读到的;读不到的是 null。 */
    data class Readings(
        val battery: Float? = null,
        val charging: Boolean? = null,
        val screenOn: Boolean? = null,
        val inCall: Boolean? = null,
    )

    /** 原因标签 → 给模型看的词。只有词,不带数值。 */
    val REASON_WORDS: Map<InterruptibilityReason, String> = mapOf(
        InterruptibilityReason.DO_NOT_DISTURB to "开着勿扰",
        InterruptibilityReason.LIKELY_ASLEEP to "极可能在睡",
        InterruptibilityReason.ATTENDING to "正抬腕在看",
        InterruptibilityReason.HIGH_MOTION to "运动量大",
        InterruptibilityReason.MODERATE_MOTION to "有一定活动",
        InterruptibilityReason.STILL to "静止",
        InterruptibilityReason.ELEVATED_AROUSAL to "心率高于平时静息",
        InterruptibilityReason.CALM to "平静",
        InterruptibilityReason.RECENT_INTERACTION to "刚交互过",
        InterruptibilityReason.NO_SENSOR_DATA to "没有传感证据",
    )

    fun build(report: InterruptibilityReport, r: Readings): DeviceState {
        val usable = report.band != InterruptibilityBand.UNKNOWN && report.confidence > 0f
        return DeviceState(
            battery = r.battery?.coerceIn(0f, 1f),
            charging = r.charging,
            screenOn = r.screenOn,
            inCall = r.inCall,
            interruptible = if (usable) report.band.wire else null,
            note = if (usable) note(report) else null,
        )
    }

    /** 原因写成一句话;证据不足时说一声,模型据此拿捏轻重。 */
    fun note(report: InterruptibilityReport): String? {
        val words = report.reasons.mapNotNull { REASON_WORDS[it] }.distinct().joinToString("、")
        val hedge = if (report.confidence < 0.5f) "证据不足" else ""
        return listOf(words, hedge).filter { it.isNotEmpty() }.joinToString(",").ifEmpty { null }
    }

    /** 节流时比较用:电量按一成算,免得每掉 1% 就上行一次。 */
    fun uplinkKey(r: Readings): Readings = r.copy(battery = r.battery?.let { Math.round(it * 10f) / 10f })
}
