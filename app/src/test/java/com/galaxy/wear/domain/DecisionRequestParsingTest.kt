package com.galaxy.wear.domain

import com.galaxy.wear.domain.model.DecisionOption
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import org.junit.Assert.assertEquals
import org.junit.Test

class DecisionRequestParsingTest {

    @Test
    fun `extracts id and label when both present`() {
        val arr = Json.parseToJsonElement(
            """[{"id":"approve","label":"批准"},{"id":"reject","label":"拒绝"}]"""
        ) as JsonArray

        val (ids, options) = parseDecisionOptions(arr)

        assertEquals(listOf("approve", "reject"), ids)
        assertEquals(listOf(DecisionOption("approve", "批准"), DecisionOption("reject", "拒绝")), options)
    }

    @Test
    fun `falls back to id as label when label missing`() {
        val arr = Json.parseToJsonElement("""[{"id":"opt1"}]""") as JsonArray

        val (ids, options) = parseDecisionOptions(arr)

        assertEquals(listOf("opt1"), ids)
        assertEquals(DecisionOption("opt1", "opt1"), options[0])
    }

    @Test
    fun `skips entries with no id`() {
        val arr = Json.parseToJsonElement("""[{"label":"no id here"},{"id":"valid"}]""") as JsonArray

        val (ids, options) = parseDecisionOptions(arr)

        assertEquals(listOf("valid"), ids)
        assertEquals(1, options.size)
    }

    @Test
    fun `handles null array`() {
        val (ids, options) = parseDecisionOptions(null)
        assertEquals(emptyList<String>(), ids)
        assertEquals(emptyList<DecisionOption>(), options)
    }

    @Test
    fun `handles empty array`() {
        val arr = Json.parseToJsonElement("[]") as JsonArray
        val (ids, options) = parseDecisionOptions(arr)
        assertEquals(emptyList<String>(), ids)
        assertEquals(emptyList<DecisionOption>(), options)
    }

    @Test
    fun `skips non-object array entries instead of crashing`() {
        val arr = Json.parseToJsonElement("""["not an object", {"id":"valid"}]""") as JsonArray

        val (ids, options) = parseDecisionOptions(arr)

        assertEquals(listOf("valid"), ids)
        assertEquals(1, options.size)
    }
}

/**
 * 决策通知的按钮**要给人看**。此前只有 id 被送到通知层(源码注释还写着
 * "通知动作只需要 id"),于是手腕上弹出来的按钮印着 `approve` / `deny`
 * 这种协议字面量 —— 一个瞟一眼就要按下去的界面,却要求用户先认识协议。
 */
class PairOptionLabelsTest {

    @Test
    fun `按钮显示 label 而不是协议 id`() {
        val paired = pairOptionLabels(listOf("deny", "approve"), listOf("不要", "批准"))

        assertEquals(listOf("不要", "批准"), paired.map { it.label })
        assertEquals(listOf("deny", "approve"), paired.map { it.id })
    }

    @Test
    fun `label 缺失时退回显示 id,而不是显示空白按钮`() {
        val paired = pairOptionLabels(listOf("deny", "approve"), emptyList())

        assertEquals(listOf("deny", "approve"), paired.map { it.label })
    }

    @Test
    fun `label 数组更短时,只有缺的那几个退回 id`() {
        val paired = pairOptionLabels(listOf("a", "b", "c"), listOf("甲", "乙"))

        assertEquals(listOf("甲", "乙", "c"), paired.map { it.label })
    }

    @Test
    fun `空白 label 视同缺失`() {
        val paired = pairOptionLabels(listOf("approve"), listOf("   "))

        assertEquals("approve", paired[0].label)
    }

    @Test
    fun `label 多出来时不影响 id 数量——绝不错位配对`() {
        // 错位比难看严重得多:点「不要」可能执行成「批准」。
        val paired = pairOptionLabels(listOf("deny"), listOf("不要", "批准", "多余"))

        assertEquals(1, paired.size)
        assertEquals("deny", paired[0].id)
        assertEquals("不要", paired[0].label)
    }

    @Test
    fun `id 与 label 的对应关系逐项成立`() {
        val ids = listOf("x1", "x2", "x3")
        val labels = listOf("L1", "L2", "L3")

        pairOptionLabels(ids, labels).forEachIndexed { i, opt ->
            assertEquals(ids[i], opt.id)
            assertEquals(labels[i], opt.label)
        }
    }
}
