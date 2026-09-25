package com.galaxy.wear.service

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 智能体给的通知标题要一路到达通知。
 *
 * 原先 AIPClient 收到 agent_message 后重新拼了一份 payload 往上抛,里面只有
 * text / conversation_id / message_id —— 标题在这一步被丢掉,而下游通知读的正是
 * payload["title"]。结果智能体给什么标题都显示不出来,且不报任何错。
 */
class AgentMessageTitleReachesTheNotificationTest {

    private fun src(rel: String): String {
        val roots = listOf(File("src/main/java"), File("app/src/main/java"), File("../app/src/main/java"))
        val root = roots.firstOrNull { it.isDirectory }
            ?: throw AssertionError("找不到 src/main/java —— 这里刻意不跳过")
        return File(root, rel).readText()
    }

    @Test
    fun `the client forwards the title it received`() {
        val branch = src("com/galaxy/wear/data/AIPClient.kt")
            .substringAfter("\"agent_message\" -> {").substringBefore("\"event\" -> {")
        assertTrue("没从报文里读标题", branch.contains("json[\"title\"]"))
        assertTrue("往上抛的 payload 里没带标题", branch.contains("put(\"title\", title)"))
    }

    @Test
    fun `the notification reads the title from that payload`() {
        val app = src("com/galaxy/wear/GalaxyWearApplication.kt")
        assertTrue(app.contains("EXTRA_MESSAGE_TITLE, payload[\"title\"]"))
    }
}
