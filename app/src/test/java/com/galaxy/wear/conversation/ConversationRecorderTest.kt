package com.galaxy.wear.conversation

import com.galaxy.wear.conversation.ConversationMessage.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 什么算「对话内容」，什么不算。
 *
 * 手表问出去的语音查询，回复是以 `command_result` 回来的 —— 而 `command_result`
 * 同时也是**设备命令**的结果（开灯、锁屏之类）。两者在线上是同一个类型。
 *
 * 所以这里最要紧的一条是：**别把设备命令的结果记成智能体说的话**。那种误记不会
 * 报错，只会让会话里凭空多出一句没人说过的话 —— 而用户下次翻记录时无从分辨。
 */
class ConversationRecorderTest {

    private class FakeStore : ConversationStore.Store {
        var persisted: List<ConversationMessage> = emptyList()
        override fun read() = persisted
        override fun write(messages: List<ConversationMessage>) { persisted = messages }
    }

    private var seq = 0
    private var now = 1_000L

    private fun recorder(maxPending: Int = ConversationRecorder.DEFAULT_MAX_PENDING): Pair<ConversationRecorder, ConversationStore> {
        val store = ConversationStore(FakeStore())
        return ConversationRecorder(
            store = store,
            idFactory = { "id-${++seq}" },
            clock = { ++now },
            maxPendingQueries = maxPending,
        ) to store
    }

    @Test
    fun `一问一答配上对，两条都进会话`() {
        val (r, store) = recorder()

        r.recordUserQuery("提醒我十分钟后喝水", correlationId = "cmd_1")
        r.recordCommandResult("cmd_1", "好，十分钟后提醒你")

        assertEquals(listOf(Role.USER, Role.ASSISTANT), store.all().map { it.role })
        assertEquals(listOf("提醒我十分钟后喝水", "好，十分钟后提醒你"), store.all().map { it.text })
    }

    @Test
    fun `配不上对的 command_result 不是对话内容`() {
        // 这就是那条最要紧的判据：设备命令（开灯、锁屏）的结果也是 command_result。
        // 把它记成智能体说的话，会话里就会凭空多出一句没人说过的话。
        val (r, store) = recorder()

        val recorded = r.recordCommandResult("cmd_设备命令", "已开灯")

        assertNull("没有配对的提问，这条不该进会话", recorded)
        assertTrue(store.all().isEmpty())
    }

    @Test
    fun `同一个相关 id 只能认领一次`() {
        // 服务端重发同一条结果时，不该记成两句回复。
        val (r, store) = recorder()
        r.recordUserQuery("今天天气", correlationId = "cmd_1")

        assertNotNull(r.recordCommandResult("cmd_1", "晴"))
        assertNull("第二次不该再被认领", r.recordCommandResult("cmd_1", "晴"))

        assertEquals(1, store.all().count { it.role == Role.ASSISTANT })
    }

    @Test
    fun `空的相关 id 不认领任何东西`() {
        val (r, _) = recorder()
        r.recordUserQuery("问一句", correlationId = "cmd_1")

        assertNull(r.recordCommandResult("", "答一句"))
    }

    @Test
    fun `配上对但回复是空的，不记一条空消息`() {
        // 服务端出错时会回 success=false 且没有正文。记一条空气泡比不记更糟。
        val (r, store) = recorder()
        r.recordUserQuery("问一句", correlationId = "cmd_1")

        assertNull(r.recordCommandResult("cmd_1", "   "))

        assertEquals(1, store.all().size)
    }

    @Test
    fun `空的提问不记`() {
        val (r, store) = recorder()

        assertNull(r.recordUserQuery("   ", correlationId = "cmd_1"))
        assertTrue(store.all().isEmpty())
    }

    @Test
    fun `等不到回复的提问不会把待配对表撑爆`() {
        // 断网时发出去的查询永远等不到回复。不设上限，这张表只增不减 ——
        // 一条必然发生的泄漏。
        val (r, _) = recorder(maxPending = 3)

        for (i in 1..10) r.recordUserQuery("问题 $i", correlationId = "cmd_$i")

        assertEquals(3, r.pendingCount())
    }

    @Test
    fun `待配对表满了丢最旧的`() {
        // 最旧的那条等回复也等不到了；丢最新的等于刚问完就认领不了。
        val (r, _) = recorder(maxPending = 2)
        r.recordUserQuery("旧问题", correlationId = "cmd_1")
        r.recordUserQuery("中问题", correlationId = "cmd_2")
        r.recordUserQuery("新问题", correlationId = "cmd_3")

        assertNull("最旧的应已被丢弃", r.recordCommandResult("cmd_1", "答"))
        assertNotNull("最新的必须还能认领", r.recordCommandResult("cmd_3", "答"))
    }

    @Test
    fun `智能体主动发的消息不需要配对`() {
        // 这正是 agent_message 这条协议类型存在的理由：它不回答任何提问。
        val (r, store) = recorder()

        val recorded = r.recordAgentMessage("快递到楼下了", conversationId = "c1", messageId = "srv-1")

        assertNotNull(recorded)
        assertEquals(Role.ASSISTANT, store.all().single().role)
    }

    @Test
    fun `主动消息用服务端 id 去重`() {
        // 断线重连后服务端可能补发同一条。用它自己的 id 才去得掉重 ——
        // 本地生成 id 的话，补发会变成两条一模一样的通知。
        val (r, store) = recorder()

        r.recordAgentMessage("快递到楼下了", messageId = "srv-1")
        val again = r.recordAgentMessage("快递到楼下了", messageId = "srv-1")

        assertNull("同一条补发不该记第二次", again)
        assertEquals(1, store.all().size)
    }

    @Test
    fun `没有服务端 id 时退回本地生成`() {
        // 这时补发会重复，但那也好过整条丢掉 —— 用户至少看得到消息。
        val (r, store) = recorder()

        assertNotNull(r.recordAgentMessage("一条没有 id 的消息"))
        assertEquals(1, store.all().size)
    }

    @Test
    fun `主动消息带着会话号归拢`() {
        // 跨设备看到同一串上下文的依据。
        val (r, store) = recorder()

        r.recordUserQuery("问一句", correlationId = "cmd_1", conversationId = "c9")
        r.recordCommandResult("cmd_1", "答一句")
        r.recordAgentMessage("再补一句", conversationId = "c9", messageId = "srv-1")

        assertEquals(3, store.conversation("c9").size)
    }
}
