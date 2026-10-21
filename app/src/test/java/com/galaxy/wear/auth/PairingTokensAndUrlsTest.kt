package com.galaxy.wear.auth

import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 配对请求往哪儿发、令牌什么时候该续 —— 都是纯逻辑,不需要 Android。
 */
class PairingTokensAndUrlsTest {

    private fun token(expSeconds: Double): String {
        val payload = """{"jti":"abc","sub":"w-1","scopes":["device:status"],"iat":1.0,"exp":$expSeconds}"""
        val b64 = Base64.getUrlEncoder().withoutPadding().encodeToString(payload.toByteArray())
        return "galaxy.$b64.sig"
    }

    // ── URL ────────────────────────────────────────────────

    @Test
    fun `a websocket url with a path becomes the http base without the path`() {
        assertEquals("http://192.168.1.5:9000", PairUrl.httpBase("ws://192.168.1.5:9000/ws/device/abc"))
        assertEquals(
            "http://192.168.1.5:9000/api/v1/pair/renew",
            PairUrl.apiUrl("ws://192.168.1.5:9000/ws/device/abc", "/api/v1/pair/renew"),
        )
    }

    @Test
    fun `a bare lan address without a scheme is accepted`() {
        assertEquals("http://192.168.1.5:9000", PairUrl.httpBase("192.168.1.5:9000"))
    }

    @Test
    fun `tls stays tls and trailing slashes and queries are dropped`() {
        assertEquals("https://galaxy.example.com", PairUrl.httpBase("wss://galaxy.example.com/"))
        assertEquals("https://galaxy.example.com:8443", PairUrl.httpBase("https://galaxy.example.com:8443/x?y=1"))
    }

    @Test
    fun `cleartext to a public address is refused so the pairing code and token never cross it`() {
        assertNull(PairUrl.httpBase("http://8.8.8.8:9000"))
        assertNull(PairUrl.httpBase("ws://example.com:9000/ws/device/abc"))
    }

    @Test
    fun `cleartext to a private or tailnet address is allowed`() {
        assertEquals("http://10.0.0.8:9000", PairUrl.httpBase("http://10.0.0.8:9000"))
        assertEquals("http://100.64.0.1:9000", PairUrl.httpBase("ws://100.64.0.1:9000"))
    }

    @Test
    fun `an empty address has no base`() {
        assertNull(PairUrl.httpBase(""))
        assertNull(PairUrl.httpBase("   "))
        assertNull(PairUrl.apiUrl("", "/api/v1/pair/claim"))
    }

    // ── 令牌到期 ───────────────────────────────────────────

    @Test
    fun `the expiry is read from the token claims`() {
        assertEquals(1_700_000_000_000L, CapabilityTokenExpiry.expiresAtMs(token(1_700_000_000.0)))
    }

    @Test
    fun `a token with plenty of life left is not renewed`() {
        val now = 1_700_000_000_000L
        assertFalse(CapabilityTokenExpiry.shouldRenew(token(1_700_000_000.0 + 20 * 3600), now))
    }

    @Test
    fun `a token close to expiry is renewed`() {
        val now = 1_700_000_000_000L
        assertTrue(CapabilityTokenExpiry.shouldRenew(token(1_700_000_000.0 + 3 * 3600), now))
        assertTrue(CapabilityTokenExpiry.shouldRenew(token(1_700_000_000.0 + 60), now))
    }

    @Test
    fun `an already expired token is not renewable it has to be paired again`() {
        val now = 1_700_000_000_000L
        val t = token(1_700_000_000.0 - 10)
        assertFalse(CapabilityTokenExpiry.shouldRenew(t, now))
        assertTrue(CapabilityTokenExpiry.isExpired(t, now))
    }

    @Test
    fun `a token that is not a capability token is left alone`() {
        val now = 1_700_000_000_000L
        for (t in listOf("static-api-token-0123456789", "a.b", "a.%%%.c", "")) {
            assertNull(t, CapabilityTokenExpiry.expiresAtMs(t))
            assertFalse(t, CapabilityTokenExpiry.shouldRenew(t, now))
            assertFalse(t, CapabilityTokenExpiry.isExpired(t, now))
        }
    }
}
