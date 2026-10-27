package com.galaxy.wear.data

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 手表的设备自述(V2 `core/device_self.py` 读):能力上报里带 `self_description`,
 * 做得了的动作带 effect / 参数,传感器或权限缺了的进 unavailable 附原因,四样声明照手表真实情况填。
 */
class WatchSelfDescriptionTest {

    private fun selfOf(frame: JsonObject) = frame["self_description"]!!.jsonObject

    private fun names(arr: JsonObject, key: String) = arr[key]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }

    @Test
    fun `the capability report carries the self description next to the old fields`() {
        val frame = WatchMember.capabilityReportFrame("w1", 1L, WatchMember.SUPPORTED_ACTIONS, deviceName = "Galaxy Watch")
        val self = selfOf(frame)
        assertEquals("device_self/1", self["schema"]!!.jsonPrimitive.content)
        assertEquals("Galaxy Watch", self["name"]!!.jsonPrimitive.content)
        assertEquals("wearos", self["kind"]!!.jsonPrimitive.content)
        assertTrue(self["about"]!!.jsonPrimitive.content.contains("手腕"))
        assertEquals(WatchMember.SUPPORTED_ACTIONS, names(self, "actions"))
        // 过渡期旧字段照发,给还没升级的中心读
        assertEquals(WatchMember.SUPPORTED_ACTIONS, frame["supported_actions"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun `every action says what doing it means`() {
        val self = WatchMember.selfDescription(WatchMember.SUPPORTED_ACTIONS)
        val effects = self["actions"]!!.jsonArray.associate {
            it.jsonObject["name"]!!.jsonPrimitive.content to it.jsonObject["effect"]!!.jsonPrimitive.content
        }
        assertEquals(
            mapOf("notify" to "notify", "haptic" to "notify", "get_status" to "read", "heart_rate" to "read", "steps" to "read"),
            effects,
        )
    }

    @Test
    fun `parameters match what the executor reads`() {
        val notify = WatchMember.selfDescription(listOf("notify"))["actions"]!!.jsonArray.single().jsonObject
        val params = notify["params"]!!.jsonObject
        assertEquals(listOf("text"), params["required"]!!.jsonArray.map { it.jsonPrimitive.content })
        val text = params["properties"]!!.jsonObject["text"]!!.jsonObject
        assertEquals(500, text["maxLength"]!!.jsonPrimitive.content.toInt())
        val haptic = WatchMember.selfDescription(listOf("haptic"))["actions"]!!.jsonArray.single().jsonObject
        val patterns = haptic["params"]!!.jsonObject["properties"]!!.jsonObject["pattern"]!!.jsonObject["enum"]!!.jsonArray
        assertTrue(patterns.isNotEmpty())
    }

    @Test
    fun `a sensor permission not given moves the action to unavailable with the reason`() {
        val reason = "戴表的人没给手表「身体传感器」权限,测不了心率(在手表的应用权限里打开)"
        val actions = WatchMember.reportable { it != WatchMember.Need.HEART_RATE }
        val unavailable = WatchMember.unavailable { if (it == WatchMember.Need.HEART_RATE) reason else null }
        val self = WatchMember.selfDescription(actions, unavailable)
        assertFalse("heart_rate" in names(self, "actions"))
        val u = self["unavailable"]!!.jsonArray.single().jsonObject
        assertEquals("heart_rate", u["name"]!!.jsonPrimitive.content)
        assertEquals(reason, u["reason"]!!.jsonPrimitive.content)
    }

    @Test
    fun `nothing missing means no unavailable list at all`() {
        val self = WatchMember.selfDescription(WatchMember.SUPPORTED_ACTIONS, WatchMember.unavailable { null })
        assertFalse("unavailable" in self.keys)
    }

    @Test
    fun `the four declarations are what this watch really does`() {
        val d = WatchMember.selfDescription(WatchMember.SUPPORTED_ACTIONS)["declares"]!!.jsonObject
        assertTrue(d["answers_decisions"]!!.jsonPrimitive.boolean)
        assertFalse(d["shows_request_progress"]!!.jsonPrimitive.boolean)
        assertEquals("", d["hosts_agent"]!!.jsonPrimitive.content)
        assertEquals("embedded", d["tailnet"]!!.jsonPrimitive.content)
    }
}
