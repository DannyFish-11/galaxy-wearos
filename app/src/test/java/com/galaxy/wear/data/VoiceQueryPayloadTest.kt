package com.galaxy.wear.data

import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 手表说的话带不带「接着哪条对话」。
 *
 * 回复智能体发来的消息时,这句话必须落进那条消息所属的对话 —— 否则落进手表自己的对话,
 * 智能体那条会话里看不到你的回复。网关读的是 `payload.session_id`。
 */
class VoiceQueryPayloadTest {

    @Test
    fun `a plain utterance carries no session id so the gateway uses the watchs own conversation`() {
        val p = AipPureLogic.voiceQueryPayload("你好", "")
        assertEquals("你好", p["text"]!!.jsonPrimitive.content)
        assertEquals("wear_os", p["source"]!!.jsonPrimitive.content)
        assertFalse(p.containsKey("session_id"))
    }

    @Test
    fun `a blank session id is treated as absent`() {
        assertFalse(AipPureLogic.voiceQueryPayload("x", "   ").containsKey("session_id"))
    }

    @Test
    fun `a reply to an agent message continues that conversation`() {
        val p = AipPureLogic.voiceQueryPayload("好的,接入吧", "session_ab12cd34ef56")
        assertEquals("session_ab12cd34ef56", p["session_id"]!!.jsonPrimitive.content)
    }
}
