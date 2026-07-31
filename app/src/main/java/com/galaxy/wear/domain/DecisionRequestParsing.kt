package com.galaxy.wear.domain

import com.galaxy.wear.domain.model.DecisionOption
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Parses a decision_request payload's `options` array: `[{"id":..,"label":..}]`.
 *
 * Extracted out of GalaxyWearApplication.handleDecisionRequest() so this pure
 * parsing logic can be unit-tested directly (Application subclasses can't be
 * instantiated outside an Android runtime).
 *
 * Returns the raw option ids (for the legacy notification-action extras, which
 * only need ids to match back to V2's registered options) alongside the full
 * [DecisionOption] list (id + display label, falling back to the id when no
 * label is present) for in-app rendering.
 */
/**
 * 把「id 数组」与「label 数组」重新配成 [DecisionOption]。
 *
 * 为什么需要这个:决策通知的动作按钮**要显示给人看**,而此前只有 id 被送到
 * 通知层(源码注释还写着"通知动作只需要 id")。于是手腕上弹出来的按钮文字
 * 是 `approve` / `deny` 这种协议字面量,而不是「批准」「不要」——
 * 一个瞟一眼就要做决定的界面,却印着英文小写的协议 id。
 *
 * 两个数组长度对不上时(跨进程传递被截断、上游漏发 label)**退回用 id 显示**,
 * 而不是抛异常或错位配对 —— 错位比难看严重得多:点「不要」可能执行成「批准」。
 */
fun pairOptionLabels(ids: List<String>, labels: List<String>): List<DecisionOption> =
    ids.mapIndexed { index, id ->
        val label = labels.getOrNull(index)?.takeIf { it.isNotBlank() } ?: id
        DecisionOption(id = id, label = label)
    }

fun parseDecisionOptions(optionsArr: JsonArray?): Pair<List<String>, List<DecisionOption>> {
    val optionIds = ArrayList<String>()
    val decisionOptions = ArrayList<DecisionOption>()
    optionsArr?.forEach { el ->
        val o = el as? JsonObject ?: return@forEach
        val id = o["id"]?.jsonPrimitive?.content ?: return@forEach
        val label = o["label"]?.jsonPrimitive?.content ?: id
        optionIds.add(id)
        decisionOptions.add(DecisionOption(id = id, label = label))
    }
    return optionIds to decisionOptions
}
