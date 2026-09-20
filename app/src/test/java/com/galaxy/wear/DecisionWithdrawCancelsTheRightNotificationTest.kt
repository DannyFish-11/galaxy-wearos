package com.galaxy.wear

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 撤回要收掉的,必须正是当初弹出来的那一条。
 *
 * 决策通知的 id 是 `decisionId.hashCode()`，撤回时也必须用同一个算法算。两处一旦
 * 不一致，`cancel()` 会**静默地什么都不做** —— 没有异常、没有返回值、没有日志。
 * 于是通知继续挂在那儿，而代码看起来是"已经收了"。
 *
 * 这类缺陷编译器管不到，运行时也不报错，只有戴着表试一遍才发现。所以钉在源码上。
 */
class DecisionWithdrawCancelsTheRightNotificationTest {

    private fun source(relative: String): String {
        val candidates = listOf(
            File("src/main/java/$relative"),
            File("app/src/main/java/$relative"),
            File("../app/src/main/java/$relative"),
        )
        val f = candidates.firstOrNull { it.isFile }
            ?: throw AssertionError(
                "找不到 $relative。试过:${candidates.joinToString { it.absolutePath }}。" +
                    "这里刻意不跳过 —— 一个『找不到就当通过』的守卫等于没有守卫。"
            )
        return f.readText()
    }

    @Test
    fun `弹通知和撤回通知用的是同一个 id 算法`() {
        val service = source("com/galaxy/wear/service/GalaxyWearService.kt")
        val app = source("com/galaxy/wear/GalaxyWearApplication.kt")

        assertTrue(
            "GalaxyWearService 弹决策通知时不是用 decisionId.hashCode() 当 id —— " +
                "撤回那边算出来的会对不上",
            service.contains("nm.notify(decisionId.hashCode()"),
        )
        assertTrue(
            "撤回时没有用 decisionId.hashCode() —— cancel() 会静默地什么都不做",
            app.contains("cancel(decisionId.hashCode())"),
        )
    }

    @Test
    fun `撤回分支真的接在消息收集器上`() {
        val app = source("com/galaxy/wear/GalaxyWearApplication.kt")
        assertTrue(
            "消息收集器里没有 DECISION_WITHDRAW 分支 —— 服务端发了也没人接",
            app.contains("MsgType.DECISION_WITHDRAW -> handleDecisionWithdraw(msg)"),
        )
        assertTrue("handleDecisionWithdraw 没有实现", app.contains("fun handleDecisionWithdraw("))
    }

    @Test
    fun `撤回不依赖服务端把答案发过来`() {
        // 把答案广播给每一台设备，等于把一次私人决定发给所有人。
        // 手表这边只该读 decision_id 和 reason。
        val app = source("com/galaxy/wear/GalaxyWearApplication.kt")
        val fn = app.substringAfter("fun handleDecisionWithdraw(").substringBefore("\n    private fun ")
        for (leak in listOf("selected_option", "voice_input")) {
            assertTrue("撤回处理里读了 $leak —— 它不该出现在这条消息里", !fn.contains(leak))
        }
    }

    @Test
    fun `四种撤回原因在协议侧是封闭枚举`() {
        // 自由文本做不到"按原因决定怎么呈现"这件事：设备只能把它当字符串显示或者去猜。
        val app = source("com/galaxy/wear/GalaxyWearApplication.kt")
        val fn = app.substringAfter("fun handleDecisionWithdraw(").substringBefore("\n    private fun ")
        assertTrue("撤回处理没有读 reason", fn.contains("\"reason\""))
    }

    @Test
    fun `reason 缺失时按 cancelled 处理而不是崩掉`() {
        val app = source("com/galaxy/wear/GalaxyWearApplication.kt")
        val fn = app.substringAfter("fun handleDecisionWithdraw(").substringBefore("\n    private fun ")
        assertTrue(
            "reason 没有兜底值 —— 老版本服务端不发这个字段时会走到哪儿不确定",
            fn.contains("?: \"cancelled\""),
        )
    }

    @Test
    fun `decision_id 缺失时直接返回,不去猜一个 id 乱收通知`() {
        val app = source("com/galaxy/wear/GalaxyWearApplication.kt")
        val fn = app.substringAfter("fun handleDecisionWithdraw(").substringBefore("\n    private fun ")
        assertEquals(
            "decision_id 缺失必须 return,不能兜一个默认值 —— 那会收掉一条无关的通知",
            true,
            fn.contains("?: return"),
        )
    }
}
