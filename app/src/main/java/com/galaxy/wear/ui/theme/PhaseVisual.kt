package com.galaxy.wear.ui.theme

import androidx.compose.ui.graphics.Color
import com.galaxy.wear.domain.model.Phase

/**
 * 相位视觉的**唯一权威**：一个相位长什么样、叫什么，只在这里写一遍。
 *
 * 为什么需要它
 * ------------
 * 相位的「点 + 文字」在手表上有两个渲染器，它们**同时可见**：
 *
 *  - 表盘应用（Compose）—— [com.galaxy.wear.ui.screens.HomeScreen]
 *  - 表盘 Tile（protolayout）—— [com.galaxy.wear.tile.GalaxyTileService]
 *
 * 两边各写各的字面量，结果是同一时刻同一台表上，同一个相位有两种颜色：
 *
 * | 相位 / 元素 | 表盘应用（改前） | Tile（改前） |
 * |---|---|---|
 * | SILENT   | `#333333` | `#333333` |
 * | LIMINAL  | `#666666` | `#808080` ← 漂 |
 * | MANIFEST | `#F5F5F7` | `#E0E0E0` ← 漂 |
 * | 背景     | `#0A0A0F` | `#000000` ← 漂 |
 *
 * 四项里漂了三项。这类漂移没有任何自动化能发现 —— 两边都编译得过、都跑得通，
 * 只有把表盘和 Tile 并排看的人才会觉得「这两个灰不一样」，而那个人通常就是用户。
 *
 * 两种渲染器的取色口
 * ------------------
 * protolayout 不认 Compose 的 [Color]，它要的是带符号的 ARGB [Int]；Compose 要
 * [Color]。所以权威值以 [Long] 保存，两边各取各的形态，但**源头只有一个**：
 *
 *  - Compose 侧：[statusColor]
 *  - protolayout 侧：[statusArgb]
 *
 * `0xFFxxxxxx` 在 Kotlin 里超出 [Int] 范围因而是 [Long]，直接传给 protolayout 的
 * `argb()` 不能编译；[statusArgb] 里的 `.toInt()` 就是那一步取带符号 ARGB 的转换。
 */
object PhaseVisual {

    /** 深空黑底（微蓝调）。表盘与 Tile 共用，避免 Tile 退回纯黑。 */
    const val BACKGROUND_ARGB: Long = 0xFF0A0A0F

    /** Tile 底部「GALAXY」字样的弱化灰。 */
    const val CAPTION_ARGB: Long = 0xFF555555

    private const val SILENT_ARGB: Long = 0xFF333333
    private const val LIMINAL_ARGB: Long = 0xFF666666
    private const val MANIFEST_ARGB: Long = 0xFFF5F5F7

    /**
     * 相位的中文短标签。
     *
     * 用 `when` 而不是给 [Phase] 加字段：[Phase] 是 domain 层的核心概念，
     * 不该知道自己在界面上叫什么。
     */
    fun label(phase: Phase): String = when (phase) {
        Phase.SILENT -> "静默"
        Phase.LIMINAL -> "临界"
        Phase.MANIFEST -> "显现"
    }

    /** 相位状态色的权威 ARGB 值。 */
    fun statusArgbLong(phase: Phase): Long = when (phase) {
        Phase.SILENT -> SILENT_ARGB
        Phase.LIMINAL -> LIMINAL_ARGB
        Phase.MANIFEST -> MANIFEST_ARGB
    }

    /** protolayout（Tile）侧取色口：带符号 ARGB [Int]。 */
    fun statusArgb(phase: Phase): Int = statusArgbLong(phase).toInt()

    /** Compose 侧取色口。 */
    fun statusColor(phase: Phase): Color = Color(statusArgbLong(phase))
}
