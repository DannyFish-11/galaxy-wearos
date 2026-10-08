package com.galaxy.wear.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 手表作为「成员」说的三种帧,形状按 V2 网关的处理器钉死。
 *
 * 这些字段位置不是手表的惯例,是网关读的位置:读错一个,表现就是「认证过了、连得上,
 * 智能体却看不见这块表」—— 没有任何报错。每条断言后面都对应网关里读它的那一行。
 */
class WatchMemberFramesTest {

    private fun str(o: JsonObject, k: String) = o[k]!!.jsonPrimitive.content

    @Test
    fun `register carries the token at the top level because that is where the gateway reads it`() {
        val f = WatchMember.registerFrame("w-1", "tok-123", "OPPO Watch 3", "2.0.1", 1_000L)
        assertEquals("device_register", str(f, "type"))
        assertEquals("w-1", str(f, "device_id"))
        assertEquals("tok-123", str(f, "token"))
        assertEquals("wearos", str(f, "device_type"))
        assertEquals("wearos", str(f, "platform"))
        assertEquals("OPPO Watch 3", str(f, "device_name"))
        assertEquals(1_000L, f["timestamp"]!!.jsonPrimitive.content.toLong())
    }

    @Test
    fun `register mirrors identity into payload for readers that only look there`() {
        val p = WatchMember.registerFrame("w-1", "t", "n", "v", 1L)["payload"]!!.jsonObject
        assertEquals("wearos", str(p, "device_type"))
        assertEquals("wearos", str(p, "platform"))
    }

    @Test
    fun `register does not claim a capability bitmap it does not have`() {
        // V2 把 capabilities 当整数位图;名字列表按「没报位图」处理。动作清单走 capability_report。
        assertFalse(WatchMember.registerFrame("w", "t", "n", "v", 1L).containsKey("capabilities"))
    }

    @Test
    fun `capability report lists the actions at the top level`() {
        val f = WatchMember.capabilityReportFrame("w-1", 2L)
        assertEquals("capability_report", str(f, "type"))
        assertEquals("w-1", str(f, "device_id"))
        val actions = (f["supported_actions"] as JsonArray).map { it.jsonPrimitive.content }
        assertEquals(WatchMember.SUPPORTED_ACTIONS, actions)
        assertTrue(actions.containsAll(listOf("notify", "haptic", "get_status")))
    }

    @Test
    fun `a successful command result is addressed by command id and carries the data`() {
        val f = WatchMember.commandResultFrame(
            "w-1", "cmd_ab12", true, buildJsonObject { put("delivered", true) }, null, 3L,
        )
        assertEquals("command_result", str(f, "type"))
        assertEquals("cmd_ab12", str(f, "command_id"))
        assertEquals("cmd_ab12", str(f, "correlation_id"))
        assertTrue(f["success"]!!.jsonPrimitive.boolean)
        val p = f["payload"]!!.jsonObject
        assertTrue(p["success"]!!.jsonPrimitive.boolean)
        assertEquals(JsonPrimitive(true), p["delivered"])
        assertFalse(p.containsKey("error"))
    }

    @Test
    fun `a failed command result says why`() {
        val f = WatchMember.commandResultFrame("w", "c1", false, JsonObject(emptyMap()), "通知没有弹出", 4L)
        val p = f["payload"]!!.jsonObject
        assertFalse(p["success"]!!.jsonPrimitive.boolean)
        assertEquals("通知没有弹出", str(p, "error"))
    }

    @Test
    fun `a failure without a reason still carries one rather than an empty success false`() {
        val p = WatchMember.commandResultFrame("w", "c1", false, JsonObject(emptyMap()), null, 5L)["payload"]!!.jsonObject
        assertTrue(str(p, "error").isNotBlank())
    }

    @Test
    fun `the executor and the advertised list cannot drift apart`() {
        // 清单里的每一项都得在 AgentCommandExecutor 里有实现(AgentCommandTest 逐项验),
        // 反过来,清单不许悄悄变:加动作就得同时改这里和执行器。
        assertEquals(listOf("notify", "haptic", "get_status"), WatchMember.SUPPORTED_ACTIONS)
    }
}
