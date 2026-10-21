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
import com.galaxy.wear.domain.HomeStatus
import com.galaxy.wear.ui.theme.StatusVisual
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
 * 在表盘轮播里一眼看到「和智能体连着没有、有几件事在等」。
 * P2-FIX: Updates every 60 seconds (was 30s) to reduce battery drain.
 * 连接状态变化时 Application 立刻调 requestRefresh()。
 * 显示的是连接,不是三态：三态是电脑上的东西，与手表无关。
 */
class GalaxyTileService : TileService() {

    companion object {
        private const val RESOURCES_VERSION = "1"
        private const val REFRESH_INTERVAL_MS = 60000L // P2-FIX: 60s to reduce battery drain

        // W16-FIX: Request tile refresh from external callers (e.g., on connection change)
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
        val status = HomeStatus.of(
            state = app.connectionState.value,
            needsRepair = app.needsRepair.value,
            pending = app.islandItems.value.size,
        )

        val tile = TileBuilders.Tile.Builder()
            .setResourcesVersion(RESOURCES_VERSION)
            .setFreshnessIntervalMillis(REFRESH_INTERVAL_MS)
            .setTileTimeline(
                TimelineBuilders.Timeline.fromLayoutElement(buildLayout(status))
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

    private fun buildLayout(status: HomeStatus): LayoutElementBuilders.LayoutElement {
        // 颜色从 StatusVisual 取、标签从 HomeStatus 取 —— 表盘应用读的是同一份。
        // Tile 和表盘应用在同一块表上同时可见,各写各的字面量就是肉眼可见的两个灰。
        val dotColor = StatusVisual.argb(status.tone)
        val label = status.label

        return LayoutElementBuilders.Box.Builder()
            .setWidth(expand())
            .setHeight(expand())
            .setModifiers(
                ModifiersBuilders.Modifiers.Builder()
                    .setBackground(
                        ModifiersBuilders.Background.Builder()
                            .setColor(argb(StatusVisual.BACKGROUND_ARGB.toInt()))
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
                        // 状态点 — 圆点(用 Background 的 corner 半径做成圆形)
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
                    .apply {
                        status.detail?.let { detail ->
                            addContent(
                                Text.Builder(this@GalaxyTileService, detail)
                                    .setTypography(Typography.TYPOGRAPHY_CAPTION2)
                                    .setColor(argb(dotColor))
                                    .build()
                            )
                        }
                    }
                    .addContent(
                        LayoutElementBuilders.Spacer.Builder()
                            .setHeight(dp(2f))
                            .build()
                    )
                    .addContent(
                        Text.Builder(this, "GALAXY")
                            .setTypography(Typography.TYPOGRAPHY_CAPTION2)
                            .setColor(argb(StatusVisual.CAPTION_ARGB.toInt()))
                            .build()
                    )
                    .build()
            )
            .build()
    }
}
