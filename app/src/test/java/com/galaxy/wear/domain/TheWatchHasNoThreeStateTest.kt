package com.galaxy.wear.domain

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 三态（静默 / 临界 / 显现）是电脑上的一种东西，不是手表的。
 *
 * 手表在这套系统里是中心智能体的「成员」：向智能体说话、听智能体提问、登记并回应它下发的动作、
 * 通话。它既不往三态上推，三态也不往它身上推。首页该回答的是「连着没有、要不要重新配对、
 * 有几件事在等我」（[HomeStatus]）。
 *
 * 这条守卫钉的是**不会悄悄长回来**：谁把相位类、相位上行、相位点又加回手表，这里就红。
 * 判读源码而不是运行期行为 —— 要钉的是「手表里没有这个概念」，没有就没有什么可运行的。
 */
class TheWatchHasNoThreeStateTest {

    private fun root(): File {
        val candidates = listOf(File("src/main/java"), File("app/src/main/java"), File("../app/src/main/java"))
        return candidates.firstOrNull { it.isDirectory }
            ?: throw AssertionError(
                "找不到 src/main/java。试过:" + candidates.joinToString { it.absolutePath } +
                    "。这里刻意不跳过 —— 一个『找不到就当通过』的守卫等于没有守卫。"
            )
    }

    private val mainSources by lazy { root().walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }

    private fun source(relative: String): String =
        File(root(), "com/galaxy/wear/$relative").also { check(it.isFile) { "找不到 $it" } }.readText()

    /** 去掉注释，只看代码：注释里解释「为什么没有三态」不算引用。 */
    private fun code(text: String): String =
        text.replace(Regex("/\\*[\\s\\S]*?\\*/"), "").lines().joinToString("\n") { it.substringBefore("//") }

    @Test
    fun `no phase types exist on the watch`() {
        val names = mainSources.map { it.name }
        for (gone in listOf("Phase.kt", "PhaseAuthority.kt", "PhaseVisual.kt")) {
            assertFalse("$gone 又回到了手表上", names.contains(gone))
        }
    }

    @Test
    fun `no code refers to phases, pulses or phase reports`() {
        val banned = listOf(
            Regex("\\bPhase\\b"), Regex("PhaseVisual"), Regex("PhaseAuthority"),
            Regex("phase_report"), Regex("PHASE_CHANGE"), Regex("sendPhaseReport"),
            Regex("pulseTrigger"), Regex("\\.phase\\b"), Regex("\\bSILENT\\b"),
            Regex("\\bLIMINAL\\b"), Regex("\\bMANIFEST\\b"), Regex("to_phase"),
        )
        val hits = mutableListOf<String>()
        for (f in mainSources) {
            val c = code(f.readText())
            for (b in banned) if (b.containsMatchIn(c)) hits += "${f.name}: ${b.pattern}"
        }
        assertTrue("手表代码里又出现了三态: $hits", hits.isEmpty())
    }

    @Test
    fun `home, tile and the ongoing notification all read the same HomeStatus`() {
        val home = source("ui/screens/HomeScreen.kt")
        val tile = source("tile/GalaxyTileService.kt")
        val service = source("service/GalaxyWearService.kt")
        val main = source("MainActivity.kt")
        assertTrue("首页要拿 HomeStatus", home.contains("status: HomeStatus"))
        assertTrue("Tile 要用 HomeStatus", tile.contains("HomeStatus.of("))
        assertTrue("常驻通知要用 HomeStatus", service.contains("HomeStatus.of("))
        assertTrue("MainActivity 要把连接 / 重新配对 / 待办交给 HomeStatus", main.contains("HomeStatus.of(connection, needsRepair"))
        // 两个渲染器必须从同一处取色
        assertTrue(source("ui/screens/HomeScreen.kt").contains("StatusVisual.argbLong("))
        assertTrue(tile.contains("StatusVisual.argb("))
    }

    @Test
    fun `a watch that needs pairing again can get there from the home screen`() {
        val home = source("ui/screens/HomeScreen.kt")
        val main = source("MainActivity.kt")
        assertTrue(home.contains("StatusTone.NEEDS_REPAIR"))
        assertTrue("首页要有「重新配对」入口", home.contains("重新配对"))
        assertTrue("入口要真的去配对页", main.contains("onRepair = { navController.navigate(\"auth\") }"))
    }

    @Test
    fun `connection changes refresh the tile`() {
        val app = source("GalaxyWearApplication.kt")
        assertTrue(
            "连接状态变化后 Tile 要刷新，否则它会一直停在上一个状态",
            code(app).contains("GalaxyTileService.requestRefresh("),
        )
    }
}
