package com.galaxy.wear.domain

import com.galaxy.wear.ui.HapticType
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * 智能体经 `devices__invoke` 下发给手表的一个动作。
 *
 * 线上形状(V2 `UnifiedConnectionManager.send_command_and_wait`):
 * `{"type":"command","command_id":"cmd_ab12…","command":"<动作>","params":{…}}`,
 * 网关等同一个 `command_id` 的 `command_result`,默认 15 秒。
 *
 * 手表是「成员」——只响应,不发起、不执行任意代码。能做的动作是一个**封闭的清单**
 * ([com.galaxy.wear.data.WatchMember.SUPPORTED_ACTIONS]),每个都在 [AgentCommandExecutor] 里有实现;
 * 清单之外的一律回失败并说明原因,而不是忽略(忽略 = 智能体白等 15 秒)。
 */
data class AgentCommand(
    val commandId: String,
    val action: String,
    val params: JsonObject,
)

/** 动作的执行结果。[error] 在 [success] 为 false 时给出原因。 */
data class AgentCommandResult(
    val success: Boolean,
    val data: JsonObject = JsonObject(emptyMap()),
    val error: String? = null,
) {
    companion object {
        fun ok(data: JsonObject = JsonObject(emptyMap())) = AgentCommandResult(true, data)
        fun fail(reason: String) = AgentCommandResult(false, error = reason)
    }
}

/**
 * 动作要碰的那些「真东西」(通知、震动、状态)。执行器只管判定与编排,真正的 Android 调用由实现方做 ——
 * 所以执行器能直接在 JVM 上测,不需要 Context。
 */
interface WatchEffects {
    /** 弹一条智能体的消息通知。返回是否真的弹出(例如通知权限被拒就是 false)。 */
    fun notify(title: String, text: String, replyExpected: Boolean, conversationId: String): Boolean

    /** 震一下。返回是否真的震了(没有振动器 / 被系统拒绝就是 false)。 */
    fun haptic(type: HapticType): Boolean

    /** 手表此刻的状态(只读)。 */
    fun status(): JsonObject
}

object AgentCommandParser {
    /**
     * 从网关下发的原始帧取出动作。缺 `command_id` 或 `command` 就是没法回话的帧 → null
     * (调用方记日志,不回:没有 command_id 回了网关也认领不到)。
     */
    fun parse(frame: JsonObject): AgentCommand? {
        val commandId = frame["command_id"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val action = frame["command"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (commandId.isEmpty() || action.isEmpty()) return null
        val params = (frame["params"] as? JsonObject) ?: JsonObject(emptyMap())
        return AgentCommand(commandId, action, params)
    }
}

/**
 * 执行智能体下发的动作。
 *
 * 规矩:
 *  * **幂等**:同一个 `command_id` 重发(网关重试、断线补发)只执行一次,回同一个结果。
 *    否则「震一下」会震两次、「弹通知」会弹两条。
 *  * **只放行不请自来的那一类震动**:智能体只能触发 `alerting` 的触觉类别。非 alerting 的(点按确认、
 *    相位切换)是伴随用户自己的动作的反馈,由智能体触发就成了噪音,也会稀释「手感 = 发生了什么」的词汇。
 *  * **输入有上限**:标题 60 字、正文 500 字。手表屏幕小,超长文本不是「显示得下」的问题,
 *    而是智能体没想清楚要说什么。
 */
class AgentCommandExecutor(
    private val effects: WatchEffects,
    private val rememberLast: Int = DEFAULT_REMEMBER,
) {
    private val done = object : LinkedHashMap<String, AgentCommandResult>(16, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, AgentCommandResult>?): Boolean =
            size > rememberLast
    }

    fun execute(cmd: AgentCommand): AgentCommandResult {
        synchronized(done) { done[cmd.commandId]?.let { return it } }
        val result = try {
            run(cmd)
        } catch (e: Exception) {
            AgentCommandResult.fail("手表执行 ${cmd.action} 时出错:${e.message ?: e.javaClass.simpleName}")
        }
        synchronized(done) { done[cmd.commandId] = result }
        return result
    }

    private fun run(cmd: AgentCommand): AgentCommandResult = when (cmd.action) {
        "notify" -> notify(cmd.params)
        "haptic" -> haptic(cmd.params)
        "get_status" -> AgentCommandResult.ok(effects.status())
        else -> AgentCommandResult.fail(
            "手表不支持动作 '${cmd.action}'(支持:${SUPPORTED.joinToString()})",
        )
    }

    private fun notify(params: JsonObject): AgentCommandResult {
        val text = params.str("text").trim()
        if (text.isEmpty()) return AgentCommandResult.fail("notify 需要非空的 text")
        if (text.length > MAX_TEXT) return AgentCommandResult.fail("notify 的 text 太长(${text.length} 字,上限 $MAX_TEXT)")
        val title = params.str("title").trim()
        if (title.length > MAX_TITLE) return AgentCommandResult.fail("notify 的 title 太长(${title.length} 字,上限 $MAX_TITLE)")
        val replyExpected = (params["reply_expected"] as? JsonPrimitive)?.booleanOrNull ?: false
        val delivered = effects.notify(title, text, replyExpected, params.str("conversation_id"))
        return if (delivered) {
            AgentCommandResult.ok(buildJsonObject { put("delivered", true) })
        } else {
            AgentCommandResult.fail("通知没有弹出(通知权限被拒,或系统拦下了)")
        }
    }

    private fun haptic(params: JsonObject): AgentCommandResult {
        val name = params.str("pattern").trim().ifEmpty { HapticType.MESSAGE_ARRIVAL.name }
        val type = HapticType.values().firstOrNull { it.name.equals(name, ignoreCase = true) }
            ?: return AgentCommandResult.fail("未知的触觉类别 '$name'(可用:${ALLOWED_HAPTICS.joinToString { it.name.lowercase() }})")
        if (!type.alerting) {
            return AgentCommandResult.fail(
                "触觉类别 '${name.lowercase()}' 是伴随用户自己动作的反馈,不能由智能体触发" +
                    "(可用:${ALLOWED_HAPTICS.joinToString { it.name.lowercase() }})",
            )
        }
        return if (effects.haptic(type)) {
            AgentCommandResult.ok(buildJsonObject { put("pattern", type.name.lowercase()) })
        } else {
            AgentCommandResult.fail("这台手表没有震动器,或系统拒绝了震动")
        }
    }

    private fun JsonObject.str(key: String): String = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()

    companion object {
        const val MAX_TITLE = 60
        const val MAX_TEXT = 500
        const val DEFAULT_REMEMBER = 32

        private val SUPPORTED = com.galaxy.wear.data.WatchMember.SUPPORTED_ACTIONS
        private val ALLOWED_HAPTICS = HapticType.values().filter { it.alerting }
    }
}
