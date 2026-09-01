package com.galaxy.wear.tile

import android.content.Context
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.DimensionBuilders.expand
import androidx.wear.protolayout.DimensionBuilders.wrap
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.protolayout.material.Text
import androidx.wear.protolayout.material.Typography
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.galaxy.wear.GalaxyWearApplication
import com.galaxy.wear.domain.model.Phase
import com.galaxy.wear.ui.theme.PhaseVisual
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/**
 * GalaxyTileService — Wear OS Tile (glanceable widget)
 *
 * 统一走 androidx.wear.protolayout 这一套(现行受支持的 Tiles 布局 API):
 *   - 布局构建器全部来自 androidx.wear.protolayout.*(LayoutElementBuilders/
 *     ModifiersBuilders/ColorBuilders/DimensionBuilders + material.Text/Typography)
 *   - 服务框架仍来自 androidx.wear.tiles(TileService/TileBuilders/RequestBuilders)
 *   - Tile 用 setTileTimeline(...) 装载时间线;资源用 onTileResourcesRequest 回调
 * 之前同时 wildcard 了 tiles.* 与 protolayout.* 两套 builder,导致每个 builder 重载歧义、
 * 且 deprecated-tiles 一套缺 setCorner/ColorBuilders.Color 等成员 —— 收敛到 protolayout 后消除。
 *
 * Shows current phase at a glance on the watch face carousel.
 * P2-FIX: Updates every 60 seconds (was 30s) to reduce battery drain.
 * Phase changes trigger immediate refresh via requestRefresh().
 */
class GalaxyTileService : TileService() {

    companion object {
        private const val RESOURCES_VERSION = "1"
        private const val REFRESH_INTERVAL_MS = 60000L // P2-FIX: 60s to reduce battery drain

        // W16-FIX: Request tile refresh from external callers (e.g., on phase change)
        fun requestRefresh(context: Context) {
            try {
                getUpdater(context).requestUpdate(GalaxyTileService::class.java)
            } catch (e: Exception) {
                android.util.Log.w("GalaxyTileService", "Tile refresh request failed: ${e.message}")
            }
        }
    }

    override fun onTileRequest(
        requestParams: RequestBuilders.TileRequest
    ): ListenableFuture<TileBuilders.Tile> {
        val app = application as GalaxyWearApplication
        val phase = app.phase.value

        val tile = TileBuilders.Tile.Builder()
            .setResourcesVersion(RESOURCES_VERSION)
            .setFreshnessIntervalMillis(REFRESH_INTERVAL_MS)
            .setTileTimeline(
                TimelineBuilders.Timeline.fromLayoutElement(buildLayout(phase))
            )
            .build()
        return Futures.immediateFuture(tile)
    }

    override fun onTileResourcesRequest(
        requestParams: RequestBuilders.ResourcesRequest
    ): ListenableFuture<ResourceBuilders.Resources> {
        return Futures.immediateFuture(
            ResourceBuilders.Resources.Builder()
                .setVersion(RESOURCES_VERSION)
                .build()
        )
    }

    private fun buildLayout(phase: Phase): LayoutElementBuilders.LayoutElement {
        // 颜色与标签都从 PhaseVisual 取 —— 这里曾经各写各的字面量,四项里漂了三项
        // (LIMINAL #808080 vs #666666、MANIFEST #E0E0E0 vs #F5F5F7、底色纯黑 vs #0A0A0F)。
        // Tile 和表盘应用在同一块表上同时可见,漂了就是肉眼可见的两个灰。
        val dotColor = PhaseVisual.statusArgb(phase)
        val label = PhaseVisual.label(phase)

        return LayoutElementBuilders.Box.Builder()
            .setWidth(expand())
            .setHeight(expand())
            .setModifiers(
                ModifiersBuilders.Modifiers.Builder()
                    .setBackground(
                        ModifiersBuilders.Background.Builder()
                            .setColor(argb(PhaseVisual.BACKGROUND_ARGB.toInt()))
                            .build()
                    )
                    .build()
            )
            .addContent(
                LayoutElementBuilders.Column.Builder()
                    .setWidth(wrap())
                    .setHeight(wrap())
                    .setHorizontalAlignment(
                        LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER
                    )
                    .addContent(
                        // Phase dot — 圆点(用 Background 的 corner 半径做成圆形)
                        LayoutElementBuilders.Box.Builder()
                            .setWidth(dp(12f))
                            .setHeight(dp(12f))
                            .setModifiers(
                                ModifiersBuilders.Modifiers.Builder()
                                    .setBackground(
                                        ModifiersBuilders.Background.Builder()
                                            .setColor(argb(dotColor))
                                            .setCorner(
                                                ModifiersBuilders.Corner.Builder()
                                                    .setRadius(dp(6f))
                                                    .build()
                                            )
                                            .build()
                                    )
                                    .build()
                            )
                            .build()
                    )
                    .addContent(
                        LayoutElementBuilders.Spacer.Builder()
                            .setHeight(dp(4f))
                            .build()
                    )
                    .addContent(
                        Text.Builder(this, label)
                            .setTypography(Typography.TYPOGRAPHY_CAPTION1)
                            .setColor(argb(dotColor))
                            .build()
                    )
                    .addContent(
                        LayoutElementBuilders.Spacer.Builder()
                            .setHeight(dp(2f))
                            .build()
                    )
                    .addContent(
                        Text.Builder(this, "GALAXY")
                            .setTypography(Typography.TYPOGRAPHY_CAPTION2)
                            .setColor(argb(PhaseVisual.CAPTION_ARGB.toInt()))
                            .build()
                    )
                    .build()
            )
            .build()
    }
}
