package com.galaxy.wear.service

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 一条普通消息不许用闹钟的手感推过来。
 *
 * 决策通知（HITL）是刻意做成打扰式的：`IMPORTANCE_HIGH` + `CATEGORY_ALARM` +
 * 词汇表里最重的那条"等距三拍"。它的语义是**停下手里的事，等你拿主意**。
 *
 * 而智能体主动发来的消息就是平常手表上那种推送。两者共用一条渠道的话，用户不看
 * 屏幕就分不出哪条是真要他决定的 —— 那恰恰是决策渠道存在的全部理由。更实际的
 * 代价是免打扰：`CATEGORY_ALARM` 会让系统在深夜也放行，一条"快递到了"就能把人吵醒。
 *
 * 这条守卫判读源码本身，而不是起 Android 运行时：要钉的是**声明**（用了哪条渠道、
 * 报了哪个类别），运行期行为要真机才能证，那不是单测该承诺的事 —— 与
 * `PhaseVisualIsTheSingleAuthorityTest` 的做法一致。
 */
class AgentMessageNotificationIsNotAnAlarmTest {

    private fun source(relative: String): String {
        val candidates = listOf(
            File(relative),
            File("app/$relative"),
            File("../app/$relative"),
        )
        return candidates.firstOrNull { it.isFile }?.readText()
            ?: throw AssertionError(
                "找不到 $relative。试过：" + candidates.joinToString { it.absolutePath } +
                    "。这里刻意不跳过 —— 一个『找不到就当通过』的守卫等于没有守卫。"
            )
    }

    private val service: String by lazy {
        source("src/main/java/com/galaxy/wear/service/GalaxyWearService.kt")
    }

    /** 只取 showAgentMessageNotification 那一段，避免把决策通知的声明算进来。 */
    private val agentMessageBody: String by lazy {
        val start = service.indexOf("fun showAgentMessageNotification(")
        assertTrue("找不到 showAgentMessageNotification —— 消息通知路径不见了", start >= 0)
        service.substring(start)
    }

    @Test
    fun `消息通知用自己的渠道，不蹭决策渠道`() {
        assertTrue(
            "消息通知没有用 CHANNEL_ID_MESSAGES",
            agentMessageBody.contains("CHANNEL_ID_MESSAGES"),
        )
        assertFalse(
            "消息通知蹭了决策渠道 —— 那条是 IMPORTANCE_HIGH，会让普通消息用闹钟的手感推过来",
            agentMessageBody.contains("CHANNEL_ID_DECISIONS"),
        )
    }

    @Test
    fun `消息通知报的是消息类别，不是闹钟`() {
        // CATEGORY_ALARM 会让系统在免打扰时段也放行。一条"快递到了"不该把人在深夜吵醒。
        assertTrue(
            "消息通知没有报 CATEGORY_MESSAGE",
            agentMessageBody.contains("CATEGORY_MESSAGE"),
        )
        assertFalse(
            "消息通知报成了 CATEGORY_ALARM —— 免打扰时段会被放行",
            agentMessageBody.contains("CATEGORY_ALARM"),
        )
    }

    @Test
    fun `消息渠道的重要性是 DEFAULT 而不是 HIGH`() {
        val channelBlock = service.substringAfter("CHANNEL_ID_MESSAGES,").substringBefore("nm.createNotificationChannel(messageChannel)")
        assertTrue(
            "消息渠道不是 IMPORTANCE_DEFAULT：$channelBlock",
            channelBlock.contains("IMPORTANCE_DEFAULT"),
        )
        assertFalse(
            "消息渠道被提到了 IMPORTANCE_HIGH —— 那是决策才该有的规格",
            channelBlock.contains("IMPORTANCE_HIGH"),
        )
    }

    @Test
    fun `消息渠道用的是消息到达那条触感`() {
        // Android O+ 上振动由**渠道**决定，Builder 上的 setVibrate 会被忽略。
        // 不接这一行的话，消息用的是系统默认振动，和决策提醒分不出来。
        val channelBlock = service.substringAfter("CHANNEL_ID_MESSAGES,").substringBefore("nm.createNotificationChannel(messageChannel)")
        assertTrue(
            "消息渠道没有接词汇表里的 MESSAGE_ARRIVAL",
            channelBlock.contains("HapticType.MESSAGE_ARRIVAL"),
        )
        assertFalse(
            "消息渠道用了决策的触感 —— 手腕上就分不出哪条要拿主意",
            channelBlock.contains("HapticType.DECISION_PROMPT"),
        )
    }

    @Test
    fun `消息回复走 voice_query，不冒充决策回复`() {
        // 两者上行完全不同：决策回复是 human_input（带 decision_id，服务端要拿它匹配
        // 那次挂起的决策）。消息回复带一个不存在的 decision_id 上去，服务端匹配不到、
        // 静默丢弃，而手表这边看起来"已经回了"。
        val receiver = source("src/main/java/com/galaxy/wear/receiver/ReplyReceiver.kt")
        val messageReplyBody = receiver.substringAfter("private fun handleMessageReply(")

        assertTrue(
            "消息回复没有走 sendVoiceQuery —— 那条路顺带会把这句话记进会话上下文",
            messageReplyBody.contains("sendVoiceQuery"),
        )
        assertFalse(
            "消息回复发成了 human_input —— 服务端会因为匹配不到 decision_id 而静默丢弃",
            messageReplyBody.contains("human_input"),
        )
    }
}
