package com.galaxy.wear.domain

import com.galaxy.wear.data.AIPConnectionState

/** 首页与 Tile 上「这块表和智能体的关系」的四档。 */
enum class StatusTone { CONNECTED, CONNECTING, OFFLINE, NEEDS_REPAIR }

/**
 * 首页 / Tile 显示什么：**与智能体的连接**，和**有几件事在等你**。
 *
 * 为什么不是三态
 * ==============
 * 三态（静默 / 临界 / 显现）是**电脑这具身体**的表达，不是手表要显示的东西，也不是手表要上报的东西。
 * 手表在这套系统里是智能体的「成员」，它与智能体之间只有：说话、提问、登记与回应、通话。
 * 所以手表首页该回答的是：连着没有、要不要重新配对、有没有事在等我。
 *
 * 「需要重新配对」优先于连接态：令牌过期后连接态只会是「出错」或「未连接」，用户看到的是
 * 一个没法自己恢复的故障，而真正要做的事只有一件。
 *
 * 纯 Kotlin，不碰 Android —— 单测直接跑。
 */
data class HomeStatus(
    val tone: StatusTone,
    val label: String,
    /** 在等人处理的事（待回答的决策、未读消息）的条数。 */
    val pending: Int,
) {
    /** 第二行小字；没有事在等就没有。 */
    val detail: String? get() = if (pending > 0) "$pending 条待处理" else null

    companion object {
        fun of(state: AIPConnectionState, needsRepair: Boolean, pending: Int): HomeStatus {
            val p = pending.coerceAtLeast(0)
            if (needsRepair) return HomeStatus(StatusTone.NEEDS_REPAIR, "需要重新配对", p)
            return when (state) {
                AIPConnectionState.AUTHENTICATED -> HomeStatus(StatusTone.CONNECTED, "已连接", p)
                AIPConnectionState.CONNECTED,
                AIPConnectionState.CONNECTING -> HomeStatus(StatusTone.CONNECTING, "连接中…", p)
                AIPConnectionState.ERROR -> HomeStatus(StatusTone.OFFLINE, "连接出错", p)
                AIPConnectionState.DISCONNECTED -> HomeStatus(StatusTone.OFFLINE, "未连接", p)
            }
        }
    }
}
