package com.lumiv.wear.ui.screens

import android.content.Context
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.*

/**
 * DecisionOption — 决策选项数据类
 *
 * @param id 选项唯一标识（发送到后端）
 * @param label 显示文本
 * @param icon 图标资源 ID（android.R.drawable.xxx）
 * @param isDestructive 是否为破坏性操作（用红色背景警示）
 */
data class DecisionOption(
    val id: String,
    val label: String,
    val icon: Int = android.R.drawable.ic_menu_help,
    val isDestructive: Boolean = false,
)

/**
 * DecisionScreen — OpenClawd 决策卡片
 *
 * 当 OpenClawd 需要人类确认时显示的全屏决策界面。
 * 复用 HomeScreen 的 ScalingLazyColumn + Chip 设计模式，
 * 保持 WearOS 圆形屏幕的视觉一致性。
 *
 * 设计要点：
 * - 黑色背景（与 HomeScreen 一致）
 * - 高对比度文字（MaterialTheme.colors.primary / onSurfaceVariant）
 * - 破坏性操作红色警示
 * - 底部语音回复入口
 * - 选项点击时触发触觉反馈
 */
@Composable
fun DecisionScreen(
    title: String,
    summary: String,
    options: List<DecisionOption>,
    onOptionSelected: (String) -> Unit,
    onVoiceReply: () -> Unit,
    onDismiss: (() -> Unit)? = null,
) {
    val listState = rememberScalingLazyListState(initialCenterItemIndex = 0)
    val context = LocalContext.current

    Scaffold(
        vignette = { Vignette(vignettePosition = VignettePosition.TopAndBottom) },
        positionIndicator = { PositionIndicator(scalingLazyListState = listState) }
    ) {
        ScalingLazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
            state = listState,
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // ── 标题（警示图标 + 文本） ──────────────────────────────
            item {
                Text(
                    text = title,
                    style = MaterialTheme.typography.title3,
                    color = MaterialTheme.colors.primary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp, bottom = 2.dp)
                )
            }

            // ── 摘要描述 ────────────────────────────────────────────
            item {
                Text(
                    text = summary,
                    style = MaterialTheme.typography.body2,
                    color = MaterialTheme.colors.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }

            // ── 分隔线 ──────────────────────────────────────────────
            item {
                Divider(
                    color = MaterialTheme.colors.onSurfaceVariant.copy(alpha = 0.2f),
                    modifier = Modifier
                        .fillMaxWidth(0.7f)
                        .padding(vertical = 6.dp)
                )
            }

            // ── 决策选项按钮 ─────────────────────────────────────────
            // 复用 HomeScreen 的 Chip 模式，但用标准尺寸（非 CompactChip）
            // 以确保触摸目标足够大
            items(options.size) { index ->
                val option = options[index]
                DecisionOptionChip(
                    option = option,
                    onSelected = {
                        triggerHaptic(context, HapticType.MESSAGE_ARRIVAL)
                        onOptionSelected(option.id)
                    }
                )
            }

            // ── 语音回复按钮 ─────────────────────────────────────────
            item {
                Spacer(modifier = Modifier.height(4.dp))
                Chip(
                    onClick = {
                        triggerHaptic(context, HapticType.MESSAGE_ARRIVAL)
                        onVoiceReply()
                    },
                    label = {
                        Text(
                            "语音回复",
                            style = MaterialTheme.typography.button
                        )
                    },
                    icon = {
                        Icon(
                            android.R.drawable.ic_btn_speak_now,
                            contentDescription = "语音",
                            modifier = Modifier.size(20.dp)
                        )
                    },
                    colors = ChipDefaults.secondaryChipColors(),
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // ── 取消/关闭（可选） ────────────────────────────────────
            if (onDismiss != null) {
                item {
                    CompactChip(
                        onClick = onDismiss,
                        label = {
                            Text(
                                "关闭",
                                style = MaterialTheme.typography.caption2,
                                color = MaterialTheme.colors.onSurfaceVariant
                            )
                        },
                        colors = ChipDefaults.chipColors(
                            backgroundColor = Color.Transparent
                        ),
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }
    }
}

/**
 * 单个决策选项的 Chip 组件。
 *
 * - 非破坏性选项：半透明表面色背景
 * - 破坏性选项（isDestructive=true）：红色警示背景
 * - 选中时有轻微缩放动画
 */
@Composable
private fun DecisionOptionChip(
    option: DecisionOption,
    onSelected: () -> Unit,
) {
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.96f else 1f,
        animationSpec = spring(stiffness = 400f, damping = 20f),
        label = "option_scale_${option.id}"
    )

    val chipColors = if (option.isDestructive) {
        ChipDefaults.primaryChipColors(
            backgroundColor = Color(0xFFB00020).copy(alpha = 0.85f)
        )
    } else {
        ChipDefaults.primaryChipColors(
            backgroundColor = MaterialTheme.colors.surface.copy(alpha = 0.65f)
        )
    }

    Chip(
        onClick = {
            pressed = true
            onSelected()
        },
        label = {
            Text(
                option.label,
                style = MaterialTheme.typography.button,
                color = if (option.isDestructive) Color.White else MaterialTheme.colors.onSurface
            )
        },
        icon = {
            Icon(
                option.icon,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = if (option.isDestructive) Color.White else MaterialTheme.colors.primary
            )
        },
        colors = chipColors,
        modifier = Modifier
            .fillMaxWidth()
            .scale(scale)
    )

    // 自动释放 pressed 状态（防止点击后保持缩放）
    LaunchedEffect(pressed) {
        if (pressed) {
            kotlinx.coroutines.delay(150)
            pressed = false
        }
    }
}
