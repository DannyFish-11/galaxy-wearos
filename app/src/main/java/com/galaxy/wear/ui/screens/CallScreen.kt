package com.galaxy.wear.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.collectAsState
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import com.galaxy.wear.call.CallState
import com.galaxy.wear.call.VoiceCallService

/**
 * 通话界面 —— 一颗按钮进,一颗按钮出。
 *
 * 交互上刻意不做"按住说话":那正是用户嫌麻烦的那一套。接通之后就一直开着,想说就说,
 * 打断 AI 也直接开口即可(服务端 VAD 判定,见 VoiceCallController.onAiEvent)。
 *
 * 这个界面**不持有通话**。通话住在 [VoiceCallService] 里,因为它要活过熄屏和划走界面
 * —— 表上这两件事随时发生。界面只是观察者:划走再回来看到的是同一通电话。
 */
@Composable
fun CallScreen(isAmbient: Boolean = false, onBack: () -> Unit) {
    val context = LocalContext.current
    val controller by VoiceCallService.controller.collectAsState()
    val ui = controller?.ui?.collectAsState()?.value

    var hasPermission by remember {
        mutableStateOf(
            context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED,
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasPermission = granted
        if (granted) VoiceCallService.start(context)
    }

    // 通话结束就自动退回上一屏。停在一块写着"已结束"的表盘上没有意义,而且用户下一步
    // 一定是划走。
    LaunchedEffect(ui?.state) {
        if (ui?.state == CallState.ENDED && ui.endedReason.isEmpty()) onBack()
    }

    Box(
        modifier = Modifier.fillMaxSize().background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(horizontal = 16.dp),
        ) {
            Text(
                text = statusLabel(ui?.state),
                style = MaterialTheme.typography.title3,
                color = Color.White,
                textAlign = TextAlign.Center,
            )

            // AI 回推的文字。它不是本地识别的结果 —— 手表上不跑识别,这是 AI 那边
            // 听到/说到哪儿的实时反馈,也是"除了说话之外还能看见点什么"的那一半。
            val transcript = ui?.transcript.orEmpty()
            if (transcript.isNotEmpty() && !isAmbient) {
                Text(
                    text = transcript,
                    style = MaterialTheme.typography.body2,
                    color = Color(0xFFB0B0B0),
                    textAlign = TextAlign.Center,
                    maxLines = 3,
                )
            }

            val reason = ui?.endedReason.orEmpty()
            if (reason.isNotEmpty() && ui?.state == CallState.ENDED) {
                // 失败原因必须显示出来。「通话失败」四个字是最难排查的一类提示:
                // 没给麦克风权限、网关没连上、后端没配 key,处置完全不同。
                Text(
                    text = reason,
                    style = MaterialTheme.typography.caption2,
                    color = Color(0xFFE57373),
                    textAlign = TextAlign.Center,
                    maxLines = 3,
                )
            }

            if (!isAmbient) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    when (ui?.state) {
                        CallState.DIALING, CallState.IN_CALL -> {
                            Button(
                                onClick = { VoiceCallService.hangup(context) },
                                colors = ButtonDefaults.primaryButtonColors(backgroundColor = Color(0xFFB00020)),
                                modifier = Modifier.size(48.dp),
                            ) { Text("挂断") }
                            Button(
                                onClick = { controller?.setMuted(ui.muted.not()) },
                                colors = ButtonDefaults.secondaryButtonColors(),
                                modifier = Modifier.size(48.dp),
                            ) { Text(if (ui.muted) "取消" else "静音") }
                        }
                        else -> Button(
                            onClick = {
                                if (hasPermission) {
                                    VoiceCallService.start(context)
                                } else {
                                    permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                }
                            },
                            colors = ButtonDefaults.primaryButtonColors(),
                            modifier = Modifier.size(56.dp),
                        ) { Text("通话") }
                    }
                }
            }
        }
    }
}

private fun statusLabel(state: CallState?): String = when (state) {
    CallState.DIALING -> "呼叫中…"
    CallState.IN_CALL -> "通话中"
    CallState.ENDED -> "已结束"
    else -> "与 AI 通话"
}
