package com.galaxy.wear.domain

/**
 * 灵动岛上一条「决策」条目的 id 与决策本身的 id 怎么互相换算。
 *
 * 为什么单独一处
 * ==============
 * 决策卡上的「语音回复」要回答的是**这条决策**，而不是随便开一次通用语音查询。回答需要
 * 决策 id，而界面手里只有岛上条目的 id（`decision_<决策 id>`）。这个前缀原先只是 Application
 * 里两处各写一遍的字符串字面量 —— 一处造、一处拆，改一边另一边就悄悄对不上。
 *
 * 纯 Kotlin，不碰 Android。
 */
object DecisionIslandIds {

    private const val PREFIX = "decision_"

    fun islandId(decisionId: String): String = PREFIX + decisionId

    /** 岛上条目是决策卡时返回它的决策 id；不是（普通消息卡）或 id 为空返回 null。 */
    fun decisionIdOf(islandId: String): String? =
        islandId.takeIf { it.startsWith(PREFIX) }?.removePrefix(PREFIX)?.takeIf { it.isNotEmpty() }
}
