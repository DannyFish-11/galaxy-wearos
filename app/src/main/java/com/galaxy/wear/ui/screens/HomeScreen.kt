package com.galaxy.wear.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Devices
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
import com.galaxy.wear.domain.model.Phase
import com.galaxy.wear.ui.theme.*
import kotlinx.coroutines.launch

/**
 * HomeScreen — PR-UI-V2: Watch face-inspired main screen
 *
 * PR-CIRCULAR-FIX: Changed chip layout from horizontal Row (clips on round screen)
 * to vertical Column (fits within circular display bounds).
 *
 * Three entries: [语音] [设备] [设置] — vertically stacked, centered,
 * each 0.6 fillMaxWidth to stay clear of circular edges.
 */
@Composable
fun HomeScreen(
    phase: Phase,
    isAmbient: Boolean = false,
    onVoice: () -> Unit,
    /** 进入实时通话。与 [onVoice] 的一问一答是两条路,不是同一件事的两种入口。 */
    onCall: () -> Unit,
    onDevices: () -> Unit,
    onSettings: () -> Unit,
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

            // ── Phase indicator dots (BLACK/WHITE/GRAY) ─
            item {
                PhaseDotsHome(phase = phase, isAmbient = isAmbient)
            }

            // ── Phase status text ────────────────────
            item {
                // 标签与颜色都取自 PhaseVisual —— Tile 读的是同一份，
                // 否则表盘和 Tile 并排时同一相位会是两个灰。
                val label = PhaseVisual.label(phase)
                val color = PhaseVisual.statusColor(phase)
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.title3,
                        color = color,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        text = phase.name.uppercase(),
                        style = MaterialTheme.typography.caption3,
                        color = color.copy(alpha = 0.4f),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 1.dp)
                    )
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

    // ── Halo ring (ambient, always rendered) ────
    val pulseTrigger by app.pulseTrigger.collectAsState()
    PhaseHaloRing(
        phase = phase,
        pulseTrigger = pulseTrigger,
        isAmbient = isAmbient,
        modifier = Modifier.fillMaxSize()
    )

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
            phaseText = when (phase) {
                Phase.SILENT -> "Galaxy"
                Phase.LIMINAL -> "认知中..."
                Phase.MANIFEST -> "执行中..."
            },
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

@Composable
private fun PhaseDotsHome(phase: Phase, isAmbient: Boolean = false) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .background(SurfaceGlass, CircleShape)
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        // SILENT — dark gray
        val silentScale by animateFloatAsState(
            targetValue = if (phase == Phase.SILENT) 1.25f else 0.8f,
            animationSpec = if (isAmbient) snap() else spring(dampingRatio = 0.6f, stiffness = 300f),
            label = "dot_silent"
        )
        val silentAlpha by animateFloatAsState(
            targetValue = if (phase == Phase.SILENT) 1.0f else 0.25f,
            animationSpec = if (isAmbient) snap() else tween(300),
            label = "alpha_silent"
        )
        val silentColor = if (phase == Phase.SILENT) GrayManifest else GraySilent

        // LIMINAL — medium gray (NOT amber)
        val liminalScale by animateFloatAsState(
            targetValue = if (phase == Phase.LIMINAL) 1.25f else 0.8f,
            animationSpec = if (isAmbient) snap() else spring(dampingRatio = 0.6f, stiffness = 300f),
            label = "dot_liminal"
        )
        val liminalAlpha by animateFloatAsState(
            targetValue = if (phase == Phase.LIMINAL) 1.0f else 0.25f,
            animationSpec = if (isAmbient) snap() else tween(300),
            label = "alpha_liminal"
        )
        val liminalColor = if (phase == Phase.LIMINAL) GrayManifest else GrayLiminal

        // MANIFEST — bright white/gray
        val manifestScale by animateFloatAsState(
            targetValue = if (phase == Phase.MANIFEST) 1.25f else 0.8f,
            animationSpec = if (isAmbient) snap() else spring(dampingRatio = 0.6f, stiffness = 300f),
            label = "dot_manifest"
        )
        val manifestAlpha by animateFloatAsState(
            targetValue = if (phase == Phase.MANIFEST) 1.0f else 0.25f,
            animationSpec = if (isAmbient) snap() else tween(300),
            label = "alpha_manifest"
        )
        val manifestColor = if (phase == Phase.MANIFEST) Color.White else GrayManifest

        // LIMINAL pulsing animation
        val liminalPulseTransition = rememberInfiniteTransition(label = "liminal_pulse")
        val liminalPulse by liminalPulseTransition.animateFloat(
            initialValue = 1f,
            targetValue = if (phase == Phase.LIMINAL && !isAmbient) 1.6f else 1f,
            animationSpec = infiniteRepeatable(tween(900, easing = EaseInOutCubic), RepeatMode.Reverse),
            label = "liminal_pulse"
        )

        // Draw dots
        Box(Modifier.size(8.dp).scale(silentScale).background(silentColor.copy(alpha = silentAlpha), CircleShape))

        val liminalFinalScale = if (phase == Phase.LIMINAL && !isAmbient) liminalScale * liminalPulse else liminalScale
        Box(Modifier.size(8.dp).scale(liminalFinalScale).background(liminalColor.copy(alpha = liminalAlpha), CircleShape))

        Box(Modifier.size(8.dp).scale(manifestScale).background(manifestColor.copy(alpha = manifestAlpha), CircleShape))
    }
}

// ═══════════════════════════════════════════════════════
// Phase Halo Ring (monochrome — no blue)
// ═══════════════════════════════════════════════════════
@Composable
fun PhaseHaloRing(
    phase: Phase,
    pulseTrigger: Int,
    isAmbient: Boolean = false,
    modifier: Modifier = Modifier
) {
    val haloAlpha by animateFloatAsState(
        targetValue = when (phase) {
            Phase.SILENT -> 0f
            Phase.LIMINAL -> 0.3f
            Phase.MANIFEST -> 0.5f
        },
        animationSpec = tween(800, easing = EaseInOutSine),
        label = "halo_alpha"
    )
    val infiniteTransition = rememberInfiniteTransition(label = "halo_breath")
    val breathAlpha by infiniteTransition.animateFloat(
        initialValue = 0.15f,
        targetValue = if (phase == Phase.LIMINAL && !isAmbient) 0.4f else 0.15f,
        animationSpec = infiniteRepeatable(tween(2000, easing = EaseInOutSine), RepeatMode.Reverse),
        label = "halo_breath"
    )
    val pulseScale = remember { Animatable(1f) }
    LaunchedEffect(pulseTrigger) {
        if (pulseTrigger > 0) {
            pulseScale.snapTo(0.9f)
            pulseScale.animateTo(1f, tween(150, easing = EaseOutCubic))
        }
    }
    val finalAlpha = if (isAmbient) haloAlpha else haloAlpha * breathAlpha
    val haloColor = when (phase) {
        Phase.SILENT -> Color.Transparent
        Phase.LIMINAL -> GrayLiminal
        Phase.MANIFEST -> WhitePrimary.copy(alpha = 0.7f)
    }

    Canvas(modifier = modifier.scale(pulseScale.value)) {
        val cx = size.width / 2
        val cy = size.height / 2
        val r = (size.minDimension / 2) - 8.dp.toPx()
        drawCircle(
            color = haloColor.copy(alpha = finalAlpha),
            radius = r,
            center = Offset(cx, cy),
            style = Stroke(width = 2.5f)
        )
        drawCircle(
            color = haloColor.copy(alpha = finalAlpha * 0.5f),
            radius = r + 4.dp.toPx(),
            center = Offset(cx, cy),
            style = Stroke(width = 1f)
        )
    }
}

