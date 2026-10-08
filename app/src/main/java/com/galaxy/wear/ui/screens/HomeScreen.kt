package com.galaxy.wear.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.platform.LocalContext
import com.galaxy.wear.ui.triggerHaptic
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.*
import com.galaxy.wear.domain.HomeStatus
import com.galaxy.wear.domain.StatusTone
import com.galaxy.wear.ui.theme.*
import kotlinx.coroutines.launch

/**
 * HomeScreen — PR-UI-V2: Watch face-inspired main screen
 *
 * PR-CIRCULAR-FIX: Changed chip layout from horizontal Row (clips on round screen)
 * to vertical Column (fits within circular display bounds).
 *
 * Entries are vertically stacked, centered, each 0.58 fillMaxWidth to stay clear
 * of circular edges.
 *
 * 首页回答的是「这块表和智能体的关系」：连着没有、要不要重新配对、有几件事在等你。
 * 不显示三态 —— 那是电脑上的东西，不是手表的。
 */
@Composable
fun HomeScreen(
    status: HomeStatus,
    isAmbient: Boolean = false,
    onVoice: () -> Unit,
    /** 进入实时通话。与 [onVoice] 的一问一答是两条路,不是同一件事的两种入口。 */
    onCall: () -> Unit,
    /** 进入会话记录。 */
    onConversation: () -> Unit,
    onDevices: () -> Unit,
    onSettings: () -> Unit,
    /** 令牌失效时的唯一出路：回到配对页。 */
    onRepair: () -> Unit = {},
    islandItems: List<com.galaxy.wear.ui.components.IslandItem> = emptyList(),
) {
    val listState = rememberScalingLazyListState(initialCenterItemIndex = 1)
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    val app = context.applicationContext as com.galaxy.wear.GalaxyWearApplication

    Scaffold(
        vignette = { Vignette(vignettePosition = VignettePosition.TopAndBottom) },
        positionIndicator = { PositionIndicator(scalingLazyListState = listState) }
    ) {
        ScalingLazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(SpaceBlack)
                .onRotaryScrollEvent { event ->
                    coroutineScope.launch { listState.scrollBy(event.verticalScrollPixels) }
                    true
                },
            horizontalAlignment = Alignment.CenterHorizontally,
            state = listState,
        ) {
            // ── GALAXY Title ─────────────────────────
            item {
                Text(
                    text = "GALAXY",
                    style = MaterialTheme.typography.display3,
                    color = WhitePrimary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 4.dp, bottom = 6.dp)
                )
            }

            // ── 与智能体的连接 ───────────────────────
            item {
                // 颜色取自 StatusVisual —— Tile 读的是同一份，
                // 否则表盘和 Tile 并排时同一档状态会是两个灰。
                val color = Color(StatusVisual.argbLong(status.tone))
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        Modifier
                            .size(8.dp)
                            .background(color, CircleShape)
                    )
                    Text(
                        text = status.label,
                        style = MaterialTheme.typography.title3,
                        color = color,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                    status.detail?.let { detail ->
                        Text(
                            text = detail,
                            style = MaterialTheme.typography.caption3,
                            color = color.copy(alpha = 0.7f),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = 1.dp)
                        )
                    }
                }
            }

            // ── Action chips — VERTICAL for circular screen ──
            item {
                Column(
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .padding(top = 12.dp, bottom = 8.dp)
                        .fillMaxWidth(0.58f) // ← PR-CIRCULAR-FIX: stay inside round edges
                ) {
                    if (status.tone == StatusTone.NEEDS_REPAIR) {
                        CompactChip(
                            onClick = { triggerHaptic(context); onRepair() },
                            label = { Text("重新配对", style = MaterialTheme.typography.caption2) },
                            icon = {
                                Icon(
                                    imageVector = Icons.Default.LinkOff,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp)
                                )
                            },
                            colors = ChipDefaults.primaryChipColors(),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    CompactChip(
                        onClick = { triggerHaptic(context); onVoice() },
                        label = { Text("语音", style = MaterialTheme.typography.caption2) },
                        icon = {
                            Icon(
                                imageVector = Icons.Default.Mic,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp)
                            )
                        },
                        colors = ChipDefaults.primaryChipColors(),
                        modifier = Modifier.fillMaxWidth()
                    )
                    CompactChip(
                        onClick = { triggerHaptic(context); onCall() },
                        label = { Text("通话", style = MaterialTheme.typography.caption2) },
                        icon = {
                            Icon(
                                imageVector = Icons.Default.Call,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp)
                            )
                        },
                        colors = ChipDefaults.primaryChipColors(),
                        modifier = Modifier.fillMaxWidth()
                    )
                    CompactChip(
                        onClick = { triggerHaptic(context); onConversation() },
                        label = { Text("会话", style = MaterialTheme.typography.caption2) },
                        icon = {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.List,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp)
                            )
                        },
                        colors = ChipDefaults.secondaryChipColors(),
                        modifier = Modifier.fillMaxWidth()
                    )
                    CompactChip(
                        onClick = { triggerHaptic(context); onDevices() },
                        label = { Text("设备", style = MaterialTheme.typography.caption2) },
                        icon = {
                            Icon(
                                imageVector = Icons.Default.Devices,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp)
                            )
                        },
                        colors = ChipDefaults.secondaryChipColors(),
                        modifier = Modifier.fillMaxWidth()
                    )
                    CompactChip(
                        onClick = { triggerHaptic(context); onSettings() },
                        label = { Text("设置", style = MaterialTheme.typography.caption2) },
                        icon = {
                            Icon(
                                imageVector = Icons.Default.Settings,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp)
                            )
                        },
                        colors = ChipDefaults.secondaryChipColors(),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }

    // ── DYNAMIC ISLAND(全屏 overlay,置于最上层)──
    // 真 bug 修复:之前把 DynamicIsland 放在 ScalingLazyColumn 的 item{} 里。
    // Lazy 列表沿滚动轴用无限高度约束测量 item,而灵动岛点开(EXPANDED)后内部渲染
    // DecisionScreen —— 又是一个纵向 ScalingLazyColumn,纵向可滚动组件在无限高度
    // 约束下测量会直接抛 IllegalStateException("Vertically scrollable component was
    // measured with an infinity maximum height constraints...")→ 点一下胶囊必崩。
    // DynamicIsland 根 Box 本来就是 fillMaxSize + 胶囊 TopCenter 的 overlay 设计,
    // 挪到 Scaffold 外作全屏兄弟节点:胶囊悬浮在表盘顶部,展开态获得有界约束、
    // 真正全屏,且未命中胶囊的触摸仍透传给下层列表(Box 本身不消费手势)。
    if (islandItems.isNotEmpty()) {
        com.galaxy.wear.ui.components.DynamicIsland(
            items = islandItems,
            statusText = status.label,
            onVoiceReply = onVoice,
            // Dismissing (tap outside / "关闭") only closes the expanded
            // overlay by default - without this, the same item would
            // still be in app.islandItems and pop right back up next
            // time the capsule is tapped. Actually drop the currently-
            // shown item (DynamicIsland always shows items.firstOrNull()).
            onCollapse = { islandItems.firstOrNull()?.let { app.dismissIslandItem(it.id) } },
            modifier = Modifier.fillMaxSize()
        )
    }
}
