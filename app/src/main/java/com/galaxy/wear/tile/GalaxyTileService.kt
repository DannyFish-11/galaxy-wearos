package com.galaxy.wear.tile

import android.util.Log
import androidx.wear.tiles.*
import androidx.wear.tiles.material.*
import androidx.wear.protolayout.*
import androidx.wear.protolayout.material.*
import androidx.wear.protolayout.DimensionBuilders.*
import androidx.wear.protolayout.ModifiersBuilders.*
import com.galaxy.wear.GalaxyWearApplication
import com.galaxy.wear.domain.model.Phase
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/**
 * GalaxyTileService — Wear OS Tile (glanceable widget)
 *
 * C6 FIX: Unified to use only the traditional protolayout Tiles API.
 * Removed unused Glance imports that were causing API confusion.
 *
 * Shows current phase at a glance on the watch face carousel.
 * P2-FIX: Updates every 60 seconds (was 30s) to reduce battery drain.
 * Phase changes trigger immediate refresh via requestRefresh().
 */
class GalaxyTileService : androidx.wear.tiles.TileService() {

    companion object {
        private const val RESOURCES_VERSION = "1"
        private const val REFRESH_INTERVAL_MS = 60000L // P2-FIX: 60s to reduce battery drain

        // W16-FIX: Request tile refresh from external callers (e.g., on phase change)
        fun requestRefresh(context: android.content.Context) {
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

        return Futures.immediateFuture(
            TileBuilders.Tile.Builder()
                .setResourcesVersion(RESOURCES_VERSION)
                .setFreshnessIntervalMillis(REFRESH_INTERVAL_MS)
                .setTimeline(
                    TimelineBuilders.Timeline.Builder()
                        .addTimelineEntry(
                            TimelineBuilders.TimelineEntry.Builder()
                                .setLayout(
                                    LayoutElementBuilders.Layout.Builder()
                                        .setRoot(buildLayout(phase))
                                        .build()
                                )
                                .build()
                        )
                        .build()
                )
                .build()
        )
    }

    override fun onResourcesRequest(
        requestParams: RequestBuilders.ResourcesRequest
    ): ListenableFuture<ResourceBuilders.Resources> {
        return Futures.immediateFuture(
            ResourceBuilders.Resources.Builder()
                .setVersion(RESOURCES_VERSION)
                .build()
        )
    }

    private fun buildLayout(phase: Phase): LayoutElementBuilders.LayoutElement {
        val (dotColor, label) = when (phase) {
            Phase.SILENT -> 0xFF333333 to "静默"
            Phase.LIMINAL -> 0xFF808080 to "临界"
            Phase.MANIFEST -> 0xFFE0E0E0 to "显现"
        }

        return LayoutElementBuilders.Box.Builder()
            .setWidth(expand())
            .setHeight(expand())
            .setModifiers(
                ModifiersBuilders.Modifiers.Builder()
                    .setBackground(
                        ModifiersBuilders.Background.Builder()
                            .setColor(argb(0xFF000000))
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
                        // Phase dot
                        LayoutElementBuilders.Box.Builder()
                            .setWidth(dp(12f))
                            .setHeight(dp(12f))
                            .setModifiers(
                                ModifiersBuilders.Modifiers.Builder()
                                    .setCorner(
                                        ModifiersBuilders.Corner.Builder()
                                            .setRadius(dp(6f))
                                            .build()
                                    )
                                    .setBackground(
                                        ModifiersBuilders.Background.Builder()
                                            .setColor(argb(dotColor.toInt()))
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
                            .setColor(argb(dotColor.toInt()))
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
                            .setColor(argb(0xFF555555))
                            .build()
                    )
                    .build()
            )
            .build()
    }
}

/**
 * Helper to create ARGB color.
 * NOTE: 0xFF000000.toInt() produces -16777216 in Kotlin (signed 32-bit Int overflow),
 * which is expected behavior. ColorBuilders.Color.Builder().setArgb() accepts an Int
 * parameter and interprets the sign bit as the alpha channel (0xFF = 255 = fully opaque).
 * Do NOT "fix" with .toUInt() — the Tiles API requires Int.
 */
private fun argb(color: Int): ColorBuilders.Color {
    return ColorBuilders.Color.Builder().setArgb(color).build()
}
