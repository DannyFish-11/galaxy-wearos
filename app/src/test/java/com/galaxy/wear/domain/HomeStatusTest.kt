package com.galaxy.wear.domain

import com.galaxy.wear.data.AIPConnectionState
import com.galaxy.wear.ui.theme.StatusVisual
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 手表首页回答的是：连着没有、要不要重新配对、有没有事在等我 —— 不是三态。
 */
class HomeStatusTest {

    @Test
    fun `every connection state maps to a tone and a label`() {
        val expected = mapOf(
            AIPConnectionState.AUTHENTICATED to (StatusTone.CONNECTED to "已连接"),
            AIPConnectionState.CONNECTED to (StatusTone.CONNECTING to "连接中…"),
            AIPConnectionState.CONNECTING to (StatusTone.CONNECTING to "连接中…"),
            AIPConnectionState.DISCONNECTED to (StatusTone.OFFLINE to "未连接"),
            AIPConnectionState.ERROR to (StatusTone.OFFLINE to "连接出错"),
        )
        assertEquals("新增连接态必须同时决定它在首页上叫什么", AIPConnectionState.values().toSet(), expected.keys)
        for ((state, want) in expected) {
            val s = HomeStatus.of(state, needsRepair = false, pending = 0)
            assertEquals(state.name, want.first, s.tone)
            assertEquals(state.name, want.second, s.label)
        }
    }

    @Test
    fun `needing to pair again wins over whatever the link is doing`() {
        for (state in AIPConnectionState.values()) {
            val s = HomeStatus.of(state, needsRepair = true, pending = 0)
            assertEquals(state.name, StatusTone.NEEDS_REPAIR, s.tone)
            assertEquals("需要重新配对", s.label)
        }
    }

    @Test
    fun `pending items are counted and never negative`() {
        assertNull(HomeStatus.of(AIPConnectionState.AUTHENTICATED, false, 0).detail)
        assertEquals("3 条待处理", HomeStatus.of(AIPConnectionState.AUTHENTICATED, false, 3).detail)
        assertEquals(0, HomeStatus.of(AIPConnectionState.AUTHENTICATED, false, -2).pending)
        assertNull(HomeStatus.of(AIPConnectionState.AUTHENTICATED, false, -2).detail)
    }

    @Test
    fun `each tone has its own colour and the tile reads the same source as the app`() {
        val colours = StatusTone.values().map { StatusVisual.argbLong(it) }
        assertEquals("四档颜色必须两两不同", colours.size, colours.toSet().size)
        for (tone in StatusTone.values()) {
            // protolayout 取的是同一个值的带符号形态
            assertEquals(tone.name, StatusVisual.argbLong(tone).toInt(), StatusVisual.argb(tone))
            assertTrue(tone.name, StatusVisual.argbLong(tone) and 0xFF000000L == 0xFF000000L)
        }
        assertNotEquals(StatusVisual.BACKGROUND_ARGB, StatusVisual.argbLong(StatusTone.CONNECTED))
    }
}
