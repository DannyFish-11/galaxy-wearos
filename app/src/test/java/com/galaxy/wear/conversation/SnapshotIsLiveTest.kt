package com.galaxy.wear.conversation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 会话界面是**看着这个快照**画的。
 *
 * 这组测试守的是一件很容易悄悄坏掉的事:界面开着的时候来了一条消息,列表要当场
 * 多出一行。快照不发新值,界面就停在进入那一刻的样子 —— 消息确实收到了、也确实
 * 存下来了、通知也弹了,唯独列表是死的。那种坏法从代码上完全看不出来,只能靠
 * 在表上盯着屏幕等一条消息才发现。所以把它钉在这里。
 */
class SnapshotIsLiveTest {

    private class MemoryStore(
        private var rows: List<ConversationMessage> = emptyList(),
    ) : ConversationStore.Store {
        override fun read(): List<ConversationMessage> = rows
        override fun write(messages: List<ConversationMessage>) {
            rows = messages
        }
    }

    private fun msg(id: String, at: Long, role: ConversationMessage.Role = ConversationMessage.Role.USER) =
        ConversationMessage(id, "c1", role, "内容 $id", at)

    @Test
    fun `构造时快照就等于已经读出来的历史`() {
        val store = ConversationStore(MemoryStore(listOf(msg("b", 2), msg("a", 1))))
        assertEquals(listOf("a", "b"), store.snapshot.value.map { it.id })
    }

    @Test
    fun `追加一条,快照当场就多一条`() {
        val store = ConversationStore(MemoryStore())
        assertTrue(store.snapshot.value.isEmpty())
        store.append(msg("a", 1))
        assertEquals(listOf("a"), store.snapshot.value.map { it.id })
        store.append(msg("b", 2))
        assertEquals(listOf("a", "b"), store.snapshot.value.map { it.id })
    }

    @Test
    fun `每次都是新的一份列表,不是同一个可变对象被改了`() {
        // Compose 的 collectAsState 靠相等性判断要不要重组。若每次发的是同一个
        // 列表实例、只是内容变了,StateFlow 的 equals 去重会把这次变更吃掉,界面不动。
        val store = ConversationStore(MemoryStore())
        store.append(msg("a", 1))
        val first = store.snapshot.value
        store.append(msg("b", 2))
        val second = store.snapshot.value
        assertNotSame(first, second)
        assertEquals(1, first.size) // 旧的那份没有被就地改掉
        assertEquals(2, second.size)
    }

    @Test
    fun `重复 id 被丢掉时,快照不该动`() {
        val store = ConversationStore(MemoryStore())
        store.append(msg("a", 1))
        val before = store.snapshot.value
        assertTrue(!store.append(msg("a", 9)))
        assertSame(before, store.snapshot.value)
    }

    @Test
    fun `清空后快照是空的`() {
        val store = ConversationStore(MemoryStore())
        store.append(msg("a", 1))
        store.clear()
        assertTrue(store.snapshot.value.isEmpty())
    }

    @Test
    fun `快照和 all 永远是同一份内容`() {
        val store = ConversationStore(MemoryStore())
        listOf(msg("c", 3), msg("a", 1), msg("b", 2)).forEach { store.append(it) }
        assertEquals(store.all(), store.snapshot.value)
    }

    @Test
    fun `recorder 暴露的就是 store 的那份快照`() {
        val store = ConversationStore(MemoryStore())
        val recorder = ConversationRecorder(store)
        recorder.recordAgentMessage(messageId = "m1", conversationId = "c1", text = "在")
        assertEquals(listOf("m1"), recorder.messages.value.map { it.id })
    }
}
