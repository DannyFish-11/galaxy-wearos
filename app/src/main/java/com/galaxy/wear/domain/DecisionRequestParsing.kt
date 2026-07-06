package com.galaxy.wear.domain

import com.galaxy.wear.ui.screens.DecisionOption
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
