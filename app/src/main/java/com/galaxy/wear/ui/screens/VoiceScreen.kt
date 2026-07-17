package com.galaxy.wear.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.content.Intent
import android.speech.RecognizerIntent
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.wear.compose.material.*
import com.galaxy.wear.GalaxyWearApplication
import com.galaxy.wear.domain.model.Phase
import com.galaxy.wear.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * VoiceScreen — PR-UI-V2: Liquid Glass + Waveform + Glow Halo
 *
 * 1:1 复刻预览效果:
 *   - 深空黑底 + 星云粒子
 *   - 三个黑白灰相位点
 *   - 液态玻璃球（gradient 模拟折射 + 边缘高光 + 缓慢旋转 conic）
 *   - 动态波形（Canvas 竖条，正弦波动）
 *   - 呼吸光晕（outer glow animateFloat）
 *   - 扩散环（listening ring expand-fade）
 *   - 按住说话 → 语音识别 → 发送 AIP
 */
@Composable
fun VoiceScreen(
    isAmbient: Boolean = false,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val app = context.applicationContext as GalaxyWearApplication
    val scope = rememberCoroutineScope()

    // ── States ──────────────────────────────────────────
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                    == PackageManager.PERMISSION_GRANTED
        )
    }
    var isListening by remember { mutableStateOf(false) }
    var transcript by remember { mutableStateOf("") }
    var isSending by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf("按住屏幕说话") }

    // Animated waveform time
    var waveTime by remember { mutableFloatStateOf(0f) }

    // Listen for phase
    val phase by app.phase.collectAsState()

    // Auto-advance waveform when active
    LaunchedEffect(isListening) {
        while (isListening) {
            waveTime += 0.05f
            delay(16) // ~60fps
        }
    }

    // Speech launcher
    val speechLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (!scope.isActive) return@rememberLauncherForActivityResult
        isListening = false
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val results = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            val text = results?.firstOrNull() ?: ""
            if (text.isNotEmpty()) {
                transcript = text
                statusText = "发送中..."
                scope.launch {
                    try {
                        if (app.isAipClientReady()) {
                            app.aipClient.sendVoiceQuery(text)
                            statusText = "已发送"
                            delay(1500)
                            if (!isListening) {
                                statusText = "按住屏幕说话"
                                transcript = ""
                            }
                        } else {
                            statusText = "服务未就绪"
                        }
                    } catch (e: Exception) {
                        Log.e("VoiceScreen", "Send failed: ${e.message}")
                        statusText = "发送失败"
                    }
                }
            } else {
                statusText = "未识别"
            }
        } else {
            statusText = if (result.resultCode == android.app.Activity.RESULT_CANCELED) "已取消" else "未识别"
        }
    }

    // FIX: permission request path — previously, if RECORD_AUDIO was not granted
    // the tap handler silently returned and there was NO way to grant the
    // permission in-app, leaving the voice screen permanently dead.
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasPermission = granted
        statusText = if (granted) "按住屏幕说话" else "需要麦克风权限"
    }

    // ── Main Layout ─────────────────────────────────────
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(SpaceBlack)
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        if (isSending) return@detectTapGestures
                        if (!hasPermission) {
                            statusText = "请求麦克风权限..."
                            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            return@detectTapGestures
                        }
                        isListening = true
                        statusText = "聆听中..."
                        waveTime = 0f
                        try {
                            // RecognizerIntent 构造器私有,不能实例化;须用 Intent(ACTION)。
                            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                            }
                            speechLauncher.launch(intent)
                        } catch (e: Exception) {
                            Log.e("VoiceScreen", "Speech launch failed: ${e.message}")
                            isListening = false
                            statusText = "语音识别不可用"
                        }
                        tryAwaitRelease()
                    }
                )
            }
    ) {
        // ── Nebula particles (subtle) ──────────────────
        NebulaParticles()

        // ── Phase dots (black/white/gray) ──────────────
        PhaseDots(phase = phase, modifier = Modifier.align(Alignment.TopCenter).padding(top = 32.dp))

        // ── Glass Orb + Waveform center ────────────────
        Box(
            modifier = Modifier
                .size(170.dp)
                .align(Alignment.Center)
                .offset(y = (-10).dp)
        ) {
            // Outer breathing glow
            BreathingGlow(isActive = isListening)

            // Listening expand ring
            if (isListening) {
                ExpandRing()
            }

            // Glass orb background
            GlassOrb()

            // Waveform canvas
            WaveformCanvas(
                time = waveTime,
                isActive = isListening,
                modifier = Modifier
                    .size(120.dp)
                    .align(Alignment.Center)
            )
        }

        // ── Status text ────────────────────────────────
        Text(
            text = if (transcript.isNotEmpty() && !isListening) transcript else statusText,
            style = MaterialTheme.typography.body2,
            color = if (isListening) NebulaCyan else WhiteSecondary,
            textAlign = TextAlign.Center,
            maxLines = 2,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 40.dp)
                .fillMaxWidth(0.8f)
        )

        // ── Back chip ──────────────────────────────────
        CompactChip(
            onClick = onBack,
            label = { Text("返回", style = MaterialTheme.typography.caption3) },
            colors = ChipDefaults.secondaryChipColors(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 8.dp)
        )
    }
}

// ═══════════════════════════════════════════════════════
// Phase Dots (BLACK/WHITE/GRAY only)
// ═══════════════════════════════════════════════════════
@Composable
private fun PhaseDots(phase: Phase, modifier: Modifier = Modifier) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier
    ) {
        // SILENT — dark gray
        val silentColor = when (phase) {
            Phase.SILENT -> GrayManifest    // bright when active
            else -> GraySilent               // dim when inactive
        }
        val silentGlow = when (phase) {
            Phase.SILENT -> GrayManifest.copy(alpha = 0.5f)
            else -> Color.Transparent
        }

        // LIMINAL — medium gray (NOT amber)
        val liminalColor = when (phase) {
            Phase.LIMINAL -> GrayManifest
            else -> GrayLiminal
        }
        val liminalGlow = when (phase) {
            Phase.LIMINAL -> GrayManifest.copy(alpha = 0.5f)
            else -> Color.Transparent
        }

        // MANIFEST — white/bright gray
        val manifestColor = when (phase) {
            Phase.MANIFEST -> Color.White
            else -> GrayManifest
        }
        val manifestGlow = when (phase) {
            Phase.MANIFEST -> Color.White.copy(alpha = 0.4f)
            else -> Color.Transparent
        }

        PhaseDot(color = silentColor, glow = silentGlow, isPulsing = phase == Phase.SILENT)
        PhaseDot(color = liminalColor, glow = liminalGlow, isPulsing = phase == Phase.LIMINAL)
        PhaseDot(color = manifestColor, glow = manifestGlow, isPulsing = phase == Phase.MANIFEST)
    }
}

@Composable
private fun PhaseDot(color: Color, glow: Color, isPulsing: Boolean) {
    val scale by animateFloatAsState(
        targetValue = if (isPulsing) 1.3f else 1f,
        animationSpec = if (isPulsing) {
            infiniteRepeatable(tween(900, easing = EaseInOutCubic), RepeatMode.Reverse)
        } else {
            tween(300)
        },
        label = "phase_dot_scale"
    )
    Box(
        modifier = Modifier
            .size(6.dp)
            .scale(if (isPulsing) scale else 1f)
            .background(
                brush = Brush.radialGradient(
                    listOf(glow, Color.Transparent),
                    radius = 12.dp.value
                ),
                shape = CircleShape
            )
            .background(color, CircleShape)
    )
}

// ═══════════════════════════════════════════════════════
// Nebula Particles (subtle background dots)
// ═══════════════════════════════════════════════════════
@Composable
private fun NebulaParticles() {
    val particles = remember {
        List(8) {
            ParticleData(
                x = (20..80).random() / 100f,
                y = (10..90).random() / 100f,
                size = (1..3).random(),
                delay = (it * 800).toLong()
            )
        }
    }
    particles.forEach { p ->
        FloatingParticle(p)
    }
}

private data class ParticleData(val x: Float, val y: Float, val size: Int, val delay: Long)

@Composable
private fun FloatingParticle(p: ParticleData) {
    val infiniteTransition = rememberInfiniteTransition(label = "particle")
    val offsetY by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = -8f,
        animationSpec = infiniteRepeatable(
            tween(4000 + p.delay.toInt(), easing = EaseInOutSine),
            RepeatMode.Reverse
        ),
        label = "particle_y"
    )
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.15f,
        targetValue = 0.35f,
        animationSpec = infiniteRepeatable(
            tween(3000 + p.delay.toInt(), easing = EaseInOutSine),
            RepeatMode.Reverse
        ),
        label = "particle_alpha"
    )
    Box(
        modifier = Modifier
            .fillMaxSize()
            .wrapContentSize(Alignment.TopStart)
    ) {
        Box(
            modifier = Modifier
                .offset(
                    x = (p.x * 170).dp,
                    y = (p.y * 170 + offsetY).dp
                )
                .size(p.size.dp)
                .background(
                    if (p.x < 0.5f) NebulaBlue.copy(alpha = alpha)
                    else NebulaCyan.copy(alpha = alpha * 0.7f),
                    CircleShape
                )
        )
    }
}

// ═══════════════════════════════════════════════════════
// Breathing Glow (outer halo)
// ═══════════════════════════════════════════════════════
@Composable
private fun BreathingGlow(isActive: Boolean) {
    val infiniteTransition = rememberInfiniteTransition(label = "breath_glow")
    val scale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (isActive) 1.12f else 1.05f,
        animationSpec = infiniteRepeatable(
            tween(3000, easing = EaseInOutSine),
            RepeatMode.Reverse
        ),
        label = "glow_scale"
    )
    val alpha by infiniteTransition.animateFloat(
        initialValue = if (isActive) 0.25f else 0.12f,
        targetValue = if (isActive) 0.40f else 0.18f,
        animationSpec = infiniteRepeatable(
            tween(3000, easing = EaseInOutSine),
            RepeatMode.Reverse
        ),
        label = "glow_alpha"
    )
    Box(
        modifier = Modifier
            .fillMaxSize()
            .scale(scale)
            .background(
                brush = Brush.radialGradient(
                    listOf(
                        NebulaBlue.copy(alpha = alpha),
                        NebulaBlue.copy(alpha = alpha * 0.4f),
                        Color.Transparent
                    ),
                    radius = 0.7f
                ),
                shape = CircleShape
            )
    )
}

// ═══════════════════════════════════════════════════════
// Expand Ring (listening indicator)
// ═══════════════════════════════════════════════════════
@Composable
private fun ExpandRing() {
    val infiniteTransition = rememberInfiniteTransition(label = "expand_ring")
    val scale by infiniteTransition.animateFloat(
        initialValue = 0.85f,
        targetValue = 1.25f,
        animationSpec = infiniteRepeatable(
            tween(2000, easing = EaseOutCubic),
            RepeatMode.Restart
        ),
        label = "ring_scale"
    )
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            tween(2000, easing = EaseOutCubic),
            RepeatMode.Restart
        ),
        label = "ring_alpha"
    )
    Box(
        modifier = Modifier
            .fillMaxSize()
            .scale(scale)
            .background(
                brush = Brush.radialGradient(
                    listOf(
                        NebulaCyan.copy(alpha = alpha * 0.5f),
                        Color.Transparent
                    ),
                    radius = 0.6f
                ),
                shape = CircleShape
            )
            .background(Color.Transparent, CircleShape)
            .graphicsLayer {
                this.scaleX = scale
                this.scaleY = scale
                this.alpha = alpha
            }
    )
    // Border ring
    Box(
        modifier = Modifier
            .fillMaxSize()
            .scale(scale)
            .background(Color.Transparent, CircleShape)
            .graphicsLayer {
                this.scaleX = scale
                this.scaleY = scale
                this.alpha = alpha
            },
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Transparent, CircleShape)
                .border(1.dp, NebulaCyan.copy(alpha = alpha * 0.6f), CircleShape)
        )
    }
}

// ═══════════════════════════════════════════════════════
// Glass Orb (liquid glass simulation)
// ═══════════════════════════════════════════════════════
@Composable
private fun GlassOrb() {
    val infiniteTransition = rememberInfiniteTransition(label = "orb_rotate")
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            tween(12000, easing = LinearEasing),
            RepeatMode.Restart
        ),
        label = "orb_rotation"
    )

    Canvas(modifier = Modifier.fillMaxSize()) {
        val center = Offset(size.width / 2, size.height / 2)
        val radius = size.minDimension / 2

        // 1. Base dark gradient
        drawCircle(
            brush = Brush.radialGradient(
                listOf(SurfaceGlass, SurfaceElevated.copy(alpha = 0.8f)),
                center = center,
                radius = radius
            ),
            radius = radius
        )

        // 2. Conic highlight (rotating edge light)
        drawCircle(
            brush = Brush.sweepGradient(
                listOf(
                    Color.Transparent,
                    NebulaCyan.copy(alpha = 0.08f),
                    Color.Transparent,
                    Color.Transparent,
                    NebulaBlue.copy(alpha = 0.06f),
                    Color.Transparent,
                ),
                center = center
            ),
            radius = radius
        )

        // 3. Top-left highlight (glass reflection)
        drawCircle(
            brush = Brush.radialGradient(
                listOf(
                    GlassHighlight.copy(alpha = 0.15f),
                    Color.Transparent
                ),
                center = Offset(center.x - radius * 0.25f, center.y - radius * 0.25f),
                radius = radius * 0.6f
            ),
            radius = radius
        )

        // 4. Bottom-right subtle blue tint
        drawCircle(
            brush = Brush.radialGradient(
                listOf(
                    NebulaBlue.copy(alpha = 0.08f),
                    Color.Transparent
                ),
                center = Offset(center.x + radius * 0.3f, center.y + radius * 0.3f),
                radius = radius * 0.7f
            ),
            radius = radius
        )

        // 5. Edge border (glass rim)
        drawCircle(
            color = GlassEdge,
            radius = radius,
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.5f)
        )

        // 6. Inner rim highlight
        drawCircle(
            color = GlassHighlight.copy(alpha = 0.08f),
            radius = radius - 3f,
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1f)
        )
    }
}

// ═══════════════════════════════════════════════════════
// Waveform Canvas (dynamic audio visualization)
// ═══════════════════════════════════════════════════════
@Composable
private fun WaveformCanvas(
    time: Float,
    isActive: Boolean,
    modifier: Modifier = Modifier
) {
    val barCount = 24
    val barWidth = 3f
    val barGap = 3f
    val maxBarHeight = 40f
    val minBarHeight = 4f

    // Idle gentle wave when not listening
    val idleTime by produceState(0f) {
        while (true) {
            value += 0.02f
            delay(32)
        }
    }

    Canvas(modifier = modifier) {
        val canvasCenterX = size.width / 2
        val canvasCenterY = size.height / 2
        val totalWidth = barCount * (barWidth + barGap)
        val startX = canvasCenterX - totalWidth / 2 + barGap / 2

        val effectiveTime = if (isActive) time else idleTime

        for (i in 0 until barCount) {
            val distFromCenter = kotlin.math.abs(i - barCount / 2f) / (barCount / 2f)

            // FIX: clamp to minBarHeight — in active mode the three-wave sum can
            // dip below -1.5f, making the raw height negative and producing
            // drawRect calls with an invalid (negative) size.
            val height = (if (isActive) {
                // Active: lively waveform
                val wave1 = kotlin.math.sin(effectiveTime * 3f + i * 0.5f)
                val wave2 = kotlin.math.sin(effectiveTime * 2f + i * 0.8f) * 0.6f
                val wave3 = kotlin.math.cos(effectiveTime * 5f + i * 0.3f) * 0.3f
                val envelope = 1f - distFromCenter * 0.4f
                minBarHeight + (wave1 + wave2 + wave3 + 1.5f) / 3f * maxBarHeight * envelope
            } else {
                // Idle: gentle breathing
                val wave = kotlin.math.sin(effectiveTime * 1.5f + i * 0.3f) * 0.3f
                val envelope = 1f - distFromCenter * 0.5f
                minBarHeight + (wave + 0.5f) * 10f * envelope
            }).coerceAtLeast(minBarHeight)

            val x = startX + i * (barWidth + barGap)
            val top = canvasCenterY - height / 2
            val bottom = canvasCenterY + height / 2

            // Color gradient: cyan center → blue edges
            val colorT = distFromCenter
            val r = (100f + colorT * (91f - 100f)).toInt()
            val g = (210f - colorT * (210f - 141f)).toInt()
            val b = (255f - colorT * (255f - 239f)).toInt()
            val alpha = if (isActive) 0.7f + (1f - distFromCenter) * 0.3f else 0.4f

            // Glow bar (blur simulation = draw wider, lower alpha behind)
            drawRect(
                color = Color(r, g, b, (alpha * 0.3f * 255).toInt()),
                topLeft = Offset(x - 1f, top - 2f),
                size = Size(barWidth + 2f, height + 4f)
            )

            // Main bar
            val barGradient = Brush.verticalGradient(
                listOf(
                    Color(r, g, b, (alpha * 0.2f * 255).toInt()),
                    Color(r, g, b, (alpha * 255).toInt()),
                    Color(r, g, b, (alpha * 0.2f * 255).toInt())
                ),
                startY = top,
                endY = bottom
            )
            drawRect(
                brush = barGradient,
                topLeft = Offset(x, top),
                size = Size(barWidth, height)
            )
        }
    }
}
