package com.galaxy.wear.ui.screens

import android.content.Context
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.*
import com.galaxy.wear.domain.model.Phase
import kotlinx.coroutines.launch

/**
 * LIQUID-ISLAND: Halo ring animation parameters
 * The halo breathes at different rates depending on phase.
 */
private object HaloParams {
    val SILENT_ALPHA = 0.0f      // Invisible
    val LIMINAL_ALPHA_MIN = 0.15f // Breathing range
    val LIMINAL_ALPHA_MAX = 0.45f
    val MANIFEST_ALPHA = 0.7f    // Steady glow
    val BREATH_DURATION_MS = 2000 // 2s breathing cycle
    val PULSE_DURATION_MS = 150   // Quick pulse on message arrival
    val STROKE_WIDTH_DP = 3f
}

/**
 * HomeScreen — Watch face-inspired main screen
 *
 * Layout (centered, vertical):
 *   GALAXY title
 *   ┌─────────────┐
 *   │  ●  ●  ●    │  ← Phase dots (black/gray/white)
 *   └─────────────┘
 *   [Agent] [Voice] [Settings] ← Action chips
 *   SILENT / LIMINAL / MANIFEST label
 *
 * W4-FIX: Added isAmbient parameter to stop animations in Ambient mode.
 * W5-FIX: Added onRotaryScrollEvent for crown-based scrolling.
 * W9-FIX: Shared ScalingLazyListState between PositionIndicator and ScalingLazyColumn.
 */
@Composable
fun HomeScreen(
    phase: Phase,
    isAmbient: Boolean = false,
    onAgents: () -> Unit,
    onVoice: () -> Unit,
    onSettings: () -> Unit,
) {
    // W9-FIX: Shared list state — must be same instance for PositionIndicator and ScalingLazyColumn
    val listState = rememberScalingLazyListState(initialCenterItemIndex = 1)
    // W5-FIX: Coroutine scope for rotary scroll animation
    val coroutineScope = rememberCoroutineScope()

    Scaffold(
        vignette = { Vignette(vignettePosition = VignettePosition.TopAndBottom) },
        positionIndicator = { PositionIndicator(scalingLazyListState = listState) }
    ) {
        ScalingLazyColumn(
            // W4-FIX: Disable animations in Ambient mode to save battery
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                // W5-FIX: Handle rotary (crown) scroll events
                .onRotaryScrollEvent { event ->
                    coroutineScope.launch {
                        listState.scrollBy(event.verticalScrollPixels)
                    }
                    true
                },
            horizontalAlignment = Alignment.CenterHorizontally,
            state = listState,
        ) {
            // GALAXY Title
            item {
                Text(
                    text = "GALAXY",
                    style = MaterialTheme.typography.display3,
                    color = Color(0xFFE0E0E0),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                )
            }

            // Phase indicator dots (the three dots)
            item {
                PhaseIndicatorDots(
                    phase = phase,
                    isAmbient = isAmbient,
                    modifier = Modifier.padding(vertical = 12.dp)
                )
            }

            // Phase status text
            item {
                val (label, color) = when (phase) {
                    Phase.SILENT -> Pair("静默", Color(0xFF333333))
                    Phase.LIMINAL -> Pair("临界", Color(0xFF808080))
                    Phase.MANIFEST -> Pair("显现", Color(0xFFE0E0E0))
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.title2,
                        color = color,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        text = phase.name.uppercase(),
                        style = MaterialTheme.typography.caption3,
                        color = color.copy(alpha = 0.6f),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }

            // Action chips row
            item {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)
                ) {
                    CompactChip(
                        onClick = onAgents,
                        label = { Text("设备", style = MaterialTheme.typography.caption2) },
                        icon = {
                            Icon(
                                imageVector = androidx.compose.material.icons.Icons.Default.Devices,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                        },
                        colors = ChipDefaults.primaryChipColors()
                    )
                    CompactChip(
                        onClick = onVoice,
                        label = { Text("语音", style = MaterialTheme.typography.caption2) },
                        icon = {
                            Icon(
                                imageVector = androidx.compose.material.icons.Icons.Default.Mic,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                        },
                        colors = ChipDefaults.secondaryChipColors()
                    )
                    CompactChip(
                        onClick = onSettings,
                        label = { Text("设置", style = MaterialTheme.typography.caption2) },
                        icon = {
                            Icon(
                                imageVector = androidx.compose.material.icons.Icons.Default.Settings,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                        },
                        colors = ChipDefaults.secondaryChipColors()
                    )
                }
            }
        }
    }

    // W4-FIX: Halo ring — always call PhaseHaloRing to maintain Compose call order,
    // ambient mode handling is done inside the composable
    val app = LocalContext.current.applicationContext as com.galaxy.wear.GalaxyWearApplication
    val pulseTrigger by app.pulseTrigger.collectAsState()
    PhaseHaloRing(
        phase = phase,
        pulseTrigger = pulseTrigger,
        isAmbient = isAmbient,
        modifier = Modifier.fillMaxSize()
    )
}

/**
 * Three-phase dot indicator — real-time animated
 *
 * W4-FIX: Stop pulse animations in Ambient mode to save battery.
 */
@Composable
fun PhaseIndicatorDots(
    phase: Phase,
    isAmbient: Boolean = false,
    modifier: Modifier = Modifier
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .background(Color(0xFF111111), CircleShape)
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Phase.values().forEach { p ->
            val isActive = phase == p
            val targetScale = if (isActive) 1.25f else 0.8f
            val targetAlpha = if (isActive) 1.0f else 0.2f

            // W4-FIX: Skip animations in Ambient mode
            val scale by animateFloatAsState(
                targetValue = targetScale,
                animationSpec = if (isAmbient) snap() else spring(stiffness = 300f, damping = 15f),
                label = "dot_scale_$p"
            )

            val color = when (p) {
                Phase.SILENT -> if (isActive) Color(0xFF333333) else Color(0xFF1A1A1A)
                Phase.LIMINAL -> if (isActive) Color(0xFF808080) else Color(0xFF333333)
                Phase.MANIFEST -> if (isActive) Color(0xFFE0E0E0) else Color(0xFF444444)
            }

            // W4-FIX: Disable LIMINAL pulse animation in Ambient mode
            // FIXED: rememberInfiniteTransition moved outside condition — Compose requires consistent call order
            val liminalPulseTransition = rememberInfiniteTransition(label = "liminal_pulse")
            val liminalPulseAnim by liminalPulseTransition.animateFloat(
                initialValue = 1f,
                targetValue = if (isActive && p == Phase.LIMINAL) 1.6f else 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(900, easing = EaseInOutCubic),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "liminal_pulse"
            )
            val liminalPulse = if (isAmbient) 1f else liminalPulseAnim

            val liminalAlphaTransition = rememberInfiniteTransition(label = "liminal_alpha")
            val liminalAlphaAnim by liminalAlphaTransition.animateFloat(
                initialValue = if (isActive && p == Phase.LIMINAL) 0.6f else targetAlpha,
                targetValue = if (isActive && p == Phase.LIMINAL) 1.0f else targetAlpha,
                animationSpec = infiniteRepeatable(
                    animation = tween(900, easing = EaseInOutCubic),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "liminal_alpha"
            )
            val liminalAlpha = if (isAmbient) targetAlpha else liminalAlphaAnim

            Box(
                modifier = Modifier
                    .size(10.dp)
                    .scale(scale * if (isActive && p == Phase.LIMINAL) liminalPulse else 1f)
                    .background(
                        color.copy(
                            alpha = if (isActive && p == Phase.LIMINAL) liminalAlpha else targetAlpha
                        ),
                        CircleShape
                    )
            )
        }
    }
}

// ── LIQUID-ISLAND: Breathing Halo Ring (outer edge of watch face) ───────────

/**
 * LIQUID-ISLAND: Breathing halo ring that encircles the watch face.
 *
 * - SILENT:  completely invisible (sleeping)
 * - LIMINAL: breathing gray glow (like a slow heartbeat)
 * - MANIFEST: steady white glow (awake and alert)
 *
 * When a message arrives, the halo "pulses" once (bright flash then decay),
 * giving tactile-like visual feedback even before the user reads the message.
 *
 * W4-FIX: Ambient mode suppresses drawing — the composable always runs to maintain Compose call order.
 */
@Composable
fun PhaseHaloRing(
    phase: Phase,
    pulseTrigger: Int = 0,  // increment to trigger a one-shot pulse
    isAmbient: Boolean = false,  // W4-FIX: When true, suppress drawing to save battery
    modifier: Modifier = Modifier
) {
    // Base alpha animated by phase
    val targetBaseAlpha = when (phase) {
        Phase.SILENT -> HaloParams.SILENT_ALPHA
        Phase.LIMINAL -> HaloParams.LIMINAL_ALPHA_MAX
        Phase.MANIFEST -> HaloParams.MANIFEST_ALPHA
    }

    val baseAlpha by animateFloatAsState(
        targetValue = if (isAmbient) 0f else targetBaseAlpha,
        animationSpec = tween(durationMillis = 800, easing = EaseInOutCubic),
        label = "halo_alpha"
    )

    // Breathing animation (only for LIMINAL, disabled in Ambient)
    val infiniteTransition = rememberInfiniteTransition(label = "halo_breath")
    val breathAlpha by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = if (!isAmbient && phase == Phase.LIMINAL) 1f else 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(HaloParams.BREATH_DURATION_MS, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        ),
        label = "halo_breath"
    )

    // H6 FIX: One-shot pulse using Animatable instead of busy-wait while+delay loop
    val pulseAnimatable = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(pulseTrigger) {
        if (!isAmbient && pulseTrigger > 0) {
            pulseAnimatable.snapTo(0.8f)
            pulseAnimatable.animateTo(
                targetValue = 0f,
                animationSpec = tween(
                    durationMillis = HaloParams.PULSE_DURATION_MS,
                    easing = EaseOutCubic
                )
            )
        }
    }
    val pulseAlpha = pulseAnimatable.value

    // Combine: base + breath + pulse
    val finalAlpha = if (isAmbient) 0f else (baseAlpha +
            breathAlpha * (HaloParams.LIMINAL_ALPHA_MAX - HaloParams.LIMINAL_ALPHA_MIN) +
            pulseAlpha).coerceIn(0f, 1f)

    // Halo color by phase
    val haloColor = when (phase) {
        Phase.SILENT -> Color(0xFF000000)
        Phase.LIMINAL -> Color(0xFF808080)
        Phase.MANIFEST -> Color(0xFFE0E0E0)
    }

    Canvas(modifier = modifier.fillMaxSize()) {
        if (finalAlpha > 0.01f) {
            val strokeWidth = HaloParams.STROKE_WIDTH_DP.dp.toPx()
            drawCircle(
                color = haloColor.copy(alpha = finalAlpha),
                radius = size.minDimension / 2f - strokeWidth / 2f,
                center = center,
                style = Stroke(width = strokeWidth)
            )
        }
    }
}

// ── LIQUID-ISLAND: Haptic Feedback Helpers ───────────────────────────────────

/**
 * Trigger haptic feedback on the watch.
 *
 * - [HapticType.PHASE_CHANGE]: longer vibration for phase transitions
 * - [HapticType.TASK_DONE]: double-tap confirmation
 * - [HapticType.MESSAGE_ARRIVAL]: short tap for new messages
 */
enum class HapticType {
    PHASE_CHANGE,   // 200ms medium vibration
    TASK_DONE,      // double-tap (50ms on, 50ms off, 50ms on)
    MESSAGE_ARRIVAL,// 30ms short tap
    ERROR,          // 60ms sharp vibration for failure feedback
}

/**
 * LIQUID-ISLAND: Trigger haptic feedback on the watch.
 *
 * This gives tactile confirmation of state changes and message arrivals,
 * so the user feels the system "responding" even before looking at the screen.
 */
fun triggerHaptic(context: Context, type: HapticType) {
    val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        ?: return

    if (!vibrator.hasVibrator()) return

    val effect = when (type) {
        HapticType.PHASE_CHANGE ->
            VibrationEffect.createOneShot(200L, VibrationEffect.DEFAULT_AMPLITUDE)
        HapticType.TASK_DONE ->
            VibrationEffect.createWaveform(longArrayOf(0, 50, 50, 50), -1)
        HapticType.MESSAGE_ARRIVAL ->
            VibrationEffect.createOneShot(30L, 80)
        HapticType.ERROR ->
            VibrationEffect.createWaveform(longArrayOf(0, 60, 80, 40), -1)
    }

    vibrator.vibrate(effect)
}
