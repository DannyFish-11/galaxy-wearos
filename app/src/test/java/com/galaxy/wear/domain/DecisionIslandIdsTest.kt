package com.galaxy.wear.domain

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DecisionIslandIdsTest {

    @Test
    fun `an island id round-trips to its decision id`() {
        for (id in listOf("d-1", "abc_def", "decision_nested", "中文")) {
            assertEquals(id, DecisionIslandIds.decisionIdOf(DecisionIslandIds.islandId(id)))
        }
    }

    @Test
    fun `a card that is not a decision has no decision id`() {
        assertNull(DecisionIslandIds.decisionIdOf("msg_42"))
        assertNull(DecisionIslandIds.decisionIdOf(""))
        assertNull(DecisionIslandIds.decisionIdOf("decision_"))
    }

    // ── 接线：语音回复回答的是这条决策，而不是开一次通用语音查询 ─────────────

    private fun source(relative: String): String {
        val candidates = listOf(File(relative), File("app/$relative"), File("../app/$relative"))
        return candidates.firstOrNull { it.isFile }?.readText()
            ?: throw AssertionError("找不到 $relative。试过:" + candidates.joinToString { it.absolutePath })
    }

    @Test
    fun `the application builds and tears down island ids through the same helper`() {
        val app = source("src/main/java/com/galaxy/wear/GalaxyWearApplication.kt")
        assertTrue(app.contains("DecisionIslandIds.islandId(decisionId)"))
        assertTrue("不许再有手写的 decision_ 前缀", !Regex("\"decision_\\$").containsMatchIn(app))
        assertTrue(app.contains("fun answerDecisionByVoice("))
    }

    @Test
    fun `a withdrawn decision also leaves the island`() {
        val app = source("src/main/java/com/galaxy/wear/GalaxyWearApplication.kt")
        val withdraw = app.substringAfter("private fun handleDecisionWithdraw(").substringBefore("private fun handleDecisionRequest(")
        assertTrue(
            "别处已答的决策只收了通知、岛上的卡还在 —— 用户还能点一个已落定的选项",
            withdraw.contains("dismissIslandItem(DecisionIslandIds.islandId(decisionId))"),
        )
    }

    @Test
    fun `the home screen answers the decision on screen by voice`() {
        val home = source("src/main/java/com/galaxy/wear/ui/screens/HomeScreen.kt")
        assertTrue(home.contains("DecisionIslandIds.decisionIdOf("))
        assertTrue(home.contains("app.answerDecisionByVoice("))
        // 不是决策卡时才退回通用语音页
        assertTrue(home.contains("onVoice()"))
    }
}
