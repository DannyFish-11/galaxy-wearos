package com.galaxy.wear.ci

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 跨仓改动必须能在单个 PR 里验证。
 *
 * `wear-compile.yml` 要同时检出本仓和兄弟仓 `ufo-galaxy-android`（本仓的
 * `settings.gradle.kts` 把 `:shared-transport` / `:shared-protocol` 当子项目引进来）。
 * 它此前把兄弟仓**硬钉在 `ref: main`**，后果不是"偶尔不方便"，而是任何跨仓改动都
 * 不可能在单个 PR 里变绿：手表侧引用安卓分支上的新类必然 `Unresolved reference`，
 * 只能先合安卓、再回来重跑。相位色收敛与 mDNS 收敛两轮都撞过同一堵墙。
 *
 * 修法是：PR 上先找兄弟仓里同名分支，没有才回落 `main`；push（含 `main`）永远用 `main`。
 * **两条缺一不可** —— 只有前半句，主干就不再对着主干验证，"main 是绿的"会失去意义。
 *
 * 判据只能是源码级：工作流的行为在 JVM 单测里跑不起来，能拦住"有人改回 ref: main"的
 * 只有读 YAML 本身。
 */
class CrossRepoCiIsNotPinnedToMainTest {

    private fun workflow(): String {
        val candidates = listOf(
            File(".github/workflows/wear-compile.yml"),
            File("../.github/workflows/wear-compile.yml"),
        )
        val f = candidates.firstOrNull { it.isFile }
            ?: throw AssertionError(
                "找不到 wear-compile.yml。试过：${candidates.joinToString { it.absolutePath }}。" +
                    "这里刻意不跳过 —— 一个『找不到就当通过』的守卫等于没有守卫。"
            )
        return f.readText()
    }

    @Test
    fun `the sibling checkout ref is resolved, not hard pinned`() {
        val src = workflow()
        assertTrue(
            "兄弟仓又被硬钉成 ref: main —— 跨仓改动会重新变成不可能在单个 PR 里验证",
            src.contains("ref: \${{ steps.sibling.outputs.ref }}"),
        )
        assertTrue("没有解析 ref 的步骤", src.contains("id: sibling"))
    }

    @Test
    fun `only pull requests look for a matching sibling branch`() {
        // push（含 main）必须永远用 main：主干要对着主干验证。
        assertTrue(
            "PR_BRANCH 不是只在 pull_request 事件下取 head_ref —— main 将不再对着 main 验证",
            workflow().contains("github.event_name == 'pull_request' && github.head_ref"),
        )
    }

    @Test
    fun `probing failures fall back instead of turning the gate red`() {
        val src = workflow()
        assertTrue("没有先置默认值", src.contains("REF=\"\$DEFAULT_REF\""))
        assertTrue(
            "ls-remote 的失败没被吞掉，会触发 set -e —— 探测本身把整条门弄红",
            src.contains(">/dev/null 2>&1"),
        )
    }

    @Test
    fun `the token never reaches the log`() {
        val src = workflow()
        assertTrue("脚本没开 set -euo pipefail", src.contains("set -euo pipefail"))
        assertTrue("脚本开了 set -x，带 token 的 URL 会进日志", !src.contains("set -x"))
        val leaks = src.lines().filter { it.trim().startsWith("echo") && it.contains("x-access-token") }
        assertTrue("把带 token 的 URL 回显进日志了：$leaks", leaks.isEmpty())
    }

    @Test
    fun `using a sibling branch is announced loudly`() {
        // 这条绿只证明「两个分支放在一起是好的」，不证明合进 main 之后也好。
        // 顺序反了 main 会在合并那一刻变红，而那时没有任何东西提示过为什么。
        val src = workflow()
        assertTrue("没有写作业摘要", src.contains("GITHUB_STEP_SUMMARY"))
        assertTrue("摘要里没说明合并顺序 —— 这正是这条绿唯一的风险", src.contains("合并顺序"))
    }
}
