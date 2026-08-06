package com.galaxy.wear.domain

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 多路径连接：**装了，得有人用**（手表侧）。
 *
 * 这条守的不是逻辑，是接线。
 *
 * PairClaimClient 早就把配对拿到的全部可达路径存了下来，还配了
 * [com.galaxy.wear.auth.PairClaimClient.storedCandidates] /
 * [com.galaxy.wear.auth.PairClaimClient.lastGoodKind] /
 * [com.galaxy.wear.auth.PairClaimClient.rememberGoodKind] 三个读写口 ——
 * 但**一个调用方都没有**。手表照旧只连 `galaxy_auth` 里那一个 server_url。
 *
 * 于是"手表带流量出门也能连上"这件事，从代码上看像是做完了：存了、能读、
 * 顺序也有 ConnectionPathPlanner 兜着。只是没人用。这类缺陷不报错、不变红，
 * 也测不出来 —— 因为被测的东西根本没被调用。手表侧尤其要命：
 * 它是"带流量单独出门"这个场景的**唯一**主角。
 */
class CandidatePathsAreActuallyUsedTest {

    private fun source(relative: String): String {
        val root = locateMainSourceRoot()
        val f = File(root, relative)
        assertTrue("源文件不存在：${f.absolutePath}", f.isFile)
        return f.readText()
    }

    @Test
    fun `reconnect picks a candidate instead of always the stored single url`() {
        val src = source("com/galaxy/wear/GalaxyWearApplication.kt")
        assertTrue("没有换路逻辑", src.contains("private fun nextConnectUrl("))
        // 光有函数不够 —— 自动重连那条路径必须真的调它。
        assertTrue(
            "自动重连没有调用 nextConnectUrl —— 会永远只连存下来的那一个地址",
            src.contains("nextConnectUrl(savedUrl)"),
        )
    }

    @Test
    fun `the ordering comes from the shared planner, not a second local sort`() {
        // 两边各排各的必然会漂：同一个故障在手机和手表上会表现成两种样子，
        // 排障时看到的两份事实互相矛盾。
        val src = source("com/galaxy/wear/GalaxyWearApplication.kt")
        assertTrue(
            "候选顺序没有走 ConnectionPathPlanner",
            src.contains("ConnectionPathPlanner.planAttempts("),
        )
    }

    @Test
    fun `a successful connect records which path worked`() {
        // 不记的话，每次断线都要从头试探一轮；在外面走流量时，局域网那条
        // 每次都要白等一个超时才轮到能用的。
        val src = source("com/galaxy/wear/GalaxyWearApplication.kt")
        assertTrue("没有记录 last-good 路径", src.contains("rememberGoodKind("))
        assertTrue("连上之后没有触发记录", src.contains("rememberWorkingPath("))
    }

    @Test
    fun `the stored candidates are actually read`() {
        val src = source("com/galaxy/wear/GalaxyWearApplication.kt")
        assertTrue(
            "storedCandidates 仍然没有调用方 —— 配对存下来的路径等于白存",
            src.contains("storedCandidates()"),
        )
    }

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
}
