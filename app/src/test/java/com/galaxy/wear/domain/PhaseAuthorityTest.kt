package com.galaxy.wear.domain

import com.galaxy.wear.domain.model.Phase
import com.galaxy.wear.domain.model.PhaseAuthority
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 谁有资格改写三态。
 *
 * 这不是一条风格约定，是一条**曾经被违反过**的规则：手表原来把"我连上了、我鉴权过了"
 * 直接映射成 LIMINAL / MANIFEST。于是鉴权一成功，表上就常驻「显现」，而那台电脑
 * 其实什么也没在做。用户看到的是一台正在忙的机器，实际它在发呆 —— 而且这个错误
 * **永远不会自己纠正**，除非桌面恰好发生一次相位跃迁。
 *
 * 三态说的是"那台电脑上的主体在干什么"，是**远端**的属性；
 * 连接态说的是"我这块表连上没有"，是**本机链路**的属性。
 * 两件事塞进同一个字段，就必然是谁后写谁赢。
 */
class PhaseAuthorityTest {

    // ── 一、只有桌面能把相位抬起来 ─────────────────────────────────────────

    @Test
    fun `desktop wire values map to the three phases`() {
        assertEquals(Phase.SILENT, PhaseAuthority.fromDesktop("silent"))
        assertEquals(Phase.LIMINAL, PhaseAuthority.fromDesktop("liminal"))
        assertEquals(Phase.MANIFEST, PhaseAuthority.fromDesktop("manifest"))
    }

    @Test
    fun `wire values are case and whitespace tolerant`() {
        // V2 侧发的是小写，但报文经过若干层转手；大小写把相位判没了会很难查。
        assertEquals(Phase.MANIFEST, PhaseAuthority.fromDesktop("MANIFEST"))
        assertEquals(Phase.LIMINAL, PhaseAuthority.fromDesktop("  Liminal "))
    }

    @Test
    fun `an unrecognised phase is read down to SILENT, never up`() {
        // 桌面将来多出第四种状态时，旧表宁可显示"静默"也不能猜成"显现"。
        // 往低了猜是安全的；往高了猜会让人以为它在替自己干活。
        assertEquals(Phase.SILENT, PhaseAuthority.fromDesktop("executing"))
        assertEquals(Phase.SILENT, PhaseAuthority.fromDesktop(""))
        assertEquals(Phase.SILENT, PhaseAuthority.fromDesktop("MANIFESTO"))
    }

    // ── 二、连接态**永远**抬不高相位 ───────────────────────────────────────

    @Test
    fun `a link coming up does not raise the phase by itself`() {
        // 这条就是当初那个 bug 的正面写法：连上/鉴权过 ≠ 那台电脑在干活。
        assertEquals(Phase.SILENT, PhaseAuthority.onLinkChange(Phase.SILENT, linkUp = true))
    }

    @Test
    fun `a live link preserves whatever the desktop last said`() {
        // 链路事件不该抹掉桌面刚下发的相位 —— 否则每次心跳/重连都会把它打回去。
        assertEquals(Phase.LIMINAL, PhaseAuthority.onLinkChange(Phase.LIMINAL, linkUp = true))
        assertEquals(Phase.MANIFEST, PhaseAuthority.onLinkChange(Phase.MANIFEST, linkUp = true))
    }

    @Test
    fun `losing the link falls back to SILENT from any phase`() {
        // 不是"本机判定了相位"，是**我们不再知道**了。手里那个值是断线前的说法，
        // 继续显示「显现」等于拿一份过期事实冒充现状。
        assertEquals(Phase.SILENT, PhaseAuthority.onLinkChange(Phase.MANIFEST, linkUp = false))
        assertEquals(Phase.SILENT, PhaseAuthority.onLinkChange(Phase.LIMINAL, linkUp = false))
        assertEquals(Phase.SILENT, PhaseAuthority.onLinkChange(Phase.SILENT, linkUp = false))
    }

    // ── 三、区分度 ─────────────────────────────────────────────────────────

    @Test
    fun `no link state can ever produce LIMINAL or MANIFEST`() {
        // 区分度所在：如果有人再把"AUTHENTICATED → MANIFEST"塞回来，
        // onLinkChange 就会在 linkUp 时凭空产出一个更高的相位，这条随即变红。
        // 遍历所有 (当前相位 × 链路状态) 组合，结果只能是"保持"或"降到 SILENT"。
        for (current in Phase.entries) {
            for (linkUp in listOf(true, false)) {
                val result = PhaseAuthority.onLinkChange(current, linkUp)
                assertEquals(
                    "链路状态不得抬高相位：current=$current linkUp=$linkUp",
                    if (linkUp) current else Phase.SILENT,
                    result,
                )
            }
        }
    }
}
