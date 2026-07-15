package com.galaxy.wear.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import kotlinx.coroutines.delay

/**
 * DynamicIsland — WearOS 灵动岛胶囊
 *
 * 极简设计：表盘上方的小胶囊，点一下全屏展开，再点收回。
 * 显示实时状态 + 未读消息数。展开后显示完整消息/决策。
 *
 * 状态：
 *   COLLAPSED — 小胶囊 (width=wrap, height=28dp)
 *   EXPANDED  — 全屏覆盖 (fillMaxSize)
 */

enum class IslandState { COLLAPSED, EXPANDED }

/**
 * 灵动岛数据项 — 单条消息/决策
 */
data class IslandItem(
    val id: String,
    val title: String,
    val summary: String,
    val source: String = "OpenClawd",  // "OpenClawd" | "微信" | "系统"
    val priority: String = "normal",   // "low" | "normal" | "high"
    val timestamp: Long = System.currentTimeMillis(),
    val options: List<com.galaxy.wear.ui.screens.DecisionOption> = emptyList(),
    val onOptionSelected: ((String) -> Unit)? = null,
)

@Composable
fun DynamicIsland(
    items: List<IslandItem>,
    phaseText: String = "Galaxy",
    onExpand: () -> Unit = {},
    onCollapse: () -> Unit = {},
    onVoiceReply: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var state by remember { mutableStateOf(IslandState.COLLAPSED) }
    val selectedItem by remember(state, items) {
        derivedStateOf { items.firstOrNull() }
    }

    // 呼吸动画
    val infiniteTransition = rememberInfiniteTransition(label = "breathe")
    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.8f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glow"
    )

    Box(modifier = modifier.fillMaxSize()) {
        when (state) {
            IslandState.COLLAPSED -> {
                // ── 小胶囊 ──
                IslandCapsule(
                    phaseText = phaseText,
                    count = items.size,
                    glowAlpha = glowAlpha,
                    hasUrgent = items.any { it.priority == "high" },
                    onClick = {
                        if (items.isNotEmpty()) {
                            state = IslandState.EXPANDED
                            onExpand()
                        }
                    },
                    modifier = Modifier.align(Alignment.TopCenter)
                )
            }

            IslandState.EXPANDED -> {
                // ── 全屏展开 ──
                selectedItem?.let { item ->
                    IslandExpanded(
                        item = item,
                        glowAlpha = glowAlpha,
                        onOptionSelected = { optionId ->
                            item.onOptionSelected?.invoke(optionId)
                            if (items.size <= 1) {
                                state = IslandState.COLLAPSED
                                onCollapse()
                            }
                        },
                        onVoiceReply = onVoiceReply,
                        onDismiss = {
                            state = IslandState.COLLAPSED
                            onCollapse()
                        },
                    )
                } ?: run {
                    // 没有消息，自动收回
                    LaunchedEffect(Unit) {
                        delay(300)
                        state = IslandState.COLLAPSED
                        onCollapse()
                    }
                }
            }
        }
    }
}

// ═══════════════════════════════════════════
// COLLAPSED — 小胶囊
// ═══════════════════════════════════════════

@Composable
private fun IslandCapsule(
    phaseText: String,
    count: Int,
    glowAlpha: Float,
    hasUrgent: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scale by animateFloatAsState(
        targetValue = if (count > 0) 1f else 0.88f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 380f),
        label = "capsule_scale"
    )

    val urgencyColor = if (hasUrgent) Color(0xFFFF453A) else Color(0xFFD4A030)

    // ── 液态玻璃质感 (Liquid Glass) ──
    // 参考 Apple Dynamic Island: 有厚度、有折射、有高光

    Box(
        modifier = modifier
            .padding(top = 6.dp)
            .scale(scale)
            .height(30.dp)
            .wrapContentWidth()
            // 底层: 环境光晕 (ambient glow)
            .shadow(
                elevation = 8.dp,
                shape = RoundedCornerShape(15.dp),
                ambientColor = urgencyColor.copy(alpha = glowAlpha * 0.25f),
                spotColor = Color.Transparent,
            )
            // 中层: 浮出阴影 (lift shadow)
            .shadow(
                elevation = 2.dp,
                shape = RoundedCornerShape(15.dp),
                ambientColor = Color.Black.copy(alpha = 0.4f),
                spotColor = Color.Black.copy(alpha = 0.2f),
            )
            .clip(RoundedCornerShape(15.dp))
            .clickable(onClick = onClick)
            // 玻璃体: 径向渐变模拟折射
            .background(
                Brush.radialGradient(
                    colors = listOf(
                        Color(0xFF2A2A30).copy(alpha = 0.7f),   // 中心亮
                        Color(0xFF18181C).copy(alpha = 0.85f),  // 边缘暗
                        Color(0xFF0E0E12).copy(alpha = 0.9f),   // 外缘最深
                    ),
                    radius = 120f,
                )
            )
            // 玻璃边框: 内发光
            .padding(1.dp)
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFFFFFFFF).copy(alpha = 0.12f),  // 顶部高光
                        Color(0xFFFFFFFF).copy(alpha = 0.03f),  // 中间淡
                        Color(0xFFFFFFFF).copy(alpha = 0.01f),  // 底部几乎无
                    ),
                ),
                shape = RoundedCornerShape(14.dp),
            )
            .padding(horizontal = 14.dp, vertical = 5.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // 状态点 — 液态光球
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .background(
                        Brush.radialGradient(
                            listOf(
                                urgencyColor.copy(alpha = 0.9f),
                                urgencyColor.copy(alpha = 0.4f),
                            ),
                        ),
                        RoundedCornerShape(3.5.dp),
                    )
            )

            // 状态文本 — 清晰锐利
            Text(
                text = phaseText,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFFFFF8EB).copy(alpha = 0.85f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                letterSpacing = 0.3.sp,
            )

            // 未读数 — 微光红点
            if (count > 0) {
                Box(
                    modifier = Modifier
                        .background(
                            urgencyColor.copy(alpha = 0.85f),
                            RoundedCornerShape(8.dp),
                        )
                        .padding(horizontal = 5.dp, vertical = 1.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "$count",
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                    )
                }
            }
        }
    }
}

// ═══════════════════════════════════════════
// EXPANDED — 全屏
// ═══════════════════════════════════════════

@Composable
private fun IslandExpanded(
    item: IslandItem,
    glowAlpha: Float,
    onOptionSelected: (String) -> Unit,
    onVoiceReply: () -> Unit,
    onDismiss: () -> Unit,
) {
    val enterAlpha by animateFloatAsState(
        targetValue = 1f,
        animationSpec = tween(300),
        label = "enter"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .alpha(enterAlpha)
            .background(Color.Black.copy(alpha = 0.92f))
            .clickable(onClick = onDismiss),  // 点击空白处收回
    ) {
        // 使用已有的 DecisionScreen
        com.galaxy.wear.ui.screens.DecisionScreen(
            title = item.title,
            summary = item.summary,
            options = item.options,
            onOptionSelected = onOptionSelected,
            onVoiceReply = onVoiceReply,
            onDismiss = onDismiss,
        )

        // 顶部来源标注
        Text(
            text = "来自 ${item.source}",
            fontSize = 9.sp,
            color = Color(0xFFD4A030).copy(alpha = 0.5f),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 4.dp)
        )
    }
}
