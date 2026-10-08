package com.galaxy.wear.auth

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 配对令牌 24 小时到期，手表必须在到期前续。
 *
 * 此前手表一次都不续（笔记本客户端早就在续）：24 小时后进 ERROR 且不重试，主页上只显示「静默」，
 * 没有任何一处告诉用户「该重新配对了」。这些接线缺任何一环，表现都一样安静，所以钉在源码上。
 */
class TokenRenewalIsWiredTest {

    private fun source(relative: String): String {
        val candidates = listOf(File(relative), File("app/$relative"), File("../app/$relative"))
        return candidates.firstOrNull { it.isFile }?.readText()
            ?: throw AssertionError("找不到 $relative。试过:" + candidates.joinToString { it.absolutePath })
    }

    private val app by lazy { source("src/main/java/com/galaxy/wear/GalaxyWearApplication.kt") }
    private val client by lazy { source("src/main/java/com/galaxy/wear/auth/PairClaimClient.kt") }
    private val screen by lazy { source("src/main/java/com/galaxy/wear/ui/screens/PairClaimScreen.kt") }

    @Test
    fun `every path that connects renews first`() {
        val connect = app.substringAfter("fun connect(serverUrl: String, token: String, deviceId: String? = null)")
            .substringBefore("fun loginWithToken(")
        assertTrue("connect() 没有先续令牌", connect.contains("freshToken(token)"))
        val callback = app.substringAfter("override fun onAvailable(network: Network)").substringBefore("override fun onLost(")
        assertTrue("网络恢复后的自动重连没有先续令牌", callback.contains("freshToken(savedToken)"))
    }

    @Test
    fun `a token that cannot be renewed tells the user to pair again`() {
        assertTrue(app.contains("val needsRepair: StateFlow<Boolean>"))
        val fresh = app.substringAfter("private suspend fun freshToken(").substringBefore("// FIX(connect)")
        assertTrue(fresh.contains("RenewResult.NeedsRepair"))
        assertTrue("续期失败（断网）不该被当成需要重新配对", fresh.contains("RenewResult.Failed"))
        assertTrue("配对成功必须清掉『需要重新配对』", app.substringAfter("fun loginWithToken(").contains("_needsRepair.value = false"))
    }

    @Test
    fun `only a token that came from pairing is renewed`() {
        val fresh = app.substringAfter("private suspend fun freshToken(")
        assertTrue(fresh.contains("pairClaimClient.storedToken() != connectToken"))
    }

    @Test
    fun `pairing and renewal both go through the cleartext-checked url builder`() {
        assertTrue(client.contains("PairUrl.apiUrl(serverUrl, PATH_CLAIM)"))
        assertTrue(client.contains("PairUrl.apiUrl(base, PATH_RENEW)"))
        assertFalse("又出现了不看明文策略的字面拼接", client.contains("fun buildApiUrl("))
    }

    @Test
    fun `automatic reconnect slows down instead of giving up for good`() {
        val callback = app.substringAfter("override fun onAvailable(network: Network)").substringBefore("override fun onLost(")
        assertFalse("到上限就 return 放弃 —— 放弃之后只能靠用户手动连", callback.contains("giving up auto-reconnect"))
        assertTrue(callback.contains("SLOW_RECONNECT_INTERVAL_MS"))
        assertTrue(
            "自动重连只认 VALIDATED：没有外网出口的家庭局域网永远不会触发",
            callback.contains("NET_CAPABILITY_INTERNET") && !callback.contains("NET_CAPABILITY_VALIDATED"),
        )
    }

    @Test
    fun `the pairing screen says why instead of jumping away when the tailnet did not come up`() {
        assertTrue(screen.contains("PairingMessages.claimErrorText(r.error)"))
        assertTrue(screen.contains("PairingMessages.tailnetNotice(r.tailnetUnavailableReason)"))
    }
}
