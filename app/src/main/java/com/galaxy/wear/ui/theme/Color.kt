package com.galaxy.wear.ui.theme

import androidx.compose.ui.graphics.Color
import com.galaxy.wear.domain.model.Phase

/**
 * Galaxy Wear Color System — PR-UI-V2
 *
 * 原则:
 *   - 主界面黑白灰（三个相位点 SILENT/LIMINAL/MANIFEST）
 *   - 仅波形动画 + 光晕使用星云蓝 accent
 *   - 深空黑底，不是纯黑，带微蓝调增加深度感
 */

// ── Background ──────────────────────────────
val SpaceBlack       = Color(PhaseVisual.BACKGROUND_ARGB)  // 主背景（深空黑，微蓝调）
val SurfaceGlass     = Color(0xFF14141C)  // 卡片/球体底色
val SurfaceElevated  = Color(0xFF1C1C26)  // 抬起态表面

// ── Monochrome Scale ────────────────────────
val WhitePrimary     = Color(0xFFF5F5F7)  // 主文字（米白）
val WhiteSecondary   = Color(0xFF8E8E93)  // 次级文字
// 三个相位色不在这里写字面量 —— 权威在 PhaseVisual，表盘与 Tile 共用同一份。
val GraySilent       = PhaseVisual.statusColor(Phase.SILENT)   // #333333
val GrayLiminal      = PhaseVisual.statusColor(Phase.LIMINAL)  // #666666
// 注意：这不是 MANIFEST 的相位色（那个是 PhaseVisual.statusColor(MANIFEST) = #F5F5F7），
// 而是相位点在「点亮」态下的亮灰。名字是历史遗留，值一直按后者在用。
val GrayManifest     = Color(0xFFE0E0E0)  // 相位点点亮态的亮灰

// ── Nebula Blue Accent (仅用于波形/光晕) ───
val NebulaBlue       = Color(0xFF5B8DEF)  // 主 accent
val NebulaCyan       = Color(0xFF64D2FF)  // 高亮 cyan
val NebulaDim        = Color(0xFF3D5A80)  // 暗态蓝
val NebulaGlow       = Color(0xFF5B8DEF).copy(alpha = 0.15f)
val NebulaGlowStrong = Color(0xFF64D2FF).copy(alpha = 0.30f)

// ── Functional ──────────────────────────────
val ErrorRed         = Color(0xFFCF6679)
val SuccessGreen     = Color(0xFF4CAF50)
val GlassHighlight   = Color(0xFFFFFFFF).copy(alpha = 0.10f)  // 玻璃高光
val GlassEdge        = Color(0xFFFFFFFF).copy(alpha = 0.06f)  // 玻璃边缘
