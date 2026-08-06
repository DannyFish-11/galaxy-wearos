package com.galaxy.wear.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import com.galaxy.wear.auth.PairClaimClient
import kotlinx.coroutines.launch

/**
 * PairClaimScreen — 手表侧的接入界面。
 *
 * 用户要做的事
 * ============
 * 在桌面面板点「出示名片」，把那 6 位短码在这块表上输进来。就这一步。
 *
 * 换掉了什么
 * ==========
 * 此前是 OAuth 2.0 Device Flow：手表显示一个码，你得**去另一块屏幕上**打开一个
 * 网址、登录、输码、授权，手表这边最长轮询 30 分钟。也就是说「手表单独用」这件事
 * 在接入阶段就不成立 —— 必须先有手机或电脑。
 *
 * 现在反过来：桌面出示、手表领取。一次 HTTP，没有轮询，没有第二块屏幕。
 *
 * 为什么敢让人在手表上输 6 个字符
 * ==============================
 * 短码的字符集刻意去掉了 0/O/1/I/L 这些易混字符（见 V2 侧
 * `core/agent_card._CODE_ALPHABET`），就是为了能被口述、能在小屏上输。
 * 这里再配一个 5×6 的字符网格，不依赖系统键盘 —— 圆屏上的系统键盘会盖掉大半个
 * 屏幕，输到一半看不见已输入的内容。
 */
@Composable
fun PairClaimScreen(
    pairClaimClient: PairClaimClient,
    serverUrl: String,
    deviceId: String,
    deviceName: String? = null,
    onPaired: () -> Unit,
    onCancelled: () -> Unit,
) {
    val scope = rememberCoroutineScope()

    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colors.background)) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            if (busy) {
                CircularProgressIndicator(modifier = Modifier.size(32.dp))
                Spacer(Modifier.height(8.dp))
                Text("正在接入…", style = MaterialTheme.typography.body2)
                return@Column
            }

            Text(
                "输入桌面上的短码",
                style = MaterialTheme.typography.caption1,
                color = MaterialTheme.colors.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(6.dp))

            // 已输入的码：等宽 + 空位占位，让人一眼看出还差几个
            Text(
                text = (code + "······").take(6).toList().joinToString(" "),
                style = MaterialTheme.typography.title2,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))

            error?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.caption2,
                    color = MaterialTheme.colors.error,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(6.dp))
            }

            // 字符网格。只列短码字符集里真实存在的字符 —— 列出 0/O/1/I/L 只会
            // 让人输进一个必然被判无效的码，而每次无效都会消耗服务端的节流额度。
            CodeGrid(
                enabled = code.length < 6,
                onChar = { c ->
                    if (code.length < 6) {
                        code += c
                        error = null
                    }
                },
            )

            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(
                    onClick = { if (code.isNotEmpty()) code = code.dropLast(1) },
                    enabled = code.isNotEmpty(),
                    colors = ButtonDefaults.secondaryButtonColors(),
                    modifier = Modifier.size(width = 56.dp, height = 32.dp),
                ) { Text("删", style = MaterialTheme.typography.caption2) }

                Button(
                    // 不满 6 位就别让点：点了必然被服务端判无效，而每一次无效都会
                    // 记进那边按来源的节流，白白消耗重试额度。
                    enabled = code.length == 6,
                    onClick = {
                        busy = true
                        error = null
                        scope.launch {
                            val r = pairClaimClient.claim(
                                serverUrl = serverUrl,
                                deviceId = deviceId,
                                deviceName = deviceName,
                                code = code,
                            )
                            busy = false
                            if (r.ok) {
                                onPaired()
                            } else {
                                // 失败原因分档：可重输 / 要等 / 网络问题 —— 下一步
                                // 该做的事完全不同，糊成一句"失败"等于没说。
                                error = when (r.error) {
                                    "too_many_attempts" -> "错太多次，等几分钟"
                                    "network_error" -> "连不上网关"
                                    "no_token_issued" -> "被拒绝接入"
                                    "missing_device_id" -> "本机标识为空"
                                    else -> "码无效或已过期"
                                }
                                code = ""
                            }
                        }
                    },
                    modifier = Modifier.size(width = 72.dp, height = 32.dp),
                ) { Text("接入", style = MaterialTheme.typography.caption2) }

                Button(
                    onClick = onCancelled,
                    colors = ButtonDefaults.secondaryButtonColors(),
                    modifier = Modifier.size(width = 56.dp, height = 32.dp),
                ) { Text("退", style = MaterialTheme.typography.caption2) }
            }
        }
    }
}

/**
 * 短码字符网格。
 *
 * 字符集与 V2 侧 `core/agent_card._CODE_ALPHABET` **必须一致**：那边发什么，
 * 这边就得能输什么。写成两份的话，某个字符在那边能签出来、在这边输不进去，
 * 表现成"这个码怎么都输不完整"。
 */
@Composable
private fun CodeGrid(enabled: Boolean, onChar: (Char) -> Unit) {
    val rows = CODE_ALPHABET.chunked(6)
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                row.forEach { ch ->
                    Box(
                        modifier = Modifier
                            .size(26.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(
                                if (enabled) MaterialTheme.colors.surface
                                else MaterialTheme.colors.surface.copy(alpha = 0.4f)
                            )
                            .clickable(enabled = enabled) { onChar(ch) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            ch.toString(),
                            fontSize = 13.sp,
                            fontFamily = FontFamily.Monospace,
                            color = if (enabled) MaterialTheme.colors.onSurface else Color.Gray,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 与 V2 侧 `core/agent_card._CODE_ALPHABET` 逐字符一致。
 * 去掉了 0/O/1/I/L —— 它们在小屏上认不准，口述时也分不开。
 */
internal const val CODE_ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ"
