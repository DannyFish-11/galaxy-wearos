package com.galaxy.wear.ui.theme

import androidx.compose.runtime.Composable
import androidx.wear.compose.material.Colors
import androidx.wear.compose.material.MaterialTheme

/**
 * Galaxy Wear Theme — PR-UI-V2
 *
 * 深色太空主题，黑白灰主调。
 * 波形/光晕的星云蓝色通过 Color.kt 的 accent 常量直接使用，
 * 不放在 theme 色板中（保持主界面纯黑白灰）。
 */
private val GalaxyDarkColors = Colors(
    primary = GrayManifest,          // #E0E0E0 亮灰（主交互色）
    primaryVariant = GrayLiminal,    // #666666 中灰
    secondary = WhiteSecondary,      // #8E8E93
    secondaryVariant = WhiteSecondary,
    background = SpaceBlack,         // #0A0A0F 深空黑
    surface = SurfaceGlass,          // #14141C 玻璃面
    error = ErrorRed,
    onPrimary = SpaceBlack,          // 主色上的文字用深色
    onSecondary = WhitePrimary,
    onBackground = WhitePrimary,     // #F5F5F7 背景上的文字
    onSurface = WhitePrimary,
    onError = SpaceBlack,
)

@Composable
fun GalaxyWearTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colors = GalaxyDarkColors,
        typography = GalaxyTypography,
        content = content
    )
}
