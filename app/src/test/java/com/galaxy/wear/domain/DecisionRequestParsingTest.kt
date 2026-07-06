package com.galaxy.wear.domain

import com.galaxy.wear.ui.screens.DecisionOption
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
