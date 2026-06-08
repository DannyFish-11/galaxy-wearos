package com.lumiv.wear.ui.screens

import android.os.VibrationEffect
import android.os.Vibrator
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.*
import com.lumiv.wear.auth.DeviceFlowManager
import com.lumiv.wear.auth.DeviceFlowManager.DeviceFlowResult
import com.lumiv.wear.auth.DeviceFlowManager.FlowState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * DeviceAuthScreen — Wear OS 专用 OAuth 2.0 Device Flow 授权界面
 *
 * 四个界面状态：
 * 1. 显示授权码 — 大字显示用户码，提示在手机上打开验证 URI
 * 2. 授权中 — 旋转菊花 + 倒计时 + 取消按钮
 * 3. 授权成功 — 绿色勾选 + 用户信息 + 自动跳转
 * 4. 二维码 — 全屏显示二维码供手机扫描
 *
 * 所有界面针对 Wear OS 圆形小屏幕优化，使用 Wear Material 组件。
 */
@Composable
fun DeviceAuthScreen(
    deviceFlowManager: DeviceFlowManager,
    serverUrl: String,
    onAuthSuccess: () -> Unit,
    onAuthCancelled: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    // 当前 UI 状态
    var currentState by remember { mutableStateOf<FlowState>(FlowState.Idle) }
    // 是否显示二维码
    var showQrCode by remember { mutableStateOf(false) }
    // 授权码信息（用于二维码）
    var verificationUriComplete by remember { mutableStateOf("") }

    // 监听 DeviceFlowManager 状态变化
    DisposableEffect(deviceFlowManager) {
        val listener = object : DeviceFlowManager.FlowStateListener {
            override fun onStateChanged(state: FlowState) {
                currentState = state
                // 保存完整验证 URI 用于二维码
                if (state is FlowState.AwaitingAuth) {
                    verificationUriComplete = "${state.verificationUri}?user_code=${state.userCode}"
                }
            }
        }
        deviceFlowManager.setStateListener(listener)
        onDispose { deviceFlowManager.setStateListener(null) }
    }

    // 启动授权流程
    LaunchedEffect(Unit) {
        coroutineScope.launch {
            when (val result = deviceFlowManager.startDeviceAuth(
                provider = "google",
                serverUrl = serverUrl
            )) {
                is DeviceFlowResult.Success -> {
                    triggerHaptic(context, HapticType.TASK_DONE)
                    delay(3000) // 3秒后自动跳转
                    onAuthSuccess()
                }
                is DeviceFlowResult.Error,
                is DeviceFlowResult.Timeout -> {
                    triggerHaptic(context, HapticType.ERROR)
                    delay(3000) // 错误页面停留3秒
                    onAuthCancelled()
                }
                is DeviceFlowResult.Cancelled -> {
                    onAuthCancelled()
                }
            }
        }
    }

    // 根据状态渲染不同界面
    when {
        showQrCode -> {
            // 界面 4: 全屏二维码
            QrCodeFullScreen(
                data = verificationUriComplete,
                onBack = { showQrCode = false }
            )
        }
        currentState is FlowState.Success -> {
            // 界面 3: 授权成功
            val token = (currentState as FlowState.Success).token
            AuthSuccessView(userEmail = token.userEmail)
        }
        currentState is FlowState.Error -> {
            // 错误界面
            val message = (currentState as FlowState.Error).message
            AuthErrorView(message = message, onDismiss = onAuthCancelled)
        }
        currentState is FlowState.Polling -> {
            // 界面 2: 轮询中
            val state = currentState as FlowState.Polling
            AuthPollingView(
                userCode = state.userCode,
                remainingSeconds = state.remainingSeconds,
                onCancel = {
                    deviceFlowManager.cancelFlow()
                    onAuthCancelled()
                }
            )
        }
        currentState is FlowState.AwaitingAuth -> {
            // 界面 1: 显示授权码
            val state = currentState as FlowState.AwaitingAuth
            UserCodeDisplayView(
                userCode = state.userCode,
                verificationUri = state.verificationUri,
                elapsedSeconds = state.elapsedSeconds,
                expiresIn = state.expiresIn,
                onShowQrCode = { showQrCode = true },
                onCancel = {
                    deviceFlowManager.cancelFlow()
                    onAuthCancelled()
                }
            )
        }
        else -> {
            // 初始加载中
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(
                    color = MaterialTheme.colors.primary,
                    strokeWidth = 3.dp,
                    modifier = Modifier.size(32.dp)
                )
            }
        }
    }
}

/**
 * 界面 1 — 显示授权码
 *
 * 圆形布局优化：
 * - 中心大字显示授权码（如 ABCD-EFGH）
 * - 副标题提示在手机打开验证 URI
 * - 底部提供二维码按钮和取消按钮
 */
@Composable
private fun UserCodeDisplayView(
    userCode: String,
    verificationUri: String,
    elapsedSeconds: Int,
    expiresIn: Int,
    onShowQrCode: () -> Unit,
    onCancel: () -> Unit
) {
    val context = LocalContext.current
    // 倒计时剩余秒数
    val remainingSeconds = (expiresIn - elapsedSeconds).coerceAtLeast(0)
    val remainingMinutes = remainingSeconds / 60
    val remainingSecs = remainingSeconds % 60

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
            modifier = Modifier.fillMaxSize()
        ) {
            // 标题
            Text(
                text = "设备授权",
                style = MaterialTheme.typography.caption3,
                color = MaterialTheme.colors.primaryVariant,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            // 授权码 — 大字号居中显示，分两组便于阅读
            val displayCode = userCode.replace("-", "-")
            Text(
                text = displayCode,
                style = MaterialTheme.typography.display1,
                color = MaterialTheme.colors.primary,
                textAlign = TextAlign.Center,
                maxLines = 1,
                modifier = Modifier.padding(vertical = 4.dp)
            )

            // 副标题 — 提示用户在手机上操作
            Text(
                text = "在手机上打开",
                style = MaterialTheme.typography.caption2,
                color = Color(0xFF999999),
                textAlign = TextAlign.Center,
                maxLines = 1
            )

            // 验证 URI — 高亮显示
            Text(
                text = formatShortUri(verificationUri),
                style = MaterialTheme.typography.caption1,
                color = MaterialTheme.colors.primary,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 4.dp)
            )

            // 剩余时间
            Text(
                text = "剩余 ${remainingMinutes}分${remainingSecs}秒",
                style = MaterialTheme.typography.caption3,
                color = Color(0xFF666666),
                textAlign = TextAlign.Center,
                maxLines = 1
            )

            // 操作按钮
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 二维码按钮
                CompactChip(
                    onClick = onShowQrCode,
                    label = { Text("二维码", style = MaterialTheme.typography.caption3) },
                    icon = {
                        Icon(
                            imageVector = androidx.compose.material.icons.Icons.Default.QrCode,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp)
                        )
                    },
                    colors = ChipDefaults.primaryChipColors()
                )

                // 取消按钮
                CompactChip(
                    onClick = onCancel,
                    label = { Text("取消", style = MaterialTheme.typography.caption3) },
                    colors = ChipDefaults.secondaryChipColors()
                )
            }
        }
    }
}

/**
 * 界面 2 — 授权中（轮询状态）
 *
 * 圆形布局优化：
 * - 中心旋转菊花动画
 * - "等待授权..." 文字
 * - 倒计时显示
 * - 取消按钮
 */
@Composable
private fun AuthPollingView(
    userCode: String,
    remainingSeconds: Int,
    onCancel: () -> Unit
) {
    val context = LocalContext.current
    // 旋转动画
    val infiniteTransition = rememberInfiniteTransition(label = "spinner")
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotation"
    )

    val minutes = remainingSeconds / 60
    val seconds = remainingSeconds % 60
    val timeStr = "${minutes}分${seconds.toString().padStart(2, '0')}秒"

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
            modifier = Modifier.fillMaxSize()
        ) {
            // 旋转菊花 — 使用 Canvas 绘制圆形进度条
            Box(
                modifier = Modifier.size(48.dp),
                contentAlignment = Alignment.Center
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val strokeWidth = 3.dp.toPx()
                    val radius = size.minDimension / 2f - strokeWidth
                    // 背景圆环
                    drawArc(
                        color = Color(0xFF333333),
                        startAngle = 0f,
                        sweepAngle = 360f,
                        useCenter = false,
                        style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                    )
                    // 旋转进度弧
                    drawArc(
                        color = MaterialTheme.colors.primary,
                        startAngle = rotation,
                        sweepAngle = 90f,
                        useCenter = false,
                        style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                    )
                }
            }

            // 等待文字
            Text(
                text = "等待授权...",
                style = MaterialTheme.typography.title3,
                color = MaterialTheme.colors.primary,
                textAlign = TextAlign.Center,
                maxLines = 1
            )

            // 倒计时
            Text(
                text = "剩余 $timeStr",
                style = MaterialTheme.typography.caption2,
                color = Color(0xFF808080),
                textAlign = TextAlign.Center,
                maxLines = 1
            )

            // 取消按钮
            CompactChip(
                onClick = onCancel,
                label = { Text("取消", style = MaterialTheme.typography.caption2) },
                colors = ChipDefaults.secondaryChipColors()
            )
        }
    }
}

/**
 * 界面 3 — 授权成功
 *
 * 圆形布局优化：
 * - 绿色勾选动画（缩放弹出）
 * - 用户邮箱显示
 * - "3秒后自动跳转" 提示
 */
@Composable
private fun AuthSuccessView(userEmail: String) {
    val context = LocalContext.current
    // 勾选弹出动画
    val scaleAnim = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        scaleAnim.animateTo(
            targetValue = 1f,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessLow
            )
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
            modifier = Modifier.fillMaxSize()
        ) {
            // 绿色勾选图标 — 带弹出动画
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .scale(scaleAnim.value)
                    .background(Color(0xFF4CAF50), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = androidx.compose.material.icons.Icons.Default.Check,
                    contentDescription = "授权成功",
                    tint = Color.White,
                    modifier = Modifier.size(32.dp)
                )
            }

            // 成功文字
            Text(
                text = "授权成功",
                style = MaterialTheme.typography.title3,
                color = Color(0xFF4CAF50),
                textAlign = TextAlign.Center,
                maxLines = 1
            )

            // 用户邮箱
            Text(
                text = if (userEmail.isNotEmpty()) "已登录为" else "",
                style = MaterialTheme.typography.caption3,
                color = Color(0xFF808080),
                textAlign = TextAlign.Center,
                maxLines = 1
            )

            if (userEmail.isNotEmpty()) {
                Text(
                    text = userEmail,
                    style = MaterialTheme.typography.caption2,
                    color = MaterialTheme.colors.primary,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 8.dp)
                )
            }

            // 跳转提示
            Text(
                text = "正在跳转...",
                style = MaterialTheme.typography.caption3,
                color = Color(0xFF666666),
                textAlign = TextAlign.Center,
                maxLines = 1
            )
        }
    }
}

/**
 * 错误界面
 *
 * 显示授权失败信息，3秒后自动关闭。
 */
@Composable
private fun AuthErrorView(
    message: String,
    onDismiss: () -> Unit
) {
    // 3秒后自动关闭
    LaunchedEffect(Unit) {
        delay(3000)
        onDismiss()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp)
        ) {
            // 错误图标 — 红色叉号
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .background(Color(0xFFCF6679), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = androidx.compose.material.icons.Icons.Default.Close,
                    contentDescription = "授权失败",
                    tint = Color.White,
                    modifier = Modifier.size(28.dp)
                )
            }

            // 错误标题
            Text(
                text = "授权失败",
                style = MaterialTheme.typography.title3,
                color = Color(0xFFCF6679),
                textAlign = TextAlign.Center,
                maxLines = 1
            )

            // 错误信息
            Text(
                text = message,
                style = MaterialTheme.typography.caption2,
                color = Color(0xFF808080),
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

// ---- 辅助函数 ----

/**
 * 格式化 URI 显示 — 截断过长的 URI
 */
private fun formatShortUri(uri: String): String {
    return when {
        uri.length > 25 -> uri.substring(0, 22) + "..."
        else -> uri
    }
}
