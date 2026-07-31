package com.galaxy.wear.ui.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.*
import com.galaxy.wear.auth.DeviceFlowManager
import com.galaxy.wear.auth.DeviceFlowManager.DeviceFlowResult
import com.galaxy.wear.auth.DeviceFlowManager.FlowState
import com.galaxy.wear.ui.HapticType
import com.galaxy.wear.ui.triggerHaptic
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
    // ROUND-2-FIX: 记住最近一次 AwaitingAuth 信息。轮询从拿到 device_code 的
    // 同一刻就开始了,若 Polling 态单独占用界面,授权码会一闪而过(≈0ms),
    // 用户根本来不及在手机上输入 —— 整个 RFC 8628 流程因此不可用。
    // 授权期间(AwaitingAuth + Polling)始终展示授权码。
    var lastAwaiting by remember { mutableStateOf<FlowState.AwaitingAuth?>(null) }

    // 监听 DeviceFlowManager 状态变化
    DisposableEffect(deviceFlowManager) {
        val listener = object : DeviceFlowManager.FlowStateListener {
            override fun onStateChanged(state: FlowState) {
                currentState = state
                // 保存完整验证 URI 用于二维码 + 记住授权码供轮询期间持续展示
                if (state is FlowState.AwaitingAuth) {
                    verificationUriComplete = "${state.verificationUri}?user_code=${state.userCode}"
                    lastAwaiting = state
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
        currentState is FlowState.AwaitingAuth || currentState is FlowState.Polling -> {
            // 界面 1+2 合并: 整个授权期间(AwaitingAuth → Polling)持续显示授权码,
            // 轮询倒计时实时刷新;取消按钮行为不变。
            val awaiting = lastAwaiting
            val polling = (currentState as? FlowState.Polling)
            if (awaiting == null) {
                // 尚未拿到授权码(理论瞬时态)—— 显示加载菊花
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(
                        indicatorColor = MaterialTheme.colors.primary,
                        strokeWidth = 3.dp,
                        modifier = Modifier.size(32.dp)
                    )
                }
            } else {
                UserCodeDisplayView(
                    userCode = awaiting.userCode,
                    verificationUri = awaiting.verificationUri,
                    remainingSeconds = polling?.remainingSeconds ?: awaiting.expiresIn,
                    isPolling = polling != null,
                    onShowQrCode = { showQrCode = true },
                    onCancel = {
                        deviceFlowManager.cancelFlow()
                        onAuthCancelled()
                    }
                )
            }
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
                    indicatorColor = MaterialTheme.colors.primary,
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
    remainingSeconds: Int,
    isPolling: Boolean,
    onShowQrCode: () -> Unit,
    onCancel: () -> Unit
) {
    val context = LocalContext.current
    // 倒计时剩余秒数（由 Polling 状态实时下发）
    val remainingMinutes = remainingSeconds.coerceAtLeast(0) / 60
    val remainingSecs = remainingSeconds.coerceAtLeast(0) % 60

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

            // 剩余时间 + 轮询中提示
            Text(
                text = if (isPolling) "等待授权 · 剩余 ${remainingMinutes}分${remainingSecs}秒"
                       else "剩余 ${remainingMinutes}分${remainingSecs}秒",
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
                            imageVector = Icons.Default.QrCode,
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
                    imageVector = Icons.Default.Check,
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
                    imageVector = Icons.Default.Close,
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
