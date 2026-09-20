package com.galaxy.wear.conversation

import com.galaxy.wear.conversation.ConversationMessage.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 会话列表的排版规则。
 *
 * 这类规则出错的样子很隐蔽：每条都显示时间，手表那块小屏就被时间戳塞满；
 * 一条都不显示，又分不清两句话之间隔了一天还是隔了一秒。写在 Composable 里
 * 就只能靠肉眼在真表上看，所以拎出来单测。
 */
class ConversationDisplayTest {

    private fun msg(at: Long, role: Role = Role.USER, id: String = "m$at") =
        ConversationMessage(id, "c1", role, "t", at)

    @Test
    fun `第一条总是显示时间`() {
        // 否则整屏没有任何时间参照。
        val rows = ConversationDisplay.rowsFor(listOf(msg(1000)))

        assertTrue(rows.single().showTimestamp)
    }

    @Test
    fun `隔得近的不重复显示时间`() {
        val rows = ConversationDisplay.rowsFor(
            listOf(msg(0), msg(30_000), msg(60_000))
        )

        assertEquals(listOf(true, false, false), rows.map { it.showTimestamp })
    }

    @Test
    fun `隔得久了重新显示一次`() {
        val gap = ConversationDisplay.TIMESTAMP_GAP_MS
        val rows = ConversationDisplay.rowsFor(listOf(msg(0), msg(gap)))

        assertEquals(listOf(true, true), rows.map { it.showTimestamp })
    }

    @Test
    fun `刚好差一毫秒不到就还不显示`() {
        // 边界写反的话，要么永远显示要么永远不显示 —— 两种都是屏幕上的灾难。
        val gap = ConversationDisplay.TIMESTAMP_GAP_MS
        val rows = ConversationDisplay.rowsFor(listOf(msg(0), msg(gap - 1)))

        assertFalse(rows[1].showTimestamp)
    }

    @Test
    fun `同一个人连着说算接着说`() {
        val rows = ConversationDisplay.rowsFor(
            listOf(msg(0, Role.USER), msg(1000, Role.USER))
        )

        assertFalse(rows[0].continuesPrevious)
        assertTrue(rows[1].continuesPrevious)
    }

    @Test
    fun `换人说话就不是接着说`() {
        val rows = ConversationDisplay.rowsFor(
            listOf(msg(0, Role.USER), msg(1000, Role.ASSISTANT))
        )

        assertFalse(rows[1].continuesPrevious)
    }

    @Test
    fun `隔久了即使同一个人也不算接着说`() {
        // 时间重新显示了，身份标记也该重新出现 —— 否则那条看起来像是紧接上一句，
        // 而它们之间其实隔了很久。
        val gap = ConversationDisplay.TIMESTAMP_GAP_MS
        val rows = ConversationDisplay.rowsFor(
            listOf(msg(0, Role.USER), msg(gap, Role.USER))
        )

        assertFalse(rows[1].continuesPrevious)
    }

    @Test
    fun `空列表算出空行`() {
        assertTrue(ConversationDisplay.rowsFor(emptyList()).isEmpty())
    }

    @Test
    fun `行的顺序与输入一致`() {
        // 会话从上往下读，这里不该做任何重排。
        val rows = ConversationDisplay.rowsFor(listOf(msg(1), msg(2), msg(3)))

        assertEquals(listOf("m1", "m2", "m3"), rows.map { it.message.id })
    }
}
