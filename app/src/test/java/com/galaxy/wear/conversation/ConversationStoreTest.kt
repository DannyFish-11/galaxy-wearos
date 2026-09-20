package com.galaxy.wear.conversation

import com.galaxy.wear.conversation.ConversationMessage.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 手表上的会话上下文。
 *
 * 这个类补的是一个**从零开始**的缺口:此前整个手表工程里没有任何一处存过对话
 * （46 个源文件里 `message` / `conversation` / `history` 一次都没出现过）。
 * 表现出来就是：你对着手表说一句，它回一句，抬手再看什么都没有。
 *
 * 而数据本来就在线上 —— `sendVoiceQuery` 发出去、`command_result` 回来，
 * 只是回来那条**没有任何代码读它**。所以这里要钉的是"存得住、不重、不丢顺序、
 * 不把手表存储吃光"这几条。
 */
class ConversationStoreTest {

    /** 记录每次落盘内容的假存储。 */
    private class FakeStore(initial: List<ConversationMessage> = emptyList()) : ConversationStore.Store {
        var persisted: List<ConversationMessage> = initial
        var writeCount = 0
        override fun read(): List<ConversationMessage> = persisted
        override fun write(messages: List<ConversationMessage>) {
            persisted = messages
            writeCount++
        }
    }

    private fun msg(
        id: String,
        text: String = "t-$id",
        role: Role = Role.USER,
        at: Long = id.filter { it.isDigit() }.ifEmpty { "0" }.toLong(),
        conversationId: String = "c1",
    ) = ConversationMessage(id, conversationId, role, text, at)

    @Test
    fun `存进去就读得出来`() {
        val s = ConversationStore(FakeStore())

        assertTrue(s.append(msg("m1", "今天天气怎么样")))

        assertEquals(listOf("今天天气怎么样"), s.all().map { it.text })
    }

    @Test
    fun `一问一答两个方向都存得住`() {
        // 这就是缺口的形状:发出去的和收回来的，此前哪个都没存。
        val s = ConversationStore(FakeStore())

        s.append(msg("m1", "提醒我十分钟后喝水", Role.USER, at = 1))
        s.append(msg("m2", "好，十分钟后提醒你", Role.ASSISTANT, at = 2))

        assertEquals(listOf(Role.USER, Role.ASSISTANT), s.all().map { it.role })
    }

    @Test
    fun `每次追加都落盘`() {
        // 只存在内存里等于没存：手表上进程被系统回收是常态。
        val store = FakeStore()
        val s = ConversationStore(store)

        s.append(msg("m1"))
        s.append(msg("m2"))

        assertEquals(2, store.writeCount)
        assertEquals(listOf("m1", "m2"), store.persisted.map { it.id })
    }

    @Test
    fun `重启后接着读得到`() {
        val store = FakeStore()
        ConversationStore(store).append(msg("m1", "记得买牛奶"))

        val afterRestart = ConversationStore(store)

        assertEquals(listOf("记得买牛奶"), afterRestart.all().map { it.text })
    }

    @Test
    fun `同一条补发两次只记一条`() {
        // 断线重连后服务端可能补发。不去重的话，界面上会出现两句一模一样的回复。
        val s = ConversationStore(FakeStore())

        assertTrue(s.append(msg("m1")))
        assertFalse("同一个 id 第二次应当被拒", s.append(msg("m1")))

        assertEquals(1, s.all().size)
    }

    @Test
    fun `内容相同但 id 不同的两条都要留下`() {
        // 关键是别把"用户真的连说了两句一样的话"当成重复吃掉。
        val s = ConversationStore(FakeStore())

        s.append(msg("m1", "再说一遍", at = 1))
        s.append(msg("m2", "再说一遍", at = 2))

        assertEquals(2, s.all().size)
    }

    @Test
    fun `没有 id 的消息不收`() {
        // 没有 id 就无从去重，收下它等于给重复留了个后门。
        val s = ConversationStore(FakeStore())

        assertFalse(s.append(msg("", "无主消息")))
        assertTrue(s.all().isEmpty())
    }

    @Test
    fun `乱序到达的消息按发生时间归位`() {
        // 补传会让消息乱序到达。界面按时间读，不能靠到达顺序 ——
        // 否则补回来的一条会插在最后，看起来像是刚刚说的。
        val s = ConversationStore(FakeStore())

        s.append(msg("m3", at = 3))
        s.append(msg("m1", at = 1))
        s.append(msg("m2", at = 2))

        assertEquals(listOf(1L, 2L, 3L), s.all().map { it.timestampMs })
    }

    @Test
    fun `超出上限时丢最旧的`() {
        // 手表存储小，而对话只增不减。满了丢最新的等于"越用越看不到刚说的话"。
        val s = ConversationStore(FakeStore(), maxMessages = 3)

        for (i in 1..5) s.append(msg("m$i", at = i.toLong()))

        assertEquals(listOf("m3", "m4", "m5"), s.all().map { it.id })
    }

    @Test
    fun `被丢掉的旧消息之后还能再进来`() {
        // 去重表要跟着裁剪一起收缩，否则一个 id 被丢掉之后就永远进不来了 ——
        // 补传一条很旧的消息时会被静默吃掉。
        val s = ConversationStore(FakeStore(), maxMessages = 2)
        s.append(msg("m1", at = 1))
        s.append(msg("m2", at = 2))
        s.append(msg("m3", at = 3)) // m1 被丢

        assertTrue("m1 已被裁掉，应当可以重新记入", s.append(msg("m1", at = 1)))
    }

    @Test
    fun `按会话过滤`() {
        // 同一时刻可能有两段对话在进行，跨设备靠 conversationId 归拢。
        val s = ConversationStore(FakeStore())
        s.append(msg("a1", conversationId = "c1", at = 1))
        s.append(msg("b1", conversationId = "c2", at = 2))
        s.append(msg("a2", conversationId = "c1", at = 3))

        assertEquals(listOf("a1", "a2"), s.conversation("c1").map { it.id })
        assertEquals(listOf("b1"), s.conversation("c2").map { it.id })
    }

    @Test
    fun `最近若干条保持时间升序`() {
        // 界面从上往下读，顺序不能倒过来。
        val s = ConversationStore(FakeStore())
        for (i in 1..5) s.append(msg("m$i", at = i.toLong()))

        assertEquals(listOf("m4", "m5"), s.recent(2).map { it.id })
        assertEquals(emptyList<String>(), s.recent(0).map { it.id })
    }

    @Test
    fun `清空之后落盘的也是空的`() {
        // 只清内存不清盘，下次启动它又回来了 —— 用户会以为没删掉。
        val store = FakeStore()
        val s = ConversationStore(store)
        s.append(msg("m1"))

        s.clear()

        assertTrue(s.all().isEmpty())
        assertTrue(store.persisted.isEmpty())
        assertTrue("清空后原 id 应可再次记入", ConversationStore(store).append(msg("m1")))
    }

    @Test
    fun `从已有历史构造时按时间排好`() {
        // 落盘内容可能被外部改动或版本迁移弄乱顺序，构造时统一归位。
        val store = FakeStore(listOf(msg("m2", at = 2), msg("m1", at = 1)))

        assertEquals(listOf("m1", "m2"), ConversationStore(store).all().map { it.id })
    }
}
