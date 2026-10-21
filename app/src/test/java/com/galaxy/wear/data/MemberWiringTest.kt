package com.galaxy.wear.data

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「手表是智能体的成员」这条接线真的接上了。
 *
 * 帧的形状由 [WatchMemberFramesTest] 钉死、动作的执行由 AgentCommandTest 钉死;这里钉的是它们**被用上**:
 * 认证通过后登记、网关下发的 command 帧到得了执行器、结果带着 command_id 回得去。
 * 缺任何一环的表现都一样安静 —— 连接是好的、认证是过的,智能体却看不见这块表、调不动它。
 */
class MemberWiringTest {

    private fun source(relative: String): String {
        val candidates = listOf(File(relative), File("app/$relative"), File("../app/$relative"))
        return candidates.firstOrNull { it.isFile }?.readText()
            ?: throw AssertionError("找不到 $relative。试过:" + candidates.joinToString { it.absolutePath })
    }

    private val client by lazy { source("src/main/java/com/galaxy/wear/data/AIPClient.kt") }
    private val app by lazy { source("src/main/java/com/galaxy/wear/GalaxyWearApplication.kt") }

    @Test
    fun `the watch registers and reports capabilities right after the gateway accepts its auth`() {
        val branch = client.substringAfter("\"auth_ok\" -> {").substringBefore("\"auth_failed\" -> {")
        assertTrue("auth_ok 之后没有登记", branch.contains("registerAsMember()"))
        val reg = client.substringAfter("private suspend fun registerAsMember()").substringBefore("/** 回话")
        assertTrue(reg.contains("WatchMember.registerFrame("))
        assertTrue(reg.contains("WatchMember.capabilityReportFrame("))
    }

    @Test
    fun `a rejected registration leaves a log line`() {
        val branch = client.substringAfter("\"device_register_ack\" -> {").substringBefore("\"command\" -> {")
        assertTrue(branch.contains("Log.w"))
    }

    @Test
    fun `a command frame from the gateway reaches the executor`() {
        val branch = client.substringAfter("\"command\" -> {").substringBefore("\"command_result\" -> {")
        assertTrue("command 帧没有上抛", branch.contains("MsgType.COMMAND"))
        assertTrue("collector 没有 COMMAND 分支", app.contains("MsgType.COMMAND -> handleAgentCommand(msg)"))
        val handler = app.substringAfter("private fun handleAgentCommand(").substringBefore("private fun handleDecisionWithdraw(")
        assertTrue(handler.contains("AgentCommandParser.parse("))
        assertTrue(handler.contains("agentCommands.execute("))
        assertTrue("结果没有带着 command_id 回去", handler.contains("sendCommandResult(cmd.commandId"))
    }

    @Test
    fun `every frame the watch speaks as a member goes out as raw json with the field positions the gateway reads`() {
        assertTrue(client.contains("private suspend fun sendRawJson("))
        assertTrue(client.contains("suspend fun sendCommandResult("))
    }

    @Test
    fun `the agent message that reaches the notification keeps reply expected`() {
        val branch = client.substringAfter("\"agent_message\" -> {").substringBefore("\"event\" -> {")
        assertTrue("reply_expected 在这一层被丢掉了 —— 智能体说在等回话,手表上却没有回复框", branch.contains("put(\"reply_expected\", replyExpected)"))
    }
}
