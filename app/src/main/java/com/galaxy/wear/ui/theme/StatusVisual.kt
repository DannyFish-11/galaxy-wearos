package com.galaxy.wear.ui.theme

import com.galaxy.wear.domain.StatusTone

/**
 * 首页与 Tile 的「连接状态」长什么样 —— **唯一的取色处**。
 *
 * 为什么要有这么一处
 * ==================
 * 状态的「点 + 文字」在手表上有两个渲染器，它们**同时可见**：表盘应用（Compose）与表盘 Tile
 * （protolayout）。两边各写各的字面量，结果是同一时刻同一台表上，同一档状态有两种颜色 ——
 * 编译得过、跑得通，只有把两者并排看的人才会觉得「这两个灰不一样」。
 *
 * protolayout 要带符号的 ARGB [Int]，Compose 要 `Color`。所以权威值以 [Long] 保存，两边各取各的形态：
 * Compose 侧 `Color(StatusVisual.argbLong(tone))`，protolayout 侧 [argb]。
 * `0xFFxxxxxx` 在 Kotlin 里超出 [Int] 范围因而是 [Long]，[argb] 里的 `.toInt()` 就是取带符号 ARGB 的那一步。
 *
 * 纯 Kotlin（不 import Compose），所以单测能直接钉。
 */
object StatusVisual {

    /** 深空黑底（微蓝调）。表盘与 Tile 共用，避免 Tile 退回纯黑。 */
    const val BACKGROUND_ARGB: Long = 0xFF0A0A0F

    /** Tile 底部「GALAXY」字样的弱化灰。 */
    const val CAPTION_ARGB: Long = 0xFF555555

    fun argbLong(tone: StatusTone): Long = when (tone) {
        StatusTone.CONNECTED -> 0xFFF5F5F7
        StatusTone.CONNECTING -> 0xFF999999
        StatusTone.OFFLINE -> 0xFF666666
        StatusTone.NEEDS_REPAIR -> 0xFFCF6679
    }

    /** protolayout（Tile）侧取色口：带符号 ARGB [Int]。 */
    fun argb(tone: StatusTone): Int = argbLong(tone).toInt()
}
