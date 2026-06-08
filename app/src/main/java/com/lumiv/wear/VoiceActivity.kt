package com.lumiv.wear

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.*
import com.lumiv.wear.LumivWearApplication
import com.lumiv.wear.domain.model.Phase
import com.lumiv.wear.ui.theme.LumivWearTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext

/**
 * VoiceActivity — OPPO Watch 3 语音输入入口
 *
 * 交互流程（ColorOS Watch 双击侧键启动）：
 *   双击侧键 → 启动 VoiceActivity → 自动进入 push-to-talk
 *   按住屏幕说话 → 松开发送 → 振动反馈 → 显示结果
 *
 * OPPO Watch 3 硬件适配：
 *   - 标准版无表冠，仅一个侧键
 *   - 侧键双击可自定义启动本 Activity（系统设置中配置）
 *   - 内置麦克风支持语音输入
 *   - 基础振动马达支持触觉反馈
 */
class VoiceActivity : ComponentActivity() {

    // W15-FIX: Activity-bound coroutine scope instead of MainScope()
    // to prevent accessing destroyed Activity state.
    // CRITICAL-FIX: Use Dispatchers.Default (not Main.immediate) for IO operations
    // to avoid ANR. UI updates inside coroutines use withContext(Dispatchers.Main).
    private val voiceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    companion object {
        /**
         * Create launch intent for OPPO Watch 3 double-press shortcut.
         * Usage:
         *   ColorOS Settings > Shortcuts > Double-press side key > Lumiv Voice
         */
        fun createShortcutIntent(context: android.content.Context): Intent {
            return Intent(context, VoiceActivity::class.java).apply {
                action = ACTION_VOICE_COMMAND
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_NO_ANIMATION
            }
        }

        private const val ACTION_VOICE_COMMAND = "com.lumiv.wear.ACTION_VOICE_COMMAND"
    }

    private val speechLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val results = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            transcript = results?.firstOrNull() ?: ""

            if (transcript.isNotEmpty()) {
                statusText = "发送中..."
                val app = application as LumivWearApplication
                // W15-FIX: Use activity-bound scope; check !isDestroyed before accessing UI
                voiceScope.launch {
                    try {
                        // W15-FIX: Guard against Activity being destroyed during network call
                        if (isFinishing || isDestroyed) return@launch
                        // FIX: Guard against uninitialized AIPClient
                        if (!app.isAipClientReady()) {
                            withContext(Dispatchers.Main) {
                                if (!isFinishing && !isDestroyed) statusText = "服务未就绪"
                            }
                            return@launch
                        }
                        app.aipClient.sendVoiceQuery(transcript)
                        if (isFinishing || isDestroyed) return@launch
                        // CRITICAL-FIX: UI updates must be on Main dispatcher
                        withContext(Dispatchers.Main) {
                            if (!isFinishing && !isDestroyed) {
                                statusText = "已发送"
                                // LIQUID-ISLAND: haptic confirmation on send
                                triggerHaptic(this@VoiceActivity, HapticType.MESSAGE_ARRIVAL)
                            }
                        }
                        // Auto-clear after 2s
                        kotlinx.coroutines.delay(2000)
                        if (isFinishing || isDestroyed) return@launch
                        withContext(Dispatchers.Main) {
                            if (!isFinishing && !isDestroyed && !isListening) {
                                statusText = "按住说话"
                                transcript = ""
                            }
                        }
                    } catch (e: Exception) {
                        if (isFinishing || isDestroyed) return@launch
                        Log.e(LumivWearApplication.TAG, "Send failed: ${e.message}")
                        withContext(Dispatchers.Main) {
                            if (!isFinishing && !isDestroyed) {
                                statusText = "发送失败"
                                triggerHaptic(this@VoiceActivity, HapticType.ERROR)
                            }
                        }
                    }
                }
            } else {
                statusText = "未识别"
                isProcessing = false
            }
        } else {
            isProcessing = false
            statusText = if (result.resultCode == Activity.RESULT_CANCELED) "已取消" else "未识别"
        }
    }

    private var transcript by mutableStateOf("")
    private var isListening by mutableStateOf(false)
    private var isProcessing by mutableStateOf(false)
    private var statusText by mutableStateOf("按住说话")  // Display status

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            LumivWearTheme {
                PushToTalkScreen()
            }
        }
    }

    @Composable
    private fun PushToTalkScreen() {
        val app = application as LumivWearApplication
        val phase by app.phase.collectAsState()

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onPress = {
                            // Finger DOWN: start recording
                            startListening()
                            tryAwaitRelease()
                            // Finger UP: stop and send
                            stopListeningAndSend()
                        }
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.padding(16.dp)
            ) {
                // Animated ring indicator
                val ringScale by animateFloatAsState(
                    targetValue = if (isListening) 1.5f else 1f,
                    animationSpec = tween(300, easing = EaseInOutCubic),
                    label = "ring_scale"
                )
                val ringAlpha by animateFloatAsState(
                    targetValue = if (isListening) 0.6f else 0f,
                    animationSpec = tween(300, easing = EaseInOutCubic),
                    label = "ring_alpha"
                )

                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.size(72.dp)
                ) {
                    // Pulsing ring when listening
                    if (ringAlpha > 0.01f) {
                        Box(
                            modifier = Modifier
                                .size(56.dp)
                                .scale(ringScale)
                                .background(
                                    MaterialTheme.colors.primary.copy(alpha = ringAlpha),
                                    CircleShape
                                )
                        )
                    }

                    // Central status dot (black/gray/white only)
                    val dotColor by animateColorAsState(
                        targetValue = when {
                            isListening -> Color(0xFFE0E0E0)   // White = recording
                            isProcessing -> Color(0xFF808080)   // Gray = processing
                            transcript.isNotEmpty() -> Color(0xFFCCCCCC)  // Light gray = done
                            else -> Color(0xFF333333)           // Dark gray = idle
                        },
                        animationSpec = tween(200),
                        label = "dot_color"
                    )

                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .background(dotColor, CircleShape)
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Status text
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.body2,
                    color = when {
                        isListening -> MaterialTheme.colors.primary
                        isProcessing -> Color(0xFF808080)
                        transcript.isNotEmpty() -> Color(0xFFAAAAAA)
                        else -> Color(0xFF666666)
                    },
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    modifier = Modifier.fillMaxWidth()
                )

                // Transcript display (brief, auto-clear)
                if (transcript.isNotEmpty() && !isListening && !isProcessing) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "\"$transcript\"",
                        style = MaterialTheme.typography.caption3,
                        color = Color(0xFF555555),
                        textAlign = TextAlign.Center,
                        maxLines = 1
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Phase indicator (ambient state awareness)
                PhaseMiniDots(phase = phase)
            }
        }
    }

    @Composable
    private fun PhaseMiniDots(phase: Phase) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Phase.values().forEach { p ->
                val color = when (p) {
                    Phase.SILENT -> if (phase == p) Color(0xFF444444) else Color(0xFF222222)
                    Phase.LIMINAL -> if (phase == p) Color(0xFF808080) else Color(0xFF222222)
                    Phase.MANIFEST -> if (phase == p) Color(0xFFCCCCCC) else Color(0xFF222222)
                }
                Box(
                    modifier = Modifier
                        .size(5.dp)
                        .background(color, CircleShape)
                )
            }
        }
    }

    // ── Voice Control ─────────────────────────────────────────────────

    private fun startListening() {
        isListening = true
        statusText = "聆听中..."
        transcript = ""

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
            putExtra(RecognizerIntent.EXTRA_PROMPT, "")
        }
        try {
            speechLauncher.launch(intent)
        } catch (e: Exception) {
            Log.e(LumivWearApplication.TAG, "Speech recognition not available")
            isListening = false
            statusText = "语音识别不可用"
        }
    }

    private fun stopListeningAndSend() {
        // ActivityResultContracts handles the transcript via speechLauncher
        isListening = false
        statusText = "处理中..."
        isProcessing = true
    }

    // W15-FIX: Cancel all voice coroutines when Activity is destroyed
    // to prevent accessing destroyed Activity state
    override fun onDestroy() {
        voiceScope.cancel()
        super.onDestroy()
    }
}
