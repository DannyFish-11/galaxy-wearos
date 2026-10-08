package com.galaxy.wear.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 手表作为中心智能体的「成员」,在线上说话的那几种帧。
 *
 * 为什么手表要登记
 * ================
 * 手表是分布式系统里的成员(只响应,不发起)。中心智能体靠两样东西认得它:
 *
 *  1. **登记**(`device_register`)—— 网关据此知道「这是谁、什么类型」,把它登记进设备管理器与
 *     连接权威。没有这一步,智能体想「在手表上问你」时找不到它,只能退回在对话里问。
 *  2. **报能力**(`capability_report`)—— 告诉智能体「我能替你做什么」。智能体经 `devices__invoke`
 *     调手表上的一个动作,走的是 `{"type":"command","command_id","command","params"}`,等同一个
 *     `command_id` 的 `command_result`。
 *
 * 这些帧的形状是照 V2 网关的处理器写的,不是手表自己的惯例:
 *  * `device_register`:令牌在**顶层** `token`(`evaluate_ingress_authentication` 读它);
 *  * `capability_report`:`supported_actions` 在**顶层**(处理器读 `message["supported_actions"]`);
 *  * `command_result`:`command_id` 在**顶层**(网关按它唤醒等待中的调用),结果在 `payload`
 *    (`payload.success == false` 即失败,`payload.error` 是原因)。
 *
 * `AipMessage`(共享协议的信封)没有 `command_id` 这个字段,所以这里直接造 [JsonObject]。
 *
 * 纯 Kotlin,不碰 Android —— 单测能直接跑。
 */
internal object WatchMember {

    const val DEVICE_TYPE = "wearos"
    const val PLATFORM = "wearos"

    /**
     * 这几种帧不经 [com.ufo.galaxy.shared.protocol.AipMessage] 信封，所以信封默认带的
     * `version` 得自己补上。缺了它网关把帧当成 AIP/1.0：1.0 里的 `command_result` 是「任务结果」
     * (`task_result`)，会被跨仓 schema 闸门以 `missing_schema_version_metadata` 拒收 ——
     * 手表对智能体下发动作的回话到不了等待它的那个调用，每次 `devices__invoke` 都只能等到超时。
     */
    const val AIP_VERSION = "3.0"

    /** 手表接受智能体下发的动作。每一个都在 [com.galaxy.wear.domain.AgentCommandExecutor] 里有实现。 */
    val SUPPORTED_ACTIONS: List<String> = listOf("notify", "haptic", "get_status")

    /**
     * `device_register`。
     *
     * 不报 `capabilities`:V2 把它当整数位图,名字列表按「没报位图」处理;动作清单走
     * `capability_report`。缺这个字段是诚实的(没有位图可报),不是遗漏。
     */
    fun registerFrame(
        deviceId: String,
        token: String,
        deviceName: String,
        appVersion: String,
        nowMs: Long,
    ): JsonObject = buildJsonObject {
        put("version", AIP_VERSION)
        put("type", "device_register")
        put("device_id", deviceId)
        put("timestamp", nowMs)
        put("token", token)
        put("device_type", DEVICE_TYPE)
        put("platform", PLATFORM)
        put("device_name", deviceName)
        put("app_version", appVersion)
        put(
            "payload",
            buildJsonObject {
                put("device_type", DEVICE_TYPE)
                put("platform", PLATFORM)
                put("device_name", deviceName)
            },
        )
    }

    /** `capability_report`:这台手表能替智能体做的动作。 */
    fun capabilityReportFrame(deviceId: String, nowMs: Long): JsonObject = buildJsonObject {
        put("version", AIP_VERSION)
        put("type", "capability_report")
        put("device_id", deviceId)
        put("timestamp", nowMs)
        put("platform", PLATFORM)
        put("supported_actions", JsonArray(SUPPORTED_ACTIONS.map { JsonPrimitive(it) }))
    }

    /**
     * `command_result`:对智能体下发的动作的回话。
     *
     * @param data 成功时带回去的内容(失败时也可以带,比如已执行到哪一步)
     * @param error 失败原因。失败时必填,不要让智能体对着一个没有原因的 `success=false` 猜。
     */
    fun commandResultFrame(
        deviceId: String,
        commandId: String,
        success: Boolean,
        data: JsonObject,
        error: String?,
        nowMs: Long,
    ): JsonObject = buildJsonObject {
        put("version", AIP_VERSION)
        put("type", "command_result")
        put("device_id", deviceId)
        put("command_id", commandId)
        // 网关两种认领方式都认:按 command_id(UCM.send_command_and_wait)与按相关 id。
        put("correlation_id", commandId)
        put("timestamp", nowMs)
        put("success", success)
        put(
            "payload",
            buildJsonObject {
                put("success", success)
                for ((k, v) in data) put(k, v)
                if (!success) put("error", error ?: "unknown error")
            },
        )
    }
}
