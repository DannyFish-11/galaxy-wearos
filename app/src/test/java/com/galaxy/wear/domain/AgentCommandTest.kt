package com.galaxy.wear.domain

import com.galaxy.wear.data.WatchMember
import com.galaxy.wear.sensing.StepDay
import com.galaxy.wear.ui.HapticType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 智能体下发给手表的动作:解析、白名单、幂等、输入上限、心率与步数。
 *
 * 手表是「成员」——只响应。能做的动作是封闭清单,清单之外回失败并说明原因(忽略 = 智能体白等 15 秒)。
 */
class AgentCommandTest {

    private class FakeEffects : WatchEffects {
        val notified = mutableListOf<List<Any>>()
        val buzzed = mutableListOf<HapticType>()
        var notifyResult = true
        var hapticResult = true
        var throwOnNotify = false
        var statusCalls = 0

        override fun notify(title: String, text: String, replyExpected: Boolean, conversationId: String): Boolean {
            if (throwOnNotify) throw IllegalStateException("boom")
            notified += listOf(title, text, replyExpected, conversationId)
            return notifyResult
        }

        override fun haptic(type: HapticType): Boolean {
            buzzed += type
            return hapticResult
        }

        override fun status(): JsonObject {
            statusCalls++
            return buildJsonObject { put("connected", true) }
        }

        var heartRate: List<Float> = listOf(70f, 72f, 71f)
        var heartRateUnavailable: String? = null
        var heartRateGate: CompletableDeferred<Unit>? = null
        var measurements = 0
        override suspend fun heartRateSamples(): List<Float> {
            heartRateUnavailable?.let { throw WatchActionUnavailable(it) }
            measurements++
            heartRateGate?.await()
            return heartRate
        }

        var steps: StepDay? = StepDay("2026-10-25", baseline = 1_000, carried = 0, last = 4_400, sinceMs = 1_000L)
        var stepsUnavailable: String? = null
        override fun stepsToday(): StepDay? {
            stepsUnavailable?.let { throw WatchActionUnavailable(it) }
            return steps
        }
    }

    /** 执行器的 execute 是挂起的(测心率要几秒);单测里就地等它做完。 */
    private fun AgentCommandExecutor.exec(c: AgentCommand): AgentCommandResult = runBlocking { execute(c) }

    private fun cmd(id: String, action: String, vararg params: Pair<String, Any>) = AgentCommand(
        id,
        action,
        buildJsonObject {
            for ((k, v) in params) when (v) {
                is Boolean -> put(k, v)
                is Number -> put(k, v)
                else -> put(k, v.toString())
            }
        },
    )

    // ── 解析 ───────────────────────────────────────────────

    @Test
    fun `parses the frame the gateway sends`() {
        val frame = buildJsonObject {
            put("type", "command")
            put("command_id", "cmd_ab12")
            put("command", "notify")
            put("params", buildJsonObject { put("text", "hi") })
        }
        val c = AgentCommandParser.parse(frame)
        assertNotNull(c)
        assertEquals("cmd_ab12", c!!.commandId)
        assertEquals("notify", c.action)
        assertEquals("hi", (c.params["text"] as JsonPrimitive).content)
    }

    @Test
    fun `a frame without a command id cannot be answered so it is not parsed`() {
        assertNull(AgentCommandParser.parse(buildJsonObject { put("command", "notify") }))
        assertNull(AgentCommandParser.parse(buildJsonObject { put("command_id", "  "); put("command", "notify") }))
    }

    @Test
    fun `a frame without an action is not parsed and missing params default to empty`() {
        assertNull(AgentCommandParser.parse(buildJsonObject { put("command_id", "c1") }))
        val c = AgentCommandParser.parse(buildJsonObject { put("command_id", "c1"); put("command", "get_status") })
        assertTrue(c!!.params.isEmpty())
    }

    // ── notify ─────────────────────────────────────────────

    @Test
    fun `notify shows the message and says it was delivered`() {
        val fx = FakeEffects()
        val r = AgentCommandExecutor(fx).exec(
            cmd("c1", "notify", "text" to "构建跑完了", "title" to "任务完成", "reply_expected" to true, "conversation_id" to "conv-9"),
        )
        assertTrue(r.error, r.success)
        assertEquals(listOf<List<Any>>(listOf("任务完成", "构建跑完了", true, "conv-9")), fx.notified)
        assertEquals(JsonPrimitive(true), r.data["delivered"])
    }

    @Test
    fun `notify without text is refused with a reason`() {
        val fx = FakeEffects()
        val r = AgentCommandExecutor(fx).exec(cmd("c1", "notify", "text" to "   "))
        assertFalse(r.success)
        assertTrue(r.error!!.contains("text"))
        assertTrue(fx.notified.isEmpty())
    }

    @Test
    fun `overlong text and title are refused instead of silently cut`() {
        val fx = FakeEffects()
        val ex = AgentCommandExecutor(fx)
        assertFalse(ex.exec(cmd("c1", "notify", "text" to "x".repeat(AgentCommandExecutor.MAX_TEXT + 1))).success)
        assertFalse(ex.exec(cmd("c2", "notify", "text" to "ok", "title" to "t".repeat(AgentCommandExecutor.MAX_TITLE + 1))).success)
        assertTrue(fx.notified.isEmpty())
        assertTrue(ex.exec(cmd("c3", "notify", "text" to "x".repeat(AgentCommandExecutor.MAX_TEXT))).success)
    }

    @Test
    fun `a notification the system did not show is reported as a failure not a success`() {
        val fx = FakeEffects().apply { notifyResult = false }
        val r = AgentCommandExecutor(fx).exec(cmd("c1", "notify", "text" to "hi"))
        assertFalse(r.success)
        assertTrue(r.error!!.contains("通知"))
    }

    @Test
    fun `reply is not expected unless the agent says so`() {
        val fx = FakeEffects()
        AgentCommandExecutor(fx).exec(cmd("c1", "notify", "text" to "只是告诉你一声"))
        assertEquals(false, fx.notified.single()[2])
    }

    // ── haptic ─────────────────────────────────────────────

    @Test
    fun `haptic defaults to the message arrival feel`() {
        val fx = FakeEffects()
        val r = AgentCommandExecutor(fx).exec(cmd("c1", "haptic"))
        assertTrue(r.success)
        assertEquals(listOf(HapticType.MESSAGE_ARRIVAL), fx.buzzed)
        assertEquals(JsonPrimitive("message_arrival"), r.data["pattern"])
    }

    @Test
    fun `every alerting pattern is allowed and matched case-insensitively`() {
        val fx = FakeEffects()
        val ex = AgentCommandExecutor(fx)
        HapticType.values().filter { it.alerting }.forEachIndexed { i, t ->
            assertTrue(t.name, ex.exec(cmd("c$i", "haptic", "pattern" to t.name.uppercase())).success)
        }
        assertEquals(HapticType.values().count { it.alerting }, fx.buzzed.size)
    }

    @Test
    fun `the agent cannot trigger the feedback that belongs to the wearers own actions`() {
        val fx = FakeEffects()
        val ex = AgentCommandExecutor(fx)
        for (t in HapticType.values().filter { !it.alerting }) {
            val r = ex.exec(cmd("c_${t.name}", "haptic", "pattern" to t.name.lowercase()))
            assertFalse(t.name, r.success)
        }
        assertTrue(fx.buzzed.isEmpty())
    }

    @Test
    fun `an unknown pattern is refused and the reply lists what is allowed`() {
        val r = AgentCommandExecutor(FakeEffects()).exec(cmd("c1", "haptic", "pattern" to "earthquake"))
        assertFalse(r.success)
        assertTrue(r.error!!.contains("message_arrival"))
    }

    @Test
    fun `a watch without a vibrator says so`() {
        val fx = FakeEffects().apply { hapticResult = false }
        assertFalse(AgentCommandExecutor(fx).exec(cmd("c1", "haptic")).success)
    }

    // ── get_status / 未知动作 ───────────────────────────────

    @Test
    fun `get_status returns what the watch reports`() {
        val fx = FakeEffects()
        val r = AgentCommandExecutor(fx).exec(cmd("c1", "get_status"))
        assertTrue(r.success)
        assertEquals(JsonPrimitive(true), r.data["connected"])
    }

    @Test
    fun `an action outside the closed list is refused with the list`() {
        val r = AgentCommandExecutor(FakeEffects()).exec(cmd("c1", "format_disk"))
        assertFalse(r.success)
        assertTrue(r.error!!.contains("format_disk"))
        for (a in WatchMember.SUPPORTED_ACTIONS) assertTrue(a, r.error!!.contains(a))
    }

    @Test
    fun `every advertised action is actually implemented`() {
        val ex = AgentCommandExecutor(FakeEffects())
        for ((i, a) in WatchMember.SUPPORTED_ACTIONS.withIndex()) {
            val r = ex.exec(cmd("adv$i", a, "text" to "x"))
            assertFalse("$a is advertised but the executor says it is unsupported: ${r.error}", r.error.orEmpty().contains("不支持动作"))
        }
    }

    // ── 幂等与健壮 ─────────────────────────────────────────

    @Test
    fun `a redelivered command runs once and gets the same answer`() {
        val fx = FakeEffects()
        val ex = AgentCommandExecutor(fx)
        val first = ex.exec(cmd("dup", "notify", "text" to "一次就够"))
        val second = ex.exec(cmd("dup", "notify", "text" to "一次就够"))
        assertEquals(first, second)
        assertEquals(1, fx.notified.size)
    }

    @Test
    fun `the memory of finished commands is bounded`() {
        val fx = FakeEffects()
        val ex = AgentCommandExecutor(fx, rememberLast = 3)
        for (i in 1..4) ex.exec(cmd("c$i", "haptic"))
        assertEquals(4, fx.buzzed.size)
        ex.exec(cmd("c4", "haptic")) // 最近的还记得 → 不重放
        assertEquals(4, fx.buzzed.size)
        ex.exec(cmd("c1", "haptic")) // 最旧的已被挤出 → 会再执行
        assertEquals(5, fx.buzzed.size)
    }

    @Test
    fun `a failure inside an effect becomes a failed result not a crash`() {
        val fx = FakeEffects().apply { throwOnNotify = true }
        val r = AgentCommandExecutor(fx).exec(cmd("c1", "notify", "text" to "hi"))
        assertFalse(r.success)
        assertTrue(r.error!!.contains("boom"))
    }

    // ── 心率 ───────────────────────────────────────────────

    @Test
    fun `heart rate comes back as the middle reading with how many there were and when`() {
        val fx = FakeEffects().apply { heartRate = listOf(70f, 74f, 71.6f, 72f, 69f) }
        val r = AgentCommandExecutor(fx, now = { 1_234L }).exec(cmd("c1", "heart_rate"))
        assertTrue(r.error, r.success)
        assertEquals(JsonPrimitive(72), r.data["bpm"])
        assertEquals(JsonPrimitive(5), r.data["samples"])
        assertEquals(JsonPrimitive(1_234L), r.data["measured_at"])
    }

    @Test
    fun `readings a loose watch makes up are dropped and none at all is a failure that says why`() {
        val fx = FakeEffects().apply { heartRate = listOf(0f, 3f, 400f, 66f) }
        val r = AgentCommandExecutor(fx).exec(cmd("c1", "heart_rate"))
        assertEquals(JsonPrimitive(66), r.data["bpm"])
        assertEquals(JsonPrimitive(1), r.data["samples"])

        fx.heartRate = listOf(0f, 3f)
        val none = AgentCommandExecutor(fx).exec(cmd("c2", "heart_rate"))
        assertFalse(none.success)
        assertTrue(none.error!!, none.error!!.contains("没戴好"))
    }

    @Test
    fun `a watch that cannot measure says the real reason not that something broke`() {
        val fx = FakeEffects().apply { heartRateUnavailable = "戴表的人没给手表「身体传感器」权限,测不了心率" }
        val r = AgentCommandExecutor(fx).exec(cmd("c1", "heart_rate"))
        assertFalse(r.success)
        assertEquals("戴表的人没给手表「身体传感器」权限,测不了心率", r.error)
        assertEquals(0, fx.measurements)
    }

    @Test
    fun `a command resent while the heart rate is still being measured waits for that one measurement`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val fx = FakeEffects().apply { heartRateGate = gate }
        val ex = AgentCommandExecutor(fx)
        val first = async { ex.execute(cmd("hr", "heart_rate")) }
        yield()
        val resent = async { ex.execute(cmd("hr", "heart_rate")) }
        yield()
        gate.complete(Unit)
        assertEquals(first.await(), resent.await())
        assertEquals(1, fx.measurements)
        assertTrue(first.await().success)
    }

    // ── 步数 ───────────────────────────────────────────────

    @Test
    fun `steps come back with the day and when counting started`() {
        val r = AgentCommandExecutor(FakeEffects()).exec(cmd("c1", "steps"))
        assertTrue(r.error, r.success)
        assertEquals(JsonPrimitive(3_400L), r.data["steps"])
        assertEquals(JsonPrimitive("2026-10-25"), r.data["date"])
        assertEquals(JsonPrimitive(1_000L), r.data["counting_since"])
    }

    @Test
    fun `no count yet today is a failure with a reason not zero steps`() {
        val fx = FakeEffects().apply { steps = null }
        val r = AgentCommandExecutor(fx).exec(cmd("c1", "steps"))
        assertFalse(r.success)
        assertTrue(r.error!!.contains("还没记到步数"))
    }

    @Test
    fun `a watch without the activity permission says so`() {
        val fx = FakeEffects().apply { stepsUnavailable = "戴表的人没给手表「体能活动」权限,读不了步数" }
        val r = AgentCommandExecutor(fx).exec(cmd("c1", "steps"))
        assertFalse(r.success)
        assertEquals("戴表的人没给手表「体能活动」权限,读不了步数", r.error)
    }
}
