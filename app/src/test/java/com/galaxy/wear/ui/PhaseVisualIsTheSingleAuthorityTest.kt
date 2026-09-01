package com.galaxy.wear.ui

import androidx.compose.ui.graphics.toArgb
import com.galaxy.wear.domain.model.Phase
import com.galaxy.wear.ui.theme.PhaseVisual
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 相位的颜色与标签只有一份 —— 表盘应用和 Tile 不许各写各的。
 *
 * 这条守的是**同屏可见的漂移**，不是逻辑。
 *
 * 手表上有两个渲染器同时展示相位：Compose 表盘应用和 protolayout 的 Tile。
 * 改前它们各写各的字面量，四项里漂了三项：
 *
 * | 相位 / 元素 | 表盘应用 | Tile |
 * |---|---|---|
 * | SILENT   | `#333333` | `#333333` |
 * | LIMINAL  | `#666666` | `#808080` |
 * | MANIFEST | `#F5F5F7` | `#E0E0E0` |
 * | 背景     | `#0A0A0F` | `#000000` |
 *
 * 为什么必须查源码而不是只比对象
 * ------------------------------
 * 两边都改成读 [PhaseVisual] 之后，「比一比两个取色口相不相等」是**恒真**的 ——
 * 同一个函数当然等于它自己。那样的断言看着绿，实际什么都没守住。
 *
 * 真正会复发的动作是「有人图省事，在 Tile 里直接写回一个 `0xFF808080`」。
 * 能拦住它的只有源码级检查：Tile 的源文件里不许出现颜色字面量。
 *
 * protolayout 用不了 Compose 的 `Color`，所以这里也没有把两边合成一种类型的办法 ——
 * 只能共用同一个 [Long] 权威值，两边各取各的形态。
 */
class PhaseVisualIsTheSingleAuthorityTest {

    private fun locateMainSourceRoot(): File {
        // 单测的工作目录在不同调用方式下不一样（模块目录 / 仓库根），两种都试。
        val candidates = listOf(
            File("src/main/java"),
            File("app/src/main/java"),
            File("../app/src/main/java"),
        )
        return candidates.firstOrNull { it.isDirectory }
            ?: throw AssertionError(
                "找不到 src/main/java。试过：${candidates.joinToString { it.absolutePath }}。" +
                    "这里刻意不跳过 —— 一个『找不到就当通过』的守卫等于没有守卫。"
            )
    }

    private fun source(relative: String): String {
        val f = File(locateMainSourceRoot(), relative)
        assertTrue("源文件不存在：${f.absolutePath}", f.isFile)
        return f.readText()
    }

    /** `0xFF` 开头的六位十六进制 —— 本仓写颜色字面量的唯一形态。 */
    private val colorLiteral = Regex("0x[Ff][Ff][0-9A-Fa-f]{6}")

    // ── 权威值本身是自洽的 ────────────────────────────────────────────────

    @Test
    fun `每个相位都有非空标签和不透明颜色`() {
        // 用 Phase.entries 遍历而不是列三个常量：将来加相位时这条会跟着覆盖到，
        // 而 PhaseVisual 里的 when 是穷尽的，编译期就会逼人补上。
        for (phase in Phase.entries) {
            assertTrue("$phase 的标签是空的", PhaseVisual.label(phase).isNotBlank())
            val alpha = (PhaseVisual.statusArgbLong(phase) ushr 24) and 0xFF
            assertEquals("$phase 的相位色不是完全不透明", 0xFFL, alpha)
        }
    }

    @Test
    fun `三个相位的颜色互不相同`() {
        // 相位点的全部作用就是让人一眼分出状态；两个相位撞色等于这个控件失效。
        val argbs = Phase.entries.map { PhaseVisual.statusArgb(it) }
        assertEquals("有相位撞色：$argbs", argbs.size, argbs.toSet().size)
    }

    @Test
    fun `Compose 与 protolayout 两个取色口指向同一个值`() {
        // 这条不是恒真：statusColor 走 Compose 的 Color(Long) 构造，statusArgb 走
        // Long.toInt()。两条转换路径不同，符号位处理错了会在这里露出来。
        for (phase in Phase.entries) {
            assertEquals(
                "$phase 两个取色口不一致",
                PhaseVisual.statusArgb(phase),
                PhaseVisual.statusColor(phase).toArgb(),
            )
        }
    }

    // ── 两个渲染器都不许再自己写颜色 ──────────────────────────────────────

    @Test
    fun `Tile 源码里没有任何颜色字面量`() {
        val src = source("com/galaxy/wear/tile/GalaxyTileService.kt")
        val found = colorLiteral.findAll(src).map { it.value }.toList()
        assertTrue(
            "GalaxyTileService.kt 里又出现了颜色字面量 $found —— " +
                "Tile 的取色必须走 PhaseVisual，否则会和表盘应用漂开（历史上漂过三项）",
            found.isEmpty(),
        )
    }

    @Test
    fun `Tile 真的在调用 PhaseVisual 而不是只把字面量搬去了别处`() {
        // 上一条只证明「Tile 里没有字面量」。字面量被挪到隔壁文件也能让它变绿，
        // 所以还得正面确认取色口确实被调用了。
        val src = source("com/galaxy/wear/tile/GalaxyTileService.kt")
        assertTrue("Tile 没调用 PhaseVisual.statusArgb", src.contains("PhaseVisual.statusArgb(phase)"))
        assertTrue("Tile 没调用 PhaseVisual.label", src.contains("PhaseVisual.label(phase)"))
        assertTrue("Tile 底色没走共享值", src.contains("PhaseVisual.BACKGROUND_ARGB"))
    }

    @Test
    fun `表盘应用的相位状态文字也走同一个权威`() {
        val src = source("com/galaxy/wear/ui/screens/HomeScreen.kt")
        assertTrue(
            "HomeScreen 的相位状态文字没走 PhaseVisual —— 它就是和 Tile 漂开的那一半",
            src.contains("PhaseVisual.label(phase)") && src.contains("PhaseVisual.statusColor(phase)"),
        )
        assertFalse(
            "HomeScreen 里又出现了 `Phase.LIMINAL -> Pair(` 形态的本地相位映射",
            src.contains("Phase.LIMINAL -> Pair("),
        )
    }

    @Test
    fun `色板里的相位色也从权威派生`() {
        val src = source("com/galaxy/wear/ui/theme/Color.kt")
        // 不比对齐用的空格：那是格式，不是约束。只要求这两个 token 由权威派生。
        for (token in listOf("GraySilent", "GrayLiminal")) {
            val line = src.lineSequence().firstOrNull { it.trimStart().startsWith("val $token") }
                ?: throw AssertionError("Color.kt 里找不到 $token 的定义")
            assertTrue(
                "$token 又写回了颜色字面量 —— 那就是第三份定义：$line",
                line.contains("PhaseVisual.statusColor("),
            )
        }
    }
}
