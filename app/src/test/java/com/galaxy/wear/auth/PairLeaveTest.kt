package com.galaxy.wear.auth

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 解除配对：退出是这块表自己的事。网关怎么回都要算得清，本机都要真的忘掉这次配对。
 */
class PairLeaveTest {

    @Test
    fun `the gateway's answers map to three outcomes`() {
        assertEquals(PairLeave.Outcome.LEFT, PairLeave.outcomeOf(200, true))
        assertEquals(PairLeave.Outcome.ALREADY_GONE, PairLeave.outcomeOf(401, false))
        assertEquals(PairLeave.Outcome.ALREADY_GONE, PairLeave.outcomeOf(403, null))
        // 200 却没说成功、5xx、解不出 —— 都不能当成「那边已移除」
        assertEquals(PairLeave.Outcome.UNREACHABLE, PairLeave.outcomeOf(200, null))
        assertEquals(PairLeave.Outcome.UNREACHABLE, PairLeave.outcomeOf(500, false))
    }

    @Test
    fun `every outcome tells the wearer the watch has left`() {
        for (o in PairLeave.Outcome.values()) {
            assertTrue(o.name, PairLeave.text(o).contains("已解除配对"))
        }
        assertTrue(PairLeave.text(PairLeave.Outcome.UNREACHABLE).contains("面板"))
    }

    @Test
    fun `the path is the one the gateway exempts from auth`() {
        assertEquals("/api/v1/pair/leave", PairLeave.PATH)
    }

    private fun source(relative: String): String {
        val candidates = listOf(File(relative), File("app/$relative"), File("../app/$relative"))
        return candidates.firstOrNull { it.isFile }?.readText()
            ?: throw AssertionError("找不到 $relative。试过:" + candidates.joinToString { it.absolutePath })
    }

    @Test
    fun `leaving goes through the cleartext-checked url builder and always forgets locally`() {
        val client = source("src/main/java/com/galaxy/wear/auth/PairClaimClient.kt")
        val leave = client.substringAfter("suspend fun leave(").substringBefore("fun forgetPairing()")
        assertTrue(leave.contains("PairUrl.apiUrl(base, PairLeave.PATH)"))
        assertTrue("连不上也要退出：forgetPairing 不能放在成功分支里", leave.substringAfterLast("}").isNotEmpty())
        assertTrue(leave.trimEnd().contains("forgetPairing()\n"))
        val forget = client.substringAfter("fun forgetPairing()").substringBefore("/** 已存下来的令牌")
        for (key in listOf("KEY_GATEWAY_TOKEN", "KEY_TAILNET_AUTH_KEY", "KEY_CANDIDATES", "KEY_LAST_GOOD", "KEY_TAILNET_CONTROL_URL")) {
            assertTrue("忘掉配对时漏了 $key", forget.contains(key))
        }
    }

    @Test
    fun `unpair disconnects, stops the tailnet and is reachable from settings`() {
        val app = source("src/main/java/com/galaxy/wear/GalaxyWearApplication.kt")
        val unpair = app.substringAfter("suspend fun unpair()").substringBefore("fun disconnect()")
        assertTrue(unpair.contains("pairClaimClient.leave("))
        assertTrue(unpair.contains("aipClient.disconnect()"))
        assertTrue(unpair.contains("tailnetDaemon.stop()"))
        assertFalse("续期 / 退出都到不了本机转发口", unpair.contains("kind == \"tailscale\""))
        val settings = source("src/main/java/com/galaxy/wear/ui/screens/SettingsScreen.kt")
        assertTrue(settings.contains("app.unpair()"))
        assertTrue("解除配对要二次确认", settings.contains("再点一次确认解除"))
    }
}
