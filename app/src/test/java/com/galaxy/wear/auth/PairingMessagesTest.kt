package com.galaxy.wear.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 配对结果对人说什么 —— 每一档失败的下一步完全不同，糊成一句「失败」等于没说。
 */
class PairingMessagesTest {

    @Test
    fun `each recoverable failure says what to do next`() {
        assertEquals("错太多次，等几分钟", PairingMessages.claimErrorText("too_many_attempts"))
        assertEquals("连不上网关", PairingMessages.claimErrorText("network_error"))
        assertTrue(PairingMessages.claimErrorText("need_server_address").contains("设置"))
        assertTrue(PairingMessages.claimErrorText("cleartext_not_allowed").contains("内网"))
    }

    @Test
    fun `the generic failure is only for a code that is actually wrong`() {
        for (e in listOf(null, "", "claim_rejected", "something_new")) {
            assertEquals(e, "码无效或已过期", PairingMessages.claimErrorText(e))
        }
        assertEquals("网关回应异常", PairingMessages.claimErrorText("bad_response_502"))
    }

    @Test
    fun `no notice when the gateway handed out a tailnet key`() {
        assertNull(PairingMessages.tailnetNotice(null))
        assertNull(PairingMessages.tailnetNotice(""))
    }

    @Test
    fun `a missing tailnet is explained and the home lan is reassured`() {
        for (reason in listOf(
            "no_headscale_url", "no_api_key", "api_key_rejected", "headscale_unreachable",
            "headscale_error", "no_such_user", "desktop_not_on_tailnet",
        )) {
            val n = PairingMessages.tailnetNotice(reason)
            assertNotNull(reason, n)
            assertTrue(reason, n!!.startsWith("出门直连未开启"))
            assertTrue(reason, n.contains("局域网直连不受影响"))
        }
    }

    @Test
    fun `an unknown reason is shown rather than swallowed`() {
        val n = PairingMessages.tailnetNotice("brand_new_reason")!!
        assertTrue(n.contains("brand_new_reason"))
        assertFalse(n.isBlank())
    }
}
