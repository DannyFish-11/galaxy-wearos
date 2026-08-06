package com.galaxy.wear.ui

import com.galaxy.wear.ui.screens.CODE_ALPHABET
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 短码字符集必须与 V2 侧逐字符一致。
 *
 * 为什么这条值得单独钉
 * ====================
 * 手表这块屏没有可用的系统键盘（圆屏上它会盖掉大半个界面，输到一半看不见已输入的
 * 内容），所以接入界面自己画了一个字符网格。网格里列哪些字符，是**抄**过来的一份
 * 常量 —— 而抄来的常量就是会漂。
 *
 * 漂了会怎样：V2 那边签出一个含 `W` 的码，这边网格里没有 `W`，用户就是**输不完**
 * 那个码。界面上没有任何提示说"这个字符输不进去"，看起来只是"怎么点都差一个"。
 * 反过来，这边多列了 `0`，用户点进去，服务端判无效，还白白吃掉一次按来源的节流额度。
 *
 * 所以这里直接去读 V2 仓里的那份定义来比，而不是在测试里再抄一遍 ——
 * 再抄一遍的话，抄错的时候两边一起错，测试恒绿。
 */
class PairClaimCodeAlphabetTest {

    @Test
    fun `alphabet matches the V2 definition character for character`() {
        val v2 = locateV2AgentCard()
        if (v2 == null) {
            // 单仓 CI 里没有 V2 的工作副本。此时退回**自洽性**检查（见下一条），
            // 但不能假装比对过了 —— 静默跳过会让这道闸在最需要它的地方失效。
            println("[SKIP] 找不到 V2 仓的 core/agent_card.py，跳过跨仓比对")
            return
        }
        val line = v2.readLines().firstOrNull { it.trimStart().startsWith("_CODE_ALPHABET") }
            ?: error("V2 的 core/agent_card.py 里找不到 _CODE_ALPHABET —— 定义被改名了？")
        val v2Alphabet = line.substringAfter('"').substringBeforeLast('"')

        assertEquals(
            "短码字符集与 V2 不一致：那边发得出来的字符，这边必须输得进去",
            v2Alphabet,
            CODE_ALPHABET,
        )
    }

    @Test
    fun `alphabet excludes the confusable characters`() {
        // 这几个是刻意排除的：小屏上认不准，口述时也分不开。
        // 与上一条的分工：上一条比"是否与 V2 相同"，这一条比"是否仍然满足当初的理由"。
        // 只有前者的话，两边一起加回 `0` 也会绿。
        for (c in listOf('0', 'O', '1', 'I', 'L')) {
            assertFalse("字符集里不该有易混字符 $c", CODE_ALPHABET.contains(c))
        }
    }

    @Test
    fun `alphabet has no duplicates and is all uppercase`() {
        assertEquals("字符集里有重复字符", CODE_ALPHABET.length, CODE_ALPHABET.toSet().size)
        assertTrue("字符集必须全大写 —— 输入侧统一 uppercase 之后才对得上", CODE_ALPHABET == CODE_ALPHABET.uppercase())
    }

    @Test
    fun `grid rows divide evenly enough to be laid out`() {
        // 网格按 6 个一行切。最后一行短一点没关系，但不能为空 —— 空行会在
        // 圆屏上留一条视觉断裂，看起来像少了一排按钮。
        val rows = CODE_ALPHABET.chunked(6)
        assertTrue(rows.isNotEmpty())
        assertTrue("最后一行不该为空", rows.last().isNotEmpty())
    }

    /** 在祖先目录里找 V2 的工作副本。三仓平铺在同一层时能找到。 */
    private fun locateV2AgentCard(): File? {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        repeat(6) {
            val d = dir ?: return null
            d.parentFile?.listFiles()?.forEach { sibling ->
                val f = File(sibling, "core/agent_card.py")
                if (f.isFile) return f
            }
            dir = d.parentFile
        }
        return null
    }
}
