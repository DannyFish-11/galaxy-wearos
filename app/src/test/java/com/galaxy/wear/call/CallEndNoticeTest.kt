package com.galaxy.wear.call

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 通话失败的原因必须让人看见；正常挂断什么都不该说。
 *
 * 原先的两个反面：自己挂断时原因是 `user_hangup`，被当成失败原样写在屏幕上；真正的失败
 * （网关没连、没配语音后端）因为服务马上停、控制器置空，原因跟着消失，用户只看到
 * 「点了通话，屏幕闪一下又回来了」。
 */
class CallEndNoticeTest {

    @Test
    fun `normal ends say nothing`() {
        for (r in listOf("", "user_hangup", "通话已结束", "已释放")) {
            assertNull(r, CallEndNotice.of(r))
        }
    }

    @Test
    fun `machine codes from the gateway are put in words`() {
        assertEquals("网关还不认这块表，先在设置里重新配对", CallEndNotice.of("unauthenticated"))
        assertEquals("与网关的连接断了", CallEndNotice.of("connection_closed"))
        assertEquals("通话协商失败，稍后再试", CallEndNotice.of("sdp_negotiation_failed"))
        assertEquals("通话链路中断", CallEndNotice.of("webrtc_failed"))
        assertEquals("通话链路中断", CallEndNotice.of("webrtc_closed"))
    }

    @Test
    fun `reasons that are already words are passed through`() {
        val why = "没有可用的实时语音后端(未配置 provider key,或建连失败)"
        assertEquals(why, CallEndNotice.of(why))
        assertNotNull(CallEndNotice.of("网关未连接,先连上再拨"))
    }

    // ── 接线：服务把原因留下来，界面读得到 ───────────────────────────────

    private fun source(relative: String): String {
        val candidates = listOf(File(relative), File("app/$relative"), File("../app/$relative"))
        return candidates.firstOrNull { it.isFile }?.readText()
            ?: throw AssertionError("找不到 $relative。试过:" + candidates.joinToString { it.absolutePath })
    }

    @Test
    fun `the service keeps the failure before it disposes the controller`() {
        val svc = source("src/main/java/com/galaxy/wear/call/VoiceCallService.kt")
        val onDestroy = svc.substringAfter("override fun onDestroy()")
        val keep = onDestroy.indexOf("_lastFailure.value = it")
        val dispose = onDestroy.indexOf("_controller.value?.dispose()")
        assertTrue("onDestroy 里没找到留下失败原因的那一行", keep >= 0)
        assertTrue("dispose() 会把原因覆盖成「已释放」，必须先取原因", dispose > keep)
        assertTrue("没连上网关就起不了通话，这条原因也要留", svc.contains("手表还没连上网关"))
        assertTrue("新一通开始要清掉上一通的提示", svc.contains("_lastFailure.value = null"))
    }

    @Test
    fun `the screen shows the kept failure and goes back only on a normal end`() {
        val screen = source("src/main/java/com/galaxy/wear/ui/screens/CallScreen.kt")
        assertTrue(screen.contains("VoiceCallService.lastFailure"))
        assertTrue(screen.contains("CallEndNotice.of(ui.endedReason) == null"))
        assertTrue("离开这一屏要收起旧提示", screen.contains("VoiceCallService.clearFailure()"))
    }
}
