package com.galaxy.wear.sensing

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **隐私契约** —— 手表只回答"现在能不能打扰他",不交出身体数据。
 *
 * 这是所有者拍板的边界,靠人工审阅守不住(任何人往 [toWirePayload] 里
 * 随手 `put("bpm", ...)` 都能悄悄把心率送上云)。所以这里用**键集合完全相等**
 * 而不是"包含"来钉死:多一个键就红,少一个键也红。
 *
 * 同时钉死 [InterruptibilityBand] 的线上字符串 —— V2 侧要按这些字符串分支,
 * 改动等于跨仓协议漂移。
 */
class InterruptibilityWireContractTest {

    private val sample = InterruptibilityReport(
        score = 0.791666f,
        band = InterruptibilityBand.FREE,
        reasons = listOf(InterruptibilityReason.STILL, InterruptibilityReason.CALM),
        confidence = 0.4166f,
    )

    @Test
    fun `上行键集合必须与允许集合完全相等`() {
        val payload = sample.toWirePayload(timestampMs = 1_700_000_000_000L)

        assertEquals(INTERRUPTIBILITY_WIRE_KEYS, payload.keys)
    }

    @Test
    fun `上行载荷不含任何生物特征字段`() {
        val payload = sample.toWirePayload(timestampMs = 1_700_000_000_000L)
        val serialized = payload.toString().lowercase()

        // 键名与整段序列化文本都查一遍:防止有人把心率塞进 reasons 标签里。
        listOf("bpm", "heart", "hrv", "rmssd", "resting", "sleep_stage", "step", "accel", "motion_intensity")
            .forEach { forbidden ->
                assertTrue(
                    "上行载荷里出现了生物特征字段 '$forbidden':$serialized",
                    !serialized.contains(forbidden),
                )
            }
    }

    @Test
    fun `原因标签只能是粗粒度词,不带任何数值`() {
        InterruptibilityReason.values().forEach { reason ->
            assertTrue(
                "原因标签 ${reason.wire} 含数字 —— 标签不得携带测量值",
                reason.wire.none { it.isDigit() },
            )
        }
    }

    @Test
    fun `分数与置信度只保留两位——多余精度会泄露细微生理波动`() {
        val payload = sample.toWirePayload(timestampMs = 1L)

        assertEquals(0.79f, payload["score"]!!.jsonPrimitive.content.toFloat(), 1e-6f)
        assertEquals(0.42f, payload["confidence"]!!.jsonPrimitive.content.toFloat(), 1e-6f)
    }

    @Test
    fun `档位线上字符串是跨仓协议,改动即漂移`() {
        assertEquals(
            mapOf(
                InterruptibilityBand.UNKNOWN to "unknown",
                InterruptibilityBand.BLOCKED to "blocked",
                InterruptibilityBand.BUSY to "busy",
                InterruptibilityBand.NEUTRAL to "neutral",
                InterruptibilityBand.FREE to "free",
            ),
            InterruptibilityBand.values().associateWith { it.wire },
        )
    }

    @Test
    fun `原因标签按声明顺序原样序列化`() {
        val payload = sample.toWirePayload(timestampMs = 1L)
        val reasons = (payload["reasons"] as JsonArray).map { it.jsonPrimitive.content }

        assertEquals(listOf("still", "calm"), reasons)
    }

    @Test
    fun `设备标识与时间戳由调用方注入,便于确定性验证`() {
        val payload = sample.toWirePayload(deviceId = "wear_os", timestampMs = 42L)

        assertEquals("wear_os", payload["device"]!!.jsonPrimitive.content)
        assertEquals("42", payload["timestamp"]!!.jsonPrimitive.content)
    }

    @Test
    fun `UNKNOWN 与 BLOCKED 在线上必须可区分`() {
        // "不知道"和"明确别打扰"是两回事:前者上游应当忽略,后者上游必须遵守。
        val unknown = InterruptibilityReport(0.5f, InterruptibilityBand.UNKNOWN, emptyList(), 0f)
        val blocked = InterruptibilityReport(0f, InterruptibilityBand.BLOCKED, emptyList(), 1f)

        assertEquals("unknown", unknown.toWirePayload(timestampMs = 1L)["band"]!!.jsonPrimitive.content)
        assertEquals("blocked", blocked.toWirePayload(timestampMs = 1L)["band"]!!.jsonPrimitive.content)
        assertTrue(blocked.isBlocked)
        assertTrue(!unknown.isBlocked)
    }
}
