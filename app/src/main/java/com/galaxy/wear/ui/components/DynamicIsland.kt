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
        targetValue = if (count > 0) 1f else 0.85f,
        animationSpec = spring(stiffness = 400f, damping = 25f),
        label = "capsule_scale"
    )

    val urgencyColor = if (hasUrgent) Color(0xFFB00020) else Color(0xFFD4A030)

    Box(
        modifier = modifier
            .padding(top = 8.dp)
            .scale(scale)
            .height(28.dp)
            .wrapContentWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            // 玻璃态背景
            .background(
                Brush.horizontalGradient(
                    colors = listOf(
                        Color(0xFF1A1A1E).copy(alpha = 0.85f),
                        Color(0xFF121214).copy(alpha = 0.9f),
                    )
                )
            )
            // 发光边框
            .shadow(
                elevation = 4.dp,
                shape = RoundedCornerShape(14.dp),
                ambientColor = urgencyColor.copy(alpha = glowAlpha),
                spotColor = urgencyColor.copy(alpha = glowAlpha * 0.6f),
            )
            .padding(horizontal = 12.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // 状态点 — 脉冲
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(urgencyColor.copy(alpha = 0.6f + glowAlpha * 0.4f))
            )

            // 状态文本
            Text(
                text = phaseText,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = Color(0xFFFFF8EB).copy(alpha = 0.8f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            // 未读数
            if (count > 0) {
                Text(
                    text = "• $count",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = urgencyColor.copy(alpha = 0.9f),
                )
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
