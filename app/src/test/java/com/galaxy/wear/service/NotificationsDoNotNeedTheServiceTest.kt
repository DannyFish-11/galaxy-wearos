package com.galaxy.wear.service

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 智能体的提问、消息能不能到手腕上,不取决于前台服务在不在跑。
 *
 * 此前 Application 收到决策 / 消息时靠 `startForegroundService` 去请服务来弹通知。手表在后台时
 * (收到网关消息几乎总是后台)Android 12+ 不许应用启动前台服务,抛
 * `ForegroundServiceStartNotAllowedException`,被外层 `catch` 吞掉,只在日志留一行 ——
 * 智能体问「要不要接入这盏灯」,手表上什么都没有,也没有任何报错。
 *
 * 另一处同样安静的缺陷:带 `RemoteInput` 的内联回复用了 `FLAG_IMMUTABLE`,系统填不进用户输入的
 * 文字,通知上的「回复」点得开、输得进、发得出,收到的却是空回复。
 *
 * 编译器和运行期都不报这两类问题,只有戴着表试一遍才发现,所以钉在源码上(与本目录其它守卫一致)。
 */
class NotificationsDoNotNeedTheServiceTest {

    private fun source(relative: String): String {
        val candidates = listOf(File(relative), File("app/$relative"), File("../app/$relative"))
        return candidates.firstOrNull { it.isFile }?.readText()
            ?: throw AssertionError(
                "找不到 $relative。试过:" + candidates.joinToString { it.absolutePath } +
                    "。这里刻意不跳过 —— 一个『找不到就当通过』的守卫等于没有守卫。"
            )
    }

    private val app by lazy { source("src/main/java/com/galaxy/wear/GalaxyWearApplication.kt") }
    private val notifications by lazy { source("src/main/java/com/galaxy/wear/service/WatchNotifications.kt") }
    private val service by lazy { source("src/main/java/com/galaxy/wear/service/GalaxyWearService.kt") }

    @Test
    fun `Application shows decisions and messages itself instead of asking the service`() {
        assertFalse(
            "Application 又在用 startForegroundService 请服务来弹通知 —— 后台时会被系统拒绝并被吞掉",
            app.contains("startForegroundService"),
        )
        assertTrue(app.contains("WatchNotifications.showDecisionNotification("))
        assertTrue(app.contains("WatchNotifications.showAgentMessageNotification("))
    }

    @Test
    fun `the service no longer carries notification actions that nothing sends`() {
        assertFalse(service.contains("ACTION_SHOW_DECISION"))
        assertFalse(service.contains("ACTION_SHOW_MESSAGE"))
        assertFalse(service.contains("fun showDecisionNotification("))
        assertFalse(service.contains("fun showAgentMessageNotification("))
    }

    /** `PendingIntent.getBroadcast(` 之后到它自己的右括号（单独一行）之间的实参文本。 */
    private fun argsAfter(text: String, marker: String): String {
        val rest = text.substringAfter(marker)
        val close = Regex("\\n\\s*\\)").find(rest)?.range?.first ?: rest.length
        return rest.substring(0, close)
    }

    @Test
    fun `inline replies carry a mutable pending intent and plain buttons stay immutable`() {
        // 每个 RemoteInput 对应的 PendingIntent 必须用 inlineReplyFlags()（可变）
        val decisionReply = argsAfter(notifications, "val replyPending = PendingIntent.getBroadcast(")
        assertTrue("决策回复的 PendingIntent 不是可变的:$decisionReply", decisionReply.contains("inlineReplyFlags()"))
        val messageReply = argsAfter(
            notifications.substringAfter("fun showAgentMessageNotification("),
            "val replyPending = PendingIntent.getBroadcast(",
        )
        assertTrue("消息回复的 PendingIntent 不是可变的:$messageReply", messageReply.contains("inlineReplyFlags()"))
        // 选项按钮没有 RemoteInput,保持 IMMUTABLE
        val optionPending = argsAfter(notifications, "val optionPending = PendingIntent.getBroadcast(")
        assertTrue("选项按钮的 PendingIntent 不该可变:$optionPending", optionPending.contains("fixedFlags()"))
        // 可变的那一个版本用的是 FLAG_MUTABLE,并且 API 31 以下不引用它
        assertTrue(notifications.contains("PendingIntent.FLAG_MUTABLE"))
        assertTrue(notifications.contains("Build.VERSION_CODES.S"))
        // 全文件里不许再出现 IMMUTABLE 之外的「不可变 + RemoteInput」组合：IMMUTABLE 只在 fixedFlags() 里出现一次
        assertEquals(1, Regex("PendingIntent\\.FLAG_IMMUTABLE").findAll(notifications).count())
    }

    @Test
    fun `a notification the system will not show is reported not claimed as delivered`() {
        assertTrue(
            "通知有没有可能被看到必须如实返回(权限被拒时系统静默丢弃)",
            notifications.contains("areNotificationsEnabled()"),
        )
    }

    @Test
    fun `a reply to an agent message goes back into that conversation`() {
        val receiver = source("src/main/java/com/galaxy/wear/receiver/ReplyReceiver.kt")
        val body = receiver.substringAfter("private fun handleMessageReply(")
        assertTrue(
            "回复智能体的消息时没有带上那条消息的 conversation_id —— 这句话会落进手表自己的对话",
            body.contains("sendVoiceQuery(replyText, sessionId = conversationId)"),
        )
    }
}
