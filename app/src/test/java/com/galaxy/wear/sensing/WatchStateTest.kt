package com.galaxy.wear.sensing

import com.galaxy.wear.data.WatchMember
import com.ufo.galaxy.shared.protocol.DeviceState
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 这块表此刻的状态(V2 `core/device_state.py` 读),可打扰性并在里面。
 *
 * **隐私契约** —— 手表只回答"现在能不能打扰他",不交出身体数据。靠人工审阅守不住
 * (任何人往状态里随手塞个 `bpm` 都能悄悄把心率送上云),所以钉成测试:
 * 状态的键只能是 [DeviceState] 那几个,原因只有词、没有数值。
 */
class WatchStateTest {

    private val readings = WatchState.Readings(battery = 0.734f, charging = false, screenOn = true, inCall = false)
    private val free = InterruptibilityReport(0.79f, InterruptibilityBand.FREE, listOf(InterruptibilityReason.STILL, InterruptibilityReason.CALM), 0.9f)

    @Test
    fun `the state carries only the keys the center allows`() {
        val allowed = setOf("battery", "charging", "screen_on", "worn", "in_call", "interruptible", "note")
        for (r in InterruptibilityBand.values()) {
            val s = WatchState.build(InterruptibilityReport(0.5f, r, InterruptibilityReason.values().toList(), 1f), readings).toJson()
            assertTrue("多出来的键:${s.keys - allowed}", allowed.containsAll(s.keys))
        }
    }

    @Test
    fun `no body signal leaves the watch, and the reasons are words without numbers`() {
        val everything = InterruptibilityReport(0.123456f, InterruptibilityBand.BUSY, InterruptibilityReason.values().toList(), 0.3f)
        val serialized = WatchState.build(everything, readings).toJson().toString().lowercase()
        listOf("bpm", "heart_rate", "hrv", "rmssd", "resting", "sleep_stage", "step", "accel", "motion_intensity", "score", "confidence")
            .forEach { assertFalse("状态里出现了 '$it':$serialized", serialized.contains(it)) }
        val note = WatchState.build(everything, readings).toJson()["note"]!!.jsonPrimitive.content
        assertTrue("原因里不得带数值:$note", note.none { it.isDigit() })
        assertEquals("每个原因标签都有说法", InterruptibilityReason.values().toSet(), WatchState.REASON_WORDS.keys)
    }

    @Test
    fun `the watch judges whether the person can be interrupted and says why in words`() {
        val s = WatchState.build(free, readings).toJson()
        assertEquals("free", s["interruptible"]!!.jsonPrimitive.content)
        assertEquals("静止、平静", s["note"]!!.jsonPrimitive.content)
        assertEquals(0.73, s["battery"]!!.jsonPrimitive.content.toDouble(), 0.001)
        assertTrue(s["screen_on"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `weak evidence is said in the note`() {
        val weak = free.copy(confidence = 0.3f)
        assertEquals("静止、平静,证据不足", WatchState.note(weak))
        assertEquals("证据不足", WatchState.note(weak.copy(reasons = emptyList())))
        assertNull(WatchState.note(free.copy(reasons = emptyList())))
    }

    @Test
    fun `unknown and blocked are told apart on the wire`() {
        // "不知道"和"明确别打扰"是两回事:前者不报这个键,中心当不知道;后者中心必须遵守
        val unknown = WatchState.build(InterruptibilityReport(0.5f, InterruptibilityBand.UNKNOWN, listOf(InterruptibilityReason.NO_SENSOR_DATA), 0f), readings).toJson()
        val noConfidence = WatchState.build(free.copy(confidence = 0f), readings).toJson()
        val blocked = WatchState.build(InterruptibilityReport(0f, InterruptibilityBand.BLOCKED, listOf(InterruptibilityReason.DO_NOT_DISTURB), 1f), readings).toJson()
        assertFalse("interruptible" in unknown)
        assertFalse("note" in unknown)
        assertFalse("interruptible" in noConfidence)
        assertEquals("blocked", blocked["interruptible"]!!.jsonPrimitive.content)
        assertEquals("开着勿扰", blocked["note"]!!.jsonPrimitive.content)
        assertTrue("电量、屏幕照报", "battery" in unknown && "screen_on" in unknown)
    }

    @Test
    fun `the watch does not claim to know whether it is worn`() {
        assertFalse("worn" in WatchState.build(free, readings).toJson())
    }

    @Test
    fun `band strings are the values the center reads`() {
        assertEquals(
            mapOf("unknown" to null, "blocked" to "blocked", "busy" to "busy", "neutral" to "neutral", "free" to "free"),
            InterruptibilityBand.values().associate { b ->
                b.wire to WatchState.build(InterruptibilityReport(0.5f, b, emptyList(), 1f), readings).toJson()["interruptible"]?.jsonPrimitive?.content
            },
        )
        assertTrue(DeviceState.Interruptible.ALL.containsAll(InterruptibilityBand.values().map { it.wire } - "unknown"))
    }

    @Test
    fun `the state goes up in a heartbeat frame`() {
        val f = WatchMember.stateFrame("watch-1", WatchState.build(free, readings).toJson(), 42L)
        assertEquals("heartbeat", f["type"]!!.jsonPrimitive.content)
        assertEquals("watch-1", f["device_id"]!!.jsonPrimitive.content)
        assertEquals("free", f["payload"]!!.jsonObject["state"]!!.jsonObject["interruptible"]!!.jsonPrimitive.content)
    }

    @Test
    fun `battery is compared by tenths so every percent does not wake the radio`() {
        assertEquals(WatchState.uplinkKey(readings.copy(battery = 0.71f)), WatchState.uplinkKey(readings.copy(battery = 0.74f)))
        assertFalse(WatchState.uplinkKey(readings.copy(battery = 0.71f)) == WatchState.uplinkKey(readings.copy(battery = 0.76f)))
    }
}
